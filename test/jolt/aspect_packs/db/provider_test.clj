(ns jolt.aspect-packs.db.provider-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [db.driver :as driver]
            [hegel.clojure-test :as hegel-test]
            [hegel.generator :as g]
            [jolt.aspect-packs.db.model :as model]
            [jolt.aspect-packs.db.provider :as provider]
            [jolt.aspect-packs.history :as history]))

(def join-point
  {:id :db.jdbc-shim/execute
   :site-id "db-provider-test-site"
   :build-identity "db-provider-test-build"})

(defn- test-driver [descriptor]
  (reify driver/Driver
    (descriptor [_] descriptor)
    (open-handle [_ _] nil)
    (close-handle [_ _] nil)
    (execute-handle [_ _ _ _] {:labels [] :rows [] :count 0})))

(defn- run-advice [journal db-driver sql params proceed]
  (binding [history/*journal* journal]
    (provider/around-execute join-point [db-driver :private-handle sql params]
                             proceed)))

(deftest operation-and-fingerprint-are-bounded-and-sanitized
  (is (= "SELECT" (provider/operation-name
                    " /* private */ SELECT * FROM customer WHERE token = ?")))
  (is (= "UNKNOWN" (provider/operation-name "vacuum private_customer")))
  (is (= (provider/statement-fingerprint
          "SELECT secret FROM customer WHERE token = 'first-secret'")
         (provider/statement-fingerprint
          "select other_secret FROM other_customer WHERE other_token = 'second-secret'")))
  (is (= (provider/statement-fingerprint
          "SELECT秘密 FROM 顧客 WHERE 鍵 = 'first-secret'")
         (provider/statement-fingerprint
          "SELECTдругое FROM таблица WHERE ключ = 'second-secret'")))
  (is (not= (provider/statement-fingerprint "SELECT x FROM t")
            (provider/statement-fingerprint "SELECT x, y FROM t")))
  (doseq [secret ["secret" "customer" "first-secret" "second-secret"]]
    (is (not (.contains
              (provider/statement-fingerprint
               "SELECT secret FROM customer WHERE token = 'first-secret'")
              secret)))))

(deftest operation-classification-fails-closed-for-ambiguous-sql
  (doseq [[sql expected fingerprint?]
          [["/* leading ; */ SELECT ';' AS \"semi;colon\", `tick;name`; -- trailing ;"
            "SELECT" true]
           ["SELECT (1 + (2)); /* one optional terminator */" "SELECT" true]
           ["SELECT 1; UPDATE private SET token = 2" "UNKNOWN" false]
           ["SELECT 1; /* hidden ; */ DELETE FROM private" "UNKNOWN" false]
           ["SELECT 1;;" "UNKNOWN" false]
           ["; SELECT 1" "UNKNOWN" false]
           ["WITH cte AS (SELECT 1) SELECT * FROM cte" "UNKNOWN" true]
           ["WITH RECURSIVE cte AS (SELECT 1) SELECT * FROM cte" "UNKNOWN" true]
           ["SELECT (1" "UNKNOWN" false]
           ["SELECT 1)" "UNKNOWN" false]
           ["SELECT 'unterminated" "UNKNOWN" false]
           ["SELECT 'secret\\'" "UNKNOWN" false]
           ["SELECT \"secret\\\"" "UNKNOWN" false]
           ["SELECT `secret\\`" "UNKNOWN" false]
           ["SELECT 1 /* unterminated" "UNKNOWN" false]
           ["SELECT $$dialect;quoted$$" "UNKNOWN" false]
           ["SELECT [dialect;quoted]" "UNKNOWN" false]
           [(str "SELECT " (apply str (repeat 4090 "x"))) "UNKNOWN" false]]]
    (testing sql
      (is (= expected (provider/operation-name sql)))
      (is (= fingerprint?
             (some? (provider/statement-fingerprint sql)))))))

(deftest quoted-and-comment-semicolons-do-not-change-simple-operation
  (hegel-test/with
    {:name "db-operation-quoted-semicolon-v1"
     :test-cases 100
     :database ""
     :derandomize? true
     :verbosity :quiet}
    [payload (g/string {:max-size 48
                        :alphabet "abcXYZ0123; -_"})]
    (let [sql (str "/* " payload "; */ SELECT '" payload
                   "' AS \"" payload "\", `" payload
                   "` /* " payload "; */; -- " payload ";")]
      (is (= "SELECT" (provider/operation-name sql)))
      (is (re-matches #"fnv1a32:[0-9a-f]{8}"
                      (provider/statement-fingerprint sql))))))

(deftest compound-and-cte-statements-never-guess-an-operation
  (hegel-test/with
    {:name "db-operation-ambiguous-statement-v1"
     :test-cases 100
     :database ""
     :derandomize? true
     :verbosity :quiet}
    [first-op (g/sampled-from ["SELECT" "INSERT" "UPDATE" "DELETE"])
     second-op (g/sampled-from ["SELECT" "INSERT" "UPDATE" "DELETE"])
     payload (g/string {:max-size 32 :alphabet "abcXYZ0123; -_"})]
    (let [compound (str first-op " value; /* " payload "; */ "
                        second-op " other")
          cte (str "WITH cte AS (SELECT '" payload
                   "') " first-op " value")]
      (is (= "UNKNOWN" (provider/operation-name compound)))
      (is (nil? (provider/statement-fingerprint compound)))
      (is (= "UNKNOWN" (provider/operation-name cte)))
      (is (re-matches #"fnv1a32:[0-9a-f]{8}"
                      (provider/statement-fingerprint cte))))))

(deftest records-semantic-input-and-pack-owned-return-shape
  (doseq [[descriptor expected-system]
          [[{:id :chdb :database-namespace "analytics"} "clickhouse"]
           [{:id :duckdb} "duckdb"]
           [{:id :sqlite} "sqlite"]
           [{:id :postgresql} "postgresql"]
           [{:id :private-driver} "other_sql"]]]
    (testing expected-system
      (let [journal (history/journal)
            result {:labels ["private"] :rows [["secret"] ["secret-2"]]
                    :count 0}
            observed (run-advice
                      journal (test-driver descriptor)
                      "SELECT private FROM customer WHERE token = ?"
                      ["secret-parameter"] #(identity result))
            events (history/events journal)
            input (:input (first events))]
        (is (identical? result observed))
        (is (= "SELECT" (:operation input)))
        (is (= expected-system (:system input)))
        (is (not (contains? input :namespace)))
        (is (re-matches #"fnv1a32:[0-9a-f]{8}"
                        (:statement-fingerprint input)))
        (is (= {:outcome :ok :row-count 2 :row-count-kind :returned}
               (:value (second events))))
        (doseq [secret ["private-handle" "private" "customer"
                        "secret-parameter" "secret" "secret-2"]]
          (is (not (.contains (pr-str events) secret))))
        (is (true? (history/assert-complete! journal)))
        (is (= events (model/check! events)))))))

(deftest affected-count-and-unknown-result-shapes-are-safe
  (let [db-driver (test-driver {:id :duckdb})
        mutation (history/journal)
        unknown (history/journal)]
    (run-advice mutation db-driver "UPDATE private SET token = ?" ["secret"]
                #(hash-map :labels [] :rows [] :count 7))
    (run-advice unknown db-driver "VACUUM private" []
                #(hash-map :private "not retained"))
    (is (= {:outcome :ok :row-count 7 :row-count-kind :affected}
           (:value (second (history/events mutation)))))
    (is (= {:outcome :ok}
           (:value (second (history/events unknown)))))
    (is (= (history/events mutation) (model/check! (history/events mutation))))
    (is (= (history/events unknown) (model/check! (history/events unknown))))))

(deftest affected-count-outside-int64-range-is-not-retained
  (let [journal (history/journal)]
    (run-advice journal (test-driver {:id :duckdb})
                "UPDATE private SET token = ?" ["secret"]
                #(hash-map :labels [] :rows []
                           :count 9223372036854775808))
    (is (= {:outcome :ok} (:value (second (history/events journal)))))
    (is (= (history/events journal) (model/check! (history/events journal))))))

(deftest thrown-identity-is-preserved-and-private-error-data-is-excluded
  (let [journal (history/journal)
        expected (ex-info "password=super-secret"
                          {:sql "SELECT private" :token "also-secret"})
        observed (try
                   (run-advice journal (test-driver {:id :postgresql})
                               "SELECT private FROM customer" []
                               #(throw expected))
                   nil
                   (catch Throwable error error))
        events (history/events journal)]
    (is (identical? expected observed))
    (is (= [:invoke :throw] (mapv :phase events)))
    (is (= :error (get-in events [1 :value :outcome])))
    (is (string? (get-in events [1 :value :error-type])))
    (is (<= (count (get-in events [1 :value :error-type])) 255))
    (doseq [secret ["super-secret" "also-secret" "SELECT private" "customer"]]
      (is (not (.contains (pr-str events) secret))))
    (is (true? (history/assert-complete! journal)))
    (is (= events (model/check! events)))))

(deftest nested-operation-preserves-context-and-parentage
  (let [journal (history/journal)
        db-driver (test-driver {:id :sqlite})]
    (binding [history/*journal* journal history/*context-id* :request-17]
      (provider/around-execute
       join-point [db-driver nil "SELECT 1" []]
       #(provider/around-execute
         join-point [db-driver nil "INSERT INTO t VALUES (?)" ["private"]]
         (fn [] {:labels [] :rows [] :count 1}))))
    (let [[outer inner] (filterv #(= :invoke (:phase %))
                                 (history/events journal))]
      (is (= (:operation-id outer) (:parent-operation-id inner)))
      (is (= [:request-17 :request-17]
             (mapv :context-id [outer inner])))
      (is (= [[] []] (mapv :causal-links [outer inner])))
      (is (= (history/events journal)
             (model/check! (history/events journal)))))))

(deftest db-model-requires-canonical-causal-links
  (let [journal (history/journal)
        db-driver (test-driver {:id :duckdb})]
    (run-advice journal db-driver "SELECT 1" []
                (fn [] {:labels [] :rows [[1]] :count 1}))
    (let [events (history/events journal)
          missing (update events 0 dissoc :causal-links)
          duplicate (assoc-in events [0 :causal-links] [0 0])]
      (is (= [] (get-in events [0 :causal-links])))
      (is (thrown? Exception (model/check! missing)))
      (is (thrown? Exception (model/check! duplicate))))))

(deftest absent-journal-is-inert
  (let [descriptor-called? (atom false)
        db-driver
        (reify driver/Driver
          (descriptor [_]
            (reset! descriptor-called? true)
            (throw (ex-info "must not inspect" {})))
          (open-handle [_ _] nil)
          (close-handle [_ _] nil)
          (execute-handle [_ _ _ _] nil))
        result (Object.)]
    (is (identical? result
                    (provider/around-execute
                     join-point [db-driver :private "SELECT private" []]
                     #(identity result))))
    (is (false? @descriptor-called?))))

(deftest generated-histories-close-resources-and-obey-context-rules
  (hegel-test/with
    {:name "db-history-lifecycle-v1"
     :test-cases 100
     :database ""
     :derandomize? true
     :verbosity :quiet}
    [operations
     (g/vector
      {:max-size 24}
      (g/tuple
       (g/sampled-from ["SELECT private FROM t WHERE token = ?"
                        "UPDATE private SET token = ?"
                        "nonsense private statement"])
       (g/integer 0 20)
       (g/boolean)))]
    (let [journal (history/journal)
          db-driver (test-driver {:id :chdb :database-namespace "generated"})]
      (doseq [[sql n fail?] operations]
        (let [failure (ex-info "private generated failure" {:n n})]
          (try
            (run-advice journal db-driver sql ["private parameter"]
                        #(if fail?
                           (throw failure)
                           {:labels ["private"]
                            :rows (vec (repeat n ["private value"]))
                            :count n}))
            (catch Throwable error
              (is (identical? failure error))))))
      (is (true? (history/assert-complete! journal)))
      (is (= (history/events journal)
             (model/check! (history/events journal))))
      (is (not (.contains (pr-str (history/events journal)) "private"))))))

(deftest manifest-provider-and-target-pins-agree
  (let [manifest (edn/read-string
                  (slurp "resources/META-INF/jolt/aspects/packs/db-a55c554.edn"))
        target-resource (io/resource "META-INF/jolt/aspects/db-jdbc-shim.edn")
        target-manifest (some-> target-resource slurp edn/read-string)
        targets (edn/read-string (slurp "targets.edn"))
        target (get-in targets [:targets 'jolt-lang/db])
        otel-consumer (first (:consumers target))]
    (is (some? target-resource))
    (is (= manifest target-manifest))
    (is (= provider/seam-revision (get-in manifest [:library :version])))
    (is (= provider/seam-revision
           (get-in provider/aspect-provider [:libraries 'jolt-lang/db])))
    (is (= provider/target-revision (:git/sha target)))
    (is (= {:id 'jolt-lang/jolt
            :git/sha "04a543a291067fd51dc9aee1867b2b86f4b3a364"
            :chez "10.4.1"}
           (:compiler target)))
    (is (= provider/seam-revision (:seam-revision target)))
    (is (= "META-INF/jolt/aspects/packs/db-a55c554.edn" (:manifest target)))
    (is (= "scenarios/db" (:scenario target)))
    (is (= "scenarios/db-plain" (:plain-scenario target)))
    (is (= 'io.github.chucklehead-dev/jolt-otel-instrumentation-db
           (:id otel-consumer)))
    (is (= "0b6a5b850bb959563cff602ec684bb48dcc2f541"
           (:git/sha otel-consumer)))
    (is (= {:ns 'db.jdbc-shim :call 'db.driver/execute-handle :arity 4}
           (get-in manifest [:aspects 0 :match])))
    (is (= 1 (get-in manifest [:aspects 0 :expect :matches])))))
