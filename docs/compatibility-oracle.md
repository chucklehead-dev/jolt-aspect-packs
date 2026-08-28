# Exact compatibility oracle

`compatibility/known-universe.edn` is the central, deliberately small set of
validated aspect-pack combinations. It records exact Git revisions, the
library-published source seam id, the exact selector and report cardinality,
and the independently built woven and plain fixture expectations. It contains
no version ranges and the runner performs no network resolution.

The first entry covers only the validated OpenTelemetry HTTP-client pack. Check
the exact checkouts, existing compiler report, and compiled fixtures with:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 jolt -M:compatibility -- \
  --entry otel/http-client \
  --pack-root ../jolt-otel-instrumentation-http-client \
  --target-root ../http-client \
  --compiler-root ../worktrees/jolt-v0728-aspects-ffi-loans \
  --report ../jolt-otel-instrumentation-http-client/test-app/target/http-client-aspects.edn \
  --woven ../jolt-otel-instrumentation-http-client/test-app/target/woven-http-client-fixture \
  --plain ../jolt-otel-instrumentation-http-client/test-app-plain/target/plain-http-client-fixture
```

The oracle is read-only: it observes clean state and the three checkout heads,
confirms the target's exact upstream base, parses the existing report, then runs
the already compiled woven and plain fixtures and requires their behavioral
differential.
It does not fetch or build and requires no target-project changes. The record
documents the isolated build gates that produced those artifacts. Age is
explicit review policy stored in the record; the runner never consults the wall
clock, so output remains stable until a human updates the evidence.
