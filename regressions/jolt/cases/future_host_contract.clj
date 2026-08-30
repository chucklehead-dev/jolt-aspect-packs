;; regression-id: issue-46
(def pass-signature "REGRESSION PASS issue-46")
(def fail-signature "REGRESSION FAIL issue-46")

(defn thrown-shape [cause f]
  (try [:ok (f)]
       (catch Throwable e
         [:throw (.getName (class e))
          (some-> e ex-cause class .getName)
          (some-> e ex-cause ex-message)
          (identical? cause (ex-cause e))])))

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do (binding [*out* *err*] (println fail-signature details))
        (System/exit 1))))

(let [runs (atom 0)
      completed (java.util.concurrent.FutureTask.
                 ^java.util.concurrent.Callable #(swap! runs inc))
      cause (ex-info "future-contract" {:issue 46})
      failed (java.util.concurrent.FutureTask.
              ^java.util.concurrent.Callable #(throw cause))
      cancelled-runs (atom 0)
      cancelled (java.util.concurrent.FutureTask.
                 ^java.util.concurrent.Callable #(swap! cancelled-runs inc))
      waiting (java.util.concurrent.FutureTask.
               ^java.util.concurrent.Callable (fn [] :not-run))
      executor (java.util.concurrent.Executors/newFixedThreadPool 1)]
  (try
    (.run completed)
    (.run completed)
    (.run failed)
    (let [cancel-won? (.cancel cancelled false)
          _ (.run cancelled)
          timeout-deref (deref waiting 1 :timeout)
          timeout-get (try (.get waiting 1 java.util.concurrent.TimeUnit/MILLISECONDS)
                           :no-timeout
                           (catch java.util.concurrent.TimeoutException _ :timeout))
          cancelled-get (try (.get cancelled) :no-cancel
                             (catch java.util.concurrent.CancellationException _ :cancelled))
          actual {:completed [@runs (.get completed) (deref completed)]
                  :future-identity [(instance? java.util.concurrent.Future completed)
                                    (instance? java.util.concurrent.RunnableFuture completed)
                                    (instance? Runnable completed)
                                    (instance? clojure.lang.IDeref completed)]
                  :failure [(thrown-shape cause #(.get failed))
                            (thrown-shape cause #(deref failed))]
                  :timeout [timeout-deref timeout-get]
                  :cancel [cancel-won? (.isCancelled cancelled) (.isDone cancelled)
                           cancelled-get @cancelled-runs]
                  :executor-identity [(instance? java.util.concurrent.ExecutorService executor)
                                      (instance? java.util.concurrent.Executor executor)]}
          expected {:completed [1 1 1]
                    :future-identity [true true true false]
                    :failure [[:throw "java.util.concurrent.ExecutionException"
                               "clojure.lang.ExceptionInfo" "future-contract" true]
                              [:throw "java.util.concurrent.ExecutionException"
                               "clojure.lang.ExceptionInfo" "future-contract" true]]
                    :timeout [:timeout :timeout]
                    :cancel [true true true :cancelled 0]
                    :executor-identity [true true]}]
      (finish! (= expected actual) (pr-str {:expected expected :actual actual})))
    (catch Throwable e
      (finish! false
               (pr-str {:class (.getName (class e))
                        :message (ex-message e)})))
    (finally (.shutdownNow executor))))
