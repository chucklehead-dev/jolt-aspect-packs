(ns jolt.aspect-packs.glimmer.report-test
  (:require [clojure.edn :as edn]))

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))
        aspect (first (:aspects report))]
    (when-not (= 1 (:schema report))
      (throw (ex-info "unexpected aspect report schema" {:report report})))
    (when-not (= :glimmer.core/root-mount (:id aspect))
      (throw (ex-info "wrong Glimmer aspect in build report"
                      {:aspect aspect})))
    (when-not (= {:id 'jolt-lang/glimmer
                  :version "6dab5597dc0d912793fe175d0d3cbb9e75f11426"}
                 (:library aspect))
      (throw (ex-info "wrong Glimmer library identity in build report"
                      {:aspect aspect})))
    (when-not (= {:entry 'glimmer.core/mount :arity 3}
                 (:match aspect))
      (throw (ex-info "wrong Glimmer mount selector in build report"
                      {:aspect aspect})))
    (when-not (= 1 (count (:sites aspect)))
      (throw (ex-info "Glimmer mount seam must match exactly once"
                      {:sites (:sites aspect)})))
    (when-not (= 'jolt.aspect-packs.glimmer.provider/around-mount
                 (:advice aspect))
      (throw (ex-info "wrong Glimmer history advice" {:aspect aspect})))
    (println "ASPECT-PACK-GLIMMER-REPORT OK")))
