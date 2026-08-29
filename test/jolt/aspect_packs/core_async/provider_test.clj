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

(defn- invoke-callback! [journal id args f]
  (binding [history/*journal* journal
            history/*context-id* :core-async-callback-test]
    (provider/around-callback-operation (join-point id) args f)))

(defn- core-async-proceed [id]
  (case id
    :core-async/put
    (fn [[channel value callback on-caller?]]
      (async/put! channel value callback on-caller?))
    :core-async/take
    (fn [[channel callback on-caller?]]
      (async/take! channel callback on-caller?))))

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

(deftest absent-journal-callback-advice-is-fully-inert
  (let [called (atom 0)
        result (Object.)]
    (is (identical?
         result
         (provider/around-callback-operation
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

(deftest callback-advice-replaces-only-the-callback-and-preserves-duplicates
  (let [journal (history/journal)
        channel (Object.)
        value (Object.)
        result (Object.)
        callback-result (Object.)
        calls (atom [])
        callback (fn [argument]
                   (swap! calls conj
                          {:argument argument
                           :journal? (identical? journal history/*journal*)
                           :parent history/*parent-operation-id*
                           :context history/*context-id*})
                   callback-result)
        args [channel value callback true]
        observed
        (invoke-callback!
         journal :core-async/put args
         (fn [replacement]
           (is (= 4 (count replacement)))
           (is (identical? channel (nth replacement 0)))
           (is (identical? value (nth replacement 1)))
           (is (not (identical? callback (nth replacement 2))))
           (is (true? (nth replacement 3)))
           (is (identical? callback-result ((nth replacement 2) true)))
           (is (identical? callback-result ((nth replacement 2) true)))
           result))]
    (is (identical? result observed))
    (is (= 2 (count @calls)) "duplicate target delivery remains user-visible")
    (is (every? #(= {:argument true :journal? true :parent 0
                     :context :core-async-callback-test}
                    %)
                @calls))
    (is (= [:invoke :return] (mapv :phase (history/events journal))))
    (let [events (history/events journal)
          channel-token (get-in events [0 :input :channel])
          value-token (get-in events [0 :input :value])]
      (is (= {:channel channel-token
              :value value-token
              :on-caller? true}
             (:input (first events))))
      (is (some? (re-matches #"opaque-[0-9]+" channel-token)))
      (is (some? (re-matches #"opaque-[0-9]+" value-token)))
      (is (not (identical? channel channel-token)))
      (is (not (identical? value value-token)))
      (is (= {:result :accepted
              :carrier {:parent-operation-id 0
                        :context-id :core-async-callback-test}}
             (:value (second events))))
      (is (not-any? #(or (identical? channel %)
                         (identical? value %)
                         (identical? callback %))
                    (tree-seq coll? seq events))))
    (is (true? (history/assert-complete! journal)))))

(deftest nil-callback-retains-target-semantics-and-closes-history
  (doseq [operation [:core-async/put :core-async/take]]
    (let [journal (history/journal)
          channel (async/chan 1)
          value :nil-callback-value]
      (when (= operation :core-async/take)
        (async/offer! channel value))
      (is (= (if (= operation :core-async/put) true nil)
             (invoke-callback!
              journal operation
              (case operation
                :core-async/put [channel value nil true]
                :core-async/take [channel nil true])
              (core-async-proceed operation))))
      (is (= [:invoke :return]
             (mapv :phase (history/events journal))))
      (is (true? (history/assert-complete! journal)))
      (async/close! channel))))

(deftest pending-callback-outlives-advice-and-restores-its-carrier
  (let [journal (history/journal)
        callback-result (Object.)
        callback-observation (promise)
        callback (fn [value]
                   (deliver callback-observation
                            {:value value
                             :journal? (identical? journal history/*journal*)
                             :parent history/*parent-operation-id*
                             :context history/*context-id*})
                   callback-result)
        captured (atom nil)
        target-result (Object.)]
    (is (identical?
         target-result
         (invoke-callback!
          journal :core-async/take [:channel callback false]
          (fn [replacement]
            (reset! captured (nth replacement 1))
            target-result))))
    (is (= [0] (history/open-operation-ids journal)))
    (is (identical? callback-result (@captured :delivered)))
    (is (= {:value :delivered :journal? true :parent 0
            :context :core-async-callback-test}
           (deref callback-observation 1000 ::timed-out)))
    (is (true? (history/assert-complete! journal)))))

(deftest callback-and-target-throwables-retain-identity
  (let [callback-error (ex-info "callback" {:source :callback})
        callback-journal (history/journal)
        observed
        (try
          (invoke-callback!
           callback-journal :core-async/take
           [:channel (fn [_] (throw callback-error)) true]
           (fn [replacement] ((nth replacement 1) :value)))
          nil
          (catch Throwable error error))]
    (is (identical? callback-error observed))
    (is (= [:invoke :return]
           (mapv :phase (history/events callback-journal))))
    (is (true? (history/assert-complete! callback-journal))))
  (let [target-error (ex-info "target" {:source :target})
        target-journal (history/journal)
        observed
        (try
          (invoke-callback!
           target-journal :core-async/put
           [:channel :value identity true]
           (fn [_] (throw target-error)))
          nil
          (catch Throwable error error))]
    (is (identical? target-error observed))
    (is (= [:invoke :throw]
           (mapv :phase (history/events target-journal))))
    (is (= {:result :target-threw
            :error-type (str (type target-error))}
           (get-in (history/events target-journal) [1 :value])))
    (is (not-any? #(identical? target-error %)
                  (tree-seq coll? seq (history/events target-journal))))
    (is (true? (history/assert-complete! target-journal)))))

(deftest malformed-callback-invocations-fail-before-the-operation
  (doseq [[id args]
          [[:core-async/put [:channel :value identity]]
           [:core-async/take [:channel identity]]
           [:core-async/unknown [:channel identity true]]]]
    (testing id
      (let [journal (history/journal)
            called? (atom false)]
        (is (thrown? Exception
                     (invoke-callback! journal id args
                                       (fn [_] (reset! called? true)))))
        (is (false? @called?))
        (is (empty? (history/events journal)))))))

(deftest immediate-and-pending-callback-placement-is-observable
  (doseq [operation [:core-async/put :core-async/take]
          pending? [false true]
          on-caller? [true false]]
    (testing [operation (if pending? :pending :immediate) on-caller?]
      (let [journal (history/journal)
            channel (async/chan 1)
            payload (Object.)
            caller (Thread/currentThread)
            callback-count (atom 0)
            completed (promise)
            callback
            (fn [value]
              (swap! callback-count inc)
              (deliver completed
                       {:value value
                        :thread (Thread/currentThread)
                        :journal? (identical? journal history/*journal*)
                        :parent history/*parent-operation-id*
                        :context history/*context-id*})
              :callback-return)
            _ (when (and (= :core-async/put operation) pending?)
                (async/offer! channel :occupied))
            _ (when (and (= :core-async/take operation) (not pending?))
                (async/offer! channel payload))
            args (case operation
                   :core-async/put [channel payload callback on-caller?]
                   :core-async/take [channel callback on-caller?])
            target-result (invoke-callback!
                           journal operation args
                           (core-async-proceed operation))]
        (when pending?
          (case operation
            :core-async/put (is (= :occupied (async/poll! channel)))
            :core-async/take (is (true? (async/offer! channel payload)))))
        (let [observation (deref completed 5000 ::timed-out)
              events (history/events journal)
              operation-id (:operation-id (first events))]
          (is (not= ::timed-out observation))
          (is (= 1 @callback-count))
          (is (= (if (= :core-async/put operation) true nil)
                 target-result))
          (is (= (if (= :core-async/put operation) true payload)
                 (:value observation)))
          (is (true? (:journal? observation)))
          (is (= operation-id (:parent observation)))
          (is (= :core-async-callback-test (:context observation)))
          ;; core.async promises caller-thread delivery only when on-caller? is
          ;; true AND the operation completes immediately. Pending + true is
          ;; intentionally unconstrained: an implementation may use a dispatch
          ;; worker or run on the thread that completes the operation.
          (cond
            (and on-caller? (not pending?))
            (is (identical? caller (:thread observation)))

            (not on-caller?)
            (is (not (identical? caller (:thread observation)))))
          (is (= [:invoke :return] (mapv :phase events)))
          (is (= {:parent-operation-id operation-id
                  :context-id :core-async-callback-test}
                 (get-in events [1 :value :carrier])))
          (is (= (if (= :core-async/put operation)
                   {:result :accepted}
                   {:result :value
                    :value (get-in events [1 :value :value])})
                 (dissoc (:value (second events)) :carrier)))
          (when (= :core-async/take operation)
            (is (string? (get-in events [1 :value :value])))
            (is (not (identical? payload
                                  (get-in events [1 :value :value])))))
          (is (true? (history/assert-complete! journal))))
        (async/close! channel)))))

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
