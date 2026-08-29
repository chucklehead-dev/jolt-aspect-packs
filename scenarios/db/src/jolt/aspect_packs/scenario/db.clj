(ns jolt.aspect-packs.scenario.db
  (:require [db.driver :as driver]
            [db.jdbc-shim :as shim]
            [jolt.aspect-packs.history :as history]
            [otel.exporter.memory :as memory]
            [otel.instrumentation.db :as otel-db]
            [otel.sdk :as sdk]))

(def scenario-driver
  (reify driver/Driver
    (descriptor [_]
      {:id :duckdb
       :aliases #{"aspect-db"}
       :uri-prefixes ["aspect-db:"]
       :product-name "Aspect DB fixture"
       :capabilities {:transactions :none :generated-keys :none}})
    (open-handle [_ _] (Object.))
    (close-handle [_ _] nil)
    (execute-handle [_ _ sql _]
      (if (= sql "SELECT private FROM fixture")
        {:labels ["private"] :rows [[42]] :count 0}
        {:labels [] :rows [] :count 1}))))

(defn -main [& args]
  (driver/register! scenario-driver)
  (try
    (let [plain? (= ["plain"] (vec args))
          journal (history/journal)
          exporter (memory/multisignal-exporter)
          sdk-handle (sdk/init! {:service-name "db-pack-compiled-scenario"
                                 :exporter exporter
                                 :processor :simple
                                 :runtime-metrics? false
                                 :logs? true
                                 :bridge-logging? false})]
      (try
        (let [conn (shim/connection "aspect-db:private")]
          (try
            (let [result (binding [history/*journal* journal
                               history/*context-id* :db-scenario
                               otel-db/*capture-row-counts?* true]
                       (shim/execute-any conn "SELECT private FROM fixture" []))
              events (history/events journal)
              flushed? (sdk/force-flush! sdk-handle)
              _ (when (and (not plain?) (not flushed?))
                  (throw (ex-info "compiled scenario telemetry flush failed"
                                  {})))
              spans (memory/spans exporter)
              metrics (memory/metrics exporter)
              duration (first (filter #(= "db.client.operation.duration"
                                          (:name %))
                                      metrics))]
          (when-not (= {:labels ["private"] :rows [[42]] :count 0} result)
            (throw (ex-info "database scenario result changed" {:result result})))
          (if plain?
            (when (or (seq events) (seq spans) duration)
              (throw (ex-info "plain database build ran aspect advice"
                              {:events events :spans spans :metrics metrics})))
            (do
              (when-not (= "duckdb" (get-in events [0 :input :system]))
                (throw (ex-info "history consumer did not run"
                                {:events events})))
              (when-not (= :db.jdbc-shim/execute
                           (get-in events [0 :operation]))
                (throw (ex-info "history event lost semantic operation identity"
                                {:events events})))
              (when-not (= :db-scenario (get-in events [0 :context-id]))
                (throw (ex-info "history event lost the caller context"
                                {:events events})))
              (when-not
               (and (string? (get-in events [0 :site-id]))
                    (pos? (count (get-in events [0 :site-id])))
                    (string? (get-in events [0 :build-identity]))
                    (pos? (count (get-in events [0 :build-identity]))))
                (throw (ex-info "history event lacks current compiler provenance"
                                {:events events})))
              (when (.contains (pr-str events) "private")
                (throw (ex-info "history consumer retained private SQL or values"
                                {:events events})))
              (history/assert-complete! journal)
              (let [span (first spans)
                    point (first (:data-points duration))]
                (when-not (= 1 (count spans))
                  (throw (ex-info "woven database build emitted wrong span count"
                                  {:spans spans})))
                (when-not (= {:name "SELECT" :kind :client :system "duckdb"
                              :operation "SELECT" :returned-rows 1}
                             {:name (:name span)
                              :kind (:kind span)
                              :system (get (:attributes span) "db.system.name")
                              :operation (get (:attributes span)
                                              "db.operation.name")
                              :returned-rows (get (:attributes span)
                                                  "db.response.returned_rows")})
                  (throw (ex-info "woven database span is not semantically complete"
                                  {:span span})))
                (when-not (and duration (= "s" (:unit duration))
                               (= "duckdb"
                                  (get (:attributes point) "db.system.name"))
                               (= "SELECT"
                                  (get (:attributes point) "db.operation.name")))
                  (throw (ex-info "woven database duration metric is incomplete"
                                  {:metric duration})))
                  (when (.contains (pr-str {:spans spans :metrics metrics})
                                   "private")
                    (throw (ex-info "woven OTel signals retained private data"
                                    {})))))))
            (finally
              (.close conn))))
        (finally
          (sdk/shutdown! sdk-handle))))
    (finally (driver/unregister! :duckdb)))
  (println (if (= ["plain"] (vec args))
             "DB plain scenario remained uninstrumented"
             "DB dual-consumer aspect scenario ran")))
