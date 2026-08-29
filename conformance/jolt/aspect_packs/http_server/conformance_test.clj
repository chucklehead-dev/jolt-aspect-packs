(ns jolt.aspect-packs.http-server.conformance-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [jolt.aspect-packs.history :as history]
            [jolt.aspect-packs.http-server.model :as model]
            [jolt.aspect-packs.http-server.provider :as history-provider]
            [otel.exporter.memory :as memory]
            [otel.instrumentation.http-server :as otel-provider]
            [otel.sdk :as sdk]))

(def ^:private remote-trace-id "0af7651916cd43dd8448eb211c80319c")
(def ^:private remote-span-id "b7ad6b7169203331")

(def ^:private request-join-point
  {:id :http/server-ring-handler
   :advice-role :http/server
   :contract :replace-args-v1
   :match {:entry 'jolt.http.protocol/invoke-handler :arity 8}
   :library {:id 'casselc/jolt-http :version history-provider/seam-revision}
   :site-id "http-server-dual-consumer-entry"
   :build-identity "http-server-dual-consumer-conformance"})

(def ^:private response-join-point
  {:id :http/server-sanitized-response
   :advice-role :http/server-response
   :contract :args-v1
   :match {:entry 'jolt.http.protocol/sanitize-response :arity 1}
   :library {:id 'casselc/jolt-http :version history-provider/seam-revision}
   :site-id "http-server-dual-consumer-response"
   :build-identity "http-server-dual-consumer-conformance"})

(defn- request [extra]
  (merge {:request-method :get
          :scheme :http
          :uri "/orders/42"
          :protocol "HTTP/1.1"
          :server-name "private.example"
          :server-port 8080
          :remote-addr "192.0.2.10"
          :headers {"traceparent"
                    (str "00-" remote-trace-id "-" remote-span-id "-01")
                    "authorization" "private token"}}
         extra))

(defn- observe-response!
  "Compose response consumers in compiler selection order: OTel is outer and
  the neutral history provider is inner."
  [response]
  (otel-provider/around-response
   response-join-point [response]
   #(history-provider/around-response
     response-join-point [response] (fn [] [response nil]))))

(defn- apply-dual-advice
  "Source-mode model of the compiled `:providers [otel history]` entry chain.
  Replacement arguments from the outer consumer become the inner consumer's
  evaluated arguments exactly as they do in the compiler weaver."
  [journal handler request respond raise]
  (let [opts {otel-provider/network-addresses-option false}
        args [handler request :socket :done :buffer :read-buffer opts :handled]
        invoke (fn [replacement]
                 ((first replacement) (second replacement) respond raise))
        inner (fn [evaluated]
                (binding [history/*journal* journal]
                  (history-provider/around-request
                   request-join-point evaluated
                   (fn
                     ([] (invoke evaluated))
                     ([replacement] (invoke replacement))))))]
    (otel-provider/around
     request-join-point args
     (fn
       ([] (inner args))
       ([replacement] (inner replacement))))))

(defn- with-memory-sdk [f]
  (let [exporter (memory/multisignal-exporter)
        handle (sdk/init! {:service-name "aspect-packs-http-server-conformance"
                           :exporter exporter
                           :processor :simple
                           :runtime-metrics? false
                           :logs? true
                           :bridge-logging? false})]
    (try
      (f exporter handle)
      (finally (sdk/shutdown! handle)))))

(defn- duration-point [exporter]
  (some->> (memory/metrics exporter)
           (filter #(= "http.server.request.duration" (:name %)))
           first :data-points first))

(deftest exact-provider-and-scenario-revisions-agree
  (let [deps (edn/read-string (slurp "deps.edn"))
        scenario (edn/read-string (slurp "scenarios/http-server/deps.edn"))
        targets (edn/read-string (slurp "targets.edn"))]
    (is (= history-provider/seam-revision otel-provider/http-build-id))
    (is (= history-provider/seam-revision
           (get-in history-provider/aspect-provider
                   [:libraries 'casselc/jolt-http])))
    (is (= otel-provider/http-build-id
           (get-in otel-provider/aspect-provider
                   [:libraries 'casselc/jolt-http])))
    (is (= history-provider/target-revision
           (get-in targets [:targets 'casselc/jolt-http :git/sha])))
    (is (= history-provider/target-revision
           (get-in scenario [:deps 'io.github.casselc/jolt-http :git/sha])))
    (is (= "6d7837c14dbf9710e833933ef61e6314d12b54ad"
           (get-in deps [:aliases :http-server-conformance :extra-deps
                         'io.github.chucklehead-dev/jolt-otel-instrumentation-http-server
                         :git/sha])))
    (is (= "6d7837c14dbf9710e833933ef61e6314d12b54ad"
           (get-in scenario
                   [:deps
                    'io.github.chucklehead-dev/jolt-otel-instrumentation-http-server
                    :git/sha])))
    (is (= '[otel.instrumentation.http-server
             jolt.aspect-packs.http-server.provider]
           (get-in scenario [:jolt/build :aspects 0 :providers])))))

(deftest one-success-emits-remote-parented-server-span-metric-and-history
  (with-memory-sdk
    (fn [exporter handle]
      (let [journal (history/journal)
            response {:status 201 :headers {"set-cookie" "private"}
                      :body "private response"}
            callback-result (Object.)
            observed
            (apply-dual-advice
             journal
             (fn [_ respond _] (respond response false))
             (request {:query-string "tenant=private" :body "private body"})
             (fn [actual async?]
               (is (identical? response actual))
               (is (false? async?))
               (observe-response! actual)
               callback-result)
             (fn [_] (throw (ex-info "unexpected raise" {}))))]
        (is (identical? callback-result observed))
        (is (true? (history/assert-complete! journal)))
        (is (= (history/events journal) (model/check! (history/events journal))))
        (is (sdk/force-flush! handle))
        (let [[span] (memory/spans exporter)
              attrs (:attributes span)
              point (duration-point exporter)]
          (is (= 1 (count (memory/spans exporter))))
          (is (= :server (:kind span)))
          (is (= remote-trace-id (get-in span [:span-context :trace-id])))
          (is (= remote-span-id (:parent-span-id span)))
          (is (= "GET" (get attrs "http.request.method")))
          (is (= 201 (get attrs "http.response.status_code")))
          (is (some? point) "the dual chain emitted a duration measurement")
          (is (= 201 (get (:attributes point) "http.response.status_code")))
          (is (= [:invoke :return]
                 (mapv :phase (history/events journal))))
          (is (= {:http.response.status_code 201}
                 (:value (second (history/events journal)))))
          (is (empty? (memory/records exporter)))
          (doseq [private-value ["private token" "private.example"
                                 "tenant=private" "private response"]]
            (is (not (.contains
                      (pr-str [(history/events journal) span point])
                      private-value))
                (str "private value escaped dual-consumer shaping: "
                     private-value))))))))

(deftest one-raise-emits-correlated-error-span-log-metric-and-throw-history
  (with-memory-sdk
    (fn [exporter handle]
      (let [journal (history/journal)
            raised (ex-info "private raised message" {:token "private"})
            observed
            (apply-dual-advice
             journal
             (fn [_ _ raise] (raise raised))
             (request {})
             (fn [_ _] nil)
             identity)]
        (is (identical? raised observed))
        (is (true? (history/assert-complete! journal)))
        (is (= (history/events journal) (model/check! (history/events journal))))
        (is (sdk/force-flush! handle))
        (let [[span] (memory/spans exporter)
              [record] (memory/records exporter)
              point (duration-point exporter)
              error-type (get (:attributes span) "error.type")]
          (is (= 1 (count (memory/spans exporter))))
          (is (= :server (:kind span)))
          (is (= :error (get-in span [:status :code])))
          (is (some? error-type))
          (is (= "http.server.request.exception" (:event-name record)))
          (is (= (get-in span [:span-context :trace-id]) (:trace-id record)))
          (is (= (get-in span [:span-context :span-id]) (:span-id record)))
          (is (= error-type (get (:attributes record) "exception.type")))
          (is (= error-type (get (:attributes point) "error.type")))
          (is (= [:invoke :throw] (mapv :phase (history/events journal))))
          (is (= {:error.type "_OTHER"}
                 (:value (second (history/events journal)))))
          (is (not (.contains
                    (pr-str [span record point (history/events journal)])
                    "private raised message"))))))))
