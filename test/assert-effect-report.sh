#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
jolt=$1
report=$2
mode=$3
aspect_report=${4:-}

if [ -n "$aspect_report" ]; then
  "$jolt" -Srepro \
    -Sdeps "{:paths [\"$repo/test\"]}" \
    -m jolt.aspect-packs.effect-report-test "$report" "$mode" "$aspect_report"
else
  "$jolt" -Srepro \
    -Sdeps "{:paths [\"$repo/test\"]}" \
    -m jolt.aspect-packs.effect-report-test "$report" "$mode"
fi
