(ns jolt.aspect-packs.chdb-durable.faults
  "Test-only operation-scoped crash barriers for Durable control commands."
  (:import [java.nio.file Files OpenOption Paths StandardOpenOption]))

(def ^:dynamic *action* nil)

(defn call-with-fault [action f]
  (binding [*action* action]
    (f)))

(defn- invalid! [message]
  (throw (ex-info message {:kind :chdb-durable/invalid-fault})))

(defn- selected? [join-point phase]
  (and (= (:operation *action*) (:id join-point))
       (= (:phase *action*) phase)))

(defn- crash-barrier! []
  (let [ready-file (:ready-file *action*)]
    (when-not (and (string? ready-file) (not (empty? ready-file)))
      (invalid! "Durable crash barrier requires a ready file"))
    (Files/write (Paths/get ready-file (into-array String []))
                 (.getBytes "ready\n" "UTF-8")
                 (into-array OpenOption [StandardOpenOption/CREATE_NEW
                                         StandardOpenOption/WRITE]))
    ;; The harness sends SIGKILL after observing the marker. A timeout makes a
    ;; broken harness fail eventually instead of leaving an immortal process.
    (Thread/sleep 60000)
    (invalid! "Durable crash barrier was not killed")))

(defn around-control [join-point _args proceed]
  (when (selected? join-point :before)
    (crash-barrier!))
  (let [result (proceed)]
    (when (selected? join-point :after)
      (crash-barrier!))
    result))

(def aspect-provider
  {:schema 1
   :libraries
   {'io.github.chucklehead-dev/jolt-chdb
    "edc86af07d5982a185c4ef953c19c848a719da0e"}
   :roles
   {:durable/control
    {:fn 'jolt.aspect-packs.chdb-durable.faults/around-control
     :contract :control-v1}}})
