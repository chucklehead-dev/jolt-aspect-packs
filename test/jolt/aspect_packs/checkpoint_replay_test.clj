(ns jolt.aspect-packs.checkpoint-replay-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.aspect-packs.checkpoint-history :as history]
            [jolt.aspect-packs.checkpoint-replay :as replay]))

(def snapshot
  {:generation 7
   :version 1
   :sites {"queue/put" [:continue :yield]
           "queue/take" [:barrier :continue :fault]}
   :plan {["producer/0" "queue/put" 1] :yield
          ["consumer/0" "queue/take" 1] :barrier
          ["producer/0" "queue/take" 1] :barrier}
   :barriers {"queue/round-1"
              [["consumer/0" "queue/take" 1]
               ["producer/0" "queue/take" 1]]}
   :trace [{:seq 1 :actor "producer/0" :id "queue/put" :hit 1
            :action :yield}
           {:seq 2 :actor "consumer/0" :id "queue/take" :hit 1
            :action :barrier}
           {:seq 3 :actor "producer/0" :id "queue/take" :hit 1
            :action :barrier}]
   :next-seq 4})

(def provenance
  {:profile :controlled
   :source-revision "45347bd64302f80a45347bd64302f80a45347bd6"})

(def outcomes
  {"consumer/0" {:status :ok}
   "producer/0" {:status :ok}})

(def barriers
  {"queue/round-1"
   {:status :complete
    :arrivals [["consumer/0" "queue/take" 1]
               ["producer/0" "queue/take" 1]]
    :completed-after-arrivals 2}})

(def replay-case
  {:sites (:sites (history/normalize snapshot))
   :manifest (history/replay-manifest (history/normalize snapshot))
   :actor-events
   {"consumer/0" [{:id "queue/take" :hit 1 :action :barrier}]
    "producer/0" [{:id "queue/put" :hit 1 :action :yield}
                  {:id "queue/take" :hit 1 :action :barrier}]}
   :outcomes outcomes
   :extra-events :allow-unplanned
   :provenance provenance})

(defn run-of
  ([evidence] (run-of evidence outcomes provenance))
  ([evidence actual-outcomes actual-provenance]
   {:evidence evidence
    :outcomes actual-outcomes
    :barriers barriers
    :provenance actual-provenance}))

(defn renumber [events]
  (mapv #(assoc %1 :seq %2) events (iterate inc 1)))

(deftest validated-case-exposes-only-an-inert-runtime-manifest
  (let [validated (replay/validate-case replay-case)]
    (is (= :jolt/checkpoint-replay (:kind validated)))
    (is (= (:manifest validated) (replay/runtime-manifest replay-case)))
    (is (= {:jolt.checkpoint/version 1
            :jolt.checkpoint/plan (:plan snapshot)
            :jolt.checkpoint/barriers (:barriers snapshot)}
           (replay/runtime-manifest replay-case)))))

(deftest actor-local-replay-permits-cross-actor-global-sequence-permutations
  (let [permuted (assoc snapshot :trace
                        (renumber [(nth (:trace snapshot) 0)
                                   (nth (:trace snapshot) 2)
                                   (nth (:trace snapshot) 1)]))
        assessment (replay/assess replay-case (run-of permuted))]
    (is (= :reproduced (:status assessment)))
    (is (replay/reproduced? assessment))))

(deftest replay-requires-every-planned-selector-and-actor-local-order
  (testing "a missing planned occurrence is a mismatch"
    (let [short (assoc snapshot
                       :trace (renumber (subvec (:trace snapshot) 0 2))
                       :next-seq 3)
          assessment (replay/assess replay-case (run-of short))]
      (is (= :mismatch (:status assessment)))
      (is (some #(= :checkpoint-replay/plan-consumption-mismatch (:kind %))
                (:mismatches assessment)))))
  (testing "different order within one actor is a mismatch"
    (let [reordered (assoc snapshot :trace
                           (renumber [(nth (:trace snapshot) 2)
                                      (nth (:trace snapshot) 0)
                                      (nth (:trace snapshot) 1)]))
          assessment (replay/assess replay-case (run-of reordered))]
      (is (= :mismatch (:status assessment)))
      (is (some #(= :checkpoint-replay/actor-order-mismatch (:kind %))
                (:mismatches assessment))))))

(deftest unplanned-events-follow-the-explicit-case-policy
  (let [extra {:seq 4 :actor "producer/0" :id "queue/put" :hit 2 :action nil}
        extended (-> snapshot
                     (update :trace conj extra)
                     (assoc :next-seq 5))]
    (is (= :reproduced
           (:status (replay/assess replay-case (run-of extended)))))
    (is (= :mismatch
           (:status (replay/assess (assoc replay-case :extra-events :forbid)
                                   (run-of extended)))))))

(deftest outcomes-provenance-and-unresolved-runs-are-not-reproductions
  (let [wrong-outcomes
        (replay/assess replay-case
                       (run-of snapshot
                               (assoc outcomes "consumer/0"
                                      {:status :error
                                       :fingerprint "fixture/wrong-outcome"})
                               provenance))
        wrong-provenance
        (replay/assess replay-case
                       (run-of snapshot outcomes
                               (assoc provenance :source-revision
                                      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
        unresolved
        (replay/assess replay-case
                       (run-of snapshot
                               (assoc-in outcomes ["consumer/0" :status] :timeout)
                               provenance))]
    (is (= :mismatch (:status wrong-outcomes)))
    (is (= :mismatch (:status wrong-provenance)))
    (is (= :unresolved (:status unresolved)))
    (is (not (replay/reproduced? unresolved)))))

(deftest barrier-release-must-be-observed-after-every-planned-arrival
  (let [released-early
        (assoc-in (run-of snapshot)
                  [:barriers "queue/round-1" :completed-after-arrivals]
                  1)
        assessment (replay/assess replay-case released-early)]
    (is (= :mismatch (:status assessment)))
    (is (some #(= :checkpoint-replay/barrier-completion-mismatch (:kind %))
              (:mismatches assessment)))))

(deftest bounded-differential-oracle-exercises-the-real-assessor
  ;; This 3x4x2x2 product is deliberately independent of assess's internal
  ;; predicate decomposition. It invokes the public assessor for every point
  ;; and compares only its externally specified status with the table oracle.
  (let [cross-actor
        (assoc snapshot :trace
               (renumber [(nth (:trace snapshot) 0)
                          (nth (:trace snapshot) 2)
                          (nth (:trace snapshot) 1)]))
        actor-reversed
        (assoc snapshot :trace
               (renumber [(nth (:trace snapshot) 2)
                          (nth (:trace snapshot) 0)
                          (nth (:trace snapshot) 1)]))
        histories {:canonical snapshot
                   :cross-actor cross-actor
                   :actor-reversed actor-reversed}
        expected-error {:status :error :fingerprint "fixture/expected"}
        other-error {:status :error :fingerprint "fixture/other"}
        other-provenance
        (assoc provenance :source-revision
               "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        scenarios
        (for [interleaving [:canonical :cross-actor :actor-reversed]
              outcome [:ok :error-match :error-other :timeout]
              completed [2 1]
              provenance-kind [:same :other]]
          {:interleaving interleaving
           :outcome outcome
           :completed completed
           :provenance-kind provenance-kind})]
    (is (= 48 (count scenarios)))
    (doseq [{:keys [interleaving outcome completed provenance-kind] :as scenario}
            scenarios]
      (let [expected-outcomes
            (if (contains? #{:error-match :error-other} outcome)
              (assoc outcomes "consumer/0" expected-error)
              outcomes)
            actual-outcomes
            (case outcome
              :ok outcomes
              :error-match expected-outcomes
              :error-other (assoc outcomes "consumer/0" other-error)
              :timeout (assoc outcomes "consumer/0" {:status :timeout}))
            scenario-case (assoc replay-case :outcomes expected-outcomes)
            scenario-barriers
            (assoc-in barriers ["queue/round-1" :completed-after-arrivals]
                      completed)
            scenario-provenance
            (if (= :same provenance-kind) provenance other-provenance)
            expected-status
            (cond
              (= :timeout outcome) :unresolved
              (or (= :actor-reversed interleaving)
                  (= :error-other outcome)
                  (= 1 completed)
                  (= :other provenance-kind)) :mismatch
              :else :reproduced)
            actual-status
            (:status
             (replay/assess
              scenario-case
              {:evidence (get histories interleaving)
               :outcomes actual-outcomes
               :barriers scenario-barriers
               :provenance scenario-provenance}))]
        (is (= expected-status actual-status) (pr-str scenario))))))

(deftest errors-require-a-stable-fingerprint
  (let [error-outcomes
        (assoc outcomes "consumer/0"
               {:status :error :fingerprint "java.lang.IllegalStateException:closed"})
        error-case (assoc replay-case :outcomes error-outcomes)]
    (is (= :reproduced
           (:status (replay/assess error-case
                                   (run-of snapshot error-outcomes provenance)))))
    (doseq [outcome [{:status :error}
                     {:status :error :fingerprint ""}
                     {:status :error :fingerprint "stable" :message "unstable"}]]
      (try
        (replay/validate-case
         (assoc-in replay-case [:outcomes "consumer/0"] outcome))
        (is false (str "expected invalid error outcome for " outcome))
        (catch Exception error
          (is (= :checkpoint-replay/invalid-outcomes
                 (:kind (ex-data error)))))))))

(deftest malformed-runs-and-inert-extra-fields-fail-closed
  (doseq [[kind changed]
          [[:checkpoint-replay/invalid-run
            (assoc (run-of snapshot) :ignored true)]
           [:checkpoint-replay/invalid-run-outcomes
            (update (run-of snapshot) :outcomes dissoc "consumer/0")]
           [:checkpoint-replay/invalid-run-outcomes
            (assoc-in (run-of snapshot)
                      [:outcomes "consumer/0" :ignored] true)]
           [:checkpoint-replay/invalid-run-barrier
            (assoc-in (run-of snapshot)
                      [:barriers "queue/round-1" :ignored] true)]
           [:checkpoint-replay/invalid-run-barrier
            (assoc-in (run-of snapshot)
                      [:barriers "queue/round-1" :arrivals]
                      [["consumer/0" "queue/take"]])]
           [:checkpoint-replay/invalid-run-barrier
            (assoc-in (run-of snapshot)
                      [:barriers "queue/round-1"
                       :completed-after-arrivals]
                      3)]
           [:checkpoint-replay/invalid-run-barriers
            (update (run-of snapshot) :barriers dissoc "queue/round-1")]
           [:checkpoint-replay/invalid-provenance
            (assoc-in (run-of snapshot) [:provenance :ignored] true)]]]
    (try
      (replay/assess replay-case changed)
      (is false (str "expected " kind))
      (catch Exception error
        (is (= kind (:kind (ex-data error))))))))

(deftest malformed-cases-fail-with-stable-classifications
  (doseq [[kind changed]
          [[:checkpoint-replay/missing-expected-plan-selector
            (update-in replay-case [:actor-events "producer/0"] pop)]
           [:checkpoint-replay/invalid-case
            (assoc replay-case :ignored true)]
           [:checkpoint-replay/expected-action-mismatch
            (assoc-in replay-case
                      [:actor-events "producer/0" 0 :action] :continue)]
           [:checkpoint-replay/invalid-outcomes
            (update replay-case :outcomes dissoc "consumer/0")]
           [:checkpoint-replay/invalid-extra-events-policy
            (assoc replay-case :extra-events :ignore-everything)]
           [:checkpoint-replay/duplicate-site
            (update replay-case :sites conj (first (:sites replay-case)))]
           [:checkpoint-replay/invalid-provenance
            (assoc-in replay-case [:provenance :profile] :plain)]
           [:checkpoint-replay/invalid-provenance
            (assoc-in replay-case [:provenance :source-revision] "too-short")]
           [:checkpoint-replay/invalid-provenance
            (assoc-in replay-case [:provenance :ignored] true)]]]
    (try
      (replay/validate-case changed)
      (is false (str "expected " kind))
      (catch Exception error
        (is (= kind (:kind (ex-data error))))))))
