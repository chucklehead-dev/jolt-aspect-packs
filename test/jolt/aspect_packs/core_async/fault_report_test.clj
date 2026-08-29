(ns jolt.aspect-packs.core-async.fault-report-test
  (:require [clojure.edn :as edn]
            [jolt.aspect-packs.core-async.faults :as faults]))

(def expected-providers
  ['jolt.aspect-packs.core-async.faults/aspect-provider
   'jolt.aspect-packs.core-async.provider/aspect-provider])

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))]
    (when-not (and (= 1 (:schema report))
                   (= "jolt.aspect-ir/v1" (:weaver report))
                   (true? (:control-enabled? report)))
      (throw (ex-info "core.async fault report lacks explicit control opt-in"
                      {:report report})))
    (when-not (= 5 (count (:aspects report)))
      (throw (ex-info "core.async fault build selected unexpected aspects"
                      {:aspects (:aspects report)})))
    (doseq [aspect (:aspects report)]
      (let [consumers (:consumers aspect)]
        (when-not (= {:id 'jolt-lang/jolt :version faults/target-revision}
                     (:library aspect))
          (throw (ex-info "wrong core.async fault target revision"
                          {:aspect aspect})))
        (when-not (= 1 (count (:sites aspect)))
          (throw (ex-info "core.async fault seam must match once"
                          {:aspect aspect})))
        (when-not (= expected-providers (mapv :provider consumers))
          (throw (ex-info "fault provider must remain outside history"
                          {:aspect aspect :consumers consumers})))
        (when-not (= [1 2] (mapv :ordinal consumers))
          (throw (ex-info "core.async fault consumer order drifted"
                          {:aspect aspect :consumers consumers})))
        (when-not (= [1 2] (mapv :selection-ordinal consumers))
          (throw (ex-info "core.async fault provider selection order drifted"
                          {:aspect aspect :consumers consumers})))
        (when-not (= :control-v1 (get-in consumers [0 :contract]))
          (throw (ex-info "outer core.async fault consumer is not control advice"
                          {:aspect aspect})))
        (when-not (= (if (contains? #{:core-async/put :core-async/take}
                                    (:id aspect))
                       :replace-args-v1
                       :args-v1)
                     (get-in consumers [1 :contract]))
          (throw (ex-info "inner core.async history contract drifted"
                          {:aspect aspect})))))
    (println "ASPECT-PACK-CORE-ASYNC-FAULT-REPORT OK")))
