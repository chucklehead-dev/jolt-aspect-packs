#!/bin/sh
set -eu

if ! command -v z3 >/dev/null 2>&1; then
  echo "checkpoint replay formal gate requires z3" >&2
  exit 2
fi

spec=test/formal/checkpoint-replay-assessor.smt2
expected="unsat sat sat sat sat sat sat sat"
actual=$(z3 "$spec" | tr '\n' ' ' | sed 's/ $//')

if [ "$actual" != "$expected" ]; then
  echo "FAIL checkpoint replay assessor ($spec): expected '$expected', got '$actual'" >&2
  exit 1
fi

for name in \
  six-implementation-domain \
  twelve-scenario-bound \
  two-actor-domain \
  two-site-two-hit-observation-domain \
  three-action-domain \
  two-terminal-outcome-domain \
  global-sequence-integrity-only \
  exact-global-sequence-is-not-reference-equality \
  every-observed-site-registered \
  every-observed-action-declared-by-site \
  present-events-match-exact-selectors-and-actions \
  every-plan-entry-consumed-exactly-once \
  exact-selector-action-consumption-definition \
  barrier-completes-only-after-both-arrivals \
  actor-local-event-order-preserved \
  status-dependent-terminal-outcome-shapes \
  exact-terminal-error-fingerprint-equality \
  terminal-outcomes-match-by-actor \
  complete-replay-reference-predicate \
  bounded-executable-assessor-scenario-oracle \
  implementation-assessor-decision \
  shared-replay-assessment-disagreement \
  reference-replay-assessor-counterexample-query \
  ignore-hit-mutant-query \
  ignore-unconsumed-plan-mutant-query \
  ignore-outcome-mutant-query \
  ignore-barrier-dependency-mutant-query \
  exact-global-sequence-mutant-query \
  swapped-cross-actor-sequence-accepted-nonvacuity-query \
  real-actor-order-mismatch-rejected-nonvacuity-query
do
  if ! grep -q ":named $name" "$spec"; then
    echo "FAIL checkpoint replay assessor gate: missing named assertion $name" >&2
    exit 1
  fi
done

echo "PASS checkpoint replay assessor: $actual"
