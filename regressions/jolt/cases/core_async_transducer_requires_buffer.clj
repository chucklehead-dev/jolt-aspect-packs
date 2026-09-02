;; regression-id: issue-3
(require '[clojure.core.async :as async])

(def pass-signature "REGRESSION PASS issue-3")
(def fail-signature "REGRESSION FAIL issue-3")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(let [outcome (try
                (async/chan nil (map inc))
                :accepted
                (catch AssertionError _ :rejected)
                (catch Throwable error
                  [:wrong-exception (.getName (class error))]))]
  (finish! (= :rejected outcome)
           (pr-str {:expected :rejected :actual outcome})))
