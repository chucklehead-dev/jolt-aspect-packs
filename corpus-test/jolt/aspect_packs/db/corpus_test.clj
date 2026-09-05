(ns jolt.aspect-packs.db.corpus-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [hegel.corpus :as corpus]
            [hegel.corpus.digest :as digest]
            [jolt.aspect-packs.db.corpus-consumer :as consumer]
            [jolt.aspect-packs.db.corpus-profile :as profile]
            [jolt.aspect-packs.db.model :as model]))

(defn- fixture []
  (let [expected (edn/read-string (slurp "corpus-fixtures/db-v1-pin.edn"))
        envelope (corpus/decode (slurp "corpus-fixtures/db-v1.edn"))]
    {:expected expected :envelope envelope
     :payload (corpus/consume! expected envelope)}))

(defn- error-data [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))

(defn- semantic-candidate [expected payload]
  ;; Deliberately authorize a mutated candidate in a test-only pin so the
  ;; privacy/model gate, not the outer digest gate, must catch its defect.
  (let [envelope (corpus/seal payload)]
    [(assoc expected :sha256 (:sha256 envelope)) envelope]))

(deftest committed-fixture-reaches-the-existing-model-with-all-witness-families
  (let [{:keys [expected envelope payload]} (fixture)
        calls (atom 0)
        original model/check!]
    (with-redefs [model/check! (fn [events] (swap! calls inc) (original events))]
      (is (= payload (consumer/consume! expected envelope)))
      (is (= 4 @calls)))
    (doseq [events (:values payload)]
      (is (= #{:select-returned :mutation-affected :shaped-throw :nested-lifecycle}
             (profile/witness-families events))))))

(deftest semantic-mutants-reach-and-fail-the-intended-model-rule
  (let [{:keys [expected payload]} (fixture)]
    (doseq [[path value rule]
            [[[:values 0 1 :seq] 1 :db/contiguous-history]
             [[:values 0 1 :context-id] :synthetic/db-other :db/context-coherence]
             [[:values 0 1 :causal-links] [99] :db/canonical-causal-links]
             [[:values 0 2 :value :row-count] -1 :db/shaped-terminals]]]
      (let [[pin envelope] (semantic-candidate expected (assoc-in payload path value))
            data (error-data #(consumer/consume! pin envelope))]
        (is (= rule (:hegel.trace/rule data)))))))

(deftest fixture-privacy-rejects-raw-fields-before-any-model-call
  (let [{:keys [expected payload]} (fixture)]
    (doseq [changed [(assoc-in payload [:values 0 0 :sql] "DO-NOT-ECHO")
                     (assoc-in payload [:values 0 0 :input :parameters] ["DO-NOT-ECHO"])
                     (assoc-in payload [:values 0 2 :value :rows] [["DO-NOT-ECHO"]])
                     (assoc-in payload [:values 0 0 :site-id] "DO-NOT-ECHO")
                     (assoc-in payload [:values 0 5 :value :error-type] "DO-NOT-ECHO")
                     ;; All entries are preflighted, not only the first.
                     (assoc-in payload [:values 3 0 :context-id] "DO-NOT-ECHO")]]
      (let [[pin envelope] (semantic-candidate expected changed)
            calls (atom 0)
            data (with-redefs [model/check! (fn [_] (swap! calls inc))]
                   (error-data #(consumer/consume! pin envelope)))]
        (is (= :jolt.aspect-packs.db.corpus-profile/invalid-fixture (:type data)))
        (is (zero? @calls))
        (is (not (.contains (pr-str data) "DO-NOT-ECHO")))))))

(deftest corrupt-or-stale-transport-never-reaches-the-model
  (let [{:keys [expected envelope payload]} (fixture)
        changed (corpus/seal (assoc-in payload [:values 0 2 :value :row-count] 23))
        deep-text (str (apply str (repeat 34 "[")) (apply str (repeat 34 "]")))
        deep-hash (digest/sha256 deep-text)]
    (doseq [[pin artifact expected-reason]
            [[expected (assoc envelope :sha256 (apply str (repeat 64 "0")))
              :payload-digest-mismatch]
             [expected changed :expected-digest-mismatch]
             [(assoc-in expected [:provenance :seam-revision] "stale")
              envelope :expected-provenance-mismatch]
             [(assoc expected :count 3) envelope :expected-count-mismatch]
             [(assoc expected :sha256 deep-hash)
              (assoc envelope :payload deep-text :sha256 deep-hash) :max-depth]]]
      (let [calls (atom 0)
            data (with-redefs [model/check! (fn [_] (swap! calls inc))]
                   (error-data #(consumer/consume! pin artifact)))]
        (is (= expected-reason (:reason data)))
        (is (zero? @calls))))))

(deftest missing-witness-families-cannot-pass-as-a-nonempty-corpus
  (let [{:keys [expected payload]} (fixture)
        events (first (:values payload))
        select-only [(first events) (assoc (nth events 3) :seq 2)]
        changed (assoc payload :values (vec (repeat 4 select-only)))
        [pin envelope] (semantic-candidate expected changed)
        data (error-data #(consumer/consume! pin envelope))]
    (is (= :jolt.aspect-packs.db.corpus-consumer/missing-witnesses (:type data)))
    (is (= #{:mutation-affected :shaped-throw :nested-lifecycle}
           (set (:missing data))))))

(deftest fixture-profile-is-not-a-silent-change-to-the-general-model
  (let [events (-> (fixture) :payload :values first)
        extended (assoc-in events [0 :arbitrary-metadata] "DO-NOT-ECHO")]
    (is (= extended (model/check! extended)))
    (is (= :jolt.aspect-packs.db.corpus-profile/invalid-fixture
           (:type (error-data #(profile/validate! extended)))))))

(deftest committed-pins-track-the-selected-model-seam-and-new-mode-dependencies
  (let [provenance (:provenance (:expected (fixture)))
        deps (edn/read-string (slurp "deps.edn"))
        bb-config (edn/read-string (slurp "corpus-bb.edn"))
        targets (edn/read-string (slurp "targets.edn"))
        generation (edn/read-string (slurp "corpus-fixtures/db-generation.edn"))
        lib 'io.github.chucklehead-dev/jolt-hegel]
    (is (= (str "sha256:" (digest/sha256
                           (slurp "models/jolt/aspect_packs/db/model.clj")))
           (:model-revision provenance)))
    (is (= (:seam-revision provenance)
           (get-in targets [:targets 'jolt-lang/db :seam-revision])))
    (doseq [alias [:db-corpus-live :db-corpus-offline]]
      (is (= (:hegel-sha provenance)
             (get-in deps [:aliases alias :extra-deps lib :git/sha]))))
    (is (= (:hegel-sha provenance) (get-in bb-config [:deps lib :git/sha])))
    (is (= provenance (:provenance generation)))))
