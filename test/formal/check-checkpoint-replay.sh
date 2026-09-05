#!/bin/sh
set -eu

bb test/formal/formal_antivacuity.clj \
  test/formal/checkpoint-replay-assessor.contract.edn
