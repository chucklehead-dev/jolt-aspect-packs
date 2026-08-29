(ns jolt.aspect-packs.test-runner
  (:require [clojure.test :as test]
            [jolt.aspect-packs.compatibility-test]
            [jolt.aspect-packs.core-async.faults-test]
            [jolt.aspect-packs.core-async.model-test]
            [jolt.aspect-packs.core-async.perturbation-test]
            [jolt.aspect-packs.core-async.provider-test]
            [jolt.aspect-packs.db.provider-test]
            [jolt.aspect-packs.glimmer.provider-test]
            [jolt.aspect-packs.glitter.provider-test]
            [jolt.aspect-packs.history-test]
            [jolt.aspect-packs.http-server.provider-test]
            [jolt.aspect-packs.http-client.provider-test]
            [jolt.aspect-packs.mycelium.provider-test]))

(defn -main [& _]
  (let [result (test/run-tests
                'jolt.aspect-packs.compatibility-test
                'jolt.aspect-packs.core-async.faults-test
                'jolt.aspect-packs.core-async.model-test
                'jolt.aspect-packs.core-async.perturbation-test
                'jolt.aspect-packs.core-async.provider-test
                'jolt.aspect-packs.db.provider-test
                'jolt.aspect-packs.glimmer.provider-test
                'jolt.aspect-packs.glitter.provider-test
                'jolt.aspect-packs.history-test
                'jolt.aspect-packs.http-server.provider-test
                'jolt.aspect-packs.http-client.provider-test
                'jolt.aspect-packs.mycelium.provider-test)
        failures (+ (:fail result) (:error result))]
    (System/exit (if (zero? failures) 0 1))))
