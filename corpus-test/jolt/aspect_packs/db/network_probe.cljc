(ns jolt.aspect-packs.db.network-probe
  "Explicit CI network control; never loaded by the baked corpus consumer."
  #?(:jolt (:require [jolt.mvn-http :as http])))

(def probe-url
  "https://repo.maven.apache.org/maven2/org/clojure/clojure/1.12.3/clojure-1.12.3.pom")

(defn- reachable? []
  #?(:jolt
     (let [directory (java.io.File. (or (System/getenv "RUNNER_TEMP")
                                       (System/getProperty "java.io.tmpdir")))
           file (java.io.File/createTempFile "db-corpus-network-" ".pom" directory)]
       (try
         ;; This is the selected runtime's native HTTPS path, not curl or a
         ;; subprocess. The fixed public POM is only a reachability control.
         (let [result (http/fetch* probe-url (.getPath file))]
           ;; Preserve the public transport's failure classification. A failed
           ;; positive control is an environment/API failure, never evidence
           ;; that the firewall worked. The endpoint is fixed public data.
           (when-not (= :ok (:outcome result))
             (println "Network probe transport:" (pr-str result)))
           (= :ok (:outcome result)))
         (finally (.delete file))))
     :clj
     (try
       (let [^java.net.URLConnection connection (.openConnection (java.net.URL. probe-url))]
         (.setConnectTimeout connection 10000)
         (.setReadTimeout connection 10000)
         (with-open [stream (.getInputStream connection)]
           (not= -1 (.read stream))))
       (catch java.io.IOException error
         (println "Network probe I/O failure:" (.getName (class error)))
         false))))

(defn -main [& args]
  (when-not (and (= 1 (count args)) (contains? #{"open" "blocked"} (first args)))
    (throw (ex-info "usage: network-probe open|blocked" {})))
  (let [expected (= "open" (first args))
        actual (reachable?)]
    (when-not (= expected actual)
      (throw (ex-info "network isolation control failed"
                      {:expected expected :reachable? actual})))
    (println "Network control:" (first args))))
