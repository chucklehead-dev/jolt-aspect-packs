(ns jolt.aspect-packs.db.corpus-generator
  "Pure synthetic db corpus witnesses; no provider, driver, or I/O dependency."
  (:require [hegel.generator :as g]))

(def ^:private max-cardinality 9223372036854775807)
(def ^:private systems ["sqlite" "duckdb" "postgresql" "clickhouse" "other_sql"])
(def ^:private mutations ["INSERT" "UPDATE" "DELETE"])

(defn witness
  "Build one complete privacy-shaped six-event db lifecycle witness.

  `returned` and `affected` are already bounded cardinalities; `system` and
  `mutation` are controlled semantic labels selected by `generator`."
  [returned affected system mutation]
  [{:seq 1
    :operation-id 0
    :parent-operation-id nil
    :context-id :synthetic/db-request
    :causal-links []
    :phase :invoke
    :operation :db.jdbc-shim/execute
    :site-id "synthetic/db-execute-v1"
    :build-identity "synthetic/db-corpus-v1"
    :input {:operation "SELECT" :system system}}
   {:seq 2
    :operation-id 1
    :parent-operation-id 0
    :context-id :synthetic/db-request
    :causal-links [0]
    :phase :invoke
    :operation :db.jdbc-shim/execute
    :site-id "synthetic/db-execute-v1"
    :build-identity "synthetic/db-corpus-v1"
    :input {:operation mutation :system system}}
   {:seq 3
    :operation-id 1
    :phase :return
    :value {:outcome :ok :row-count affected :row-count-kind :affected}}
   {:seq 4
    :operation-id 0
    :phase :return
    :value {:outcome :ok :row-count returned :row-count-kind :returned}}
   {:seq 5
    :operation-id 2
    :parent-operation-id nil
    :context-id :synthetic/db-request
    :causal-links []
    :phase :invoke
    :operation :db.jdbc-shim/execute
    :site-id "synthetic/db-execute-v1"
    :build-identity "synthetic/db-corpus-v1"
    :input {:operation "UPDATE" :system system}}
   {:seq 6
    :operation-id 2
    :phase :throw
    :value {:outcome :error :error-type "synthetic/db-error"}}])

(defn- cardinality-generator []
  ;; Every generated entry has all witness families.  This choice domain adds
  ;; zero, one, and the int64 maximum alongside modest ordinary cardinalities;
  ;; it does not rely on a probabilistic family branch for coverage.
  (g/one-of [(g/sampled-from [0 1 max-cardinality])
             (g/integer 2 64)]))

(defn generator
  "Return a Hegel generator of complete synthetic db event-vector witnesses."
  []
  (g/fmap (fn [[returned affected system mutation]]
            (witness returned affected system mutation))
          (g/tuple (cardinality-generator)
                   (cardinality-generator)
                   (g/sampled-from systems)
                   (g/sampled-from mutations))))
