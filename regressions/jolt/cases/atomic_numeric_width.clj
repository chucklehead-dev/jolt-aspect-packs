;; regression-id: issue-36
(def pass-signature "REGRESSION PASS issue-36")
(def fail-signature "REGRESSION FAIL issue-36")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(let [integer-max (java.util.concurrent.atomic.AtomicInteger. 2147483647)
      integer-min (java.util.concurrent.atomic.AtomicInteger. -2147483648)
      long-max (java.util.concurrent.atomic.AtomicLong. 9223372036854775807)
      long-min (java.util.concurrent.atomic.AtomicLong. -9223372036854775808)
      ordinary (java.util.concurrent.atomic.AtomicInteger. 40)
      outcomes [(.incrementAndGet integer-max)
                (.decrementAndGet integer-min)
                (.incrementAndGet long-max)
                (.decrementAndGet long-min)]
      ordinary-result (.addAndGet ordinary 2)]
  (finish!
    (and (= [-2147483648 2147483647
             -9223372036854775808 9223372036854775807]
            outcomes)
         (= 42 ordinary-result)
         (= 42 (.get ordinary)))
    "AtomicInteger and AtomicLong must wrap at their Java widths while ordinary addition remains exact"))
