(ns jolt.aspect-packs.mycelium.provider
  "Canonical provider-neutral history consumer for Mycelium graph execution."
  (:require [jolt.aspect-packs.history :as history]))

(def target-revision
  "Exact Samizdat revision carrying the pinned seam manifest."
  "ff9cfd8cf6bf08c0f61b71cc98eee8c354efa861")

(def seam-revision
  "Exact reviewed source revision that introduced the Mycelium seams."
  "dd13b4b933d3db80a319d2c7b27af4ee6767fca5")

(def ^:private max-nodes 512)
(def ^:private max-edges 2048)
(def ^:private max-keyword-length 255)
(def ^:private execution-kinds #{:sync :async :resume :ring :composed})
(def ^:private terminal-phases #{:return :throw :cancel})
(def ^:private graph-id-pattern #"sha256:[0-9a-f]{64}")
(def ^:private execution-id-pattern
  #"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

;; Execution ids are globally unique while open. Each entry retains its owning
;; journal so portable callback events can recover history after dynamic
;; bindings have been lost at a thread/fiber boundary.
(defonce ^:private executions (atom {}))

(defn reset-state!
  "Clear provider-owned in-flight state. Intended for isolated test fixtures."
  []
  (reset! executions {})
  nil)

(defn- invalid! [kind]
  (throw (ex-info "invalid Mycelium semantic event" {:kind kind})))

(defn- exact-keys? [value expected]
  (and (map? value) (= expected (set (keys value)))))

(defn- graph-id? [value]
  (and (string? value) (boolean (re-matches graph-id-pattern value))))

(defn- execution-id? [value]
  (and (string? value) (boolean (re-matches execution-id-pattern value))))

(defn- bounded-keyword? [value]
  (and (keyword? value) (<= (count (str value)) max-keyword-length)))

(defn- boundary? [value]
  (and (exact-keys? value #{:node :cell})
       (bounded-keyword? (:node value))
       (bounded-keyword? (:cell value))))

(defn- node? [value]
  (boundary? value))

(defn- edge-key? [value]
  (and (vector? value)
       (= 3 (count value))
       (every? bounded-keyword? value)))

(defn- edge? [value]
  (and (exact-keys? value #{:edge-key :source :label :target})
       (edge-key? (:edge-key value))
       (= (:edge-key value)
          [(:source value) (:label value) (:target value)])))

(defn- graph? [value]
  (and (exact-keys? value
                    #{:schema :graph-id :entry :terminals :nodes :edges})
       (= 1 (:schema value))
       (graph-id? (:graph-id value))
       (or (nil? (:entry value)) (boundary? (:entry value)))
       (vector? (:terminals value))
       (<= (count (:terminals value)) max-nodes)
       (every? boundary? (:terminals value))
       (vector? (:nodes value))
       (<= (count (:nodes value)) max-nodes)
       (every? node? (:nodes value))
       (vector? (:edges value))
       (<= (count (:edges value)) max-edges)
       (every? edge? (:edges value))
       (= (count (:edges value))
          (count (distinct (map :edge-key (:edges value)))))))

(defn- invoke-event! [event]
  (when-not (and (exact-keys? event
                              #{:schema :graph-id :execution-id :kind
                                :phase :graph})
                 (= 1 (:schema event))
                 (= :invoke (:phase event))
                 (execution-id? (:execution-id event))
                 (contains? execution-kinds (:kind event))
                 (graph-id? (:graph-id event))
                 (graph? (:graph event))
                 (= (:graph-id event) (get-in event [:graph :graph-id])))
    (invalid! :mycelium/invalid-lifecycle-invoke))
  event)

(defn- terminal-event! [event]
  (when-not (and (exact-keys? event
                              #{:schema :graph-id :execution-id :kind :phase})
                 (= 1 (:schema event))
                 (contains? terminal-phases (:phase event))
                 (execution-id? (:execution-id event))
                 (contains? execution-kinds (:kind event))
                 (graph-id? (:graph-id event)))
    (invalid! :mycelium/invalid-lifecycle-terminal))
  event)

(defn- edge-event! [event]
  (when-not (and (exact-keys? event #{:schema :execution-id :edge-key})
                 (= 1 (:schema event))
                 (execution-id? (:execution-id event))
                 (edge-key? (:edge-key event)))
    (invalid! :mycelium/invalid-edge-event))
  event)

(defn- lifecycle-input [event]
  (select-keys event [:execution-id :graph-id :kind :graph]))

(defn- terminal-value [phase]
  {:outcome (case phase :return :ok :throw :error :cancel :cancel)})

(defn- close-handle! [handle phase]
  (let [value (terminal-value phase)]
    (if (= :throw phase)
      (history/throw! handle value)
      (history/return! handle value))))

(defn around-workflow-event
  "Consume one bounded Mycelium lifecycle marker.

  Invoke retains the normalized graph once. Return, throw, and cancel correlate
  by execution-id and close the canonical history handle exactly once. Source
  event identity is preserved and no workflow data, resources, or errors are
  inspected."
  [join-point [event] proceed]
  (let [ambient-journal history/*journal*
        phase (:phase event)]
    (if (= :invoke phase)
      (if-not ambient-journal
        (proceed)
        (let [event (invoke-event! event)
              execution-id (:execution-id event)]
          (locking executions
            (when (contains? @executions execution-id)
              (invalid! :mycelium/duplicate-lifecycle-invoke))
            (let [caller-context history/*context-id*
                  handle (history/begin! ambient-journal join-point
                                         (lifecycle-input event))]
              (swap! executions assoc execution-id
                     {:journal ambient-journal
                      :handle handle
                      :context-id caller-context
                      :graph-id (:graph-id event)
                      :kind (:kind event)
                      :last-edge-handle nil
                      :edge-keys (set (map :edge-key
                                           (get-in event [:graph :edges])))})
              (try
                (proceed)
                (catch Throwable error
                  (swap! executions dissoc execution-id)
                  (try (history/throw! handle {:outcome :error})
                       (catch Throwable _))
                  (throw error)))))))
      (let [execution-id (:execution-id event)
            state (get @executions execution-id)]
        (if-not state
          ;; No bound journal and no known execution is the ordinary inert
          ;; plain-build path. A bound journal makes a missing lifecycle a
          ;; malformed recorded history and therefore fails closed.
          (if-not ambient-journal
            (proceed)
            (do (terminal-event! event)
                (invalid! :mycelium/missing-lifecycle-invoke)))
          (locking executions
            (let [event (terminal-event! event)
                  {:keys [journal handle graph-id kind]}
                  (get @executions execution-id)]
              (when-not handle
                (invalid! :mycelium/missing-lifecycle-invoke))
              (when (and ambient-journal
                         (not (identical? ambient-journal journal)))
                (invalid! :mycelium/conflicting-history-journal))
              (when-not (and (= graph-id (:graph-id event))
                             (= kind (:kind event)))
                (invalid! :mycelium/lifecycle-identity-mismatch))
              (let [result (try
                             (history/call-with-carrier
                              (history/carrier handle) proceed)
                             (catch Throwable error
                               (swap! executions dissoc execution-id)
                               (try (history/throw! handle {:outcome :error})
                                    (catch Throwable _))
                               (throw error)))]
                (close-handle! handle phase)
                (swap! executions dissoc execution-id)
                result))))))))

(defn around-edge-event
  "Record one selected Mycelium edge as a nested canonical operation.

  The edge must belong to the normalized graph retained by its open lifecycle.
  Only execution-id, graph-id, and edge-key enter history."
  [join-point [event] proceed]
  (let [execution-id (:execution-id event)
        state (get @executions execution-id)]
    (if-not state
      (if-not history/*journal*
        (proceed)
        (do (edge-event! event)
            (invalid! :mycelium/missing-lifecycle-invoke)))
      (locking executions
        (let [event (edge-event! event)
              {:keys [journal handle context-id graph-id edge-keys
                      last-edge-handle]}
              (get @executions execution-id)]
          (when-not handle
            (invalid! :mycelium/missing-lifecycle-invoke))
          (when (and history/*journal*
                     (not (identical? history/*journal* journal)))
            (invalid! :mycelium/conflicting-history-journal))
          (when-not (contains? edge-keys (:edge-key event))
            (invalid! :mycelium/unknown-graph-edge))
          (let [edge-handle
                (history/begin!
                 journal join-point
                 {:execution-id (:execution-id event)
                  :graph-id graph-id
                  :edge-key (:edge-key event)}
                 {:parent-operation-id (history/operation-id handle)
                  :context-id context-id
                  :causal-links (if last-edge-handle
                                  (history/causal-links journal
                                                        [last-edge-handle])
                                  [])})]
            (swap! executions assoc-in [execution-id :last-edge-handle]
                   edge-handle)
            (try
              (let [result (history/call-with-carrier
                            (history/carrier edge-handle) proceed)]
                (history/return! edge-handle {:outcome :selected})
                result)
              (catch Throwable error
                (try (history/throw! edge-handle {:outcome :error})
                     (catch Throwable _))
                (throw error)))))))))

(def aspect-provider
  {:schema 1
   :libraries {'yogthos/samizdat seam-revision}
   :roles {:mycelium/workflow
           {:fn 'jolt.aspect-packs.mycelium.provider/around-workflow-event
            :contract :args-v1}
           :mycelium/edge-decision
           {:fn 'jolt.aspect-packs.mycelium.provider/around-edge-event
            :contract :args-v1}}})
