# HTTP-server pack

This pack targets `casselc/jolt-http` revision
`c6effc3a04be1467e66da433b879a8a73a352228`. That revision retains the
library-published compatibility id
`3ef772262308bbf6039412366ae80690cec348b0` for two unchanged semantic seams:

- the eight-argument normalized Ring entry at
  `jolt.http.protocol/invoke-handler`; and
- the one-argument accepted-response observer at
  `jolt.http.protocol/sanitize-response`.

The first seam owns a request's handler plus its asynchronous `respond` and
`raise` callbacks. The second sees `[safe-response problems]` after jolt-http
has replaced invalid response metadata. They describe Ring callback completion,
not socket delivery or peer receipt.

The neutral provider records one canonical operation that remains open when an
asynchronous handler returns and closes after the first callback completes. A
normal response records `:return`; `raise`, a thrown handler, or a callback
failure records `:throw`. The input is limited to bounded semantic fields:
known method or `_OTHER`, safe scheme, query-free path, and protocol version.
The terminal value contains only accepted status and a bounded error type.
Headers, query strings, bodies, host and peer identities, handlers, and
Throwable messages/data never enter the history.

The source conformance alias adds the exact OTel consumer only for the gate and
composes providers in the same explicit order as the native scenario:

```clojure
:providers [otel.instrumentation.http-server
            jolt.aspect-packs.http-server.provider]
```

The first provider is the outer observation layer and the neutral history is
the inner correctness layer. The success case proves one remote-parented server
span, an accepted response status, one duration measurement, and one closed
Hegel history. The failure case proves an error span, correlated privacy-safe
exception log, error-attributed duration measurement, and canonical history
throw. The history model applies contiguous sequence, closed lifecycle, causal
parentage, context coherence, and bounded semantic-shape rules from the current
Jolt Hegel revision.

Run the source and compiled gates with the workspace's pinned Chez wrapper:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  jolt -M:http-server-conformance

/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_ASPECT_JOLT=/absolute/path/to/aspect-capable-jolt \
  make http-server-aspect-smoke
```
