(ns jolt.aspect-packs.history-test
  (:require [clojure.test :refer [deftest is testing]]
            [hegel.history :as hegel-history]
            [jolt.aspect-packs.history :as history]))

(def join-point
  {:id :fixture/operation
   :site-id "site-v1"
   :build-identity "build-v1"})

(deftest canonical-history-is-directly-hegel-compatible
  (let [journal (history/journal)
        handle (binding [history/*context-id* :run-7]
                 (history/begin! journal join-point {:kind :fixture}))]
    (is (= :returned (history/return! handle :returned)))
    (is (= [{:seq 1
             :operation-id 0
             :parent-operation-id nil
             :context-id :run-7
             :causal-links []
             :phase :invoke
             :operation :fixture/operation
             :site-id "site-v1"
             :build-identity "build-v1"
             :input {:kind :fixture}}
            {:seq 2
             :operation-id 0
             :phase :return
             :value :returned}]
           (history/events journal)))
    (is (= 1 (count (hegel-history/operations (history/events journal)))))))

(deftest operation-handle-closes-exactly-once-under-contention
  (let [journal (history/journal)
        handle (history/begin! journal join-point {})
        workers (mapv (fn [n]
                        (future
                          (try
                            (history/return! handle [:winner n])
                            {:closed? true}
                            (catch Exception error
                              {:closed? false :error error}))))
                      (range 32))
        results (mapv deref workers)
        events (history/events journal)]
    (is (= 1 (count (filter :closed? results))))
    (is (= 31 (count (remove :closed? results))))
    (is (= [1 2] (mapv :seq events)))
    (is (= [:invoke :return] (mapv :phase events)))
    (is (= 1 (count (hegel-history/operations events))))))

(deftest raced-completion-has-one-winner-without-loser-exceptions
  (let [journal (history/journal)
        handle (history/begin! journal join-point {})
        workers (mapv (fn [n]
                        (future
                          (if (even? n)
                            (history/try-return! handle [:return n])
                            (history/try-throw! handle [:throw n]))))
                      (range 32))
        results (mapv deref workers)]
    (is (= 1 (count (filter :closed? results))))
    (is (= 31 (count (remove :closed? results))))
    (is (= 2 (count (history/events journal))))
    (is (true? (history/assert-complete! journal)))))

(deftest explicit-carrier-preserves-async-parentage-and-context
  (let [journal (history/journal)
        outer (history/begin! journal join-point {:level :outer}
                              {:context-id :request-9})
        outer-carrier (history/carrier outer)
        child
        @(future
           (history/call-with-carrier
             outer-carrier
             (fn []
               (let [handle (history/begin! history/*journal* join-point
                                            {:level :child})]
                 (history/return! handle :child-return)
                 handle))))]
    (history/return! outer :outer-return)
    (let [[outer-invoke child-invoke child-return outer-return]
          (history/events journal)]
      (is (= [0 1 1 0]
             (mapv :operation-id
                   [outer-invoke child-invoke child-return outer-return])))
      (is (= (history/operation-id outer)
             (:parent-operation-id child-invoke)))
      (is (= :request-9 (:context-id child-invoke)))
      (is (= 1 (history/operation-id child)))
      (is (= 2 (count (hegel-history/operations (history/events journal))))))))

(deftest duplicate-forged-and-invalid-closes-fail-closed
  (let [journal (history/journal)
        handle (history/begin! journal join-point {})]
    (history/throw! handle :thrown)
    (doseq [attempt [#(history/return! handle :late)
                     #(history/return!
                        (fn [] :not-a-handle)
                        :forged)
                     #(history/return! {} :invalid)]]
      (is (thrown? Exception (attempt))))
    (is (= [:invoke :throw] (mapv :phase (history/events journal))))))

(deftest journal-and-carriers-hide-private-state-and-enforce-journal-identity
  (let [journal-a (history/journal)
        journal-b (history/journal)
        handle (history/begin! journal-a join-point {:secret :not-exposed})
        carrier (history/carrier handle)]
    (is (thrown? Exception (deref journal-a)))
    (is (thrown-with-msg?
          Exception #"different journal"
          (history/call-with-carrier
            carrier
            #(history/begin! journal-b join-point {}))))
    (is (= [0] (history/open-operation-ids journal-a)))
    (is (thrown-with-msg? Exception #"open operations"
                          (history/assert-complete! journal-a)))
    (history/return! handle :done)
    (is (true? (history/assert-complete! journal-a)))
    (is (empty? (history/events journal-b)))))

(deftest variadic-carrier-wrapper-restores-causal-context
  (let [journal (history/journal)
        outer (history/begin! journal join-point {} {:context-id :ctx})
        wrapped (history/wrap-carrier
                  (history/carrier outer)
                  (fn [a b]
                    (let [child (history/begin! history/*journal* join-point
                                                {:sum (+ a b)})]
                      (history/return! child :done)
                      (+ a b))))]
    (is (= 7 (wrapped 3 4)))
    (history/return! outer :done)
    (let [child (second (filter #(= :invoke (:phase %))
                                (history/events journal)))]
      (is (= 0 (:parent-operation-id child)))
      (is (= :ctx (:context-id child))))))

(deftest synchronous-helper-preserves-result-error-and-parentage
  (let [journal (history/journal)
        sentinel (Object.)]
    (is (identical?
          sentinel
          (history/invoke!
            journal join-point {:level :outer}
            #(history/invoke! journal join-point {:level :inner}
                              (fn [] sentinel)))))
    (is (= [nil 0]
           (mapv :parent-operation-id
                 (filterv #(= :invoke (:phase %)) (history/events journal))))))
  (let [journal (history/journal)
        expected (ex-info "expected" {:kind :fixture})
        observed (try
                   (history/invoke! journal join-point {} #(throw expected))
                   nil
                   (catch Throwable error error))]
    (is (identical? expected observed))
    (is (= [:invoke :throw] (mapv :phase (history/events journal)))))
  (let [journal (history/journal)
        sentinel (Object.)]
    (is (identical? sentinel
                    (history/invoke! journal join-point {}
                                     {:return-fn (fn [_] {:rows 3})}
                                     (fn [] sentinel))))
    (is (= {:rows 3} (:value (second (history/events journal))))))
  (let [journal (history/journal)
        expected (ex-info "application" {})
        recorder (ex-info "recorder" {})
        observed (try
                   (history/invoke! journal join-point {}
                                    {:throw-fn (fn [_] (throw recorder))}
                                    #(throw expected))
                   nil
                   (catch Throwable error error))]
    (is (identical? expected observed))
    (is (= [:invoke] (mapv :phase (history/events journal))))))
