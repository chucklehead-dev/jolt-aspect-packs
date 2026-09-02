;; regression-id: issue-4
(require '[clojure.core.async :as async])

(def pass-signature "REGRESSION PASS issue-4")
(def fail-signature "REGRESSION FAIL issue-4")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(let [transformed (async/promise-chan (map inc))
      transformed-put (async/>!! transformed 1)
      transformed-value (async/<!! transformed)
      handled (async/promise-chan
               (map (fn [_]
                      (throw (ex-info "expected transform failure" {}))))
               (fn [_] 42))
      handled-put (async/>!! handled 1)
      handled-value (async/<!! handled)
      actual {:transformed-put transformed-put
              :transformed-value transformed-value
              :handled-put handled-put
              :handled-value handled-value}
      expected {:transformed-put true
                :transformed-value 2
                :handled-put true
                :handled-value 42}]
  (finish! (= expected actual)
           (pr-str {:expected expected :actual actual})))
