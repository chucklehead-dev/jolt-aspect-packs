;; regression-id: issue-10
(require '[clojure.core.async :as async])

(def pass-signature "REGRESSION PASS issue-10")
(def fail-signature "REGRESSION FAIL issue-10")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(try
  (let [unbuffered (async/chan nil false)
        fixed (async/chan 1 false)
        promise (async/promise-chan false)
        actual {:unbuffered-channel? (async/chan? unbuffered)
                :fixed-put (async/>!! fixed 1)
                :fixed-value (async/<!! fixed)
                :promise-put (async/>!! promise 2)
                :promise-value (async/<!! promise)}
        expected {:unbuffered-channel? true
                  :fixed-put true
                  :fixed-value 1
                  :promise-put true
                  :promise-value 2}]
    (finish! (= expected actual)
             (pr-str {:expected expected :actual actual})))
  (catch Throwable error
    (finish! false (pr-str {:unexpected-error (str error)}))))
