(ns jolt.aspect-packs.regression-coverage
  "Fail-closed coverage accounting between fork-fixed ledger issues and the
  independently runnable Jolt regression matrix."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io PushbackReader StringReader]
           [java.time Instant]))

(defn read-edn [path]
  (let [content (slurp path)
        eof (Object.)]
    (with-open [reader (PushbackReader. (StringReader. content))]
      (binding [*read-eval* false]
        (let [first-form (read {:eof eof} reader)
              trailing (read {:eof eof} reader)]
          (when (identical? first-form eof)
            (throw (ex-info "EDN file is empty" {:path path})))
          (when-not (identical? trailing eof)
            (throw (ex-info "EDN file has trailing forms" {:path path})))
          ;; `read` supplies the EOF-sensitive boundary check; `edn/read-string`
          ;; remains the authority for the accepted data language.
          (edn/read-string content))))))

(defn- problem
  ([code actual] {:code code :actual actual})
  ([code expected actual] {:code code :expected expected :actual actual}))

(defn- issue-number [url]
  (let [prefix "https://github.com/chucklehead-dev/jolt-aspect-packs/issues/"]
    (when (and (string? url) (str/starts-with? url prefix))
      (let [part (subs url (count prefix))]
        (try
          (let [number (Long/parseLong part)]
            (when (and (pos? number) (= url (str prefix number))) number))
          (catch Throwable _ nil))))))

(defn- canonical-issue-vector? [issues]
  (and (vector? issues)
       (every? #(and (integer? %) (pos? %)) issues)
       (= issues (vec (sort (distinct issues))))))

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- relative-source-path? [path]
  (and (non-blank-string? path)
       (not (str/starts-with? path "/"))
       (not (str/starts-with? path "\\"))
       (not (re-matches #"(?i)[a-z]:.*" path))
       (not-any? #{".."} (str/split path #"[/\\]"))))

(defn- existing-repo-path? [path]
  (and (relative-source-path? path) (.isFile (java.io.File. path))))

(defn- sha? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{40}" value))))

(defn- repository-coordinate? [value]
  (when (string? value)
    (let [[owner repository & extra] (str/split value #"/" -1)]
      (and (nil? extra)
           (string? owner)
           (string? repository)
           (boolean
            (re-matches
             #"[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?"
             owner))
           (boolean (re-matches #"[A-Za-z0-9_.-]{1,100}" repository))
           (not (contains? #{"." ".."} repository))))))

(defn- timestamp? [value]
  (and (string? value)
       (try
         (Instant/parse value)
         true
         (catch Throwable _ false))))

(defn- case-problems [index row]
  (let [allowed #{:id :script :issue :bad-sha :fixed-sha :timeout-ms
                  :expected :provenance}
        expected (:expected row)
        provenance (:provenance row)
        aspect (:aspect provenance)
        hegel (:hegel provenance)
        checker (:checker provenance)
        issue (issue-number (:issue row))
        script-content (when (existing-repo-path? (:script row))
                         (slurp (:script row)))
        pass-signature (:pass expected)
        fail-signature (:fail expected)
        stderr-signature (:stderr-contains expected)
        issue-signature (when issue (str "issue-" issue))]
    (vec
     (concat
      (when-not (map? row)
        [(problem :catalog/case-type {:index index :expected :map} row)])
      (when (map? row)
        (concat
         (when-not (= allowed (set (keys row)))
           [(problem :catalog/case-keys
                     {:index index :expected allowed} (set (keys row)))])
         (when-not (and (keyword? (:id row)) (namespace (:id row)))
           [(problem :catalog/id {:index index :expected :qualified-keyword}
                     (:id row))])
         (when-not (existing-repo-path? (:script row))
           [(problem :catalog/script
                     {:index index :expected :existing-relative-repo-file}
                     (:script row))])
         (when-not (issue-number (:issue row))
           [(problem :catalog/issue-url
                     {:index index :expected :github-issue-url} (:issue row))])
         (when-not (sha? (:bad-sha row))
           [(problem :catalog/bad-sha {:index index :expected :git-sha}
                     (:bad-sha row))])
         (when-not (sha? (:fixed-sha row))
           [(problem :catalog/fixed-sha {:index index :expected :git-sha}
                     (:fixed-sha row))])
         (when (and (sha? (:bad-sha row))
                    (sha? (:fixed-sha row))
                    (= (:bad-sha row) (:fixed-sha row)))
           [(problem :catalog/sha-distinction
                     {:index index :expected :different-bad-and-fixed-shas}
                     (:bad-sha row))])
         (when-not (and (integer? (:timeout-ms row)) (pos? (:timeout-ms row)))
           [(problem :catalog/timeout-ms
                     {:index index :expected :positive-integer}
                     (:timeout-ms row))])
         (when-not (and (map? expected)
                        (contains? #{#{:pass :fail}
                                     #{:pass :fail :stderr-contains}}
                                   (set (keys expected)))
                        (non-blank-string? (:pass expected))
                        (non-blank-string? (:fail expected))
                        (or (not (contains? expected :stderr-contains))
                            (non-blank-string? stderr-signature))
                        (not= (:pass expected) (:fail expected)))
           [(problem :catalog/expected
                     {:index index :expected :distinct-pass-fail-signatures}
                     expected)])
         (when-not (and (map? provenance)
                        (= #{:aspect :hegel :checker}
                           (set (keys provenance)))
                        (map? aspect)
                        (= #{:runtime-required? :status} (set (keys aspect)))
                        (= false (:runtime-required? aspect))
                        (keyword? (:status aspect))
                        (map? hegel)
                        (= #{:runtime-required? :status} (set (keys hegel)))
                        (= false (:runtime-required? hegel))
                        (keyword? (:status hegel))
                        (map? checker)
                        (= #{:source :status} (set (keys checker)))
                        (keyword? (:source checker))
                        (= :independently-runnable (:status checker)))
           [(problem :catalog/provenance
                     {:index index :expected :independent-portable-regression}
                     provenance)])
         (when-not (and script-content
                        (non-blank-string? pass-signature)
                        (non-blank-string? fail-signature)
                        issue-signature
                        (str/includes? pass-signature issue-signature)
                        (str/includes? fail-signature issue-signature)
                        (str/includes? script-content pass-signature)
                        (str/includes? script-content fail-signature)
                        (or (nil? stderr-signature)
                            (str/includes? script-content stderr-signature)))
           [(problem :catalog/script-signatures
                     {:index index
                      :expected :issue-correlated-signatures-in-script}
                     {:issue issue
                      :pass pass-signature
                      :fail fail-signature
                      :stderr-contains stderr-signature})])))))))

(defn- catalog-problems [catalog]
  (let [allowed #{:schema :cases}
        safe (if (map? catalog) catalog {})
        cases (:cases safe)
        safe-cases (if (vector? cases) cases [])
        rows (keep-indexed (fn [index row]
                             (when (map? row) [index row]))
                           safe-cases)
        ids (mapv (comp :id second) rows)
        issues (mapv (comp issue-number :issue second) rows)]
    (vec
     (concat
      (when-not (map? catalog)
        [(problem :catalog/type :map catalog)])
      (when-not (= 1 (:schema safe))
        [(problem :catalog/schema 1 (:schema safe))])
      (when-not (= allowed (set (keys safe)))
        [(problem :catalog/keys allowed (set (keys safe)))])
      (when-not (vector? cases)
        [(problem :catalog/cases :vector cases)])
      (mapcat (fn [index row] (case-problems index row))
              (range) safe-cases)
      (when (and (every? some? ids)
                 (not= (count ids) (count (distinct ids))))
        [(problem :catalog/duplicate-ids :distinct ids)])
      (when (and (every? some? issues)
                 (not= (count issues) (count (distinct issues))))
        [(problem :catalog/duplicate-issues :distinct issues)])))))

(defn- debt-problems [rows]
  (let [allowed #{:issue :reason :evidence}
        safe-rows (if (vector? rows) rows [])
        issues (mapv #(when (map? %) (:issue %)) safe-rows)
        canonical-issues? (every? #(and (integer? %) (pos? %)) issues)]
    (vec
     (concat
      (when-not (vector? rows)
        [(problem :debt/type :vector rows)])
      (when (and (vector? rows) canonical-issues?
                 (not= issues (vec (sort (distinct issues)))))
        [(problem :debt/order-and-uniqueness
                  (vec (sort (distinct issues))) issues)])
      (mapcat
       (fn [row]
         (concat
          (when-not (map? row)
            [(problem :debt/row-type :map row)])
          (when (map? row)
            (concat
             (when-not (= allowed (set (keys row)))
               [(problem :debt/row-keys allowed (set (keys row)))])
             (when-not (and (integer? (:issue row)) (pos? (:issue row)))
               [(problem :debt/issue :positive-integer (:issue row))])
             (when-not (non-blank-string? (:reason row))
               [(problem :debt/reason :non-blank-string (:reason row))])
             (let [evidence (:evidence row)]
               (when-not (and (map? evidence)
                              (= #{:repository :sha :path}
                                 (set (keys evidence)))
                              (repository-coordinate? (:repository evidence))
                              (sha? (:sha evidence))
                              (relative-source-path? (:path evidence)))
                 [(problem :debt/evidence
                           :repository-sha-relative-path evidence)]))))))
       safe-rows)))))

(defn problems
  "Return deterministic coverage problems.

  `live-issues`, when supplied, is the current GitHub issue-number vector for
  the `status:fixed-in-fork` label. Ordinary offline tests validate the checked
  snapshot; the live gate additionally prevents that snapshot from drifting."
  ([catalog coverage] (problems catalog coverage nil))
  ([catalog coverage live-issues]
   (let [safe-catalog (if (map? catalog) catalog {})
         safe-coverage (if (map? coverage) coverage {})
         coverage-keys #{:schema :repository :label :captured-at
                         :fork-fixed-issues :known-missing}
         cases (:cases safe-catalog)
         safe-cases (if (vector? cases) cases [])
         valid-case-indexes (set (keep-indexed
                                  (fn [index row]
                                    (when (empty? (case-problems index row)) index))
                                  safe-cases))
         fixed (:fork-fixed-issues safe-coverage)
         safe-fixed (if (vector? fixed) fixed [])
         debt (:known-missing safe-coverage)
         debt-issues (mapv #(when (map? %) (:issue %))
                           (if (vector? debt) debt []))
         case-issues (mapv #(issue-number (:issue %)) safe-cases)
         portable-case-issues (keep-indexed
                               (fn [index issue]
                                 (when (contains? valid-case-indexes index) issue))
                               case-issues)
         covered (set (remove nil? portable-case-issues))
         expected-debt (when (canonical-issue-vector? fixed)
                         (vec (sort (remove covered safe-fixed))))]
     (vec
      (concat
       (catalog-problems catalog)
       (when-not (map? coverage)
         [(problem :coverage/type :map coverage)])
       (when-not (= 2 (:schema safe-coverage))
         [(problem :coverage/schema 2 (:schema safe-coverage))])
       (when-not (= coverage-keys (set (keys safe-coverage)))
         [(problem :coverage/keys coverage-keys (set (keys safe-coverage)))])
       (when-not (= "chucklehead-dev/jolt-aspect-packs" (:repository safe-coverage))
         [(problem :coverage/repository
                   "chucklehead-dev/jolt-aspect-packs"
                   (:repository safe-coverage))])
       (when-not (= "status:fixed-in-fork" (:label safe-coverage))
         [(problem :coverage/label "status:fixed-in-fork"
                   (:label safe-coverage))])
       (when-not (timestamp? (:captured-at safe-coverage))
         [(problem :coverage/captured-at :instant
                   (:captured-at safe-coverage))])
       (when-not (canonical-issue-vector? fixed)
         [(problem :coverage/fork-fixed-order
                   :sorted-distinct-positive-integers fixed)])
       (debt-problems debt)
       (when (and (canonical-issue-vector? fixed)
                  (not= expected-debt debt-issues))
         [(problem :coverage/debt-drift expected-debt debt-issues)])
       (when (and (some? live-issues)
                  (not (canonical-issue-vector? live-issues)))
         [(problem :live/order :sorted-distinct-positive-integers live-issues)])
       (when (and (some? live-issues)
                  (canonical-issue-vector? live-issues)
                  (not= fixed live-issues))
         [(problem :live/snapshot-drift live-issues fixed)]))))))

(defn check
  ([catalog coverage] (check catalog coverage nil))
  ([catalog coverage live-issues]
   (let [errors (problems catalog coverage live-issues)
         fixed (:fork-fixed-issues coverage)
         cases (:cases catalog)
         covered (set (keep-indexed
                       (fn [index row]
                         (when (empty? (case-problems index row))
                           (issue-number (:issue row))))
                       (if (vector? cases) cases [])))]
     (if (seq errors)
       {:status :invalid :problems errors}
       {:status :valid
        :fork-fixed (count fixed)
        :portable (count (filter covered fixed))
        :known-missing (count (:known-missing coverage))
        :live-checked? (some? live-issues)}))))

(defn -main [& args]
  (let [[catalog-path coverage-path live-path & extra] args]
    (when (or (nil? catalog-path) (nil? coverage-path) (seq extra))
      (throw (ex-info
              (str "usage: -m jolt.aspect-packs.regression-coverage "
                   "CASES-EDN COVERAGE-EDN [LIVE-ISSUES-EDN]")
              {:args args})))
    (let [result (check (read-edn catalog-path)
                        (read-edn coverage-path)
                        (when live-path (read-edn live-path)))]
      (prn result)
      (System/exit (if (= :valid (:status result)) 0 1)))))
