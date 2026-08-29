(ns jolt.aspect-packs.db.report-test
  (:require [clojure.edn :as edn]
            [jolt.aspect-packs.db.provider :as provider]))

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))
        aspect (first (:aspects report))
        consumers (:consumers aspect)
        site (first (:sites aspect))]
    (when-not (= 1 (:schema report))
      (throw (ex-info "unexpected aspect report schema" {:report report})))
    (when-not (= "jolt.aspect-ir/v1" (:weaver report))
      (throw (ex-info "unexpected aspect weaver" {:report report})))
    (when-not (false? (:control-enabled? report))
      (throw (ex-info "database observation build enabled control advice"
                      {:report report})))
    (when-not (and (string? (:identity report))
                   (pos? (count (:identity report))))
      (throw (ex-info "aspect report lacks build identity" {:report report})))
    (when-not (= 1 (count (:aspects report)))
      (throw (ex-info "database scenario selected unexpected aspects"
                      {:aspects (:aspects report)})))
    (when-not (= :db.jdbc-shim/execute (:id aspect))
      (throw (ex-info "wrong database aspect in build report" {:aspect aspect})))
    (when-not (= {:id 'jolt-lang/db :version provider/seam-revision}
                 (:library aspect))
      (throw (ex-info "wrong database seam identity" {:aspect aspect})))
    (when-not (= {:ns 'db.jdbc-shim
                  :call 'db.driver/execute-handle
                  :arity 4}
                 (:match aspect))
      (throw (ex-info "wrong database operation selector" {:aspect aspect})))
    (when-not (= 1 (count (:sites aspect)))
      (throw (ex-info "database execute seam must match exactly once"
                      {:sites (:sites aspect)})))
    (when-not (= {:within "db.jdbc-shim"
                  :call 'db.driver/execute-handle
                  :arity 4
                  :aspect :db.jdbc-shim/execute
                  :ordinal 1}
                 (select-keys site [:within :call :arity :aspect :ordinal]))
      (throw (ex-info "database report selected the wrong source call"
                      {:site site})))
    (when-not (and (pos-int? (get-in site [:position :line]))
                   (pos-int? (get-in site [:position :column])))
      (throw (ex-info "database report lacks source position"
                      {:site site})))
    (when-not (= ['otel.instrumentation.db/aspect-provider
                  'jolt.aspect-packs.db.provider/aspect-provider]
                 (mapv :provider consumers))
      (throw (ex-info "wrong ordered database consumers"
                      {:consumers consumers})))
    (when-not (= [1 2] (mapv :ordinal consumers))
      (throw (ex-info "database consumer ordinals are not stable"
                      {:consumers consumers})))
    (when-not (= [1 2] (mapv :selection-ordinal consumers))
      (throw (ex-info "database provider selection order is not stable"
                      {:consumers consumers})))
    (when-not (every? #(= :args-v1 (:contract %)) consumers)
      (throw (ex-info "wrong database consumer contract"
                      {:consumers consumers})))
    (println "ASPECT-PACK-DB-DUAL-CONSUMER-REPORT OK")))
