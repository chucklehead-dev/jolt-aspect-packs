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

(deftest runtime-snapshot-normalizes-to-canonical-portable-evidence
  (is (= {:schema 1
          :kind :jolt/checkpoint-history
          :sites [{:id "queue/put" :dispositions [:continue :yield]}
                  {:id "queue/take" :dispositions [:continue]}]
          :plan [{:actor "consumer/0" :id "queue/take" :hit 1
                  :action :continue}]
          :events (:trace snapshot)
          :next-seq 4}
         (checkpoint-history/normalize snapshot))))

(deftest minimal-observations-are-plain-regression-data
  (let [observations (checkpoint-history/portable-observations snapshot)]
    (is (= [{:actor "producer/0" :id "queue/put" :hit 1 :action nil}
            {:actor "consumer/0" :id "queue/take" :hit 1 :action :continue}
            {:actor "producer/0" :id "queue/put" :hit 2 :action nil}]
           observations))
    (is (= observations (read-string (pr-str observations))))))

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
