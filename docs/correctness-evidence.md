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

The initial retrospective core.async set is indexed by
[`#1`](https://github.com/chucklehead-dev/jolt-aspect-packs/issues/1). Its fix
commits predate the ledger and retain their published, manifest-pinned SHAs;
their issue bodies provide the retrospective links instead of rewriting
history.
