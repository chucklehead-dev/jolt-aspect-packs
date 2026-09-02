;; regression-id: issue-5
(require '[clojure.core.async :as async])

(def pass-signature "REGRESSION PASS issue-5")
(def fail-signature "REGRESSION FAIL issue-5")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(let [blocking-calls (atom 0)
      blocking (async/chan
                (async/__promise-buffer)
                (map #(do (swap! blocking-calls inc) %)))
      first-put (async/>!! blocking 1)
      pending-put (future (async/>!! blocking 2))
      second-put (deref pending-put 1000 :timed-out)
      _ (when (= :timed-out second-put)
          (async/close! blocking)
          (deref pending-put 1000 nil))
      blocking-value (async/<!! blocking)
      offered-calls (atom 0)
      offered (async/chan
               (async/__promise-buffer)
               (map #(do (swap! offered-calls inc) %)))
      first-offer (async/offer! offered 1)
      second-offer (async/offer! offered 2)
      offered-value (async/poll! offered)
      actual {:first-put first-put
              :second-put second-put
              :blocking-calls @blocking-calls
              :blocking-value blocking-value
              :first-offer first-offer
              :second-offer second-offer
              :offered-calls @offered-calls
              :offered-value offered-value}
      expected {:first-put true
                :second-put true
                :blocking-calls 2
                :blocking-value 1
                :first-offer true
                :second-offer true
                :offered-calls 2
                :offered-value 1}]
  (finish! (= expected actual)
           (pr-str {:expected expected :actual actual})))
