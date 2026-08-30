(ns jolt.aspect-packs.checkpoint-runtime-integration-test
  (:require [clojure.edn :as edn]
            [clojure.test :as test]
            [jolt.aspect-packs.checkpoint-history :as checkpoint-history]))

(defn- read-snapshot [path]
  (edn/read-string (slurp path)))

(test/deftest controlled-runtime-events-cross-the-portable-boundary
  (let [snapshot (read-snapshot (first *command-line-args*))
        evidence (checkpoint-history/normalize snapshot)]
    (test/is
     (= {:schema 1
         :kind :jolt/checkpoint-history
         :sites [{:id "fixture/receive" :dispositions [:continue]}]
         :plan [{:actor "actor/b" :id "fixture/receive" :hit 1
                 :action :continue}]
         :events [{:seq 1 :actor "actor/a" :id "fixture/receive" :hit 1
                   :action nil}
                  {:seq 2 :actor "actor/a" :id "fixture/receive" :hit 2
                   :action nil}
                  {:seq 3 :actor "actor/b" :id "fixture/receive" :hit 1
                   :action :continue}]
         :next-seq 4}
        evidence))
    (test/is
     (= [{:actor "actor/a" :id "fixture/receive" :hit 1 :action nil}
         {:actor "actor/a" :id "fixture/receive" :hit 2 :action nil}
         {:actor "actor/b" :id "fixture/receive" :hit 1 :action :continue}]
        (checkpoint-history/portable-observations snapshot)))))

(defn -main [& _]
  (let [result (test/run-tests
                'jolt.aspect-packs.checkpoint-runtime-integration-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
