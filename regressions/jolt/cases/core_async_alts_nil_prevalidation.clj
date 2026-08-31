;; regression-id: issue-55
(require '[clojure.core.async :as a])

(def pass-signature "REGRESSION PASS issue-55")
(def fail-signature "REGRESSION FAIL issue-55")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(let [ready (a/chan 1)
      other (a/chan)
      seeded? (a/>!! ready :kept)
      threw?
      (try
        (a/alts!! [ready [other nil]] :priority true :default :fallback)
        false
        (catch Throwable _ true))
      ready-after (a/poll! ready)
      other-after (a/poll! other)
      actual {:seeded? seeded?
              :threw? threw?
              :ready-after ready-after
              :other-after other-after}
      expected {:seeded? true
                :threw? true
                :ready-after :kept
                :other-after nil}]
  (finish! (= expected actual)
           (pr-str {:expected expected :actual actual})))
