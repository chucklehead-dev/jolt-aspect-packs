(ns jolt.aspect-packs.scenario.core-async
  (:require [clojure.core.async :as async]
            [jolt.fibers :as fibers]
            [jolt.aspect-packs.core-async.model :as model]
            [jolt.aspect-packs.history :as history]))

;; Each public operation has one statically selectable source site. The runtime
;; scenario invokes those sites repeatedly to produce a nontrivial history.
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

(defn- run-action! [channel backend [operation value]]
  (case backend
    :thread (Thread/yield)
    :fiber (fibers/yield))
  (case operation
    :offer (observed-offer channel value)
    :poll (observed-poll channel)
    :close (observed-close channel)))

(defn- run-backend! [backend plain?]
  (let [journal (history/journal)
        channel (async/chan 2)
        start (promise)
        actors [[[:offer (Object.)] [:poll nil] [:close nil] [:poll nil]]
                [[:offer (Object.)] [:offer :private-full]
                 [:poll nil] [:offer :private-late]]]
        body (fn [actions]
               @start
               (doseq [action actions]
                 (run-action! channel backend action))
               :done)
        workers
        (binding [history/*journal* journal
                  history/*context-id* (keyword "core-async-scenario"
                                               (name backend))]
          (mapv (fn [actions]
                  (case backend
                    :thread (future (body actions))
                    :fiber (fibers/spawn #(body actions))))
                actors))]
    (deliver start true)
    (doseq [worker workers]
      (when-not (= :done
                   (case backend
                     :thread (deref worker 5000 ::timed-out)
                     :fiber (fibers/join worker 5000 ::timed-out)))
        (throw (ex-info "core.async scenario worker did not complete"
                        {:backend backend}))))
    (let [events (history/events journal)]
      (if plain?
        (when (seq events)
          (throw (ex-info "plain core.async build ran aspect advice"
                          {:backend backend :events events})))
        (do
          (history/assert-complete! journal)
          (let [channel-token (get-in events [0 :input :channel])]
            (model/check! (model/initial-state {channel-token 2}) events))
          (when-not (= #{:core-async/offer :core-async/poll :core-async/close}
                       (set (map :operation
                                 (filter #(= :invoke (:phase %)) events))))
            (throw (ex-info "woven core.async history missed an operation"
                            {:backend backend :events events})))
          (when (.contains (pr-str events) "private")
            (throw (ex-info "woven core.async history retained source values"
                            {:backend backend :events events})))))
      events)))

(defn- await! [completion]
  (let [value (deref completion 5000 ::timed-out)]
    (when (= ::timed-out value)
      (throw (ex-info "core.async scenario callback did not complete" {})))
    value))

(defn- run-callbacks! [backend plain?]
  (let [journal (history/journal)
        observations (atom [])
        run
        (fn []
          (binding [history/*journal* journal
                    history/*context-id*
                    (keyword "core-async-callback-scenario" (name backend))]
            (let [channel (async/chan 1)
                  caller (Thread/currentThread)
                  callback-count (atom 0)
                  callback
                  (fn [completion expected-operation expected-value
                       expected-caller?]
                    (fn [value]
                      (swap! callback-count inc)
                      (let [observation
                            {:operation expected-operation
                             :value value
                             :expected-value expected-value
                             :caller? (identical? caller
                                                  (Thread/currentThread))
                             :expected-caller? expected-caller?
                             :parent history/*parent-operation-id*
                             :context history/*context-id*}]
                        (swap! observations conj observation)
                        (deliver completion observation))))
                  immediate-put (promise)
                  immediate-take (promise)
                  pending-take (promise)
                  pending-put (promise)]
              (when-not (true? (observed-put
                                channel :immediate-put
                                (callback immediate-put :core-async/put
                                          true true) true))
                (throw (ex-info "immediate put! returned the wrong result" {})))
              (await! immediate-put)
              (when-not (nil? (observed-take
                               channel
                               (callback immediate-take :core-async/take
                                         :immediate-put false)
                               false))
                (throw (ex-info "immediate take! returned the wrong result" {})))
              (await! immediate-take)
              (when-not (nil? (observed-take
                               channel
                               (callback pending-take :core-async/take
                                         :pending-take nil)
                               true))
                (throw (ex-info "pending take! returned the wrong result" {})))
              (async/>!! channel :pending-take)
              (await! pending-take)
              (async/>!! channel :occupied)
              (when-not (true? (observed-put
                                channel :pending-put
                                (callback pending-put :core-async/put
                                          true false) false))
                (throw (ex-info "pending put! returned the wrong result" {})))
              (when-not (= :occupied (async/<!! channel))
                (throw (ex-info "pending put! setup lost its buffered value" {})))
              (await! pending-put)
              (doseq [completion [immediate-put immediate-take
                                  pending-take pending-put]]
                (let [{:keys [value expected-value caller? expected-caller?
                              parent context]
                       :as observation}
                      (await! completion)]
                  (when-not (and (= expected-value value)
                                 (or (nil? expected-caller?)
                                     (= expected-caller? caller?))
                                 (or plain?
                                     (and (integer? parent)
                                          (= (keyword
                                              "core-async-callback-scenario"
                                              (name backend))
                                             context))))
                    (throw (ex-info "callback placement or value drifted"
                                    {:backend backend
                                     :observation observation})))))
              (when-not (= 4 @callback-count)
                (throw (ex-info "core.async scenario lost or duplicated callbacks"
                                {:backend backend :count @callback-count})))
              (when-not (= :pending-put (async/<!! channel))
                (throw (ex-info "pending put! did not publish its value" {})))
              ;; Nil is an explicit no-op callback in the pinned Jolt target.
              ;; Woven and plain builds must preserve that API while the woven
              ;; recorder still closes both lifecycles internally.
              (when-not (true? (observed-put
                                channel :nil-callback-put nil true))
                (throw (ex-info "nil-callback put! drifted" {})))
              (when-not (nil? (observed-take channel nil true))
                (throw (ex-info "nil-callback take! drifted" {})))
              :done)))
        worker (case backend
                 :thread (future (run))
                 :fiber (fibers/spawn run))]
    (when-not (= :done
                 (case backend
                   :thread (deref worker 5000 ::timed-out)
                   :fiber (fibers/join worker 5000 ::timed-out)))
      (throw (ex-info "core.async callback scenario worker did not complete"
                      {:backend backend})))
    (let [events (history/events journal)]
      (if plain?
        (when (seq events)
          (throw (ex-info "plain callback build ran aspect advice"
                          {:backend backend :events events})))
        (do
          (history/assert-complete! journal)
          (let [trace (model/callback-trace events)
                logical (:logical-operations trace)
                targets (:target-operations trace)
                logical-by-id (into {} (map (juxt :operation-id identity))
                                    logical)]
            ;; The smoke deliberately uses blocking, unobserved counterparts to
            ;; complete two pending callbacks, so it is a partial history rather
            ;; than an input to the closed-world linearizability model. The
            ;; generated provider tests exercise that model with complete traces.
            (when-not (= #{:core-async/put :core-async/take}
                         (set (map :operation logical)))
              (throw (ex-info "woven callback history missed an operation"
                              {:backend backend :events events})))
            (when-not (and (= 6 (count logical))
                           (= 6 (count targets))
                           (= 24 (count events)))
              (throw (ex-info "woven callback history has wrong lifecycle count"
                              {:backend backend :events events})))
            (doseq [{:keys [operation parent]} @observations]
                (when-not (= operation (:operation (get logical-by-id parent)))
                  (throw (ex-info "callback carrier names the wrong logical operation"
                                  {:backend backend
                                   :operation operation :parent parent})))))
          (let [rendered (pr-str events)]
            (when (some #(.contains rendered %)
                        [":immediate-put" ":pending-take"
                         ":occupied" ":pending-put"
                         ":nil-callback-put"])
              (throw (ex-info "woven callback history retained source values"
                              {:backend backend :events events}))))
          (doseq [operation (:logical-operations (model/callback-trace events))]
            (when-not (= {:parent-operation-id (:operation-id operation)
                          :context-id (get-in operation [:invoke :context-id])}
                         (get-in operation [:terminal :value :carrier]))
              (throw (ex-info "callback lifecycle was not exactly once"
                              {:backend backend :operation operation}))))))
      events)))

(defn -main [& args]
  (let [plain? (= ["plain"] (vec args))]
    (doseq [backend [:thread :fiber]]
      (run-backend! backend plain?)
      (run-callbacks! backend plain?))
    (println (if plain?
               "CORE-ASYNC plain callback scenario remained uninstrumented"
               "CORE-ASYNC thread/fiber operation and callback histories ran"))))
