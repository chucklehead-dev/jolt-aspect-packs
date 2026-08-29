(ns jolt.aspect-packs.mycelium.report-test
  (:require [clojure.edn :as edn]
            [jolt.aspect-packs.mycelium.provider :as provider]))

(def expected
  [{:id :mycelium.workflow/lifecycle
    :match {:entry 'mycelium.execution/workflow-event! :arity 1}
    :advice 'jolt.aspect-packs.mycelium.provider/around-workflow-event}
   {:id :mycelium.workflow/edge-decision
    :match {:entry 'mycelium.execution/edge-event! :arity 1}
    :advice 'jolt.aspect-packs.mycelium.provider/around-edge-event}])

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))
        aspects (:aspects report)]
    (when-not (= 1 (:schema report))
      (throw (ex-info "unexpected aspect report schema" {:report report})))
    (when-not (= "jolt.aspect-ir/v1" (:weaver report))
      (throw (ex-info "unexpected aspect weaver" {:report report})))
    (when-not (false? (:control-enabled? report))
      (throw (ex-info "Mycelium observation build enabled control advice"
                      {:report report})))
    (when-not (= 2 (count aspects))
      (throw (ex-info "Mycelium scenario selected unexpected aspects"
                      {:aspects aspects})))
    (doseq [{:keys [id match advice]} expected]
      (let [aspect (first (filter #(= id (:id %)) aspects))]
        (when-not aspect
          (throw (ex-info "Mycelium report omitted an aspect" {:id id})))
        (when-not (= {:id 'yogthos/samizdat
                      :version provider/seam-revision}
                     (:library aspect))
          (throw (ex-info "wrong Mycelium seam identity" {:aspect aspect})))
        (when-not (= match (:match aspect))
          (throw (ex-info "wrong Mycelium selector" {:aspect aspect})))
        (when-not (= advice (:advice aspect))
          (throw (ex-info "wrong Mycelium history advice" {:aspect aspect})))
        (when-not (= 1 (count (:sites aspect)))
          (throw (ex-info "Mycelium seam must match exactly once"
                          {:aspect aspect})))))
    (println "ASPECT-PACK-MYCELIUM-REPORT OK")))
