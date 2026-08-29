(ns jolt.aspect-packs.core-async.model
  "Bounded linearizability model for fixed-buffer core.async operations."
  (:require [hegel.history :as history]))

(def ^:private max-operations 8)
(def ^:private operations
  #{:core-async/offer :core-async/poll :core-async/close})

(defn- exact-keys? [value expected]
  (and (map? value) (= expected (set (keys value)))))

(defn- channel-state? [state]
  (and (exact-keys? state #{:capacity :queue :closed?})
       (integer? (:capacity state))
       (pos? (:capacity state))
       (vector? (:queue state))
       (<= (count (:queue state)) (:capacity state))
       (boolean? (:closed? state))))

(defn initial-state
  "Build model state from a map of opaque channel tokens to capacities."
  [capacities]
  (when-not (and (map? capacities)
                 (every? some? (keys capacities))
                 (every? #(and (integer? %) (pos? %)) (vals capacities)))
    (throw (ex-info "core.async capacities must map channel tokens to positive integers"
                    {:type ::invalid-capacities})))
  (into {}
        (map (fn [[channel capacity]]
               [channel {:capacity capacity :queue [] :closed? false}]))
        capacities))

(defn- valid-state? [state]
  (and (map? state)
       (every? some? (keys state))
       (every? channel-state? (vals state))))

(defn- return? [operation value]
  (and (= :return (:outcome operation))
       (= value (:value operation))))

(defn- offer-step [state channel-state operation]
  (let [input (:input operation)
        terminal (:value operation)]
    (when (and (exact-keys? input #{:channel :value})
               (some? (:channel input))
               (some? (:value input))
               (exact-keys? terminal #{:result}))
      (cond
        (:closed? channel-state)
        (when (return? operation {:result :closed})
          {:state state})

        (= (:capacity channel-state) (count (:queue channel-state)))
        (when (return? operation {:result :full})
          {:state state})

        :else
        (when (return? operation {:result :accepted})
          {:state (update-in state [(:channel input) :queue]
                             conj (:value input))})))))

(defn- poll-step [state channel-state operation]
  (let [input (:input operation)
        terminal (:value operation)
        queue (:queue channel-state)]
    (when (and (exact-keys? input #{:channel})
               (some? (:channel input)))
      (if (seq queue)
        (when (and (exact-keys? terminal #{:result :value})
                   (= :value (:result terminal))
                   (= (first queue) (:value terminal))
                   (= :return (:outcome operation)))
          {:state (assoc-in state [(:channel input) :queue]
                            (vec (rest queue)))})
        (when (return? operation {:result :empty})
          {:state state})))))

(defn- close-step [state channel-state operation]
  (let [input (:input operation)]
    (when (and (exact-keys? input #{:channel})
               (some? (:channel input))
               (return? operation {:result :closed}))
      {:state (assoc-in state [(:channel input) :closed?] true)})))

(defn step
  "Apply one normalized `hegel.history` operation, or return nil when illegal."
  [state operation]
  (let [input (:input operation)
        channel (when (map? input) (:channel input))
        channel-state (get state channel)]
    (when (and (valid-state? state)
               (contains? operations (:operation operation))
               (some? channel-state)
               (not= :throw (:outcome operation)))
      (case (:operation operation)
        :core-async/offer (offer-step state channel-state operation)
        :core-async/poll (poll-step state channel-state operation)
        :core-async/close (close-step state channel-state operation)))))

(defn- options []
  {:max-operations max-operations
   :name :core-async/fixed-buffer-linearizable
   :partition-by #(get-in % [:input :channel])})

(defn linearization
  "Return a per-channel sequential witness for a complete history, or nil."
  [initial events]
  (when-not (valid-state? initial)
    (throw (ex-info "invalid core.async model state"
                    {:type ::invalid-state})))
  (history/linearization initial step events (options)))

(defn check!
  "Return a bounded linearization witness or throw with Hegel evidence."
  [initial events]
  (when-not (valid-state? initial)
    (throw (ex-info "invalid core.async model state"
                    {:type ::invalid-state})))
  (history/check! initial step events (options)))

;; Callback operations have a distinct sequential model. An accepted put first
;; owns a FIFO position, independently of whether its value currently occupies
;; the bounded buffer. This matters across close: close rejects later puts but
;; preserves ownership already admitted by an overlapping put. Capacity zero
;; always stores admitted ownership in :pending-puts. The correlation check
;; below prevents a completed unbuffered history from ending with an unmatched
;; rendezvous.

(def ^:private callback-max-operations 6)
(def ^:private callback-operations
  #{:core-async/put :core-async/take :core-async/close})

(def ^:private callback-target-operations
  #{:core-async/put-target :core-async/take-target})

(defn- callback-trace-invalid! [message events data]
  (throw (ex-info message
                  (merge {:hegel/origin
                          "hegel.history/core-async/callback-composite"
                          :type ::invalid-callback-composite
                          :hegel.history/event-count (count events)
                          :hegel.history/events events
                          :hegel.history/evidence-truncated? false}
                         data))))

(defn- target-terminal? [target]
  (case (:outcome target)
    :return (= {:result :target-returned} (:value target))
    :throw (and (exact-keys? (:value target)
                             #{:result :error-type})
                (= :target-threw (get-in target [:value :result]))
                (string? (get-in target [:value :error-type])))
    false))

(defn- resequence [events]
  (mapv (fn [index event] (assoc event :seq (inc index)))
        (range)
        events))

(defn callback-trace
  "Validate callback/target composition and return model-facing trace views.

  Every logical put!/take! owns exactly one synchronous target child. Target
  children are retained in `:target-operations` for causal assertions and are
  removed (without changing observation order) from `:logical-events` before
  linearizability search."
  [events]
  (let [all-operations
        (history/operations
         events
         {:max-operations (* 2 callback-max-operations)
          :name :core-async/callback-composite})
        logical-operations
        (filterv #(contains? callback-operations (:operation %))
                 all-operations)
        target-operations
        (filterv #(contains? callback-target-operations (:operation %))
                 all-operations)
        unknown-operations
        (remove #(or (contains? callback-operations (:operation %))
                     (contains? callback-target-operations (:operation %)))
                all-operations)
        callback-parents
        (filterv #(contains? #{:core-async/put :core-async/take}
                             (:operation %))
                 logical-operations)
        targets-by-parent
        (group-by #(get-in % [:invoke :parent-operation-id])
                  target-operations)
        parent-by-id (into {} (map (juxt :operation-id identity))
                           callback-parents)]
    (when (seq unknown-operations)
      (callback-trace-invalid!
       "callback trace contains an unknown operation" events
       {:operations (mapv :operation unknown-operations)}))
    (doseq [parent callback-parents]
      (let [targets (get targets-by-parent (:operation-id parent))
            target (first targets)
            expected (case (:operation parent)
                       :core-async/put :core-async/put-target
                       :core-async/take :core-async/take-target)]
        (when-not (= 1 (count targets))
          (callback-trace-invalid!
           "callback operation needs exactly one target child" events
           {:operation-id (:operation-id parent)
            :target-count (count targets)}))
        (when-not (and (= expected (:operation target))
                       (< (:invoke-seq parent) (:invoke-seq target))
                       (< (:invoke-seq target) (:terminal-seq parent))
                       (= (:input parent) (:input target))
                       (= (get-in parent [:invoke :context-id])
                          (get-in target [:invoke :context-id]))
                       (target-terminal? target))
          (callback-trace-invalid!
           "callback target child has invalid provenance or result" events
           {:operation-id (:operation-id parent)
            :target-operation-id (:operation-id target)}))))
    (doseq [target target-operations]
      (when-not (contains? parent-by-id
                           (get-in target [:invoke :parent-operation-id]))
        (callback-trace-invalid!
         "callback target child has no logical parent" events
         {:target-operation-id (:operation-id target)})))
    (let [target-ids (set (map :operation-id target-operations))]
      {:logical-events
       (->> events
            (remove #(contains? target-ids (:operation-id %)))
            vec
            resequence)
       :logical-operations logical-operations
       :target-operations target-operations})))

(defn callback-initial-state
  "Build callback model state for unbuffered and capacity-one channels."
  [capacities]
  (when-not (and (map? capacities)
                 (every? some? (keys capacities))
                 (every? #{0 1} (vals capacities)))
    (throw (ex-info "callback capacities must map channel tokens to zero or one"
                    {:type ::invalid-callback-capacities})))
  (into {}
        (map (fn [[channel capacity]]
               [channel {:capacity capacity
                         :buffer []
                         :pending-puts []
                         :open? true}]))
        capacities))

(defn- callback-channel-state? [state]
  (and (exact-keys? state
                    #{:capacity :buffer :pending-puts :open?})
       (contains? #{0 1} (:capacity state))
       (vector? (:buffer state))
       (<= (count (:buffer state)) (:capacity state))
       (vector? (:pending-puts state))
       (boolean? (:open? state))))

(defn- valid-callback-state? [state]
  (and (map? state)
       (every? some? (keys state))
       (every? callback-channel-state? (vals state))))

(defn- callback-carrier? [operation terminal]
  (= {:parent-operation-id (:operation-id operation)
      :context-id (get-in operation [:invoke :context-id])}
     (:carrier terminal)))

(defn- callback-return? [operation result-keys result]
  (let [terminal (:value operation)]
    (and (= :return (:outcome operation))
         (exact-keys? terminal (conj result-keys :carrier))
         (callback-carrier? operation terminal)
         (= result (dissoc terminal :carrier)))))

(defn- callback-put-step [state channel-state operation]
  (let [input (:input operation)
        channel (:channel input)]
    (when (and (exact-keys? input #{:channel :value :on-caller?})
               (some? channel)
               (some? (:value input))
               (boolean? (:on-caller? input)))
      (cond
        (not (:open? channel-state))
        (when (callback-return? operation #{:result} {:result :closed})
          {:state state})

        :else
        (when (callback-return? operation #{:result} {:result :accepted})
          (let [buffer (:buffer channel-state)
                pending (:pending-puts channel-state)
                buffer-space? (< (count buffer) (:capacity channel-state))]
            {:state
             (if (and buffer-space? (empty? pending))
               (update-in state [channel :buffer] conj (:value input))
               (update-in state [channel :pending-puts]
                          conj (:value input)))}))))))

(defn- callback-take-step [state channel-state operation]
  (let [input (:input operation)
        channel (:channel input)
        buffer (:buffer channel-state)
        pending (:pending-puts channel-state)]
    (when (and (exact-keys? input #{:channel :on-caller?})
               (some? channel)
               (boolean? (:on-caller? input)))
      (cond
        (seq buffer)
        (when (callback-return? operation #{:result :value}
                                {:result :value :value (first buffer)})
          (let [remaining-buffer (vec (rest buffer))
                promote? (and (seq pending)
                              (< (count remaining-buffer)
                                 (:capacity channel-state)))]
            {:state
             (cond-> (assoc-in state [channel :buffer]
                               (cond-> remaining-buffer
                                 promote? (conj (first pending))))
               promote? (assoc-in [channel :pending-puts]
                                  (vec (rest pending))))}))

        (seq pending)
        (when (callback-return? operation #{:result :value}
                                {:result :value :value (first pending)})
          {:state (assoc-in state [channel :pending-puts]
                            (vec (rest pending)))})

        (not (:open? channel-state))
        (when (callback-return? operation #{:result} {:result :closed})
          {:state state})))))

(defn callback-step
  "Apply one callback put!/take!/close! operation, or nil when illegal."
  [state operation]
  (let [input (:input operation)
        channel (when (map? input) (:channel input))
        channel-state (get state channel)]
    (when (and (valid-callback-state? state)
               (contains? callback-operations (:operation operation))
               (some? channel-state)
               (not= :throw (:outcome operation)))
      (case (:operation operation)
        :core-async/put
        (callback-put-step state channel-state operation)

        :core-async/take
        (callback-take-step state channel-state operation)

        :core-async/close
        (let [input (:input operation)]
          (when (and (exact-keys? input #{:channel})
                     (some? (:channel input))
                     (return? operation {:result :closed}))
            {:state (-> state
                        (assoc-in [(:channel input) :open?] false))}))))))

(defn- callback-options []
  {:max-operations callback-max-operations
   :name :core-async/callback-linearizable
   :partition-by #(get-in % [:input :channel])})

(defn- unbuffered-pairs? [initial operations]
  (every?
   (fn [[channel {:keys [capacity]}]]
     (or
      (not (zero? capacity))
      (let [channel-operations
            (filter #(= channel (get-in % [:input :channel])) operations)
            puts (->> channel-operations
                      (filter #(and (= :core-async/put (:operation %))
                                    (= :accepted
                                       (get-in % [:value :result]))))
                      (map #(get-in % [:input :value]))
                      frequencies)
            takes (->> channel-operations
                       (filter #(and (= :core-async/take (:operation %))
                                     (= :value
                                        (get-in % [:value :result]))))
                       (map #(get-in % [:value :value]))
                       frequencies)]
        (= puts takes))))
   initial))

(defn callback-linearization
  "Return a bounded callback-history witness, or nil when none exists."
  [initial events]
  (when-not (valid-callback-state? initial)
    (throw (ex-info "invalid core.async callback model state"
                    {:type ::invalid-callback-state})))
  (let [opts (callback-options)
        logical-events (:logical-events (callback-trace events))
        operations (history/operations logical-events opts)]
    (when (unbuffered-pairs? initial operations)
      (history/linearization initial callback-step logical-events opts))))

(defn check-callback!
  "Return a callback-history witness or throw bounded Hegel evidence."
  [initial events]
  (when-not (valid-callback-state? initial)
    (throw (ex-info "invalid core.async callback model state"
                    {:type ::invalid-callback-state})))
  (let [opts (callback-options)
        logical-events (:logical-events (callback-trace events))
        operations (history/operations logical-events opts)]
    (when-not (unbuffered-pairs? initial operations)
      (throw (ex-info "unbuffered callback history has an unmatched rendezvous"
                      {:hegel/origin "hegel.history/core-async/callback-linearizable"
                       :type ::unmatched-rendezvous
                       :hegel.history/event-count (count logical-events)
                       :hegel.history/events logical-events
                       :hegel.history/evidence-truncated? false})))
    (history/check! initial callback-step logical-events opts)))
