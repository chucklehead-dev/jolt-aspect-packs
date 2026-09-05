(ns jolt.aspect-packs.db.corpus-generator-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.aspect-packs.db.corpus-generator :as corpus]
            [jolt.aspect-packs.db.model :as model]))

(def expected
  [{:seq 1 :operation-id 0 :parent-operation-id nil
    :context-id :synthetic/db-request :causal-links [] :phase :invoke
    :operation :db.jdbc-shim/execute :site-id "synthetic/db-execute-v1"
    :build-identity "synthetic/db-corpus-v1"
    :input {:operation "SELECT" :system "sqlite"}}
   {:seq 2 :operation-id 1 :parent-operation-id 0
    :context-id :synthetic/db-request :causal-links [0] :phase :invoke
    :operation :db.jdbc-shim/execute :site-id "synthetic/db-execute-v1"
    :build-identity "synthetic/db-corpus-v1"
    :input {:operation "DELETE" :system "sqlite"}}
   {:seq 3 :operation-id 1 :phase :return
    :value {:outcome :ok :row-count 1 :row-count-kind :affected}}
   {:seq 4 :operation-id 0 :phase :return
    :value {:outcome :ok :row-count 9223372036854775807
            :row-count-kind :returned}}
   {:seq 5 :operation-id 2 :parent-operation-id nil
    :context-id :synthetic/db-request :causal-links [] :phase :invoke
    :operation :db.jdbc-shim/execute :site-id "synthetic/db-execute-v1"
    :build-identity "synthetic/db-corpus-v1"
    :input {:operation "UPDATE" :system "sqlite"}}
   {:seq 6 :operation-id 2 :phase :throw
    :value {:outcome :error :error-type "synthetic/db-error"}}])

(deftest witness-is-complete-privacy-shaped-and-model-valid
  (let [events (corpus/witness 9223372036854775807 1 "sqlite" "DELETE")]
    (is (= expected events))
    (is (= [1 2 3 4 5 6] (mapv :seq events)))
    (is (= [0 1 1 0 2 2] (mapv :operation-id events)))
    (is (= [#{:seq :operation-id :phase :value}
            #{:seq :operation-id :phase :value}
            #{:seq :operation-id :phase :value}]
           (mapv #(set (keys %)) (filter #(not= :invoke (:phase %)) events))))
    (is (= events (model/check! events)))))

(deftest generator-is-a-complete-witness-generator-not-a-provider-hook
  ;; Construction performs no database, OTel, or filesystem work; actual draws
  ;; belong to the separately serialized native materialization gate.
  (is (fn? (corpus/generator))))

(defn -main [& _]
  (let [{:keys [fail error]}
        (clojure.test/run-tests 'jolt.aspect-packs.db.corpus-generator-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "db corpus generator tests failed"
                      {:fail fail :error error})))))
