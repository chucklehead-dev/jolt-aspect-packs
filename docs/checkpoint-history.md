# Checkpoint history evidence

The checkpoint-capable Jolt runtime publishes a persistent snapshot through
`jolt.host/checkpoint-snapshot`. The packs repository treats that runtime value
as an evidence input, not as the portable regression itself.

`jolt.aspect-packs.checkpoint-history/normalize` validates the registered sites,
inert plan, global sequence, per-actor/site hit sequence, and plan/action
agreement. Legacy flat continue-only snapshots normalize to schema 1.
Versioned action snapshots normalize to schema 2 and additionally validate the
generation, complete disposition vocabulary, canonical exact-actor barrier
groups, unique selector ownership, and site capability agreement.
`replay-manifest` reconstructs the inert install value from exact canonical
evidence and rejects mutated evidence. `capture-runtime` is the optional
Jolt-only adapter; its host symbol is resolved dynamically, so normalization
remains loadable on older Jolt, Babashka, and JVM Clojure.

`jolt.aspect-packs.checkpoint-replay` validates a constrained replay case and
assesses a completed run. A case pins exact site capabilities, the inert
manifest, actor-local expected event order, terminal outcome fingerprints, an
explicit unplanned-event policy, and controlled-build provenance. Assessment
returns `:reproduced`, `:mismatch`, or `:unresolved`; a timeout is never treated
as a reproduced failure.

Cases and runs are fail-closed inert data. Site ids must be unique before they
are converted to a map. Controlled provenance contains exactly a profile and a
full 40-hex Git revision. Successful outcomes contain only `:status`; error
outcomes additionally require a nonempty stable `:fingerprint`. Every run also
contains a barrier completion certificate: the exact planned arrivals and the
arrival count observed when the earliest waiter returned. A barrier that
returns after only one of two planned arrivals is therefore a mismatch even if
the final checkpoint journal eventually contains both events.

Replay identity is `[actor checkpoint-id per-actor/site-hit]`. Global `:seq` is
validated as a unique contiguous journal order, but two valid cross-actor
interleavings may have different sequence assignments. Exact-actor barriers
replay rendezvous constraints; they do not impose an order on arrivals inside a
round. This pack therefore does not claim total scheduler or global-order
replay.

For a minimized failure, archive the full normalized evidence and copy the
result of `portable-observations` into an ordinary regression test. That vector
contains only `:actor`, `:id`, `:hit`, and inert `:action` values. The regression
must still call the affected public API and assert its user-visible result; a
checkpoint trace is evidence about the execution that found the defect, not a
replacement oracle. The resulting regression therefore does not require Jolt's
checkpoint runtime, the aspect compiler, or Hegel.

Run the portable unit suite normally:

```sh
make test
```

`make test` is the aggregate portable gate: it first runs the bounded Z3 model
and its reference/mutant/non-vacuity controls, then the Clojure test runner. A
3x4x2x2 differential product invokes the real assessor and compares it with an
independent status oracle, including exact error-fingerprint behavior. Z3 must
be installed. To run only the formal check, use `make checkpoint-replay-proof`.

Run the adapter contract against an explicitly selected checkpoint-capable
binary:

```sh
make checkpoint-runtime-history \
  JOLT_CHECKPOINT_JOLT=/absolute/path/to/jolt \
  JOLT_CHECKPOINT_SOURCE=/absolute/path/to/that/exact/jolt/source
```

Run this command through the workspace's pinned-Chez wrapper. The producer uses
the compiler-owned controlled lowering seam to execute planned and unplanned
events, then the selected Jolt binary consumes the emitted snapshot through the
portable pack boundary. The target fails closed unless both paths are absolute,
the source has no tracked changes, the binary version is not dirty, and its
embedded short revision matches the source `HEAD`. The live producer and
portable consumer each have a process deadline; producer workers publish
completion through a condition variable before they are joined. A join can
still briefly wait for the worker lambda to return, so the process deadline is
the final bound. An adversarial worker deliberately crashes, and the fixture
requires an explicit `joined-error` result before printing its teardown marker.
A wait timeout has a distinct result, prints no marker, and fails the gate. The
fixture must exit nonzero before the 15-second outer process deadline, which
also includes loading the clean Jolt gate image.
Each successful worker takes a journal cut only after its barrier call returns,
so the earliest cut certifies the number of real barrier events committed at
release.

The run records the clean source checkout's full 40-hex `HEAD`. The current
binary/source binding verifies only the binary version's embedded 8-hex suffix
against that checkout. It is therefore exact source provenance plus a short
binary binding, not a full-SHA binary attestation or gate receipt.

The process watchdog defaults to GNU `timeout`; set
`CHECKPOINT_TIMEOUT=gtimeout` on systems that install GNU coreutils under the
prefixed command name.

Controlled checkpoint emission remains an internal compiler/test seam. This
slice does not add a public CLI switch or expose the runtime's private hit leaf.
The reconstructed manifest replays selector/action and barrier constraints
already supported by the runtime; explicit build-profile plumbing and
schedule minimization are separate follow-ups.
