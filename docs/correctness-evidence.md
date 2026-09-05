# Correctness evidence ledger

GitHub issues in this repository are the cross-repository evidence index for
bugs found through aspect instrumentation, Hegel histories, runtime observers,
and bounded proofs. The owning library remains responsible for its source fix;
the central issue connects that fix to its reproducer, oracle, validation, and
eventual upstream disposition.

Open one issue per independently actionable root cause, preferably before the
fix commit is published. Use the correctness-evidence issue form and include the
affected repository and exact revision. Addressing commits in any repository
use one of these fully qualified references:

```text
Refs chucklehead-dev/jolt-aspect-packs#123
Fixes chucklehead-dev/jolt-aspect-packs#123
```

Use `Refs` while evidence or remediation is partial. Use `Fixes` only when the
commit actually resolves the issue's stated defect. A passing property is not
enough by itself: retain a known-bad mutation the oracle rejects and a valid
boundary it accepts. Record minimized Hegel seeds, formal witnesses, exact test
commands and totals, and independent review outcomes when available.

Issues fixed only on a user or organization fork remain open with status
**Fixed on fork**. Close them after their intended upstream disposition is
resolved, including an explicit decision not to upstream. Creating or updating
this ledger does not authorize an upstream pull request.

## Upstream submission discipline

Before calling a fix upstream-ready, inspect its semantic neighborhood rather
than only the reported operation. Record the owning abstraction and every
modeled implementer; sibling methods and overloads that share its contract;
result values, exception type and exact message, and validation/effect order;
and the focused unit and JVM/reference-runtime differential rows that cover
that surface.
An explicit evidence-backed `not applicable` is acceptable. An unperformed
neighborhood search is not.

If the fix introduces or moves a helper onto a shared I/O, dispatch,
allocation, locking, or compiler path, characterize caller frequency and retain
base/candidate timings with the same stable workload. Use a monotonic clock and
a regression-sensitive negative control. A green correctness suite does not
establish that a newly shared seam is cheap enough.

Before publication, search source, tests, documentation, issue text, and the
current unreleased changelog for the old limitation, error spelling, divergence
rationale, version literal, and issue identifier. Resolve or explicitly retain
every match. Versions used by executable oracles must have one committed source
of truth, and changelog entries must target the currently verified unreleased
section rather than a previously cut release.

For each upstream submission, keep four identities in the ledger:

1. the submitted and independently reviewed head;
2. the maintainer's final PR head, including substantive follow-up commits;
3. GitHub's recorded merge commit; and
4. the commit currently carrying the patch on upstream `main`, verified by
   ancestry or patch identity after any history rewrite.

Do not treat the PR API's merge SHA as permanent ancestry proof. Recheck the
fourth identity when refreshing a target or closing its ledger issue. Separate
mechanical branch-sync merges from substantive maintainer changes and record
the latter as feedback on the reproducer, implementation, tests, or prose.

Serialize a batch of PRs that edit the same release notes or nearby source.
After the first merges, refresh the next branch, rerun its exact-tip gates, and
update its reviewed head before submission. Preserve meaningful reproducer and
fix commits when upstream benefits from them, but fold purely corrective noise
before asking a maintainer to review the branch.

These checks were reinforced by the September 2026 upstream follow-ups: Jolt
[#854](https://github.com/jolt-lang/jolt/pull/854) extended a `toArray` fix from
one list to the full modeled `Collection` neighborhood; Jolt
[#853](https://github.com/jolt-lang/jolt/pull/853) brought adjacent string
comparison contracts under the same oracle; and Jolt
[#710](https://github.com/jolt-lang/jolt/pull/710) repaired a shared-path
performance regression before merge. Post-merge commits
[`484e6283`](https://github.com/jolt-lang/jolt/commit/484e6283f256c6301f4d32aed55a33bfeccb9b29)
and
[`51f10a02`](https://github.com/jolt-lang/jolt/commit/51f10a0239096f804a6017f850b7b235ebe40168)
show why propagation closure, current-main identity, and release placement are
part of correctness rather than optional cleanup.

## Formal-model anti-vacuity

Every SMT model used as correctness evidence must have a machine-readable
anti-vacuity contract and pass
`bb -cp src -m jolt.aspect-packs.formal-antivacuity CONTRACT.edn`. The checked
implementation or selector branch must be encoded independently of the
reference property: it may not depend on the reference predicate, share a
derived helper with it, or reduce to the same normalized SMT expression through
renamed or multi-hop aliases. Raw input variables may be shared; independently
derived predicates may not. This is a deliberately strong rule. Split common
domain constraints from the two decision encodings instead of exempting a
shared decision helper.

The same model must contain, and Z3 must execute, all three semantic controls:

1. the reference implementation's disagreement query is `unsat`;
2. at least one deliberately faulty implementation's same disagreement query
   is `sat`; and
3. at least one distinct boundary/non-vacuity query is `sat`.

The EDN contract identifies roles and selector values, not assertion names.
Its `:spec` path is resolved relative to the contract file (or may be absolute),
so a pinned consumer can invoke the checker from any working directory.
Each boundary also declares `:accept? true` or `false`; its scoped query must
explicitly assert that both the reference and checked implementation have that
classification. A satisfiable selector assignment alone is not a non-vacuity
witness.

The gate parses SMT-LIB structure, follows defining equalities, selects the
reference implementation branch, compares normalized definition graphs, and
matches scoped assertions to solver results. Grep checks for names are not
evidence of encoding independence. This structural gate complements rather
than replaces a semantic oracle: algebraically disguised duplication and two
independently written encodings with the same mistake remain possible. A
reference routed through a selector-dependent implementation can also evade the
structural comparison when its unresolved and selected expression shapes
differ; the required mutant-SAT query is a tested fail-closed backstop for that
case.

The namespace is in the library's `src` path so dependent repositories can pin
one reviewed `jolt-aspect-packs` revision in `bb.edn` or `-Sdeps` and invoke the
same CLI from that resolved classpath. Its public `analyze!` function returns
the checked definition/control counts and solver results for callers that need
structured evidence; requiring the namespace has no process side effects.

The initial retrospective core.async set is indexed by
[`#1`](https://github.com/chucklehead-dev/jolt-aspect-packs/issues/1). Its fix
commits predate the ledger and retain their published, manifest-pinned SHAs;
their issue bodies provide the retrospective links instead of rewriting
history.
