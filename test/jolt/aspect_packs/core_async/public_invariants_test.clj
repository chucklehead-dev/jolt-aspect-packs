(ns jolt.aspect-packs.core-async.public-invariants-test
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

(def ^:private timed-out ::timed-out)

(defn- join-point [id site]
  {:id id
   :site-id (str "core-async-public-invariants/" site)
   :build-identity "core-async-public-invariants-build"})

(defn- invoke! [journal id site args target]
  (binding [history/*journal* journal]
    (provider/around-operation (join-point id site) args target)))

(defn- callback-proceed [id]
  (case id
    :core-async/put
    (fn [[channel value callback on-caller?]]
      (async/put! channel value callback on-caller?))

    :core-async/take
    (fn [[channel callback on-caller?]]
      (async/take! channel callback on-caller?))))

(defn- callback! [journal id site args]
  (binding [history/*journal* journal
            history/*context-id* :core-async-public-invariants]
    (provider/around-callback-operation
     (join-point id site) args (callback-proceed id))))

(defn- callback-with-fault! [journal id site args action decisions]
  (let [point (join-point id site)]
    (binding [history/*journal* journal
              history/*context-id* :core-async-public-invariants]
      (faults/call-with-fault
       action decisions
       #(aspects/invoke-control
         faults/around-callback-operation point args
         (fn [& current-args]
           (provider/around-callback-operation
            point (vec current-args) (callback-proceed id))))))))

(defn- await! [completion origin]
  (let [result (deref completion 5000 timed-out)]
    (when (= timed-out result)
      (throw (ex-info "core.async public invariant timed out"
                      {:hegel/origin origin})))
    result))

(defn- start-worker [backend f]
  (let [guarded (fn []
                  (try
                    {:worker/outcome :return :value (f)}
                    (catch Throwable error
                      {:worker/outcome :throw :error error})))]
    (case backend
      :thread (future (guarded))
      :fiber (fibers/spawn guarded))))

(defn- join-worker! [backend worker]
  (let [result (case backend
                 :thread (deref worker 7000 timed-out)
                 :fiber (fibers/join worker 7000 timed-out))]
    (when (= timed-out result)
      (throw (ex-info "core.async public invariant worker timed out"
                       {:backend backend
                        :hegel/origin "core-async/public-invariant-worker"})))
    (if (= :throw (:worker/outcome result))
      (throw (:error result))
      (:value result))))

(defn- run-worker! [backend f]
  (join-worker! backend (start-worker backend f)))

(defn- registration-ledger-valid?
  [{:keys [operation pending-before-close pending-after-close callback-results
           drained expected-drained decisions]}]
  (and pending-before-close
       (= 1 (count callback-results))
       (= [{:operation (case operation
                         :put :core-async/put
                         :take :core-async/take)
            :effect :barrier-after :phase :before-barrier}
           {:operation (case operation
                         :put :core-async/put
                         :take :core-async/take)
            :effect :barrier-after :phase :after-barrier}]
          decisions)
       (case operation
         :put (and (= [true] callback-results)
                   pending-after-close
                   (= expected-drained drained))
         :take (= [nil] callback-results)
         false)))

(defn- assert-target-registered-before-close! [events operation]
  (let [trace (model/callback-trace events)
        site (str "core-async-public-invariants/"
                  (case operation :put "pending-put" :take "pending-take"))
        parent (first (filter #(= site (get-in % [:invoke :site-id]))
                              (:logical-operations trace)))
        target (first (filter #(= (:operation-id parent)
                                  (get-in % [:invoke :parent-operation-id]))
                              (:target-operations trace)))
        close (first (filter #(= :core-async/close (:operation %))
                             (:logical-operations trace)))]
    (is (some? parent))
    (is (some? target))
    (is (some? close))
    (is (< (:terminal-seq target) (:invoke-seq close)))))

(defn- run-registration-case! [backend capacity operation on-caller? value]
  (let [journal (history/journal)
        channel (async/chan capacity)
        callback-results (atom [])
        completion (promise)
        callback (fn [result]
                   (swap! callback-results conj result)
                   (deliver completion result))
        arrived (promise)
        release (promise)
        decisions (atom [])
        site (case operation :put "pending-put" :take "pending-take")]
    (try
      (when (and (= :put operation) (= 1 capacity))
        (let [prefill (promise)]
          (callback! journal :core-async/put "prefill"
                     [channel :buffered #(deliver prefill %) true])
          (when-not (true? (await! prefill
                                   "core-async/registration-prefill"))
            (throw (ex-info "prefill was not accepted" {})))))
      (let [worker
            (start-worker
             backend
             #(callback-with-fault!
               journal
               (case operation :put :core-async/put :take :core-async/take)
               site
               (case operation
                 :put [channel value callback on-caller?]
                 :take [channel callback on-caller?])
               {:effect :barrier-after
                :arrived arrived :release release :timeout-ms 5000}
               decisions))]
        (await! arrived "core-async/registration-barrier-arrival")
        (let [pending-before-close (not (realized? completion))]
          (invoke! journal :core-async/close "close" [channel]
                   #(async/close! channel))
          (let [pending-after-close
                (when (= :put operation) (not (realized? completion)))]
            (deliver release true)
            (let [target-result (join-worker! backend worker)
                drained
                (when (= :put operation)
                  (do
                    (when (= 1 capacity)
                      (let [take-completion (promise)]
                        (callback! journal :core-async/take "drain-buffered"
                                   [channel #(deliver take-completion %) true])
                        (when-not (= :buffered
                                     (await! take-completion
                                             "core-async/drain-buffered"))
                          (throw (ex-info "buffered value was not first" {})))))
                    (let [take-completion (promise)
                          _ (callback! journal :core-async/take "drain-pending"
                                       [channel #(deliver take-completion %) true])
                          observed (await! take-completion
                                           "core-async/drain-pending")]
                      (when-not (= value observed)
                        (throw (ex-info "pending put lost ownership"
                                        {:expected value :observed observed})))
                      observed)))]
            (when-not (= (if (= :put operation) true nil) target-result)
              (throw (ex-info "callback target returned the wrong result"
                              {:operation operation :result target-result})))
            (await! completion "core-async/registration-callback")
            (let [closed-completion (promise)]
              (callback! journal :core-async/take "observe-closed"
                         [channel #(deliver closed-completion %) true])
              (when-not (nil? (await! closed-completion
                                      "core-async/observe-closed"))
                (throw (ex-info "closed channel produced an extra value" {}))))
            (history/assert-complete! journal)
            (let [events (history/events journal)
                  channel-token (get-in events [0 :input :channel])
                  ledger {:operation operation
                          :pending-before-close pending-before-close
                          :pending-after-close pending-after-close
                          :callback-results @callback-results
                          :drained drained
                          :expected-drained value
                          :decisions @decisions}]
              (model/check-callback!
               (model/callback-initial-state {channel-token capacity}) events)
              {:events events :ledger ledger})))))
      (finally
        (deliver release true)
        (async/close! channel)))))

(deftest generated-registration-before-close-preserves-public-ownership
  (hegel-test/with
    {:name "core-async-registration-before-close-v1"
     :test-cases 60
     :database ""
     :derandomize? true
     :verbosity :quiet}
    [description
     (g/fmap (fn [[on-caller? value]]
               {:on-caller? on-caller?
                :value [:pending value]})
             (g/tuple (g/boolean)
                      (g/integer 0 7)))]
    (doseq [backend [:thread :fiber]
            capacity [0 1]
            ;; Both ownership directions run in every generated case; neither
            ;; relies on aggregate generator coverage.
            operation [:put :take]]
      (let [{:keys [events ledger]}
            (run-registration-case!
             backend capacity operation
             (:on-caller? description) (:value description))]
        (is (registration-ledger-valid? ledger))
        (assert-target-registered-before-close! events operation)
        (is (= (range 1 (inc (count events))) (map :seq events)))))))

(defn- completion-ledger-valid? [{:keys [admitted order callbacks]}]
  (let [expected-steps (mapv (fn [value] [:step value]) admitted)
        completion-indexes (keep-indexed #(when (= :complete %2) %1) order)]
    (and (= (vec (repeat (count admitted) true)) callbacks)
         (= 1 (count completion-indexes))
         (= expected-steps (vec (take (first completion-indexes) order)))
         (= (inc (count expected-steps)) (count order)))))

(defn- recording-xform [order]
  (fn [rf]
    (fn
      ([] (rf))
      ([result]
       (swap! order conj :complete)
       (rf result))
      ([result input]
       (swap! order conj [:step input])
       (rf result input)))))

(defn- run-completion-case! [backend capacity pending-values on-caller?]
  (run-worker!
   backend
   (fn []
     (let [journal (history/journal)
           order (atom [])
           channel (async/chan capacity (recording-xform order))
           admitted (into [:buffered] pending-values)
           completions (atom [])]
       (try
         (let [completion (promise)]
           (swap! completions conj completion)
           (when-not
            (true?
             (callback! journal :core-async/put "transformed-prefill"
                        [channel :buffered #(deliver completion %) true]))
            (throw (ex-info "transformed prefill did not register" {})))
           (when-not (true? (await! completion
                                    "core-async/transformed-prefill"))
             (throw (ex-info "transformed prefill was not admitted" {}))))
         (doseq [[index value] (map-indexed vector pending-values)]
           (let [completion (promise)]
             (swap! completions conj completion)
             (when-not
              (true?
               (callback! journal :core-async/put (str "pending-put-" index)
                          [channel value #(deliver completion %) on-caller?]))
              (throw (ex-info "pending transformed put did not register" {})))))
         (invoke! journal :core-async/close "transformed-close" [channel]
                  #(async/close! channel))
         (let [observed
               (reduce
                (fn [values index]
                  (let [completion (promise)]
                    (callback! journal :core-async/take
                               (str "transformed-drain-" index)
                               [channel #(deliver completion %) true])
                    (conj values
                          (await! completion
                                  "core-async/transformed-drain"))))
                []
                (range (count admitted)))
               callbacks
               (reduce (fn [values completion]
                         (conj values
                               (await! completion
                                       "core-async/transformed-put")))
                       []
                       @completions)
               closed-completion (promise)
               _ (callback! journal :core-async/take "transformed-closed"
                            [channel #(deliver closed-completion %) true])
               after (await! closed-completion "core-async/transformed-closed")]
           (when-not (= admitted observed)
             (throw (ex-info "transformed channel changed FIFO admission"
                             {:expected admitted :observed observed})))
           (when-not (nil? after)
             (throw (ex-info "transformed channel did not finish closed" {})))
           (history/assert-complete! journal)
           (let [events (history/events journal)
                 channel-token (get-in events [0 :input :channel])]
             (model/check-callback!
              (model/callback-initial-state {channel-token capacity}) events)
             {:events events
              :ledger {:admitted admitted
                       :order @order
                       :callbacks callbacks}}))
         (finally
           (async/close! channel)))))))

(deftest generated-admitted-inputs-step-before-exactly-once-completion
  (hegel-test/with
    {:name "core-async-transformed-close-drain-v1"
     :test-cases 60
     :database ""
     :derandomize? true
     :verbosity :quiet}
    [description
     (g/fmap (fn [[values on-caller?]]
               {:pending-values (mapv #(vector :pending %) values)
                :on-caller? on-caller?})
             (g/tuple (g/vector {:size 1}
                                (g/integer 0 7))
                      (g/boolean)))]
    (doseq [backend [:thread :fiber]
            ;; On Jolt a transformed capacity-zero channel steps and enqueues
            ;; the put immediately, so it cannot reach the pending-input
            ;; schedule this property is intended to check.
            capacity [1]]
      (let [{:keys [events ledger]}
            (run-completion-case! backend capacity
                                  (:pending-values description)
                                  (:on-caller? description))]
        (is (completion-ledger-valid? ledger))
        (is (= (range 1 (inc (count events))) (map :seq events)))))))

(deftest synthetic-ledger-mutation-controls-reject-broken-oracles
  (let [decisions [{:operation :core-async/put
                    :effect :barrier-after :phase :before-barrier}
                   {:operation :core-async/put
                    :effect :barrier-after :phase :after-barrier}]
        valid {:operation :put :pending-before-close true
               :pending-after-close true
               :callback-results [true]
               :drained :pending :expected-drained :pending
               :decisions decisions}]
    (testing "an admitted put with missing or wrong drained ownership is rejected"
      (is (registration-ledger-valid? valid))
      (is (not (registration-ledger-valid?
                (assoc valid :drained nil))))
      (is (not (registration-ledger-valid?
                (assoc valid :drained :wrong)))))
    (testing "success at close before a take is rejected"
      (is (not (registration-ledger-valid?
                (assoc valid :pending-after-close false)))))
    (testing "missing, duplicate, and false callbacks are independently rejected"
      (is (not (registration-ledger-valid?
                (assoc valid :callback-results []))))
      (is (not (registration-ledger-valid?
                (assoc valid :callback-results [true true]))))
      (is (not (registration-ledger-valid?
                (assoc valid :callback-results [false]))))))
  (testing "early and duplicate completion are observable"
    (let [valid {:admitted [:a :b]
                 :callbacks [true true]
                 :order [[:step :a] [:step :b] :complete]}]
      (is (completion-ledger-valid? valid))
      (is (not (completion-ledger-valid?
                (assoc valid :order [[:step :a] :complete [:step :b]]))))
      (is (not (completion-ledger-valid?
                (update valid :order conj :complete)))))))
