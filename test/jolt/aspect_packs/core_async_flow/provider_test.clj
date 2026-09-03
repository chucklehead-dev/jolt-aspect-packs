(ns jolt.aspect-packs.core-async-flow.provider-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.aspect-packs.core-async-flow.faults :as faults]
            [jolt.aspect-packs.core-async-flow.provider :as provider]
            [jolt.aspect-packs.history :as history]))

(defn- join-point [id]
  {:id id
   :site-id (str "core-async-flow-provider-test/" (name id))
   :build-identity "core-async-flow-provider-test-build"})

(defn- observe [journal id args proceed]
  (binding [history/*journal* journal
            history/*context-id* :core-async-flow-provider-test]
    ((if (#{:core-async-flow/create
            :core-async-flow/start
            :core-async-flow/pause
            :core-async-flow/resume
            :core-async-flow/ping
            :core-async-flow/inject
            :core-async-flow/stop} id)
       provider/around-lifecycle
       provider/around-step)
     (join-point id) args proceed)))

(deftest lifecycle-history-is-bounded-and-preserves-values
  (let [journal (history/journal)
        graph (Object.)
        completion (Object.)
        config {:procs {:source {} :sink {}}
                :conns [[[:source :out] [:sink :in]]]}
        created (observe journal :core-async-flow/create [config]
                         (constantly graph))]
    (is (identical? graph created))
    (is (= {:report-chan :report :error-chan :error}
           (observe journal :core-async-flow/start [graph]
                    (constantly {:report-chan :report :error-chan :error}))))
    (is (identical?
         completion
         (observe journal :core-async-flow/inject
                  [graph [:sink :in] [:private-a :private-b]]
                  (constantly completion))))
    (is (true? (observe journal :core-async-flow/stop [graph]
                        (constantly true))))
    (is (true? (history/assert-complete! journal)))
    (let [events (history/events journal)
          invokes (filterv #(= :invoke (:phase %)) events)
          returns (filterv #(= :return (:phase %)) events)]
      (is (= 8 (count events)))
      (is (= [:core-async-flow/create :core-async-flow/start
              :core-async-flow/inject :core-async-flow/stop]
             (mapv :operation invokes)))
      (is (= {:processes 2 :connections 1 :custom-executors []}
             (:input (first invokes))))
      (is (= 2 (get-in invokes [2 :input :message-count])))
      (is (= [:error-chan :report-chan]
             (get-in returns [1 :value :channels])))
      (doseq [private [graph completion [:sink :in] :private-a :private-b]]
        (is (not (.contains (pr-str events) (pr-str private))))))))

(deftest step-history-records-shape-without-state-or-message-values
  (let [journal (history/journal)
        initial (Object.)
        transitioned (Object.)
        message (Object.)]
    (is (= {:ins {:in "input"} :outs {:out "output"} :workload :io}
           (observe journal :core-async-flow/describe []
                    (constantly {:ins {:in "input"}
                                 :outs {:out "output"}
                                 :workload :io}))))
    (is (identical? initial
                    (observe journal :core-async-flow/init
                             [{:clojure.core.async.flow/pid :worker :n 2}]
                             (constantly initial))))
    (is (identical? transitioned
                    (observe journal :core-async-flow/transition
                             [initial :clojure.core.async.flow/resume]
                             (constantly transitioned))))
    (is (= [transitioned {:out [:private-output]}]
           (observe journal :core-async-flow/transform
                    [transitioned :in message]
                    (constantly
                     [transitioned {:out [:private-output]}]))))
    (is (true? (history/assert-complete! journal)))
    (let [events (history/events journal)
          invokes (filterv #(= :invoke (:phase %)) events)
          returns (filterv #(= :return (:phase %)) events)]
      (is (= [:core-async-flow/describe :core-async-flow/init
              :core-async-flow/transition :core-async-flow/transform]
             (mapv :operation invokes)))
      (is (= {:pid :worker :params [:n]} (:input (second invokes))))
      (is (= :clojure.core.async.flow/resume
             (get-in invokes [2 :input :transition])))
      (is (= [:out] (get-in returns [3 :value :outputs])))
      (doseq [private [initial transitioned message :private-output]]
        (is (not (.contains (pr-str events) (pr-str private))))))))

(deftest absent-journal-is-inert
  (let [value (Object.)
        calls (atom 0)]
    (is (identical? value
                    (provider/around-step
                     {:id :unknown} [:uninspected]
                     #(do (swap! calls inc) value))))
    (is (= 1 @calls))))

(deftest observation-does-not-realize-lazy-message-sources
  (let [journal (history/journal)
        realized (atom 0)
        messages (map (fn [value] (swap! realized inc) value) [:a :b])]
    (is (= :submitted
           (observe journal :core-async-flow/inject
                    [(Object.) [:worker :in] messages]
                    (constantly :submitted))))
    (is (zero? @realized))
    (is (= :unknown
           (get-in (first (history/events journal))
                   [:input :message-count])))))

(deftest faults-are-exactly-operation-scoped
  (let [calls (atom [])
        action {:operation :core-async-flow/transform
                :effect :return-before
                :value [:fault-state {}]}]
    (faults/call-with-fault
     action
     #(do
        (is (= :ordinary
               (faults/around-operation
                (join-point :core-async-flow/init) [{}]
                (fn [] (swap! calls conj :init) :ordinary))))
        (is (= [:fault-state {}]
               (faults/around-operation
                (join-point :core-async-flow/transform) [:state :in :message]
                (fn [] (swap! calls conj :transform) :unreachable))))))
    (is (= [:init] @calls))))

(deftest before-target-fault-explicitly-skips-inner-history
  (let [journal (history/journal)
        decisions (atom [])
        target-calls (atom 0)
        point (join-point :core-async-flow/start)]
    (is (= :skipped
           (faults/call-with-fault
            {:operation :core-async-flow/start
             :effect :return-before
             :value :skipped}
            decisions
            #(binding [history/*journal* journal]
               (faults/around-operation
                point [:graph]
                (fn []
                  (provider/around-lifecycle
                   point [:graph]
                   (fn [] (swap! target-calls inc) :started))))))))
    (is (zero? @target-calls))
    (is (empty? (history/events journal)))
    (is (= [{:operation :core-async-flow/start
             :effect :return-before
             :phase :before-target}]
           @decisions))))

(deftest replacement-and-throw-faults-preserve-control-contract
  (testing "replacement arguments are exact arity"
    (let [seen (atom nil)]
      (faults/call-with-fault
       {:operation :core-async-flow/transition
        :effect :replace-args
        :args [:replacement :clojure.core.async.flow/stop]}
       #(is (= :done
               (faults/around-operation
                (join-point :core-async-flow/transition)
                [:state :clojure.core.async.flow/resume]
                (fn [args] (reset! seen args) :done)))))
      (is (= [:replacement :clojure.core.async.flow/stop] @seen))))
  (testing "throw-before preserves the chosen Throwable"
    (let [error (ex-info "injected" {:fault true})
          caught (try
                   (faults/call-with-fault
                    {:operation :core-async-flow/transform
                     :effect :throw-before
                     :error error}
                    #(faults/around-operation
                      (join-point :core-async-flow/transform)
                      [:state :in :message]
                      (constantly :unreachable)))
                   (catch Throwable thrown thrown))]
      (is (identical? error caught)))))
