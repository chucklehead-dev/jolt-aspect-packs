# Pending Jolt evidence and portability witnesses

Open findings and portability hazards around Jolt string processing. Here,
“target” means the repository or tracked issue whose behavior is characterized;
it does not mean an upstream submission was made. The performance
characterization has no fixed commit, so it cannot enter
`regressions/jolt/cases.edn` — that catalog requires both a `:bad-sha` and a
`:fixed-sha`, and the runner fails closed without them. The indexing program is
instead a passing cross-host witness for an intentional representation
difference.

| Witness | Kind | Target |
| --- | --- | --- |
| `string_indexing_portability.clj` | passing portability witness | intentional Jolt/JVM divergence; no pending fix |
| `interop_microbench.clj` | performance characterization | aspect-packs issue #118 / casselc/jolt |
| `emit_loop_controls.ss` | compiler emission characterization | aspect-packs issue #118 / casselc/jolt |

Run either against a Jolt binary from the repository root:

```sh
jolt -A:jolt-interop-witness -M -m jolt.pending.string-indexing-portability
jolt -A:jolt-interop-witness -M -m jolt.pending.interop-microbench run
```

The alias pins the owned `casselc/data.json` fork at the merge of PR #2 as a
resolved behavior control, not as a pending target. The portability witness
demonstrates that a naive four-minimum-digit formatter is
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
