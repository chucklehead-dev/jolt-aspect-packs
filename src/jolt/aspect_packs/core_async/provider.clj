(ns jolt.aspect-packs.core-async.provider
  "Provider-neutral operation histories for Jolt's public core.async channel API."
  (:require [jolt.aspect-packs.history :as history]))

(def target-revision
  "Exact Jolt revision validated by this external pack."
  "772a27584d0bd151c421c90e80cb8c77012ab836")

(def ^:private operation-ids
  #{:core-async/offer :core-async/poll :core-async/close})

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
            :contract :args-v1}}})
