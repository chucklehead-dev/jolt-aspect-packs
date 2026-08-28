(ns jolt.aspect-packs.scenario.glitter
  (:require [glitter.widget :as widget]))

(defn reorder!
  [kind parent child sibling]
  (widget/reorder-child! kind parent child sibling))

(defn -main [& args]
  ;; The public dispatch keeps Burin's private list-box lifecycle seam reachable
  ;; for build-time conformance. The live GTK scenario remains in Glitter.
  (when (= "--never-run-native-reorder" (first args))
    (reorder! :list-box 0 0 0))
  (println "Glitter aspect scenario built"))
