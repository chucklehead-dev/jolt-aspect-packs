# Jolt counted loops and string-index portability

These are characterization notes for upstream Jolt and the owned
`casselc/data.json` fork. They do not assign a whole benchmark row to one
language construct, claim that Jolt should adopt JVM UTF-16 indexing, or report
an active astral-data defect in the owned stack.

The runnable witnesses live in `regressions/jolt/pending/`.

## Exact evidence boundary

Fresh evidence was collected on Linux x86_64 with Chez Scheme 10.4.1 and the
existing optimized Jolt binary built from `casselc/jolt:integration/aspects` at
`2d39e854a90926d8f8e9bd5d3ddbb109d657afe1` (binary SHA-256
`6e1918a72e7547546ad8cb91eb5225f0520c985d9769201e0570ab805656ee53`).
The JSON witness pins `casselc/data.json` merge
`95b1e6430b48ce4fb4e649656b79f7cabc4702a7`.

The original 2026-09 measurements on Jolt 0.8.6 remain useful discovery
evidence, but their “bare loop, no body” label was wrong. That row included a
global Var read of the limit, numeric equality, unchecked increment, and the
recur backedge. Its 64 ns/iteration result cannot be attributed to
`loop`/`recur` alone.

## Controlled counted-loop characterization

`interop_microbench.clj` separates the relevant operations while retaining the
same 83,367-codepoint payload and a checked result. Each reported value is the
median of five samples after warmup; each sample contains 20 complete passes.
The end timestamp is captured before result validation, so comparison of a
large returned string is outside the timed region. Timings are local
characterization, not portable thresholds.

| Counted-loop control | Jolt run ms/pass | Optimized AOT ms/pass |
| --- | ---: | ---: |
| global bound, `==`, `unchecked-inc` | 7.512 | 7.215 |
| hoisted global bound, `==`, `unchecked-inc` | 6.220 | 6.260 |
| function-argument bound, `==`, `unchecked-inc` | 7.103 | 7.230 |
| function-argument bound, `=`, `unchecked-inc` | 0.488 | 0.570 |
| proven `^long` bound, `==`, `unchecked-inc` | 0.454 | 0.440 |
| proven `^long` bound, `==`, `inc` | 0.325 | 0.308 |

The corrected conclusion is narrower and more useful: unproven `==` dispatch,
not an empty recur backedge, dominates the slow discovery row. The paired
function-argument controls differ only in `==` versus `=` and are about 12.7x
apart in final AOT. Hoisting the global lookup changes the final AOT median by
about 15%, with overlapping sample ranges; the run-mode difference is inside
the wider session noise. The global lookup is therefore not a co-dominant
explanation.

The two proven-long rows now both initialize `i` with the same literal `0`.
Their emitted increments differ (`jolt-uncinc` versus `jolt-l-inc`) and their
medians differ, but this only observes missing specialization for the unchecked
form on this compiler. It does not prove how much cost is unavoidable for
signed-64-bit wrapping or prescribe a particular replacement.

The witness prints all five raw sample times after each median. The final AOT
global samples were `[6.458 6.627 7.643 7.847 7.215]` ms, the hoisted samples
were `[6.037 6.234 7.651 6.260 6.639]` ms, and the paired argument `==`/`=`
samples were respectively `[7.230 6.276 6.208 7.324 7.617]` and
`[0.637 0.438 0.570 0.695 0.553]` ms. The matching-init proven unchecked/inc
samples were `[0.470 0.440 0.362 0.408 0.449]` and
`[0.328 0.268 0.273 0.319 0.308]` ms.

The corresponding final run-mode global/hoisted samples were
`[7.246 7.873 8.285 7.194 7.512]` and
`[6.220 7.516 5.851 5.855 6.949]` ms. Its paired argument `==`/`=` samples
were `[9.676 7.084 7.811 6.730 7.103]` and
`[0.455 0.586 0.480 0.488 0.562]` ms; matching-init proven samples were
`[0.426 0.359 0.454 0.500 0.549]` and
`[0.325 0.501 0.423 0.268 0.268]` ms.

No row is an empty loop. Every row performs a comparison, increment, and
backedge; the first row additionally reads a global Var inside the loop.

### Emitted Scheme

`emit_loop_controls.ss` drives the current Jolt analyzer, numeric pass, and
Scheme emitter and fails if these lowering facts change:

| Source control | Relevant emitted form |
| --- | --- |
| every `loop`/`recur` control | Scheme named `let loop...`, not a Clojure closure call |
| global bound + `==` | global lookup inside the named loop plus `jolt-invoke2` through the `==` Var |
| hoisted global bound + `==` | one global lookup before the named loop; `jolt-invoke2` remains inside |
| argument bound + unproven `==` | `jolt-invoke2` through the `==` Var |
| unproven argument bound + `=` | direct `jolt=2` |
| proven long bound + `==` | direct `jolt-l=` |
| proven long + `unchecked-inc` | `jolt-uncinc` |
| proven long + `inc` | `jolt-l-inc` |

A direct substitution of Chez `fx+` would be incorrect: Clojure's
`unchecked-inc` contract wraps in the signed 64-bit window, while Chez fixnums
are narrower. Any compiler improvement has to preserve that boundary, but the
measurements do not establish the minimum cost of doing so.

### Run mode versus optimized AOT

The same namespace is the comparison unit:

```sh
jolt -A:jolt-interop-witness -M -m jolt.pending.interop-microbench run
jolt -A:jolt-interop-witness build --direct-link --opt \
  -m jolt.pending.interop-microbench -o /tmp/jolt-interop-microbench
/tmp/jolt-interop-microbench optimized-aot
```

Run mode and AOT were measured in separate process sessions, so the columns are
not an interleaved A/B comparison. AOT is not uniformly faster (for example,
the final argument-`=` median was 0.488 ms in run mode and 0.570 ms in AOT).
Use each column to inspect the within-session control ratios; do not infer an
absolute optimization-mode delta by comparing columns.

The final AOT binary SHA-256 was
`f228f10ea6a3526c9ff2a3f37629e1b46ebbc141361937d1a085de087a9c224c`.
It used the same five-sample/20-pass design as run mode and checked the returned
value after every sample.

### Existing upstream benchmark

Current Jolt already has `bench/loop_recur.clj`. It is an optimized-AOT/JVM
scorecard row and should stay in the evidence set, but it measures much more
than a backedge: nested loops, `mod`, `quot`, multiplication, addition,
`bit-xor`, and a data-dependent Collatz branch. Its checked-in 2026-09-09
scorecard reports 28.5 ms on Jolt versus 18.8 ms on the JVM (1.5x) at Jolt
commit `871ef34c`; that historical result is not a fresh measurement for
`2d39e854`.

The current row was also run twice through its own harness at `2d39e854`:

| Invocation | Optimized Jolt | JVM | Ratio |
| --- | ---: | ---: | ---: |
| 1 | 53.1 ms | 38.5 ms | 1.4x |
| 2 | 53.4 ms | 34.8 ms | 1.5x |

That program warms up twice at one quarter size, then reports the mean of three
full `n=20000` samples and verifies checksum `985294547`. A separate raw capture
showed Jolt samples `[64.2 55.4 70.6]` (mean 63.4 ms) and
`[67.1 63.8 74.5]` (mean 68.5 ms), while JVM samples were
`[40.1 37.6 31.9]` (mean 36.5 ms). Pairing those separately captured means
would produce ratios of 1.74x and 1.88x, outside the official 1.4-1.5x range.
They are excluded from the ratio claim because the Jolt and JVM samples were
not paired by the same `bench/run.sh` invocation and the host timing regime had
visibly shifted. They are retained only to disclose that contention. The two
official paired harness invocations support a coarse 1.4-1.5x result; neither
the official nor diagnostic samples support a fine-grained regression claim
or isolate the recur backedge. The separately retained optimized benchmark
binary had SHA-256
`808f1e9574364e1788c4119d9e31541a92e1f8d2bb8b2b2f07c7ba0b4393f2d7`.

Reproduce the current benchmark with:

```sh
cd /path/to/casselc-jolt
bench/run.sh loop-recur 20000
```

## String primitive context

The old document described `casselc/jolt#70` as a fixed and fully verified
2.7-9.3x improvement. That PR was closed without merge because it was based on
a stale 63-commit graph, its hosted tests stopped at the portability check, its
claimed corpus and benchmark were not committed, and unsafe extreme-index
boundaries were not proved.

`casselc/jolt#77` later merged a narrower portable change: one-character
`String.indexOf` needles now use the checked character scanner with normalized
and clamped start indexes. It does not optimize multi-character search or
literal replacement, close the counted-loop investigation, or claim a direct
`data.json` speedup. The current witness retains separate char, string, replace,
regex, and character-at-a-time rows so those operations cannot be conflated.

The original `re-find` observation is still a profiling lead, not a proved
compiler defect. A three-range character class includes regex translation and
engine behavior; comparing it with one-character `indexOf` does not isolate a
single equivalent operation.

## data.json status

The earlier 1.5 M chars/s row identified a real post-escape per-character path,
but it is not an open owned-stack finding. `casselc/data.json#2` merged as
`95b1e6430b48ce4fb4e649656b79f7cabc4702a7`; the writer now bulk-appends each
unescaped span after an escape and retains causal range-call and exact-output
tests. Aspect-packs issue #120 is therefore closed.

The microbenchmark still characterizes a complete map/string write on the
current pin. It must not be interpreted as the cost of one internal operation,
as a universal serializer throughput claim, or as proof of a Durable row-rate.

## Codepoint indexing is a portability hazard

Jolt intentionally indexes `String` by Unicode scalar value; the JVM indexes
by UTF-16 code unit. For `"a" + U+1D11E + "b"`:

| | Jolt | JVM |
| --- | --- | --- |
| `.length` | 3 | 4 |
| `.charAt` sequence | `U+0061 U+1D11E U+0062` | `U+0061 U+D834 U+DD1E U+0062` |
| `.indexOf` `b` | 2 | 3 |

A bespoke encoder that formats each indexed element with
`(format "\\u%04x" ...)` is host-dependent. On Jolt, U+1D11E becomes the
five-digit text `\\u1d11e`; a JSON reader consumes four hex digits and leaves
the final `e`, so it does not round-trip. On the JVM, the same loop visits two
surrogates and happens to produce the required pair.

That is a warning for new cross-host encoders, not evidence that active
`casselc/data.json` corrupts astral text. Its scalar-index foundation merged in
PR #1 and its current writer emits `"\\ud834\\udd1e"` and round-trips on both
models. `string_indexing_portability.clj` asserts the representation-specific
naive result and the owned writer's representation-independent result on Jolt
and the JVM.

Keep explicit astral round-trip and exact-byte properties anywhere a project
introduces a bespoke JSON, SQL, or WAL encoder.
