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

(defn -main [& args]
  (let [plain? (= ["plain"] (vec args))]
    (doseq [backend [:thread :fiber]]
      (run-backend! backend plain?))
    (println (if plain?
               "CORE-ASYNC plain scenario remained uninstrumented"
               "CORE-ASYNC thread/fiber history scenario ran"))))
