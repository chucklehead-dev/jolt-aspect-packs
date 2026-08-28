# Glitter pack

The first independent-author transfer check targets Burin's
`glitter.widget/list-box-reorder-child!`. `NOTICE` in the upstream Glitter
repository identifies this keyed list-box behavior as Glitter work rather than
inherited Glimmer code.

The exact upstream base is
`482642fd3c9671b05f0ffaa2ef47420b1a92553b`. The central pack pins the reviewed
fork revision `f4e3eb83015566e4cadaedd7f5e8ad80dc57404f`, which adds exception-safe
temporary GObject ownership and deterministic fault-injection tests. No
upstream pull request is implied or opened.

The aspect records semantic reorder lifecycles and opaque child/sibling
identity. It does not expose raw native pointers. The compiled scenario reaches
the private entry through Glitter's normal public `reorder-child!` dispatch,
and exact match count one makes a rename or extraction fail closed.

The real GTK integration scenario remains Glitter's
`glitter.list-box-reorder-smoke`: it mounts keyed A/B/C children, reconciles to
C/A/B, and reads actual GTK child order rather than trusting reconciler state.

The central `:glitter-conformance` alias independently fault-injects remove,
index, and reinsert failures into the pinned source revision. It jointly checks
that the temporary GObject reference is balanced, suppression is cleared, and
the semantic history closes with the appropriate return or throw event. This
test remains central even if Glitter later chooses not to carry its local copy.
