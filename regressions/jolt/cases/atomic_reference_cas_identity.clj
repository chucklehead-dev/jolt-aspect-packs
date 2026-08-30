;; regression-id: issue-23
(def pass-signature "REGRESSION PASS issue-23")
(def fail-signature "REGRESSION FAIL issue-23")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(let [actual (list :same)
      stale-equal (list :same)
      replacement (list :replacement)
      cell (java.util.concurrent.atomic.AtomicReference. actual)
      fixture? (and (= actual stale-equal)
                    (not (identical? actual stale-equal)))
      stale-result (.compareAndSet cell stale-equal replacement)
      after-stale (.get cell)
      identity-result (.compareAndSet cell actual replacement)
      primitive-actual (Long/parseLong "9223372036854775806")
      primitive-equal (Long/parseLong "9223372036854775806")
      primitive-next (Long/parseLong "9223372036854775805")
      primitive-cell (java.util.concurrent.atomic.AtomicLong. primitive-actual)
      primitive-control
      (and (= primitive-actual primitive-equal)
           (not (identical? primitive-actual primitive-equal))
           (.compareAndSet primitive-cell primitive-equal primitive-next))]
  (finish!
    (and fixture?
         (false? stale-result)
         (identical? actual after-stale)
         (true? identity-result)
         (identical? replacement (.get cell))
         primitive-control
         (= primitive-next (.get primitive-cell)))
    "equal non-identical references must fail CAS while identity and primitive value controls succeed"))
