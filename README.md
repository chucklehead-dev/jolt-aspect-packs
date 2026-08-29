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
- compiled weave-report gates against the aspect-capable Jolt compiler.

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

Run the provider and manifest contract tests with Jolt v0.7.28 or newer:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_CACHE_DIR=/tmp/jolt-aspect-packs-cache \
  jolt -M:test
```

Run the compiled scenario with the current aspect-capable compiler checkout:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_ASPECT_JOLT=/home/chuck/ai-src/worktrees/jolt-v0728-aspects-ffi-loans/bin/jolt \
  make aspect-smoke
```

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

See [the central pack contract](docs/CONTRACT.md) for the compatibility and
verification rules. The next packs will cover database SPI, Samizdat
control-loop seams, and lifecycle histories discovered by the current
cross-library correctness work.
