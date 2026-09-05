(ns jolt.aspect-packs.db.corpus-offline-runner
  (:require [clojure.edn :as edn]
            [clojure.test :as t]
            [hegel.corpus :as corpus]
            [jolt.aspect-packs.db.corpus-consumer :as consumer]
            [jolt.aspect-packs.db.corpus-test]))

(defn -main [& _]
  (doseq [ns-name ['hegel.core 'hegel.ffi 'hegel.generator 'hegel.install
                   'db.driver 'jolt.aspect-packs.db.provider
                   'jolt.aspect-packs.db.corpus-generator]]
    (when (find-ns ns-name)
      (throw (ex-info "offline db corpus runner loaded a forbidden dependency"
                      {:namespace ns-name}))))
  ;; The pin file is separately reviewed configuration. Never derive it from
  ;; the artifact; generation only emits candidates and cannot rewrite this pin.
  (let [expected (edn/read-string (slurp "corpus-fixtures/db-v1-pin.edn"))
        envelope (corpus/decode (slurp "corpus-fixtures/db-v1.edn"))
        payload (consumer/consume! expected envelope)]
    (println "Verified db corpus and model:" (:count payload) "complete histories"))
  (let [{:keys [fail error]} (t/run-tests 'jolt.aspect-packs.db.corpus-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "db corpus offline controls failed" {:fail fail :error error})))))
