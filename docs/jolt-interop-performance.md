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

## Finding 2 — `.indexOf` is a Scheme loop, not `memchr`

`.indexOf` on a character runs at 451 M chars/s, or 2.2 ns per character. A
`memchr` over the same buffer runs at several GB/s, so there is roughly an
order of magnitude available by binding the C routine — or by using a
bytevector representation where one is applicable.

This matters more than it looks, because `.indexOf` is the only primitive that
can prove a character *absent*, and proving absence is what gates every fast
path. Proving that 27 rarely-used control characters are absent from a
statement costs 27 scans; at `memchr` speed that would be noise.

## Finding 3 — `re-find` over a character class is barely faster than a loop

A three-range character class scans at 27.1 M chars/s — 1.7× a bare `loop`, and
17× slower than `.indexOf` on a single character. A character class over ASCII
is a table lookup per byte and should run within a small factor of `memchr`.

This is the one primitive that can answer "does this string contain any
character from this set" in a single pass, so its cost sets the floor for
validators, escapers and lexers. In the case that prompted this write-up it is
now the single largest remaining item in a database WAL writer, at 5 ms of a
20 ms batch.

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
