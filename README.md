# jolt-aspect-packs

Central, independently maintained instrumentation and semantic-conformance
packs for the Jolt ecosystem.

This repository lets applications instrument libraries without requiring
those libraries to depend on OpenTelemetry—or to adopt this test suite at all.
It contains:

- exact-revision join-point manifests;
- provider-neutral semantic history consumers;
- bounded correctness models and minimized regressions;
- scenarios pinned to the source revision each pack claims to understand; and
- compiled weave-report and three-phase effect-evidence gates against the
  aspect-capable Jolt compiler.

Nothing activates because this repository is present on the classpath. A
consumer must explicitly select a manifest and provider in `:jolt/build`.

Each library remains isolated:

```text
resources/META-INF/jolt/aspects/packs/<library>-<revision>.edn
src/jolt/aspect_packs/<library>/provider.clj
models/jolt/aspect_packs/<library>/model.clj
test/jolt/aspect_packs/<library>/provider_test.clj
test/jolt/aspect_packs/<library>/report_test.clj
scenarios/<library>/
```

`jolt.aspect-packs.history` contains only target-neutral journal mechanics. It
emits the canonical `:invoke`/`:return`/`:throw` ABI consumed directly by
`hegel.history`, supports synchronous advice through `invoke!`, and exposes
opaque exactly-once handles plus explicit causal carriers for callback, thread,
and fiber completion. Strict closes catch lifecycle bugs; `try-return!` and
`try-throw!` let timeout/cancel/callback races select one winner without throwing
from losing event-loop callbacks. Pack-owned terminal shapers can retain bounded
HTTP status, DB row-count, FFI outcome, or graph-edge information without
changing application result or Throwable identity. Library model namespaces
compose the shared `hegel.trace` and `hegel.history` rules.
Library-specific redaction, semantic roles, advice, tests, and model rules stay
under that library's namespace.

## Current pack

The first pack targets the `jolt-lang/http-client` library identity at
`12b78edb9024d200083cf77d61fa56709ab23dd7`. Its library-specific, non-OTel
history provider
records synchronous request invocation, completion, exception, and nested
parentage while excluding headers, bodies, query strings, and server names.

The second pack targets Burin's independently authored Glitter list-box
reorder lifecycle. It records opaque child/sibling identity and validates the
pack against the exact exception-safe fork revision while retaining the exact
upstream base in `targets.edn`. See [the Glitter pack notes](docs/glitter.md).

The third pack targets Glimmer's public, toolkit-independent root mount. It
records only a coarse root shape and validates real success and failure paths
against an in-memory backend, without exposing native handles or UI content.
See [the Glimmer pack notes](docs/glimmer.md).

The HTTP-server pack targets jolt-http's normalized Ring handler and sanitized
response seams at `c6effc3a04be1467e66da433b879a8a73a352228`. Its source
conformance gate composes the production OpenTelemetry provider with the
neutral Hegel history provider in the same explicit outer-to-inner order used
by the compiled scenario. It proves remote-parented server spans, response and
error semantics, a duration metric, a correlated exception log, and a bounded
privacy-safe history from one application operation. Neither OTel nor the
target HTTP library is added to this repository's base runtime dependencies.
See [the HTTP-server pack notes](docs/http-server.md).

The database pack targets the exact `io.github.casselc/db` SPI revision and its
published stable execute seam. Its canonical history provider records only a
closed operation/system classification, sanitized structural statement
fingerprint, and safely available row count. A compiled dual-consumer scenario
selects the existing OTel provider and the history provider in a stable order
without adding OTel dependencies to the database or adapter libraries. See
[the database pack notes](docs/database.md).

The Mycelium pack targets Samizdat's provider-neutral workflow and selected-edge
semantic events at compatibility revision
`dd13b4b933d3db80a319d2c7b27af4ee6767fca5`. It correlates bounded normalized
graphs, exactly-once lifecycle terminals, graph-valid edge selections, caller
history context, and exact edge-to-edge causal progression without retaining
workflow data, predicates, resources, or errors. See
[the Mycelium pack notes](docs/mycelium.md).

The experience pack targets Samizdat's closed-domain decision surface at
compatibility revision `71f24e427649a82db96576694f6967c171e72453`: the DecisionDomain
authorization, the candidate scoring, the apply-time revalidation, gate
settlement and machine-checked artifacts. It records identifiers, counts,
margins, entropy and outcomes and never the vocabulary, the scorer, its
scores, prompts, tokens, claims, code, witnesses or connections. Its woven and
plain scenarios compile the real Samizdat decision path at `canonical` and
pass the effect and aspect report gates under the canonical fork compiler.
See [the experience pack notes](docs/experience.md).

The core.async pack is the first runtime-concurrency experiment. It externally
wraps fixed-buffer `offer!`, `poll!`, and `close!` call sites, records opaque
canonical histories, and checks them against a bounded FIFO/close
linearizability model under both OS-thread and fiber contention. The same
scenario is also compiled without the pack. This intentionally excludes
callback operations, `alts!`, and CPS-lowered `go` parks until their distinct
observation contracts are modeled. See
[the core.async pack notes](docs/core-async.md).

Run the provider and manifest contract tests with Jolt v0.7.28 or newer:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_CACHE_DIR=/tmp/jolt-aspect-packs-cache \
  jolt -M:test
```

Run portable public-API regressions against explicit upstream and consolidated
fixed binaries without switching branches:

```sh
JOLT_UNFIXED=/absolute/path/to/upstream-jolt \
JOLT_FIXED=/absolute/path/to/fixed-jolt \
make jolt-regression-matrix
make jolt-regression-coverage
make jolt-regression-coverage-live
```

The EDN result distinguishes the expected historical `:fail`, fixed `:pass`,
and upstream `:xpass`. See [the portable matrix contract](docs/jolt-regression-matrix.md).

Record-only compiler checkpoints can also be captured as canonical evidence and
reduced to dependency-free observation fixtures. See
[checkpoint history evidence](docs/checkpoint-history.md).

Run the compiled scenario with the current aspect-capable compiler checkout:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_ASPECT_JOLT=/path/to/jolt/target/release/jolt \
  make aspect-smoke
```

Every scenario build also validates its generated
`TARGET.build/effects.edn`. Woven builds cross-check the effect report's build
identity and preserved site count against `target/aspects.edn`; plain builds
prove all three phases contain no aspect sites. The currently validated
compiler revision is recorded per target in `targets.edn`.

Run the independent Glitter source/lifecycle conformance gate without a display
server:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 jolt -M:glitter-conformance
```

Run the independent Glimmer source/mount conformance gate without a display
server:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 jolt -M:glimmer-conformance
```

Run the HTTP-server dual-consumer source conformance gate:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  jolt -M:http-server-conformance
```

Run the exact database source and dual-consumer conformance gate:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_CACHE_DIR=/tmp/jolt-aspect-packs-db-source-cache \
  jolt -M:db-conformance
```

See [the central pack contract](docs/CONTRACT.md) for the compatibility and
verification rules. The next packs will cover Samizdat
control-loop seams, and lifecycle histories discovered by the current
cross-library correctness work.
