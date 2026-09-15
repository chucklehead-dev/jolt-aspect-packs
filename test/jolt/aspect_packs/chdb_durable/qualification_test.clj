(ns jolt.aspect-packs.chdb-durable.qualification-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]))

(def chdb-revision
  "3552a2575a96e3c9dd7b495a9b16b1e9c3317eee")
(def db-revision
  "6db791634e5a4c65c24646833b2e82d3a5d7a121")
(def compiler-revision
  "120643d6bc322800a700e870de5c8087ad6085fa")
(def release-revision
  "f3041a0e32ba0db1b92bd69b8ecb7b40f8b2e115")

(def scenario-paths
  ["scenarios/chdb-durable/deps.edn"
   "scenarios/chdb-durable-plain/deps.edn"
   "scenarios/chdb-durable-crash/deps.edn"])

(defn- read-edn [path]
  (edn/read-string (slurp path)))

(defn- dependency-revision [deps library]
  (get-in deps [:deps library :git/sha]))

(deftest durable-stack-is-one-exact-provenance-set
  (let [root (read-edn "deps.edn")
        target (get-in (read-edn "targets.edn")
                       [:targets 'io.github.chucklehead-dev/jolt-chdb-durable])
        scenario-deps (mapv read-edn scenario-paths)]
    (is (= chdb-revision
           (get-in root [:aliases :chdb-durable-test :extra-deps
                         'io.github.chucklehead-dev/jolt-chdb :git/sha])))
    (is (= db-revision
           (get-in root [:aliases :chdb-durable-test :extra-deps
                         'jolt-lang/db :git/sha])))
    (is (= chdb-revision
           (get-in root [:aliases :test :extra-deps
                         'io.github.chucklehead-dev/jolt-chdb :git/sha])))
    (is (= db-revision
           (get-in root [:aliases :test :extra-deps 'jolt-lang/db :git/sha])))
    (is (nil? (get-in root [:aliases :test :extra-deps
                            'io.github.casselc/db])))
    (is (= chdb-revision (get-in target [:library :git/sha])))
    (is (= {:id 'jolt-lang/db
            :git/url "https://github.com/casselc/db.git"
            :git/sha db-revision}
           (:provider target)))
    (is (= {:version "0.8.6" :release-git/sha release-revision}
           (:runtime target)))
    (is (= {:id 'jolt-lang/jolt
            :git/url "https://github.com/casselc/jolt.git"
            :git/sha compiler-revision
            :branch "integration/aspects"
            :chez "10.4.1"}
           (:compiler target)))
    (is (every? #(= chdb-revision
                    (dependency-revision %
                                         'io.github.chucklehead-dev/jolt-chdb))
                scenario-deps))
    (is (every? #(= db-revision (dependency-revision % 'jolt-lang/db))
                scenario-deps))))

(deftest hosted-gate-builds-the-same-stack-and-keeps-negative-controls
  (let [workflow (slurp ".github/workflows/chdb-durable.yml")]
    (doseq [required ["repository: casselc/jolt"
                      (str "ref: " compiler-revision)
                      "jolt v0.8.6-97-g120643d6"
                      ".jolt-cache-v0.8.6-aspects-120643d6"
                      "chdb-durable-aspect-smoke"
                      "chdb-durable-crash-smoke"
                      "chdb-durable-plain-smoke"]]
      (is (.contains workflow required)))
    (doseq [stale ["5d56b9e5d295fe0968df07e535c45353050611f7"
                   "jolt v0.8.3-41-g5d56b9e5"
                   ".jolt-cache-v0.8.3"]]
      (is (not (.contains workflow stale))))))
