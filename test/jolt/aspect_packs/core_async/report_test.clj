(ns jolt.aspect-packs.core-async.report-test
  (:require [clojure.edn :as edn]
            [jolt.aspect-packs.core-async.provider :as provider]))

(def expected-matches
  {:core-async/offer {:ns 'jolt.aspect-packs.scenario.core-async
                      :call 'clojure.core.async/offer! :arity 2}
   :core-async/poll {:ns 'jolt.aspect-packs.scenario.core-async
                     :call 'clojure.core.async/poll! :arity 1}
   :core-async/close {:ns 'jolt.aspect-packs.scenario.core-async
                      :call 'clojure.core.async/close! :arity 1}})

(def expected-advice
  'jolt.aspect-packs.core-async.provider/around-operation)

(def expected-role :concurrency/channel-operation)

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
    (doseq [[id expected-match] expected-matches]
      (let [aspect (get aspects id)
            site (first (:sites aspect))
            consumer (first (:consumers aspect))]
        (when-not (= {:id 'jolt-lang/jolt :version provider/target-revision}
                     (:library aspect))
          (throw (ex-info "wrong core.async target identity" {:aspect aspect})))
        (when-not (= expected-match (:match aspect))
          (throw (ex-info "wrong core.async operation selector" {:aspect aspect})))
        (when-not (= expected-role (:advice-role aspect))
          (throw (ex-info "wrong core.async advice role" {:aspect aspect})))
        (when-not (= expected-advice (:advice aspect))
          (throw (ex-info "wrong core.async top-level advice" {:aspect aspect})))
        (when-not (= 1 (count (:sites aspect)))
          (throw (ex-info "core.async seam must match exactly once"
                          {:aspect id :sites (:sites aspect)})))
        (when-not (= (merge {:within "jolt.aspect-packs.scenario.core-async"
                             :aspect id :ordinal 1}
                            (select-keys expected-match [:call :arity]))
                     (select-keys site [:within :call :arity :aspect :ordinal]))
          (throw (ex-info "core.async report selected the wrong source call"
                          {:aspect id :site site})))
        (when-not (= 1 (count (:consumers aspect)))
          (throw (ex-info "core.async seam must have exactly one consumer"
                          {:aspect id :consumers (:consumers aspect)})))
        (when-not (and (= 'jolt.aspect-packs.core-async.provider/aspect-provider
                          (:provider consumer))
                       (= expected-advice (:advice consumer))
                       (= :args-v1 (:contract consumer)))
          (throw (ex-info "wrong core.async history consumer"
                          {:aspect id :consumer consumer})))))
    (println "ASPECT-PACK-CORE-ASYNC-REPORT OK")))
