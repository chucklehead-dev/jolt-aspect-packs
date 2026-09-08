#!/usr/bin/env bash
set -euo pipefail

executable=${1:?usage: chdb-durable-crash.sh EXECUTABLE}
test_root=$(mktemp -d "${TMPDIR:-/tmp}/jchdb-aspect-crash.XXXXXX")
cleanup() {
  rm -rf -- "$test_root"
}
trap cleanup EXIT

run_cut() {
  local label=$1 operation=$2 phase=$3 expected=$4 expected_objects=$5
  local root="$test_root/$label" ready="$test_root/$label.ready"
  "$executable" "$root" produce "$operation" "$phase" "$ready" \
    >"$test_root/$label.out" 2>"$test_root/$label.err" &
  local producer=$!
  for _ in $(seq 1 200); do
    [[ -f $ready ]] && break
    kill -0 "$producer" 2>/dev/null || break
    sleep 0.05
  done
  if [[ ! -f $ready ]]; then
    echo "FAIL $label did not reach its aspect crash barrier" >&2
    wait "$producer" || true
    exit 1
  fi
  kill -9 "$producer"
  wait "$producer" 2>/dev/null || true

  local recovered
  recovered=$(timeout 15 "$executable" "$root" recover)
  if [[ $recovered != "$expected" ]]; then
    echo "FAIL $label recovered as $recovered" >&2
    exit 1
  fi
  local objects=0
  if [[ -d $root/objects/crash-object/wal ]]; then
    objects=$(find "$root/objects/crash-object/wal" -type f | wc -l)
  fi
  if [[ $objects -ne $expected_objects ]]; then
    echo "FAIL $label retained $objects WAL objects" >&2
    exit 1
  fi
  echo "ok $label $recovered objects=$objects"
}

run_cut before-upload durable/publish-wal before \
  '{:sequence 0, :wal-count 0, :replayed 0}' 0
run_cut after-upload durable/publish-wal after \
  '{:sequence 0, :wal-count 0, :replayed 0}' 1
run_cut after-head-cas durable/commit-reference after \
  '{:sequence 1, :wal-count 1, :replayed 1}' 1

echo "all Durable aspect crash cuts recovered correctly"
