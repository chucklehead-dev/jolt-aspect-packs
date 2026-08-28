# Glimmer pack

This pack targets the toolkit-independent root lifecycle at
`glimmer.core/mount`, not any backend operation inherited from Glitter. The
exact target is `6dab5597dc0d912793fe175d0d3cbb9e75f11426` on the `casselc/glimmer`
fork, based on upstream `jolt-lang/glimmer` revision
`55e38f0b21b8decbcf156f56d3c9dd25a875148b`.

The pinned repository has no `NOTICE` file. Git history and blame are therefore
the source-provenance evidence: Yogthos introduced `mount` in the original
portable core and retained it when commit `5581c331c51aff989259b9e8e92ec920fe5e6741`
split Glimmer from the GTK/Glitter implementation. It is public, documented,
toolkit-independent, and has remained one three-argument definition, making it
a more natural compatibility seam than a private reconciler helper.

The non-OTel advice records a synchronous root-mount lifecycle and only a coarse
root kind. It never records the native container, container tag, element tag,
component identity, props, event handlers, text, children, or metadata. The
Hegel model requires contiguous events, closed lifecycles, synchronous
parentage, and exactly that privacy-safe input shape.

The independent source conformance gate runs the exact Glimmer revision through
a small in-memory backend. It checks a real successful mount plus injected
create and append failures without a display. The compiled scenario also runs
headlessly and its aspect report must contain exactly one matching mount site.
