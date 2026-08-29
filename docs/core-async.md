# Core.async history pack

This pack externally instruments Jolt's built-in `clojure.core.async` without
adding annotations or dependencies to the runtime implementation. Its first
bounded surface is deliberately small:

- fixed positive-capacity channels;
- `offer!`, `poll!`, and `close!` at explicit application call sites; and
- at most eight completed operations per checked history.

The provider retains neither channel objects nor application values in emitted
events. Journal-local opaque tokens preserve FIFO identity, while terminal
values use the closed result vocabulary `:accepted`, `:full`, `:closed`,
`:value`, and `:empty`.

`jolt.aspect-packs.core-async.model` checks the canonical
`:invoke`/`:return`/`:throw` history with `hegel.history`. It preserves observed
real-time precedence and may reorder overlapping operations. The model rejects
post-close acceptance, non-FIFO consumption, duplicate consumption, malformed
terminals, and exceptions. Control tests corrupt otherwise valid histories to
prove those failures are observable.

The compiled scenario runs the same woven call sites concurrently on both OS
threads and Jolt fibers. A separate build of the same source selects no aspect
pack and must emit no history.

This is not yet a claim about every core.async operation. In particular:

- the `go` CPS pass can lower visible `<!` and `>!` calls to internal state
  machine operations before aspect weaving;
- `put!` and `take!` callback placement needs a callback-aware pack rather than
  a synchronous operation wrapper; and
- `alts!` fairness and multi-channel winner selection require separate
  statistical and multi-object models.

Those are follow-on experiments. A failing public history is fixed at the
runtime ownership layer first; only a recurring claim/publish/wake pattern is a
candidate for a new shared concurrency primitive.
