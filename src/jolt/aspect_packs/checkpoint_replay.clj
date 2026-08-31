(ns jolt.aspect-packs.checkpoint-replay
  "Pure validation for constrained Jolt checkpoint replay.

  Replay identity is [actor checkpoint-id per-actor/site-hit]. Cross-actor
  global sequence is journal integrity evidence, not replay equality."
  (:require [clojure.set :as set]
            [jolt.aspect-packs.checkpoint-history :as history]))

(def ^:private allowed-extra-policies #{:forbid :allow-unplanned})
(def ^:private terminal-statuses #{:ok :error})
(def ^:private unresolved-statuses #{:timeout :unresolved})
(def ^:private run-statuses (set/union terminal-statuses unresolved-statuses))
(def ^:private revision-pattern #"[0-9a-f]{40}")

(defn- invalid! [kind message data]
  (throw (ex-info message (assoc data :kind kind))))

(defn- nonempty-string? [value]
  (and (string? value) (pos? (count value))))

(defn- canonical-sites->map [sites]
  (when-not (and (vector? sites)
                 (every? #(and (map? %)
                               (= #{:id :dispositions} (set (keys %))))
                         sites))
    (invalid! :checkpoint-replay/invalid-sites
              "replay case sites must be canonical checkpoint site evidence"
              {:sites sites}))
  (let [ids (mapv :id sites)]
    (when-not (= (count ids) (count (distinct ids)))
      (invalid! :checkpoint-replay/duplicate-site
                "replay case site ids must be unique before map construction"
                {:ids ids})))
  (into {} (map (juxt :id :dispositions)) sites))

(defn- manifest->snapshot [sites manifest]
  (cond
    (and (map? manifest)
         (= 1 (:jolt.checkpoint/version manifest)))
    {:generation 1
     :version 1
     :sites sites
     :plan (:jolt.checkpoint/plan manifest)
     :barriers (:jolt.checkpoint/barriers manifest)
     :trace []
     :next-seq 1}

    (map? manifest)
    {:sites sites :plan manifest :trace [] :next-seq 1}

    :else
    (invalid! :checkpoint-replay/invalid-manifest
              "replay case manifest must be inert checkpoint data"
              {:manifest manifest})))

(defn- event-key [actor event]
  [actor (:id event) (:hit event)])

(defn- plan-map [evidence]
  (into {}
        (map (fn [{:keys [actor id hit action]}]
               [[actor id hit] action]))
        (:plan evidence)))

(defn- validate-actor-events! [actor-events plan extra-policy]
  (when-not (map? actor-events)
    (invalid! :checkpoint-replay/invalid-actor-events
              "replay actor-events must be a map"
              {:actor-events actor-events}))
  (let [seen
        (reduce-kv
         (fn [seen actor events]
           (when-not (nonempty-string? actor)
             (invalid! :checkpoint-replay/invalid-actor
                       "replay actor must be a nonempty string"
                       {:actor actor}))
           (when-not (vector? events)
             (invalid! :checkpoint-replay/invalid-actor-events
                       "each replay actor history must be a vector"
                       {:actor actor :events events}))
           (reduce
            (fn [seen event]
              (when-not (and (map? event)
                             (= #{:id :hit :action} (set (keys event)))
                             (string? (:id event))
                             (integer? (:hit event))
                             (pos? (:hit event)))
                (invalid! :checkpoint-replay/invalid-expected-event
                          "expected replay event must contain id, positive hit, and action"
                          {:actor actor :event event}))
              (let [selector (event-key actor event)
                    planned (get plan selector ::absent)]
                (when (contains? seen selector)
                  (invalid! :checkpoint-replay/duplicate-expected-selector
                            "expected replay selector appears more than once"
                            {:selector selector}))
                (when-not (if (= planned ::absent)
                            (and (= :forbid extra-policy)
                                 (nil? (:action event)))
                            (= planned (:action event)))
                  (invalid! :checkpoint-replay/expected-action-mismatch
                            "expected replay event disagrees with the manifest"
                            {:selector selector :expected planned
                             :actual (:action event)}))
                (conj seen selector)))
            seen
            events))
         #{}
         actor-events)
        planned (set (keys plan))]
    (when-not (set/subset? planned seen)
      (invalid! :checkpoint-replay/missing-expected-plan-selector
                "every planned selector must appear in expected actor-local history"
                {:missing (vec (sort (set/difference planned seen)))})))
  actor-events)

(defn- valid-outcome? [allowed-statuses outcome]
  (and (map? outcome)
       (contains? allowed-statuses (:status outcome))
       (case (:status outcome)
         :error (and (= #{:status :fingerprint} (set (keys outcome)))
                     (nonempty-string? (:fingerprint outcome)))
         (:ok :timeout :unresolved) (= #{:status} (set (keys outcome)))
         false)))

(defn- validate-outcomes! [outcomes actors allowed-statuses kind]
  (when-not (map? outcomes)
    (invalid! kind
              "replay outcomes must be a map"
              {:outcomes outcomes}))
  (when-not (= actors (set (keys outcomes)))
    (invalid! kind
              "replay outcomes must name exactly the expected actors"
              {:expected actors :actual (set (keys outcomes))}))
  (doseq [[actor outcome] outcomes]
    (when-not (and (nonempty-string? actor)
                   (valid-outcome? allowed-statuses outcome))
      (invalid! kind
                "replay outcome must have an exact status-dependent shape"
                {:actor actor :outcome outcome})))
  outcomes)

(defn- validate-provenance! [provenance]
  (when-not (and (map? provenance)
                 (= #{:profile :source-revision} (set (keys provenance)))
                 (= :controlled (:profile provenance))
                 (string? (:source-revision provenance))
                 (boolean (re-matches revision-pattern
                                      (:source-revision provenance))))
    (invalid! :checkpoint-replay/invalid-provenance
              "replay provenance requires an exact controlled profile and 40-hex source revision"
              {:provenance provenance}))
  provenance)

(defn- expected-barriers [evidence]
  (into (sorted-map)
        (map (fn [{:keys [id selectors]}]
               [id {:status :complete
                    :arrivals selectors
                    :completed-after-arrivals (count selectors)}]))
        (:barriers evidence)))

(defn- validate-run-barriers! [barriers expected]
  (when-not (and (map? barriers)
                 (= (set (keys expected)) (set (keys barriers))))
    (invalid! :checkpoint-replay/invalid-run-barriers
              "run barriers must name exactly the manifest barriers"
              {:expected (set (keys expected)) :actual barriers}))
  (doseq [[id observation] barriers]
    (let [arrivals (:arrivals observation)
          completed (:completed-after-arrivals observation)]
      (when-not (and (map? observation)
                     (= #{:status :arrivals :completed-after-arrivals}
                        (set (keys observation)))
                     (contains? #{:complete :timeout :unresolved}
                                (:status observation))
                     (vector? arrivals)
                     (every? #(and (vector? %)
                                   (= 3 (count %))
                                   (nonempty-string? (nth % 0))
                                   (nonempty-string? (nth % 1))
                                   (integer? (nth % 2))
                                   (pos? (nth % 2)))
                             arrivals)
                     (= (count arrivals) (count (distinct arrivals)))
                     (integer? completed)
                     (<= 0 completed (count arrivals)))
        (invalid! :checkpoint-replay/invalid-run-barrier
                  "run barrier observation must have an exact inert completion shape"
                  {:barrier-id id :observation observation}))))
  barriers)

(defn validate-case
  "Validate and canonicalize a constrained replay case. The case records exact
  site capabilities, an inert runtime manifest, actor-local expected events,
  terminal fingerprints, an extra-event policy, and build provenance."
  [case]
  (when-not (map? case)
    (invalid! :checkpoint-replay/invalid-case
              "checkpoint replay case must be a map"
              {:value case}))
  (when-not (= #{:sites :manifest :actor-events :outcomes :extra-events
                 :provenance}
               (set (keys case)))
    (invalid! :checkpoint-replay/invalid-case
              "checkpoint replay case must have an exact inert shape"
              {:keys (set (keys case))}))
  (let [sites-map (canonical-sites->map (:sites case))
        manifest-evidence
        (history/normalize
         (manifest->snapshot sites-map (:manifest case)))
        sites (:sites manifest-evidence)
        manifest (history/replay-manifest manifest-evidence)
        plan (plan-map manifest-evidence)
        extra-policy (:extra-events case)
        _ (when-not (contains? allowed-extra-policies extra-policy)
            (invalid! :checkpoint-replay/invalid-extra-events-policy
                      "replay extra-events must be :forbid or :allow-unplanned"
                      {:extra-events extra-policy}))
        actor-events (validate-actor-events! (:actor-events case) plan extra-policy)
        actors (set/union (set (keys actor-events))
                          (set (map first (keys plan))))
        outcomes (validate-outcomes! (:outcomes case) actors terminal-statuses
                                     :checkpoint-replay/invalid-outcomes)
        provenance (validate-provenance! (:provenance case))]
    {:schema 1
     :kind :jolt/checkpoint-replay
     :sites sites
     :manifest manifest
     :actor-events actor-events
     :outcomes outcomes
     :barriers (expected-barriers manifest-evidence)
     :extra-events extra-policy
     :provenance provenance}))

(defn runtime-manifest
  "Return the validated inert install value for a replay case."
  [case]
  (:manifest (validate-case case)))

(defn- normalized-evidence [value]
  (if (= :jolt/checkpoint-history (:kind value))
    ;; replay-manifest re-normalizes canonical evidence and rejects mutations.
    (do (history/replay-manifest value) value)
    (history/normalize value)))

(defn- project-event [{:keys [id hit action]}]
  {:id id :hit hit :action action})

(defn- actual-actor-events [evidence planned policy]
  (reduce
   (fn [out {:keys [actor] :as event}]
     (let [selector [actor (:id event) (:hit event)]]
       (if (or (= :forbid policy) (contains? planned selector))
         (update out actor (fnil conj []) (project-event event))
         out)))
   {}
   (:events evidence)))

(defn- mismatch [kind expected actual]
  {:kind kind :expected expected :actual actual})

(defn assess
  "Assess one run against a constrained replay case. Returns :reproduced,
  :mismatch, or :unresolved. Cross-actor global sequence permutations are
  intentionally ignored after checkpoint-history has verified integrity."
  [case run]
  (let [case (validate-case case)
        _ (when-not (and (map? run)
                         (= #{:evidence :outcomes :barriers :provenance}
                            (set (keys run))))
            (invalid! :checkpoint-replay/invalid-run
                      "checkpoint replay run must have an exact inert shape"
                      {:run run}))
        evidence (normalized-evidence (:evidence run))
        actors (set (keys (:actor-events case)))
        actual-outcomes (validate-outcomes!
                         (:outcomes run) actors run-statuses
                         :checkpoint-replay/invalid-run-outcomes)
        actual-provenance (validate-provenance! (:provenance run))
        actual-barriers (validate-run-barriers! (:barriers run)
                                                (:barriers case))
        expected-manifest (:manifest case)
        actual-manifest (history/replay-manifest evidence)
        planned (set (keys (plan-map evidence)))
        consumed (into #{}
                       (keep (fn [{:keys [actor id hit action]}]
                               (when action [actor id hit])))
                       (:events evidence))
        actual-events (actual-actor-events evidence planned (:extra-events case))
        mismatches
        (cond-> []
          (not= (:sites case) (:sites evidence))
          (conj (mismatch :checkpoint-replay/site-mismatch
                          (:sites case) (:sites evidence)))
          (not= expected-manifest actual-manifest)
          (conj (mismatch :checkpoint-replay/manifest-mismatch
                          expected-manifest actual-manifest))
          (not= planned consumed)
          (conj (mismatch :checkpoint-replay/plan-consumption-mismatch
                          planned consumed))
          (not= (:actor-events case) actual-events)
          (conj (mismatch :checkpoint-replay/actor-order-mismatch
                          (:actor-events case) actual-events))
          (not= (:outcomes case) actual-outcomes)
          (conj (mismatch :checkpoint-replay/outcome-mismatch
                          (:outcomes case) actual-outcomes))
          (not= (:barriers case) actual-barriers)
          (conj (mismatch :checkpoint-replay/barrier-completion-mismatch
                          (:barriers case) actual-barriers))
          (not= (:provenance case) actual-provenance)
          (conj (mismatch :checkpoint-replay/provenance-mismatch
                          (:provenance case) actual-provenance)))
        unresolved? (or (some #(contains? unresolved-statuses (:status %))
                              (vals actual-outcomes))
                        (some #(contains? #{:timeout :unresolved} (:status %))
                              (vals actual-barriers)))]
    {:schema 1
     :kind :jolt/checkpoint-replay-assessment
     :status (cond unresolved? :unresolved
                   (seq mismatches) :mismatch
                   :else :reproduced)
     :mismatches mismatches}))

(defn reproduced?
  "True only for a completed replay with no mismatch."
  [assessment]
  (= :reproduced (:status assessment)))
