;; regression-id: issue-6
(require '[clojure.core.async :as async])

(def pass-signature "REGRESSION PASS issue-6")
(def fail-signature "REGRESSION FAIL issue-6")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(let [calls (atom 0)
      channel (async/chan
               (async/__promise-buffer)
               (map #(do (swap! calls inc) %)))
      first-put (async/>!! channel 1)
      [accepted selected-port] (async/__do-alts [[channel 2]] false)
      retained-value (async/<!! channel)
      actual {:first-put first-put
              :accepted accepted
              :selected-channel? (= selected-port channel)
              :transform-calls @calls
              :retained-value retained-value}
      expected {:first-put true
                :accepted true
                :selected-channel? true
                :transform-calls 2
                :retained-value 1}]
  (finish! (= expected actual)
           (pr-str {:expected expected :actual actual})))
