# Declarative spec corpus: db pilot

Status: Phase 3b contract for [roadmap #69](https://github.com/chucklehead-dev/jolt-aspect-packs/issues/69).
The live-generation and baked-fixture consumers are not implemented by this
document. The Hegel materialization API is tracked in
[jolt-hegel #56](https://github.com/chucklehead-dev/jolt-hegel/issues/56).

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

Use the planned Hegel #56 versioned corpus envelope rather than inventing a
pack-specific serialization format. It is an unimplemented dependency at this
baseline, not an API already supplied by the current pin. A consumer pin must
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

The planned Hegel v1 transport hashes the exact UTF-8 bytes of a stored restricted
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

- [ ] Final Hegel corpus API/transport and digest provider reviewed and pinned.
- [ ] Db generator, witness families and exact valid-case policy exercised.
- [ ] Separate Mode A and Mode B commands/aliases with explicit dependencies.
- [ ] Independently pinned fixture/manifest; stale and digest mutants exercised.
- [ ] All required witness and semantic/privacy controls reach their intended gate.
- [ ] Offline libhegel-free transfer gate on supported hosts; platform scope recorded.
- [ ] Coordinated model/seam/Hegel pins and fixture revisions.
- [ ] Root review, independent local Claude review and applicable CI before merge.

Selective typing may reuse Hegel #73's eventual manifest/event contracts. It
remains optional development tooling, not a replacement for these checks or a
runtime prerequisite for either mode. Completion of this contract document alone
does not close roadmap #69, the Phase 4 obligations, or Hegel #56.
