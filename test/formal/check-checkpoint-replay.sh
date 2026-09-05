#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

bb -cp "$root/src" -m jolt.aspect-packs.formal-antivacuity \
  "$root/test/formal/checkpoint-replay-assessor.contract.edn"
