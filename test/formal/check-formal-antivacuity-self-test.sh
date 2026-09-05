#!/bin/sh
set -eu

gate="bb test/formal/formal_antivacuity.clj"
fixtures=test/fixtures/formal-antivacuity

pass_report=$(mktemp)
trap 'rm -f "$pass_report"' EXIT
$gate "$fixtures/independent.contract.edn" >"$pass_report"
grep -Fq "mutants=1 SAT, boundaries=1 SAT" "$pass_report" || {
  echo "FAIL formal anti-vacuity self-test: independent control counts drifted" >&2
  cat "$pass_report" >&2
  exit 1
}
rm -f "$pass_report"
trap - EXIT

for fixture in direct-alias renamed-duplicate indirect-renamed-alias shared-helper reference-through-implementation missing-mutants missing-boundaries missing-boundary-classification vacuous-boundary; do
  report=$(mktemp)
  trap 'rm -f "$report"' EXIT
  if $gate "$fixtures/$fixture.contract.edn" >"$report" 2>&1; then
    echo "FAIL formal anti-vacuity self-test: $fixture unexpectedly passed" >&2
    exit 1
  fi
  grep -Eq "depends on the reference predicate|share derived definitions|definitional alias|at least one mutant SAT control is required|mutant disagreement query must occur once and be SAT|at least one boundary/non-vacuity SAT control is required|boundary controls require an integer case and boolean accept\? classification|boundary query must explicitly classify both reference and implementation" "$report" || {
    echo "FAIL formal anti-vacuity self-test: $fixture failed for the wrong reason" >&2
    cat "$report" >&2
    exit 1
  }
  rm -f "$report"
  trap - EXIT
done

echo "PASS formal anti-vacuity self-test: independent accepted; aliases, shared helpers, missing controls, and vacuous boundaries rejected"
