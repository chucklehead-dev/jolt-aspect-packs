(ns jolt.aspect-packs.effect-evidence
  "Validate the source-neutral effect evidence emitted beside a Jolt build."
  (:require [clojure.edn :as edn]))

(def phases [:plain :woven :optimized])

(defn read-edn [path]
  (edn/read-string (slurp path)))

(defn- problem
  ([code actual]
   {:code code :actual actual})
  ([code expected actual]
   {:code code :expected expected :actual actual}))

(defn- phase-by-name [report phase]
  (first (filter #(= phase (:phase %)) (:phases report))))

(defn- closure-sites [phase]
  (set (mapcat #(get-in % [:closure :aspect-sites] [])
               (:summaries phase))))

(defn- phase-problems [phase-id phase]
  (let [summaries (:summaries phase)
        subjects (get-in phase [:coverage :subjects])
        subject-kinds (get-in phase [:coverage :subject-kinds])
        kind-subjects (when (and (map? subject-kinds)
                                 (every? #(and (integer? %) (not (neg? %)))
                                         (vals subject-kinds)))
                        (reduce + 0 (vals subject-kinds)))]
    (vec
     (concat
      (when-not (map? phase)
        [(problem :phase/missing phase-id phase)])
      (when (map? phase)
        (concat
         (when-not (vector? summaries)
           [(problem :phase/summaries :vector summaries)])
         (when-not (and (integer? subjects) (pos? subjects))
           [(problem :phase/positive-subjects :positive-integer subjects)])
         (when (and (vector? summaries)
                    (not= subjects (count summaries)))
           [(problem :phase/subject-count (count summaries) subjects)])
         (when-not (map? subject-kinds)
           [(problem :phase/subject-kinds :map subject-kinds)])
         (when (and (map? subject-kinds) (nil? kind-subjects))
           [(problem :phase/subject-kind-counts
                     :non-negative-integers subject-kinds)])
         (when (and (some? kind-subjects) (not= subjects kind-subjects))
           [(problem :phase/subject-kind-sum subjects kind-subjects)])))))))

(defn- common-problems [report]
  (let [actual-phases (mapv :phase (:phases report))
        verification (:verification report)]
    (vec
     (concat
      (when-not (map? report)
        [(problem :evidence/type :map report)])
      (when-not (= 1 (:schema report))
        [(problem :evidence/schema 1 (:schema report))])
      (when-not (= "jolt.effects/build-v1" (:analysis report))
        [(problem :evidence/analysis "jolt.effects/build-v1"
                  (:analysis report))])
      (when-not (= phases actual-phases)
        [(problem :evidence/phases phases actual-phases)])
      (mapcat (fn [phase]
                (phase-problems phase (phase-by-name report phase)))
              phases)
      (when-not (= "jolt.effects/verification-v1" (:analysis verification))
        [(problem :verification/analysis
                  "jolt.effects/verification-v1"
                  (:analysis verification))])
      (when-not (= phases (:phases verification))
        [(problem :verification/phases phases (:phases verification))])
      (when-not (= [] (:findings verification))
        [(problem :verification/findings [] (:findings verification))])
      (let [sites (closure-sites (phase-by-name report :plain))]
        (when (seq sites)
          [(problem :sites/plain-empty #{} sites)]))))))

(defn- physical-site-count [aspect-report]
  (reduce + 0 (map #(count (:sites %)) (:aspects aspect-report))))

(defn problems
  "Return deterministic validation problems for MODE (`:plain` or `:woven`).

  Woven validation joins effects.edn to the compiler aspect report so build
  identity and non-vacuous physical-site cardinality cannot be checked in
  isolation."
  ([mode report]
   (problems mode report nil))
  ([mode report aspect-report]
   (let [woven-sites (closure-sites (phase-by-name report :woven))
         optimized-sites (closure-sites (phase-by-name report :optimized))]
     (vec
      (concat
       (common-problems report)
       (case mode
         :plain
         (concat
          (when-not (nil? (:build-identity report))
            [(problem :plain/build-identity nil (:build-identity report))])
          (when (seq woven-sites)
            [(problem :plain/woven-sites-empty #{} woven-sites)])
          (when (seq optimized-sites)
            [(problem :plain/optimized-sites-empty #{} optimized-sites)]))

         :woven
         (let [aspects (:aspects aspect-report)
               site-count (physical-site-count aspect-report)]
           (concat
            (when-not (map? aspect-report)
              [(problem :woven/aspect-report :map aspect-report)])
            (when-not (and (vector? aspects) (seq aspects))
              [(problem :woven/aspects :non-empty-vector aspects)])
            (when-not (pos? site-count)
              [(problem :woven/physical-sites :positive-integer site-count)])
            (when-not (= (:identity aspect-report) (:build-identity report))
              [(problem :woven/build-identity (:identity aspect-report)
                        (:build-identity report))])
            (when-not (seq woven-sites)
              [(problem :woven/effect-sites :non-empty-set woven-sites)])
            (when-not (= woven-sites optimized-sites)
              [(problem :woven/sites-preserved woven-sites optimized-sites)])
            (when-not (= site-count (count woven-sites))
              [(problem :woven/site-count site-count (count woven-sites))])))

         [(problem :mode #{:plain :woven} mode)]))))))

(defn check
  ([mode report]
   (check mode report nil))
  ([mode report aspect-report]
   (let [errors (problems mode report aspect-report)]
     (if (seq errors)
       {:status :invalid :mode mode :problems errors}
       {:status :valid
        :mode mode
        :build-identity (:build-identity report)
        :subjects (into {}
                        (map (fn [phase]
                               [phase (get-in (phase-by-name report phase)
                                              [:coverage :subjects])]))
                        phases)
        :aspect-sites (count
                       (closure-sites (phase-by-name report :optimized)))}))))

(defn- parse-mode [value]
  (case value
    "plain" :plain
    "woven" :woven
    nil))

(defn -main [& args]
  (let [[mode-value effects-path aspect-path & extra] args
        mode (parse-mode mode-value)]
    (when (or (nil? mode) (nil? effects-path) (seq extra)
              (and (= :plain mode) aspect-path)
              (and (= :woven mode) (nil? aspect-path)))
      (throw (ex-info
              (str "usage: -m jolt.aspect-packs.effect-evidence "
                   "plain EFFECTS | woven EFFECTS ASPECT-REPORT")
              {:args args})))
    (let [result (check mode
                        (read-edn effects-path)
                        (when aspect-path (read-edn aspect-path)))]
      (prn result)
      (System/exit (if (= :valid (:status result)) 0 1)))))
