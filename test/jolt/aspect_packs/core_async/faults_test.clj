(ns jolt.aspect-packs.core-async.faults-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.aspects :as aspects]
            [jolt.aspect-packs.core-async.faults :as faults]))

(defn- join-point [id]
  {:id id :site-id (str "fault-test/" (name id))
   :build-identity "fault-test-build"})

(defn- invoke-control
  ([id args operation action]
   (invoke-control id args operation action nil))
  ([id args operation action decisions]
   (faults/call-with-fault
    action decisions
    #(aspects/invoke-control
      (if (contains? #{:core-async/put :core-async/take} id)
        faults/around-callback-operation
        faults/around-operation)
      (join-point id) args operation))))

(def ^:dynamic *callback-context* nil)

(deftest forced-results-and-errors-distinguish-target-ownership
  (let [calls (atom 0)
        before (ex-info "before" {:kind :before})
        after (ex-info "after" {:kind :after})]
    (is (= :injected
           (invoke-control :core-async/offer [:channel :value]
                           (fn [& _] (swap! calls inc) :target)
                           {:effect :return-before :value :injected})))
    (is (zero? @calls))
    (is (identical?
         before
         (try
           (invoke-control :core-async/offer [:channel :value]
                           (fn [& _] (swap! calls inc))
                           {:effect :throw-before :error before})
           nil
           (catch Throwable error error))))
    (is (zero? @calls))
    (is (= :masked
           (invoke-control :core-async/offer [:channel :value]
                           (fn [& _] (swap! calls inc) :target)
                           {:effect :return-after :value :masked})))
    (is (= 1 @calls))
    (is (identical?
         after
         (try
           (invoke-control :core-async/offer [:channel :value]
                           (fn [& _] (swap! calls inc) :target)
                           {:effect :throw-after :error after})
           nil
           (catch Throwable error error))))
    (is (= 2 @calls))))

(deftest invalid-throw-plan-fails-before-the-target
  (let [calls (atom 0)]
    (doseq [effect [:throw-before :throw-after]]
      (is (thrown? Exception
                   (invoke-control :core-async/offer [:channel :value]
                                   (fn [& _] (swap! calls inc))
                                   {:effect effect :error :not-throwable}))))
    (is (zero? @calls))))

(deftest replacement-is-exact-arity-and-reaches-the-target-once
  (let [seen (atom [])]
    (is (= :target
           (invoke-control :core-async/offer [:old-channel :old-value]
                           (fn [& args] (swap! seen conj args) :target)
                           {:effect :replace-args
                            :args [:new-channel :new-value]})))
    (is (= [[:new-channel :new-value]] @seen))
    (is (thrown? Exception
                 (invoke-control :core-async/offer [:channel :value]
                                 (fn [& _] :target)
                                 {:effect :replace-args :args [:short]})))))

(deftest before-barrier-is-bounded-and-keeps-proceed-on-its-owner
  (let [arrived (promise)
        release (promise)
        calls (atom 0)
        worker
        (future
          (invoke-control :core-async/offer [:channel :value]
                          (fn [& _] (swap! calls inc) :target)
                          {:effect :barrier-before
                           :arrived arrived :release release
                           :timeout-ms 2000}))]
    (is (true? (deref arrived 1000 false)))
    (is (zero? @calls))
    (deliver release true)
    (is (= :target (deref worker 2000 ::timed-out)))
    (is (= 1 @calls)))
  (let [calls (atom 0)]
    (is (thrown? Exception
                 (invoke-control :core-async/offer [:channel :value]
                                 (fn [& _] (swap! calls inc))
                                 {:effect :barrier-before
                                  :arrived (atom nil)
                                  :release (promise)})))
    (is (zero? @calls)))
  (let [arrived (promise)
        release (promise)
        calls (atom 0)
        worker
        (future
          (invoke-control :core-async/offer [:channel :value]
                          (fn [& _] (swap! calls inc) :target)
                          {:effect :barrier-after
                           :arrived arrived :release release
                           :timeout-ms 2000}))]
    (is (true? (deref arrived 1000 false)))
    (is (= 1 @calls) "after-barrier runs the target before waiting")
    (deliver release true)
    (is (= :target (deref worker 2000 ::timed-out)))))

(deftest callback-faults-wrap-delivery-without-reexecuting-the-target
  (doseq [[effect expected-count]
          [[:callback-suppress 0] [:callback-duplicate 2]]]
    (testing effect
      (let [target-count (atom 0)
            callback-count (atom 0)
            captured (atom nil)
            decisions (atom [])
            callback (fn [_] (swap! callback-count inc))]
        (is (= :registered
               (invoke-control
                :core-async/put [:channel :value callback true]
                (fn [_ _ replacement _]
                  (swap! target-count inc)
                  (reset! captured replacement)
                  :registered)
                {:effect effect} decisions)))
        (is (= 1 @target-count))
        (is (zero? @callback-count))
        (@captured true)
        (is (= expected-count @callback-count))))))

(deftest deferred-callback-captures-without-blocking-and-releases-once
  (let [slot (atom nil)
        captured (atom nil)
        callback-count (atom 0)
        callback-context (atom nil)
        callback (fn [value]
                   (reset! callback-context *callback-context*)
                   (swap! callback-count inc)
                   value)]
    (is (= :registered
           (binding [*callback-context* :captured]
             (invoke-control
              :core-async/take [:channel callback true]
              (fn [_ replacement _]
                (reset! captured replacement)
                :registered)
              {:effect :callback-defer :slot slot}))))
    (is (zero? @callback-count))
    (is (ifn? @captured))
    (binding [*callback-context* :delivery]
      (@captured :delivered))
    (is (zero? @callback-count))
    (is (ifn? @slot))
    (is (= :delivered
           (binding [*callback-context* :release]
             (@slot))))
    (is (= 1 @callback-count))
    (is (= :delivery @callback-context))
    (is (thrown? Exception (@slot)))
    (is (= 1 @callback-count))))

(deftest absent-action-is-inert-and-decisions-are-bounded-metadata
  (let [decisions (atom [])]
    (is (= :target
           (faults/call-with-fault
            {:effect :return-before :value :target}
            decisions
            #(aspects/invoke-control faults/around-operation
                                     (join-point :core-async/poll)
                                     [:private-channel]
                                     (fn [_] :unreachable)))))
    (is (= [{:operation :core-async/poll
             :effect :return-before :phase :before-target}]
           @decisions))
    (is (not (.contains (pr-str @decisions) "private-channel")))
    (is (= :ran
           (invoke-control :core-async/poll [:channel]
                           (fn [_] :ran) nil)))))
