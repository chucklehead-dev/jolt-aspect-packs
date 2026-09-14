(ns jolt.aspect-packs.http-client.provider-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [jolt.aspect-packs.history :as history]
            [jolt.aspect-packs.http-client.model :as model]
            [jolt.aspect-packs.http-client.provider :as provider]))

(def join-point {:id :http-client.core/request
                 :site-id "http-client-test-site"
                 :build-identity "aspect-packs-test-build"})

(defn- run-advice
  [journal request proceed]
  (binding [history/*journal* journal]
    (provider/around-request join-point [request] proceed)))

(deftest preserves-results-and-records-redacted-input
  (let [journal (history/journal)
        result (Object.)
        request {:request-method :post
                 :scheme :https
                 :server-name "private.example"
                 :uri "/work?token=secret#fragment"
                 :query-string "token=secret"
                 :headers {"authorization" "secret"}
                 :body "secret"}]
    (is (identical? result (run-advice journal request #(identity result))))
    (is (= [{:seq 1
             :operation-id 0
             :parent-operation-id nil
             :context-id nil
             :causal-links []
             :phase :invoke
             :operation :http-client.core/request
             :site-id "http-client-test-site"
             :build-identity "aspect-packs-test-build"
             :input {:request-method :post :scheme :https :uri "/work"}}
            {:seq 2
             :operation-id 0
             :phase :return
             :value :returned}]
           (history/events journal)))
    (is (= (history/events journal)
           (model/check! (history/events journal))))))

(deftest preserves-thrown-identity
  (let [journal (history/journal)
        expected (ex-info "expected" {:kind :fixture})
        observed (try
                   (run-advice journal {} #(throw expected))
                   nil
                   (catch Throwable error error))]
    (is (identical? expected observed))
    (is (= [:invoke :throw] (mapv :phase (history/events journal))))
    (is (= (history/events journal)
           (model/check! (history/events journal))))))

(deftest records-nested-parentage
  (let [journal (history/journal)]
    (binding [history/*journal* journal]
      (provider/around-request
       join-point [{}]
       #(provider/around-request join-point [{}] (fn [] :done))))
    (let [[outer-enter inner-enter inner-return outer-return]
          (history/events journal)]
      (is (= [1 2 3 4]
             (mapv :seq [outer-enter inner-enter inner-return outer-return])))
      (is (= (:operation-id outer-enter)
             (:parent-operation-id inner-enter)))
      (is (= (:operation-id inner-enter) (:operation-id inner-return)))
      (is (= (:operation-id outer-enter) (:operation-id outer-return)))
      (is (= (history/events journal)
             (model/check! (history/events journal)))))))

(deftest concurrent-history-is-contiguous-and-complete
  (let [journal (history/journal)
        workers (mapv (fn [n]
                        (future
                          (run-advice journal {:request-method :get :uri "/work"}
                                      (fn [] n))))
                      (range 32))]
    (doseq [worker workers] @worker)
    (let [events (history/events journal)
          grouped (group-by :operation-id events)]
      (is (= (range 1 65) (map :seq events)))
      (is (= 32 (count grouped)))
      (is (every? #(= #{:invoke :return} (set (map :phase %)))
                  (vals grouped)))
      (is (= events (model/check! events))))))

(deftest manifest-and-provider-agree-on-pinned-target
  (let [targets (edn/read-string (slurp "targets.edn"))
        target (get-in targets [:targets 'jolt-lang/http-client])
        scenario (edn/read-string (slurp "scenarios/http-client/deps.edn"))
        manifest
        (edn/read-string
         (slurp "resources/META-INF/jolt/aspects/packs/http-client-eab6b78.edn"))]
    (is (= 1 (:schema manifest)))
    (is (= 'jolt-lang/http-client (get-in manifest [:library :id])))
    (is (= provider/target-revision
           (get-in manifest [:library :version])))
    (is (= provider/target-revision
           (get-in provider/aspect-provider
                   [:libraries 'jolt-lang/http-client])))
    (is (= "eab6b78d5957f88690faf6768360572a3f185341"
           (:git/sha target)
           (get-in scenario [:deps 'jolt-lang/http-client :git/sha])))
    (is (= provider/target-revision (:seam-revision target)))
    (is (= {:id 'jolt-lang/jolt
            :git/sha "2d39e854a90926d8f8e9bd5d3ddbb109d657afe1"
            :chez "10.4.1"}
           (:compiler target)))
    (is (= "META-INF/jolt/aspects/packs/http-client-eab6b78.edn"
           (:manifest target)
           (get-in scenario [:jolt/build :aspects 0 :resource])))
    (is (= "scenarios/http-client" (:scenario target)))
    (is (= 1 (get-in manifest [:aspects 0 :expect :matches])))))
