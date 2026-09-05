(ns formal-antivacuity
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn fail! [message]
  (binding [*out* *err*]
    (println (str "FAIL formal anti-vacuity: " message)))
  (System/exit 1))

(defn- tokenize [source]
  (let [n (count source)]
    (loop [i 0, tokens []]
      (if (>= i n)
        tokens
        (let [c (.charAt source i)]
          (cond
            (Character/isWhitespace c) (recur (inc i) tokens)
            (= c \;) (let [j (or (str/index-of source "\n" i) n)]
                        (recur (inc j) tokens))
            (= c \() (recur (inc i) (conj tokens "("))
            (= c \)) (recur (inc i) (conj tokens ")"))
            (or (= c \|) (= c \"))
            (let [quoted-symbol? (= c \|)
                  delimiter c
                  [j token]
                  (loop [j (inc i)]
                    (when (>= j n)
                      (throw (ex-info "unterminated SMT-LIB quoted token" {:offset i})))
                    (let [d (.charAt source j)]
                      (cond
                        (= d delimiter)
                        (if (and (not quoted-symbol?)
                                 (< (inc j) n)
                                 (= (.charAt source (inc j)) \"))
                          (recur (+ j 2))
                          [(inc j) (subs source i (inc j))])
                        :else (recur (inc j)))))]
              (recur j (conj tokens token)))
            :else
            (let [j (loop [j i]
                      (if (or (>= j n)
                              (Character/isWhitespace (.charAt source j))
                              (#{\( \) \;} (.charAt source j)))
                        j
                        (recur (inc j))))]
              (recur j (conj tokens (subs source i j))))))))))

(defn- parse-smt [source]
  (let [tokens (tokenize source)
        cursor (atom 0)]
    (letfn [(form []
              (when (>= @cursor (count tokens))
                (throw (ex-info "unexpected end of SMT-LIB input" {})))
              (let [token (nth tokens @cursor)]
                (swap! cursor inc)
                (cond
                  (= token "(")
                  (loop [xs []]
                    (when (>= @cursor (count tokens))
                      (throw (ex-info "unterminated SMT-LIB list" {})))
                    (if (= ")" (nth tokens @cursor))
                      (do (swap! cursor inc) xs)
                      (recur (conj xs (form)))))
                  (= token ")") (throw (ex-info "unexpected SMT-LIB ')'" {}))
                  :else token)))]
      (loop [forms []]
        (if (= @cursor (count tokens))
          forms
          (recur (conj forms (form))))))))

(defn- unwrap-annotation [expr]
  (if (and (vector? expr) (= "!" (first expr)))
    (second expr)
    expr))

(defn- definition [expr declared]
  (let [expr (unwrap-annotation expr)]
    (when (and (vector? expr) (= "=" (first expr)) (= 3 (count expr)))
      (let [[_ lhs rhs] expr]
        (cond
          (contains? declared lhs) [lhs rhs]
          (contains? declared rhs) [rhs lhs])))))

(defn- definitions [forms]
  (let [declared
        (into #{}
              (keep (fn [form]
                      (when (and (vector? form)
                                 (or (= "declare-const" (first form))
                                     (and (= "declare-fun" (first form))
                                          (= [] (nth form 2 nil)))))
                        (second form))))
              forms)]
    (:definitions
     (reduce (fn [{:keys [depth definitions] :as state} form]
             (let [op (when (vector? form) (first form))]
               (cond
                 (= op "push") (update state :depth inc)
                 (= op "pop") (if (pos? depth)
                                (update state :depth dec)
                                (throw (ex-info "SMT-LIB pop without push" {})))
                 (and (zero? depth) (= op "assert"))
                 (if-let [[name expr] (definition (second form) declared)]
                   (if (contains? definitions name)
                     (throw (ex-info "multiple defining equalities for symbol" {:symbol name}))
                     (assoc-in state [:definitions name] expr))
                   state)
                 :else state)))
             {:depth 0 :definitions {}}
             forms))))

(defn- literal [x]
  (cond
    (= x "true") true
    (= x "false") false
    (and (string? x) (re-matches #"-?[0-9]+" x)) (parse-long x)
    :else ::unknown))

(declare normalize)

(defn- normalize-commutative [op args]
  (let [args (map normalize args)
        args (mapcat #(if (and (vector? %) (= op (first %))) (rest %) [%]) args)]
    (vec (cons op (sort-by pr-str args)))))

(defn normalize [expr]
  (if-not (vector? expr)
    expr
    (let [[op & args] expr]
      (cond
        (contains? #{"and" "or"} op) (normalize-commutative op args)
        (and (= op "=") (= 2 (count args)))
        (vec (cons op (sort-by pr-str (map normalize args))))
        (and (= op "not") (= 1 (count args)))
        (let [arg (normalize (first args))]
          (if (and (vector? arg) (= "not" (first arg)))
            (second arg)
            [op arg]))
        :else (vec (cons op (map normalize args)))))))

(defn- eval-condition [expr]
  (let [expr (normalize expr)]
    (cond
      (boolean? expr) expr
      (and (vector? expr) (= "=" (first expr)) (= 3 (count expr)))
      (let [a (literal (second expr)), b (literal (nth expr 2))]
        (if (or (= a ::unknown) (= b ::unknown)) ::unknown (= a b)))
      (and (vector? expr) (= "not" (first expr)))
      (let [v (eval-condition (second expr))]
        (if (= v ::unknown) v (not v)))
      :else ::unknown)))

(defn- resolve-expression [root defs substitutions]
  (let [visited (atom #{})]
    (letfn [(resolve* [expr stack]
              (cond
                (and (string? expr) (contains? substitutions expr))
                (get substitutions expr)

                (and (string? expr) (contains? defs expr))
                (do
                  (when (contains? stack expr)
                    (throw (ex-info "cyclic defining equalities" {:cycle (conj stack expr)})))
                  (swap! visited conj expr)
                  (resolve* (get defs expr) (conj stack expr)))

                (vector? expr)
                (let [[op & args] expr]
                  (if (and (= op "ite") (= 3 (count args)))
                    (let [condition (resolve* (first args) stack)
                          value (eval-condition condition)]
                      (case value
                        true (resolve* (second args) stack)
                        false (resolve* (nth args 2) stack)
                        (vec [op condition
                              (resolve* (second args) stack)
                              (resolve* (nth args 2) stack)])))
                    (vec (cons op (map #(resolve* % stack) args)))))

                :else expr))]
      {:expression (normalize (resolve* root #{}))
       :definitions @visited})))

(defn- local-queries [forms]
  (loop [remaining forms, stack [], local [], queries []]
    (if-let [form (first remaining)]
      (let [op (when (vector? form) (first form))]
        (case op
          "push" (recur (rest remaining) (conj stack local) local queries)
          "pop" (if (empty? stack)
                  (throw (ex-info "SMT-LIB pop without push" {}))
                  (recur (rest remaining) (pop stack) (peek stack) queries))
          "assert" (recur (rest remaining) stack
                           (if (seq stack)
                             (conj local (unwrap-annotation (second form)))
                             local)
                           queries)
          "check-sat" (recur (rest remaining) stack local
                              (conj queries {:assertions local}))
          (recur (rest remaining) stack local queries)))
      (do
        (when (seq stack)
          (throw (ex-info "SMT-LIB push without matching pop" {})))
        queries))))

(defn- selector-value [assertions selector]
  (some (fn [expr]
          (when (and (vector? expr) (= "=" (first expr)) (= 3 (count expr)))
            (let [[_ a b] expr]
              (cond
                (= a selector) (literal b)
                (= b selector) (literal a)))))
        assertions))

(defn- asserted-symbol? [assertions symbol]
  (boolean (some #(= symbol %) assertions)))

(defn- conjuncts [expr]
  (let [expr (normalize expr)]
    (if (and (vector? expr) (= "and" (first expr)))
      (rest expr)
      [expr])))

(defn- asserts-classification?
  [assertions predicate implementation-result accept?]
  (let [observed (set (mapcat conjuncts assertions))
        required (if accept?
                   #{predicate implementation-result}
                   #{["not" predicate] ["not" implementation-result]})]
    (set/subset? required observed)))

(defn- query-key [assertions {:keys [selector case-selector violation]}]
  {:selector (selector-value assertions selector)
   :case (when case-selector (selector-value assertions case-selector))
   :violation? (asserted-symbol? assertions violation)})

(defn- solver-results [z3 spec]
  (let [{:keys [exit out err]} (shell/sh z3 spec)]
    (when-not (zero? exit)
      (throw (ex-info "z3 failed" {:exit exit :stderr err})))
    (let [results (->> (str/split-lines out)
                       (map str/trim)
                       (filter #{"sat" "unsat" "unknown"})
                       vec)]
      (when (some #{"unknown"} results)
        (throw (ex-info "z3 returned unknown" {:results results})))
      results)))

(defn- expected-query [{:keys [selector case-selector violation] :as reference}
                       value case-value violation?]
  (cond-> {:selector value :violation? violation?}
    case-selector (assoc :case case-value)))

(defn analyze! [contract-path]
  (let [contract (edn/read-string (slurp contract-path))
        spec (:spec contract)
        forms (parse-smt (slurp spec))
        defs (definitions forms)
        {:keys [predicate implementation-result selector value violation]
         :as reference} (:reference contract)
        required [predicate implementation-result selector violation]
        _ (when (some #(not (string? %)) required)
            (throw (ex-info "reference roles must be SMT symbol strings" {:reference reference})))
        _ (when-not (integer? value)
            (throw (ex-info "reference selector value must be an integer" {:value value})))
        _ (when-not (string? (:case-selector reference))
            (throw (ex-info "reference case selector must be an SMT symbol string"
                            {:case-selector (:case-selector reference)})))
        _ (doseq [symbol [predicate implementation-result]]
            (when-not (contains? defs symbol)
              (throw (ex-info "role has no defining equality" {:symbol symbol}))))
        ref (resolve-expression predicate defs {})
        impl (resolve-expression implementation-result defs {selector (str value)})
        disagreement (resolve-expression violation
                                         (dissoc defs predicate implementation-result)
                                         {})
        expected-disagreement (normalize ["not" ["=" implementation-result predicate]])
        _ (when-not (= expected-disagreement (:expression disagreement))
            (throw (ex-info "violation must be the implementation/reference disagreement"
                            {:expected expected-disagreement
                             :actual (:expression disagreement)})))
        shared (set/intersection (disj (:definitions ref) predicate)
                                 (disj (:definitions impl) implementation-result))
        _ (when (contains? (:definitions impl) predicate)
            (throw (ex-info "checked implementation depends on the reference predicate"
                            {:predicate predicate})))
        _ (when (seq shared)
            (throw (ex-info "checked implementation and reference share derived definitions"
                            {:shared (sort shared)})))
        _ (when (= (:expression ref) (:expression impl))
            (throw (ex-info "checked implementation is a definitional alias of the reference"
                            {:predicate predicate :implementation-result implementation-result})))
        queries (local-queries forms)
        results (solver-results (or (:z3 contract) "z3") spec)
        _ (when-not (= (count queries) (count results))
            (throw (ex-info "could not align check-sat commands with solver results"
                            {:queries (count queries) :results (count results)})))
        observed (mapv (fn [query result]
                         [(query-key (:assertions query) reference) result query])
                       queries results)
        ref-key (expected-query reference value nil true)
        mutants (:mutants contract)
        _ (when-not (seq mutants)
            (throw (ex-info "at least one mutant SAT control is required" {})))
        _ (doseq [mutant mutants]
            (when-not (and (integer? (:value mutant))
                           (integer? (:case mutant)))
              (throw (ex-info "mutant controls require integer value and case selectors"
                              {:mutant mutant}))))
        boundaries (:boundaries contract)
        _ (when-not (seq boundaries)
            (throw (ex-info "at least one boundary/non-vacuity SAT control is required" {})))
        _ (doseq [boundary boundaries]
            (when-not (and (integer? (:case boundary))
                           (boolean? (:accept? boundary)))
              (throw (ex-info "boundary controls require an integer case and boolean accept? classification"
                              {:boundary boundary}))))
        expected-keys (concat [ref-key]
                              (map (fn [{mutant-value :value case-value :case}]
                                     (expected-query reference mutant-value case-value true))
                                   (:mutants contract))
                              (map (fn [{case-value :case}]
                                     (expected-query reference value case-value false))
                                   (:boundaries contract)))
        observed-keys (map first observed)
        _ (when-not (= (frequencies expected-keys) (frequencies observed-keys))
            (throw (ex-info "solver query scopes do not match the anti-vacuity contract"
                            {:expected (frequencies expected-keys)
                             :observed (frequencies observed-keys)})))
        ref-results (map second (filter #(= ref-key (first %)) observed))
        _ (when-not (= ["unsat"] (vec ref-results))
            (throw (ex-info "reference disagreement query must occur once and be UNSAT"
                            {:query ref-key :results (vec ref-results)})))
        _ (doseq [{mutant-value :value case-value :case} mutants]
            (let [key (expected-query reference mutant-value case-value true)
                  found (map second (filter #(= key (first %)) observed))]
              (when-not (= ["sat"] (vec found))
                (throw (ex-info "mutant disagreement query must occur once and be SAT"
                                {:query key :results (vec found)})))))
        _ (doseq [{case-value :case accept? :accept?} boundaries]
            (let [key (expected-query reference value case-value false)
                  matches (filter #(= key (first %)) observed)
                  found (map second matches)
                  assertions (get-in (first matches) [2 :assertions])]
              (when-not (= ["sat"] (vec found))
                (throw (ex-info "boundary/non-vacuity query must occur once and be SAT"
                                {:query key :results (vec found)})))
              (when-not (asserts-classification? assertions predicate
                                                  implementation-result accept?)
                (throw (ex-info "boundary query must explicitly classify both reference and implementation"
                                {:query key :accept? accept?})))))]
    {:reference-definitions (count (:definitions ref))
     :implementation-definitions (count (:definitions impl))
     :mutants (count mutants)
     :boundaries (count boundaries)
     :results results}))

(defn -main [& args]
  (when-not (= 1 (count args))
    (binding [*out* *err*]
      (println "usage: bb test/formal/formal_antivacuity.clj CONTRACT.edn"))
    (System/exit 2))
  (try
    (let [{:keys [reference-definitions implementation-definitions mutants boundaries]}
          (analyze! (first args))]
      (println (format "PASS formal anti-vacuity: independent definitions reference=%d implementation=%d, reference UNSAT, mutants=%d SAT, boundaries=%d SAT"
                       reference-definitions implementation-definitions mutants boundaries)))
    (catch Throwable error
      (fail! (or (ex-message error) (str error))))))

(apply -main *command-line-args*)
