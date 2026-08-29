(ns jolt.aspect-packs.db.conformance-runner
  (:require [clojure.test :as test]
            [jolt.aspect-packs.db.dual-consumer-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.aspect-packs.db.dual-consumer-test)
        failures (+ (:fail result) (:error result))]
    (System/exit (if (zero? failures) 0 1))))
