(ns jolt.aspect-packs.scenario.core-async
  (:require [clojure.core.async :as async]
            [jolt.aspect-packs.core-async.faults :as faults]
            [jolt.aspect-packs.core-async.model :as model]
            [jolt.aspect-packs.history :as history]))

;; These are the same five public, revision-pinned call sites as the observation
;; scenario. This isolated build opts into control advice explicitly.
(defn observed-offer [channel value]
  (async/offer! channel value))

(defn observed-poll [channel]
  (async/poll! channel))

(defn observed-close [channel]
  (async/close! channel))

(defn observed-put [channel value callback on-caller?]
  (async/put! channel value callback on-caller?))

(defn observed-take [channel callback on-caller?]
  (async/take! channel callback on-caller?))

(defn- check! [truth message data]
  (when-not truth (throw (ex-info message data))))

(defn- fault! [action decisions f]
  (faults/call-with-fault action decisions f))

(defn -main [& _]
  (let [journal (history/journal)
        decisions (atom [])
        before-error (ex-info "injected before" {:kind :injected-before})
        after-error (ex-info "injected after" {:kind :injected-after})]
    (binding [history/*journal* journal
              history/*context-id* :core-async-fault-scenario]
      (let [channel (async/chan 2)]
        (check! (true? (fault! {:effect :return-before :value true}
                               decisions
                               #(observed-offer channel :not-committed)))
                "forced pre-return changed its value" {})
        (check! (nil? (observed-poll channel))
                "forced pre-return unexpectedly ran the target" {})
        (check! (identical?
                 before-error
                 (try
                   (fault! {:effect :throw-before :error before-error}
                           decisions #(observed-offer channel :also-skipped))
                   nil
                   (catch Throwable error error)))
                "forced pre-throw lost Throwable identity" {})
        (check! (nil? (observed-poll channel))
                "forced pre-throw unexpectedly ran the target" {})
        (check! (identical?
                 after-error
                 (try
                   (fault! {:effect :throw-after :error after-error}
                           decisions #(observed-offer channel :committed))
                   nil
                   (catch Throwable error error)))
                "forced post-throw lost Throwable identity" {})
        (check! (= :committed (observed-poll channel))
                "post-throw target side effect was lost" {})
        (check! (true?
                 (fault! {:effect :replace-args
                          :args [channel :replacement]}
                         decisions #(observed-offer channel :original)))
                "replacement offer failed" {})
        (check! (= :replacement (observed-poll channel))
                "replacement arguments did not reach the target" {})

        (let [arrived (promise)
              release (promise)
              worker
              (future
                (binding [history/*journal* journal
                          history/*context-id* :core-async-fault-scenario]
                  (fault! {:effect :barrier-before
                           :arrived arrived :release release
                           :timeout-ms 3000}
                          decisions #(observed-offer channel :held))))]
          (check! (true? (deref arrived 2000 false))
                  "barrier did not report arrival" {})
          (check! (true? (observed-offer channel :overtake))
                  "overtaking offer failed" {})
          (deliver release true)
          (check! (true? (deref worker 3000 false))
                  "barrier owner did not complete" {})
          (check! (= [:overtake :held]
                     [(observed-poll channel) (observed-poll channel)])
                  "bounded barrier did not reorder target arrival" {}))

        (let [callback-count (atom 0)
              callback (fn [_] (swap! callback-count inc))]
          (check! (true?
                   (fault! {:effect :callback-suppress} decisions
                           #(observed-put channel :suppressed callback true)))
                  "suppressed put failed registration" {})
          (check! (zero? @callback-count)
                  "suppressed application callback ran" {})
          (check! (= :suppressed (observed-poll channel))
                  "suppression altered target commitment" {})

          (check! (true?
                   (fault! {:effect :callback-duplicate} decisions
                           #(observed-put channel :duplicated callback true)))
                  "duplicated put failed registration" {})
          (check! (= 2 @callback-count)
                  "duplicate wrapper did not invoke application callback twice"
                  {:count @callback-count})
          (check! (= :duplicated (observed-poll channel))
                  "duplication altered target commitment" {})

          (let [slot (atom nil)]
            (check! (true?
                     (fault! {:effect :callback-defer :slot slot} decisions
                             #(observed-put channel :deferred callback true)))
                    "deferred put failed registration" {})
            (check! (= 2 @callback-count)
                    "deferred callback ran before release" {})
            (check! (ifn? @slot) "deferred callback was not captured" {})
            (@slot)
            (check! (= 3 @callback-count)
                    "deferred callback did not run on release" {})
            (check! (= :deferred (observed-poll channel))
                    "defer altered target commitment" {})))

        ;; Ensure the selected take! and close! public seams also execute in the
        ;; dual-consumer build.
        (let [taken (promise)]
          (check! (true? (observed-offer channel :take-value))
                  "observed take setup offer failed" {})
          (check! (nil? (observed-take channel #(deliver taken %) true))
                  "observed take returned the wrong registration value" {})
          (check! (= :take-value (deref taken 2000 ::timed-out))
                  "observed take callback did not complete" {}))
        (check! (nil? (observed-close channel))
                "observed close returned the wrong value" {})))

    (history/assert-complete! journal)
    (let [events (history/events journal)
          callback-events
          (filterv
           (fn [event]
             (or (contains? #{:core-async/put :core-async/take
                              :core-async/put-target :core-async/take-target}
                            (:operation event))
                 (let [id (:operation-id event)
                       invoke (first (filter #(and (= id (:operation-id %))
                                                   (= :invoke (:phase %)))
                                             events))]
                   (contains? #{:core-async/put :core-async/take
                                :core-async/put-target :core-async/take-target}
                              (:operation invoke)))))
           events)
          trace (model/callback-trace
                 (mapv (fn [index event] (assoc event :seq (inc index)))
                       (range) callback-events))]
      (check! (= 4 (count (:logical-operations trace)))
              "callback history missed a logical lifecycle" {})
      (check! (= 4 (count (:target-operations trace)))
              "callback history missed a target child" {})
      (check! (= #{:return-before :throw-before :throw-after :replace-args
                   :barrier-before :callback-suppress :callback-duplicate
                   :callback-defer}
                 (set (map :effect @decisions)))
              "fault decision log missed an injected effect"
              {:decisions @decisions})
      (check! (not (.contains (pr-str @decisions) "not-committed"))
              "fault decisions retained application values" {}))
    (println "CORE-ASYNC CONTROL fault scenario ran")))
