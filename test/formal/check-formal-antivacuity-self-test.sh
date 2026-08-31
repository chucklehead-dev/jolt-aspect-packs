#!/bin/sh
set -eu

gate="bb test/formal/formal_antivacuity.clj"
fixtures=test/fixtures/formal-antivacuity

$gate "$fixtures/independent.contract.edn" >/dev/null

for fixture in direct-alias renamed-duplicate indirect-renamed-alias missing-mutants missing-boundaries; do
  report=$(mktemp)
  trap 'rm -f "$report"' EXIT
  if $gate "$fixtures/$fixture.contract.edn" >"$report" 2>&1; then
    echo "FAIL formal anti-vacuity self-test: $fixture unexpectedly passed" >&2
    exit 1
  fi
  grep -Eq "depends on the reference predicate|definitional alias|at least one mutant SAT control is required|at least one boundary/non-vacuity SAT control is required" "$report" || {
    echo "FAIL formal anti-vacuity self-test: $fixture failed for the wrong reason" >&2
    cat "$report" >&2
    exit 1
  }
  rm -f "$report"
  trap - EXIT
done

echo "PASS formal anti-vacuity self-test: independent accepted; direct, renamed, and indirect aliases plus missing semantic controls rejected"
