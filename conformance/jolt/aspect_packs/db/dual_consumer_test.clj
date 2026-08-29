(ns jolt.aspect-packs.db.dual-consumer-test
  (:require [clojure.test :refer [deftest is]]
            [db.driver :as driver]
            [jolt.aspect-packs.db.model :as model]
            [jolt.aspect-packs.db.provider :as history-provider]
            [jolt.aspect-packs.history :as history]
            [otel.exporter.memory :as memory]
            [otel.instrumentation.db :as otel-provider]
            [otel.sdk :as sdk]))

(def join-point
  {:id :db.jdbc-shim/execute
   :site-id "db-dual-consumer-source-site"
   :build-identity "db-dual-consumer-source-build"})

(defn- test-driver [id]
  (reify driver/Driver
    (descriptor [_] {:id id :product-name (name id)})
    (open-handle [_ _] nil)
    (close-handle [_ _] nil)
    (execute-handle [_ _ _ _] {:labels [] :rows [] :count 0})))

(defn- with-memory-sdk [f]
  (let [exporter (memory/multisignal-exporter)
        handle (sdk/init! {:service-name "db-pack-dual-consumer-test"
                           :exporter exporter
                           :processor :simple
                           :runtime-metrics? false
                           :logs? true
                           :bridge-logging? false})]
    (try
      (f exporter handle)
      (finally
        (sdk/shutdown! handle)))))

(defn- invoke-consumers [journal db-driver sql params proceed]
  ;; This is the compiler report's provider order. OTel is the outer consumer,
  ;; and the neutral history consumer owns the terminal shaping nearest the
  ;; application call.
  (binding [history/*journal* journal]
    (otel-provider/around
     join-point [db-driver :private-handle sql params]
     #(history-provider/around-execute
       join-point [db-driver :private-handle sql params]
       proceed))))

(defn- duration-metrics [exporter]
  (filterv #(= "db.client.operation.duration" (:name %))
           (memory/metrics exporter)))

(defn- span-duration-seconds [span]
  (/ (- (:end-time-unix-nano span) (:start-time-unix-nano span))
     1000000000.0))

(defn- batched-spans [batches]
  (vec (mapcat identity batches)))

(deftest exact-source-seam-emits-history-span-and-duration-metric
  (doseq [[id expected-system] [[:chdb "clickhouse"] [:duckdb "duckdb"]]]
    (with-memory-sdk
      (fn [exporter handle]
        (let [journal (history/journal)
              db-driver (test-driver id)
              result {:labels ["private"] :rows [["secret"]] :count 0}
              observed
              (binding [otel-provider/*capture-row-counts?* true]
                (invoke-consumers
                 journal db-driver "SELECT private" ["secret"]
                 (fn []
                   ;; Give the independent span and metric clocks a meaningful
                   ;; shared interval so a unit mismatch cannot pass as noise.
                   (Thread/sleep 10)
                   result)))
              events (history/events journal)]
          (is (sdk/force-flush! handle))
          (let [spans (vec (memory/spans exporter))
                batches (vec (memory/batches exporter))
                records (vec (memory/records exporter))
                all-metrics (vec (memory/metrics exporter))
                db-duration-metrics (duration-metrics exporter)
                span (first spans)
                metric (first db-duration-metrics)
                point (first (:data-points metric))
                span-seconds (span-duration-seconds span)
                metric-seconds (:sum point)
                observed-signals (pr-str {:history events
                                          :spans spans
                                          :metrics all-metrics
                                          :records records})]
            (is (identical? result observed))
            (is (= 2 (count events)))
            (is (= [:invoke :return] (mapv :phase events)))
            (is (= 1 (count (set (map :operation-id events)))))
            (is (= expected-system (get-in events [0 :input :system])))
            (is (= {:outcome :ok :row-count 1 :row-count-kind :returned}
                   (get-in events [1 :value])))
            (is (true? (history/assert-complete! journal)))
            (is (= events (model/check! events)))

            (is (= 1 (count spans)))
            (is (= 1 (count batches)))
            (is (= 1 (count (first batches))))
            (is (= spans (batched-spans batches)))
            (is (empty? records))
            (is (= 1 (count all-metrics)))
            (is (= 1 (count db-duration-metrics)))
            (is (= 1 (count (:data-points metric))))
            (is (= 1 (:count point)))
            (is (= "SELECT" (:name span)))
            (is (= :client (:kind span)))
            (is (= expected-system
                   (get (:attributes span) "db.system.name")))
            (is (= "SELECT"
                   (get (:attributes span) "db.operation.name")))
            (is (= 1
                   (get (:attributes span) "db.response.returned_rows")))

            (is (= "s" (:unit metric)))
            (is (= expected-system
                   (get (:attributes point) "db.system.name")))
            (is (= "SELECT"
                   (get (:attributes point) "db.operation.name")))
            (is (not (neg? span-seconds)))
            (is (<= span-seconds metric-seconds))
            (is (< metric-seconds (+ 0.05 (* 2.0 span-seconds))))
            (doseq [secret ["private-handle" "private" "secret"]]
              (is (not (.contains observed-signals secret))))))))))

(deftest otel-row-count-is-opt-in-while-neutral-safe-count-remains-available
  (with-memory-sdk
    (fn [exporter handle]
      (let [journal (history/journal)
            result {:labels ["private"] :rows [["secret"] ["secret-2"]]
                    :count 0}]
        (binding [otel-provider/*capture-row-counts?* false]
          (invoke-consumers journal (test-driver :duckdb) "SELECT private" []
                            (fn [] result)))
        (is (sdk/force-flush! handle))
        (let [spans (vec (memory/spans exporter))
              batches (vec (memory/batches exporter))
              records (vec (memory/records exporter))
              all-metrics (vec (memory/metrics exporter))
              db-duration-metrics (duration-metrics exporter)
              span (first spans)
              events (history/events journal)]
          (is (= 1 (count spans)))
          (is (= 1 (count batches)))
          (is (= spans (batched-spans batches)))
          (is (empty? records))
          (is (= 1 (count all-metrics)))
          (is (= 1 (count db-duration-metrics)))
          (is (= 1 (count (:data-points (first db-duration-metrics)))))
          (is (nil? (get (:attributes span) "db.response.returned_rows")))
          (is (nil? (get (:attributes span)
                         "jolt.db.response.affected_rows")))
          (is (= {:outcome :ok :row-count 2 :row-count-kind :returned}
                 (get-in events [1 :value])))
          (is (true? (history/assert-complete! journal)))
          (is (= events (model/check! events))))))))

(deftest ambiguous-cte-is-not-mislabeled-or-duplicated
  (with-memory-sdk
    (fn [exporter handle]
      (let [journal (history/journal)
            sql "WITH private_cte AS (SELECT 1) SELECT * FROM private_cte"
            result {:labels ["private"] :rows [[1]] :count 0}
            observed (invoke-consumers journal (test-driver :duckdb) sql []
                                       (fn [] result))
            events (history/events journal)]
        (is (sdk/force-flush! handle))
        (let [spans (vec (memory/spans exporter))
              batches (vec (memory/batches exporter))
              records (vec (memory/records exporter))
              all-metrics (vec (memory/metrics exporter))
              db-duration-metrics (duration-metrics exporter)
              span (first spans)
              metric (first db-duration-metrics)
              point (first (:data-points metric))]
          (is (identical? result observed))
          (is (nil? (otel-provider/operation-name sql)))
          (is (= "UNKNOWN" (history-provider/operation-name sql)))
          (is (= 2 (count events)))
          (is (= [:invoke :return] (mapv :phase events)))
          (is (= "UNKNOWN" (get-in events [0 :input :operation])))
          (is (= 1 (count (set (map :operation-id events)))))
          (is (true? (history/assert-complete! journal)))
          (is (= events (model/check! events)))

          (is (= 1 (count spans)))
          (is (= 1 (count batches)))
          (is (= 1 (count (first batches))))
          (is (= spans (batched-spans batches)))
          (is (empty? records))
          (is (= 1 (count all-metrics)))
          (is (= 1 (count db-duration-metrics)))
          (is (= 1 (count (:data-points metric))))
          (is (= 1 (:count point)))
          (is (= "duckdb" (:name span)))
          (is (= "duckdb" (get (:attributes span) "db.system.name")))
          (is (nil? (get (:attributes span) "db.operation.name")))
          (is (= "duckdb" (get (:attributes point) "db.system.name")))
          (is (nil? (get (:attributes point) "db.operation.name")))
          (is (not (.contains (pr-str {:history events
                                       :spans spans
                                       :metrics all-metrics})
                              "private_cte"))))))))

(deftest failing-source-seam-emits-history-error-span-log-and-metric
  (with-memory-sdk
    (fn [exporter handle]
      (let [journal (history/journal)
            failure (ex-info "password=super-secret"
                             {:token "also-secret"})
            observed
            (try
              (invoke-consumers
               journal (test-driver :duckdb)
               "CALL private_procedure(?)" ["private-arg"]
               (fn [] (throw failure)))
              nil
              (catch Throwable error error))
            events (history/events journal)]
        (is (sdk/force-flush! handle))
        (let [spans (vec (memory/spans exporter))
              batches (vec (memory/batches exporter))
              records (vec (memory/records exporter))
              all-metrics (vec (memory/metrics exporter))
              db-duration-metrics (duration-metrics exporter)
              span (first spans)
              record (first records)
              metric (first db-duration-metrics)
              point (first (:data-points metric))
              observed-signals (pr-str {:history events
                                        :spans spans
                                        :metrics all-metrics
                                        :records records})]
          (is (identical? failure observed))
          (is (= 2 (count events)))
          (is (= [:invoke :throw] (mapv :phase events)))
          (is (= 1 (count (set (map :operation-id events)))))
          (is (= :error (get-in events [1 :value :outcome])))
          (is (string? (get-in events [1 :value :error-type])))
          (is (true? (history/assert-complete! journal)))
          (is (= events (model/check! events)))

          (is (= 1 (count spans)))
          (is (= 1 (count batches)))
          (is (= 1 (count (first batches))))
          (is (= spans (batched-spans batches)))
          (is (= 1 (count records)))
          (is (= 1 (count all-metrics)))
          (is (= 1 (count db-duration-metrics)))
          (is (= 1 (count (:data-points metric))))
          (is (= 1 (:count point)))
          (is (= "CALL" (:name span)))
          (is (= :error (get-in span [:status :code])))
          (is (= "clojure.lang.ExceptionInfo"
                 (get (:attributes span) "error.type")))
          (is (= "db.client.operation.exception" (:event-name record)))
          (is (= "clojure.lang.ExceptionInfo"
                 (get (:attributes record) "exception.type")))
          (is (= (get-in span [:span-context :trace-id]) (:trace-id record)))
          (is (= (get-in span [:span-context :span-id]) (:span-id record)))
          (is (some? (:trace-id record)))
          (is (some? (:span-id record)))
          (is (= "clojure.lang.ExceptionInfo"
                 (get (:attributes point) "error.type")))
          (is (= "CALL"
                 (get (:attributes point) "db.operation.name")))
          (doseq [secret ["super-secret" "also-secret" "private_procedure"
                          "private-arg"]]
            (is (not (.contains observed-signals secret)))))))))

(deftest both-providers-accept-the-published-seam-revision
  (is (= history-provider/seam-revision otel-provider/db-build-id))
  (is (= (get-in history-provider/aspect-provider [:libraries 'jolt-lang/db])
         (get-in otel-provider/aspect-provider [:libraries 'jolt-lang/db])))
  (is (= :args-v1
         (get-in history-provider/aspect-provider [:roles :db/client :contract])))
  (is (= :args-v1
         (get-in otel-provider/aspect-provider [:roles :db/client :contract]))))
