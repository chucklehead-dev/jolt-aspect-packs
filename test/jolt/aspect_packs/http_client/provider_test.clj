(ns jolt.aspect-packs.http-client.provider-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [jolt.aspect-packs.history :as history]
            [jolt.aspect-packs.http-client.model :as model]
            [jolt.aspect-packs.http-client.provider :as provider]))

(def join-point {:id :http-client.core/request})

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
             :phase :enter
             :op :http-client.core/request
             :input {:request-method :post :scheme :https :uri "/work"}}
            {:seq 2
             :operation-id 0
             :phase :return
             :output :returned}]
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
    (is (= [:enter :throw] (mapv :phase (history/events journal))))
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
      (is (every? #(= #{:enter :return} (set (map :phase %)))
                  (vals grouped)))
      (is (= events (model/check! events))))))

(deftest manifest-and-provider-agree-on-pinned-target
  (let [manifest
        (edn/read-string
         (slurp "resources/META-INF/jolt/aspects/packs/http-client-12b78ed.edn"))]
    (is (= 1 (:schema manifest)))
    (is (= 'jolt-lang/http-client (get-in manifest [:library :id])))
    (is (= provider/target-revision
           (get-in manifest [:library :version])))
    (is (= provider/target-revision
           (get-in provider/aspect-provider
                   [:libraries 'jolt-lang/http-client])))
    (is (= 1 (get-in manifest [:aspects 0 :expect :matches])))))
