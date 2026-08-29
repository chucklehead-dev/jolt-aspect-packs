(ns jolt.aspect-packs.history)

;; Target-neutral semantic history capture. Library packs own input shaping,
;; provider maps, roles, and advice namespaces.

(def ^:dynamic *journal* nil)
(def ^:dynamic *parent-operation-id* nil)
(def ^:dynamic *context-id* nil)

(defprotocol ^:private JournalAccess
  (-journal-read [journal])
  (-journal-swap [journal f])
  (-journal-cas [journal old-state new-state]))

(defprotocol ^:private CapabilityAccess
  (-capability-data [capability]))

(defn- journal-call [journal operation & args]
  (when-not (satisfies? JournalAccess journal)
    (throw (ex-info "invalid history journal" {:kind :history/invalid-journal})))
  (case operation
    :read (-journal-read journal)
    :swap (-journal-swap journal (first args))
    :cas (-journal-cas journal (first args) (second args))))

(defn- capability-data [value kind]
  (when-not (satisfies? CapabilityAccess value)
    (throw (ex-info (str "invalid history " (name kind))
                    {:kind (keyword "history" (str "invalid-" (name kind)))})))
  (let [data (-capability-data value)]
    (when-not (= kind (:kind data))
      (throw (ex-info (str "invalid history " (name kind))
                      {:kind (keyword "history" (str "invalid-" (name kind)))})))
    data))

(defn- capability [data]
  (reify CapabilityAccess
    (-capability-data [_] data)))

(defn journal
  "Create an opaque, thread-safe semantic history journal."
  []
  (let [state (atom {:next-seq 1
                     :next-operation-id 0
                     :next-opaque-id 1
                     :opaque-values {}
                     :open-operations {}
                     :events []})]
    (reify JournalAccess
      (-journal-read [_] @state)
      (-journal-swap [_ f] (swap! state f))
      (-journal-cas [_ old new] (compare-and-set! state old new)))))

(defn opaque-token!
  "Return a journal-local stable token for value without placing value in an
  emitted event. The private source value cannot be recovered through the API."
  [journal value]
  (let [state (journal-call journal :swap
                            (fn [{:keys [next-opaque-id opaque-values] :as state}]
                              (if (contains? opaque-values value)
                                state
                                (-> state
                                    (assoc :next-opaque-id (inc next-opaque-id))
                                    (assoc-in [:opaque-values value]
                                              (str "opaque-" next-opaque-id))))))]
    (get-in state [:opaque-values value])))

(defn events
  "Return immutable events without private ownership or opaque source values."
  [journal]
  (:events (journal-call journal :read)))

(defn open-operation-ids
  "Return currently open operation ids for bounded teardown diagnostics."
  [journal]
  (-> (journal-call journal :read) :open-operations keys sort vec))

(defn assert-complete!
  "Fail with privacy-safe diagnostics when a journal has open operations."
  [journal]
  (let [ids (open-operation-ids journal)]
    (when (seq ids)
      (throw (ex-info "history journal has open operations"
                      {:kind :history/open-operations :operation-ids ids})))
    true))

(defn begin!
  "Begin an operation and return an opaque exactly-once handle.

  Input and causal links must already be bounded and privacy-shaped by the
  library pack. Parentage denotes causality, so a child may outlive its parent."
  ([journal join-point input] (begin! journal join-point input {}))
  ([journal join-point input opts]
   (when (and *journal* (not (identical? journal *journal*)))
     (throw (ex-info "history child operation uses a different journal"
                     {:kind :history/journal-mismatch})))
   (when-not (map? opts)
     (throw (ex-info "history begin options must be a map"
                     {:kind :history/invalid-options})))
   (let [allowed #{:parent-operation-id :context-id :causal-links}
         unknown (seq (remove allowed (keys opts)))]
     (when unknown
       (throw (ex-info "unsupported history begin option"
                       {:kind :history/invalid-options
                        :unknown-keys (vec unknown)}))))
   (when-not (and (some? (:id join-point))
                  (some? (:site-id join-point))
                  (some? (:build-identity join-point)))
     (throw (ex-info "history join point lacks stable provenance"
                     {:kind :history/invalid-join-point})))
   (let [token (Object.)
         parent-id (if (contains? opts :parent-operation-id)
                     (:parent-operation-id opts) *parent-operation-id*)
         context-id (if (contains? opts :context-id)
                      (:context-id opts) *context-id*)
         causal-links (vec (or (:causal-links opts) []))
         state (journal-call
                 journal :swap
                 (fn [{:keys [next-seq next-operation-id] :as state}]
                   (let [event {:seq next-seq
                                :operation-id next-operation-id
                                :parent-operation-id parent-id
                                :context-id context-id
                                :causal-links causal-links
                                :phase :invoke
                                :operation (:id join-point)
                                :site-id (:site-id join-point)
                                :build-identity (:build-identity join-point)
                                :input input}]
                     (-> state
                         (assoc :next-seq (inc next-seq)
                                :next-operation-id (inc next-operation-id))
                         (assoc-in [:open-operations next-operation-id] token)
                         (update :events conj event)))))
         operation-id (:operation-id (peek (:events state)))]
     (capability {:kind :handle :journal journal :operation-id operation-id
                  :context-id context-id :token token}))))

(defn operation-id [handle]
  (:operation-id (capability-data handle :handle)))

(defn carrier
  "Capture an opaque causal carrier for child callbacks, threads, or fibers."
  [handle]
  (let [{:keys [journal operation-id context-id]} (capability-data handle :handle)]
    (capability {:kind :carrier :journal journal
                 :parent-operation-id operation-id :context-id context-id})))

(defn call-with-carrier [carrier f]
  (when-not (ifn? f)
    (throw (ex-info "invalid history carrier callback"
                    {:kind :history/invalid-callback})))
  (let [{:keys [journal parent-operation-id context-id]}
        (capability-data carrier :carrier)]
    (binding [*journal* journal *parent-operation-id* parent-operation-id
              *context-id* context-id]
      (f))))

(defn wrap-carrier
  "Return a variadic callback that installs carrier for its dynamic call."
  [carrier f]
  (when-not (ifn? f)
    (throw (ex-info "invalid history carrier callback"
                    {:kind :history/invalid-callback})))
  (fn [& args] (call-with-carrier carrier #(apply f args))))

(defn- try-terminal! [handle phase value]
  (let [{:keys [journal operation-id token]} (capability-data handle :handle)]
    (loop []
      (let [{:keys [next-seq open-operations] :as old} (journal-call journal :read)]
        (if-not (identical? token (get open-operations operation-id))
          {:closed? false :operation-id operation-id}
          (let [new (-> old
                        (assoc :next-seq (inc next-seq))
                        (update :open-operations dissoc operation-id)
                        (update :events conj {:seq next-seq
                                              :operation-id operation-id
                                              :phase phase :value value}))]
            (if (journal-call journal :cas old new)
              {:closed? true :operation-id operation-id :value value}
              (recur))))))))

(defn try-return!
  "Race-aware return close. Exactly one contender receives `:closed? true`."
  [handle value]
  (try-terminal! handle :return value))

(defn try-throw!
  "Race-aware throw close. Exactly one contender receives `:closed? true`."
  [handle value]
  (try-terminal! handle :throw value))

(defn return! [handle value]
  (let [result (try-return! handle value)]
    (when-not (:closed? result)
      (throw (ex-info "history operation is already closed or unowned"
                      {:kind :history/already-terminal
                       :operation-id (:operation-id result) :phase :return})))
    value))

(defn throw! [handle value]
  (let [result (try-throw! handle value)]
    (when-not (:closed? result)
      (throw (ex-info "history operation is already closed or unowned"
                      {:kind :history/already-terminal
                       :operation-id (:operation-id result) :phase :throw})))
    value))

(defn invoke!
  "Record one synchronous operation while preserving application identity.
  Recorder faults after an application throw never replace that Throwable;
  recorder faults after success fail closed. Pack-owned `:return-fn` and
  `:throw-fn` shape terminal history data."
  ([journal join-point input proceed]
   (invoke! journal join-point input {} proceed))
  ([journal join-point input opts proceed]
   (let [return-fn (or (:return-fn opts) (constantly :returned))
         throw-fn (or (:throw-fn opts) (constantly :thrown))
         handle (begin! journal join-point input)
         outcome (try
                   {:phase :return
                    :value (call-with-carrier (carrier handle) proceed)}
                   (catch Throwable error {:phase :throw :value error}))]
     (if (= :return (:phase outcome))
       (let [value (:value outcome)]
         (return! handle (return-fn value))
         value)
       (let [error (:value outcome)]
         (try (throw! handle (throw-fn error)) (catch Throwable _))
         (throw error))))))
