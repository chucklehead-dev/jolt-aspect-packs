(ns jolt.aspect-packs.test-runner
  (:require [clojure.test :as test]
            [jolt.aspect-packs.glimmer.provider-test]
            [jolt.aspect-packs.glitter.provider-test]
            [jolt.aspect-packs.http-client.provider-test]))

(defn -main [& _]
  (let [result (test/run-tests
                'jolt.aspect-packs.glimmer.provider-test
                'jolt.aspect-packs.glitter.provider-test
                'jolt.aspect-packs.http-client.provider-test)
        failures (+ (:fail result) (:error result))]
    (System/exit (if (zero? failures) 0 1))))
