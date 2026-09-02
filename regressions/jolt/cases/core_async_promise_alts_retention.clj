;; regression-id: issue-8
(require '[clojure.core.async :as async])

(def pass-signature "REGRESSION PASS issue-8")
(def fail-signature "REGRESSION FAIL issue-8")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(let [channel (async/chan
               (async/__promise-buffer)
               (mapcat #(vector % (inc %))))
      first-taker (future (first (async/alts!! [channel])))
      second-taker (future (first (async/alts!! [channel])))
      _ (Thread/sleep 250)
      put-result (async/>!! channel 1)
      first-result (deref first-taker 1000 :timed-out)
      second-result (deref second-taker 1000 :timed-out)
      _ (when (or (= :timed-out first-result)
                  (= :timed-out second-result))
          (async/close! channel)
          (deref first-taker 1000 nil)
          (deref second-taker 1000 nil))
      retained-value (async/poll! channel)
      actual {:put-result put-result
              :taker-values #{first-result second-result}
              :same-taker-values? (= first-result second-result)
              :retained-value retained-value}
      expected {:put-result true
                :taker-values #{1}
                :same-taker-values? true
                :retained-value 1}]
  (finish! (= expected actual)
           (pr-str {:expected expected :actual actual})))
