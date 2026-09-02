;; regression-id: issue-11
(require '[clojure.core.async :as async])

(def pass-signature "REGRESSION PASS issue-11")
(def fail-signature "REGRESSION FAIL issue-11")
(def diagnostic-signature "Exception in core.async transducer:")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(defn exercise-handler [handler]
  (let [channel (async/chan
                 (async/__promise-buffer)
                 (map (fn [_]
                        (throw (ex-info "expected-xform-failure" {}))))
                 handler)]
    {:put-result (try
                   (async/>!! channel 1)
                   :completed
                   (catch Throwable error
                     [:threw (str error)]))
     :value (async/poll! channel)}))

(let [actual {:nil-handler (exercise-handler nil)
              :false-handler (exercise-handler false)}
      expected {:nil-handler {:put-result :completed :value nil}
                :false-handler {:put-result :completed :value nil}}]
  (finish! (= expected actual)
           (pr-str {:expected expected :actual actual})))
