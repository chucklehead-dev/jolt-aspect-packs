# Core.async history pack

This pack externally instruments Jolt's built-in `clojure.core.async` without
adding annotations or dependencies to the runtime implementation. Its first
bounded surface is deliberately small:

- fixed positive-capacity channels;
- `offer!`, `poll!`, and `close!` at explicit application call sites;
- callback-aware `put!` arity 4 and `take!` arity 3 call sites; and
- at most eight fixed-buffer operations or six callback operations per checked
  history.

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

Each logical callback lifecycle has exactly one synthetic synchronous child:
`:core-async/put-target` or `:core-async/take-target`. The child uses the same
opaque input, records only `:target-returned` (or a privacy-safe error type),
and names the logical callback operation as its parent. This separates target
registration from callback completion without changing either lifecycle: a
target child begins before the callback can close its parent. An immediate
caller-thread callback closes the parent before the target child returns; in
dispatched and pending cases either terminal may win the concurrent race. The
callback model validates the one-child relation and provenance, retains target
children for trace assertions, then removes and resequences them before the
logical linearizability search. Target children therefore add causal evidence
without pretending that registration return is channel completion.

The callback model covers two initiators and both unbuffered and capacity-one
channels. It checks exact lifecycle pairing, carrier provenance, value identity,
FIFO order, real-time precedence, and the logical-close rule from core.async:
close completes pending takes, but a put that was already pending remains until
a later take releases its value. Cleanup therefore uses bounded post-close
takes rather than assuming close cancels puts. Linearization search decides
which side of close an overlapping put occupies: an admitted put may precede
close and later be released by a take, while a put ordered after close returns
closed. Invocation and callback sequence numbers do not impose an extra close
boundary on overlapping operations.

The deterministic regressions cover both capacity-one pending puts and the
fiber registration race that previously let close overtake an operation after
its public call had returned. The generated two-actor histories keep both
cases under the same bounded oracle.

This is not yet a claim about every core.async operation. In particular:

- the `go` CPS pass can lower visible `<!` and `>!` calls to internal state
  machine operations before aspect weaving;
- `alts!` fairness and multi-channel winner selection require separate
  statistical and multi-object models.

Those are follow-on experiments. A failing public history is fixed at the
runtime ownership layer first; only a recurring claim/publish/wake pattern is a
candidate for a new shared concurrency primitive.

## Test-only public-call faults

`jolt.aspect-packs.core-async.faults` is a separate `:control-v1` provider. It
is inert unless a test binds one explicit action, and the compiler refuses to
select it unless the build sets `:allow-control-aspects true`. In a combined
build the fault provider must be the outer consumer and the history provider
the inner consumer. Substituted arguments then reach history before the target,
while calls skipped by a pre-target fault do not masquerade as target history.

The bounded actions cover pre-target return/throw, post-target return/throw,
exact-arity argument replacement, owner-context barriers, and application
callback suppression, duplication, or deferred release. Deferred callbacks
capture an exactly-once thunk and return immediately; they never block a
core.async dispatch callback thread. Fault decisions contain only operation,
effect, and phase metadata.

These controls perturb public call arrival and application-visible callback
delivery. They do not reorder native scheduler decisions, defer `proceed`
beyond its dynamic extent, invoke it on another thread or fiber, or claim to
alter the target's internal callback delivery. Those require a separate native
decision seam or a provider-neutral asynchronous continuation/context contract.

The first generated perturbation suite composes the fault provider outside the
history provider around two competing fixed-buffer operations. Separate
generated properties require pre-target, post-target, and argument-replacement
fault families; one actor is always faulted and the other remains an ordinary
target call. The same pure EDN scenario runs on both a thread and a fiber
backend. A separate application ledger checks
whether the target ran and whether the outer return or identical Throwable was
observed, while the ordinary fixed-buffer model checks only the target calls
that reached the inner history provider. This distinction is intentional:
pre-target faults must not invent operations, and post-target faults must not
rewrite the target history merely because they change the caller's outcome.

The generated slice does not yet include callback suppression, duplication, or
deferred delivery, native scheduling decisions, or `alts!`. A mutation control
deliberately reverses provider order and demonstrates that a skipped target can
then be recorded as an impossible channel result, keeping the ordering rule
non-vacuous.
