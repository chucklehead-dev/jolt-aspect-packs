(ns jolt.aspect-packs.core-async.provider
  "Provider-neutral operation histories for Jolt's public core.async channel API."
  (:require [jolt.aspect-packs.history :as history]))

(def target-revision
  "Exact Jolt revision validated by this external pack."
  "a4e5747344947163cc3d717e476795ad8f153376")

(def ^:private operation-ids
  #{:core-async/offer :core-async/poll :core-async/close})

(def ^:private callback-operation-ids
  #{:core-async/put :core-async/take})

(defn- invalid! [message data]
  (throw (ex-info message (assoc data :kind :core-async/invalid-operation))))

(defn- args! [join-point args expected]
  (when-not (= expected (count args))
    (invalid! "core.async history advice received unexpected arguments"
              {:operation (:id join-point)
               :expected-arity expected
               :actual-arity (count args)}))
  args)

(defn- offer-input [journal join-point args]
  (let [[channel value] (args! join-point args 2)]
    {:channel (history/opaque-token! journal channel)
     :value (history/opaque-token! journal value)}))

(defn- channel-input [journal join-point args]
  (let [[channel] (args! join-point args 1)]
    {:channel (history/opaque-token! journal channel)}))

(defn- callback-input [journal join-point args]
  (case (:id join-point)
    :core-async/put
    (let [[channel value _callback on-caller?]
          (args! join-point args 4)]
      {:channel (history/opaque-token! journal channel)
       :value (history/opaque-token! journal value)
       :on-caller? (boolean on-caller?)})

    :core-async/take
    (let [[channel _callback on-caller?]
          (args! join-point args 3)]
      {:channel (history/opaque-token! journal channel)
       :on-caller? (boolean on-caller?)})))

(defn- offer-result [value]
  {:result (cond
             (true? value) :accepted
             (false? value) :closed
             (nil? value) :full
             :else (invalid! "core.async offer! returned an invalid result"
                             {:result-type (str (type value))}))})

(defn- poll-result [journal value]
  (if (nil? value)
    {:result :empty}
    {:result :value :value (history/opaque-token! journal value)}))

(defn- callback-result [journal operation callback-args]
  (if-not (= 1 (count callback-args))
    {:result :invalid-callback-arity :arity (count callback-args)}
    (let [value (first callback-args)]
      (case operation
        :core-async/put
        {:result (cond
                   (true? value) :accepted
                   (false? value) :closed
                   :else :invalid-callback-value)}

        :core-async/take
        (if (nil? value)
          {:result :closed}
          {:result :value :value (history/opaque-token! journal value)})))))

(defn- target-throw-result [error]
  {:result :target-threw
   :error-type (str (type error))})

(defn around-callback-operation
  "Record an async put!/take! lifecycle by replacing only its callback.

  The history operation stays open after the target returns and closes when the
  callback first fires. Duplicate callback delivery remains visible to the
  application: every delivery invokes the original callback even though only
  the first one can close the exactly-once history handle."
  [join-point args proceed]
  (if-let [journal history/*journal*]
    (let [operation (:id join-point)]
      (when-not (contains? callback-operation-ids operation)
        (invalid! "unknown core.async callback operation"
                  {:operation operation}))
      (let [input (callback-input journal join-point args)
            callback-index (case operation :core-async/put 2 :core-async/take 1)
            callback (nth args callback-index)]
        (let [handle (history/begin! journal join-point input)
              carrier (history/carrier handle)
              wrapped
              (fn [& callback-args]
                (history/call-with-carrier
                  carrier
                  (fn []
                    ;; Recorder faults must not suppress or replace a callback
                    ;; delivery. An unclosed handle remains independently
                    ;; visible through assert-complete!.
                    (try
                      (history/try-return!
                       handle
                       (assoc (callback-result journal operation callback-args)
                              :carrier
                              {:parent-operation-id
                               history/*parent-operation-id*
                               :context-id history/*context-id*}))
                      (catch Throwable _))
                    ;; The pinned Jolt target accepts nil as an explicit no-op
                    ;; callback. Other values retain target behavior: applying a
                    ;; non-callable value fails at callback delivery, not earlier.
                    (when-not (nil? callback)
                      (apply callback callback-args)))))
              replacement (assoc args callback-index wrapped)]
          (try
            (proceed replacement)
            (catch Throwable error
              (try
                (history/try-throw! handle (target-throw-result error))
                (catch Throwable _))
              (throw error))))))
    (proceed)))

(defn around-operation
  "Record a bounded public channel operation without emitting channels or values.

  The provider is inert when no history journal is bound. The application result
  and Throwable identity always remain the result of the original operation."
  [join-point args proceed]
  (if-let [journal history/*journal*]
    (let [operation (:id join-point)]
      (when-not (contains? operation-ids operation)
        (invalid! "unknown core.async history operation"
                  {:operation operation}))
      (case operation
        :core-async/offer
        (history/invoke! journal join-point
                         (offer-input journal join-point args)
                         {:return-fn offer-result
                          :throw-fn (constantly {:result :threw})}
                         proceed)

        :core-async/poll
        (history/invoke! journal join-point
                         (channel-input journal join-point args)
                         {:return-fn #(poll-result journal %)
                          :throw-fn (constantly {:result :threw})}
                         proceed)

        :core-async/close
        (history/invoke! journal join-point
                         (channel-input journal join-point args)
                         {:return-fn (constantly {:result :closed})
                          :throw-fn (constantly {:result :threw})}
                         proceed)))
    (proceed)))

(def aspect-provider
  {:schema 1
   :libraries {'jolt-lang/jolt target-revision}
   :roles {:concurrency/channel-operation
           {:fn 'jolt.aspect-packs.core-async.provider/around-operation
            :contract :args-v1}
           :concurrency/channel-callback-operation
           {:fn 'jolt.aspect-packs.core-async.provider/around-callback-operation
            :contract :replace-args-v1}}})
