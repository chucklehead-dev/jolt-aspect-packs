(ns jolt.aspect-packs.compatibility
  "Read-only exact-SHA compatibility oracle for the bounded known universe."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def default-universe-path "compatibility/known-universe.edn")

(defn read-edn [path]
  (edn/read-string (slurp path)))

(defn- full-sha? [value]
  (and (string? value)
       (boolean (re-matches #"[0-9a-f]{40}" value))))

(defn- problem [code expected actual]
  {:code code :expected expected :actual actual})

(defn entry-problems
  "Return deterministic shape/provenance problems for one universe entry."
  [entry]
  (let [required-maps [:age :observation :pack :target :compiler :seam :evidence]
        required-shas [[:pack :git/sha] [:target :git/sha]
                       [:target :upstream :git/sha]
                       [:compiler :git/sha]]]
    (vec
     (concat
      (when-not (keyword? (:id entry))
        [(problem :entry/id :keyword (:id entry))])
      (when-not (= :validated (:status entry))
        [(problem :entry/status :validated (:status entry))])
      (for [key required-maps
            :when (not (map? (get entry key)))]
        (problem :entry/map key (get entry key)))
      (for [path required-shas
            :let [value (get-in entry path)]
            :when (not (full-sha? value))]
        (problem :entry/full-sha path value))
      (when-not (full-sha? (get-in entry [:seam :id]))
        [(problem :entry/seam-id :full-sha (get-in entry [:seam :id]))])
      (when-not (= :exactly-one (get-in entry [:seam :match]))
        [(problem :entry/match :exactly-one (get-in entry [:seam :match]))])
      (when-not (= :not-consulted (get-in entry [:age :clock]))
        [(problem :entry/clock :not-consulted (get-in entry [:age :clock]))])
      (for [fixture [:woven :plain]
            :when (not= 0 (get-in entry [:evidence fixture :exit]))]
        (problem :entry/fixture-exit [fixture 0]
                 (get-in entry [:evidence fixture :exit])))))))

(defn universe-problems [universe]
  (vec
   (concat
    (when-not (= 1 (:schema universe))
      [(problem :universe/schema 1 (:schema universe))])
    (when-not (= :jolt-aspect-packs/known-universe (:universe universe))
      [(problem :universe/id :jolt-aspect-packs/known-universe
                (:universe universe))])
    (when-not (and (vector? (:entries universe))
                   (seq (:entries universe)))
      [(problem :universe/non-empty :entries (:entries universe))])
    (mapcat entry-problems (:entries universe)))))

(defn find-entry [universe entry-id]
  (first (filter #(= entry-id (:id %)) (:entries universe))))

(defn observation-problems
  "Compare observed repository/compiler SHAs to the immutable entry pins."
  [entry observation]
  (vec
   (keep (fn [[key path]]
           (let [expected (get-in entry path)
                 actual (get observation key)]
             (when-not (= expected actual)
               (problem (keyword "observation" (name key)) expected actual))))
         [[:pack-sha [:pack :git/sha]]
          [:target-sha [:target :git/sha]]
          [:upstream-sha [:target :upstream :git/sha]]
          [:compiler-sha [:compiler :git/sha]]
          [:pack-clean? [:observation :clean]]
          [:target-clean? [:observation :clean]]
          [:compiler-clean? [:observation :clean]]])))

(defn report-problems
  "Check exact selector identity and non-vacuous report cardinality."
  [entry report]
  (let [expected (get-in entry [:evidence :report])
        selector (get-in entry [:seam :selector])
        aspect (first (:aspects report))
        site (first (:sites aspect))
        checks [[:report/schema (:schema expected) (:schema report)]
                [:report/weaver (:weaver expected) (:weaver report)]
                [:report/build-identity (:build-identity expected)
                 (:identity report)]
                [:report/aspects (:aspects expected) (count (:aspects report))]
                [:report/aspect-id (:aspect-id expected) (:id aspect)]
                [:report/library (:library (:seam entry))
                 (get-in aspect [:library :id])]
                [:report/seam-id (:id (:seam entry))
                 (get-in aspect [:library :version])]
                [:report/selector selector (:match aspect)]
                [:report/advice (:advice expected) (:advice aspect)]
                [:report/contract (:contract expected) (:contract aspect)]
                [:report/manifest (get-in entry [:pack :manifest])
                 (:resource aspect)]
                [:report/provider (get-in entry [:pack :provider])
                 (get-in aspect [:consumers 0 :provider])]
                [:report/sites (:sites expected) (count (:sites aspect))]
                [:report/site-entry (:entry selector) (:entry site)]
                [:report/site-arity (:arity selector) (:arity site)]]]
    (vec (for [[code wanted actual] checks
               :when (not= wanted actual)]
           (problem code wanted actual)))))

(defn fixture-problems
  "Compare real compiled fixture results to the joined evidence record."
  [entry fixture-results]
  (vec
   (mapcat
    (fn [fixture-id]
      (let [expected (get-in entry [:evidence fixture-id])
            actual (get fixture-results fixture-id)]
        (concat
         (when-not (= (:exit expected) (:exit actual))
           [(problem (keyword "fixture" (str (name fixture-id) "-exit"))
                     (:exit expected) (:exit actual))])
         (when-not (= (:stdout-lines expected) (:stdout-lines actual))
           [(problem (keyword "fixture" (str (name fixture-id) "-stdout"))
                     (:stdout-lines expected) (:stdout-lines actual))]))))
    [:woven :plain])))

(defn check
  "Return a stable result for pins, report, and both compiled fixtures."
  [universe entry-id observation report fixture-results]
  (let [universe-errors (universe-problems universe)
         entry (find-entry universe entry-id)
         errors (vec
                 (concat universe-errors
                         (when-not entry
                           [(problem :entry/missing entry-id nil)])
                         (when entry (observation-problems entry observation))
                         (when entry (report-problems entry report))
                         (when entry
                           (fixture-problems entry fixture-results))))]
     (if (seq errors)
       {:oracle entry-id :status :incompatible :problems errors}
       {:oracle entry-id
        :status :compatible
        :validated-on (get-in entry [:age :validated-on])
        :review-after (get-in entry [:age :review-after])
        :clock (get-in entry [:age :clock])
        :report {:aspects (count (:aspects report))
                 :sites (count (:sites (first (:aspects report))))}
        :fixtures (into {}
                        (map (fn [[id result]] [id (:exit result)]))
                        fixture-results)})))

(defn- checked-shell [& args]
  (let [result (apply shell/sh args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "read-only observation command failed"
                      {:argv (vec args) :result result})))
    result))

(defn- observed-sha [root]
  (str/trim (:out (checked-shell "git" "-C" root "rev-parse" "HEAD"))))

(defn- observed-clean? [root]
  (str/blank? (:out (checked-shell "git" "-C" root "status" "--porcelain"))))

(defn- observed-upstream [target-root entry]
  (let [target-sha (get-in entry [:target :git/sha])
        upstream-sha (get-in entry [:target :upstream :git/sha])]
    (str/trim (:out (checked-shell "git" "-C" target-root "merge-base"
                                  target-sha upstream-sha)))))

(defn- run-fixture [path args]
  (let [result (apply shell/sh path args)]
    {:exit (:exit result)
     :stdout-lines (vec (remove str/blank? (str/split-lines (:out result))))
     :stderr (:err result)}))

(defn- parse-options [args]
  (when (odd? (count args))
    (throw (ex-info "options must be --name value pairs" {:args args})))
  (reduce (fn [options [flag value]]
            (when-not (.startsWith flag "--")
              (throw (ex-info "option name must start with --" {:flag flag})))
            (assoc options (keyword (subs flag 2)) value))
          {}
          (partition 2 args)))

(defn -main [& args]
  (let [options (parse-options args)
        universe-path (get options :universe default-universe-path)
        entry-id (some-> (:entry options) keyword)
        report-path (:report options)
        universe (read-edn universe-path)
        entry (find-entry universe entry-id)]
    (when-not (and entry-id entry report-path (:pack-root options)
                   (:target-root options) (:compiler-root options)
                   (:woven options) (:plain options))
      (throw (ex-info
              (str "required: --entry --pack-root --target-root --compiler-root "
                   "--report --woven --plain")
              {:options (keys options)})))
    (let [observation {:pack-sha (observed-sha (:pack-root options))
                       :pack-clean? (observed-clean? (:pack-root options))
                       :target-sha (observed-sha (:target-root options))
                       :target-clean? (observed-clean? (:target-root options))
                       :upstream-sha (observed-upstream (:target-root options)
                                                       entry)
                       :compiler-sha (observed-sha (:compiler-root options))
                       :compiler-clean?
                       (observed-clean? (:compiler-root options))}
          fixture-results
          {:woven (run-fixture (:woven options) [])
           :plain (run-fixture (:plain options)
                               (get-in entry [:evidence :plain :args]))}
          result (check universe
                        entry-id
                        observation
                        (read-edn report-path)
                        fixture-results)]
      (prn result)
      (System/exit (if (= :compatible (:status result)) 0 1)))))
