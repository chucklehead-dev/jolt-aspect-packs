(ns jolt.aspect-packs.core-async.provider-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [hegel.clojure-test :as hegel-test]
            [hegel.generator :as g]
            [jolt.fibers :as fibers]
            [jolt.aspect-packs.core-async.model :as model]
            [jolt.aspect-packs.core-async.provider :as provider]
            [jolt.aspect-packs.history :as history]))

(defn- join-point [id]
  {:id id
   :site-id (str "core-async-provider-test/" (name id))
   :build-identity "core-async-provider-test-build"})

(defn- invoke! [journal id args f]
  (binding [history/*journal* journal]
    (provider/around-operation (join-point id) args f)))

(defn- run-action! [journal channel backend [operation value]]
  (case backend
    :thread (Thread/yield)
    :fiber (fibers/yield))
  (case operation
    :offer (invoke! journal :core-async/offer [channel value]
                    #(async/offer! channel value))
    :poll (invoke! journal :core-async/poll [channel]
                   #(async/poll! channel))
    :close (invoke! journal :core-async/close [channel]
                    #(async/close! channel))))

(defn- run-concurrent-history! [backend actors]
  (let [journal (history/journal)
        channel (async/chan 2)
        start (promise)
        body (fn [actions]
               @start
               (doseq [action actions]
                 (run-action! journal channel backend action))
               :done)
        workers (mapv (fn [actions]
                        (case backend
                          :thread (future (body actions))
                          :fiber (fibers/spawn #(body actions))))
                      actors)]
    (deliver start true)
    (doseq [worker workers]
      (is (= :done
             (case backend
               :thread (deref worker 5000 ::timed-out)
               :fiber (fibers/join worker 5000 ::timed-out)))))
    (history/assert-complete! journal)
    (let [events (history/events journal)
          channel-token (get-in events [0 :input :channel])]
      (model/check! (model/initial-state {channel-token 2}) events)
      events)))

(deftest captures-a-privacy-safe-fixed-buffer-history
  (let [journal (history/journal)
        channel (async/chan 2)
        first-value (Object.)
        second-value (Object.)]
    (is (true? (invoke! journal :core-async/offer [channel first-value]
                        #(async/offer! channel first-value))))
    (is (true? (invoke! journal :core-async/offer [channel second-value]
                        #(async/offer! channel second-value))))
    (is (nil? (invoke! journal :core-async/offer [channel :private-full]
                       #(async/offer! channel :private-full))))
    (is (identical? first-value
                    (invoke! journal :core-async/poll [channel]
                             #(async/poll! channel))))
    (is (nil? (invoke! journal :core-async/close [channel]
                       #(async/close! channel))))
    (is (false? (invoke! journal :core-async/offer [channel :private-closed]
                         #(async/offer! channel :private-closed))))
    (is (identical? second-value
                    (invoke! journal :core-async/poll [channel]
                             #(async/poll! channel))))
    (is (nil? (invoke! journal :core-async/poll [channel]
                       #(async/poll! channel))))
    (let [events (history/events journal)]
      (is (true? (history/assert-complete! journal)))
      (is (= 16 (count events)))
      (let [channel-token (get-in events [0 :input :channel])]
        (is (string? channel-token))
        (is (some? (re-matches #"opaque-[0-9]+" channel-token)))
        (is (not (identical? channel channel-token)))
        (is (every? #(= channel-token (get-in % [:input :channel]))
                    (filter #(= :invoke (:phase %)) events)))
        (is (some? (model/check!
                    (model/initial-state {channel-token 2}) events))))
      (is (not (.contains (pr-str events) (pr-str channel))))
      (doseq [private-value [first-value second-value :private-full
                             :private-closed]]
        (is (not (.contains (pr-str events) (pr-str private-value))))))))

(deftest absent-journal-is-fully-inert
  (let [called (atom 0)
        result (Object.)]
    (is (identical?
         result
         (provider/around-operation
          {:id :not-even-a-known-operation} [:uninspected]
          (fn [] (swap! called inc) result))))
    (is (= 1 @called))))

(deftest malformed-bound-invocations-fail-before-the-operation
  (doseq [[id args]
          [[:core-async/offer [:only-channel]]
           [:core-async/poll []]
           [:core-async/close [:channel :extra]]
           [:core-async/unknown [:channel]]]]
    (testing id
      (let [journal (history/journal)
            called? (atom false)]
        (is (thrown? Exception
                     (invoke! journal id args #(reset! called? true))))
        (is (false? @called?))
        (is (empty? (history/events journal)))))))

(deftest generated-thread-and-fiber-histories-are-linearizable
  (hegel-test/with
    {:name "core-async-fixed-buffer-history-v1"
     :test-cases 100
     :database ""
     :derandomize? true
     :verbosity :quiet}
    [actors
     (g/vector
      {:min-size 2 :max-size 2}
      (g/vector
       {:min-size 1 :max-size 4}
       (g/tuple (g/sampled-from [:offer :poll :close])
                (g/integer 0 7))))]
    (doseq [backend [:thread :fiber]]
      (let [events (run-concurrent-history! backend actors)]
        (is (<= (count events) 16))
        (is (= (range 1 (inc (count events))) (map :seq events)))))))
