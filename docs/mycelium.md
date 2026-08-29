# Mycelium execution pack

This pack consumes the provider-neutral Mycelium semantic seams introduced in
`yogthos/samizdat` at
`dd13b4b933d3db80a319d2c7b27af4ee6767fca5`. The manifest selects exactly one
arity-1 entry for `mycelium.execution/workflow-event!` and exactly one arity-1
entry for `mycelium.execution/edge-event!`. The inspected source revision that
carries that exact compatibility pin is
`ff9cfd8cf6bf08c0f61b71cc98eee8c354efa861`.

The lifecycle advice records canonical `:invoke` plus exactly one `:return` or
`:throw`, correlated by the portable execution UUID in the bounded input.
Mycelium source `:cancel` becomes the shared history ABI's `:return` terminal
with `{:outcome :cancel}`. The source event's `:return` and `:throw` become the
corresponding history terminal with `:ok` and `:error`. The caller's existing
history context is preserved; the execution UUID does not replace it. This lets
an async Mycelium execution remain naturally nested under its application or
Samizdat operation even when later callbacks run on a thread or fiber that lost
the dynamic history binding. The open execution registry recovers its owning
journal by globally unique execution-id; a different non-nil ambient journal
fails closed instead of mixing histories.

The invoke retains the normalized graph once. It is limited to schema,
graph-id, entry, terminals, nodes, and edges; counts and every rendered keyword
are bounded. Edge advice retains only execution-id, graph-id, and the selected
`[source label target]` reference. Predicate functions, workflow values,
resources, exceptions, messages, and arbitrary application data never enter
history. An edge not present in the retained invoke graph fails closed.

Selected edges are nested under the lifecycle operation. The first selected
edge has no causal predecessor and each later edge names exactly the immediately
prior edge operation. The independent model checks that progression together
with contiguous sequence, closed and exactly-once operation lifecycles,
synchronous edge parentage, caller-context coherence, canonical causal-link
shape, graph membership, and strict privacy-shaped inputs and terminals. It
rejects missing, dangling, later, duplicate, unsorted, extra, and unknown-edge
relations.

The generated conformance histories use `hegel.generator/recursive`: a leaf
chooses the successful terminal path and each recursive branch prepends one
`:retry` self-edge. This exercises arbitrarily nested, depth-bounded retry
paths while preserving libhegel's native recursive shrink tree, so a failing
workflow can shrink by hoisting a valid descendant path instead of merely
truncating an unrelated vector.

The provider is inert when no history journal is bound. It owns no workflow
mechanism or resource behavior: the advice calls the semantic no-op marker and
preserves its return or Throwable identity. Provider state retains only the
open history handle, bounded graph identity/membership, caller context, and
prior edge handle until the exactly-once lifecycle terminal.

Run the focused conformance test with the mandatory Chez wrapper:

```sh
HEGEL_CACHE_DIR=/tmp/jolt-aspect-packs-hegel-cache \
JOLT_GITLIBS_DIR=/tmp/jolt-aspect-packs-mycelium-gitlibs \
JOLT_CACHE_DIR=/tmp/jolt-aspect-packs-mycelium-cache \
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  jolt -A:test -e \
  "(require 'jolt.aspect-packs.mycelium.provider-test :reload) \
   (clojure.test/run-tests 'jolt.aspect-packs.mycelium.provider-test)"
```

Install the checksum-pinned native Hegel library first with the same cache
settings and `jolt -A:test -m hegel.install` when the cache is empty.

Compiled smoke scenarios require an aspect-capable Jolt containing
`4f711dfcf60c576aa004c8c98d8361b61256a144`, which adds stable `:site-id` and
`:build-identity` values to runtime join points. Older aspect compilers may
produce a correct static aspect report but cannot satisfy the history
provider's fail-closed provenance contract.
