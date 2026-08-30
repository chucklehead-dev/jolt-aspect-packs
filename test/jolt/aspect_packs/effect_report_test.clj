(ns jolt.aspect-packs.effect-report-test
  (:require [clojure.edn :as edn]))

(defn- fail! [message data]
  (throw (ex-info (str "effect evidence: " message) data)))

(defn- ensure! [pred message data]
  (when-not pred (fail! message data)))

(defn- sites [phase]
  (into #{}
        (mapcat #(get-in % [:closure :aspect-sites] []))
        (:summaries phase)))

(defn- physical-site-count [aspect-report]
  (reduce + 0 (map #(count (:sites %)) (:aspects aspect-report))))

(defn -main [report-path expected-mode & [aspect-report-path]]
  (let [report (edn/read-string (slurp report-path))
        aspect-report (when aspect-report-path
                        (edn/read-string (slurp aspect-report-path)))
        phases (into {} (map (juxt :phase identity) (:phases report)))
        phase-names (mapv :phase (:phases report))
        subject-sets (mapv #(set (map :subject (:summaries %))) (:phases report))
        plain-sites (sites (:plain phases))
        woven-sites (sites (:woven phases))
        optimized-sites (sites (:optimized phases))]
    (ensure! (= 1 (:schema report)) "unsupported report schema" {:schema (:schema report)})
    (ensure! (= "jolt.effects/build-v1" (:analysis report))
             "wrong analysis" {:analysis (:analysis report)})
    (ensure! (= [:plain :woven :optimized] phase-names)
             "missing compiler phase" {:phases phase-names})
    (doseq [phase (:phases report)]
      (ensure! (pos? (get-in phase [:coverage :subjects] 0))
               "phase coverage is vacuous" {:phase (:phase phase)})
      (ensure! (= (get-in phase [:coverage :subjects])
                  (count (:summaries phase)))
               "coverage count disagrees with summaries" {:phase (:phase phase)})
      (ensure! (= (get-in phase [:coverage :subjects])
                  (reduce + 0 (vals (get-in phase [:coverage :subject-kinds]))))
               "subject-kind coverage disagrees with subjects"
               {:phase (:phase phase)}))
    (ensure! (apply = subject-sets) "phase subject sets differ" {})
    (ensure! (= "jolt.effects/verification-v1"
                (get-in report [:verification :analysis]))
             "wrong verification analysis"
             {:analysis (get-in report [:verification :analysis])})
    (ensure! (= [:plain :woven :optimized]
                (get-in report [:verification :phases]))
             "verification phase set differs"
             {:phases (get-in report [:verification :phases])})
    (ensure! (empty? (get-in report [:verification :findings]))
             "compiler verification has findings"
             {:findings (get-in report [:verification :findings])})
    (ensure! (= woven-sites optimized-sites)
             "optimization changed aspect-site provenance"
             {:woven woven-sites :optimized optimized-sites})
    (case expected-mode
      "woven"
      (do
        (ensure! aspect-report "woven evidence needs its aspect report" {})
        (ensure! (= 1 (:schema aspect-report)) "unsupported aspect-report schema"
                 {:schema (:schema aspect-report)})
        (ensure! (= (:build-identity report) (:identity aspect-report))
                 "effect and aspect build identities differ"
                 {:effect (:build-identity report)
                  :aspect (:identity aspect-report)})
        (ensure! (empty? plain-sites) "plain phase already has woven sites"
                 {:sites plain-sites})
        (ensure! (seq woven-sites) "woven build has no aspect-site evidence" {})
        (ensure! (= (physical-site-count aspect-report) (count woven-sites))
                 "effect site count differs from physical aspect matches"
                 {:effect-sites (count woven-sites)
                  :physical-sites (physical-site-count aspect-report)}))

      "plain"
      (do
        (ensure! (nil? aspect-report-path)
                 "plain evidence should not have an aspect report"
                 {:aspect-report aspect-report-path})
        (ensure! (= "plain" (:build-identity report))
                 "plain build has a non-plain identity"
                 {:identity (:build-identity report)})
        (ensure! (and (empty? plain-sites) (empty? woven-sites)
                      (empty? optimized-sites))
                 "plain build contains aspect-site evidence"
                 {:plain plain-sites :woven woven-sites :optimized optimized-sites}))

      (fail! "expected mode must be plain or woven" {:mode expected-mode}))
    (println "PASS: compiler effect evidence" expected-mode
             (get-in phases [:optimized :coverage :subjects]) "subjects")))
