(ns jolt.aspect-packs.http-server.conformance-runner
  (:require [clojure.test :as test]
            [jolt.aspect-packs.http-server.conformance-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'jolt.aspect-packs.http-server.conformance-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
