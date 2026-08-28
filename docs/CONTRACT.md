# Central pack contract

Each target entry in `targets.edn` pins an exact source revision, manifest,
and runnable scenario. A pack contains inert EDN selectors; merely adding this
repository to the classpath does not enable instrumentation.

The conformance gate must prove all of the following:

1. The pinned target revision resolves.
2. Every selected join point matches its declared exact count.
3. Provider and manifest library identities agree exactly.
4. Plain and woven builds remain separate artifacts.
5. Advice preserves application results and thrown identity.
6. Semantic histories are contiguous, well formed, and bounded before a model
   or property checker consumes them.

Packs may be maintained without cooperation from the target project. A target
project can later adopt the same manifest as an inert resource without taking
on a runtime dependency or enabling any consumer.

Providers are independent and composable. OpenTelemetry, a Hegel event
journal, a profiler, or an application policy consumer may select the same
semantic roles in an explicit order. Assertions and expensive model checking
must remain outside fail-open production advice.

Every target owns distinct manifest, provider, model, scenario, and test
namespaces. Only mechanics that are demonstrably target-neutral belong in a
shared namespace; target-specific redaction, event shaping, and lifecycle
semantics never accumulate in an omnibus provider.
