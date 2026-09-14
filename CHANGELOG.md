# Changelog

## Unreleased

- Add a hermetic woven TLS compatibility scenario for stale pooled
  connections. It proves an idempotent GET may retry on a fresh connection,
  while a POST that the server fully received is surfaced as ambiguous and is
  never replayed. A forced replay-policy mutant must produce a third POST on
  the wire and fail the exactly-once oracle.

- Requalify the provider-neutral HTTP-client pack against the merged
  `casselc/http-client` v0.0.10 convergence line and its library-owned
  compatibility identity. The scenario still selects exactly one request
  entry seam and adds no transport or retry behavior. Refresh the compatibility
  oracle from real woven/plain evidence produced by the matching OTel provider,
  exact target, and current aspect compiler.

- Shape and validate jolt-chdb's redacted forced-live takeover warning without
  retaining raw advice arguments or changing proceed/CAS outcomes; causally
  complete traces reject suppression, duplication, and malformed warnings. The
  bounded hosted Durable job now enforces both the woven compiler evidence and
  plain-build erasure against the pinned target.

- Follow chDB Durable control operations through their option-bearing retry
  arities, preserving one history/fault event for both convenience-wrapper and
  writer-direct calls originally introduced at jolt-chdb `dbc2db2`; the current
  qualified target is the immutable SHA in `targets.edn`, with its target-owned
  `4a0b821` seam epoch.
