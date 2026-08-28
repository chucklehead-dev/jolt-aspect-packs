(ns jolt.aspect-packs.glitter.report-test
  (:require [clojure.edn :as edn]))

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))
        aspect (first (:aspects report))]
    (when-not (= 1 (:schema report))
      (throw (ex-info "unexpected aspect report schema" {:report report})))
    (when-not (= :glitter.widget/list-box-child-reorder (:id aspect))
      (throw (ex-info "wrong Glitter aspect in build report"
                      {:aspect aspect})))
    (when-not (= 1 (count (:sites aspect)))
      (throw (ex-info "Glitter reorder seam must match exactly once"
                      {:sites (:sites aspect)})))
    (when-not (= 'jolt.aspect-packs.glitter.provider/around-list-box-reorder
                 (:advice aspect))
      (throw (ex-info "wrong Glitter history advice" {:aspect aspect})))
    (println "ASPECT-PACK-GLITTER-REPORT OK")))

