(ns jolt.aspect-packs.core-async.perturbation-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [hegel.clojure-test :as hegel-test]
            [hegel.generator :as g]
            [jolt.aspects :as aspects]
            [jolt.fibers :as fibers]
            [jolt.aspect-packs.core-async.faults :as faults]
            [jolt.aspect-packs.core-async.model :as model]
            [jolt.aspect-packs.core-async.provider :as provider]
            [jolt.aspect-packs.history :as history]))

(def ^:private capacity 1)
(def ^:private timed-out ::timed-out)

(defn- join-point [actor operation]
  {:id (case operation
         :offer :core-async/offer
         :poll :core-async/poll
         :close :core-async/close)
   :site-id (str "core-async-perturbation/actor-" actor "/" (name operation))
   :build-identity "core-async-perturbation-build"})

(defn- baseline-action-gen []
  (g/fmap (fn [[operation value]]
            {:operation operation :value value :effect :none})
          (g/tuple (g/sampled-from [:offer :poll :close])
                   (g/integer 0 3))))

(defn- faulted-action-gen [family]
  (g/bind
   (fn [operation]
     (g/fmap
      (fn [[effect value]]
        {:operation operation :value value :effect effect})
      (g/tuple
       (g/sampled-from
        (case family
          :pre-target [:return-before :throw-before]
          :post-target [:return-after :throw-after]
          :replacement [:replace-args]))
       (g/integer 0 3))))
   (g/sampled-from (if (= :replacement family)
                     [:offer]
                     [:offer :poll :close]))))

(defn- scenario-gen [family]
  ;; The first actor is always perturbed, so every generated case exercises the
  ;; control provider without assumptions or aggregate coverage counters.
  (g/tuple (faulted-action-gen family) (baseline-action-gen)))

(defn- original-value [actor value]
  [:original actor value])

(defn- replacement-value [actor value]
  [:replacement actor value])

(defn- evaluated-args [channel actor {:keys [operation value]}]
  (case operation
    :offer [channel (original-value actor value)]
    :poll [channel]
    :close [channel]))

(defn- execute-target! [operation target-observation target-count args]
  (swap! target-count inc)
  (let [result
        (case operation
          :offer (async/offer! (nth args 0) (nth args 1))
          :poll (async/poll! (nth args 0))
          :close (async/close! (nth args 0)))]
    (reset! target-observation
            {:result result
             :value (when (= :offer operation) (nth args 1))})
    result))

(defn- fault-action [channel actor {:keys [operation value effect]} error]
  (case effect
    :none nil
    :return-before {:effect effect :value [:forced actor effect]}
    :throw-before {:effect effect :error error}
    :return-after {:effect effect :value [:forced actor effect]}
    :throw-after {:effect effect :error error}
    :replace-args
    {:effect effect
     :args (case operation
             :offer [channel (replacement-value actor value)])}))

(defn- invoke-fault-outside-history!
  [journal join-point args target action decisions]
  (binding [history/*journal* journal]
    (faults/call-with-fault
     action decisions
     #(aspects/invoke-control
       faults/around-operation join-point args
       (fn [& current-args]
         (let [current-args (vec current-args)]
           (provider/around-operation
            join-point current-args
            #(apply target current-args))))))))

(defn- invoke-history-outside-fault!
  "Deliberately wrong provider order used only by the mutation control."
  [journal join-point args target action decisions]
  (binding [history/*journal* journal]
    (provider/around-operation
     join-point args
     #(faults/call-with-fault
       action decisions
       (fn []
         (aspects/invoke-control
          faults/around-operation join-point args target))))))

(defn- run-attempt! [journal channel decisions actor description]
  (let [{:keys [operation value effect]} description
        join-point (join-point actor operation)
        args (evaluated-args channel actor description)
        error (ex-info "generated injected fault"
                       {:actor actor :effect effect})
        action (fault-action channel actor description error)
        target-observation (atom nil)
        target-count (atom 0)
        target #(execute-target! operation target-observation target-count %&)
        application
        (try
          {:outcome :return
           :value (invoke-fault-outside-history!
                   journal join-point args target action decisions)}
          (catch Throwable observed
            {:outcome :throw :error observed}))]
    {:actor actor
     :operation operation
     :value value
     :effect effect
     :error error
     :target-count @target-count
     :target @target-observation
     :application application}))

(defn- start-worker [backend f]
  (case backend
    :thread (future (f))
    :fiber (fibers/spawn f)))

(defn- join-worker! [backend worker]
  (let [result (case backend
                 :thread (deref worker 5000 timed-out)
                 :fiber (fibers/join worker 5000 timed-out))]
    (when (= timed-out result)
      (throw (ex-info "generated perturbation worker timed out"
                      {:backend backend
                       :hegel/origin "core-async/perturbation-worker"})))
    result))

(defn- run-scenario! [backend descriptions]
  (let [journal (history/journal)
        channel (async/chan capacity)
        decisions (atom [])
        start (promise)
        workers
        (mapv
         (fn [actor description]
           (start-worker
            backend
            (fn []
              @start
              (case backend
                :thread (Thread/yield)
                :fiber (fibers/yield))
              (run-attempt! journal channel decisions actor description))))
         (range)
         descriptions)]
    (deliver start true)
    (try
      (let [ledger (mapv #(join-worker! backend %) workers)]
        {:backend backend
         :journal journal
         :events (history/events journal)
         :decisions @decisions
         :ledger ledger})
      (finally
        (async/close! channel)))))

(defn- skipped-effect? [effect]
  (contains? #{:return-before :throw-before} effect))

(defn- throwing-effect? [effect]
  (contains? #{:throw-before :throw-after} effect))

(defn- forced-return-effect? [effect]
  (contains? #{:return-before :return-after} effect))

(defn- decision-phase [effect]
  (case effect
    (:return-before :throw-before :replace-args) :before-target
    (:return-after :throw-after) :after-target))

(defn- expected-terminal [journal operation target]
  {:phase :return
   :value
   (case operation
     :offer {:result (cond
                       (true? (:result target)) :accepted
                       (false? (:result target)) :closed
                       (nil? (:result target)) :full)}
     :poll (if (nil? (:result target))
             {:result :empty}
             {:result :value
              :value (history/opaque-token! journal (:result target))})
     :close {:result :closed})})

(defn- operation-events-by-site [events]
  (->> events
       (group-by :operation-id)
       vals
       (map (fn [operation-events]
              [(->> operation-events
                    (filter #(= :invoke (:phase %)))
                    first
                    :site-id)
               (vec (sort-by :seq operation-events))]))
       (group-by first)))

(defn- assert-attempt! [{:keys [actor operation value effect error target-count
                                target application]}]
  (testing [actor operation effect]
    (is (= (if (skipped-effect? effect) 0 1) target-count))
    (if (skipped-effect? effect)
      (is (nil? target))
      (do
        (is (some? target))
        (when (= :offer operation)
          (is (= ((if (= :replace-args effect)
                    replacement-value
                    original-value)
                  actor value)
                 (:value target))))))
    (cond
      (throwing-effect? effect)
      (do
        (is (= :throw (:outcome application)))
        (is (identical? error (:error application))))

      (forced-return-effect? effect)
      (is (= {:outcome :return :value [:forced actor effect]}
             application))

      :else
      (is (= {:outcome :return :value (:result target)}
             application)))))

(defn- assert-scenario!
  [{:keys [backend journal events decisions ledger]}]
  (testing backend
    (is (true? (history/assert-complete! journal)))
    (doseq [attempt ledger]
      (assert-attempt! attempt))
    (let [executed (remove #(skipped-effect? (:effect %)) ledger)
          operations-by-site (operation-events-by-site events)
          faulted (first ledger)
          expected-decision
          {:operation (case (:operation faulted)
                        :offer :core-async/offer
                        :poll :core-async/poll
                        :close :core-async/close)
           :effect (:effect faulted)
           :phase (decision-phase (:effect faulted))}]
      (is (= [expected-decision] decisions))
      (is (= (* 2 (count executed)) (count events)))
      (is (= (range 1 (inc (count events))) (map :seq events)))
      (doseq [{:keys [actor operation target]} executed]
        (let [site-id (str "core-async-perturbation/actor-" actor "/"
                           (name operation))
              matches (get operations-by-site site-id)
              operation-events (second (first matches))
              [invoke terminal] operation-events]
          (is (= 1 (count matches)))
          (is (= 2 (count operation-events)))
          (is (= [:invoke :return] (mapv :phase operation-events)))
          (is (= (case operation
                   :offer :core-async/offer
                   :poll :core-async/poll
                   :close :core-async/close)
                 (:operation invoke)))
          (is (= (get-in events [0 :input :channel])
                 (get-in invoke [:input :channel])))
          (is (= (expected-terminal journal operation target)
                 (select-keys terminal [:phase :value])))
          (when (= :offer operation)
            (is (= (history/opaque-token! journal (:value target))
                   (get-in invoke [:input :value]))))))
      (let [channel-token (get-in events [0 :input :channel])
            witness (model/check! (model/initial-state
                                   {channel-token capacity}) events)]
        (is (= (count executed) (:operation-count witness)))))))

(defn- check-generated-family! [family]
  (hegel-test/with
    {:name (str "core-async-public-fault-history-v1-" (name family))
     :test-cases 60
     :database ""
     :derandomize? true
     :verbosity :quiet}
    [descriptions (scenario-gen family)]
    (doseq [backend [:thread :fiber]]
      (assert-scenario! (run-scenario! backend descriptions)))))

(deftest generated-pre-target-fault-histories-remain-sound
  (check-generated-family! :pre-target))

(deftest generated-post-target-fault-histories-remain-sound
  (check-generated-family! :post-target))

(deftest generated-replacement-fault-histories-remain-sound
  (check-generated-family! :replacement))

(deftest reversed-provider-order-mutation-is-observably-wrong
  (let [action {:effect :return-before :value false}]
    (testing "fault outside history skips the target without inventing history"
      (let [journal (history/journal)
            calls (atom 0)
            result
            (invoke-fault-outside-history!
             journal (join-point 0 :offer) [:channel :value]
             (fn [& _] (swap! calls inc) true) action (atom []))]
        (is (false? result))
        (is (zero? @calls))
        (is (empty? (history/events journal)))))
    (testing "history outside fault records a skipped call as an impossible close"
      (let [journal (history/journal)
            channel (async/chan 1)
            calls (atom 0)
            result
            (invoke-history-outside-fault!
             journal (join-point 0 :offer) [channel :value]
             (fn [& _] (swap! calls inc) true) action (atom []))
            events (history/events journal)
            channel-token (get-in events [0 :input :channel])]
        (is (false? result))
        (is (zero? @calls))
        (is (true? (history/assert-complete! journal)))
        (is (= {:result :closed} (get-in events [1 :value])))
        (is (nil? (model/linearization
                   (model/initial-state {channel-token 1}) events)))
        (async/close! channel)))))
