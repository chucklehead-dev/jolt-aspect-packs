(ns jolt.aspect-packs.core-async-flow.report-test
  (:require [clojure.edn :as edn]
            [jolt.aspect-packs.core-async-flow.provider :as provider]))

(def expected
  {:core-async-flow/create ['jolt.aspect-packs.scenario.core-async-flow/observed-create 1 :concurrency/flow-lifecycle]
   :core-async-flow/start ['jolt.aspect-packs.scenario.core-async-flow/observed-start 1 :concurrency/flow-lifecycle]
   :core-async-flow/pause ['jolt.aspect-packs.scenario.core-async-flow/observed-pause 1 :concurrency/flow-lifecycle]
   :core-async-flow/resume ['jolt.aspect-packs.scenario.core-async-flow/observed-resume 1 :concurrency/flow-lifecycle]
   :core-async-flow/ping ['jolt.aspect-packs.scenario.core-async-flow/observed-ping 2 :concurrency/flow-lifecycle]
   :core-async-flow/inject ['jolt.aspect-packs.scenario.core-async-flow/observed-inject 3 :concurrency/flow-lifecycle]
   :core-async-flow/stop ['jolt.aspect-packs.scenario.core-async-flow/observed-stop 1 :concurrency/flow-lifecycle]
   :core-async-flow/describe ['jolt.aspect-packs.scenario.core-async-flow/worker-describe 0 :concurrency/flow-step]
   :core-async-flow/init ['jolt.aspect-packs.scenario.core-async-flow/worker-init 1 :concurrency/flow-step]
   :core-async-flow/transition ['jolt.aspect-packs.scenario.core-async-flow/worker-transition 2 :concurrency/flow-step]
   :core-async-flow/transform ['jolt.aspect-packs.scenario.core-async-flow/worker-transform 3 :concurrency/flow-step]})

(def expected-resource
  "META-INF/jolt/aspects/packs/core-async-flow-0.1.0.edn")

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))
        aspects (into {} (map (juxt :id identity) (:aspects report)))]
    (when-not (and (= 1 (:schema report))
                   (= "jolt.aspect-ir/v1" (:weaver report))
                   (false? (:control-enabled? report))
                   (= (set (keys expected)) (set (keys aspects))))
      (throw (ex-info "unexpected core.async.flow observation plan"
                      {:report report})))
    (doseq [[id [entry arity role]] expected]
      (let [aspect (get aspects id)
            site (first (:sites aspect))
            consumer (first (:consumers aspect))]
        (when-not (= {:id 'io.github.chucklehead-dev/jolt-aspect-packs-flow-fixture
                      :version provider/fixture-version}
                     (:library aspect))
          (throw (ex-info "wrong flow fixture identity" {:aspect aspect})))
        (when-not (= {:arity arity :entry entry} (:match aspect))
          (throw (ex-info "wrong flow entry selector" {:aspect aspect})))
        (when-not (and (= role (:advice-role aspect))
                       (= expected-resource (:resource aspect))
                       (= 1 (count (:sites aspect)))
                       (= entry (:entry site))
                       (= arity (:arity site))
                       (= 1 (:ordinal site)))
          (throw (ex-info "wrong flow physical match" {:aspect aspect})))
        (when-not (and (= 1 (count (:consumers aspect)))
                       (= 'jolt.aspect-packs.core-async-flow.provider/aspect-provider
                          (:provider consumer))
                       (= :args-v1 (:contract consumer)))
          (throw (ex-info "wrong flow observation consumer"
                          {:aspect aspect})))))
    (println "ASPECT-PACK-CORE-ASYNC-FLOW-REPORT OK")))
