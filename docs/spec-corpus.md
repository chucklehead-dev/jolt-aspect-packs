# Declarative spec corpus: db pilot

Status: Phase 3b contract for [roadmap #69](https://github.com/chucklehead-dev/jolt-aspect-packs/issues/69).
This branch implements the live-generation and baked-fixture consumers against
Hegel `88cc32cc3c39cb445fa16f725ff5f9c1db115858` (merged PR86).
Cross-platform integration CI and final independent review are still required;
local tests alone do not establish completion of this contract.

## One model, two modes

Both modes consume complete plain-data event vectors through
`jolt.aspect-packs.db.model/check!`. A corpus entry is one whole event vector,
not one event, a database operation, or a native replay blob. Model checks run
outside fail-open advice. The model's 512-event bound is independent of corpus
transport and total-case bounds.

- **Mode A — live:** a seeded Hegel generator constructs bounded event vectors;
  each candidate is checked with the pure model before successful materialization.
  Generation failures, flakiness, inconclusiveness and setup/native errors cannot
  publish a partially successful fixture. Use the versioned seed/count/valid-case
  policy provided by Hegel rather than implementing a second sampling loop here.
- **Mode B — baked:** a consumer verifies a committed plain-data artifact against
  an independently pinned manifest/digest, then checks the same event vectors
  with the same model. It needs neither libhegel nor network access. Requiring a
  pure Hegel namespace is allowed; requiring its native engine, installer or
  generator namespace is not. It must not initialize a driver or OTel SDK.

Generation and verification must be separate commands/aliases. Pre-resolve all
declared dependencies before the offline gate. Do not hide a network fetch or
native installation inside a fixture loader. Malli, if used for Mode A generation,
must be explicitly pinned in that consumer alias and absent from Mode B's
required dependency surface.

Mode A must name the exact supported frontend/host and native artifact identity
for Linux x86_64, Windows x86_64 and macOS arm64. A local Linux pass is not proof
of all three platforms. Experimental jank/CLR support is a separate claim and
must not be inferred from successful pure-data loading.

## Manifest and integrity boundary

Use the Hegel #56 versioned corpus envelope rather than inventing a
pack-specific serialization format. The corpus-specific aliases pin the merged
implementation; unrelated conformance aliases retain their existing pins. A consumer pin must
independently identify:

- full peeled Hegel source SHA and exact libhegel version;
- producer runtime host/version/OS/architecture;
- property ID, generator revision and model revision;
- the target's semantic seam revision;
- expected corpus SHA-256, case count and the named valid-case policy.

The seam revision is a compatibility identifier, not necessarily the target's
Git SHA. The current db provider declares seam
`a55c554a66d8f5e9e5198e238773f8218f6050d7` and target source
`0c559d78d839f2f9c8cc1a7326a639134134bfac`; these distinct identities must not be
collapsed. This baseline was inspected at aspect-packs
`63d947b06802d1cd412ec85872c051dbb8e58153`.

The Hegel v1 transport hashes the exact UTF-8 bytes of a stored restricted
EDN payload containing both provenance and values. Verify those bytes; do not
parse/reprint to reconstruct them. Compare with the independent expected digest,
not just the hash supplied inside the artifact. Payload-plus-self-hash mutation
must still fail. A digest is not a signature or proof that the producer is trusted.
Changing the accepted Hegel transport contract requires a coordinated update to
this document, fixture pins and both consumers before integration.

All required fields, versions, bounds, expected counts and provenance must match
before any model callback runs. Malformed or stale input fails closed; there is
no warning-and-continue fallback, implicit regeneration, or relaxed model path.
The corpus loader returns validated data, not a new native context.

## Db values and privacy

The input schema is the existing model's privacy-shaped semantic history:
invocation/return/throw lifecycle events with contiguous sequence, operation
identity, parent/context and causal metadata where required by the event
contract. Invocation metadata includes the published site/build identity and
semantic operation/system fields. Return and throw values use the provider's
bounded outcome shapes. Reuse the same model rules; do not maintain a second
weaker corpus-only validator.

Keep SQL, parameters, result rows, native handles, arbitrary exception messages,
spans and metrics out of persisted values. The optional bounded statement
fingerprint is a grouping hint, not a cryptographic identity or proof of anonymity.
Synthetic fixture provenance must be labeled as synthetic; it does not prove a
woven compiler artifact actually emitted those events.

The existing model's exact-key privacy checks cover invocation `:input` and
terminal `:value`, not every top-level event field. Before model execution, the
corpus consumer therefore also needs an explicit pure db-fixture privacy profile:
closed event keys, bounded scalar/container types, and controlled synthetic
site/build/context/operation identities. It must reject extra raw fields and
opaque or unconstrained metadata. Passing the existing model alone must not be
reported as comprehensive redaction. This profile is a stricter fixture boundary,
not a silent change to the general trace/history acceptance contract in Hegel #22.

Parent/context coherence concerns invocation metadata; terminal events need not
repeat it. For provider-backed witnesses, SELECT cardinality comes from vector
`:rows` in a result map; mutation cardinality comes from a bounded integer
`:count` for an allowed mutation operation. Persist only the shaped cardinality,
never those source rows. Pure synthetic witnesses directly construct the shaped
event values and do not claim to test the provider's extraction behavior.

The corpus model gate supplements, rather than replaces, the existing db
dual-consumer tests, plain/woven scenario, exact join-point matches and compiler
effect evidence required by [the central contract](CONTRACT.md).

## Required witnesses and negative controls

Phase 3b must carry explicit non-vacuity evidence into roadmap Phase 4. Random
generation success or a nonempty corpus alone is insufficient. At minimum:

1. Passing witnesses exercise SELECT/returned cardinality, mutation/affected
   cardinality, a shaped throw, and a nested lifecycle with matching context.
2. Semantic mutants break a sequence/lifecycle, introduce a mismatched context
   or causal reference, use an invalid cardinality, and leak a forbidden raw
   input/result field. Each must reach and fail the relevant model/privacy gate.
3. Transport controls corrupt the digest, replace payload plus its declared hash
   while retaining the independent pin, alter a required provenance field, change
   the declared count, and exceed a reader bound. Each must fail before the model
   executes, paired with an adjacent valid fixture that reaches the model.
4. Materialization controls cover ordinary failure, flakiness, rejected candidates,
   a constant generator, seed reproducibility and uint64 seed wrap. Partial output
   must never be treated as a successful corpus.
5. A committed fixture is transferred across supported hosts and consumed with
   libhegel unavailable and network disabled. Record the actual verification and
   model verdict, not only a successful require or a cached install.

## Integration checklist

### Implemented commands and fixture

Run from the repository root. Provision dependencies before disabling networking;
none of these baked-consumption commands installs libhegel:

```sh
bb --config corpus-bb.edn corpus-offline
jolt -M:db-corpus-offline
clojure -M:db-corpus-offline
```

`corpus-fixtures/db-v1.edn` contains four complete synthetic histories. Its
independent expected digest and provenance are in `db-v1-pin.edn`; the payload
SHA-256 is `55e814e46b1447ca410d5a1942232ad71d038b4d753bca5b26afaac96f8ca5ff`.
The producer is BB 1.13.220 on Linux amd64 with libhegel 0.36.3. Consumer host
identity need not equal producer identity. Tests detect drift in the model's
source-byte hash, seam revision and corpus dependency pins. The model source
has an explicit LF checkout policy so its identity is stable on Windows.

Mode A is deliberately separate:

```sh
bb --config corpus-bb.edn -m hegel.install
bb --config corpus-bb.edn corpus-live corpus-fixtures/db-generation.edn
```

The generation file is trusted operator configuration. Generation prints a
candidate envelope; it never rewrites the independent acceptance pin. Review
any replacement fixture and its pin together. The synthetic profile excludes
optional fingerprints and arbitrary top-level metadata without changing the
general model or provider contract. Every generated entry carries all four
mandatory witness families; this is not a random coverage-frequency claim.

The new `db-corpus.yml` workflow targets BB/Jolt/JVM across Linux, Windows and
macOS. It separates native installation from live tests so a later successful
command cannot conceal an installation failure. Its isolation controls require
successful public HTTPS access before restriction, denied access during it, the
actual model/fixture verdict while restricted, and successful access afterward:

- Linux uses a separate network namespace.
- macOS uses `sandbox-exec` with networking denied for the process tree.
- Windows uses a temporary outbound firewall rule for the actual BB/Jolt/Java
  executable, not a launcher. The checked-in consumer makes no subprocess or
  broker requests; this program-scoped gate is not a general sandbox for hostile
  code. The helper refuses non-GitHub-hosted environments, preserves firewall
  profile enablement and removes only its own rule in `finally`.

The network probe is a separate explicit command, never a consumer dependency.
It uses each selected runtime's HTTPS implementation, not a curl subprocess.
Live JVM tests pin Clojure 1.12.3 and reject a supplied version that differs from
the running runtime. Windows/macOS isolation remains an unverified integration
gate until actual CI records the controls and corpus verdict on those hosts.

### Remaining acceptance gates

- [x] Final Hegel corpus API/transport and digest provider reviewed and pinned (PR86).
- [x] Db generator, witness families and exact valid-case policy exercised locally on BB/JVM/Jolt.
- [x] Separate Mode A and Mode B commands/aliases with explicit dependencies.
- [x] Independently pinned fixture/manifest; stale and digest mutants exercised locally.
- [x] All required witness and semantic/privacy controls reach their intended gate locally.
- [ ] Offline libhegel-free transfer gate on supported hosts; platform scope recorded.
- [x] Coordinated model/seam/Hegel pins and fixture revisions with drift tests.
- [ ] Root review, independent local Claude review and applicable CI before merge.

Selective typing may reuse Hegel #73's eventual manifest/event contracts. It
remains optional development tooling, not a replacement for these checks or a
runtime prerequisite for either mode. Completion of this contract document alone
does not close roadmap #69, the Phase 4 obligations, or Hegel #56.
