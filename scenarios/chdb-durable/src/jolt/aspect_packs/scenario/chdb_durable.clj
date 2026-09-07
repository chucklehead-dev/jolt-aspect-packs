(ns jolt.aspect-packs.scenario.chdb-durable
  (:require [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.control :as control]
            [jolt.aspect-packs.chdb-durable.model :as model]
            [jolt.aspect-packs.history :as history])
  (:import [java.nio.file Files OpenOption Path StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- exercise-control! []
  (let [store (backend/memory-backend)
        acquired (control/acquire!
                  store {:owner "private-owner" :instance "private-instance"
                         :expires-at 200M :now 100M :database "default"
                         :engine-version "scenario" :backup-format 1
                         :min-reader "scenario"})
        token (:token acquired)
        publication (control/publish-wal-bytes!
                     store token (.getBytes "SELECT 1\n" "UTF-8"))
        _ (control/commit-reference!
           store token {:kind :wal :reference (:reference publication)
                        :verify-reference! control/verify-byte-reference!})
        _ (control/renew! store token 300M)
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
                   (nil? (get-in result [:head "lease" "owner"])))
      (throw (ex-info "Durable scenario did not release its writer" {})))
    (if plain?
      (when (seq events)
        (throw (ex-info "plain Durable build ran aspect advice"
                        {:event-count (count events)})))
      (let [commands (model/commands events)
            printed (pr-str events)]
        (when-not (= [:acquire :publish :commit-attempt :renew-attempt
                      :checkpoint-publish :commit-attempt :release-attempt]
                     (mapv :command commands))
          (throw (ex-info "woven Durable command history is incomplete"
                          {:commands commands})))
        (when-not (every? #(= :chdb-durable-scenario (:context-id %))
                          (filter #(= :invoke (:phase %)) events))
          (throw (ex-info "woven Durable history lost context" {})))
        (doseq [secret ["private-owner" "private-instance" "SELECT 1"
                        "wal/" "checkpoints/"]]
          (when (.contains printed secret)
            (throw (ex-info "woven Durable history retained private data"
                            {:secret-class :durable-private-data}))))
        (history/assert-complete! journal)))
    (println (if plain?
               "chDB Durable plain scenario remained uninstrumented"
               "chDB Durable model-checkable aspect scenario ran"))))
