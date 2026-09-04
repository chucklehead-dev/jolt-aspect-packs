(ns jolt.aspect-packs.experience.provider
  "Canonical provider-neutral history consumer for Samizdat's closed-domain
  decision surface (ADR-002 section 5 in samizdat-agent-bootstrap).

  Five roles, one around-entry each. Every payload is ids, counts, keywords
  and outcomes: no vocabulary items, legality predicates, scorer functions,
  per-candidate scores, prompts, tokens, claim text, code, witnesses,
  connections or native state ever enter history. With no bound journal the
  advice is inert and only calls `proceed`."
  (:require [jolt.aspect-packs.history :as history]))

(def target-revision
  "Exact Samizdat revision carrying the pinned experience manifest."
  "4745c96e08a6e0bf4fb2903374849d745a08da3c")

(def seam-revision
  "Exact reviewed source revision that introduced the decision surface: the
  value of `samizdat.instrumentation/compatibility-id` at `target-revision`."
  "71f24e427649a82db96576694f6967c171e72453")

(def ^:private max-string-length 255)
(def ^:private decisions #{:act :defer})
(def ^:private revalidation-outcomes
  #{:fresh :stale-revision :authority-changed :budget-exceeded :invariant-violated})
(def ^:private based-on-keys
  #{:run/id :branch/id :turn :manifest :graph/revision :state/version})

(defn- invalid! [kind]
  (throw (ex-info "invalid Samizdat experience event" {:kind kind})))

(defn- bounded-string? [value]
  (and (string? value) (<= (count value) max-string-length)))

(defn- bounded-keyword? [value]
  (and (keyword? value) (<= (count (str value)) max-string-length)))

(defn- identifier?
  "An identifier small enough to journal: a bounded string or keyword, a uuid,
  an integer, or nil where the source may legitimately carry none."
  [value]
  (or (nil? value)
      (bounded-string? value)
      (bounded-keyword? value)
      (uuid? value)
      (integer? value)))

(defn- count? [value]
  (and (integer? value) (<= 0 value)))

(defn- number-or-nil? [value]
  (or (nil? value) (number? value)))

(defn- based-on
  "The derivation coordinate, restricted to the six ADR-002 keys and to
  identifier-sized values. Anything else in the map is dropped, never
  journaled; a non-map is nil."
  [value]
  (when (map? value)
    (let [picked (select-keys value based-on-keys)]
      (when-not (every? identifier? (vals picked))
        (invalid! :experience/unbounded-based-on))
      (not-empty picked))))

;; ------------------------------------------------------------ decision domain

(defn- domain-input [[vocabulary opts]]
  (when-not (and (sequential? vocabulary) (map? opts))
    (invalid! :experience/invalid-authorize-args))
  (let [input {:domain-id (:id opts)
               :domain-revision (:revision opts)
               :authority (:authority opts)
               :policy-revision (:policy-revision opts)
               :based-on (based-on (:based-on opts))
               :vocabulary-count (count vocabulary)}]
    (when-not (and (identifier? (:domain-id input))
                   (identifier? (:domain-revision input))
                   (identifier? (:authority input))
                   (identifier? (:policy-revision input)))
      (invalid! :experience/unbounded-domain-identity))
    input))

(defn- domain-return [domain]
  (if (map? domain)
    {:outcome :authorized
     :candidate-count (count (:domain/candidates domain))
     :rejected-count (count (:domain/rejected domain))}
    {:outcome :error}))

(defn around-decision-domain
  "Record one `samizdat.decide/authorize` call: the domain's identity, the
  coordinate it was derived from, and how many candidates the vocabulary held
  and how many survived legality. The vocabulary itself and the legality
  predicate never enter history."
  [join-point args proceed]
  (if-let [journal history/*journal*]
    (history/invoke! journal join-point (domain-input args)
                     {:return-fn domain-return
                      :throw-fn (constantly {:outcome :error})}
                     proceed)
    (proceed)))

;; ------------------------------------------------------------ candidate score

(defn- score-input [[request]]
  (when-not (map? request)
    (invalid! :experience/invalid-decide-args))
  (let [domain (:domain request)
        prov (:prov-ctx request)
        input {:domain-id (when (map? domain) (:domain/id domain))
               :candidate-count (if (map? domain) (count (:domain/candidates domain)) 0)
               :policy-revision (when (map? domain) (:domain/policy-revision domain))
               :scorer-id (when (map? prov) (:scorer-id prov))}]
    (when-not (and (identifier? (:domain-id input))
                   (identifier? (:policy-revision input))
                   (identifier? (:scorer-id input)))
      (invalid! :experience/unbounded-score-identity))
    input))

(defn- score-return
  "The decision record's summary. Never the per-candidate scores, the
  context, or the scorer's raw result; the selected id is an authorized
  candidate's id, which the domain event already exposed as a count."
  [record]
  (if (map? record)
    (let [summary {:decision (:decision record)
                   :reason (:reason record)
                   :selected (:selected record)
                   :margin (:margin record)
                   :entropy (:entropy record)
                   :n-offered (:n-offered record)
                   :n-scored (:n-scored record)
                   :model-state-id (get-in record [:provenance :model-state-id])}]
      (when-not (and (contains? decisions (:decision summary))
                     (identifier? (:reason summary))
                     (identifier? (:selected summary))
                     (number-or-nil? (:margin summary))
                     (number-or-nil? (:entropy summary))
                     (count? (or (:n-offered summary) 0))
                     (count? (or (:n-scored summary) 0))
                     (identifier? (:model-state-id summary)))
        (invalid! :experience/unbounded-score-summary))
      summary)
    {:decision :defer :reason :reason/malformed-record}))

(defn around-candidate-score
  "Record one `samizdat.decide/decide` call: which domain was scored under
  which policy and scorer, and the margin, entropy, decision and model state
  id that came out. The scorer, its context and its scores never enter
  history."
  [join-point args proceed]
  (if-let [journal history/*journal*]
    (history/invoke! journal join-point (score-input args)
                     {:return-fn score-return
                      :throw-fn (constantly {:decision :defer :reason :reason/scorer-threw})}
                     proceed)
    (proceed)))

;; ---------------------------------------------------------------- transition

(defn- transition-input [[outcome domain now]]
  (when-not (and (map? outcome) (map? domain) (map? now))
    (invalid! :experience/invalid-revalidate-args))
  (let [input {:domain-id (:domain/id domain)
               :decision-before (:decision outcome)
               :derived-at (get-in domain [:domain/based-on :state/version])
               :state-version (:state/version now)}]
    (when-not (and (identifier? (:domain-id input))
                   (contains? decisions (:decision-before input))
                   (identifier? (:derived-at input))
                   (identifier? (:state-version input)))
      (invalid! :experience/unbounded-transition-identity))
    input))

(defn- transition-return [result]
  (let [verdict (get-in result [:revalidation :outcome])
        summary {:outcome verdict
                 :decision (:decision result)
                 :reason (when (= :defer (:decision result)) (:reason result))}]
    (when-not (and (contains? revalidation-outcomes verdict)
                   (contains? decisions (:decision summary))
                   (identifier? (:reason summary)))
      (invalid! :experience/unbounded-transition-outcome))
    summary))

(defn around-transition
  "Record one `samizdat.decide/revalidate` call: the domain, the decision
  going in, the state version it was derived at against the current one, and
  the revalidation verdict coming out."
  [join-point args proceed]
  (if-let [journal history/*journal*]
    (history/invoke! journal join-point (transition-input args)
                     {:return-fn transition-return
                      :throw-fn (constantly {:outcome :error})}
                     proceed)
    (proceed)))

;; -------------------------------------------------------------- verification

(defn- verification-input [[_conn firing-id outcome settled-turn]]
  (let [input {:firing-id firing-id :outcome outcome :settled-turn settled-turn}]
    (when-not (and (identifier? firing-id) (bounded-keyword? outcome) (identifier? settled-turn))
      (invalid! :experience/unbounded-verification))
    input))

(defn around-verification
  "Record one `samizdat.store.journal/settle-gate!` call: which firing settled,
  with which outcome, at which turn. The connection never enters history."
  [join-point args proceed]
  (if-let [journal history/*journal*]
    (history/invoke! journal join-point (verification-input args)
                     {:return-fn (constantly {:outcome :settled})
                      :throw-fn (constantly {:outcome :error})}
                     proceed)
    (proceed)))

;; ------------------------------------------------------------------ artifact

(defn- artifact-input [[_conn run-id artifact]]
  (when-not (map? artifact)
    (invalid! :experience/invalid-artifact-args))
  (let [input {:run-id run-id
               :branch-id (:branch-id artifact)
               :turn (:turn artifact)
               :kind (:kind artifact)
               :claim-status (:claim-status artifact)
               :tier (:tier artifact)}]
    (when-not (and (identifier? run-id) (identifier? (:branch-id input)) (identifier? (:turn input))
                   (bounded-keyword? (:kind input))
                   (bounded-keyword? (:claim-status input))
                   (or (nil? (:tier input)) (bounded-keyword? (:tier input))))
      (invalid! :experience/unbounded-artifact))
    input))

(defn around-artifact
  "Record one `samizdat.store.journal/record-artifact!` call: coordinates,
  kind, claim status and tier. The claim, the code, the verdict text and the
  witness never enter history."
  [join-point args proceed]
  (if-let [journal history/*journal*]
    (history/invoke! journal join-point (artifact-input args)
                     {:return-fn (constantly {:outcome :recorded})
                      :throw-fn (constantly {:outcome :error})}
                     proceed)
    (proceed)))

;; ------------------------------------------------------------------ provider

(def roles
  "The five roles this provider binds. ADR-002 names a sixth,
  `:samizdat/state-restore`, on the inference runtime's `load-state!`; that
  entry is not a join point of yogthos/samizdat and no jolt-llama manifest is
  pinned here, so it is deliberately not published until one is."
  #{:samizdat/decision-domain :samizdat/candidate-score :samizdat/transition
    :samizdat/verification :samizdat/artifact})

(def aspect-provider
  {:schema 1
   :libraries {'yogthos/samizdat seam-revision}
   :roles {:samizdat/decision-domain
           {:fn 'jolt.aspect-packs.experience.provider/around-decision-domain
            :contract :args-v1}
           :samizdat/candidate-score
           {:fn 'jolt.aspect-packs.experience.provider/around-candidate-score
            :contract :args-v1}
           :samizdat/transition
           {:fn 'jolt.aspect-packs.experience.provider/around-transition
            :contract :args-v1}
           :samizdat/verification
           {:fn 'jolt.aspect-packs.experience.provider/around-verification
            :contract :args-v1}
           :samizdat/artifact
           {:fn 'jolt.aspect-packs.experience.provider/around-artifact
            :contract :args-v1}}})
