(ns jolt.aspect-packs.db.corpus-live
  "Explicit live generation command; never required by baked consumption."
  (:require [clojure.edn :as edn]
            [hegel.corpus :as corpus]
            [hegel.materialize :as materialize]
            [jolt.aspect-packs.db.corpus-generator :as generator]
            [jolt.aspect-packs.db.corpus-profile :as profile]))

(defn generate! [opts]
  (materialize/materialize!
   opts (generator/generator)
   (fn [events]
     (profile/check! events)
     (when-not (= #{:select-returned :mutation-affected :shaped-throw :nested-lifecycle}
                  (profile/witness-families events))
       (throw (ex-info "generated db fixture lacks mandatory witnesses"
                       {:type ::missing-witnesses}))))))

(defn -main [& args]
  (when-not (= 1 (count args))
    (throw (ex-info "usage: db-corpus-live TRUSTED-GENERATION-OPTIONS.edn" {})))
  ;; This is operator-owned generation configuration, not an incoming corpus.
  ;; Produces a candidate on stdout; never rewrites the consumer's trusted pin.
  (let [opts (edn/read-string (slurp (first args)))]
    (println (corpus/encode (generate! opts)))))
