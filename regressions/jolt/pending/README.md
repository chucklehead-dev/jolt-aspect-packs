# Pending upstream findings

Findings against upstream Jolt (`jolt-lang`) and `clojure.data.json` (`yogthos`)
that have evidence and a runnable witness but no fixed commit yet, so they
cannot enter `regressions/jolt/cases.edn` — that catalog requires both a
`:bad-sha` and a `:fixed-sha`, and the runner fails closed without them.

Each witness here prints the same `REGRESSION PASS <id>` / `REGRESSION FAIL <id>`
signatures the matrix expects, so promoting one is a matter of adding a catalog
entry once a fix lands, not rewriting the program.

| Witness | Kind | Upstream |
| --- | --- | --- |
| `string_codepoint_indexing.clj` | correctness | jolt-lang/jolt |
| `interop_microbench.clj` | performance | jolt-lang/jolt, yogthos/data.json |

Run either against any Jolt binary:

```sh
jolt -e "$(cat regressions/jolt/pending/string_codepoint_indexing.clj)"
jolt -e "$(cat regressions/jolt/pending/interop_microbench.clj)"
```

The microbenchmark reports `data.json write-str` only when that library is on
the classpath; run it from a project that depends on it to get that row.

The measurements and what they cost in practice are in
`docs/jolt-interop-performance.md`.
