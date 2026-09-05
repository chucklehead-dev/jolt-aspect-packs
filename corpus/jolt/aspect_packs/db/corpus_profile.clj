(ns jolt.aspect-packs.db.corpus-profile
  "Closed synthetic fixture boundary, separate from general trace semantics.

  This profile excludes arbitrary metadata and raw database data. It is not a
  claim that matching strings or numeric values establish anonymity."
  (:require [jolt.aspect-packs.db.model :as model]))

(def ^:private invocation-keys
  #{:seq :operation-id :parent-operation-id :context-id :causal-links :phase
    :operation :site-id :build-identity :input})
(def ^:private terminal-keys #{:seq :operation-id :phase :value})
(def systems
  "Ordered synthetic-v1 generation domain; intentionally narrower than the model."
  ["sqlite" "duckdb" "postgresql" "clickhouse" "other_sql"])
(def mutations
  "Ordered synthetic-v1 mutation domain. Ordering participates in seed replay."
  ["INSERT" "UPDATE" "DELETE"])
(def ^:private system-set (set systems))
(def ^:private operations (conj (set mutations) "SELECT"))
(def ^:private contexts #{:synthetic/db-request :synthetic/db-other})

(defn- invalid! [path reason]
  (throw (ex-info "invalid synthetic db corpus fixture"
                  {:type ::invalid-fixture :path path :reason reason})))

(defn- closed! [path value expected]
  (when-not (and (map? value) (not (record? value))
                 (= (count expected) (count value))
                 (= expected (set (keys value))))
    ;; Never include a caller-controlled unknown key or value in diagnostics.
    (invalid! path :closed-map-required)))

(defn- integer-range! [path value minimum maximum]
  (when-not (and (integer? value) (<= minimum value maximum))
    (invalid! path :bounded-integer-required)))

(defn- string-member! [path value allowed]
  (when-not (and (string? value) (<= (count value) 64)
                 (contains? allowed value))
    (invalid! path :synthetic-string-required)))

(defn- invocation! [path event]
  (closed! path event invocation-keys)
  (when-not (= :db.jdbc-shim/execute (:operation event))
    (invalid! (conj path :operation) :synthetic-operation-required))
  (string-member! (conj path :site-id) (:site-id event) #{"synthetic/db-execute-v1"})
  (string-member! (conj path :build-identity) (:build-identity event)
                  #{"synthetic/db-corpus-v1"})
  (when-not (contains? contexts (:context-id event))
    (invalid! (conj path :context-id) :synthetic-context-required))
  (when (some? (:parent-operation-id event))
    (integer-range! (conj path :parent-operation-id) (:parent-operation-id event) 0 255))
  (let [links (:causal-links event)]
    (when-not (and (vector? links) (<= (count links) 256))
      (invalid! (conj path :causal-links) :bounded-vector-required))
    (doseq [link links]
      (integer-range! (conj path :causal-links) link 0 255)))
  (let [input (:input event)]
    ;; Optional fingerprints are deliberately absent from this synthetic v1
    ;; profile; the existing provider/model may support a broader domain.
    (closed! (conj path :input) input #{:operation :system})
    (string-member! (conj path :input :operation) (:operation input) operations)
    (string-member! (conj path :input :system) (:system input) system-set)))

(defn- terminal! [path event]
  (closed! path event terminal-keys)
  (let [value (:value event)]
    (case (:phase event)
      :return
      (do
        (closed! (conj path :value) value #{:outcome :row-count :row-count-kind})
        (when-not (= :ok (:outcome value))
          (invalid! (conj path :value :outcome) :synthetic-outcome-required))
        ;; Negative int64 remains representable so the semantic model, rather
        ;; than this privacy boundary, diagnoses invalid cardinality witnesses.
        (integer-range! (conj path :value :row-count) (:row-count value)
                         -9223372036854775808N 9223372036854775807N)
        (when-not (contains? #{:returned :affected} (:row-count-kind value))
          (invalid! (conj path :value :row-count-kind) :synthetic-count-kind-required)))
      :throw
      (do
        (closed! (conj path :value) value #{:outcome :error-type})
        (when-not (= :error (:outcome value))
          (invalid! (conj path :value :outcome) :synthetic-outcome-required))
        (string-member! (conj path :value :error-type) (:error-type value)
                        #{"synthetic/db-error"}))
      (invalid! (conj path :phase) :fixture-phase-required))))

(defn validate!
  "Validate bounded, closed synthetic metadata without changing model semantics."
  [events]
  (when-not (and (vector? events) (<= 1 (count events) 512))
    (invalid! [] :bounded-event-vector-required))
  (doseq [[index event] (map-indexed vector events)]
    (let [path [:events index]]
      (when-not (and (map? event) (not (record? event)) (<= (count event) 10))
        (invalid! path :bounded-event-map-required))
      (if (= :invoke (:phase event))
        (invocation! path event)
        (terminal! path event))
      (integer-range! (conj path :seq) (:seq event) 1 512)
      (integer-range! (conj path :operation-id) (:operation-id event) 0 255)))
  events)

(defn check! [events]
  (validate! events)
  (model/check! events))

(defn witness-families
  "Identify mandatory families in an already privacy/model-checked history."
  [events]
  (let [invocations (into {} (map (juxt :operation-id identity)
                                  (filter #(= :invoke (:phase %)) events)))]
    (reduce
     (fn [families event]
       (let [invocation (get invocations (:operation-id event))
             operation (get-in invocation [:input :operation])]
         (cond-> families
           (and (= :return (:phase event)) (= "SELECT" operation)
                (= :returned (get-in event [:value :row-count-kind])))
           (conj :select-returned)
           (and (= :return (:phase event))
                (contains? #{"INSERT" "UPDATE" "DELETE"} operation)
                (= :affected (get-in event [:value :row-count-kind])))
           (conj :mutation-affected)
           (= :throw (:phase event)) (conj :shaped-throw)
           (and (= :invoke (:phase event)) (some? (:parent-operation-id event)))
           (conj :nested-lifecycle))))
     #{} events)))
