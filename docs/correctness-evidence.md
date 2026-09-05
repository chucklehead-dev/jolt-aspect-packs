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

## Formal-model anti-vacuity

Every SMT model used as correctness evidence must have a machine-readable
anti-vacuity contract and pass `test/formal/formal_antivacuity.clj`. The checked
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

The initial retrospective core.async set is indexed by
[`#1`](https://github.com/chucklehead-dev/jolt-aspect-packs/issues/1). Its fix
commits predate the ledger and retain their published, manifest-pinned SHAs;
their issue bodies provide the retrospective links instead of rewriting
history.
