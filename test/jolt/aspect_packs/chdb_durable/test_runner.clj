(ns jolt.aspect-packs.chdb-durable.test-runner
  (:require [clojure.test :as test]
            [jolt.aspect-packs.chdb-durable.provider-test]))

(defn -main [& _]
  (let [result (test/run-tests
                'jolt.aspect-packs.chdb-durable.provider-test)
        failures (+ (:fail result) (:error result))]
    (System/exit (if (zero? failures) 0 1))))
