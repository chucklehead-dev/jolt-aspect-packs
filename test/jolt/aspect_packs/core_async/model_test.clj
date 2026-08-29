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
