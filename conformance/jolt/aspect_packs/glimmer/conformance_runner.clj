(ns jolt.aspect-packs.glimmer.conformance-runner
  (:require [clojure.test :as test]
            [jolt.aspect-packs.glimmer.mount-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.aspect-packs.glimmer.mount-test)
        failures (+ (:fail result) (:error result))]
    (System/exit (if (zero? failures) 0 1))))
