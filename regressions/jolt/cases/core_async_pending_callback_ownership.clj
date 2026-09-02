;; regression-id: issue-14
(require '[clojure.core.async :as async]
         '[jolt.scheme :as scheme])

(def pass-signature "REGRESSION PASS issue-14")
(def fail-signature "REGRESSION FAIL issue-14")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

;; Hold only the worker fork requested by the first public operation. The
;; complementary call then observes either caller-side registration (fixed) or
;; no registration yet (historical), without relying on thread scheduling.
(scheme/eval-string
 "(begin
    (define jolt-regression-issue14-original-fork-thread fork-thread)
    (define jolt-regression-issue14-held-forks '())
    (define (jolt-regression-issue14-hold-forks!)
      (set! jolt-regression-issue14-held-forks '())
      (set! fork-thread
        (lambda (thunk)
          (set! jolt-regression-issue14-held-forks
            (append jolt-regression-issue14-held-forks (list thunk)))
          #t)))
    (define (jolt-regression-issue14-restore-forks!)
      (set! fork-thread jolt-regression-issue14-original-fork-thread)
      (length jolt-regression-issue14-held-forks))
    (define (jolt-regression-issue14-release-forks!)
      (let ((held jolt-regression-issue14-held-forks))
        (set! jolt-regression-issue14-held-forks '())
        (for-each jolt-regression-issue14-original-fork-thread held)
        (length held))))")

(defn restore-and-release! []
  (scheme/call "jolt-regression-issue14-restore-forks!")
  (scheme/call "jolt-regression-issue14-release-forks!"))

(defn pending-put-then-take []
  (try
    (scheme/call "jolt-regression-issue14-hold-forks!")
    (let [caller (Thread/currentThread)
          channel (async/chan)
          put-completed (promise)
          put-returned (async/put! channel :value
                                   #(deliver put-completed %) true)
          held-count (scheme/call
                      "jolt-regression-issue14-restore-forks!")
          take-callback-thread (promise)
          take-returned (async/take!
                         channel
                         (fn [_]
                           (deliver take-callback-thread
                                    (Thread/currentThread)))
                         true)
          released-count (scheme/call
                          "jolt-regression-issue14-release-forks!")
          observed (deref take-callback-thread 1000 :timed-out)]
      {:put-returned put-returned
       :take-returned take-returned
       :held-count held-count
       :released-count released-count
       :take-callback-on-caller? (identical? caller observed)
       :put-completed (deref put-completed 1000 :timed-out)})
    (finally (restore-and-release!))))

(defn pending-take-then-put []
  (try
    (scheme/call "jolt-regression-issue14-hold-forks!")
    (let [caller (Thread/currentThread)
          channel (async/chan)
          taken (promise)
          take-returned (async/take! channel #(deliver taken %) true)
          held-count (scheme/call
                      "jolt-regression-issue14-restore-forks!")
          put-callback-thread (promise)
          put-returned (async/put!
                        channel :value
                        (fn [_]
                          (deliver put-callback-thread
                                   (Thread/currentThread)))
                        true)
          released-count (scheme/call
                          "jolt-regression-issue14-release-forks!")
          observed (deref put-callback-thread 1000 :timed-out)]
      {:take-returned take-returned
       :put-returned put-returned
       :held-count held-count
       :released-count released-count
       :put-callback-on-caller? (identical? caller observed)
       :taken (deref taken 1000 :timed-out)})
    (finally (restore-and-release!))))

(let [actual {:put-then-take (pending-put-then-take)
              :take-then-put (pending-take-then-put)}
      expected {:put-then-take {:put-returned true
                                :take-returned nil
                                :held-count 1
                                :released-count 1
                                :take-callback-on-caller? true
                                :put-completed true}
                :take-then-put {:take-returned nil
                                :put-returned true
                                :held-count 1
                                :released-count 1
                                :put-callback-on-caller? true
                                :taken :value}}]
  (finish! (= expected actual)
           (pr-str {:expected expected :actual actual})))
