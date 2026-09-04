(ns jolt.aspect-packs.scenario.core-async-flow-faults
  (:require [jolt.aspect-packs.core-async-flow.faults :as faults]
            [jolt.aspect-packs.scenario.core-async-flow :as scenario]))

(defn -main [& _]
  (let [decisions (atom [])
        result
        (faults/call-with-fault
         {:operation :core-async-flow/ping
          :effect :return-after
          :value {:worker {:clojure.core.async.flow/count 99}}}
         decisions
         #(scenario/run-scenario! {:expected-ping-count 99}))]
    (when-not (= [{:operation :core-async-flow/ping
                   :effect :return-after
                   :phase :after-target}]
                 @decisions)
      (throw (ex-info "flow fault decision was not exact"
                      {:decisions @decisions})))
    (println (pr-str (assoc result :fault-decisions (count @decisions))))))
