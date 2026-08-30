(ns jolt.aspect-packs.effect-evidence-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.aspect-packs.effect-evidence :as evidence]))

(defn summary [fqn sites]
  {:subject {:kind :var-arity :fqn fqn :arity {:fixed 0}}
   :direct {:effects [] :callees [] :transfers []
            :aspect-sites [] :opaque-calls []}
   :closure {:effects [] :aspect-sites sites :transfers [] :unknown? false}})

(defn phase [id summaries]
  {:phase id
   :coverage {:subjects (count summaries)
              :subject-kinds {:var-arity (count summaries)}}
   :summaries summaries})

(defn report [build-identity woven-sites optimized-sites]
  {:schema 1
   :analysis "jolt.effects/build-v1"
   :build-identity build-identity
   :phases [(phase :plain [(summary "fixture/run" [])])
            (phase :woven [(summary "fixture/run" woven-sites)])
            (phase :optimized [(summary "fixture/run" optimized-sites)])]
   :verification {:analysis "jolt.effects/verification-v1"
                  :phases [:plain :woven :optimized]
                  :findings []}})

(def aspect-report
  {:schema 1
   :identity "v1-fixture"
   :aspects [{:id :fixture/one :sites [{:ordinal 0}]}
             {:id :fixture/two :sites [{:ordinal 0}]}]})

(deftest valid-plain-evidence-is-source-neutral-and-site-free
  (let [result (evidence/check :plain (report nil [] []))]
    (is (= :valid (:status result)))
    (is (= nil (:build-identity result)))
    (is (= 0 (:aspect-sites result)))
    (is (= {:plain 1 :woven 1 :optimized 1} (:subjects result)))))

(deftest plain-mode-rejects-build-identity-and-sites
  (let [result (evidence/check :plain
                               (report "v1-unexpected" ["site-1"] ["site-1"]))
        codes (set (map :code (:problems result)))]
    (is (= :invalid (:status result)))
    (is (contains? codes :plain/build-identity))
    (is (contains? codes :plain/woven-sites-empty))
    (is (contains? codes :plain/optimized-sites-empty))))

(deftest woven-evidence-is-joined-to-the-physical-aspect-report
  (let [result (evidence/check
                :woven
                (report "v1-fixture" ["site-1" "site-2"]
                        ["site-2" "site-1"])
                aspect-report)]
    (is (= :valid (:status result)))
    (is (= "v1-fixture" (:build-identity result)))
    (is (= 2 (:aspect-sites result)))))

(deftest woven-validation-rejects-vacuity-identity-and-site-loss
  (testing "an empty aspect report cannot make empty effect evidence pass"
    (let [result (evidence/check :woven (report nil [] [])
                                 {:identity nil :aspects []})
          codes (set (map :code (:problems result)))]
      (is (contains? codes :woven/aspects))
      (is (contains? codes :woven/physical-sites))
      (is (contains? codes :woven/effect-sites))))
  (testing "the effects artifact belongs to the same build and keeps its sites"
    (let [result (evidence/check
                  :woven
                  (report "v1-wrong" ["site-1"] [])
                  aspect-report)
          codes (set (map :code (:problems result)))]
      (is (contains? codes :woven/build-identity))
      (is (contains? codes :woven/sites-preserved))
      (is (contains? codes :woven/site-count)))))

(deftest schema-phase-coverage-and-verification-are-checked
  (let [bad (-> (report nil [] [])
                (assoc :schema 2)
                (assoc-in [:phases 1 :coverage :subjects] 0)
                (assoc-in [:phases 2 :coverage :subject-kinds]
                          {:var-arity 0})
                (assoc-in [:verification :findings] [{:rule :bad}]))
        result (evidence/check :plain bad)
        codes (set (map :code (:problems result)))]
    (is (contains? codes :evidence/schema))
    (is (contains? codes :phase/positive-subjects))
    (is (contains? codes :phase/subject-count))
    (is (contains? codes :phase/subject-kind-sum))
    (is (contains? codes :verification/findings))))
