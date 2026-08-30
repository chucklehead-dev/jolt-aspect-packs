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

(jolt-checkpoint-reset!)
(jolt-checkpoint-register-site! "fixture/receive" '(continue))
(jolt-checkpoint-install-plan!
 (jolt-hash-map (jolt-vector "actor/b" "fixture/receive" 1)
                (keyword #f "continue")))

(define checkpoint-expression
  (compile-controlled
   "(jolt.checkpoints/checkpoint! :fixture/receive #{:continue})"))

(jolt-checkpoint-bind-actor! "actor/a")
(execute-emitted checkpoint-expression)
(execute-emitted checkpoint-expression)
(jolt-checkpoint-bind-actor! "actor/b")
(execute-emitted checkpoint-expression)

(display ((var-deref "clojure.core" "pr-str") (jolt-checkpoint-snapshot)))
(newline)
