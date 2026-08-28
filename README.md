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

`jolt.aspect-packs.history` contains only target-neutral journal mechanics;
library model namespaces compose the shared `hegel.trace` rules.
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

See [the central pack contract](docs/CONTRACT.md) for the compatibility and
verification rules. The next packs will cover Glimmer separately, HTTP server,
database SPI, Samizdat control-loop seams, and lifecycle histories discovered
by the current cross-library correctness work.
