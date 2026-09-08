(ns jolt.aspect-packs.chdb-durable.faults-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.aspect-packs.chdb-durable.faults :as faults]))

(def ^:private point
  {:id :durable/commit-reference
   :site-id "fault-test/commit-reference"
   :build-identity "chdb-durable-fault-test"})

(defn- invoke! [calls]
  (faults/around-control
   point [] #(do (swap! calls inc) {:status :committed})))

(deftest typed-throw-faults-target-one-exact-operation-hit
  (doseq [[phase expected-calls]
          [[:before 1]
           [:after 2]]]
    (let [calls (atom 0)
          marker (ex-info "typed injected fault"
                          {:type :jdbc.chdb.durable.control/commit-ambiguous})
          observed
          (try
            (faults/call-with-fault
             {:operation :durable/commit-reference
              :phase phase :hit 2 :effect :throw :error marker}
             #(do (is (= {:status :committed} (invoke! calls)))
                  (invoke! calls)))
            nil
            (catch Throwable error error))]
      (is (identical? marker observed))
      ;; Before skips the selected target; after proves it completed first.
      (is (= expected-calls @calls)))))

(deftest nonselected-operation-is-inert
  (let [calls (atom 0)]
    (is (= {:status :committed}
           (faults/call-with-fault
            {:operation :durable/release :phase :before :effect :throw
             :error (ex-info "must not escape" {})}
            #(invoke! calls))))
    (is (= 1 @calls))))

(deftest malformed-typed-faults-fail-closed
  (doseq [action
          [{:operation :durable/commit-reference :phase :middle}
           {:operation :durable/commit-reference :phase :before :hit 0}
           {:operation :durable/commit-reference :phase :before
            :effect :replace}
           {:operation :durable/commit-reference :phase :before
            :effect :throw}
           {:operation :durable/commit-reference :phase :before
            :effect :throw :error :not-a-throwable}
           {:operation :durable/commit-reference :phase :before
            :effect :crash}
           {:operation :durable/commit-reference :phase :before
            :effect :crash :ready-file ""}]]
    (let [entered? (atom false)]
      (is (thrown? Exception
                   (faults/call-with-fault
                    action #(reset! entered? true))))
      (is (false? @entered?)))))
