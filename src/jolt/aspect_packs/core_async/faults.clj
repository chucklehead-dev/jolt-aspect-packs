(ns jolt.aspect-packs.core-async.faults
  "Explicitly enabled, test-only faults for public core.async call sites.")

(def target-revision
  "Exact Jolt revision validated by this fault provider."
  "db00fad945f9145d4a5452039da18f9957a9d0bc")

(def ^:dynamic *action* nil)
(def ^:dynamic *decisions* nil)

(def ^:private operation-ids
  #{:core-async/offer :core-async/poll :core-async/close})

(def ^:private callback-operation-ids
  #{:core-async/put :core-async/take})

(defn call-with-fault
  "Run `f` with one explicit fault action and optional decision-log atom."
  ([action f] (call-with-fault action nil f))
  ([action decisions f]
   (when-not (or (nil? decisions) (instance? clojure.lang.Atom decisions))
     (throw (ex-info "fault decisions must be an atom or nil"
                     {:kind :core-async/invalid-fault-log})))
   (binding [*action* action *decisions* decisions]
     (f))))

(defn- invalid! [message data]
  (throw (ex-info message (assoc data :kind :core-async/invalid-fault))))

(defn- record-to! [decisions join-point effect phase]
  (when decisions
    (swap! decisions conj {:operation (:id join-point)
                           :effect effect
                           :phase phase})))

(defn- record! [join-point effect phase]
  (record-to! *decisions* join-point effect phase))

(defn- await-release! [join-point action]
  (let [{:keys [arrived release timeout-ms]} action
        timeout-ms (or timeout-ms 5000)]
    ;; On the pinned Jolt runtime promises are the deliverable IDeref/IPending
    ;; values and, like JVM promises, are callable. Futures and delays satisfy
    ;; the deref protocols but are not valid arrival signals.
    (when-not (and (instance? clojure.lang.IDeref arrived)
                   (instance? clojure.lang.IPending arrived)
                   (ifn? arrived)
                   (instance? clojure.lang.IDeref release)
                   (instance? clojure.lang.IPending release)
                   (integer? timeout-ms) (pos? timeout-ms))
      (invalid! "barrier fault needs a deliverable arrival, release gate, and timeout"
                {:operation (:id join-point)}))
    (deliver arrived true)
    (when (= ::timed-out (deref release timeout-ms ::timed-out))
      (throw (ex-info "core.async fault barrier timed out"
                      {:kind :core-async/fault-barrier-timeout
                       :operation (:id join-point)})))))

(defn- exact-replacement! [join-point args replacement]
  (when-not (and (vector? replacement)
                 (= (count args) (count replacement)))
    (invalid! "replacement fault needs an exact-arity vector"
              {:operation (:id join-point)
               :expected-arity (count args)}))
  replacement)

(defn- error! [join-point action]
  (let [error (:error action)]
    (when-not (instance? Throwable error)
      (invalid! "throw fault needs a Throwable"
                {:operation (:id join-point)
                 :effect (:effect action)}))
    error))

(defn- invoke-action [join-point args proceed action]
  (let [effect (:effect action)]
    (case effect
      nil (proceed)

      :return-before
      (do (record! join-point effect :before-target)
          (:value action))

      :throw-before
      (let [error (error! join-point action)]
        (record! join-point effect :before-target)
        (throw error))

      :return-after
      (do (proceed)
          (record! join-point effect :after-target)
          (:value action))

      :throw-after
      (let [error (error! join-point action)]
        (proceed)
        (record! join-point effect :after-target)
        (throw error))

      :replace-args
      (do (record! join-point effect :before-target)
          (proceed (exact-replacement! join-point args
                                       (:args action))))

      :barrier-before
      (do (record! join-point effect :before-barrier)
          (await-release! join-point action)
          (record! join-point effect :after-barrier)
          (proceed))

      :barrier-after
      (let [result (proceed)]
        (record! join-point effect :before-barrier)
        (await-release! join-point action)
        (record! join-point effect :after-barrier)
        result)

      (invalid! "unsupported core.async fault effect"
                {:operation (:id join-point) :effect effect}))))

(defn around-operation [join-point args proceed]
  (when-not (contains? operation-ids (:id join-point))
    (invalid! "unknown core.async fault operation"
              {:operation (:id join-point)}))
  (invoke-action join-point args proceed *action*))

(defn- deferred-callback [join-point callback action decisions]
  (let [slot (:slot action)
        released? (atom false)]
    (when-not (instance? clojure.lang.Atom slot)
      (invalid! "deferred callback fault needs an atom slot"
                {:operation (:id join-point)}))
    (fn [& callback-args]
      (let [release
            (bound-fn []
              (when-not (compare-and-set! released? false true)
                (throw (ex-info "deferred callback released more than once"
                                {:kind :core-async/duplicate-fault-release
                                 :operation (:id join-point)})))
              (when-not (nil? callback)
                (apply callback callback-args)))]
        (when-not (compare-and-set! slot nil release)
          (throw (ex-info "deferred callback slot was already occupied"
                          {:kind :core-async/occupied-fault-slot
                           :operation (:id join-point)})))
        (record-to! decisions join-point :callback-defer :callback-captured)
        nil))))

(defn around-callback-operation [join-point args proceed]
  (let [operation (:id join-point)]
    (when-not (contains? callback-operation-ids operation)
      (invalid! "unknown core.async callback fault operation"
                {:operation operation}))
    (let [action *action*
          effect (:effect action)
          decisions *decisions*
          callback-index (case operation
                           :core-async/put 2
                           :core-async/take 1)
          callback (nth args callback-index)]
      (case effect
        :callback-suppress
        (do (record! join-point effect :before-target)
            (proceed
             (assoc args callback-index
                    (fn [& _]
                      (record-to! decisions join-point effect
                                  :callback-suppressed)
                      nil))))

        :callback-duplicate
        (do (record! join-point effect :before-target)
            (proceed
             (assoc args callback-index
                    (fn [& callback-args]
                      (record-to! decisions join-point effect
                                  :callback-duplicated)
                      (when-not (nil? callback)
                        (apply callback callback-args)
                        (apply callback callback-args))))))

        :callback-defer
        (do
          (record! join-point effect :before-target)
          (proceed
           (assoc args callback-index
                  (deferred-callback join-point callback action decisions))))

        (invoke-action join-point args proceed action)))))

(def aspect-provider
  {:schema 1
   :libraries {'jolt-lang/jolt target-revision}
   :roles {:concurrency/channel-operation
           {:fn 'jolt.aspect-packs.core-async.faults/around-operation
            :contract :control-v1}
           :concurrency/channel-callback-operation
           {:fn 'jolt.aspect-packs.core-async.faults/around-callback-operation
            :contract :control-v1}}})
