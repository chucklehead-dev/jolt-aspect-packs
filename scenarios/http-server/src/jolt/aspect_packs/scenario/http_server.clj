(ns jolt.aspect-packs.scenario.http-server
  (:require [jolt.http.protocol]))

(defn -main [& _]
  ;; Requiring the real protocol namespace keeps both private semantic seams in
  ;; the compiled dependency graph without opening a listening socket.
  (println "http-server aspect scenario built"))
