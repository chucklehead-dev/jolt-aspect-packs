(ns jolt.aspect-packs.core-async.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.aspect-packs.core-async.model :as model]))

(defn- invoke [seq operation-id operation input]
  {:seq seq :operation-id operation-id :phase :invoke
   :operation operation :input input})

(defn- returned [seq operation-id value]
  {:seq seq :operation-id operation-id :phase :return :value value})

(defn- thrown [seq operation-id value]
  {:seq seq :operation-id operation-id :phase :throw :value value})

(defn- callback-invoke [seq operation-id operation input]
  (assoc (invoke seq operation-id operation input)
         :context-id :callback-model-test))

(defn- callback-return [seq operation-id value]
  (returned seq operation-id
            (assoc value :carrier
                   {:parent-operation-id operation-id
                    :context-id :callback-model-test})))

(defn- callback-composite [logical-events]
  (let [first-target-id
        (inc (reduce max -1 (map :operation-id logical-events)))]
    (loop [remaining logical-events
           next-target-id first-target-id
           result []]
      (if-let [event (first remaining)]
        (if (and (= :invoke (:phase event))
                 (contains? #{:core-async/put :core-async/take}
                            (:operation event)))
          (let [target-operation
                (case (:operation event)
                  :core-async/put :core-async/put-target
                  :core-async/take :core-async/take-target)
                target-invoke
                {:operation-id next-target-id
                 :parent-operation-id (:operation-id event)
                 :context-id (:context-id event)
                 :phase :invoke
                 :operation target-operation
                 :input (:input event)}
                target-return
                {:operation-id next-target-id :phase :return
                 :value {:result :target-returned}}]
            (recur (rest remaining) (inc next-target-id)
                   (conj result event target-invoke target-return)))
          (recur (rest remaining) next-target-id (conj result event)))
        (mapv (fn [index event] (assoc event :seq (inc index)))
              (range) result)))))

(def initial (model/initial-state {:channel-a 2 :channel-b 1}))

(deftest legal-sequential-fixed-buffer-history
  (let [events [(invoke 1 0 :core-async/offer
                        {:channel :channel-a :value :one})
                (returned 2 0 {:result :accepted})
                (invoke 3 1 :core-async/offer
                        {:channel :channel-a :value :two})
                (returned 4 1 {:result :accepted})
                (invoke 5 2 :core-async/offer
                        {:channel :channel-a :value :three})
                (returned 6 2 {:result :full})
                (invoke 7 3 :core-async/poll {:channel :channel-a})
                (returned 8 3 {:result :value :value :one})]]
    (is (some? (model/check! initial events)))))

(deftest overlapping-operations-may-linearize-outside-invocation-order
  (let [events [(invoke 1 0 :core-async/poll {:channel :channel-a})
                (invoke 2 1 :core-async/offer
                        {:channel :channel-a :value :one})
                (returned 3 1 {:result :accepted})
                (returned 4 0 {:result :value :value :one})]
        witness (model/linearization initial events)]
    (is (= [1 0] (get-in witness [:partitions 0 :order])))))

(deftest close-is-idempotent-and-monotonic
  (let [legal [(invoke 1 0 :core-async/close {:channel :channel-a})
               (returned 2 0 {:result :closed})
               (invoke 3 1 :core-async/close {:channel :channel-a})
               (returned 4 1 {:result :closed})
               (invoke 5 2 :core-async/offer
                       {:channel :channel-a :value :late})
               (returned 6 2 {:result :closed})]
        corrupted (assoc-in legal [5 :value] {:result :accepted})]
    (is (some? (model/linearization initial legal)))
    (is (nil? (model/linearization initial corrupted)))))

(deftest fixed-buffer-is-fifo
  (let [prefix [(invoke 1 0 :core-async/offer
                        {:channel :channel-a :value :one})
                (returned 2 0 {:result :accepted})
                (invoke 3 1 :core-async/offer
                        {:channel :channel-a :value :two})
                (returned 4 1 {:result :accepted})]
        legal (conj prefix
                    (invoke 5 2 :core-async/poll {:channel :channel-a})
                    (returned 6 2 {:result :value :value :one}))
        corrupted (assoc-in legal [5 :value :value] :two)]
    (is (some? (model/linearization initial legal)))
    (is (nil? (model/linearization initial corrupted)))))

(deftest one-value-cannot-be-consumed-twice
  (let [events [(invoke 1 0 :core-async/offer
                        {:channel :channel-a :value :one})
                (returned 2 0 {:result :accepted})
                (invoke 3 1 :core-async/poll {:channel :channel-a})
                (returned 4 1 {:result :value :value :one})
                (invoke 5 2 :core-async/poll {:channel :channel-a})
                (returned 6 2 {:result :value :value :one})]]
    (is (nil? (model/linearization initial events)))))

(deftest malformed-and-thrown-terminals-are-rejected
  (doseq [events
          [[(invoke 1 0 :core-async/offer {:channel :channel-a})
            (returned 2 0 {:result :accepted})]
           [(invoke 1 0 :core-async/poll {:channel :channel-a :extra true})
            (returned 2 0 {:result :empty})]
           [(invoke 1 0 :core-async/close {:channel :channel-a})
            (returned 2 0 {:result :closed :extra true})]
           [(invoke 1 0 :core-async/poll {:channel :channel-a})
            (thrown 2 0 {:error :boom})]
           [(invoke 1 0 :core-async/poll {:channel :unknown})
            (returned 2 0 {:result :empty})]]]
    (is (nil? (model/linearization initial events))))
  (is (thrown? Exception
               (model/check!
                initial
                [(invoke 1 0 :core-async/poll {:channel :channel-a})
                 (returned 2 0 {:result :value :value :not-present})]))))

(deftest mutation-control-detects-corrupted-observation
  (let [legal [(invoke 1 0 :core-async/offer
                       {:channel :channel-b :value :one})
               (returned 2 0 {:result :accepted})
               (invoke 3 1 :core-async/poll {:channel :channel-b})
               (returned 4 1 {:result :value :value :one})]
        corrupted (assoc-in legal [3 :value :value] :mutated)]
    (is (some? (model/linearization initial legal)))
    (is (nil? (model/linearization initial corrupted)))))

(deftest initial-state-requires-positive-fixed-capacities
  (testing "capacity and channel token validation"
    (is (thrown? Exception (model/initial-state {:channel-a 0})))
    (is (thrown? Exception (model/initial-state {nil 1}))))
  (is (= {:channel-a {:capacity 1 :queue [] :closed? false}}
         (model/initial-state {:channel-a 1}))))

(deftest operation-bound-fails-before-search
  (let [events (vec
                (mapcat (fn [operation-id]
                          [(invoke (inc (* 2 operation-id)) operation-id
                                   :core-async/close {:channel :channel-a})
                           (returned (+ 2 (* 2 operation-id)) operation-id
                                     {:result :closed})])
                        (range 9)))
        failure (try
                  (model/check! initial events)
                  nil
                  (catch Throwable error error))]
    (is (= :hegel.history/operation-bound (:type (ex-data failure))))
    (is (= 8 (:hegel.history/max-operations (ex-data failure))))
    (is (= 16 (count (:hegel.history/events (ex-data failure)))))
    (is (true? (:hegel.history/evidence-truncated? (ex-data failure))))))

(deftest histories-are-partitioned-by-channel
  (let [events [(invoke 1 0 :core-async/offer
                        {:channel :channel-a :value :one})
                (returned 2 0 {:result :accepted})
                (invoke 3 1 :core-async/offer
                        {:channel :channel-b :value :two})
                (returned 4 1 {:result :accepted})
                (invoke 5 2 :core-async/poll {:channel :channel-a})
                (returned 6 2 {:result :value :value :one})
                (invoke 7 3 :core-async/poll {:channel :channel-b})
                (returned 8 3 {:result :value :value :two})]
        witness (model/check! initial events)]
    (is (= 4 (:operation-count witness)))
    (is (= #{:channel-a :channel-b}
           (set (map :partition (:partitions witness)))))
    (is (= {2 2}
           (frequencies (map #(count (:operations %))
                             (:partitions witness)))))))

(deftest callback-capacity-one-history-is-fifo-and-close-aware
  (let [initial (model/callback-initial-state {:channel 1})
        logical [(callback-invoke 1 0 :core-async/put
                                 {:channel :channel :value :one
                                  :on-caller? true})
                (callback-return 2 0 {:result :accepted})
                (callback-invoke 3 1 :core-async/take
                                 {:channel :channel :on-caller? false})
                (callback-return 4 1 {:result :value :value :one})
                (invoke 5 2 :core-async/close {:channel :channel})
                (returned 6 2 {:result :closed})
                (callback-invoke 7 3 :core-async/take
                                 {:channel :channel :on-caller? true})
                (callback-return 8 3 {:result :closed})]
        events (callback-composite logical)]
    (is (some? (model/check-callback! initial events)))
    (is (nil? (model/callback-linearization
               initial
               (callback-composite
                (assoc-in logical [3 :value :value] :wrong)))))))

(deftest overlapping-unbuffered-take-and-put-form-one-rendezvous
  (let [initial (model/callback-initial-state {:channel 0})
        events (callback-composite
                [(callback-invoke 1 0 :core-async/take
                                 {:channel :channel :on-caller? false})
                (callback-invoke 2 1 :core-async/put
                                 {:channel :channel :value :one
                                  :on-caller? false})
                (callback-return 3 1 {:result :accepted})
                 (callback-return 4 0 {:result :value :value :one})])
        witness (model/check-callback! initial events)]
    (is (= [1 0] (get-in witness [:partitions 0 :order])))))

(deftest close-may-linearize-before-an-overlapping-pending-operation
  (let [initial (model/callback-initial-state {:channel 0})
        events (callback-composite
                [(callback-invoke 1 0 :core-async/take
                                 {:channel :channel :on-caller? false})
                (invoke 2 1 :core-async/close {:channel :channel})
                (returned 3 1 {:result :closed})
                 (callback-return 4 0 {:result :closed})])
        witness (model/check-callback! initial events)]
    (is (= [1 0] (get-in witness [:partitions 0 :order])))))

(deftest close-may-linearize-before-an-overlapping-put
  (let [initial (model/callback-initial-state {:channel 0})
        events (callback-composite
                [(invoke 1 0 :core-async/close {:channel :channel})
                (callback-invoke 2 1 :core-async/put
                                 {:channel :channel :value :one
                                  :on-caller? false})
                (returned 3 0 {:result :closed})
                 (callback-return 4 1 {:result :closed})])
        witness (model/check-callback! initial events)]
    (is (= [0 1] (get-in witness [:partitions 0 :order])))))

(deftest close-preserves-a-preexisting-unbuffered-put-until-taken
  (let [initial (model/callback-initial-state {:channel 0})
        events (callback-composite
                [(callback-invoke 1 0 :core-async/put
                                 {:channel :channel :value :one
                                  :on-caller? false})
                (invoke 2 1 :core-async/close {:channel :channel})
                (returned 3 1 {:result :closed})
                (callback-invoke 4 2 :core-async/take
                                 {:channel :channel :on-caller? false})
                (callback-return 5 0 {:result :accepted})
                 (callback-return 6 2 {:result :value :value :one})])
        witness (model/check-callback! initial events)]
    (is (= [0 1 2] (get-in witness [:partitions 0 :order])))))

(deftest close-preserves-capacity-one-pending-put-ownership
  (let [initial (model/callback-initial-state {:channel 1})
        events (callback-composite
                [(callback-invoke 1 0 :core-async/put
                                 {:channel :channel :value :buffered
                                  :on-caller? true})
                (callback-return 2 0 {:result :accepted})
                (callback-invoke 3 1 :core-async/put
                                 {:channel :channel :value :pending
                                  :on-caller? false})
                (invoke 4 2 :core-async/close {:channel :channel})
                (returned 5 2 {:result :closed})
                (callback-invoke 6 3 :core-async/take
                                 {:channel :channel :on-caller? false})
                (callback-return 7 3 {:result :value :value :buffered})
                (callback-return 8 1 {:result :accepted})
                (callback-invoke 9 4 :core-async/take
                                 {:channel :channel :on-caller? false})
                 (callback-return 10 4 {:result :value :value :pending})])
        witness (model/check-callback! initial events)]
    (is (= [0 1 2 3 4] (get-in witness [:partitions 0 :order])))))

(deftest callback-model-rejects-lifecycle-and-carrier-corruption
  (let [initial (model/callback-initial-state {:channel 1})
        logical [(callback-invoke 1 0 :core-async/put
                                  {:channel :channel :value :one
                                   :on-caller? true})
                 (callback-return 2 0 {:result :accepted})]
        legal (callback-composite logical)
        corrupted (callback-composite
                   (assoc-in logical [1 :value :carrier
                                      :parent-operation-id] 7))]
    (is (some? (model/callback-linearization initial legal)))
    (is (nil? (model/callback-linearization initial corrupted)))
    (is (thrown? Exception
                 (model/check-callback!
                  initial
                  (callback-composite
                   (conj logical
                         (callback-return 3 0 {:result :accepted}))))))))

(deftest unbuffered-completed-put-requires-a-matching-take
  (let [initial (model/callback-initial-state {:channel 0})
        unmatched (callback-composite
                   [(callback-invoke 1 0 :core-async/put
                                     {:channel :channel :value :one
                                      :on-caller? true})
                    (callback-return 2 0 {:result :accepted})])
        failure (try
                  (model/check-callback! initial unmatched)
                  nil
                  (catch Throwable error error))]
    (is (nil? (model/callback-linearization initial unmatched)))
    (is (= :jolt.aspect-packs.core-async.model/unmatched-rendezvous
           (:type (ex-data failure))))))

(deftest callback-composite-requires-one-correctly-parented-target
  (let [logical [(callback-invoke 1 0 :core-async/put
                                  {:channel :channel :value :one
                                   :on-caller? true})
                 (callback-return 2 0 {:result :accepted})]
        events (callback-composite logical)
        trace (model/callback-trace events)
        target (first (:target-operations trace))]
    (is (= 1 (count (:target-operations trace))))
    (is (= :core-async/put-target (:operation target)))
    (is (= 0 (get-in target [:invoke :parent-operation-id])))
    (is (= (:input (first (:logical-operations trace))) (:input target)))
    (is (thrown? Exception
                 (model/callback-trace
                  (vec (remove #(= (:operation-id target) (:operation-id %))
                               events)))))
    (is (thrown? Exception
                 (model/callback-trace
                  (assoc-in events [1 :parent-operation-id] 99))))))

(deftest callback-composite-requires-target-to-begin-before-parent-closes
  (let [logical [(callback-invoke 1 0 :core-async/put
                                  {:channel :channel :value :one
                                   :on-caller? true})
                 (callback-return 2 0 {:result :accepted})]
        events (callback-composite logical)
        by-position [(nth events 0) (nth events 3)
                     (nth events 1) (nth events 2)]
        target-after-parent
        (mapv (fn [index event] (assoc event :seq (inc index)))
              (range) by-position)]
    (is (some? (model/callback-trace events)))
    (is (thrown? Exception
                 (model/callback-trace target-after-parent)))))
