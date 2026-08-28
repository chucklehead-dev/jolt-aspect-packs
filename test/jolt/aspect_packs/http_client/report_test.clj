(ns jolt.aspect-packs.http-client.report-test
  (:require [clojure.edn :as edn]))

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))
        aspect (first (:aspects report))]
    (when-not (= 1 (:schema report))
      (throw (ex-info "unexpected aspect report schema" {:report report})))
    (when-not (= :http-client.core/request (:id aspect))
      (throw (ex-info "wrong aspect in build report" {:aspect aspect})))
    (when-not (= 1 (count (:sites aspect)))
      (throw (ex-info "HTTP request seam must match exactly once"
                      {:sites (:sites aspect)})))
    (when-not (= 'jolt.aspect-packs.http-client.provider/around-request
                 (:advice aspect))
      (throw (ex-info "wrong HTTP client history advice" {:aspect aspect})))
    (println "ASPECT-PACK-HTTP-CLIENT-REPORT OK")))

