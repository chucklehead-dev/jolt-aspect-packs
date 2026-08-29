(ns jolt.aspect-packs.http-server.report-test
  (:require [clojure.edn :as edn]))

(def expected
  [{:id :http/server-ring-handler
    :advice 'otel.instrumentation.http-server/around
    :contract :replace-args-v1}
   {:id :http/server-sanitized-response
    :advice 'otel.instrumentation.http-server/around-response
    :contract :args-v1}])

(def expected-providers
  ['otel.instrumentation.http-server/aspect-provider
   'jolt.aspect-packs.http-server.provider/aspect-provider])

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))
        aspects (:aspects report)]
    (when-not (= 1 (:schema report))
      (throw (ex-info "unexpected aspect report schema" {:report report})))
    (when-not (= 2 (count aspects))
      (throw (ex-info "HTTP server pack must report both lifecycle seams"
                      {:aspects aspects})))
    (doseq [[aspect expectation] (map vector aspects expected)]
      (when-not (= (:id expectation) (:id aspect))
        (throw (ex-info "wrong HTTP server aspect order or identity"
                        {:aspect aspect :expected expectation})))
      (when-not (= 'casselc/jolt-http (get-in aspect [:library :id]))
        (throw (ex-info "wrong HTTP server library" {:aspect aspect})))
      (when-not (= "3ef772262308bbf6039412366ae80690cec348b0"
                   (get-in aspect [:library :version]))
        (throw (ex-info "wrong HTTP server seam revision" {:aspect aspect})))
      (when-not (= 1 (count (:sites aspect)))
        (throw (ex-info "HTTP server seam must match exactly once"
                        {:aspect aspect})))
      (when-not (= (:advice expectation) (:advice aspect))
        (throw (ex-info "wrong HTTP server history advice" {:aspect aspect})))
      (when-not (= (:contract expectation) (:contract aspect))
        (throw (ex-info "wrong HTTP server advice contract" {:aspect aspect})))
      (when-not (= expected-providers (mapv :provider (:consumers aspect)))
        (throw (ex-info "wrong ordered HTTP server consumer chain"
                        {:aspect aspect :expected expected-providers})))
      (when-not (= [1 2] (mapv :ordinal (:consumers aspect)))
        (throw (ex-info "wrong HTTP server consumer ordinals"
                        {:aspect aspect})))
      (when-not (= [(:contract expectation) (:contract expectation)]
                   (mapv :contract (:consumers aspect)))
        (throw (ex-info "HTTP server consumers disagree on the seam contract"
                        {:aspect aspect}))))
    (println "ASPECT-PACK-HTTP-SERVER-REPORT OK")))
