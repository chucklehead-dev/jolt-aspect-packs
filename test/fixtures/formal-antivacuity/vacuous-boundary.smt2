(set-logic QF_LIA)
(declare-const implementation Int)
(declare-const scenario Int)
(declare-const reference-valid Bool)
(declare-const separately-encoded-oracle Bool)
(declare-const implementation-accept Bool)
(declare-const disagreement Bool)
(assert (and (<= 0 scenario) (<= scenario 1)))
(assert (= reference-valid (= scenario 0)))
(assert (= separately-encoded-oracle (not (= scenario 1))))
(assert (= implementation-accept
  (ite (= implementation 0) separately-encoded-oracle true)))
(assert (= disagreement (not (= implementation-accept reference-valid))))
(push) (assert (= implementation 0)) (assert disagreement) (check-sat) (pop)
(push) (assert (= implementation 1)) (assert (= scenario 1))
(assert disagreement) (check-sat) (pop)
; Deliberately vacuous: the selectors are reachable, but the query never states
; whether the reference and checked implementation accept this boundary.
(push) (assert (= implementation 0)) (assert (= scenario 0)) (check-sat) (pop)
