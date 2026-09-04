(ns jolt.aspect-packs.scenario.core-async-flow
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [jolt.aspect-packs.history :as history]))

;; Lifecycle wrappers are deliberately separate fixed-arity functions. They are
;; application-owned seams around the public flow API, not patches to upstream's
;; scheduler implementation.
(defn ^{:jolt.aspects/id :core-async-flow/create
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-create [config]
  (flow/create-flow config))

(defn ^{:jolt.aspects/id :core-async-flow/start
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-start [graph]
  (flow/start graph))

(defn ^{:jolt.aspects/id :core-async-flow/pause
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-pause [graph]
  (flow/pause graph))

(defn ^{:jolt.aspects/id :core-async-flow/resume
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-resume [graph]
  (flow/resume graph))

(defn ^{:jolt.aspects/id :core-async-flow/ping
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-ping [graph timeout-ms]
  (flow/ping graph :timeout-ms timeout-ms))

(defn ^{:jolt.aspects/id :core-async-flow/inject
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-inject [graph coord messages]
  (flow/inject graph coord messages))

(defn ^{:jolt.aspects/id :core-async-flow/stop
        :jolt.aspects/role :concurrency/flow-lifecycle}
  observed-stop [graph]
  (flow/stop graph))

;; map->step keeps each process phase independently selectable. Annotating the
;; four-arity step fn itself would require choosing one arity and would obscure
;; which lifecycle phase was observed or faulted.
(defn ^{:jolt.aspects/id :core-async-flow/describe
        :jolt.aspects/role :concurrency/flow-step}
  worker-describe []
  {:params {:seen "test-owned observation atom"}
   :ins {:in "messages injected by the scenario"}
   :workload :io
   :ping-map-fn #(select-keys % [:count :status])})

(defn ^{:jolt.aspects/id :core-async-flow/init
        :jolt.aspects/role :concurrency/flow-step}
  worker-init [{:keys [seen]}]
  {:seen seen :count 0 :status :initialized})

(defn ^{:jolt.aspects/id :core-async-flow/transition
        :jolt.aspects/role :concurrency/flow-step}
  worker-transition [state transition]
  (swap! (:seen state) conj [:transition transition])
  (assoc state :status transition))

(defn ^{:jolt.aspects/id :core-async-flow/transform
        :jolt.aspects/role :concurrency/flow-step}
  worker-transform [state input message]
  (swap! (:seen state) conj [:message input message])
  [(update state :count inc)
   {::flow/report [{:input input :message message}]}])

(def worker-step
  (flow/map->step {:describe worker-describe
                   :init worker-init
                   :transition worker-transition
                   :transform worker-transform}))

(defn- await! [value description]
  (let [result (deref value 5000 ::timed-out)]
    (when (= ::timed-out result)
      (throw (ex-info (str description " timed out")
                      {:kind :core-async-flow/scenario-timeout})))
    result))

(defn- take-with-timeout! [channel description]
  (let [timeout-channel (async/timeout 5000)
        [value selected] (async/alts!! [channel timeout-channel]
                                       :priority true)]
    (when (identical? selected timeout-channel)
      (throw (ex-info (str description " timed out")
                      {:kind :core-async-flow/scenario-timeout})))
    value))

(defn- await-history-complete! [journal]
  ;; stop may return while a process callback that was already dispatched is
  ;; finishing its terminal history event. Give that bounded in-flight work a
  ;; chance to close; never turn teardown into an unbounded demo hang.
  (loop [remaining 500]
    (let [open-ids (history/open-operation-ids journal)]
      (cond
        (empty? open-ids) true
        (zero? remaining)
        (throw (ex-info "flow history did not quiesce after stop"
                        {:kind :core-async-flow/history-timeout
                         :operation-ids open-ids}))
        :else
        (do
          (async/<!! (async/timeout 10))
          (recur (dec remaining)))))))

(defn- await-stop-transition! [seen]
  ;; flow/stop enqueues the process transition and closes the public channels;
  ;; its true return does not mean the process callback has run yet.
  (loop [remaining 500]
    (cond
      (some #(= [:transition ::flow/stop] %) @seen) true
      (zero? remaining)
      (throw (ex-info "flow process did not observe stop"
                      {:kind :core-async-flow/stop-timeout}))
      :else
      (do
        (async/<!! (async/timeout 10))
        (recur (dec remaining))))))

(defn run-scenario!
  [{:keys [plain? expected-ping-count]
    :or {plain? false expected-ping-count 2}}]
  (let [journal (history/journal)
        seen (atom [])]
    (binding [history/*journal* journal
              history/*context-id* :core-async-flow-scenario]
      (let [graph (observed-create
                   {:procs {:worker {:proc (flow/process #'worker-step)
                                     :args {:seen seen}}}
                    :conns []})
            {:keys [report-chan error-chan]} (observed-start graph)]
        (observed-resume graph)
        (await! (observed-inject graph [:worker :in]
                                 [:private-one :private-two])
                "flow injection")
        (let [reports [(take-with-timeout! report-chan "first flow report")
                       (take-with-timeout! report-chan "second flow report")]
              ping (observed-ping graph 2000)]
          (when-not (= #{:private-one :private-two}
                       (set (map :message reports)))
            (throw (ex-info "flow reports lost or changed messages"
                            {:reports reports})))
          (when-not (= expected-ping-count
                       (get-in ping [:worker ::flow/count]))
            (throw (ex-info "flow ping reported the wrong process count"
                            {:expected expected-ping-count :ping ping}))))
        (observed-pause graph)
        (observed-resume graph)
        (when-not (true? (observed-stop graph))
          (throw (ex-info "flow did not stop" {})))
        (await-stop-transition! seen)
        (when-some [error (async/poll! error-chan)]
          (throw (ex-info "flow scenario reported an unexpected error"
                          {:error-type (str (type (::flow/ex error)))})))
        (await-history-complete! journal)))
    (let [events (history/events journal)]
      (if plain?
        (when (seq events)
          (throw (ex-info "plain flow scenario ran aspect advice"
                          {:event-count (count events)})))
        (do
          (history/assert-complete! journal)
          (let [operations (set (map :operation events))]
            (when-not (every? operations
                              [:core-async-flow/create
                               :core-async-flow/start
                               :core-async-flow/resume
                               :core-async-flow/inject
                               :core-async-flow/ping
                               :core-async-flow/pause
                               :core-async-flow/stop
                               :core-async-flow/describe
                               :core-async-flow/init
                               :core-async-flow/transition
                               :core-async-flow/transform])
              (throw (ex-info "woven flow history missed an operation"
                              {:operations operations}))))
          (when (.contains (pr-str events) "private-")
            (throw (ex-info "woven flow history retained message values" {})))))
      {:events (count events)
       :seen-count (count @seen)
       :plain? plain?})))

(defn -main [& args]
  (println (pr-str (run-scenario!
                    {:plain? (= "plain" (first args))}))))
