(ns jolt.aspect-packs.core-async.provider-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [hegel.clojure-test :as hegel-test]
            [hegel.generator :as g]
            [jolt.fibers :as fibers]
            [jolt.aspect-packs.core-async.model :as model]
            [jolt.aspect-packs.core-async.provider :as provider]
            [jolt.aspect-packs.history :as history]))

(defn- join-point [id]
  {:id id
   :site-id (str "core-async-provider-test/" (name id))
   :build-identity "core-async-provider-test-build"})

(defn- invoke! [journal id args f]
  (binding [history/*journal* journal]
    (provider/around-operation (join-point id) args f)))

(defn- invoke-callback! [journal id args f]
  (binding [history/*journal* journal
            history/*context-id* :core-async-callback-test]
    (provider/around-callback-operation (join-point id) args f)))

(defn- core-async-proceed [id]
  (case id
    :core-async/put
    (fn [[channel value callback on-caller?]]
      (async/put! channel value callback on-caller?))
    :core-async/take
    (fn [[channel callback on-caller?]]
      (async/take! channel callback on-caller?))))

(defn- await-callback!
  ([completion] (await-callback! completion {}))
  ([completion context]
   (let [result (deref completion 5000 ::timed-out)]
     (when (= ::timed-out result)
       (throw (ex-info "core.async callback did not complete"
                       (assoc context
                              :hegel/origin
                              "core-async/callback-completion"))))
     result)))

(defn- run-initiator! [backend f]
  (let [worker (case backend
                 :thread (future (f))
                 :fiber (fibers/spawn f))
        result (case backend
                 :thread (deref worker 7000 ::timed-out)
                 :fiber (fibers/join worker 7000 ::timed-out))]
    (when (= ::timed-out result)
      (throw (ex-info "core.async initiator did not complete"
                      {:backend backend
                       :hegel/origin "core-async/initiator-completion"})))
    result))

(defn- callback! [journal operation channel value on-caller?
                  callback-count completions]
  (let [completion (promise)
        callback (fn [result]
                   (swap! callback-count inc)
                   (deliver completion {:operation operation :result result}))
        args (case operation
               :put [channel value callback on-caller?]
               :take [channel callback on-caller?])]
    (swap! completions conj completion)
    (invoke-callback! journal
                      (case operation
                        :put :core-async/put
                        :take :core-async/take)
                      args
                      (core-async-proceed
                       (case operation
                         :put :core-async/put
                         :take :core-async/take)))))

(defn- callback-model-check! [journal capacity]
  (history/assert-complete! journal)
  (let [events (history/events journal)
        channel-token (get-in events [0 :input :channel])]
    (model/check-callback!
     (model/callback-initial-state {channel-token capacity}) events)
    events))

(defn- callback-trace [events]
  (model/callback-trace events))

(defn- operation-terminal [operations operation]
  (:terminal (first (filter #(= operation (:operation %)) operations))))

(defn- run-action! [journal channel backend [operation value]]
  (case backend
    :thread (Thread/yield)
    :fiber (fibers/yield))
  (case operation
    :offer (invoke! journal :core-async/offer [channel value]
                    #(async/offer! channel value))
    :poll (invoke! journal :core-async/poll [channel]
                   #(async/poll! channel))
    :close (invoke! journal :core-async/close [channel]
                    #(async/close! channel))))

(defn- run-concurrent-history! [backend actors]
  (let [journal (history/journal)
        channel (async/chan 2)
        start (promise)
        body (fn [actions]
               @start
               (doseq [action actions]
                 (run-action! journal channel backend action))
               :done)
        workers (mapv (fn [actions]
                        (case backend
                          :thread (future (body actions))
                          :fiber (fibers/spawn #(body actions))))
                      actors)]
    (deliver start true)
    (doseq [worker workers]
      (is (= :done
             (case backend
               :thread (deref worker 5000 ::timed-out)
               :fiber (fibers/join worker 5000 ::timed-out)))))
    (history/assert-complete! journal)
    (let [events (history/events journal)
          channel-token (get-in events [0 :input :channel])]
      (model/check! (model/initial-state {channel-token 2}) events)
      events)))

(deftest captures-a-privacy-safe-fixed-buffer-history
  (let [journal (history/journal)
        channel (async/chan 2)
        first-value (Object.)
        second-value (Object.)]
    (is (true? (invoke! journal :core-async/offer [channel first-value]
                        #(async/offer! channel first-value))))
    (is (true? (invoke! journal :core-async/offer [channel second-value]
                        #(async/offer! channel second-value))))
    (is (nil? (invoke! journal :core-async/offer [channel :private-full]
                       #(async/offer! channel :private-full))))
    (is (identical? first-value
                    (invoke! journal :core-async/poll [channel]
                             #(async/poll! channel))))
    (is (nil? (invoke! journal :core-async/close [channel]
                       #(async/close! channel))))
    (is (false? (invoke! journal :core-async/offer [channel :private-closed]
                         #(async/offer! channel :private-closed))))
    (is (identical? second-value
                    (invoke! journal :core-async/poll [channel]
                             #(async/poll! channel))))
    (is (nil? (invoke! journal :core-async/poll [channel]
                       #(async/poll! channel))))
    (let [events (history/events journal)]
      (is (true? (history/assert-complete! journal)))
      (is (= 16 (count events)))
      (let [channel-token (get-in events [0 :input :channel])]
        (is (string? channel-token))
        (is (some? (re-matches #"opaque-[0-9]+" channel-token)))
        (is (not (identical? channel channel-token)))
        (is (every? #(= channel-token (get-in % [:input :channel]))
                    (filter #(= :invoke (:phase %)) events)))
        (is (some? (model/check!
                    (model/initial-state {channel-token 2}) events))))
      (is (not (.contains (pr-str events) (pr-str channel))))
      (doseq [private-value [first-value second-value :private-full
                             :private-closed]]
        (is (not (.contains (pr-str events) (pr-str private-value))))))))

(deftest absent-journal-is-fully-inert
  (let [called (atom 0)
        result (Object.)]
    (is (identical?
         result
         (provider/around-operation
          {:id :not-even-a-known-operation} [:uninspected]
          (fn [] (swap! called inc) result))))
    (is (= 1 @called))))

(deftest absent-journal-callback-advice-is-fully-inert
  (let [called (atom 0)
        result (Object.)]
    (is (identical?
         result
         (provider/around-callback-operation
          {:id :not-even-a-known-operation} [:uninspected]
          (fn [] (swap! called inc) result))))
    (is (= 1 @called))))

(deftest malformed-bound-invocations-fail-before-the-operation
  (doseq [[id args]
          [[:core-async/offer [:only-channel]]
           [:core-async/poll []]
           [:core-async/close [:channel :extra]]
           [:core-async/unknown [:channel]]]]
    (testing id
      (let [journal (history/journal)
            called? (atom false)]
        (is (thrown? Exception
                     (invoke! journal id args #(reset! called? true))))
        (is (false? @called?))
        (is (empty? (history/events journal)))))))

(deftest callback-advice-replaces-only-the-callback-and-preserves-duplicates
  (let [journal (history/journal)
        channel (Object.)
        value (Object.)
        result (Object.)
        callback-result (Object.)
        calls (atom [])
        callback (fn [argument]
                   (swap! calls conj
                          {:argument argument
                           :journal? (identical? journal history/*journal*)
                           :parent history/*parent-operation-id*
                           :context history/*context-id*})
                   callback-result)
        args [channel value callback true]
        observed
        (invoke-callback!
         journal :core-async/put args
         (fn [replacement]
           (is (= 4 (count replacement)))
           (is (identical? channel (nth replacement 0)))
           (is (identical? value (nth replacement 1)))
           (is (not (identical? callback (nth replacement 2))))
           (is (true? (nth replacement 3)))
           (is (identical? callback-result ((nth replacement 2) true)))
           (is (identical? callback-result ((nth replacement 2) true)))
           result))]
    (is (identical? result observed))
    (is (= 2 (count @calls)) "duplicate target delivery remains user-visible")
    (is (every? #(= {:argument true :journal? true :parent 0
                     :context :core-async-callback-test}
                    %)
                @calls))
    (is (= [:invoke :invoke :return :return]
           (mapv :phase (history/events journal))))
    (let [events (history/events journal)
          trace (callback-trace events)
          operations (:logical-operations trace)
          targets (:target-operations trace)
          channel-token (get-in events [0 :input :channel])
          value-token (get-in events [0 :input :value])]
      (is (= {:channel channel-token
              :value value-token
              :on-caller? true}
             (:input (first events))))
      (is (some? (re-matches #"opaque-[0-9]+" channel-token)))
      (is (some? (re-matches #"opaque-[0-9]+" value-token)))
      (is (not (identical? channel channel-token)))
      (is (not (identical? value value-token)))
      (is (= {:result :accepted
              :carrier {:parent-operation-id 0
                        :context-id :core-async-callback-test}}
             (:value (operation-terminal operations :core-async/put))))
      (is (= 1 (count targets)))
      (is (= :core-async/put-target (:operation (first targets))))
      (is (= 0 (get-in (first targets) [:invoke :parent-operation-id])))
      (is (= "core-async-provider-test/put/target"
             (get-in (first targets) [:invoke :site-id])))
      (is (= "core-async-provider-test-build"
             (get-in (first targets) [:invoke :build-identity])))
      (is (= {:result :target-returned} (:value (first targets))))
      (is (not-any? #(or (identical? channel %)
                         (identical? value %)
                         (identical? callback %)
                         (identical? result %))
                    (tree-seq coll? seq events))))
    (is (true? (history/assert-complete! journal)))))

(deftest nil-callback-retains-target-semantics-and-closes-history
  (doseq [operation [:core-async/put :core-async/take]]
    (let [journal (history/journal)
          channel (async/chan 1)
          value :nil-callback-value]
      (when (= operation :core-async/take)
        (async/offer! channel value))
      (is (= (if (= operation :core-async/put) true nil)
             (invoke-callback!
              journal operation
              (case operation
                :core-async/put [channel value nil true]
                :core-async/take [channel nil true])
              (core-async-proceed operation))))
      (is (= [:invoke :invoke :return :return]
             (mapv :phase (history/events journal))))
      (is (true? (history/assert-complete! journal)))
      (async/close! channel))))

(deftest pending-callback-outlives-advice-and-restores-its-carrier
  (let [journal (history/journal)
        callback-result (Object.)
        callback-observation (promise)
        callback (fn [value]
                   (deliver callback-observation
                            {:value value
                             :journal? (identical? journal history/*journal*)
                             :parent history/*parent-operation-id*
                             :context history/*context-id*})
                   callback-result)
        captured (atom nil)
        target-result (Object.)]
    (is (identical?
         target-result
         (invoke-callback!
          journal :core-async/take [:channel callback false]
          (fn [replacement]
            (reset! captured (nth replacement 1))
            target-result))))
    (is (= [0] (history/open-operation-ids journal)))
    (is (identical? callback-result (@captured :delivered)))
    (is (= {:value :delivered :journal? true :parent 0
            :context :core-async-callback-test}
           (deref callback-observation 1000 ::timed-out)))
    (is (true? (history/assert-complete! journal)))))

(deftest callback-and-target-throwables-retain-identity
  (let [callback-error (ex-info "callback" {:source :callback})
        callback-journal (history/journal)
        observed
        (try
          (invoke-callback!
           callback-journal :core-async/take
           [:channel (fn [_] (throw callback-error)) true]
           (fn [replacement] ((nth replacement 1) :value)))
          nil
          (catch Throwable error error))]
    (is (identical? callback-error observed))
    (is (= [:invoke :invoke :return :throw]
           (mapv :phase (history/events callback-journal))))
    (is (not-any? #(identical? callback-error %)
                  (tree-seq coll? seq (history/events callback-journal))))
    (is (true? (history/assert-complete! callback-journal))))
  (let [target-error (ex-info "target" {:source :target})
        target-journal (history/journal)
        observed
        (try
          (invoke-callback!
           target-journal :core-async/put
           [:channel :value identity true]
           (fn [_] (throw target-error)))
          nil
          (catch Throwable error error))]
    (is (identical? target-error observed))
    (is (= [:invoke :invoke :throw :throw]
           (mapv :phase (history/events target-journal))))
    (is (= {:result :target-threw
            :error-type (str (type target-error))}
           (get-in (history/events target-journal) [2 :value])))
    (is (not-any? #(identical? target-error %)
                  (tree-seq coll? seq (history/events target-journal))))
    (is (true? (history/assert-complete! target-journal)))))

(deftest malformed-callback-invocations-fail-before-the-operation
  (doseq [[id args]
          [[:core-async/put [:channel :value identity]]
           [:core-async/take [:channel identity]]
           [:core-async/unknown [:channel identity true]]]]
    (testing id
      (let [journal (history/journal)
            called? (atom false)]
        (is (thrown? Exception
                     (invoke-callback! journal id args
                                       (fn [_] (reset! called? true)))))
        (is (false? @called?))
        (is (empty? (history/events journal)))))))

(deftest immediate-and-pending-callback-placement-is-observable
  (doseq [operation [:core-async/put :core-async/take]
          pending? [false true]
          on-caller? [true false]]
    (testing [operation (if pending? :pending :immediate) on-caller?]
      (let [journal (history/journal)
            channel (async/chan 1)
            payload (Object.)
            caller (Thread/currentThread)
            callback-count (atom 0)
            completed (promise)
            callback
            (fn [value]
              (swap! callback-count inc)
              (deliver completed
                       {:value value
                        :thread (Thread/currentThread)
                        :journal? (identical? journal history/*journal*)
                        :parent history/*parent-operation-id*
                        :context history/*context-id*})
              :callback-return)
            _ (when (and (= :core-async/put operation) pending?)
                (async/offer! channel :occupied))
            _ (when (and (= :core-async/take operation) (not pending?))
                (async/offer! channel payload))
            args (case operation
                   :core-async/put [channel payload callback on-caller?]
                   :core-async/take [channel callback on-caller?])
            target-result (invoke-callback!
                           journal operation args
                           (core-async-proceed operation))]
        (when pending?
          (case operation
            :core-async/put (is (= :occupied (async/poll! channel)))
            :core-async/take (is (true? (async/offer! channel payload)))))
        (let [observation (deref completed 5000 ::timed-out)
              events (history/events journal)
              trace (callback-trace events)
              logical (first (:logical-operations trace))
              target (first (:target-operations trace))
              operation-id (:operation-id logical)
              logical-terminal (:terminal logical)]
          (is (not= ::timed-out observation))
          (is (= 1 @callback-count))
          (is (= (if (= :core-async/put operation) true nil)
                 target-result))
          (is (= (if (= :core-async/put operation) true payload)
                 (:value observation)))
          (is (true? (:journal? observation)))
          (is (= operation-id (:parent observation)))
          (is (= :core-async-callback-test (:context observation)))
          ;; core.async promises caller-thread delivery only when on-caller? is
          ;; true AND the operation completes immediately. Pending + true is
          ;; intentionally unconstrained: an implementation may use a dispatch
          ;; worker or run on the thread that completes the operation.
          (cond
            (and on-caller? (not pending?))
            (is (identical? caller (:thread observation)))

            (not on-caller?)
            (is (not (identical? caller (:thread observation)))))
          (is (= [:invoke :invoke :return :return]
                 (mapv :phase events)))
          (is (= 1 (count (:target-operations trace))))
          (is (= operation-id
                 (get-in target [:invoke :parent-operation-id])))
          (is (= {:result :target-returned} (:value target)))
          (is (< (:invoke-seq target) (:terminal-seq logical)))
          (when (and (not pending?) on-caller?)
            (is (< (:terminal-seq logical) (:terminal-seq target))))
          (is (= {:parent-operation-id operation-id
                  :context-id :core-async-callback-test}
                 (get-in logical-terminal [:value :carrier])))
          (is (= (if (= :core-async/put operation)
                   {:result :accepted}
                   {:result :value
                    :value (get-in logical-terminal [:value :value])})
                 (dissoc (:value logical-terminal) :carrier)))
          (when (= :core-async/take operation)
            (is (string? (get-in logical-terminal [:value :value])))
            (is (not (identical? payload
                                  (get-in logical-terminal [:value :value])))))
          (is (true? (history/assert-complete! journal))))
        (async/close! channel)))))

(deftest close-completes-takes-and-preserves-preexisting-puts
  (doseq [backend [:thread :fiber]
          capacity [0 1]
          operation [:put :take]]
    (testing [backend capacity operation]
      (let [journal (history/journal)
            channel (async/chan capacity)
            callback-count (atom 0)
            completions (atom [])]
        (run-initiator!
         backend
         (fn []
           ;; A capacity-one pending put needs one already accepted value.
           (when (and (= :put operation) (= 1 capacity))
             (callback! journal :put channel :buffered true
                        callback-count completions)
             (await-callback! (first @completions)))
           (let [target-result
                 (callback! journal operation channel :pending false
                            callback-count completions)]
             (when-not (= (if (= :put operation) true nil) target-result)
               (throw (ex-info "callback target returned the wrong result"
                               {:operation operation
                                :target-result target-result}))))
           (when (empty? (history/open-operation-ids journal))
             (throw (ex-info "callback operation unexpectedly completed"
                             {:backend backend :capacity capacity
                              :operation operation})))
           (invoke! journal :core-async/close [channel]
                    #(async/close! channel))
           (if (= :take operation)
             (await-callback! (last @completions)
                              {:backend backend :capacity capacity
                               :operation operation})
             (do
               ;; Official core.async close semantics retain the pending put.
               ;; A taker must release it; capacity one first drains the value
               ;; that made this put pending.
               (dotimes [_ (if (zero? capacity) 1 2)]
                 (callback! journal :take channel nil false
                            callback-count completions)
                 (await-callback! (last @completions)
                                  {:backend backend :capacity capacity
                                   :operation :cleanup-take}))
               (await-callback! (if (zero? capacity)
                                  (first @completions)
                                  (second @completions))
                                {:backend backend :capacity capacity
                                 :operation :pending-put})))
           :done))
        (let [observed (mapv #(-> (await-callback! %) :result)
                             @completions)
              expected (case [capacity operation]
                         [0 :put] [true :pending]
                         [1 :put] [true true :buffered :pending]
                         [0 :take] [nil]
                         [1 :take] [nil])]
          (is (= expected observed))
          (history/assert-complete! journal)
          (let [events (history/events journal)
                channel-token (get-in events [0 :input :channel])
                callback-terminals
                (filter #(and (= :return (:phase %))
                              (contains? (:value %) :carrier)) events)]
            (is (some? (model/callback-linearization
                        (model/callback-initial-state
                         {channel-token capacity}) events)))
            (is (= (count expected) @callback-count))
            (is (= @callback-count (count callback-terminals)))
            (is (= (+ (* 2 (case [capacity operation]
                               [0 :put] 3
                               [1 :put] 5
                               2))
                      (* 2 (count expected)))
                   (count events)))))))))

(deftest unbuffered-counterpart-after-return-completes-the-rendezvous
  (doseq [backend [:thread :fiber]
          first-operation [:put :take]]
    (testing [backend first-operation]
      (let [journal (history/journal)
            channel (async/chan)
            value (Object.)
            callback-count (atom 0)
            completions (atom [])]
        (run-initiator!
         backend
         (fn []
           (let [target-result
                 (callback! journal first-operation channel value false
                            callback-count completions)]
             (when-not (= (if (= :put first-operation) true nil)
                          target-result)
               (throw (ex-info "first callback target returned the wrong result"
                               {:operation first-operation
                                :target-result target-result}))))
           (when-not (= [0] (history/open-operation-ids journal))
             (throw (ex-info "first unbuffered operation did not remain pending"
                             {:backend backend :operation first-operation})))
           (let [counterpart (case first-operation :put :take :take :put)
                 target-result
                 (callback! journal counterpart channel value false
                            callback-count completions)]
             (when-not (= (if (= :put counterpart) true nil) target-result)
               (throw (ex-info "counterpart returned the wrong result"
                               {:operation counterpart
                                :target-result target-result}))))
           (doseq [completion @completions]
             (await-callback! completion))
           (invoke! journal :core-async/close [channel]
                    #(async/close! channel))
           :done))
        (let [events (callback-model-check! journal 0)
              trace (callback-trace events)
              operations (:logical-operations trace)
              targets (:target-operations trace)
              first-logical (first operations)
              first-target
              (first (filter #(= (:operation-id first-logical)
                                 (get-in % [:invoke :parent-operation-id]))
                             targets))
              counterpart-logical (second operations)]
          (is (= 2 @callback-count))
          (is (= 3 (count operations)))
          (is (= 2 (count targets)))
          (is (< (:terminal-seq first-target)
                 (:invoke-seq counterpart-logical)))
          (is (= #{:accepted :value :closed}
                 (set (map #(get-in % [:value :result]) operations))))
          (is (= 10 (count events))))))))

(defn- run-generated-callback-history! [backend capacity actors]
  (let [journal (history/journal)
        channel (async/chan capacity)
        start (promise)
        callback-count (atom 0)
        completions (atom [])
        body
        (fn [actions]
          @start
          (doseq [[operation value on-caller?] actions]
            (case operation
              :put (callback! journal :put channel value on-caller?
                              callback-count completions)
              :take (callback! journal :take channel nil on-caller?
                               callback-count completions)
              :close (invoke! journal :core-async/close [channel]
                              #(async/close! channel))))
          :done)
        workers
        (mapv (fn [actions]
                (case backend
                  :thread (future (body actions))
                  :fiber (fibers/spawn #(body actions))))
              actors)]
    (deliver start true)
    (doseq [worker workers]
      (let [result (case backend
                     :thread (deref worker 5000 ::timed-out)
                     :fiber (fibers/join worker 5000 ::timed-out))]
        (when-not (= :done result)
          (throw (ex-info "generated callback initiator did not complete"
                          {:backend backend :result result})))))
    ;; Close completes pending takes. Two bounded post-close takes release up to
    ;; two pre-close puts (one initiated by each actor), as required by the
    ;; official logical-close contract.
    (invoke! journal :core-async/close [channel] #(async/close! channel))
    (dotimes [_ 2]
      (callback! journal :take channel nil false callback-count completions)
      (await-callback! (last @completions)))
    (doseq [completion @completions]
      (await-callback! completion))
    (when-not (= (count @completions) @callback-count)
      (throw (ex-info "generated callback was lost or duplicated"
                      {:expected (count @completions)
                       :actual @callback-count})))
    (let [events (callback-model-check! journal capacity)
          trace (callback-trace events)]
      (when (> (count (:logical-operations trace)) 6)
        (throw (ex-info "generated callback history exceeded its bound" {})))
      events)))

(deftest generated-two-actor-callback-histories-are-linearizable
  (hegel-test/with
    {:name "core-async-callback-history-v1"
     :test-cases 60
     :database ""
     :derandomize? true
     :verbosity :quiet}
    [actors
     (g/vector
      {:size 2}
      (g/vector
       {:size 1}
       (g/tuple (g/sampled-from [:put :take :close])
                (g/integer 0 3)
                (g/boolean))))]
    (doseq [backend [:thread :fiber]
            capacity [0 1]]
      (let [events (run-generated-callback-history!
                    backend capacity actors)]
        (is (<= (count events) 18))
        (is (= (range 1 (inc (count events))) (map :seq events)))))))

(deftest generated-thread-and-fiber-histories-are-linearizable
  (hegel-test/with
    {:name "core-async-fixed-buffer-history-v1"
     :test-cases 100
     :database ""
     :derandomize? true
     :verbosity :quiet}
    [actors
     (g/vector
      {:min-size 2 :max-size 2}
      (g/vector
       {:min-size 1 :max-size 4}
       (g/tuple (g/sampled-from [:offer :poll :close])
                (g/integer 0 7))))]
    (doseq [backend [:thread :fiber]]
      (let [events (run-concurrent-history! backend actors)]
        (is (<= (count events) 16))
        (is (= (range 1 (inc (count events))) (map :seq events)))))))
