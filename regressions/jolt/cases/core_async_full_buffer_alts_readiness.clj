;; regression-id: issue-7
(require '[clojure.core.async :as async])

(def pass-signature "REGRESSION PASS issue-7")
(def fail-signature "REGRESSION FAIL issue-7")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(let [channel (async/chan 1 (map inc))
      first-put (async/>!! channel 1)
      pending-alt (future (async/__do-alts [[channel 2]] false))
      early-result (deref pending-alt 500 :timed-out)
      first-value (async/<!! channel)
      final-result (deref pending-alt 1000 :timed-out)
      transformed-value (async/poll! channel)
      actual {:first-put first-put
              :early-result early-result
              :first-value first-value
              :final-accepted (when (vector? final-result)
                                (first final-result))
              :final-selected-channel? (and (vector? final-result)
                                            (= channel (second final-result)))
              :transformed-value transformed-value}
      expected {:first-put true
                :early-result :timed-out
                :first-value 2
                :final-accepted true
                :final-selected-channel? true
                :transformed-value 3}]
  (finish! (= expected actual)
           (pr-str {:expected expected :actual actual})))
