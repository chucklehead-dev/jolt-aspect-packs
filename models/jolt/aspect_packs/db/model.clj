(ns jolt.aspect-packs.db.model
  (:require [hegel.trace :as trace]))

(def ^:private systems #{"clickhouse" "duckdb" "sqlite" "postgresql" "other_sql"})
(def ^:private operations
  #{"SELECT" "INSERT" "UPDATE" "DELETE" "MERGE"
    "CREATE" "ALTER" "DROP" "TRUNCATE"
    "BEGIN" "COMMIT" "ROLLBACK" "SAVEPOINT" "RELEASE"
    "PRAGMA" "EXPLAIN" "CALL" "COPY" "UNKNOWN"})
(def ^:private row-count-kinds #{:returned :affected})
(def ^:private max-cardinality 9223372036854775807)

(def semantic-invocations
  (trace/rule
   :db/semantic-invocations
   (fn [events]
     (every?
      (fn [event]
        (or
         (not= :invoke (:phase event))
         (let [input (:input event)
               keys (set (keys input))]
           (and (map? input)
                (contains? #{#{:operation :system}
                             #{:operation :system :statement-fingerprint}}
                           keys)
                (contains? operations (:operation input))
                (contains? systems (:system input))
                (or (not (contains? input :statement-fingerprint))
                    (boolean
                     (re-matches #"fnv1a32:[0-9a-f]{8}"
                                 (:statement-fingerprint input))))))))
      events))))

(def shaped-terminals
  (trace/rule
   :db/shaped-terminals
   (fn [events]
     (every?
      (fn [event]
        (if (= :invoke (:phase event))
          true
          (let [value (:value event)]
            (and (map? value)
                 (if (= :return (:phase event))
                   (and (= :ok (:outcome value))
                        (contains? #{#{:outcome}
                                     #{:outcome :row-count :row-count-kind}}
                                   (set (keys value)))
                        (or (not (contains? value :row-count))
                            (and (integer? (:row-count value))
                                 (<= 0 (:row-count value) max-cardinality)
                                 (contains? row-count-kinds
                                            (:row-count-kind value)))))
                   (and (= :throw (:phase event))
                        (= #{:outcome :error-type} (set (keys value)))
                        (= :error (:outcome value))
                        (string? (:error-type value))
                        (pos? (count (:error-type value)))
                        (<= (count (:error-type value)) 255)))))))
      events))))

(def rules
  [(trace/contiguous-sequence :db/contiguous-history 1)
   (trace/closed-lifecycles :db/closed-operation-lifecycles)
   (trace/synchronous-parentage :db/synchronous-parentage)
   (trace/causal-links :db/canonical-causal-links)
   (trace/context-coherence :db/context-coherence)
   semantic-invocations
   shaped-terminals])

(defn check! [events]
  (trace/check! events rules {:max-events 512}))
