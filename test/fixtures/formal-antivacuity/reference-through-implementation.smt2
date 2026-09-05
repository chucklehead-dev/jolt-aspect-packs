(set-logic QF_LIA)
(declare-const implementation Int)
(declare-const scenario Int)
(declare-const reference-valid Bool)
(declare-const implementation-accept Bool)
(declare-const disagreement Bool)
(assert (and (<= 0 scenario) (<= scenario 1)))
(assert (= implementation-accept
  (ite (= implementation 0) (= scenario 0) true)))
; This alias shape is not rejected by the structural comparison because the
; reference sees an unresolved selector while the checked implementation does
; not. The mandatory mutant query still rejects it: both sides always agree.
(assert (= reference-valid implementation-accept))
(assert (= disagreement (not (= implementation-accept reference-valid))))
(push) (assert (= implementation 0)) (assert disagreement) (check-sat) (pop)
(push) (assert (= implementation 1)) (assert (= scenario 1))
(assert disagreement) (check-sat) (pop)
(push) (assert (= implementation 0)) (assert (= scenario 0))
(assert (and reference-valid implementation-accept)) (check-sat) (pop)
