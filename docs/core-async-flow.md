# core.async.flow instrumentation pack

This pack exercises Jolt's bundled `clojure.core.async.flow` implementation
without patching or annotating upstream flow source. The scenario owns eleven
small, fixed-arity seams:

- lifecycle wrappers for create, start, pause, resume, ping, inject, and stop;
- process functions for describe, init, transition, and transform, assembled
  with `flow/map->step`.

The compiler derives the manifest from source annotations and requires every
entry to match exactly once. This boundary is deliberate: applications can use
the same wrapper/process-function pattern without binding an aspect pack to
flow's private scheduler implementation.

## Observation contract

`jolt.aspect-packs.core-async-flow.provider` emits canonical invoke/terminal
history through `jolt.aspect-packs.history`. It retains only bounded structure:

- process and connection counts at creation;
- opaque graph, completion, state, and message tokens;
- lifecycle operation, coordinate, timeout, and message count;
- bounded process/parameter/input/output key sets; and
- transition names and coarse result kinds.

Message values, process state, coordinates, flow configuration values, error
objects, and executor objects are not retained. The compiled scenario rejects
any history containing
its private message markers and requires every lifecycle/process operation to
have a complete terminal event.

## Fault contract

`jolt.aspect-packs.core-async-flow.faults` is a separate `:control-v1`
provider. It is inert unless both the control preset and
`:allow-control-aspects true` are selected. An action names one exact operation
and one effect: return or throw before/after the target, replace arguments with
an exact-arity vector, or pause at a bounded barrier.

The fault scenario selects `:return-after` for ping. The real flow target and
inner history provider both run, then the outer control provider replaces the
result. The executable requires exactly one `:after-target` decision for ping
and validates the injected count, proving selection and provider order without
depending on dynamic bindings crossing into flow worker threads.
Before-target faults intentionally skip both the target and the inner history
provider; their outer decision record is the evidence that the operation was
suppressed. A focused composition test pins that distinction.

## Build and evidence gates

The flow scenario also serves as a compiler phase canary. The bundled source
snapshot initially ran the library but could not build it because the dependency
scanner rejected flow's `:as-alias` plus `#::flow{...}` form. The canonical
compiler ref recorded in `targets.edn` includes the reviewed scan-only reader
repair; ordinary reads remain strict. The target record also pins the official
v0.8.1 tag and the checked current-main revision. All four flow implementation
paths are byte-for-byte unchanged between the bundled snapshot, that tag, and
the checked current main.

Run the three focused gates with the exact aspect-capable compiler:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_ASPECT_JOLT=/absolute/path/to/jolt/target/release/jolt \
  make -j1 core-async-flow-aspect-smoke \
           core-async-flow-fault-smoke \
           core-async-flow-plain-smoke
```

Each gate builds and runs a self-contained binary. The woven gates validate all
eleven physical matches, exact provider contracts/order, history behavior, and
the compiler's three-phase effect artifact. The plain gate proves that merely
having the pack on the classpath activates no advice and still validates its
effect artifact.
