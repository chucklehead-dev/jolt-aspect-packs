;; Produce one real checkpoint snapshot through the compiler-owned controlled
;; lowering seam. Run from the root of the exact Jolt source revision.
(import (chezscheme))
(load "host/chez/gate-boot.ss")

(define (compile-controlled source)
  (let-values (((form next) (rdr-read-form source 0 (string-length source))))
    (let ((ctx (make-analyze-ctx "checkpoint.pack.producer"))
          (unit ((var-deref "jolt.passes.types" "new-unit"))))
      ((var-deref "jolt.checkpoints" "configure-unit!")
       unit (keyword #f "controlled"))
      ((var-deref "jolt.backend-scheme" "set-emit-unit!") unit)
      (jolt-ce-emit
       (jolt-ce-run-passes (jolt-ce-analyze ctx form) ctx unit)))))

(define (execute-emitted scheme)
  (eval (read (open-string-input-port scheme)) (interaction-environment)))

(define yield-expression #f)
(define barrier-expression #f)

(define (compile-fixture-expressions!)
  (set! yield-expression
        (compile-controlled
         "(jolt.checkpoints/checkpoint! :fixture/yield #{:continue :yield})"))
  (set! barrier-expression
        (compile-controlled
         "(jolt.checkpoints/checkpoint! :fixture/rendezvous #{:barrier :continue})")))

(define kw-version (keyword "jolt.checkpoint" "version"))
(define kw-plan (keyword "jolt.checkpoint" "plan"))
(define kw-barriers (keyword "jolt.checkpoint" "barriers"))
(define kw-yield (keyword #f "yield"))
(define kw-barrier (keyword #f "barrier"))

;; A worker publishes completion under a condition variable. The absolute
;; deadline is only a watchdog; successful synchronization is the published
;; done bit. The subsequent join may briefly wait for the worker lambda to
;; return, but no user thunk work remains; the outer process watchdog is the
;; final bound if the host fails to terminate the thread normally.
(define (bounded-worker name thunk)
  (let ((mu (make-mutex))
        (cv (make-condition))
        (done? #f)
        (value #f)
        (raised #f))
    (let ((thread
           (fork-thread
            (lambda ()
              (guard (e (#t (jolt-with-mutex mu
                               (set! raised e)
                               (set! done? #t)
                               (condition-broadcast cv))))
                (let ((v (thunk)))
                  (jolt-with-mutex mu
                    (set! value v)
                    (set! done? #t)
                    (condition-broadcast cv))))))))
      (vector name thread mu cv
              (lambda () done?)
              (lambda () value)
              (lambda () raised)))))

(define (bounded-worker-await-result worker timeout-ms)
  (let ((deadline (+ (now-millis) timeout-ms))
        (timed-out? #f))
    (jolt-with-mutex (vector-ref worker 2)
      (let loop ()
        (unless ((vector-ref worker 4))
          (if (>= (now-millis) deadline)
              (set! timed-out? #t)
              (begin
                (jolt-condition-wait (vector-ref worker 3)
                                     (vector-ref worker 2)
                                     (jolt-millis->time deadline))
                (loop))))))
    (if timed-out?
        (vector 'timeout #f)
        (begin
          ;; This result is returned only after join itself confirms thread
          ;; termination. Publication alone is not reported as reaped.
          (thread-join (vector-ref worker 1))
          (let ((raised ((vector-ref worker 6))))
            (if raised
                (vector 'joined-error raised)
                (vector 'joined-ok ((vector-ref worker 5)))))))))

(define (bounded-worker-await worker . timeout-ms)
  (let* ((timeout (if (null? timeout-ms) 10000 (car timeout-ms)))
         (result (bounded-worker-await-result worker timeout))
         (status (vector-ref result 0)))
    (cond ((eq? status 'timeout)
           (error 'checkpoint-runtime-producer
                  "worker watchdog expired"
                  (vector-ref worker 0)))
          ((eq? status 'joined-error) (raise (vector-ref result 1)))
          (else (vector-ref result 1)))))

;; A crashing worker is the adversarial teardown case. joined-error is produced
;; only after thread-join returns, so the marker cannot be printed merely
;; because completion was published or because a cleanup timeout was swallowed.
(define (run-crashed-worker)
  (let* ((worker
          (bounded-worker
           "crashed-worker"
           (lambda ()
             (error 'checkpoint-runtime-producer
                    "intentional worker crash"))))
         (result (bounded-worker-await-result worker 1000))
         (status (vector-ref result 0)))
    (cond ((eq? status 'timeout)
           (error 'checkpoint-runtime-producer
                  "crashed worker did not terminate before watchdog"))
          ((eq? status 'joined-ok)
           (error 'checkpoint-runtime-producer
                  "crashed worker unexpectedly returned"))
          (else
           (display "checkpoint crashed worker joined\n"
                    (current-error-port))
           (error 'checkpoint-runtime-producer
                  "intentional worker crash observed after join")))))

(define (run-once)
  (jolt-checkpoint-reset!)
  (jolt-checkpoint-register-site! "fixture/yield" '(continue yield))
  (jolt-checkpoint-register-site! "fixture/rendezvous" '(barrier continue))
  (jolt-checkpoint-install-plan!
   (jolt-hash-map
    kw-version 1
    kw-plan
    (jolt-hash-map
     (jolt-vector "actor/a" "fixture/yield" 1) kw-yield
     (jolt-vector "actor/a" "fixture/rendezvous" 1) kw-barrier
     (jolt-vector "actor/b" "fixture/rendezvous" 1) kw-barrier)
    kw-barriers
    (jolt-hash-map
     "fixture/round"
     (jolt-vector
      (jolt-vector "actor/a" "fixture/rendezvous" 1)
      (jolt-vector "actor/b" "fixture/rendezvous" 1)))))
  (let ((actor-a
         (bounded-worker
          "actor/a"
          (lambda ()
            (jolt-checkpoint-bind-actor! "actor/a")
            (execute-emitted yield-expression)
            (execute-emitted barrier-expression)
            ;; This cut is taken only after the barrier call returns. Its
            ;; journal therefore certifies how many real barrier events had
            ;; committed when this waiter was released.
            (jolt-checkpoint-snapshot))))
        (actor-b
         (bounded-worker
          "actor/b"
          (lambda ()
            (jolt-checkpoint-bind-actor! "actor/b")
            (execute-emitted barrier-expression)
            (jolt-checkpoint-snapshot)))))
    (let ((a-completion-cut (bounded-worker-await actor-a))
          (b-completion-cut (bounded-worker-await actor-b)))
      (jolt-vector (jolt-checkpoint-snapshot)
                   a-completion-cut
                   b-completion-cut))))

(let ((args (cdr (command-line))))
  (if (and (pair? args) (string=? "--crashed-worker" (car args)))
      (run-crashed-worker)
      (begin
        (compile-fixture-expressions!)
        (display ((var-deref "clojure.core" "pr-str")
                  (jolt-vector (run-once) (run-once))))
        (newline))))
