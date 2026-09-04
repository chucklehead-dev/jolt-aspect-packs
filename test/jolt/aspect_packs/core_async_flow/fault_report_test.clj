(ns jolt.aspect-packs.core-async-flow.fault-report-test
  (:require [clojure.edn :as edn]
            [jolt.aspect-packs.core-async-flow.faults :as faults]))

(def expected-providers
  ['jolt.aspect-packs.core-async-flow.faults/aspect-provider
   'jolt.aspect-packs.core-async-flow.provider/aspect-provider])

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))]
    (when-not (and (= 1 (:schema report))
                   (= "jolt.aspect-ir/v1" (:weaver report))
                   (true? (:control-enabled? report))
                   (= 11 (count (:aspects report))))
      (throw (ex-info "flow fault report lacks explicit control plan"
                      {:report report})))
    (doseq [aspect (:aspects report)]
      (let [consumers (:consumers aspect)]
        (when-not (= {:id 'io.github.chucklehead-dev/jolt-aspect-packs-flow-fixture
                      :version faults/fixture-version}
                     (:library aspect))
          (throw (ex-info "wrong flow fault fixture identity"
                          {:aspect aspect})))
        (when-not (and (= 1 (count (:sites aspect)))
                       (= expected-providers (mapv :provider consumers))
                       (= [1 2] (mapv :ordinal consumers))
                       (= [1 2] (mapv :selection-ordinal consumers))
                       (= [:control-v1 :args-v1]
                          (mapv :contract consumers)))
          (throw (ex-info "flow fault consumer order drifted"
                          {:aspect aspect})))))
    (println "ASPECT-PACK-CORE-ASYNC-FLOW-FAULT-REPORT OK")))
