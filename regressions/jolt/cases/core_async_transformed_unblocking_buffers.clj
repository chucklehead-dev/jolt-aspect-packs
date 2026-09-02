;; regression-id: issue-9
(require '[clojure.core.async :as async])

(def pass-signature "REGRESSION PASS issue-9")
(def fail-signature "REGRESSION FAIL issue-9")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(defn exercise-buffer [buffer]
  (let [calls (atom 0)
        channel (async/chan
                 buffer
                 (map #(do (swap! calls inc) (inc %))))
        first-put (async/>!! channel 1)
        pending-put (future (async/>!! channel 2))
        second-put (deref pending-put 1000 :timed-out)
        _ (when (= :timed-out second-put)
            (async/close! channel)
            (deref pending-put 1000 nil))
        retained-value (async/<!! channel)]
    {:first-put first-put
     :second-put second-put
     :transform-calls @calls
     :retained-value retained-value}))

(let [actual {:dropping (exercise-buffer (async/dropping-buffer 1))
              :sliding (exercise-buffer (async/sliding-buffer 1))}
      expected {:dropping {:first-put true
                           :second-put true
                           :transform-calls 2
                           :retained-value 2}
                :sliding {:first-put true
                          :second-put true
                          :transform-calls 2
                          :retained-value 3}}]
  (finish! (= expected actual)
           (pr-str {:expected expected :actual actual})))
