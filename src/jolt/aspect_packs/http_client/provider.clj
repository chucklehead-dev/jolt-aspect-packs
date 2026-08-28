(ns jolt.aspect-packs.http-client.provider
  (:require [clojure.string :as str]
            [jolt.aspect-packs.history :as history]))

(def target-revision
  "12b78edb9024d200083cf77d61fa56709ab23dd7")

(defn- path-only
  [uri]
  (when (string? uri)
    (first (str/split uri #"[?#]" 2))))

(defn- request-summary
  "Retain useful conformance data without recording headers, bodies, query
  strings, credentials, fragments, or network authority."
  [request]
  (when (map? request)
    (cond-> {}
      (contains? request :request-method)
      (assoc :request-method (:request-method request))

      (contains? request :scheme)
      (assoc :scheme (:scheme request))

      (string? (:uri request))
      (assoc :uri (path-only (:uri request))))))

(defn around-request
  "Non-OTel semantic-history consumer for the synchronous HTTP client seam."
  [join-point evaluated-args proceed]
  (if-not history/*journal*
    (proceed)
    (history/invoke! history/*journal*
                     join-point
                     (request-summary (first evaluated-args))
                     proceed)))

(def aspect-provider
  {:schema 1
   :libraries {'jolt-lang/http-client target-revision}
   :roles {:http/client
           {:fn 'jolt.aspect-packs.http-client.provider/around-request
            :contract :args-v1}}})

