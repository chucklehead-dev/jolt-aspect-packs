(ns jolt.aspect-packs.core-async.report-test
  (:require [clojure.edn :as edn]
            [jolt.aspect-packs.core-async.provider :as provider]))

(def expected-matches
  {:core-async/offer
   {:match {:ns 'jolt.aspect-packs.scenario.core-async
            :call 'clojure.core.async/offer! :arity 2}
    :role :concurrency/channel-operation
    :advice 'jolt.aspect-packs.core-async.provider/around-operation
    :contract :args-v1}
   :core-async/poll
   {:match {:ns 'jolt.aspect-packs.scenario.core-async
            :call 'clojure.core.async/poll! :arity 1}
    :role :concurrency/channel-operation
    :advice 'jolt.aspect-packs.core-async.provider/around-operation
    :contract :args-v1}
   :core-async/close
   {:match {:ns 'jolt.aspect-packs.scenario.core-async
            :call 'clojure.core.async/close! :arity 1}
    :role :concurrency/channel-operation
    :advice 'jolt.aspect-packs.core-async.provider/around-operation
    :contract :args-v1}
   :core-async/put
   {:match {:ns 'jolt.aspect-packs.scenario.core-async
            :call 'clojure.core.async/put! :arity 4}
    :role :concurrency/channel-callback-operation
    :advice 'jolt.aspect-packs.core-async.provider/around-callback-operation
    :contract :replace-args-v1}
   :core-async/take
   {:match {:ns 'jolt.aspect-packs.scenario.core-async
            :call 'clojure.core.async/take! :arity 3}
    :role :concurrency/channel-callback-operation
    :advice 'jolt.aspect-packs.core-async.provider/around-callback-operation
    :contract :replace-args-v1}})

(def expected-resource
  "META-INF/jolt/aspects/packs/core-async-a4e5747.edn")

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))
        aspects (into {} (map (juxt :id identity) (:aspects report)))]
    (when-not (= 1 (:schema report))
      (throw (ex-info "unexpected aspect report schema" {:report report})))
    (when-not (= "jolt.aspect-ir/v1" (:weaver report))
      (throw (ex-info "unexpected aspect weaver" {:report report})))
    (when-not (= (set (keys expected-matches)) (set (keys aspects)))
      (throw (ex-info "core.async report selected unexpected aspects"
                      {:aspects (:aspects report)})))
    (doseq [[id {:keys [match role advice contract]}] expected-matches]
      (let [aspect (get aspects id)
            site (first (:sites aspect))
            consumer (first (:consumers aspect))]
        (when-not (= {:id 'jolt-lang/jolt :version provider/target-revision}
                     (:library aspect))
          (throw (ex-info "wrong core.async target identity" {:aspect aspect})))
        (when-not (= match (:match aspect))
          (throw (ex-info "wrong core.async operation selector" {:aspect aspect})))
        (when-not (= role (:advice-role aspect))
          (throw (ex-info "wrong core.async advice role" {:aspect aspect})))
        (when-not (= advice (:advice aspect))
          (throw (ex-info "wrong core.async top-level advice" {:aspect aspect})))
        (when-not (and (= contract (:contract aspect))
                       (= expected-resource (:resource aspect)))
          (throw (ex-info "wrong core.async contract or manifest resource"
                          {:aspect aspect})))
        (when-not (= 1 (count (:sites aspect)))
          (throw (ex-info "core.async seam must match exactly once"
                          {:aspect id :sites (:sites aspect)})))
        (when-not (= (merge {:within "jolt.aspect-packs.scenario.core-async"
                            :aspect id :ordinal 1}
                            (select-keys match [:call :arity]))
                     (select-keys site [:within :call :arity :aspect :ordinal]))
          (throw (ex-info "core.async report selected the wrong source call"
                          {:aspect id :site site})))
        (when-not (= 1 (count (:consumers aspect)))
          (throw (ex-info "core.async seam must have exactly one consumer"
                          {:aspect id :consumers (:consumers aspect)})))
        (when-not (and (= 'jolt.aspect-packs.core-async.provider/aspect-provider
                          (:provider consumer))
                       (= advice (:advice consumer))
                       (= contract (:contract consumer)))
          (throw (ex-info "wrong core.async history consumer"
                          {:aspect id :consumer consumer})))))
    (println "ASPECT-PACK-CORE-ASYNC-REPORT OK")))
