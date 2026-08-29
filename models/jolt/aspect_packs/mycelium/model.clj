(ns jolt.aspect-packs.mycelium.model
  "Independent Hegel oracle for canonical Mycelium execution histories."
  (:require [hegel.trace :as trace]))

(def ^:private lifecycle-operation :mycelium.workflow/lifecycle)
(def ^:private edge-operation :mycelium.workflow/edge-decision)
(def ^:private execution-kinds #{:sync :async :resume :ring :composed})
(def ^:private lifecycle-outcomes #{:ok :error :cancel})
(def ^:private max-keyword-length 255)
(def ^:private graph-id-pattern #"sha256:[0-9a-f]{64}")
(def ^:private execution-id-pattern
  #"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

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

(defn- edge-key? [value]
  (and (vector? value) (= 3 (count value))
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
       (<= (count (:terminals value)) 512)
       (every? boundary? (:terminals value))
       (vector? (:nodes value))
       (<= (count (:nodes value)) 512)
       (every? boundary? (:nodes value))
       (vector? (:edges value))
       (<= (count (:edges value)) 2048)
       (every? edge? (:edges value))
       (= (count (:edges value))
          (count (distinct (map :edge-key (:edges value)))))))

(def semantic-invocations
  (trace/rule
   :mycelium/semantic-invocations
   (fn [events]
     (every?
      (fn [event]
        (if (not= :invoke (:phase event))
          true
          (case (:operation event)
            :mycelium.workflow/lifecycle
            (let [input (:input event)]
              (and (exact-keys? input
                                #{:execution-id :graph-id :kind :graph})
                   (execution-id? (:execution-id input))
                   (graph-id? (:graph-id input))
                   (contains? execution-kinds (:kind input))
                   (graph? (:graph input))
                   (= (:graph-id input) (get-in input [:graph :graph-id]))))

            :mycelium.workflow/edge-decision
            (let [input (:input event)]
              (and (exact-keys? input #{:execution-id :graph-id :edge-key})
                   (execution-id? (:execution-id input))
                   (graph-id? (:graph-id input))
                   (edge-key? (:edge-key input))))

            false)))
      events))))

(def shaped-terminals
  (trace/rule
   :mycelium/shaped-terminals
   (fn [events]
     (every?
      (fn [event]
        (if (= :invoke (:phase event))
          true
          (let [value (:value event)]
            (and (exact-keys? value #{:outcome})
                 (case (:operation
                        (first (filter #(and (= (:operation-id event)
                                               (:operation-id %))
                                            (= :invoke (:phase %)))
                                      events)))
                   :mycelium.workflow/lifecycle
                   (and (contains? lifecycle-outcomes (:outcome value))
                        (= (= :throw (:phase event))
                           (= :error (:outcome value))))

                   :mycelium.workflow/edge-decision
                   (if (= :throw (:phase event))
                     (= :error (:outcome value))
                     (= :selected (:outcome value)))

                   false)))))
      events))))

(def execution-correlations
  (trace/rule
   :mycelium/execution-correlations
   (fn [events]
     (let [invokes (filter #(= :invoke (:phase %)) events)
           lifecycles (filter #(= lifecycle-operation (:operation %)) invokes)
           by-execution (group-by #(get-in % [:input :execution-id]) lifecycles)]
       (and
        (every? #(= 1 (count %)) (vals by-execution))
        (every?
         (fn [edge]
           (let [input (:input edge)
                 lifecycle (first (get by-execution (:execution-id input)))
                 graph-edges (set (map :edge-key
                                       (get-in lifecycle [:input :graph :edges])))]
             (and lifecycle
                  (< (:seq lifecycle) (:seq edge))
                  (= (:operation-id lifecycle) (:parent-operation-id edge))
                  (= (:context-id lifecycle) (:context-id edge))
                  (= (get-in lifecycle [:input :graph-id]) (:graph-id input))
                  (contains? graph-edges (:edge-key input)))))
         (filter #(= edge-operation (:operation %)) invokes)))))))

(def edge-progression
  (trace/rule
   :mycelium/edge-progression
   (fn [events]
     (let [edges (->> events
                      (filter #(and (= :invoke (:phase %))
                                    (= edge-operation (:operation %))))
                      (group-by #(get-in % [:input :execution-id])))]
       (every?
        (fn [execution-edges]
          (let [ordered (sort-by :seq execution-edges)]
            (every?
             true?
             (map-indexed
              (fn [index edge]
                (= (if (zero? index)
                     []
                     [(:operation-id (nth ordered (dec index)))])
                   (:causal-links edge)))
              ordered))))
        (vals edges))))))

(def rules
  [(trace/contiguous-sequence :mycelium/contiguous-history 1)
   (trace/closed-lifecycles :mycelium/closed-operation-lifecycles)
   (trace/synchronous-parentage :mycelium/nested-edge-parentage)
   (trace/causal-links :mycelium/canonical-causal-links)
   (trace/context-coherence :mycelium/context-coherence)
   semantic-invocations
   shaped-terminals
   execution-correlations
   edge-progression])

(defn check! [events]
  (trace/check! events rules {:max-events 4096}))
