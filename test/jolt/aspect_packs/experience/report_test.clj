(ns jolt.aspect-packs.experience.report-test
  (:require [clojure.edn :as edn]
            [jolt.aspect-packs.experience.provider :as provider]))

(def expected
  [{:id :samizdat.decide/decision-domain
    :match {:entry 'samizdat.decide/authorize :arity 2}
    :advice 'jolt.aspect-packs.experience.provider/around-decision-domain}
   {:id :samizdat.decide/candidate-score
    :match {:entry 'samizdat.decide/decide :arity 1}
    :advice 'jolt.aspect-packs.experience.provider/around-candidate-score}
   {:id :samizdat.decide/transition
    :match {:entry 'samizdat.decide/revalidate :arity 3}
    :advice 'jolt.aspect-packs.experience.provider/around-transition}
   {:id :samizdat.store.journal/verification
    :match {:entry 'samizdat.store.journal/settle-gate! :arity 4}
    :advice 'jolt.aspect-packs.experience.provider/around-verification}
   {:id :samizdat.store.journal/artifact
    :match {:entry 'samizdat.store.journal/record-artifact! :arity 3}
    :advice 'jolt.aspect-packs.experience.provider/around-artifact}])

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))
        aspects (:aspects report)]
    (when-not (= 1 (:schema report))
      (throw (ex-info "unexpected aspect report schema" {:report report})))
    (when-not (= "jolt.aspect-ir/v1" (:weaver report))
      (throw (ex-info "unexpected aspect weaver" {:report report})))
    (when-not (false? (:control-enabled? report))
      (throw (ex-info "experience observation build enabled control advice"
                      {:report report})))
    (when-not (= 5 (count aspects))
      (throw (ex-info "experience scenario selected unexpected aspects"
                      {:aspects aspects})))
    (doseq [{:keys [id match advice]} expected]
      (let [aspect (first (filter #(= id (:id %)) aspects))]
        (when-not aspect
          (throw (ex-info "experience report omitted an aspect" {:id id})))
        (when-not (= {:id 'yogthos/samizdat
                      :version provider/seam-revision}
                     (:library aspect))
          (throw (ex-info "wrong experience seam identity" {:aspect aspect})))
        (when-not (= match (:match aspect))
          (throw (ex-info "wrong experience selector" {:aspect aspect})))
        (when-not (= advice (:advice aspect))
          (throw (ex-info "wrong experience history advice" {:aspect aspect})))
        (when-not (= 1 (count (:sites aspect)))
          (throw (ex-info "experience seam must match exactly once"
                          {:aspect aspect})))))
    (println "ASPECT-PACK-EXPERIENCE-REPORT OK")))
