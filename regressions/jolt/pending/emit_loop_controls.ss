(import (chezscheme))
(load "host/chez/gate-boot.ss")

;; Run from an exact Jolt source checkout. This uses the same analyze, numeric
;; pass, and Scheme emitter path as Jolt's numeric compiler tests.
(define (emitf str)
  (let-values (((form next)
                (rdr-read-form str 0 (string-length str))))
    (let ((ctx (make-analyze-ctx "user")))
      (jolt-ce-emit (jolt-ce-run-passes (jolt-ce-analyze ctx form) ctx)))))

(define (contains? s part)
  (let ((limit (- (string-length s) (string-length part))))
    (let loop ((i 0))
      (cond ((> i limit) #f)
            ((string=? part (substring s i (+ i (string-length part)))) #t)
            (else (loop (+ i 1)))))))

(define failures 0)
(define (check label emitted required)
  (printf "=== ~a ===\n~a\n" label emitted)
  (for-each
    (lambda (part)
      (unless (contains? emitted part)
        (set! failures (+ failures 1))
        (printf "MISSING ~a: ~s\n" label part)))
    required))

(check "global bound, ==, unchecked-inc"
       (emitf "(fn* ([] (loop [i 0] (if (== i user/global-limit) i (recur (unchecked-inc i))))))")
       '("(let loop" "host-static-ref \"user\" \"global-limit\""
         "jolt-invoke2" "jolt-uncinc"))
(check "hoisted global bound, ==, unchecked-inc"
       (emitf "(fn* ([] (let [limit user/global-limit] (loop [i 0] (if (== i limit) i (recur (unchecked-inc i)))))))")
       '("host-static-ref \"user\" \"global-limit\"" "(let loop"
         "jolt-invoke2" "jolt-uncinc"))
(check "argument bound, ==, unchecked-inc"
       (emitf "(fn* ([limit] (loop [i 0] (if (== i limit) i (recur (unchecked-inc i))))))")
       '("(let loop" "jolt-invoke2" "jolt-uncinc"))
(check "argument bound, =, unchecked-inc"
       (emitf "(fn* ([limit] (loop [i 0] (if (= i limit) i (recur (unchecked-inc i))))))")
       '("(let loop" "jolt=2" "jolt-uncinc"))
(check "proven long bound, ==, unchecked-inc"
       (emitf "(fn* ([^long limit] (loop [i 0] (if (== i limit) i (recur (unchecked-inc i))))))")
       '("(let loop" "jolt-l=" "jolt-uncinc"))
(check "proven long bound, ==, inc"
       (emitf "(fn* ([^long limit] (loop [i 0] (if (== i limit) i (recur (inc i))))))")
       '("(let loop" "jolt-l=" "jolt-l-inc"))

(if (= failures 0)
  (printf "EMISSION PASS loop controls\n")
  (begin
    (printf "EMISSION FAIL loop controls: ~a missing fragments\n" failures)
    (exit 1)))
