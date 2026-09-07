(ns jolt.aspect-packs.scenario.chdb-durable-crash
  (:require [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.local-posix :as local]
            [jdbc.chdb.durable.writer :as writer]
            [jolt.aspect-packs.chdb-durable.faults :as faults])
  (:import [java.io File]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(def object-id "crash-object")

(defn- delete-tree! [path]
  (let [file (.toFile ^Path path)]
    (when (.exists file)
      (doseq [child (or (.listFiles file) (make-array File 0))]
        (delete-tree! (.toPath child)))
      (.delete file))))

(defn- recovery-operations [executed]
  {:now-ms (constantly 1000M)
   :await-heartbeat! (fn [stop _] @stop :stop)
   :durable-capability (fn [] {:status :supported
                               :native-version "26.7.2-rc.2"})
   :create-scratch! (fn [_]
                      (Files/createTempDirectory
                       "jchdb-crash-recovery-" (make-array FileAttribute 0)))
   :cleanup-scratch! delete-tree!
   :open-native! (constantly :fake-handle)
   :close-native! (constantly nil)
   :restore-database! (fn [_ _ _] nil)
   :create-checkpoint! (fn [& _]
                         (throw (ex-info "unused" {:type ::unused})))
   :delete-checkpoint! (constantly nil)
   :create-database! (fn [_ _] nil)
   :use-database! (fn [_ _] nil)
   :analyze-query! (fn [& _] nil)
   :analyze-execute! (fn [& _] nil)
   :classification-sql! (fn [sql _] sql)
   :classify! (fn [& _]
                {:query-class :read-only :statement-count 1
                 :has-secrets false :writes-only-target-database true
                 :changes-database-lifecycle false})
   :query-native! (fn [& _] nil)
   :query-bytes-native! (fn [& _] nil)
   :execute-native! (fn [_ sql] (swap! executed conj sql))})

(defn- produce! [namespace operation phase ready-file]
  (let [store (backend/object-backend namespace object-id)
        acquired (control/acquire!
                  store {:owner "producer" :instance "crash-run"
                         :expires-at 500M :now 0M :database "default"
                         :engine-version "26.7.2-rc.2" :backup-format 1
                         :min-reader "26.7.2-rc.2"})
        token (:token acquired)
        action {:operation operation :phase phase :ready-file ready-file}]
    (faults/call-with-fault
     action
     #(let [publication
            (control/publish-wal-bytes!
             store token
             (.getBytes "{\"sql\":\"INSERT INTO t VALUES (1)\"}\n" "UTF-8"))]
        (control/commit-reference!
         store token {:kind :wal :reference (:reference publication)
                      :verify-reference! control/verify-byte-reference!})))))

(defn- recover! [namespace]
  (let [executed (atom [])
        opened (durable/open-writer!
                {:namespace-backend namespace :object-id object-id
                 :owner "recovery" :instance "fresh-process"
                 :database "default" :lease-ttl-ms 100M :force? true
                 :operations (recovery-operations executed)})]
    (try
      (let [head (:head (control/read-head!
                         (backend/object-backend namespace object-id)))]
        (println (pr-str {:sequence (get-in head ["manifest" "seq"])
                          :wal-count (count (get-in head ["manifest" "wal"]))
                          :replayed (count @executed)})))
      (finally
        (writer/close! opened)))))

(defn -main [root mode & args]
  (let [namespace (local/local-backend root)]
    (case mode
      "produce"
      (produce! namespace (keyword (first args)) (keyword (second args))
                (nth args 2))

      "recover" (recover! namespace)

      (throw (ex-info "Unknown Durable crash scenario mode" {:mode mode})))))
