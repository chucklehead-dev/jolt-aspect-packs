; Bounded checkpoint replay-assessor semantics.
;
; The completed replay design requires an assessment to accept exactly when:
; every event names a registered site and an action declared by that site;
; every planned [actor,site,hit] selector and action is consumed exactly once;
; the two-party barrier completes only after both planned arrivals; each actor's
; event order is preserved; and both actor terminal outcomes match.  Global
; sequence numbers are integrity metadata (a contiguous unique permutation),
; not an exact replay-order oracle across independent actors.
;
; Domain: actors {A=0,B=1}, sites {work=0,barrier=1}, hits {1,2}, actions
; {continue=0,barrier=1,yield=2}, four planned events (two per actor), one
; two-party barrier, outcome statuses {ok=0,error=1,unresolved=2}, error
; fingerprints {7,8}, scenarios {0..11}, and
; implementations {0..5}.  The four plan entries are A/work/1/continue,
; B/work/1/continue, A/barrier/1/barrier, and B/barrier/1/barrier.
;
; Scenarios: 0 canonical valid replay; 1 valid cross-actor global permutation;
; 2 wrong hit; 3 one unconsumed non-barrier plan entry; 4 terminal-outcome
; mismatch; 5 the barrier completion certificate reports that the earliest
; waiter returned after one arrival; 6 actor-local order mismatch;
; 7 unregistered site; 8 undeclared capability; 9 wrong but declared action;
; 10 wrong error fingerprint; 11 missing required error fingerprint.
; Implementations: 0 reference; 1 ignores hit; 2 ignores an unconsumed plan
; entry; 3 ignores terminal outcomes; 4 ignores barrier dependency; 5 requires
; exact global sequence equality.
;
; The model omits scheduler fairness, arbitrary trace length, payload values,
; multiple barriers, minimization, and runtime installation.  Expected checks:
; one reference UNSAT, five mutant SAT, and two non-vacuity SAT.
(set-logic QF_LIA)

(declare-const implementation Int)
(declare-const scenario Int)

; Presence and concrete observed event fields for A0, B0, A1, B1.
(declare-const e0-present Bool)
(declare-const e1-present Bool)
(declare-const e2-present Bool)
(declare-const e3-present Bool)
(declare-const e0-actor Int)
(declare-const e1-actor Int)
(declare-const e2-actor Int)
(declare-const e3-actor Int)
(declare-const e0-site Int)
(declare-const e1-site Int)
(declare-const e2-site Int)
(declare-const e3-site Int)
(declare-const e0-hit Int)
(declare-const e1-hit Int)
(declare-const e2-hit Int)
(declare-const e3-hit Int)
(declare-const e0-action Int)
(declare-const e1-action Int)
(declare-const e2-action Int)
(declare-const e3-action Int)
(declare-const e0-seq Int)
(declare-const e1-seq Int)
(declare-const e2-seq Int)
(declare-const e3-seq Int)
(declare-const event-count Int)

; Barrier and actor-terminal observations.
(declare-const barrier-arrival-count Int)
(declare-const barrier-complete Bool)
(declare-const barrier-complete-after-arrivals Int)
(declare-const expected-a-outcome Int)
(declare-const expected-b-outcome Int)
(declare-const actual-a-outcome Int)
(declare-const actual-b-outcome Int)
(declare-const expected-b-fingerprint Int)
(declare-const actual-b-fingerprint Int)
(declare-const expected-b-fingerprint-present Bool)
(declare-const actual-b-fingerprint-present Bool)

; Fully derived reference clauses and the implementation result.
(declare-const global-sequence-integrity Bool)
(declare-const exact-global-sequence Bool)
(declare-const sites-registered Bool)
(declare-const capabilities-declared Bool)
(declare-const present-selectors-actions-exact Bool)
(declare-const present-selectors-actions-without-hit Bool)
(declare-const all-plan-entries-consumed Bool)
(declare-const exact-selector-action-consumption Bool)
(declare-const ignore-hit-consumption Bool)
(declare-const ignore-unconsumed-plan-consumption Bool)
(declare-const barrier-dependency-satisfied Bool)
(declare-const actor-local-order-preserved Bool)
(declare-const terminal-outcomes-match Bool)
(declare-const terminal-outcome-shapes-valid Bool)
(declare-const terminal-error-fingerprints-match Bool)
(declare-const replay-reference-valid Bool)
(declare-const bounded-executable-assessor-accept Bool)
(declare-const implementation-accept Bool)
(declare-const replay-assessment-violation Bool)

(assert (! (and (<= 0 implementation) (<= implementation 5))
  :named six-implementation-domain))
(assert (! (and (<= 0 scenario) (<= scenario 11))
  :named twelve-scenario-bound))
(assert (! (and (<= 0 e0-actor) (<= e0-actor 1)
                (<= 0 e1-actor) (<= e1-actor 1)
                (<= 0 e2-actor) (<= e2-actor 1)
                (<= 0 e3-actor) (<= e3-actor 1))
  :named two-actor-domain))
(assert (! (and (<= 0 e0-site) (<= e0-site 2)
                (<= 0 e1-site) (<= e1-site 2)
                (<= 0 e2-site) (<= e2-site 2)
                (<= 0 e3-site) (<= e3-site 2)
                (<= 1 e0-hit) (<= e0-hit 2)
                (<= 1 e1-hit) (<= e1-hit 2)
                (<= 1 e2-hit) (<= e2-hit 2)
                (<= 1 e3-hit) (<= e3-hit 2))
  :named two-site-two-hit-observation-domain))
(assert (! (and (<= 0 e0-action) (<= e0-action 2)
                (<= 0 e1-action) (<= e1-action 2)
                (<= 0 e2-action) (<= e2-action 2)
                (<= 0 e3-action) (<= e3-action 2))
  :named three-action-domain))
(assert (! (and (<= 0 actual-a-outcome) (<= actual-a-outcome 2)
                (<= 0 actual-b-outcome) (<= actual-b-outcome 2))
  :named two-terminal-outcome-domain))

; Scenario-derived observations. Scenario 3 omits B's work event; every other
; scenario observes all four planned occurrences.
(assert (= e0-present true))
(assert (= e1-present (not (= scenario 3))))
(assert (= e2-present true))
(assert (= e3-present true))
(assert (= event-count (+ (ite e0-present 1 0) (ite e1-present 1 0)
                          (ite e2-present 1 0) (ite e3-present 1 0))))

(assert (= e0-actor 0))
(assert (= e1-actor 1))
(assert (= e2-actor 0))
(assert (= e3-actor 1))
(assert (= e0-site (ite (= scenario 7) 2 0)))
(assert (= e1-site 0))
(assert (= e2-site 1))
(assert (= e3-site 1))
(assert (= e0-hit (ite (= scenario 2) 2 1)))
(assert (= e1-hit 1))
(assert (= e2-hit 1))
(assert (= e3-hit 1))
(assert (= e0-action (ite (= scenario 9) 2 0)))
(assert (= e1-action 0))
(assert (= e2-action 1))
(assert (= e3-action 1))

; Canonical order is A0,B0,A1,B1. Scenario 1 swaps independent actors while
; preserving A0<A1 and B0<B1. Scenario 6 is a real A1<A0 order mismatch.
(assert (= e0-seq
  (ite (= scenario 1) 2
    (ite (= scenario 6) 3 1))))
(assert (= e1-seq
  (ite (= scenario 1) 1
    (ite (= scenario 3) 0 2))))
(assert (= e2-seq
  (ite (= scenario 1) 4
    (ite (= scenario 3) 2
      (ite (= scenario 6) 1 3)))))
(assert (= e3-seq
  (ite (= scenario 1) 3
    (ite (= scenario 3) 3 4))))

; Sequence integrity requires precisely the contiguous set 1..event-count for
; present events, and zero for absent slots. It deliberately does not require
; equality with the recorded cross-actor sequence.
(assert (! (= global-sequence-integrity
  (and (= e0-present (and (<= 1 e0-seq) (<= e0-seq event-count)))
       (= e1-present (and (<= 1 e1-seq) (<= e1-seq event-count)))
       (= e2-present (and (<= 1 e2-seq) (<= e2-seq event-count)))
       (= e3-present (and (<= 1 e3-seq) (<= e3-seq event-count)))
       (or (not e0-present) (not e1-present) (not (= e0-seq e1-seq)))
       (or (not e0-present) (not e2-present) (not (= e0-seq e2-seq)))
       (or (not e0-present) (not e3-present) (not (= e0-seq e3-seq)))
       (or (not e1-present) (not e2-present) (not (= e1-seq e2-seq)))
       (or (not e1-present) (not e3-present) (not (= e1-seq e3-seq)))
       (or (not e2-present) (not e3-present) (not (= e2-seq e3-seq)))
       (= (not e0-present) (= e0-seq 0))
       (= (not e1-present) (= e1-seq 0))
       (= (not e2-present) (= e2-seq 0))
       (= (not e3-present) (= e3-seq 0))))
  :named global-sequence-integrity-only))
(assert (! (= exact-global-sequence
  (and e0-present e1-present e2-present e3-present
       (= e0-seq 1) (= e1-seq 2) (= e2-seq 3) (= e3-seq 4)))
  :named exact-global-sequence-is-not-reference-equality))

; Registered sites are 0 and 1. Work site 0 declares continue and yield;
; barrier site 1 declares barrier. Scenario 8 removes work/continue capability.
(assert (! (= sites-registered
  (and (=> e0-present (or (= e0-site 0) (= e0-site 1)))
       (=> e1-present (or (= e1-site 0) (= e1-site 1)))
       (=> e2-present (or (= e2-site 0) (= e2-site 1)))
       (=> e3-present (or (= e3-site 0) (= e3-site 1)))))
  :named every-observed-site-registered))
(assert (! (= capabilities-declared
  (and
   (=> e0-present
       (or (and (= e0-site 0)
                (or (= e0-action 2)
                    (and (= e0-action 0) (not (= scenario 8)))))
           (and (= e0-site 1) (= e0-action 1))))
   (=> e1-present
       (or (and (= e1-site 0)
                (or (= e1-action 2)
                    (and (= e1-action 0) (not (= scenario 8)))))
           (and (= e1-site 1) (= e1-action 1))))
   (=> e2-present
       (or (and (= e2-site 0)
                (or (= e2-action 2)
                    (and (= e2-action 0) (not (= scenario 8)))))
           (and (= e2-site 1) (= e2-action 1))))
   (=> e3-present
       (or (and (= e3-site 0)
                (or (= e3-action 2)
                    (and (= e3-action 0) (not (= scenario 8)))))
           (and (= e3-site 1) (= e3-action 1))))))
  :named every-observed-action-declared-by-site))

; Exact consumption checks actor, site, hit, and action for every present event,
; then separately requires all four plan entries to have been consumed.
(assert (! (= present-selectors-actions-exact
  (and (=> e0-present (and (= e0-actor 0) (= e0-site 0)
                            (= e0-hit 1) (= e0-action 0)))
       (=> e1-present (and (= e1-actor 1) (= e1-site 0)
                            (= e1-hit 1) (= e1-action 0)))
       (=> e2-present (and (= e2-actor 0) (= e2-site 1)
                            (= e2-hit 1) (= e2-action 1)))
       (=> e3-present (and (= e3-actor 1) (= e3-site 1)
                            (= e3-hit 1) (= e3-action 1)))))
  :named present-events-match-exact-selectors-and-actions))
(assert (! (= present-selectors-actions-without-hit
  (and (=> e0-present (and (= e0-actor 0) (= e0-site 0) (= e0-action 0)))
       (=> e1-present (and (= e1-actor 1) (= e1-site 0) (= e1-action 0)))
       (=> e2-present (and (= e2-actor 0) (= e2-site 1) (= e2-action 1)))
       (=> e3-present (and (= e3-actor 1) (= e3-site 1) (= e3-action 1)))))
  :named mutant-selector-match-without-hit))
(assert (! (= all-plan-entries-consumed
  (and e0-present e1-present e2-present e3-present))
  :named every-plan-entry-consumed-exactly-once))
(assert (! (= exact-selector-action-consumption
  (and all-plan-entries-consumed present-selectors-actions-exact))
  :named exact-selector-action-consumption-definition))
(assert (= ignore-hit-consumption
  (and all-plan-entries-consumed present-selectors-actions-without-hit)))
(assert (= ignore-unconsumed-plan-consumption present-selectors-actions-exact))

; Both barrier selectors are eventually observed. The executable run contract
; separately carries the count observed when its earliest waiter returned.
; Reference completion depends on both arrivals; scenario 5 reports an early
; waiter return after only one.
(assert (= barrier-arrival-count
  (+ (ite (and e2-present (= e2-actor 0) (= e2-site 1)
                   (= e2-hit 1) (= e2-action 1)) 1 0)
     (ite (and e3-present (= e3-actor 1) (= e3-site 1)
                   (= e3-hit 1) (= e3-action 1)) 1 0))))
(assert (= barrier-complete true))
(assert (= barrier-complete-after-arrivals
  (ite (= scenario 5) 1 barrier-arrival-count)))
(assert (! (= barrier-dependency-satisfied
  (and (= barrier-arrival-count 2)
       barrier-complete
       (= barrier-complete-after-arrivals 2)))
  :named barrier-completes-only-after-both-arrivals))

; Actor order is a partial order over that actor's own present events.
(assert (! (= actor-local-order-preserved
  (and (=> (and e0-present e2-present) (< e0-seq e2-seq))
       (=> (and e1-present e3-present) (< e1-seq e3-seq))))
  :named actor-local-event-order-preserved))

(assert (= expected-a-outcome 0))
(assert (= expected-b-outcome 1))
(assert (= actual-a-outcome 0))
(assert (= actual-b-outcome (ite (= scenario 4) 2 1)))
(assert (= expected-b-fingerprint 7))
(assert (= actual-b-fingerprint (ite (= scenario 10) 8 7)))
(assert (= expected-b-fingerprint-present true))
(assert (= actual-b-fingerprint-present
  (not (or (= scenario 4) (= scenario 11)))))
(assert (! (= terminal-outcome-shapes-valid
  (and
    ; :ok has exactly :status; :error has exactly status plus nonempty fp.
    (= expected-a-outcome 0)
    (= actual-a-outcome 0)
    expected-b-fingerprint-present
    (> expected-b-fingerprint 0)
    (or (and (= actual-b-outcome 2)
             (not actual-b-fingerprint-present))
        (and (= actual-b-outcome 1)
             actual-b-fingerprint-present
             (> actual-b-fingerprint 0)))))
  :named status-dependent-terminal-outcome-shapes))
(assert (! (= terminal-error-fingerprints-match
  (and expected-b-fingerprint-present
       actual-b-fingerprint-present
       (= expected-b-fingerprint actual-b-fingerprint)))
  :named exact-terminal-error-fingerprint-equality))
(assert (! (= terminal-outcomes-match
  (and (= actual-a-outcome expected-a-outcome)
       (= actual-b-outcome expected-b-outcome)
       terminal-outcome-shapes-valid
       terminal-error-fingerprints-match))
  :named terminal-outcomes-match-by-actor))

; The reference is an exact conjunction. Each mutant changes one clause only.
(assert (! (= replay-reference-valid
  (and global-sequence-integrity
       sites-registered
       capabilities-declared
       exact-selector-action-consumption
       barrier-dependency-satisfied
       actor-local-order-preserved
       terminal-outcomes-match))
  :named complete-replay-reference-predicate))

; Independent bounded executable oracle. For the concrete scenario table, the
; actual public assessor is expected to reproduce only the canonical replay and
; the permitted cross-actor permutation. The Clojure differential product test
; exercises the real function over a larger 3x4x2x2 domain; this table keeps the
; SMT implementation side independent of replay-reference-valid's decomposition.
(assert (! (= bounded-executable-assessor-accept
  (or (= scenario 0) (= scenario 1)))
  :named bounded-executable-assessor-scenario-oracle))
(assert (! (= implementation-accept
  (ite (= implementation 0)
       bounded-executable-assessor-accept
    (ite (= implementation 1)
         (and global-sequence-integrity sites-registered capabilities-declared
              ignore-hit-consumption barrier-dependency-satisfied
              actor-local-order-preserved terminal-outcomes-match)
      (ite (= implementation 2)
           (and global-sequence-integrity sites-registered capabilities-declared
                ignore-unconsumed-plan-consumption barrier-dependency-satisfied
                actor-local-order-preserved terminal-outcomes-match)
       (ite (= implementation 3)
            (and global-sequence-integrity sites-registered capabilities-declared
                 exact-selector-action-consumption barrier-dependency-satisfied
                 actor-local-order-preserved)
        (ite (= implementation 4)
             (and global-sequence-integrity sites-registered capabilities-declared
                  exact-selector-action-consumption actor-local-order-preserved
                  terminal-outcomes-match)
             (and replay-reference-valid exact-global-sequence)))))))
  :named implementation-assessor-decision))

; Shared disagreement query: SAT is either false acceptance of a real mismatch
; or false rejection of a permitted replay.
(assert (! (= replay-assessment-violation
  (not (= implementation-accept replay-reference-valid)))
  :named shared-replay-assessment-disagreement))

; Reference: no bounded disagreement with the complete predicate exists.
(push)
(assert (= implementation 0))
(assert (! replay-assessment-violation
  :named reference-replay-assessor-counterexample-query))
(check-sat)
(pop)

; Known-SAT mutations, all through the same disagreement query.
(push)
(assert (= implementation 1)) (assert (= scenario 2))
(assert (! replay-assessment-violation :named ignore-hit-mutant-query))
(check-sat) (pop)
(push)
(assert (= implementation 2)) (assert (= scenario 3))
(assert (! replay-assessment-violation :named ignore-unconsumed-plan-mutant-query))
(check-sat) (pop)
(push)
(assert (= implementation 3)) (assert (= scenario 4))
(assert (! replay-assessment-violation :named ignore-outcome-mutant-query))
(check-sat) (pop)
(push)
(assert (= implementation 4)) (assert (= scenario 5))
(assert (! replay-assessment-violation :named ignore-barrier-dependency-mutant-query))
(check-sat) (pop)
(push)
(assert (= implementation 5)) (assert (= scenario 1))
(assert (! replay-assessment-violation :named exact-global-sequence-mutant-query))
(check-sat) (pop)

; Non-vacuity: a cross-actor global permutation with both actor-local orders
; intact is accepted by the reference and is not exact-global equality.
(push)
(assert (= implementation 0)) (assert (= scenario 1))
(assert (! (and replay-reference-valid implementation-accept
                global-sequence-integrity actor-local-order-preserved
                (not exact-global-sequence))
  :named swapped-cross-actor-sequence-accepted-nonvacuity-query))
(check-sat) (pop)

; Non-vacuity: a concrete actor-local order reversal retains valid global
; sequence integrity but is rejected by the reference.
(push)
(assert (= implementation 0)) (assert (= scenario 6))
(assert (! (and global-sequence-integrity
                (not actor-local-order-preserved)
                (not replay-reference-valid)
                (not implementation-accept))
  :named real-actor-order-mismatch-rejected-nonvacuity-query))
(check-sat) (pop)
