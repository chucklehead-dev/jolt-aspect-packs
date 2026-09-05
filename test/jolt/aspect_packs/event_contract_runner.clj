(ns jolt.aspect-packs.event-contract-runner
  "Pure producer/profile compatibility gate; no native engine or compiler aspects."
  (:require [clojure.test :as test]
            [jolt.aspect-packs.event-contract-test]
            [jolt.aspect-packs.history-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.aspect-packs.event-contract-test
                               'jolt.aspect-packs.history-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
