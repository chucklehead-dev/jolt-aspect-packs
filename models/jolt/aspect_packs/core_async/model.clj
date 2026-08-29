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
