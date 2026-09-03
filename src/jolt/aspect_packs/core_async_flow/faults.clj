(ns jolt.aspect-packs.core-async-flow.faults
  "Explicitly enabled, operation-scoped faults for core.async.flow seams.")

(def fixture-version "0.1.0")

(def ^:dynamic *action* nil)
(def ^:dynamic *decisions* nil)

(defn call-with-fault
  "Run f with one action. An action applies only to its exact :operation."
  ([action f] (call-with-fault action nil f))
  ([action decisions f]
   (when-not (or (nil? decisions) (instance? clojure.lang.Atom decisions))
     (throw (ex-info "flow fault decisions must be an atom or nil"
                     {:kind :core-async-flow/invalid-fault-log})))
   (binding [*action* action *decisions* decisions]
     (f))))

(defn- invalid! [message data]
  (throw (ex-info message
                  (assoc data :kind :core-async-flow/invalid-fault))))

(defn- record! [join-point effect phase]
  (when *decisions*
    (swap! *decisions* conj {:operation (:id join-point)
                             :effect effect
                             :phase phase})))

(defn- selected? [join-point action]
  (= (:id join-point) (:operation action)))

(defn- fault-error! [join-point action]
  (let [error (:error action)]
    (when-not (instance? Throwable error)
      (invalid! "throw fault needs a Throwable"
                {:operation (:id join-point)}))
    error))

(defn- exact-replacement! [join-point args replacement]
  (when-not (and (vector? replacement)
                 (= (count args) (count replacement)))
    (invalid! "replacement fault needs an exact-arity vector"
              {:operation (:id join-point)
               :expected-arity (count args)}))
  replacement)

(defn- await-release! [join-point action]
  (let [{:keys [arrived release timeout-ms]} action
        timeout-ms (or timeout-ms 5000)]
    (when-not (and (instance? clojure.lang.IDeref arrived)
                   (instance? clojure.lang.IPending arrived)
                   (ifn? arrived)
                   (instance? clojure.lang.IDeref release)
                   (instance? clojure.lang.IPending release)
                   (integer? timeout-ms)
                   (pos? timeout-ms))
      (invalid! "barrier fault needs arrival and release promises"
                {:operation (:id join-point)}))
    (deliver arrived true)
    (when (= ::timed-out (deref release timeout-ms ::timed-out))
      (throw (ex-info "core.async.flow fault barrier timed out"
                      {:kind :core-async-flow/fault-barrier-timeout
                       :operation (:id join-point)})))))

(defn around-operation [join-point args proceed]
  (let [action *action*]
    (if-not (selected? join-point action)
      (proceed)
      (let [effect (:effect action)]
        (case effect
          :return-before
          (do (record! join-point effect :before-target)
              (:value action))

          :throw-before
          (let [error (fault-error! join-point action)]
            (record! join-point effect :before-target)
            (throw error))

          :return-after
          (do (proceed)
              (record! join-point effect :after-target)
              (:value action))

          :throw-after
          (let [error (fault-error! join-point action)]
            (proceed)
            (record! join-point effect :after-target)
            (throw error))

          :replace-args
          (do (record! join-point effect :before-target)
              (proceed (exact-replacement! join-point args (:args action))))

          :barrier-before
          (do (record! join-point effect :before-barrier)
              (await-release! join-point action)
              (record! join-point effect :after-barrier)
              (proceed))

          (invalid! "unsupported core.async.flow fault effect"
                    {:operation (:id join-point) :effect effect}))))))

(def aspect-provider
  {:schema 1
   :libraries
   {'io.github.chucklehead-dev/jolt-aspect-packs-flow-fixture
    fixture-version}
   :roles
   {:concurrency/flow-lifecycle
    {:fn 'jolt.aspect-packs.core-async-flow.faults/around-operation
     :contract :control-v1}
    :concurrency/flow-step
    {:fn 'jolt.aspect-packs.core-async-flow.faults/around-operation
     :contract :control-v1}}})
