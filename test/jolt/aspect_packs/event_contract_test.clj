(ns jolt.aspect-packs.event-contract-test
  (:require [clojure.test :refer [deftest is]]
            [hegel.event-contract :as contract]
            [hegel.trace :as trace]
            [jolt.aspect-packs.history :as history]))

(def ^:private join-point
  {:id :fixture/operation
   :site-id "event-contract-site"
   :build-identity "event-contract-build"})

(defn- error-type [thunk]
  (try
    (thunk)
    nil
    (catch Throwable error
      (:type (ex-data error)))))

(defn- normal-events []
  (let [journal (history/journal)
        handle (history/begin! journal join-point {:private-input :shaped}
                               {:context-id :request-1})]
    (history/return! handle {:value :shaped})
    (history/events journal)))

(defn- thrown-events []
  (let [journal (history/journal)
        handle (history/begin! journal join-point {:private-input :throw}
                               {:context-id :request-2})]
    (history/throw! handle {:exception :shaped})
    (history/events journal)))

(defn- async-events []
  (let [journal (history/journal)
        parent (history/begin! journal join-point {:level :parent}
                               {:context-id :request-3})
        carrier (history/carrier parent)]
    ;; Causal parentage deliberately permits a parent terminal before a child
    ;; begins under the captured carrier.
    (history/return! parent :parent-done)
    (history/call-with-carrier
     carrier
     #(let [child (history/begin! history/*journal* join-point {:level :child})]
        (history/return! child :child-done)))
    (history/events journal)))

(defn- fan-in-events []
  (let [journal (history/journal)
        sources (mapv #(history/begin! journal join-point {:source %}
                                      {:context-id :request-4})
                      (range 11))]
    (doseq [handle sources] (history/return! handle :source-done))
    (let [links (history/causal-links journal [(nth sources 2) (nth sources 10)])
          join (history/begin! journal join-point {:join :fan-in}
                               {:context-id :request-4 :causal-links links})]
      (history/return! join :join-done)
      (history/events journal))))

(deftest producer-journals-satisfy-the-canonical-event-profile
  (let [events (normal-events)
        envelope {:contract-id contract/contract-id
                  :contract-revision contract/contract-revision
                  :events events}]
    (is (identical? events (contract/check! events)))
    (is (identical? envelope (contract/check-envelope! envelope)))
    (is (= {:private-input :shaped} (get-in events [0 :input])))
    (is (= {:value :shaped} (get-in events [1 :value])))
    (is (= "event-contract-site" (get-in events [0 :site-id])))
    (is (= "event-contract-build" (get-in events [0 :build-identity])))))

(deftest declared-envelope-comes-from-one-closed-journal-snapshot
  (let [journal (history/journal)
        handle (history/begin! journal join-point {})]
    (is (= :history/open-operations
           (try (history/event-envelope journal) nil
                (catch Exception error (:kind (ex-data error))))))
    (history/return! handle :done)
    (let [envelope (history/event-envelope journal)]
      ;; Independent literal identity pins detect producer/checker drift.
      (is (= "hegel.operation-events" (:contract-id envelope)))
      (is (= "1" (:contract-revision envelope)))
      (is (identical? envelope (contract/check-envelope! envelope)))
      (is (= (history/events journal) (:events envelope)))
      (let [later (history/begin! journal join-point {})]
        (history/return! later :later)
        (is (= 2 (count (:events envelope))))
        (is (= 4 (count (:events (history/event-envelope journal)))))))))

(deftest producer-emits-closed-throw-async-and-empty-histories
  (let [throwing (thrown-events)
        asynchronous (async-events)
        empty-events (history/events (history/journal))]
    (is (identical? throwing (contract/check! throwing)))
    (is (= :throw (:phase (second throwing))))
    (is (identical? asynchronous (contract/check! asynchronous)))
    (is (= [:invoke :return :invoke :return]
           (mapv :phase asynchronous)))
    (is (= 0 (get-in asynchronous [2 :parent-operation-id])))
    (is (= :request-3 (get-in asynchronous [2 :context-id])))
    (is (identical? empty-events (contract/check! empty-events)))))

(deftest producer-causal-link-builder-emits-canonical-multi-digit-fan-in
  (let [events (fan-in-events)
        join-invocation (last (filter #(= :invoke (:phase %)) events))]
    (is (= [10 2] (:causal-links join-invocation)))
    (is (identical? events (contract/check! events)))))

(deftest canonical-profile-rejects-mutated-producer-events
  (let [events (normal-events)
        missing-links (update events 0 dissoc :causal-links)
        missing-context (update events 0 dissoc :context-id)
        sequence-loss (assoc-in events [1 :seq] 3)
        dangling-parent (assoc-in events [0 :parent-operation-id] 99)]
    (doseq [mutated [missing-links missing-context sequence-loss dangling-parent]]
      (is (= ::trace/rule-failed (error-type #(contract/check! mutated)))))))

(deftest envelope-profile-identity-rejects-stale-or-mismatched-headers
  (let [events (normal-events)
        valid {:contract-id contract/contract-id
               :contract-revision contract/contract-revision
               :events events}
        stale (assoc valid :contract-revision "0")
        mismatched (assoc valid :contract-id "hegel.operation-events-other")]
    (doseq [envelope [stale mismatched]]
      (is (= ::contract/invalid-envelope
             (error-type #(contract/check-envelope! envelope)))))))
