(ns jolt.aspect-packs.chdb-durable.faults
  "Test-only operation-scoped crash barriers for Durable control commands."
  (:import [java.nio.file Files OpenOption Paths StandardOpenOption]))

(def ^:dynamic *action* nil)

(defn- invalid! [message]
  (throw (ex-info message {:kind :chdb-durable/invalid-fault})))

(defn- validate-effect! [action]
  (case (:effect action)
    (nil :crash)
    (when-not (and (string? (:ready-file action))
                   (not (empty? (:ready-file action))))
      (invalid! "Durable crash barrier requires a ready file"))

    :throw
    (when-not (instance? Throwable (:error action))
      (invalid! "Durable throw fault requires a Throwable"))

    (invalid! "Unsupported Durable fault effect")))

(defn call-with-fault
  "Run `f` with one operation-scoped test fault.

  `:hit` selects the matching operation invocation (default 1). `:effect`
  selects a process crash barrier (default `:crash`) or a typed `:throw` before
  or after the target. An after-throw is useful for modeling a completed CAS
  whose caller-visible outcome became ambiguous."
  [action f]
  (when-not (and (map? action)
                 (keyword? (:operation action))
                 (contains? #{:before :after} (:phase action))
                 (or (nil? (:hit action))
                     (and (integer? (:hit action)) (pos? (:hit action))))
                 (contains? #{nil :crash :throw} (:effect action)))
    (invalid! "Durable fault action is invalid"))
  (validate-effect! action)
  (binding [*action* (assoc action :counts (atom {}))]
    (f)))

(defn- selected? [join-point phase occurrence]
  (and (= (:operation *action*) (:id join-point))
       (= (:phase *action*) phase)
       (= (or (:hit *action*) 1) occurrence)))

(defn- crash-barrier! []
  (let [ready-file (:ready-file *action*)]
    (Files/write (Paths/get ready-file (into-array String []))
                 (.getBytes "ready\n" "UTF-8")
                 (into-array OpenOption [StandardOpenOption/CREATE_NEW
                                         StandardOpenOption/WRITE]))
    ;; The harness sends SIGKILL after observing the marker. A timeout makes a
    ;; broken harness fail eventually instead of leaving an immortal process.
    (Thread/sleep 60000)
    (invalid! "Durable crash barrier was not killed")))

(defn- trigger! []
  (case (:effect *action*)
    (nil :crash) (crash-barrier!)
    :throw (throw (:error *action*))
    (invalid! "Unsupported Durable fault effect")))

(defn around-control [join-point _args proceed]
  (if-not (= (:operation *action*) (:id join-point))
    (proceed)
    (let [occurrence (get (swap! (:counts *action*) update (:id join-point)
                                 (fnil inc 0))
                          (:id join-point))]
      (when (selected? join-point :before occurrence)
        (trigger!))
      (let [result (proceed)]
        (when (selected? join-point :after occurrence)
          (trigger!))
        result))))

(def aspect-provider
  {:schema 1
   :libraries
   {'io.github.chucklehead-dev/jolt-chdb
    "4a0b82119a09fdadb08442cb5d189bdc0474ed86"}
   :roles
   {:durable/control
    {:fn 'jolt.aspect-packs.chdb-durable.faults/around-control
     :contract :control-v1}}})
