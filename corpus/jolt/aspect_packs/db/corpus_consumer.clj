(ns jolt.aspect-packs.db.corpus-consumer
  "Verify a pinned corpus before running the existing pure db model."
  (:require [hegel.corpus :as corpus]
            [jolt.aspect-packs.db.corpus-profile :as profile]
            [jolt.aspect-packs.db.model :as model]))

(defn consume!
  "Return the verified payload after privacy, model and required-family gates.
  expected must come from the independent reviewed fixture manifest."
  [expected envelope]
  (let [payload (corpus/consume! expected envelope)
        values (:values payload)]
    ;; Check every fixture's privacy boundary before the first model callback.
    (doseq [events values] (profile/validate! events))
    (doseq [events values] (model/check! events))
    (let [families (reduce into #{} (map profile/witness-families values))
          required #{:select-returned :mutation-affected :shaped-throw :nested-lifecycle}]
      (when-not (= required families)
        (throw (ex-info "db corpus lacks mandatory witness families"
                        {:type ::missing-witnesses
                         :missing (vec (sort (remove families required)))}))))
    payload))
