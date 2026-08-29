(ns jolt.aspect-packs.http-server.provider
  (:require [clojure.string :as str]
            [jolt.aspect-packs.history :as history]))

(def target-revision
  "Live maintained jolt-http revision validated by this pack."
  "c6effc3a04be1467e66da433b879a8a73a352228")

(def target-base-revision
  "Merge base of target-revision and the maintained jolt-http main branch."
  "126d30b47d926f8dbace386d5c94d0894315e06a")

(def seam-revision
  "Compatibility id published by jolt-http for the unchanged lifecycle seams."
  "3ef772262308bbf6039412366ae80690cec348b0")

(def ^:private known-methods
  #{"CONNECT" "DELETE" "GET" "HEAD" "OPTIONS" "PATCH"
    "POST" "PUT" "QUERY" "TRACE"})

(def ^:dynamic *request-terminal* nil)

(defn- method-value [value]
  (let [candidate (cond
                    (keyword? value) (str/upper-case (name value))
                    (string? value) (str/upper-case value)
                    :else nil)]
    (if (contains? known-methods candidate) candidate "_OTHER")))

(defn- safe-scheme [value]
  (let [candidate (cond
                    (keyword? value) (name value)
                    (string? value) (str/lower-case value)
                    :else nil)]
    (when (contains? #{"http" "https"} candidate) candidate)))

(defn- safe-path [value]
  (when (and (string? value)
             (<= 1 (count value) 2048)
             (.startsWith value "/")
             (not (re-find #"[?#\r\n]" value)))
    value))

(defn- safe-protocol-version [value]
  (when (and (string? value) (<= 1 (count value) 32))
    (or (second (re-matches #"HTTP/([0-9]+(?:\.[0-9]+)?)" value))
        (when (re-matches #"[0-9]+(?:\.[0-9]+)?" value) value))))

(defn- request-summary
  "Shape only bounded HTTP semantic-convention fields. Headers, query data,
  bodies, authority, peer addresses, and handler values never enter history."
  [request]
  (let [request (if (map? request) request {})]
    (cond-> {:http.request.method (method-value (:request-method request))}
      (safe-scheme (:scheme request))
      (assoc :url.scheme (safe-scheme (:scheme request)))

      (safe-path (:uri request))
      (assoc :url.path (safe-path (:uri request)))

      (safe-protocol-version (:protocol request))
      (assoc :network.protocol.version
             (safe-protocol-version (:protocol request))))))

(defn- response-summary [response]
  (let [status (:status response)]
    (when (and (integer? status) (<= 100 status 999))
      {:http.response.status_code status})))

(defn- terminal-value [terminal]
  (let [{:keys [response raised?]} @terminal
        status (:http.response.status_code response)]
    (cond-> (or response {})
      raised? (assoc :error.type "_OTHER")
      (and (not raised?) (integer? status) (>= status 500))
      (assoc :error.type (str status)))))

(defn- call-in-request [carrier terminal f]
  (binding [*request-terminal* terminal]
    (history/call-with-carrier carrier f)))

(defn- history-handler [journal join-point handler request]
  (let [handle (history/begin! journal join-point (request-summary request))
        carrier (history/carrier handle)
        terminal (atom {:response nil :raised? false})
        terminal-lock (Object.)
        ended? (atom false)
        complete!
        (fn [phase operation]
          (locking terminal-lock
            (if @ended?
              (operation)
              ;; jolt-http's raise callback can synchronously call respond via
              ;; the configured error handler. Let that nested callback collect
              ;; the sanitized response, but leave lifecycle ownership with the
              ;; outer callback so raise remains a canonical :throw terminal.
              (if (:completing? @terminal)
                (operation)
                (do
                  (swap! terminal assoc
                         :completing? true
                         :raised? (or (:raised? @terminal) (= :throw phase)))
                  (try
                    (let [result (call-in-request carrier terminal operation)
                          phase (if (:raised? @terminal) :throw phase)]
                      (reset! ended? true)
                      ((if (= :throw phase)
                         history/try-throw!
                         history/try-return!)
                       handle (terminal-value terminal))
                      result)
                    (catch Throwable error
                      (reset! ended? true)
                      ;; A recorder failure after the application has thrown
                      ;; must never replace the application's Throwable.
                      (try
                        (history/try-throw!
                         handle (assoc (terminal-value terminal)
                                       :error.type "_OTHER"))
                        (catch Throwable _))
                      (throw error))
                    (finally
                      (swap! terminal dissoc :completing?))))))))]
    (fn [_request respond raise]
      (let [respond (fn [response async?]
                      (complete! :return #(respond response async?)))
            raise (fn [error]
                    (complete! :throw #(raise error)))]
        (try
          (call-in-request carrier terminal
                           #(handler request respond raise))
          (catch Throwable error
            (locking terminal-lock
              (when-not @ended?
                (reset! ended? true)
                (try
                  (history/try-throw! handle {:error.type "_OTHER"})
                  (catch Throwable _))))
            (throw error)))))))

(defn around-request
  "Non-OTel async history consumer for jolt-http's normalized Ring lifecycle.

  The operation stays open after an async handler returns and closes after the
  first response or raise callback finishes. Callback results and exceptions
  remain application-owned."
  [_join-point _evaluated-args proceed]
  (if-not history/*journal*
    (proceed)
    (let [[handler request socket done buffer read-buffer opts handled]
          _evaluated-args]
      (proceed [(history-handler history/*journal* _join-point handler request)
                request socket done buffer read-buffer opts handled]))))

(defn around-response
  "Capture jolt-http's sanitized wire response into the active request.

  The exact `[safe-response problems]` result is returned unchanged."
  [_join-point _evaluated-args proceed]
  (let [result (proceed)]
    (when *request-terminal*
      (swap! *request-terminal* assoc :response
             (or (response-summary (first result)) {})))
    result))

(def aspect-provider
  {:schema 1
   :libraries {'casselc/jolt-http seam-revision}
   :roles {:http/server
           {:fn 'jolt.aspect-packs.http-server.provider/around-request
            :contract :replace-args-v1}
           :http/server-response
           {:fn 'jolt.aspect-packs.http-server.provider/around-response
            :contract :args-v1}}})
