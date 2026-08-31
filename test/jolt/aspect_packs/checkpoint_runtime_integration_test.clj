(ns jolt.aspect-packs.checkpoint-runtime-integration-test
  (:require [clojure.edn :as edn]
            [clojure.test :as test]
            [jolt.aspect-packs.checkpoint-history :as checkpoint-history]
            [jolt.aspect-packs.checkpoint-replay :as checkpoint-replay]))

(defn- read-runs [path]
  (edn/read-string (slurp path)))

(defn- barrier-arrival-count [snapshot]
  (->> (:events (checkpoint-history/normalize snapshot))
       (filter #(and (= "fixture/rendezvous" (:id %))
                     (= :barrier (:action %))))
       count))

(test/deftest controlled-runtime-events-cross-the-portable-boundary
  (let [runs (read-runs (first *command-line-args*))
        revision (second *command-line-args*)
        snapshots (mapv first runs)
        completed-after-arrivals
        (mapv (fn [[_ & completion-cuts]]
                (apply min (map barrier-arrival-count completion-cuts)))
              runs)
        evidence (mapv checkpoint-history/normalize snapshots)
        provenance {:profile :controlled
                    :source-revision revision}
        arrivals [["actor/a" "fixture/rendezvous" 1]
                  ["actor/b" "fixture/rendezvous" 1]]
        barriers {"fixture/round"
                  {:status :complete
                   :arrivals arrivals
                   :completed-after-arrivals 2}}
        replay-case
        {:sites (:sites (first evidence))
         :manifest (checkpoint-history/replay-manifest (first evidence))
         :actor-events
         {"actor/a" [{:id "fixture/yield" :hit 1 :action :yield}
                     {:id "fixture/rendezvous" :hit 1 :action :barrier}]
          "actor/b" [{:id "fixture/rendezvous" :hit 1 :action :barrier}]}
         :outcomes {"actor/a" {:status :ok}
                    "actor/b" {:status :ok}}
         :extra-events :forbid
         :provenance provenance}
        assessments
        (mapv (fn [run-evidence completed]
                (checkpoint-replay/assess
                 replay-case
                 {:evidence run-evidence
                  :outcomes (:outcomes replay-case)
                  :barriers (assoc-in barriers
                                      ["fixture/round"
                                       :completed-after-arrivals]
                                      completed)
                  :provenance provenance}))
              evidence completed-after-arrivals)]
    (test/is (= 2 (count evidence)))
    (test/is (= [2 2] completed-after-arrivals))
    (test/is (every? #(= 2 (:schema %)) evidence))
    (test/is (= #{:yield :barrier}
                (set (map :action (mapcat :events evidence)))))
    (test/is (apply not= (map :generation evidence)))
    (test/is (every? checkpoint-replay/reproduced? assessments))))

(defn -main [& _]
  (let [result (test/run-tests
                'jolt.aspect-packs.checkpoint-runtime-integration-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
