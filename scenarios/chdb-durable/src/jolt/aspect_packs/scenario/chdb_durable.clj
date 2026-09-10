(ns jolt.aspect-packs.scenario.chdb-durable
  (:require [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.control :as control]
            [jolt.aspect-packs.chdb-durable.model :as model]
            [jolt.aspect-packs.history :as history])
  (:import [java.nio.file Files OpenOption Path StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- exercise-control! []
  (let [store (backend/memory-backend)
        _ (control/acquire!
           store {:owner "old-private-owner" :instance "old-private-instance"
                  :expires-at 200M :now 100M :database "default"
                  :engine-version "1.0.0" :backup-format 1
                  :min-reader "1.0.0"})
        acquired (control/acquire!
                  store {:owner "private-owner" :instance "private-instance"
                         :expires-at 300M :now 101M :clock-skew 0M
                         :force? true :database "default"
                         :engine-version "1.0.0" :backup-format 1
                         :min-reader "1.0.0"})
        token (:token acquired)
        publication (control/publish-wal-bytes!
                     store token (.getBytes "SELECT 1\n" "UTF-8"))
        _ (control/commit-reference!
           store token {:kind :wal :reference (:reference publication)
                        :verify-reference! control/verify-byte-reference!})
        _ (control/renew! store token 400M)
        checkpoint (Files/createTempFile
                    "chdb-durable-aspect-" ".tar.gz"
                    (make-array FileAttribute 0))]
    (try
      (Files/write checkpoint (.getBytes "checkpoint" "UTF-8")
                   (into-array OpenOption [StandardOpenOption/TRUNCATE_EXISTING
                                           StandardOpenOption/WRITE]))
      (let [publication (control/publish-checkpoint-file!
                         store token checkpoint)]
        (control/commit-reference!
         store token {:kind :checkpoint :reference (:reference publication)
                      :engine-metadata {:version "1.0.0"
                                        :backup-format 1
                                        :min-reader "1.0.0"}
                      :verify-reference! control/verify-file-reference!}))
      (control/release! store token)
      (finally
        (Files/deleteIfExists ^Path checkpoint)))))

(defn -main [& args]
  (let [plain? (= ["plain"] (vec args))
        journal (history/journal)
        result (binding [history/*journal* journal
                         history/*context-id* :chdb-durable-scenario]
                 (exercise-control!))
        events (history/events journal)]
    (when-not (and (= :committed (:status result))
                   (nil? (get-in result [:head "lease" "owner"]))
                   (= 2 (get-in result [:head "lease" "generation"]))
                   (= {"version" "1.0.0"
                       "backup_format" 1
                       "min_reader" "1.0.0"}
                      (select-keys (get-in result [:head "engine"])
                                   ["version" "backup_format" "min_reader"]))
                   (= 2 (get-in result [:head "manifest" "seq"]))
                   (map? (get-in result [:head "manifest" "base"]))
                   (empty? (get-in result [:head "manifest" "wal"])))
      (throw (ex-info "Durable scenario did not release its writer" {})))
    (if plain?
      (when (seq events)
        (throw (ex-info "plain Durable build ran aspect advice"
                        {:event-count (count events)})))
      (let [commands (model/commands events)
            printed (pr-str events)]
        (when-not (= [:acquire :acquire :publish :commit-attempt :renew-attempt
                      :checkpoint-publish :commit-attempt :release-attempt]
                     (mapv :command commands))
          (throw (ex-info "woven Durable command history is incomplete"
                          {:commands commands})))
        (when-not (= [[]
                      [{:event :durable/forced-live-takeover
                        :severity :warning
                        :protocol-version 1
                        :lease-generation 2}]]
                     (mapv #(get-in % [:result :warnings])
                           (take 2 commands)))
          (throw (ex-info "woven forced-live warning history is incomplete" {})))
        (when-not (every? #(= :chdb-durable-scenario (:context-id %))
                          (filter #(= :invoke (:phase %)) events))
          (throw (ex-info "woven Durable history lost context" {})))
        (doseq [secret ["old-private-owner" "old-private-instance"
                        "private-owner" "private-instance" "SELECT 1"
                        "wal/" "checkpoints/"]]
          (when (.contains printed secret)
            (throw (ex-info "woven Durable history retained private data"
                            {:secret-class :durable-private-data}))))
        (history/assert-complete! journal)))
    (println (if plain?
               "chDB Durable plain scenario remained uninstrumented"
               "chDB Durable model-checkable aspect scenario ran"))))
