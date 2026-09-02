(ns jolt.aspect-packs.regression-coverage-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.aspect-packs.regression-coverage :as coverage]))

(def catalog
  {:schema 1
   :cases
   [{:id :fixture/issue-2
     :script "test/fixtures/regression-coverage/issue_2.clj"
     :issue "https://github.com/chucklehead-dev/jolt-aspect-packs/issues/2"
     :bad-sha "0000000000000000000000000000000000000000"
     :fixed-sha "1111111111111111111111111111111111111111"
     :timeout-ms 1000
     :expected {:pass "FIXTURE PASS issue-2"
                :fail "FIXTURE FAIL issue-2"}
     :provenance
     {:aspect {:runtime-required? false :status :portable-public-api-extraction}
      :hegel {:runtime-required? false :status :deterministic-witness}
      :checker {:source :fixture :status :independently-runnable}}}]})

(def manifest
  {:schema 2
   :repository "chucklehead-dev/jolt-aspect-packs"
   :label "status:fixed-in-fork"
   :captured-at "2026-08-30T00:00:00Z"
   :fork-fixed-issues [1 2]
   :known-missing [{:issue 1 :reason "not extracted"
                    :evidence
                    {:repository "casselc/jolt"
                     :sha "4444444444444444444444444444444444444444"
                     :path "test/chez/example-test.clj"}}]})

(defn codes [result]
  (set (map :code (:problems result))))

(deftest valid-debt-baseline-distinguishes-portable-and-missing-cases
  (is (= {:status :valid :fork-fixed 2 :portable 1 :known-missing 1
          :live-checked? false}
         (coverage/check catalog manifest)))
  (is (= true (:live-checked? (coverage/check catalog manifest [1 2])))))

(deftest cross-repository-exact-source-provenance-is-supported
  (let [cross-repo (assoc-in manifest
                             [:known-missing 0 :evidence :repository]
                             "chucklehead-dev/time")]
    (is (= :valid (:status (coverage/check catalog cross-repo))))
    (doseq [repository ["missing-slash" "/repo" "owner/" "owner/repo/extra"
                        "owner repo/project" "-owner/repo" "owner-/repo"
                        "owner/.."]]
      (is (contains?
           (codes (coverage/check
                   catalog
                   (assoc-in manifest
                             [:known-missing 0 :evidence :repository]
                             repository)))
           :debt/evidence)))))

(deftest newly-fixed-issue-and-dropped-or-stale-debt-fail-closed
  (testing "a live label not represented by the checked snapshot fails"
    (is (contains? (codes (coverage/check catalog manifest [1 2 3]))
                   :live/snapshot-drift)))
  (testing "a missing debt row cannot disappear without a portable case"
    (is (contains? (codes (coverage/check catalog
                                           (assoc manifest :known-missing [])))
                   :coverage/debt-drift)))
  (testing "a new portable case makes its old debt row stale"
    (let [new-case (-> (first (:cases catalog))
                       (assoc :id :fixture/issue-1
                              :script "test/fixtures/regression-coverage/issue_1.clj"
                              :issue (str "https://github.com/chucklehead-dev/"
                                          "jolt-aspect-packs/issues/1")
                              :bad-sha "2222222222222222222222222222222222222222"
                              :fixed-sha "3333333333333333333333333333333333333333"
                              :expected {:pass "FIXTURE PASS issue-1"
                                         :fail "FIXTURE FAIL issue-1"}))
          catalog (update catalog :cases conj new-case)]
      (is (contains? (codes (coverage/check catalog manifest))
                     :coverage/debt-drift)))))

(deftest url-only-case-cannot-retire-portable-regression-debt
  (let [fake-case {:issue (str "https://github.com/chucklehead-dev/"
                               "jolt-aspect-packs/issues/1")}
        result (coverage/check (update catalog :cases conj fake-case) manifest)]
    (is (= :invalid (:status result)))
    (is (contains? (codes result) :catalog/case-keys))
    (is (not (contains? (codes result) :coverage/debt-drift)))))

(deftest non-executable-case-metadata-cannot-retire-debt
  (let [fake-case (-> (first (:cases catalog))
                      (assoc :id :fixture/issue-1
                             :issue (str "https://github.com/chucklehead-dev/"
                                         "jolt-aspect-packs/issues/1")
                             :expected {:pass (str "NEVER" " PASS issue-1")
                                        :fail (str "NEVER" " FAIL issue-1")}))
        result (coverage/check
                (update catalog :cases conj fake-case)
                (assoc manifest :known-missing []))]
    (is (= :invalid (:status result)))
    (is (contains? (codes result) :catalog/script-signatures))
    (is (contains? (codes result) :coverage/debt-drift))))

(deftest case-commit-pair-must-distinguish-bad-and-fixed-builds
  (let [case (first (:cases catalog))
        result (coverage/check
                (assoc catalog :cases
                       [(assoc case :fixed-sha (:bad-sha case))])
                manifest)]
    (is (= :invalid (:status result)))
    (is (contains? (codes result) :catalog/sha-distinction))))

(deftest malformed-snapshot-debt-and-case-url-are-rejected
  (is (contains? (codes (coverage/check catalog
                                         (assoc manifest :fork-fixed-issues [2 1 1])))
                 :coverage/fork-fixed-order))
  (is (contains? (codes (coverage/check catalog
                                         (assoc manifest :known-missing
                                                [{:issue 1 :reason ""
                                                  :evidence
                                                  {:repository "casselc/jolt"
                                                   :sha (apply str (repeat 40 "5"))
                                                   :path "test/x"}}])))
                 :debt/reason))
  (is (contains? (codes (coverage/check
                         {:schema 1 :cases [{:issue "not-an-issue"}]}
                         manifest))
                 :catalog/issue-url))
  (is (contains? (codes (coverage/check
                         {:schema 1
                          :cases [{:issue "https://example.test/issues/2"}]}
                         manifest))
                 :catalog/issue-url))
  (is (contains? (codes (coverage/check catalog
                                         (assoc manifest :known-missing 42)))
                 :debt/type))
  (is (contains? (codes (coverage/check catalog
                                         (assoc manifest :fork-fixed-issues 42)))
                 :coverage/fork-fixed-order))
  (is (contains? (codes (coverage/check
                         catalog
                         (assoc manifest :fork-fixed-issues [1 "two"])))
                 :coverage/fork-fixed-order))
  (is (contains? (codes (coverage/check catalog
                                         (assoc manifest :unexpected true)))
                 :coverage/keys))
  (is (contains? (codes (coverage/check 42 manifest)) :catalog/type))
  (is (contains? (codes (coverage/check catalog 42)) :coverage/type))
  (is (contains? (codes (coverage/check (assoc catalog :cases 42) manifest))
                 :catalog/cases))
  (is (contains? (codes (coverage/check
                         catalog
                         (assoc manifest :known-missing
                                [{:issue "one" :reason "bad issue type"
                                  :evidence
                                  {:repository "casselc/jolt"
                                   :sha (apply str (repeat 40 "6"))
                                   :path "test/chez/example-test.clj"}}])))
                 :debt/issue))
  (is (contains? (codes (coverage/check catalog
                                         (assoc manifest :captured-at "fixture")))
                 :coverage/captured-at))
  (is (contains? (codes (coverage/check
                         catalog
                         (assoc-in manifest [:known-missing 0 :evidence]
                                   {:repository "casselc/jolt"
                                    :sha "not-a-sha"
                                    :path "../escape"})))
                 :debt/evidence))
  (doseq [path ["..\\escape" "C:\\escape" "\\server\\share\\escape"]]
    (is (contains? (codes (coverage/check
                           catalog
                           (assoc-in manifest [:known-missing 0 :evidence :path]
                                     path)))
                   :debt/evidence))))

(deftest live-vector-and-label-drift-fail-closed
  (is (contains? (codes (coverage/check catalog manifest []))
                 :live/snapshot-drift))
  (is (contains? (codes (coverage/check catalog manifest 42)) :live/order))
  (is (contains? (codes (coverage/check catalog
                                         (assoc manifest :label "bug") [1 2]))
                 :coverage/label)))

(deftest strict-edn-reader-rejects-empty-and-trailing-forms
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"trailing forms"
                        (coverage/read-edn
                         "test/fixtures/regression-coverage/trailing.edn")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"empty"
                        (coverage/read-edn
                         "test/fixtures/regression-coverage/empty.edn")))
  (is (thrown? Throwable
               (coverage/read-edn
                "test/fixtures/regression-coverage/wrapper-escape.edn"))))

(deftest checked-repository-files-form-a-valid-current-offline-baseline
  (let [catalog (coverage/read-edn "regressions/jolt/cases.edn")
        manifest (coverage/read-edn
                  "regressions/jolt/fork-fixed-coverage.edn")]
    (is (= :valid (:status (coverage/check catalog manifest))))
    (is (= 40 (:fork-fixed (coverage/check catalog manifest))))
    (is (= 12 (:portable (coverage/check catalog manifest))))
    (is (= 28 (:known-missing (coverage/check catalog manifest))))))
