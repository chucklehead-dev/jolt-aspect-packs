# Experience pack

This pack consumes Samizdat's closed-domain decision surface, the five
join points that ADR-002 (section 5, in `samizdat-agent-bootstrap`) names
for tying a DecisionDomain, its scoring, the apply-time revalidation, a gate
settlement and a machine-checked artifact to one experience record. The
manifest is `resources/META-INF/jolt/aspects/packs/experience-71f24e4.edn`; the
same selectors ship inert inside `yogthos/samizdat` as
`META-INF/jolt/aspects/samizdat-m2-experience.edn`. The library revision
that introduced the surface, and therefore the value of
`samizdat.instrumentation/compatibility-id` the manifest pins, is
`71f24e427649a82db96576694f6967c171e72453`; the inspected source revision that carries that
pin is `4745c96e08a6e0bf4fb2903374849d745a08da3c`.

| role | entry | input | terminal |
|---|---|---|---|
| `:samizdat/decision-domain` | `samizdat.decide/authorize` 2 | domain id, revision, authority, policy revision, the six-key `:based-on` coordinate, vocabulary count | `:authorized` with candidate and rejected counts |
| `:samizdat/candidate-score` | `samizdat.decide/decide` 1 | domain id, candidate count, policy revision, scorer id | decision, reason, selected id, margin, entropy, offered and scored counts, model state id |
| `:samizdat/transition` | `samizdat.decide/revalidate` 3 | domain id, decision going in, derived-at and current state versions | revalidation verdict, decision coming out, deferral reason |
| `:samizdat/verification` | `samizdat.store.journal/settle-gate!` 4 | firing id, outcome, settled turn | `:settled` |
| `:samizdat/artifact` | `samizdat.store.journal/record-artifact!` 3 | run, branch, turn, kind, claim status, tier | `:recorded` |

Every input and terminal is identifiers, counts, keywords and numbers. The
vocabulary entries and the legality predicate, the scorer function, its
context and its per-candidate scores, the prompt and tokens, the database
connection, the claim text, the code, the verdict text and the witness never
enter history. Identifiers are bounded to 255 characters; `:based-on` is
restricted to `:run/id`, `:branch/id`, `:turn`, `:manifest`,
`:graph/revision` and `:state/version` and anything else in that map is
dropped. A source value outside those bounds, or a return that is not a
summary of the expected shape, fails closed under a bound journal and never
opens an operation; with no journal bound every advice is inert and only
calls `proceed`. Application results and thrown identity are preserved; a
throw closes the operation with `{:outcome :error}` and nothing from the
exception.

ADR-002 names a sixth role, `:samizdat/state-restore`, on the inference
runtime's `load-state!`. That entry belongs to jolt-llama, not to
`yogthos/samizdat`, and no jolt-llama manifest is pinned in this repository,
so the role is deliberately not published here. It is recorded as a gap, not
implied by an unbound advice function.

Run the focused conformance test with the mandatory Chez wrapper:

```sh
JOLT_CACHE_DIR=/tmp/jolt-aspect-packs-experience-cache \
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  jolt -A:test -e \
  "(require 'jolt.aspect-packs.experience.provider-test :reload) \
   (clojure.test/run-tests 'jolt.aspect-packs.experience.provider-test)"
```

The test does not need the native Hegel library. Compiled woven and plain
scenarios for this pack, and the effect evidence the central contract
requires of them, are not yet built: Samizdat's experience surface has no
scenario under `scenarios/` and `targets.edn` records the pack without
`:scenario` or `:effect-evidence` until one exists.
