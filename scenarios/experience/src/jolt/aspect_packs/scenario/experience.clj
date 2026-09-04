(ns jolt.aspect-packs.scenario.experience
  "One closed-domain decision end to end through Samizdat's real code: a
  domain authorized from a vocabulary, scored, revalidated twice (stale, then
  fresh), a gate firing settled and an artifact recorded, against an
  in-memory store. Woven, the five join points produce a bounded history;
  plain, the same run produces none."
  (:require [jolt.aspect-packs.history :as history]
            [samizdat.decide :as decide]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]))

;; Single-token candidates: decide compares scores only across candidates of
;; equal token length (carried from the jolt-llama exactness measurement).
(def vocabulary
  [{:id :hold :op :continue :tokens [11] :target "private hold target"}
   {:id :branch :op :branch :tokens [12] :target "private branch target"}
   {:id :escalate :op :escalate :tokens [13] :target "private escalate target"}])

(defn- legality [c]
  (not= :escalate (:id c)))

(def ^:private log-scores {:hold -0.2 :branch -2.5})

(defn- scorer [_context candidates]
  ;; An earlier draft wrote (juxt :id log-scores), which applies the map to
  ;; the candidate, not to its id, and so scored nothing; decide recorded
  ;; that as :missing evidence and deferred, which is the fail-closed
  ;; behaviour the compiled gate then caught. Same on the JVM; not a Jolt
  ;; discrepancy.
  {:scores (into {} (map (fn [c] [(:id c) (get log-scores (:id c))])) candidates)
   :meta {:scorer-id "scenario-scorer" :model-state-id "ms-scenario"}})

(defn- open-run!
  "The run row the journal's foreign keys need. Written directly rather than
  through samizdat.store.runs/start-run!, which reads the retention policy
  from userspace: a compiled scenario embeds no policy resources, and the
  scenario is about the decision surface, not the policy loader."
  [conn]
  (let [run-id (str (random-uuid))]
    (db/with-writer
      (db/execute! conn
                   ["INSERT INTO runs (id, problem, status, started_at)
                     VALUES (?, ?, 'running', ?)"
                    run-id "private scenario problem" (db/now)]))
    run-id))

(defn- run-decision! []
  (let [conn (db/open! ":memory:")]
    (try
      (let [run-id (open-run! conn)
            domain (decide/authorize
                    vocabulary
                    {:id "dom-scenario" :revision 1 :authority :ops
                     :legality {:legality/pred legality
                                :legality/source :scenario
                                :legality/revision "private legality revision"}
                     :based-on {:run/id run-id :branch/id "B1" :turn 3
                                :manifest :loop :graph/revision "g1"
                                :state/version 41}
                     :policy-revision "pol-scenario"})
            record (decide/decide {:scorer scorer :domain domain
                                   :policy {:min-margin 0.5}
                                   :context {:prompt "private prompt"}
                                   :prov-ctx {:scorer-id "scenario-scorer"}})
            outcome (select-keys record [:decision :reason :selected :margin])
            stale (decide/revalidate outcome domain {:state/version 42 :authority :ops})
            fresh (decide/revalidate outcome domain {:state/version 41 :authority :ops})
            firing (journal/record-gate! conn run-id
                                         {:branch-id "B1" :turn 3 :gate :scenario-gate
                                          :priority 1 :message "private gate message"
                                          :prediction "private prediction" :window 2})]
        (journal/settle-gate! conn firing :confirmed 5)
        (journal/record-artifact! conn run-id
                                  {:branch-id "B1" :turn 5 :kind :smt :tier :fast
                                   :claim "private claim text" :code "(assert private)"
                                   :claim-status :confirmed})
        {:domain domain :record record :stale stale :fresh fresh :firing firing})
      (finally (db/close conn)))))

(defn- assert-results! [{:keys [domain record stale fresh]}]
  (when-not (= [:hold :branch] (mapv :id (:domain/candidates domain)))
    (throw (ex-info "authorize changed the legal candidates" {:domain domain})))
  (when-not (and (= :act (:decision record)) (= :hold (:selected record))
                 (number? (:entropy record)))
    (throw (ex-info "decide changed its outcome" {:record record})))
  (when-not (and (= :defer (:decision stale))
                 (= :stale-revision (get-in stale [:revalidation :outcome])))
    (throw (ex-info "stale revalidation changed" {:stale stale})))
  (when-not (and (= :act (:decision fresh))
                 (= :fresh (get-in fresh [:revalidation :outcome])))
    (throw (ex-info "fresh revalidation changed" {:fresh fresh}))))

(def expected-operations
  [:samizdat.decide/decision-domain
   :samizdat.decide/candidate-score
   :samizdat.decide/transition
   :samizdat.decide/transition
   :samizdat.store.journal/verification
   :samizdat.store.journal/artifact])

(defn- assert-woven-history! [journal results]
  (let [events (history/events journal)
        invokes (filterv #(= :invoke (:phase %)) events)
        terminals (filterv #(not= :invoke (:phase %)) events)
        by-op (group-by :operation invokes)]
    (history/assert-complete! journal)
    (when-not (= 12 (count events))
      (throw (ex-info "woven experience history has the wrong event count"
                      {:count (count events) :events events})))
    (when-not (= expected-operations (mapv :operation invokes))
      (throw (ex-info "woven experience history has the wrong operations"
                      {:operations (mapv :operation invokes)})))
    (when-not (every? #(= :return (:phase %)) terminals)
      (throw (ex-info "a woven experience operation did not return" {:terminals terminals})))
    (when-not (every? #(= :experience-scenario (:context-id %)) invokes)
      (throw (ex-info "woven experience history lost caller context" {:invokes invokes})))
    (let [domain-invoke (first (by-op :samizdat.decide/decision-domain))
          score-terminal (second (filter #(= (:operation-id (first (by-op :samizdat.decide/candidate-score)))
                                             (:operation-id %))
                                         events))
          transitions (by-op :samizdat.decide/transition)]
      (when-not (= {:domain-id "dom-scenario" :domain-revision 1 :authority :ops
                    :policy-revision "pol-scenario"
                    :based-on {:run/id (get-in results [:domain :domain/based-on :run/id])
                               :branch/id "B1" :turn 3 :manifest :loop
                               :graph/revision "g1" :state/version 41}
                    :vocabulary-count 3}
                   (:input domain-invoke))
        (throw (ex-info "woven domain input is not the bounded coordinate"
                        {:input (:input domain-invoke)})))
      (when-not (and (= :act (get-in score-terminal [:value :decision]))
                     (= :hold (get-in score-terminal [:value :selected]))
                     (= "ms-scenario" (get-in score-terminal [:value :model-state-id]))
                     (number? (get-in score-terminal [:value :entropy])))
        (throw (ex-info "woven score terminal lost the decision summary"
                        {:terminal score-terminal})))
      (when-not (= [{:derived-at 41 :state-version 42} {:derived-at 41 :state-version 41}]
                   (mapv #(select-keys (:input %) [:derived-at :state-version]) transitions))
        (throw (ex-info "woven transitions lost the version comparison"
                        {:transitions transitions}))))
    (when (.contains (pr-str events) "private")
      (throw (ex-info "woven experience history retained private material" {})))
    (when (.contains (pr-str events) "-0.2")
      (throw (ex-info "woven experience history retained a candidate score" {})))))

(defn -main [& args]
  (let [plain? (= ["plain"] (vec args))
        journal (history/journal)
        results (binding [history/*journal* journal
                          history/*context-id* :experience-scenario]
                  (run-decision!))]
    (assert-results! results)
    (if plain?
      (when (seq (history/events journal))
        (throw (ex-info "plain experience build ran aspect advice"
                        {:events (history/events journal)})))
      (assert-woven-history! journal results))
    (println (if plain?
               "experience plain scenario remained uninstrumented"
               "experience aspect scenario ran"))))
