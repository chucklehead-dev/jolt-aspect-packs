(ns jolt.aspect-packs.chdb-durable.model
  (:require [hegel.trace :as trace]))

(def ^:private operations
  #{:durable/acquire :durable/publish-wal :durable/publish-checkpoint
    :durable/commit-reference :durable/renew :durable/release})

(defn- opaque-writer? [value]
  (and (string? value) (boolean (re-matches #"opaque-[1-9][0-9]*" value))))

(defn- writer? [value]
  (and (map? value)
       (= #{:writer :generation} (set (keys value)))
       (opaque-writer? (:writer value))
       (integer? (:generation value))
       (pos? (:generation value))))

(defn- reference? [value]
  (and (map? value)
       (= #{:kind :generation :sequence :object :attempt} (set (keys value)))
       (contains? #{:wal :checkpoint} (:kind value))
       (integer? (:generation value))
       (pos? (:generation value))
       (integer? (:sequence value))
       (pos? (:sequence value))
       (opaque-writer? (:object value))
       (opaque-writer? (:attempt value))))

(defn- head? [value]
  (and (map? value)
       (contains? #{#{:generation :writer :manifest-sequence}
                    #{:generation :writer :manifest-sequence :last-reference}}
                  (set (keys value)))
       (integer? (:generation value))
       (pos? (:generation value))
       (or (nil? (:writer value)) (opaque-writer? (:writer value)))
       (integer? (:manifest-sequence value))
       (not (neg? (:manifest-sequence value)))
       (or (not (contains? value :last-reference))
           (reference? (:last-reference value)))))

(defn- input? [{:keys [command writer kind reference payload-size now
                        expires-at force?] :as input}]
  (and
   (map? input)
   (case command
     :acquire
     (and (= #{:command :writer :now :expires-at :force?} (set (keys input)))
          (opaque-writer? writer) (number? now) (number? expires-at)
          (boolean? force?))

     :publish
     (and (= #{:command :writer :kind :payload-size} (set (keys input)))
          (writer? writer) (= :wal kind)
          (integer? payload-size) (not (neg? payload-size)))

     :checkpoint-publish
     (and (= #{:command :writer} (set (keys input))) (writer? writer))

     :commit-attempt
     (and (= #{:command :writer :kind :reference} (set (keys input)))
          (writer? writer) (= kind (:kind reference)) (reference? reference))

     :renew-attempt
     (and (= #{:command :writer :expires-at} (set (keys input)))
          (writer? writer) (number? expires-at))

     :release-attempt
     (and (= #{:command :writer} (set (keys input))) (writer? writer))

     false)))

(def semantic-invocations
  (trace/rule
   :chdb-durable/semantic-invocations
   (fn [events]
     (every? (fn [event]
               (or (not= :invoke (:phase event))
                   (and (contains? operations (:operation event))
                        (input? (:input event)))))
             events))))

(def shaped-terminals
  (trace/rule
   :chdb-durable/shaped-terminals
   (fn [events]
     (let [invocations (into {} (map (juxt :operation-id identity))
                             (filter #(= :invoke (:phase %)) events))]
       (every?
        (fn [event]
          (if (= :invoke (:phase event))
            true
            (let [operation (:operation (get invocations (:operation-id event)))
                  value (:value event)]
              (and (map? value)
                   (if (= :throw (:phase event))
                     (and (= #{:outcome :error-type} (set (keys value)))
                          (= :error (:outcome value))
                          (keyword? (:error-type value)))
                     (case operation
                       :durable/acquire
                       (and (contains? #{:acquired :reconciled} (:outcome value))
                            (= #{:outcome :head} (set (keys value)))
                            (head? (:head value)))
                       (:durable/publish-wal :durable/publish-checkpoint)
                       (and (contains? #{:published :already-published}
                                       (:outcome value))
                            (= #{:outcome :reference} (set (keys value)))
                            (reference? (:reference value)))
                       (:durable/commit-reference :durable/renew :durable/release)
                       (and (contains? #{:committed :reconciled} (:outcome value))
                            (= #{:outcome :head} (set (keys value)))
                            (head? (:head value)))
                       false))))))
        events)))))

(defn- transition [state event]
  (if-not (contains? #{:return :throw} (:phase event))
    state
    (let [invoke (get-in state [:invocations (:operation-id event)])]
      (if (= :throw (:phase event))
        state
        (let [operation (:operation invoke)
              input (:input invoke)
              value (:value event)
              next-head (:head value)
              prior-head (:head state)
              valid?
              (case operation
                :durable/acquire
                (and (= (:writer input) (:writer next-head))
                     (or (nil? prior-head)
                         (and (> (:generation next-head)
                                 (:generation prior-head))
                              (= (:manifest-sequence next-head)
                                 (:manifest-sequence prior-head)))))

                (:durable/publish-wal :durable/publish-checkpoint)
                (let [reference (:reference value)]
                  (and (= (:generation (:writer input))
                          (:generation reference))
                       (or (nil? prior-head)
                           (= (inc (:manifest-sequence prior-head))
                              (:sequence reference)))))

                :durable/commit-reference
                (and (= (:reference input) (:last-reference next-head))
                     (= (:generation (:writer input)) (:generation next-head))
                     (or (nil? prior-head)
                         (and (= (:generation prior-head) (:generation next-head))
                              (= (inc (:manifest-sequence prior-head))
                                 (:manifest-sequence next-head)))))

                :durable/renew
                (and (= (:generation (:writer input)) (:generation next-head))
                     (or (nil? prior-head) (= prior-head next-head)))

                :durable/release
                (and (= (:generation (:writer input)) (:generation next-head))
                     (nil? (:writer next-head))
                     (or (nil? prior-head)
                         (= (dissoc prior-head :writer)
                            (dissoc next-head :writer))))

                false)]
          (cond-> (assoc state :valid? (and (:valid? state) valid?))
            next-head (assoc :head next-head)))))))

(def control-transitions
  (trace/event-model
   :chdb-durable/control-transitions
   {:initial {:valid? true :invocations {} :head nil}
    :step (fn [state event]
            (if (= :invoke (:phase event))
              (assoc-in state [:invocations (:operation-id event)] event)
              (transition state event)))
    :invariant (fn [state _] (:valid? state))}))

(def rules
  [(trace/contiguous-sequence :chdb-durable/contiguous-history 1)
   (trace/closed-lifecycles :chdb-durable/closed-operation-lifecycles)
   (trace/synchronous-parentage :chdb-durable/synchronous-parentage)
   (trace/causal-links :chdb-durable/canonical-causal-links)
   (trace/context-coherence :chdb-durable/context-coherence)
   semantic-invocations
   shaped-terminals
   control-transitions])

(declare check!)

(defn commands
  "Project checked aspect events into the command/outcome vocabulary shared
  with the Quint fixed-trace adapter."
  [events]
  (check! events)
  (let [terminals (into {} (map (juxt :operation-id :value))
                        (remove #(= :invoke (:phase %)) events))]
    (mapv (fn [event]
            (assoc (:input event) :result (get terminals (:operation-id event))))
          (filter #(= :invoke (:phase %)) events))))

(defn check! [events]
  (trace/check! events rules {:max-events 512}))
