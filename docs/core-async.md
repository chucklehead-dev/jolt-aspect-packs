# Core.async history pack

This pack externally instruments Jolt's built-in `clojure.core.async` without
adding annotations or dependencies to the runtime implementation. Its first
bounded surface is deliberately small:

- fixed positive-capacity channels;
- `offer!`, `poll!`, and `close!` at explicit application call sites;
- callback-aware `put!` arity 4 and `take!` arity 3 call sites; and
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

The callback advice uses the compiler's `:replace-args-v1` contract. It begins
the lifecycle before calling the target and replaces only the already-evaluated
callback argument. The handle may remain open after `put!` or `take!` returns;
the first callback delivery closes it with a privacy-safe accepted, closed, or
opaque value result. Its terminal also exposes the carrier's operation-local
parent ID and already-public context ID. An explicit history carrier restores
that same relation in callbacks that run later or on another thread. Every
callback delivery still reaches the original callback, so a broken target that
delivers twice is visible to an independent application-side counter even
though the history lifecycle closes exactly once. Callback arguments, return
values, and thrown objects retain their application identity. A target throw
before callback completion closes the history as a throw and is rethrown
unchanged.

This is not yet a claim about every core.async operation. In particular:

- the `go` CPS pass can lower visible `<!` and `>!` calls to internal state
  machine operations before aspect weaving;
- `alts!` fairness and multi-channel winner selection require separate
  statistical and multi-object models.

Those are follow-on experiments. A failing public history is fixed at the
runtime ownership layer first; only a recurring claim/publish/wake pattern is a
candidate for a new shared concurrency primitive.
