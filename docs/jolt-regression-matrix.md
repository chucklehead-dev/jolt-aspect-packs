# Portable Jolt regression matrix

The regression matrix runs ordinary, maintainer-readable Clojure programs
against two explicit Jolt executables. It never changes a checkout or branch.
The case programs have no dependency on the aspect compiler, jolt-hegel, this
repository's source namespaces, or a test framework. Cases normally use public
APIs. Issue #5 deliberately uses Jolt's exposed `__promise-buffer` boundary to
isolate fulfilled-buffer stepping from issue #4's constructor defect; its
catalog provenance labels that internal boundary explicitly.

Set both binaries to absolute paths and run:

```sh
JOLT_UNFIXED=/absolute/path/to/upstream-jolt \
JOLT_FIXED=/absolute/path/to/fixed-jolt \
make jolt-regression-matrix
```

The runner gives every case and binary a fresh `HOME`, temporary directory,
XDG directories, Jolt cache, and gate-build directory. `/usr/bin/timeout`
bounds each child. Standard output is one EDN report suitable for archiving or
diffing.

For the `:unfixed` binary, a violated property with the declared failure
signature is `:fail` and is the expected historical result. A passing property
is `:xpass`: upstream may have incorporated the fix and the issue should be
rechecked. For the `:fixed` binary, only a zero exit with the declared pass
signature is `:pass`. Timeouts, missing signatures, unexpected exits, missing
scripts, malformed catalog entries, and invalid executables fail closed.

Each entry in `regressions/jolt/cases.edn` records the central issue, exact
historical bad and fixed commits, timeout and signatures, plus how the witness
relates to aspects, Hegel, or a checker. Provenance does not introduce a runtime
dependency.

## Initial cases

- #23 checks that `AtomicReference.compareAndSet` uses reference identity and
  retains a value-CAS control for primitive atomics.
- #31 checks that `Files.createFile` raises on an existing file without
  truncating its sentinel, while the fresh-file boundary succeeds.
- #32 checks that `Files.createDirectories` is idempotent for a directory but
  rejects and preserves an existing regular file. The full deterministic
  two-creator race remains in Jolt's native gate; this is its minimized public
  boundary witness.
- #36 checks Java-width wraparound at both ends of `AtomicInteger` and
  `AtomicLong`, with ordinary in-range addition as a positive control.

The #34 tagged-method reproducer currently relies on
`clojure.core/__register-class-methods!`, so it is not yet presented as a public
maintainer-facing case. The #35 numeric-delta fix moves error construction
outside an internal counted lock; the stable public outcome is intentionally
unchanged. Both remain covered by their Jolt-native gates rather than by a
vacuous portable claim.

Run the runner's controlled positive and fail-closed checks with:

```sh
make jolt-regression-matrix-self-test
```

## Fork-fixed coverage debt

`regressions/jolt/fork-fixed-coverage.edn` is the checked snapshot of open
issues labeled `status:fixed-in-fork`. Every issue must either have at least
one independently runnable matrix case or one issue-tagged `:known-missing`
row naming the source evidence that still needs extraction. Schema 2 permits
that evidence to live in any canonical GitHub `owner/repository` coordinate,
so library fixes are tracked without pretending they belong to Jolt core. The
offline gate rejects duplicate or unsorted issues, malformed debt, stale debt after a case
is added, and debt removed before a case exists. A URL alone is never counted
as coverage: a case needs an existing script, exact SHAs, a bounded timeout,
distinct result signatures, and provenance declaring that aspects and Hegel
are not needed at run time. Debt evidence is anchored by repository, exact
commit SHA, and relative source path:

```sh
make jolt-regression-coverage
```

The live gate additionally compares the checked snapshot with GitHub. A newly
labeled issue, a removed label, or any other remote drift fails until the
snapshot and its portable evidence are reviewed together:

```sh
make jolt-regression-coverage-live
```

This is an explicit debt migration rather than a coverage claim. The current
snapshot has 40 open fork-fixed issues, 8 with portable cases and 32 with named
extraction debt. New debt is not silently accepted. Existing rows are burned
down by adding positive/negative cases that run without aspects, Hegel, or the
original checker.
