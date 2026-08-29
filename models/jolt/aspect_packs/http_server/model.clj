(ns jolt.aspect-packs.http-server.model
  (:require [hegel.trace :as trace]))

(def ^:private request-keys
  #{:http.request.method :url.scheme :url.path :network.protocol.version})

(def ^:private terminal-keys
  #{:http.response.status_code :error.type})

(def ^:private methods
  #{"CONNECT" "DELETE" "GET" "HEAD" "OPTIONS" "PATCH"
    "POST" "PUT" "QUERY" "TRACE" "_OTHER"})

(defn- bounded-string? [value maximum]
  (and (string? value) (<= 1 (count value) maximum)))

(defn- request-shape? [input]
  (and (map? input)
       (contains? methods (:http.request.method input))
       (every? request-keys (keys input))
       (bounded-string? (:http.request.method input) 32)
       (or (nil? (:url.scheme input))
           (contains? #{"http" "https"} (:url.scheme input)))
       (or (nil? (:url.path input))
           (and (bounded-string? (:url.path input) 2048)
                (.startsWith (:url.path input) "/")
                (not (re-find #"[?#\r\n]" (:url.path input)))))
       (or (nil? (:network.protocol.version input))
           (and (bounded-string? (:network.protocol.version input) 32)
                (boolean (re-matches #"[0-9]+(?:\.[0-9]+)?"
                                     (:network.protocol.version input)))))))

(defn- terminal-shape? [event]
  (let [value (:value event)
        status (:http.response.status_code value)
        error-type (:error.type value)]
    (and (map? value)
         (every? terminal-keys (keys value))
         (or (nil? status)
             (and (integer? status) (<= 100 status 999)))
         (or (nil? error-type)
             (and (string? error-type) (<= 1 (count error-type) 16)))
         (or (not= :throw (:phase event)) (some? error-type)))))

(def rules
  [(trace/contiguous-sequence :http-server/contiguous-history 1)
   (trace/closed-lifecycles :http-server/closed-request-lifecycles)
   (trace/causal-parentage :http-server/causal-parentage)
   (trace/context-coherence :http-server/context-coherence)
   (trace/rule
    :http-server/bounded-semconv-shape
    (fn [events]
      (every?
       (fn [event]
         (case (:phase event)
           :invoke (and (= :http/server-ring-handler (:operation event))
                        (request-shape? (:input event)))
           (:return :throw) (terminal-shape? event)
           false))
       events)))])

(defn check! [events]
  (trace/check! events rules {:max-events 256}))
