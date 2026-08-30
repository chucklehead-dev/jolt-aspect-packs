# Database SPI pack

This pack targets `io.github.casselc/db` at
`0c559d78d839f2f9c8cc1a7326a639134134bfac`. That revision publishes the inert
`db.jdbc-shim` call seam with compatibility id
`a55c554a66d8f5e9e5198e238773f8218f6050d7`. The two identities are deliberately
separate: the first pins the complete source checkout used by conformance; the
second changes only when the selected call boundary changes.

The selector observes the single shared call from `db.jdbc-shim` to
`db.driver/execute-handle`. It therefore covers SQLite, PostgreSQL, and external
SPI adapters such as the currently inspected chDB
(`6321a0a23a06e396c684d303f3cb4d31bebc8d9f`) and DuckDB
(`facd634492ff1d984871a0d37122ab527b7648aa`) integrations without selecting
adapter-native functions or adding dependencies to those libraries.

The history provider emits the canonical `:invoke`/`:return`/`:throw` ABI. Its
input is limited to a closed operation name, closed system name, and a
structural statement fingerprint. Fingerprinting first removes comments,
literals, identifiers, and excess whitespace; raw SQL and parameters are never
retained. The terminal shapers retain only success/error classification,
canonical error type, and an eager returned- or affected-row count when the SPI
result makes that count safe.
Driver objects, handles, parameters, labels, rows, exception messages, paths,
coordinates, and credentials are excluded.

Operation classification is intentionally lexical and conservative. It accepts
one bounded, balanced simple statement with at most one trailing terminator;
comments and semicolons inside standard single/double/backtick quotes do not
change the result. Compound statements, CTEs, unbalanced or over-bound input,
and bracket/dollar or other dialect-dependent quoting become `UNKNOWN` until a
dialect-aware parser or a driver-owned semantic operation contract is available.

This follows the current stable OpenTelemetry database direction: model the
logical client operation, use `db.system.name` and `db.operation.name`, avoid
claiming `db.namespace` without a safe contract, avoid unsanitized query text,
and treat returned-row count as optional. The history keys are provider-neutral
rather than OTel attributes. The separate OTel provider remains the owner of
spans, metrics, suppression, and OTel dependencies.

The conformance expectations track the OpenTelemetry database
[span](https://opentelemetry.io/docs/specs/semconv/db/database-spans/),
[SQL](https://opentelemetry.io/docs/specs/semconv/db/sql/),
[metric](https://opentelemetry.io/docs/specs/semconv/db/database-metrics/), and
[exception](https://opentelemetry.io/docs/specs/semconv/db/database-exceptions/)
contracts. The neutral fingerprint is deliberately not represented as a
standard OTel attribute.

Two semantic-convention surfaces are intentionally still absent. Connection
pool use/idle counts and pool-operation metrics require a pool lifecycle
seam; the execute call cannot truthfully reconstruct those states. Likewise,
this pack does not infer `db.namespace` from connection strings or parse
PostgreSQL SQLSTATE out of exception messages. The current OTel consumer can
only classify the exception type; `db.namespace` and PostgreSQL-specific
SQLSTATE guarantees need explicit, privacy-reviewed driver descriptor and
error contracts first.

The compiled evidence is pinned to Jolt
`04a543a291067fd51dc9aee1867b2b86f4b3a364` under Chez 10.4.1. The OTel
consumer is pinned independently to
`0b6a5b850bb959563cff602ec684bb48dcc2f541`; neither pin is inferred from a
working tree.

The compiled scenario selects the existing OTel consumer first and this history
consumer second through the manifest's ordered `:providers` list. The report
gate checks both consumer identities, contracts, ordinals, and the exactly-one
source match. A separately compiled plain scenario proves that adding the
manifest and providers to the dependency universe does not enable them without
explicit build selection. The source gate directly composes both consumers for
chDB- and DuckDB-shaped descriptors against an in-memory OTel SDK. It requires
a real client span, duration metric, exception log and error status, verifies
OTel's row-count opt-in independently from the neutral safe count, and checks
identity, privacy, lifecycle completion, context, and bounded Hegel rules.
The model also composes `hegel.trace/causal-links`, requiring every invocation
to carry canonical, sorted, unique fan-in links; ordinary DB calls prove the
canonical empty-vector case rather than omitting the field.
