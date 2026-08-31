(ns jolt.aspect-packs.checkpoint-history-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.aspect-packs.checkpoint-history :as checkpoint-history]))

(def snapshot
  {:sites {"queue/take" [:continue]
           "queue/put" [:continue :yield]}
   :plan {["consumer/0" "queue/take" 1] :continue}
   :trace [{:seq 1 :actor "producer/0" :id "queue/put" :hit 1 :action nil}
           {:seq 2 :actor "consumer/0" :id "queue/take" :hit 1
            :action :continue}
           {:seq 3 :actor "producer/0" :id "queue/put" :hit 2 :action nil}]
   :next-seq 4})

(def action-snapshot
  {:generation 7
   :version 1
   :sites {"queue/put" [:continue :yield]
           "queue/take" [:barrier :cancel :continue :fault]}
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

(deftest runtime-snapshot-normalizes-to-canonical-portable-evidence
  (let [evidence (checkpoint-history/normalize snapshot)]
    (is (= {:schema 1
            :kind :jolt/checkpoint-history
            :sites [{:id "queue/put" :dispositions [:continue :yield]}
                    {:id "queue/take" :dispositions [:continue]}]
            :plan [{:actor "consumer/0" :id "queue/take" :hit 1
                    :action :continue}]
            :events (:trace snapshot)
            :next-seq 4}
           evidence))
    (is (= {["consumer/0" "queue/take" 1] :continue}
           (checkpoint-history/replay-manifest evidence)))))

(deftest minimal-observations-are-plain-regression-data
  (let [observations (checkpoint-history/portable-observations snapshot)]
    (is (= [{:actor "producer/0" :id "queue/put" :hit 1 :action nil}
            {:actor "consumer/0" :id "queue/take" :hit 1 :action :continue}
            {:actor "producer/0" :id "queue/put" :hit 2 :action nil}]
           observations))
    (is (= observations (read-string (pr-str observations))))))

(deftest versioned-action-history-normalizes-and-reconstructs-an-inert-manifest
  (let [evidence (checkpoint-history/normalize action-snapshot)]
    (is (= 2 (:schema evidence)))
    (is (= 1 (:version evidence)))
    (is (= 7 (:generation evidence)))
    (is (= [{:id "queue/round-1"
             :selectors [["consumer/0" "queue/take" 1]
                         ["producer/0" "queue/take" 1]]}]
           (:barriers evidence)))
    (is (= {:jolt.checkpoint/version 1
            :jolt.checkpoint/plan
            {["consumer/0" "queue/take" 1] :barrier
             ["producer/0" "queue/put" 1] :yield
             ["producer/0" "queue/take" 1] :barrier}
            :jolt.checkpoint/barriers
            {"queue/round-1"
             [["consumer/0" "queue/take" 1]
              ["producer/0" "queue/take" 1]]}}
           (checkpoint-history/replay-manifest evidence)))
    (is (= evidence
           (checkpoint-history/normalize
            (assoc action-snapshot
                   :plan (:jolt.checkpoint/plan
                          (checkpoint-history/replay-manifest evidence))
                   :barriers (:jolt.checkpoint/barriers
                              (checkpoint-history/replay-manifest evidence))))))))

(deftest versioned-action-history-rejects-capability-and-barrier-corruption
  (doseq [[kind changed]
          [[:checkpoint-history/undeclared-plan-action
            (assoc-in action-snapshot
                      [:sites "queue/put"] [:continue])]
           [:checkpoint-history/invalid-barrier-id
            (assoc action-snapshot :barriers
                   {"unqualified" [["consumer/0" "queue/take" 1]
                                    ["producer/0" "queue/take" 1]]})]
           [:checkpoint-history/noncanonical-barrier-selectors
            (assoc-in action-snapshot [:barriers "queue/round-1"]
                      [["producer/0" "queue/take" 1]
                       ["consumer/0" "queue/take" 1]])]
           [:checkpoint-history/duplicate-barrier-actor
            (assoc-in action-snapshot [:barriers "queue/round-1"]
                      [["consumer/0" "queue/take" 1]
                       ["consumer/0" "queue/take" 2]])]
           [:checkpoint-history/unplanned-barrier-selector
            (assoc-in action-snapshot [:barriers "queue/round-1" 1]
                      ["producer/0" "queue/take" 2])]
           [:checkpoint-history/orphaned-barrier-action
            (assoc action-snapshot :barriers {})]]]
    (try
      (checkpoint-history/normalize changed)
      (is false (str "expected " kind))
      (catch Exception error
        (is (= kind (:kind (ex-data error))))))))

(deftest validation-rejects-non-vacuous-history-corruption
  (doseq [[kind changed]
          [[:checkpoint-history/noncontiguous-sequence
            (assoc-in snapshot [:trace 1 :seq] 1)]
           [:checkpoint-history/noncontiguous-hit
            (assoc-in snapshot [:trace 2 :hit] 3)]
           [:checkpoint-history/unregistered-event-site
            (assoc-in snapshot [:trace 0 :id] "queue/missing")]
           [:checkpoint-history/action-plan-mismatch
            (assoc-in snapshot [:trace 0 :action] :continue)]
           [:checkpoint-history/action-plan-mismatch
            (assoc-in snapshot [:trace 1 :action] nil)]
           [:checkpoint-history/invalid-next-seq
            (assoc snapshot :next-seq 9)]]]
    (try
      (checkpoint-history/normalize changed)
      (is false (str "expected " kind))
      (catch Exception error
        (is (= kind (:kind (ex-data error))))))))

(deftest validation-rejects-malformed-site-and-plan-provenance
  (testing "site declarations are canonical"
    (doseq [[kind sites]
            [[:checkpoint-history/invalid-site-id
              {"unqualified" [:continue]}]
             [:checkpoint-history/invalid-dispositions
              {"queue/put" [:yield :continue]}]
             [:checkpoint-history/invalid-dispositions
              {"queue/put" [:continue :continue]}]
             [:checkpoint-history/invalid-dispositions
              {"queue/put" [:yield]}]]]
      (try
        (checkpoint-history/normalize (assoc snapshot :sites sites))
        (is false (str "expected " kind))
        (catch Exception error
          (is (= kind (:kind (ex-data error))))))))
  (testing "plans are inert and name registered sites"
    (doseq [[kind plan]
            [[:checkpoint-history/invalid-plan-key
              {["consumer/0" "queue/take" 0] :continue}]
             [:checkpoint-history/unregistered-plan-site
              {["consumer/0" "queue/missing" 1] :continue}]
             [:checkpoint-history/invalid-plan-action
              {["consumer/0" "queue/take" 1] :yield}]]]
      (try
        (checkpoint-history/normalize (assoc snapshot :plan plan))
        (is false (str "expected " kind))
        (catch Exception error
          (is (= kind (:kind (ex-data error)))))))))

(deftest forward-compatible-top-level-diagnostics-do-not-change-evidence
  (is (= (checkpoint-history/normalize snapshot)
         (checkpoint-history/normalize
          (assoc snapshot :runtime-diagnostic {:carrier-count 4})))))

(deftest missing-runtime-api-fails-with-a-stable-classification
  ;; Normal released Jolt, Babashka, and JVM Clojure exercise this branch. The
  ;; checkpoint-runtime integration target separately exercises the live API.
  (when-not (resolve 'jolt.host/checkpoint-snapshot)
    (try
      (checkpoint-history/runtime-snapshot)
      (is false "expected unavailable runtime")
      (catch Exception error
        (is (= :checkpoint-history/runtime-unavailable
               (:kind (ex-data error))))))))
