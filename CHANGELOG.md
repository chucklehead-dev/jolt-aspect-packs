# Changelog

## Unreleased

- Requalify the core.async observation and fault pack against the pending-put
  ownership repair merged at `casselc/jolt@aa0e71f`: capacity-zero,
  capacity-one, callback, fiber, `alts!`, and transformed close/drain witnesses
  retain causal registration, FIFO value ownership, exactly-once completion,
  woven effect evidence, and plain-build erasure.

- Requalify the complete chDB Durable woven, plain-erasure, and crash/fault
  matrix on Jolt 0.8.6 with aspect compiler
  `120643d6bc322800a700e870de5c8087ad6085fa`, jolt-chdb merge
  `3552a2575a96e3c9dd7b495a9b16b1e9c3317eee`, and canonical DB provider
  `6db791634e5a4c65c24646833b2e82d3a5d7a121`. A focused provenance test keeps
  the root alias, all three scenario graphs, target ledger, and hosted gate on
  that one exact stack while retaining woven/plain and crash-cut controls.

- Correct the pending Jolt string witnesses: decompose the previously labelled
  empty loop into global, hoisted, function-argument, equality, and matching-init
  proven-long controls; exclude result validation from timing; record current
  emitted Scheme and run/AOT evidence; and reframe
  scalar-indexed astral strings as a portability hazard that the pinned owned
  `casselc/data.json` writer handles safely.

- Shape and validate jolt-chdb's redacted forced-live takeover warning without
  retaining raw advice arguments or changing proceed/CAS outcomes; causally
  complete traces reject suppression, duplication, and malformed warnings. The
  bounded hosted Durable job now enforces both the woven compiler evidence and
  plain-build erasure against the pinned target.

- Follow chDB Durable control operations through their option-bearing retry
  arities, preserving one history/fault event for both convenience-wrapper and
  writer-direct calls originally introduced at jolt-chdb `dbc2db2`; the current
  qualified target is the immutable SHA in `targets.edn`, with its target-owned
  `4a0b821` seam epoch.
