# chDB Durable semantic-history pack

This pack observes the exact Durable control entry seams introduced after
`edc86af07d5982a185c4ef953c19c848a719da0e`. That revision is an opaque
compatibility epoch: it is the parent of the source change that adds the
declarations and must agree between the target-owned and pack-owned manifests.

The provider records the control vocabulary used by the Durable Quint model:

- `:acquire`
- `:publish` and `:checkpoint-publish`
- `:commit-attempt`
- `:renew-attempt`
- `:release-attempt`

Durable-object identity, writer identity, immutable-object identity, and
publication-attempt identity are stable only inside one journal and are emitted
as opaque tokens. The object token partitions interleaved commands from
independent backends; it is correlation metadata, not a fencing or ordering
coordinate. Generation and manifest sequence remain visible because they are
the protocol's fencing and ordering coordinates. The public event API never exposes backend instances,
owner/instance strings, SQL or WAL bytes, filesystem paths, full object keys,
digests, ETags, credentials, exception messages, or unknown head fields.

`jolt.aspect-packs.chdb-durable.model/check!` runs outside advice. It checks the
canonical Hegel envelope, contiguous sequence, closed synchronous lifecycles,
context and causal links, bounded command/terminal shapes, and one pure Durable
head transition fold per opaque object. `model/commands` produces one checked
command plus result per invocation and retains that object token. That
projection is the adapter boundary for a Quint fixed-trace test; it is not
itself an exhaustive model-checking claim.

The mapping to the existing bounded Quint model is direct for acquire,
publication, commit, and release. Checkpoint publication is a publication with
`:kind :checkpoint`; renew is a head-content stutter in the current model
because lease expiry is intentionally outside its content/refinement state.
The unconstrained Quint verification remains authoritative for exploration;
captured woven histories validate concrete executions against the same
transition vocabulary.

Immutable publication outcomes include confirmed `:published`, verified
pre-existing `:already-published`, and operation-specific `:reconciled` after
an ambiguous provider acknowledgement. All three require the same exact
privacy-safe reference shape before a later head commit can be accepted.

## Process-crash cuts

`jolt.aspect-packs.chdb-durable.faults` is a separate test-only control
provider. It can stop an exact Durable command immediately before or after its
target and publish a readiness marker for an external harness. It is never
included by the observation pack and requires the compiler's explicit
`:allow-control-aspects true` opt-in.

`make chdb-durable-crash-smoke` builds a self-contained scenario, waits at each
selected aspect barrier, sends real `SIGKILL`, then opens the same local
namespace/object in a fresh process. The cuts use the same
`durable/publish-wal` and `durable/commit-reference` command identities as the
offline history and Quint/ITF bridge. The required outcomes are:

- before upload: no WAL object and sequence zero;
- after upload: one unreachable WAL object and sequence zero;
- after head CAS: one reachable WAL object, sequence one, and one replayed SQL
  statement.

Run the focused provider/model tests with either JVM Clojure or Jolt:

```sh
clojure -M:chdb-durable-test
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 jolt -M:chdb-durable-test
```

Run the exact woven and plain compiled scenarios with the compiler revision
recorded in `targets.edn`:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  make JOLT_ASPECT_JOLT=/absolute/path/to/jolt \
  chdb-durable-aspect-smoke chdb-durable-plain-smoke
```

The woven lane executes the real in-memory Durable implementation through
acquire, WAL publish/commit, renew, checkpoint publish/commit, and release. It
then validates the captured history offline. The plain lane executes the same
source and proves that no journal events or compiler aspect effects exist.

For application integration, keep the gates layered:

1. Quint explores the bounded protocol and produces ITF witnesses.
2. jolt-chdb replays Quint ITF against the real control implementation and runs
   Hegel stateful properties.
3. This pack validates real woven command histories plus compiler provenance
   and retains a plain-build erasure lane.
4. oscope runs the native chDB Durable lifecycle and can bind a journal around
   that test to validate the application-observed history with this model.

This keeps model checking, generated model-based testing, implementation
properties, and observed-trace validation distinct while sharing one command
language.
