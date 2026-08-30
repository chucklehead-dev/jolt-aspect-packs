(ns jolt.aspect-packs.checkpoint-history
  "Portable validation and extraction for Jolt record-only checkpoint journals.")

(def ^:private supported-dispositions
  #{:barrier :cancel :continue :fault :yield})

(defn- invalid! [kind message data]
  (throw (ex-info message (assoc data :kind kind))))

(defn- qualified-id? [value]
  (and (string? value)
       (let [slash (.indexOf value "/")]
         (and (pos? slash) (< slash (dec (count value)))))))

(defn- actor? [value]
  (and (string? value) (pos? (count value))))

(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn- compare-plan-keys [[actor-a id-a hit-a] [actor-b id-b hit-b]]
  (let [actor-order (compare actor-a actor-b)]
    (if (zero? actor-order)
      (let [id-order (compare id-a id-b)]
        (if (zero? id-order) (compare hit-a hit-b) id-order))
      actor-order)))

(defn- validate-sites! [sites]
  (when-not (map? sites)
    (invalid! :checkpoint-history/invalid-sites
              "checkpoint snapshot sites must be a map"
              {:value sites}))
  (doseq [[id dispositions] sites]
    (when-not (qualified-id? id)
      (invalid! :checkpoint-history/invalid-site-id
                "checkpoint site id must be a qualified string"
                {:id id}))
    (when-not (and (vector? dispositions)
                   (every? supported-dispositions dispositions)
                   (= dispositions (vec (sort dispositions)))
                   (= (count dispositions) (count (distinct dispositions)))
                   (some #{:continue} dispositions))
      (invalid! :checkpoint-history/invalid-dispositions
                "checkpoint site dispositions must be canonical and include :continue"
                {:id id :dispositions dispositions})))
  sites)

(defn- validate-plan-key! [key]
  (when-not (and (vector? key)
                 (= 3 (count key))
                 (actor? (nth key 0))
                 (qualified-id? (nth key 1))
                 (positive-integer? (nth key 2)))
    (invalid! :checkpoint-history/invalid-plan-key
              "checkpoint plan key must be [nonempty-actor qualified-id positive-hit]"
              {:plan-key key}))
  key)

(defn- validate-plan! [sites plan]
  (when-not (map? plan)
    (invalid! :checkpoint-history/invalid-plan
              "checkpoint snapshot plan must be a map"
              {:value plan}))
  (doseq [[key action] plan]
    (validate-plan-key! key)
    (let [id (nth key 1)]
      (when-not (contains? sites id)
        (invalid! :checkpoint-history/unregistered-plan-site
                  "checkpoint plan names an unregistered site"
                  {:plan-key key}))
      (when-not (= :continue action)
        (invalid! :checkpoint-history/invalid-plan-action
                  "record-only checkpoint plans support only :continue"
                  {:plan-key key :action action}))))
  plan)

(defn- validate-event-shape! [event]
  (when-not (and (map? event)
                 (positive-integer? (:seq event))
                 (actor? (:actor event))
                 (qualified-id? (:id event))
                 (positive-integer? (:hit event))
                 (contains? #{nil :continue} (:action event)))
    (invalid! :checkpoint-history/invalid-event
              "checkpoint trace contains a malformed event"
              {:event event}))
  event)

(defn- canonical-sites [sites]
  (->> sites
       (sort-by key)
       (mapv (fn [[id dispositions]]
               {:id id :dispositions dispositions}))))

(defn- canonical-plan [plan]
  (->> plan
       (sort (fn [[key-a _] [key-b _]]
               (compare-plan-keys key-a key-b)))
       (mapv (fn [[[actor id hit] action]]
               {:actor actor :id id :hit hit :action action}))))

(defn normalize
  "Validate a `jolt.host/checkpoint-snapshot` value and return canonical,
  portable evidence. The result contains inert values only and is stable under
  EDN round trips. Unknown top-level snapshot keys are ignored so a runtime may
  add unrelated diagnostics without changing this schema."
  [snapshot]
  (when-not (map? snapshot)
    (invalid! :checkpoint-history/invalid-snapshot
              "checkpoint snapshot must be a map"
              {:value snapshot}))
  (let [sites (validate-sites! (:sites snapshot))
        plan (validate-plan! sites (:plan snapshot))
        trace (:trace snapshot)
        next-seq (:next-seq snapshot)]
    (when-not (vector? trace)
      (invalid! :checkpoint-history/invalid-trace
                "checkpoint snapshot trace must be a vector"
                {:value trace}))
    (when-not (and (positive-integer? next-seq)
                   (= next-seq (inc (count trace))))
      (invalid! :checkpoint-history/invalid-next-seq
                "checkpoint next sequence must follow the trace"
                {:next-seq next-seq :trace-count (count trace)}))
    (let [events
          (:events
           (reduce
            (fn [{:keys [hits events]} [index event]]
              (validate-event-shape! event)
              (let [expected-seq (inc index)
                    actor (:actor event)
                    id (:id event)
                    hit-key [actor id]
                    expected-hit (inc (get hits hit-key 0))
                    plan-key [actor id (:hit event)]
                    planned-action (get plan plan-key)
                    action (:action event)]
                (when-not (= expected-seq (:seq event))
                  (invalid! :checkpoint-history/noncontiguous-sequence
                            "checkpoint trace sequence is not contiguous"
                            {:expected expected-seq :event event}))
                (when-not (contains? sites id)
                  (invalid! :checkpoint-history/unregistered-event-site
                            "checkpoint event names an unregistered site"
                            {:event event}))
                (when-not (= expected-hit (:hit event))
                  (invalid! :checkpoint-history/noncontiguous-hit
                            "checkpoint actor/site hit count is not contiguous"
                            {:expected expected-hit :event event}))
                (when-not (= planned-action action)
                  (invalid! :checkpoint-history/action-plan-mismatch
                            "checkpoint event action does not match its inert plan"
                            {:plan-key plan-key
                             :expected planned-action
                             :actual action}))
                {:hits (assoc hits hit-key expected-hit)
                 :events (conj events
                               {:seq expected-seq
                                :actor actor
                                :id id
                                :hit expected-hit
                                :action action})}))
            {:hits {} :events []}
            (map-indexed vector trace)))]
      {:schema 1
       :kind :jolt/checkpoint-history
       :sites (canonical-sites sites)
       :plan (canonical-plan plan)
       :events events
       :next-seq next-seq})))

(defn portable-observations
  "Extract the minimal ordered observations suitable for an ordinary portable
  regression fixture. The returned vector has no dependency on Jolt runtime,
  checkpoint, aspect, or Hegel APIs."
  [snapshot]
  (mapv #(select-keys % [:actor :id :hit :action])
        (:events (normalize snapshot))))

(defn runtime-snapshot
  "Read the current runtime journal when the selected Jolt provides it.
  Resolution is dynamic so this namespace and `normalize` remain loadable on
  older Jolt releases, Babashka, and JVM Clojure."
  []
  (if-let [snapshot-fn (resolve 'jolt.host/checkpoint-snapshot)]
    (snapshot-fn)
    (invalid! :checkpoint-history/runtime-unavailable
              "selected runtime has no checkpoint snapshot API"
              {})))

(defn capture-runtime
  "Capture and normalize the selected Jolt runtime's current journal."
  []
  (normalize (runtime-snapshot)))
