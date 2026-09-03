(ns jolt.aspect-packs.core-async-flow.provider
  "Privacy-safe lifecycle and process-step histories for core.async.flow."
  (:require [jolt.aspect-packs.history :as history]))

(def fixture-version
  "Version of the annotated flow fixture contract consumed by this provider."
  "0.1.0")

(def ^:private lifecycle-operations
  #{:core-async-flow/create
    :core-async-flow/start
    :core-async-flow/pause
    :core-async-flow/resume
    :core-async-flow/ping
    :core-async-flow/inject
    :core-async-flow/stop})

(def ^:private step-operations
  #{:core-async-flow/describe
    :core-async-flow/init
    :core-async-flow/transition
    :core-async-flow/transform})

(defn- invalid! [message data]
  (throw (ex-info message
                  (assoc data :kind :core-async-flow/invalid-operation))))

(defn- exact-arity! [join-point args expected]
  (when-not (= expected (count args))
    (invalid! "core.async.flow advice received unexpected arguments"
              {:operation (:id join-point)
               :expected-arity expected
               :actual-arity (count args)}))
  args)

(defn- bounded-keys [value]
  (when (map? value)
    (->> (keys value) (take 32) (sort-by str) vec)))

(defn- bounded-statuses [value]
  (when (map? value)
    (->> (vals value)
         (take 32)
         (keep :clojure.core.async.flow/status)
         (sort-by str)
         vec)))

(defn- graph-token [journal graph]
  (history/opaque-token! journal graph))

(defn- lifecycle-input [journal join-point args]
  (case (:id join-point)
    :core-async-flow/create
    (let [[config] (exact-arity! join-point args 1)]
      {:processes (count (:procs config))
       :connections (count (:conns config))
       :custom-executors
       (->> [:mixed-exec :io-exec :compute-exec]
            (filterv #(some? (get config %))))})

    (:core-async-flow/start :core-async-flow/pause
     :core-async-flow/resume :core-async-flow/stop)
    (let [[graph] (exact-arity! join-point args 1)]
      {:graph (graph-token journal graph)})

    :core-async-flow/ping
    (let [[graph timeout-ms] (exact-arity! join-point args 2)]
      {:graph (graph-token journal graph)
       :timeout-ms timeout-ms})

    :core-async-flow/inject
    (let [[graph coord messages] (exact-arity! join-point args 3)]
      {:graph (graph-token journal graph)
       :coord (history/opaque-token! journal coord)
       ;; Observation must not realize a lazy message source before flow does.
       :message-count (if (counted? messages) (count messages) :unknown)})))

(defn- lifecycle-result [journal operation value]
  (case operation
    :core-async-flow/create
    {:result :flow :graph (graph-token journal value)}

    :core-async-flow/start
    {:result :started
     :already-running? (boolean (:already-running value))
     :channels (bounded-keys (dissoc value :already-running))}

    :core-async-flow/ping
    {:result :ping
     :processes (bounded-keys value)
     :statuses (bounded-statuses value)}

    :core-async-flow/inject
    {:result :submitted
     :completion (history/opaque-token! journal value)}

    (:core-async-flow/pause :core-async-flow/resume :core-async-flow/stop)
    {:result (if value :accepted :no-op)}))

(defn around-lifecycle
  [join-point args proceed]
  (if-let [journal history/*journal*]
    (let [operation (:id join-point)]
      (when-not (contains? lifecycle-operations operation)
        (invalid! "unknown core.async.flow lifecycle operation"
                  {:operation operation}))
      (history/invoke!
       journal join-point (lifecycle-input journal join-point args)
       {:return-fn #(lifecycle-result journal operation %)
        :throw-fn #(hash-map :result :threw :error-type (str (type %)))}
       proceed))
    (proceed)))

(defn- step-input [journal join-point args]
  (case (:id join-point)
    :core-async-flow/describe
    (do (exact-arity! join-point args 0) {})

    :core-async-flow/init
    (let [[arg-map] (exact-arity! join-point args 1)]
      {:pid (:clojure.core.async.flow/pid arg-map)
       :params (bounded-keys
                (dissoc arg-map :clojure.core.async.flow/pid))})

    :core-async-flow/transition
    (let [[state transition] (exact-arity! join-point args 2)]
      {:state (history/opaque-token! journal state)
       :transition transition})

    :core-async-flow/transform
    (let [[state input message] (exact-arity! join-point args 3)]
      {:state (history/opaque-token! journal state)
       :input input
       :message (history/opaque-token! journal message)})))

(defn- step-result [journal operation value]
  (case operation
    :core-async-flow/describe
    {:result :description
     :params (bounded-keys (:params value))
     :ins (bounded-keys (:ins value))
     :outs (bounded-keys (:outs value))
     :workload (:workload value)}

    (:core-async-flow/init :core-async-flow/transition)
    {:result :state :state (history/opaque-token! journal value)}

    :core-async-flow/transform
    (let [[state outputs] value]
      {:result :transform
       :state (history/opaque-token! journal state)
       :outputs (bounded-keys outputs)})))

(defn around-step
  [join-point args proceed]
  (if-let [journal history/*journal*]
    (let [operation (:id join-point)]
      (when-not (contains? step-operations operation)
        (invalid! "unknown core.async.flow step operation"
                  {:operation operation}))
      (history/invoke!
       journal join-point (step-input journal join-point args)
       {:return-fn #(step-result journal operation %)
        :throw-fn #(hash-map :result :threw :error-type (str (type %)))}
       proceed))
    (proceed)))

(def aspect-provider
  {:schema 1
   :libraries
   {'io.github.chucklehead-dev/jolt-aspect-packs-flow-fixture
    fixture-version}
   :roles
   {:concurrency/flow-lifecycle
    {:fn 'jolt.aspect-packs.core-async-flow.provider/around-lifecycle
     :contract :args-v1}
    :concurrency/flow-step
    {:fn 'jolt.aspect-packs.core-async-flow.provider/around-step
     :contract :args-v1}}})
