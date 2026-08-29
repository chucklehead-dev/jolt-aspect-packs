(ns jolt.aspect-packs.http-server.provider-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [hegel.clojure-test :refer [with]]
            [hegel.generator :as g]
            [jolt.aspect-packs.history :as history]
            [jolt.aspect-packs.http-server.model :as model]
            [jolt.aspect-packs.http-server.provider :as provider]))

(def request-join-point
  {:id :http/server-ring-handler
   :site-id "http-server-handler-test-site"
   :build-identity "aspect-packs-http-server-test-build"})

(def response-join-point
  {:id :http/server-sanitized-response
   :site-id "http-server-response-test-site"
   :build-identity "aspect-packs-http-server-test-build"})

(defn- observe-response! [response]
  (provider/around-response response-join-point [response]
                            (fn [] [response nil])))

(defn- apply-advice [journal handler request respond raise]
  (binding [history/*journal* journal]
    (let [args [handler request :socket :done :buffer :read-buffer {} :handled]
          invoke (fn [replacement]
                   ((first replacement) (second replacement) respond raise))]
      (provider/around-request
       request-join-point args
       (fn
         ([] (invoke args))
         ([replacement] (invoke replacement)))))))

(deftest synchronous-request-is-redacted-and-completes-with-sanitized-status
  (let [journal (history/journal)
        response {:status 201 :headers {"authorization" "private"}
                  :body "private body"}
        callback-result (Object.)
        result
        (apply-advice
         journal
         (fn [_ respond _] (respond response false))
         {:request-method :post :scheme :https
          :uri "/orders/42" :query-string "token=secret"
          :protocol "HTTP/1.1" :server-name "private.example"
          :remote-addr "192.0.2.10"
          :headers {"authorization" "secret"} :body "secret"}
         (fn [actual async?]
           (is (identical? response actual))
           (is (false? async?))
           (observe-response! actual)
           callback-result)
         (fn [_] (throw (ex-info "unexpected raise" {}))))]
    (is (identical? callback-result result))
    (is (= [{:seq 1
             :operation-id 0
             :parent-operation-id nil
             :context-id nil
             :causal-links []
             :phase :invoke
             :operation :http/server-ring-handler
             :site-id "http-server-handler-test-site"
             :build-identity "aspect-packs-http-server-test-build"
             :input {:http.request.method "POST"
                     :url.scheme "https"
                     :url.path "/orders/42"
                     :network.protocol.version "1.1"}}
            {:seq 2 :operation-id 0 :phase :return
             :value {:http.response.status_code 201}}]
           (history/events journal)))
    (is (not (.contains (pr-str (history/events journal)) "secret")))
    (is (= (history/events journal) (model/check! (history/events journal))))))

(deftest async-handler-return-does-not-close-request-before-callback
  (let [journal (history/journal)
        callbacks (atom nil)
        handler-result (Object.)
        callback-result (Object.)
        observed
        (apply-advice
         journal
         (fn [_ respond raise]
           (reset! callbacks {:respond respond :raise raise})
           handler-result)
         {:request-method :get :uri "/events"}
         (fn [response _]
           (observe-response! response)
           callback-result)
         (fn [_] (throw (ex-info "unexpected raise" {}))))]
    (is (identical? handler-result observed))
    (is (= [0] (history/open-operation-ids journal)))
    (is (= [:invoke] (mapv :phase (history/events journal))))
    (is (identical? callback-result
                    ((:respond @callbacks) {:status 204} true)))
    (is (true? (history/assert-complete! journal)))
    (is (= {:http.response.status_code 204}
           (:value (second (history/events journal)))))
    (is (= (history/events journal) (model/check! (history/events journal))))))

(deftest raise-and-unexpected-throw-have-privacy-safe-terminal-data
  (testing "raise records the sanitized error response without exception data"
    (let [journal (history/journal)
          private-error (ex-info "private message" {:token "secret"})]
      (apply-advice
       journal
       (fn [_ _ raise] (raise private-error))
       {:request-method :get :uri "/raise"}
       (fn [_ _] nil)
       (fn [actual]
         (is (identical? private-error actual))
         (observe-response! {:status 500})
         :raised))
      (is (= {:http.response.status_code 500 :error.type "_OTHER"}
             (:value (second (history/events journal)))))
      (is (= [:invoke :throw] (mapv :phase (history/events journal))))
      (is (not (.contains (pr-str (history/events journal)) "private message")))
      (is (= (history/events journal) (model/check! (history/events journal))))))
  (testing "an unexpected handler throw preserves Throwable identity"
    (let [journal (history/journal)
          expected (ex-info "private failure" {:token "secret"})
          observed (try
                     (apply-advice journal (fn [_ _ _] (throw expected))
                                   {:request-method :get :uri "/throw"}
                                   (fn [_ _] nil) (fn [_] nil))
                     nil
                     (catch Throwable error error))]
      (is (identical? expected observed))
      (is (= [:invoke :throw] (mapv :phase (history/events journal))))
      (is (= {:error.type "_OTHER"}
             (:value (second (history/events journal)))))
      (is (= (history/events journal) (model/check! (history/events journal)))))))

(deftest duplicate-callbacks-close-history-exactly-once
  (let [journal (history/journal)
        callbacks (atom nil)
        calls (atom [])]
    (apply-advice journal
                  (fn [_ respond raise]
                    (reset! callbacks {:respond respond :raise raise}))
                  {:request-method :get :uri "/race"}
                  (fn [response _]
                    (swap! calls conj [:respond (:status response)])
                    (observe-response! response))
                  (fn [_] (swap! calls conj [:raise])))
    ((:respond @callbacks) {:status 202} true)
    ((:raise @callbacks) (ex-info "late private error" {}))
    (is (= [[:respond 202] [:raise]] @calls)
        "losing callbacks still reach jolt-http's own arbitration")
    (is (= 2 (count (history/events journal))))
    (is (= {:http.response.status_code 202}
           (:value (second (history/events journal)))))
    (is (= (history/events journal) (model/check! (history/events journal))))))

(deftest generated-bounded-semconv-histories-satisfy-hegel-rules
  (with {:test-cases 40 :database "" :verbosity :quiet}
    [method (g/sampled-from [:get :post :patch :private-extension])
     status (g/integer 100 599)
     suffix (g/string {:max-size 32 :alphabet "abc012-/"})]
    (let [journal (history/journal)
          path (str "/" suffix)]
      (apply-advice journal
                    (fn [_ respond _]
                      (respond {:status status :headers {} :body nil} false))
                    {:request-method method :scheme :http :uri path
                     :protocol "HTTP/1.1"}
                    (fn [response _] (observe-response! response))
                    (fn [_] nil))
      (is (= (history/events journal)
             (model/check! (history/events journal)))))))

(deftest exact-target-manifest-and-provider-pins-agree
  (let [manifest
        (edn/read-string
         (slurp "resources/META-INF/jolt/aspects/packs/http-server-c6effc3.edn"))
        targets (edn/read-string (slurp "targets.edn"))
        target (get-in targets [:targets 'casselc/jolt-http])]
    (is (= 1 (:schema manifest)))
    (is (= 'casselc/jolt-http (get-in manifest [:library :id])))
    (is (= provider/seam-revision (get-in manifest [:library :version])))
    (is (= provider/seam-revision
           (get-in provider/aspect-provider [:libraries 'casselc/jolt-http])))
    (is (= provider/target-revision (:git/sha target)))
    (is (= provider/target-base-revision (get-in target [:base :git/sha])))
    (is (= provider/seam-revision (get-in target [:seam :git/sha])))
    (is (= [:http/server-ring-handler :http/server-sanitized-response]
           (mapv :id (:aspects manifest))))
    (is (= [1 1] (mapv #(get-in % [:expect :matches]) (:aspects manifest))))))
