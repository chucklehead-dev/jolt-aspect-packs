(ns jolt.aspect-packs.glitter.conformance-runner
  (:require [clojure.test :as test]
            [jolt.aspect-packs.glitter.lifecycle-test]))

(defn -main [& _]
  (let [result
        (test/run-tests 'jolt.aspect-packs.glitter.lifecycle-test)
        failures (+ (:fail result) (:error result))]
    (System/exit (if (zero? failures) 0 1))))

