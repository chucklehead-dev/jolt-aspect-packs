(ns jolt.aspect-packs.scenario.http-client
  (:require [clj-http.lite.core :as http]))

(defn request!
  [request]
  (http/request request))

(defn -main [& args]
  ;; Keeping the call reachable proves the real dependency's entry seam at
  ;; build time. The conformance gate does not perform an external request.
  (if (seq args)
    (println (request! {:method :get :url (first args)}))
    (println "http-client aspect scenario built")))

