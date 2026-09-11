# Jolt interop performance and string indexing

Findings against upstream Jolt (`jolt-lang/jolt`) and `clojure.data.json`
(`yogthos/data.json`), recorded here because neither is owned by this
organisation. Witnesses live in `regressions/jolt/pending/`; they print the
matrix signatures already, so each can be promoted into
`regressions/jolt/cases.edn` as soon as there is a fixed commit to pin.

Everything below was measured on Jolt 0.8.6 against an 83,367-character
JSONEachRow payload — the shape a telemetry exporter hands a database driver —
and reproduced with `regressions/jolt/pending/interop_microbench.clj`.

## Measurements

| Operation | ms per pass | chars/s |
| --- | ---: | ---: |
| bare loop, no body | 5.22 | 16.0 M |
| loop + `.charAt` | 6.27 | 13.3 M |
| `.indexOf` char, absent | 0.185 | 451 M |
| `.indexOf` string, absent | 0.355 | 235 M |
| `.replace`, absent needle | 0.356 | 234 M |
| `.replace`, 15,360 hits | 0.922 | 90.5 M |
| `.getBytes` UTF-8 | 0.243 | 343 M |
| `.toCharArray` | 0.776 | 107 M |
| `re-find`, 3-range char class | 3.08 | 27.1 M |
| `data.json/write-str`, one large string | 57.4 | 1.5 M |

## Finding 1 — loop overhead dominates every character-level algorithm

A `loop`/`recur` with an empty body and `unchecked-inc` costs **64 ns per
iteration**. Adding a `.charAt` costs 13 ns more, so the loop is five times the
price of the interop call inside it. Chez Scheme compiles the equivalent
`let`-loop over a fixnum to a handful of instructions, so this is not an
inherent cost of the target.

The consequence is architectural rather than incremental: on Jolt a native scan
is 29× cheaper *per character* than an empty loop is *per iteration*, so any
routine that inspects characters one at a time loses to a chain of native
`String` calls, even when the chain makes several passes over the data and
allocates a new string each time. Every string routine has to be written
inside-out compared to how it would be written on the JVM.

Worth checking whether `loop`/`recur` is emitting a closure call per iteration
rather than a jump, and whether the loop variables are being boxed despite
`unchecked-*` arithmetic.

## Finding 2 — `.indexOf` and `.replace` leave 2.7-9.3x on the table — FIXED

`.indexOf` is the only primitive that can prove a character *absent*, and
proving absence is what gates every fast path, so its cost sets the ceiling for
escapers and validators.

**A fix is measured and proposed in casselc/jolt#70.** Three changes to
`host/chez/java/natives-str.ss`, both binaries built from the same commit so
the deltas are attributable:

| operation | before | after | |
| --- | ---: | ---: | ---: |
| `.indexOf` char, absent | 219 M chars/s | 596 M | 2.7x |
| `.indexOf` string, absent | 80 M chars/s | 595 M | 7.5x |
| `.replace`, absent needle | 62 M chars/s | 578 M | 9.3x |
| `.replace`, 15,360 matches | 59 M chars/s | 142 M | 2.4x |

- `str-char-index` ran with `optimize-level 2` bounds and type checks on
  `string-ref` inside the loop, though the loop invariant already proves the
  index in range. Unsafe primitives in the body only.
- `str-index-of` sent every needle through the substring matcher, a procedure
  call at each non-matching position. A one-character needle — every
  `(.replace s "\"" ...)` — needs no matcher at all.
- `str-replace-literal` tested every position and wrote its result **one
  character at a time** to an output string port. Jumping to each match and
  copying the span before it in one `put-string` is where the absent-needle
  9.3x comes from: `.replace` used as a conditional escape no longer walks and
  rebuilds the whole string to discover it has nothing to do.

A real `memchr` binding was considered and rejected: Chez strings are UTF-32,
so it cannot be applied without a representation change or unsupported access
to moving GC memory. The unsafe-primitive loop reaches 603 M chars/s in raw
Chez against 231 M for the checked one, which is most of the available gap
without leaving supported ground.

Verified byte-identical to the v0.8.6 release over a deterministic 4,000-case
corpus and 25 edge cases; gates `corpus`, `unit` and `cts` pass.

End to end on a Durable chDB WAL writer, v0.8.6 with and without the patch,
interleaved on one host: 13,484 -> 17,003 rows/s.

## Finding 3 — `re-find` over a character class is barely faster than a loop

A three-range character class scans at 27.1 M chars/s — 1.7× a bare `loop`, and
17× slower than `.indexOf` on a single character. A character class over ASCII
is a table lookup per byte and should run within a small factor of `memchr`.

This is the one primitive that can answer "does this string contain any
character from this set" in a single pass, so its cost sets the floor for
validators, escapers and lexers. In the case that prompted this write-up it is
now **the** bottleneck: with casselc/jolt#70 applied, the WAL escaper's
`.replace` chain drops from 5.6 ms to 2.0 ms and the unchanged character-class
search becomes 5.5 ms of a 7.9 ms stage.

Note what that implies. Once `.indexOf` is 2.7x faster, proving the 27 exotic
control characters absent one needle at a time costs 3.8 ms — **less** than the
single character-class search that replaced it. A primitive meant to do this
job in one pass is slower than 27 separate passes.

## Finding 4 — `data.json/write-str` is 60× slower than a native replace chain

Encoding one large string as a JSON string value runs at **1.5 M chars/s**:
57 ms for 83 KB. That is 10× slower than a bare Jolt loop over the same
characters, so the writer is doing on the order of ten operations per
character, and 60× slower than the `.replace` chain that replaced it.

The writer appears to dispatch per character rather than bulk-copying the runs
between escapes. Since escapes are rare in real payloads, appending each
unescaped run in one operation should recover most of the gap without changing
any output.

Measured in context: a Durable WAL writer that recorded each statement with
`(json/write-str {"sql" sql})` spent 101 ms per 512-row batch there, two thirds
of the entire write path. Replacing it with `.indexOf`-gated `.replace` passes
producing byte-identical output for ASCII took that stage to under 6 ms.

## Finding 5 — `String` is indexed by codepoint, not UTF-16 code unit

`.length`, `.charAt`, `.indexOf` and `.substring` all operate on Unicode
codepoints. The JVM operates on UTF-16 code units. For `"a" + U+1D11E + "b"`:

| | Jolt | JVM |
| --- | --- | --- |
| `.length` | 3 | 4 |
| `.charAt` sequence | `U+0061 U+1D11E U+0062` | `U+0061 U+D834 U+DD1E U+0062` |
| `.indexOf \b` | 2 | 3 |

Self-consistent, and arguably the better design — but it is a silent
portability divergence, and it has a specific failure mode worth calling out.
Any writer that escapes a character as `(format "\\u%04x" (int (.charAt s i)))`
— the ordinary way to emit a JSON or SQL unicode escape — produces a **five**
hex digit escape on Jolt for any character above the BMP. A conformant reader
consumes four digits and leaves the fifth as a literal, so `U+1D11E` round-trips
as `U+1D11` followed by `e`. No exception, no truncation, just different text.

This was found in a Durable WAL writer during the work above, where it would
have silently corrupted any statement carrying an astral character. Portable
code must split the codepoint into a surrogate pair by hand, which is exactly
the code a JVM-shaped mental model would never write.

The witness is `regressions/jolt/pending/string_codepoint_indexing.clj`. It
asserts the JVM contract, so it fails on Jolt today; the failure message shows
the malformed escape.

Whether the fix is to match the JVM or to document the divergence loudly, the
`\uXXXX` hazard deserves a note wherever host interop is described.
