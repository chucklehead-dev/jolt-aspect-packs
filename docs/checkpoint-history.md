# Checkpoint history evidence

The checkpoint-capable Jolt runtime publishes a persistent snapshot through
`jolt.host/checkpoint-snapshot`. The packs repository treats that runtime value
as an evidence input, not as the portable regression itself.

`jolt.aspect-packs.checkpoint-history/normalize` validates the registered sites,
inert plan, global sequence, per-actor/site hit sequence, and plan/action
agreement. It returns schema-1 canonical data containing sorted site and plan
vectors plus the ordered events. `capture-runtime` is the optional Jolt-only
adapter; its host symbol is resolved dynamically, so the normalization and
extraction namespace remains loadable on older Jolt, Babashka, and JVM Clojure.

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
embedded short revision matches the source `HEAD`.

Controlled checkpoint emission remains an internal compiler/test seam. This
slice does not add a public CLI switch, expose the runtime's private hit leaf,
or attempt to reconstruct a runnable schedule from record-only events.
