# Pending upstream findings

Open findings and portability hazards around Jolt string processing. The
performance characterization has no fixed commit, so it cannot enter
`regressions/jolt/cases.edn` — that catalog requires both a `:bad-sha` and a
`:fixed-sha`, and the runner fails closed without them. The indexing program is
instead a passing cross-host witness for an intentional representation
difference.

| Witness | Kind | Upstream |
| --- | --- | --- |
| `string_indexing_portability.clj` | passing portability witness | jolt-lang/jolt, casselc/data.json |
| `interop_microbench.clj` | performance characterization | jolt-lang/jolt, casselc/data.json |
| `emit_loop_controls.ss` | compiler emission characterization | casselc/jolt |

Run either against a Jolt binary from the repository root:

```sh
jolt -A:jolt-interop-witness -M -m jolt.pending.string-indexing-portability
jolt -A:jolt-interop-witness -M -m jolt.pending.interop-microbench run
```

The alias pins the owned `casselc/data.json` fork at the merge of PR #2. The
portability witness demonstrates that a naive four-minimum-digit formatter is
host-dependent while the pinned writer emits a surrogate pair and round-trips
the astral scalar. The microbenchmark reports medians of five samples and
checks each result, but deliberately imposes no timing threshold.

The measurements and what they cost in practice are in
`docs/jolt-interop-performance.md`.

The emission witness runs from an exact `casselc/jolt` source checkout with
Chez 10.4.1:

```sh
scheme --script /path/to/jolt-aspect-packs/regressions/jolt/pending/emit_loop_controls.ss
```
