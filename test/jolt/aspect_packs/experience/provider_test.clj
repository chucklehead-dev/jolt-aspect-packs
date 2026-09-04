(ns jolt.aspect-packs.experience.provider-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [jolt.aspect-packs.experience.provider :as provider]
            [jolt.aspect-packs.history :as history]))

(defn- join-point [id]
  {:id id
   :site-id (str (name id) "-site")
   :build-identity "experience-provider-test-build"})

(def domain-join-point (join-point :samizdat.decide/decision-domain))
(def score-join-point (join-point :samizdat.decide/candidate-score))
(def transition-join-point (join-point :samizdat.decide/transition))
(def verification-join-point (join-point :samizdat.store.journal/verification))
(def artifact-join-point (join-point :samizdat.store.journal/artifact))

;; The shapes below mirror what canonical's samizdat.decide produces; the
;; private material (vocabulary entries, the legality predicate, the scorer,
;; the context, per-candidate scores, claim text, code) is marked so a
;; rendered history can be searched for it.

(def vocabulary
  [{:id :continue :op :continue :target "PRIVATE-VOCAB-continue"}
   {:id :block :op :block :target "PRIVATE-VOCAB-block"}
   {:id :escalate :op :escalate :target "PRIVATE-VOCAB-escalate"}])

(def based-on
  {:run/id "run-1" :branch/id "b0" :turn 7 :manifest :beam
   :graph/revision "g-9" :state/version 41
   :prompt "PRIVATE-PROMPT-in-based-on"})

(def authorize-opts
  {:id "dom-1" :revision 3 :authority :arbiter
   :legality {:legality/pred (fn [c] (not= :escalate (:id c)))
              :legality/source :gates :legality/revision "PRIVATE-LEGALITY"}
   :based-on based-on
   :policy-revision "pol-2"})

(def domain
  {:domain/id "dom-1" :domain/revision 3 :domain/authority :arbiter
   :domain/legality-source :gates :domain/legality-revision "PRIVATE-LEGALITY"
   :domain/candidates (vec (take 2 vocabulary))
   :domain/rejected [:escalate]
   :domain/based-on (dissoc based-on :prompt)
   :domain/policy-revision "pol-2"})

(def decide-request
  {:scorer (fn [_ _] {:scores {:continue -0.1 :block -2.3}})
   :domain domain
   :policy {:margin 0.5}
   :context {:prompt "PRIVATE-PROMPT-context" :tokens [1 2 3]}
   :prov-ctx {:scorer-id "scorer-a" :model-id "PRIVATE-MODEL-PATH"
              :session "PRIVATE-SESSION-HANDLE"}})

(def record
  {:decision :act :reason :reason/clear-winner :selected :continue
   :margin 2.2 :entropy 0.1 :n-offered 2 :n-scored 2
   :domain [{:id :continue :score -0.1} {:id :block :score -2.3}]
   :provenance {:scorer-id "scorer-a" :model-state-id "ms-77"}})

(defn- run-with-journal [journal advice jp args value]
  (binding [history/*journal* journal history/*context-id* :turn-7]
    (advice jp args (fn [] value))))

(defn- pair [journal]
  (let [[invoke terminal] (history/events journal)]
    [invoke terminal]))

(deftest decision-domain-records-identity-coordinate-and-counts-only
  (let [journal (history/journal)
        result (run-with-journal journal provider/around-decision-domain
                                 domain-join-point [vocabulary authorize-opts] domain)
        [invoke terminal] (pair journal)]
    (is (identical? domain result))
    (is (= :samizdat.decide/decision-domain (:operation invoke)))
    (is (= :turn-7 (:context-id invoke)))
    (is (= {:domain-id "dom-1" :domain-revision 3 :authority :arbiter
            :policy-revision "pol-2"
            :based-on {:run/id "run-1" :branch/id "b0" :turn 7 :manifest :beam
                       :graph/revision "g-9" :state/version 41}
            :vocabulary-count 3}
           (:input invoke)))
    (is (= :return (:phase terminal)))
    (is (= {:outcome :authorized :candidate-count 2 :rejected-count 1}
           (:value terminal)))
    (is (true? (history/assert-complete! journal)))))

(deftest candidate-score-records-the-summary-never-the-scores
  (let [journal (history/journal)
        result (run-with-journal journal provider/around-candidate-score
                                 score-join-point [decide-request] record)
        [invoke terminal] (pair journal)]
    (is (identical? record result))
    (is (= {:domain-id "dom-1" :candidate-count 2 :policy-revision "pol-2"
            :scorer-id "scorer-a"}
           (:input invoke)))
    (is (= {:decision :act :reason :reason/clear-winner :selected :continue
            :margin 2.2 :entropy 0.1 :n-offered 2 :n-scored 2
            :model-state-id "ms-77"}
           (:value terminal)))
    (is (true? (history/assert-complete! journal)))))

(deftest transition-records-versions-and-the-revalidation-verdict
  (let [journal (history/journal)
        outcome {:decision :act :selected :continue :margin 2.2}
        now {:state/version 42 :authority :arbiter}
        deferred {:decision :defer :reason :reason/stale-revision
                  :would-have-selected :continue :selected nil
                  :revalidated? true
                  :revalidation {:state/version 42 :derived-at 41 :outcome :stale-revision}}
        result (run-with-journal journal provider/around-transition
                                 transition-join-point [outcome domain now] deferred)
        [invoke terminal] (pair journal)]
    (is (identical? deferred result))
    (is (= {:domain-id "dom-1" :decision-before :act :derived-at 41 :state-version 42}
           (:input invoke)))
    (is (= {:outcome :stale-revision :decision :defer :reason :reason/stale-revision}
           (:value terminal)))
    (testing "a fresh apply carries no reason"
      (let [fresh-journal (history/journal)
            fresh (assoc outcome :revalidated? true
                         :revalidation {:state/version 41 :derived-at 41 :outcome :fresh})]
        (run-with-journal fresh-journal provider/around-transition
                          transition-join-point [outcome domain {:state/version 41}] fresh)
        (is (= {:outcome :fresh :decision :act :reason nil}
               (:value (second (pair fresh-journal)))))))
    (is (true? (history/assert-complete! journal)))))

(deftest verification-and-artifact-record-coordinates-and-outcomes-only
  (let [conn (Object.)
        journal (history/journal)]
    (run-with-journal journal provider/around-verification
                      verification-join-point [conn 12 :confirmed 9] nil)
    (run-with-journal journal provider/around-artifact
                      artifact-join-point
                      [conn "run-1" {:branch-id "b0" :turn 9 :kind :proof
                                     :claim "PRIVATE-CLAIM text"
                                     :code "(PRIVATE-CODE)"
                                     :verdict :pass
                                     :witness {:secret "PRIVATE-WITNESS"}
                                     :claim-status :confirmed :tier :fast}]
                      nil)
    (let [[v-invoke v-terminal a-invoke a-terminal] (history/events journal)]
      (is (= {:firing-id 12 :outcome :confirmed :settled-turn 9} (:input v-invoke)))
      (is (= {:outcome :settled} (:value v-terminal)))
      (is (= {:run-id "run-1" :branch-id "b0" :turn 9 :kind :proof
              :claim-status :confirmed :tier :fast}
             (:input a-invoke)))
      (is (= {:outcome :recorded} (:value a-terminal))))
    (is (true? (history/assert-complete! journal)))))

(deftest no-private-material-reaches-a-rendered-history
  (let [journal (history/journal)
        conn (Object.)]
    (run-with-journal journal provider/around-decision-domain
                      domain-join-point [vocabulary authorize-opts] domain)
    (run-with-journal journal provider/around-candidate-score
                      score-join-point [decide-request] record)
    (run-with-journal journal provider/around-transition
                      transition-join-point
                      [{:decision :act} domain {:state/version 41}]
                      {:decision :act :revalidation {:outcome :fresh}})
    (run-with-journal journal provider/around-verification
                      verification-join-point [conn 12 :confirmed 9] nil)
    (run-with-journal journal provider/around-artifact
                      artifact-join-point
                      [conn "run-1" {:branch-id "b0" :turn 9 :kind :proof
                                     :claim "PRIVATE-CLAIM" :code "PRIVATE-CODE"
                                     :claim-status :confirmed}]
                      nil)
    (let [rendered (pr-str (history/events journal))]
      (is (not (.contains rendered "PRIVATE")))
      (is (not (.contains rendered "-0.1")) "no per-candidate score")
      (is (not (.contains rendered "Object")) "no connection")
      (is (not (.contains rendered ":scores"))))))

(deftest throws-preserve-identity-and-close-with-an-error-terminal
  (let [journal (history/journal)
        boom (ex-info "PRIVATE-FAILURE" {:secret "PRIVATE-DATA"})
        thrown (try
                 (binding [history/*journal* journal]
                   (provider/around-decision-domain
                    domain-join-point [vocabulary authorize-opts] #(throw boom)))
                 (catch Exception e e))
        [_ terminal] (pair journal)]
    (is (identical? boom thrown))
    (is (= :throw (:phase terminal)))
    (is (= {:outcome :error} (:value terminal)))
    (is (not (.contains (pr-str (history/events journal)) "PRIVATE")))
    (is (true? (history/assert-complete! journal)))))

(deftest unbounded-or-malformed-source-values-fail-closed-under-a-journal
  (let [oversized (apply str (repeat 256 "x"))
        journal (history/journal)]
    (doseq [[advice jp args]
            [[provider/around-decision-domain domain-join-point
              [vocabulary (assoc authorize-opts :id oversized)]]
             [provider/around-decision-domain domain-join-point
              [vocabulary (assoc authorize-opts :based-on {:run/id {:nested "map"}})]]
             [provider/around-decision-domain domain-join-point
              ["not-a-vocabulary" authorize-opts]]
             [provider/around-candidate-score score-join-point
              [(assoc-in decide-request [:prov-ctx :scorer-id] (Object.))]]
             [provider/around-candidate-score score-join-point ["not-a-map"]]
             [provider/around-transition transition-join-point
              [{:decision :maybe} domain {:state/version 41}]]
             [provider/around-verification verification-join-point
              [(Object.) 12 "confirmed" 9]]
             [provider/around-artifact artifact-join-point
              [(Object.) "run-1" {:kind "proof" :claim-status :confirmed}]]]]
      (is (thrown-with-msg? Exception #"invalid Samizdat experience event"
                            (run-with-journal journal advice jp args nil))
          (pr-str (:id jp))))
    (is (empty? (history/events journal))
        "a refused input never opens an operation")
    (testing "a return that is not a summary is refused after the call ran"
      ;; The history ABI's contract: a recorder fault after application
      ;; success fails closed, so the operation stays open and the journal
      ;; is visibly incomplete rather than carrying the unbounded value.
      (doseq [[advice jp args value]
              [[provider/around-candidate-score score-join-point [decide-request]
                {:decision :act :selected {:secret "PRIVATE-SELECTION"}}]
               [provider/around-transition transition-join-point
                [{:decision :act} domain {:state/version 41}]
                {:decision :act :revalidation {:outcome :whatever}}]]]
        (let [open (history/journal)]
          (is (thrown-with-msg? Exception #"invalid Samizdat experience event"
                                (run-with-journal open advice jp args value)))
          (is (thrown? Exception (history/assert-complete! open)))
          (is (not (.contains (pr-str (history/events open)) "PRIVATE"))))))))

(deftest absent-journal-is-inert-and-does-not-validate-or-retain
  (let [private-args ["PRIVATE" {:id (Object.)}]
        sentinel (Object.)]
    (doseq [advice [provider/around-decision-domain provider/around-candidate-score
                    provider/around-transition provider/around-verification
                    provider/around-artifact]]
      (is (identical? sentinel
                      (advice (join-point :any) private-args (fn [] sentinel)))))))

(deftest manifest-provider-and-exact-revisions-agree
  (let [path (str "resources/META-INF/jolt/aspects/packs/experience-"
                  (subs provider/seam-revision 0 7) ".edn")
        manifest (edn/read-string (slurp path))
        by-id (into {} (map (juxt :id identity)) (:aspects manifest))]
    (is (.exists (io/file path)))
    (is (re-matches #"[0-9a-f]{40}" provider/target-revision))
    (is (re-matches #"[0-9a-f]{40}" provider/seam-revision))
    (is (= provider/seam-revision (get-in manifest [:library :version])))
    (is (= 'yogthos/samizdat (get-in manifest [:library :id])))
    (is (= provider/seam-revision
           (get-in provider/aspect-provider [:libraries 'yogthos/samizdat])))
    (is (= provider/roles (set (keys (:roles provider/aspect-provider)))))
    (is (= provider/roles (set (map :advice-role (:aspects manifest)))))
    (doseq [[id entry arity role fn-sym]
            [[:samizdat.decide/decision-domain 'samizdat.decide/authorize 2
              :samizdat/decision-domain
              'jolt.aspect-packs.experience.provider/around-decision-domain]
             [:samizdat.decide/candidate-score 'samizdat.decide/decide 1
              :samizdat/candidate-score
              'jolt.aspect-packs.experience.provider/around-candidate-score]
             [:samizdat.decide/transition 'samizdat.decide/revalidate 3
              :samizdat/transition
              'jolt.aspect-packs.experience.provider/around-transition]
             [:samizdat.store.journal/verification 'samizdat.store.journal/settle-gate! 4
              :samizdat/verification
              'jolt.aspect-packs.experience.provider/around-verification]
             [:samizdat.store.journal/artifact 'samizdat.store.journal/record-artifact! 3
              :samizdat/artifact
              'jolt.aspect-packs.experience.provider/around-artifact]]]
      (testing (pr-str id)
        (is (= {:entry entry :arity arity} (get-in by-id [id :match])))
        (is (= role (get-in by-id [id :advice-role])))
        (is (= 1 (get-in by-id [id :expect :matches])))
        (is (= fn-sym (get-in provider/aspect-provider [:roles role :fn])))
        (is (= :args-v1 (get-in provider/aspect-provider [:roles role :contract])))
        (is (some? (resolve fn-sym)))))))
