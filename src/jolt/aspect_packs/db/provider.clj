(ns jolt.aspect-packs.db.provider
  "Canonical semantic-history consumer for the jolt-lang/db driver seam."
  (:require [clojure.string :as str]
            [db.driver :as driver]
            [jolt.aspect-packs.history :as history]))

(def target-revision
  "Exact target revision validated by this pack."
  "0c559d78d839f2f9c8cc1a7326a639134134bfac")

(def seam-revision
  "Source compatibility id published by the target's inert manifest."
  "a55c554a66d8f5e9e5198e238773f8218f6050d7")

(def ^:private max-statement-scan 4096)
(def ^:private max-cardinality 9223372036854775807)
(def ^:private max-error-type-length 255)
(def ^:private known-operations
  #{"SELECT" "INSERT" "UPDATE" "DELETE" "MERGE"
    "CREATE" "ALTER" "DROP" "TRUNCATE"
    "BEGIN" "COMMIT" "ROLLBACK" "SAVEPOINT" "RELEASE"
    "PRAGMA" "EXPLAIN" "CALL" "COPY"})
(def ^:private result-operations #{"SELECT" "EXPLAIN" "CALL"})
(def ^:private mutation-operations #{"INSERT" "UPDATE" "DELETE" "MERGE" "COPY"})
(def ^:private hex-digits "0123456789abcdef")

(defn- ascii-letter? [c]
  (let [n (int c)]
    (or (<= 65 n 90) (<= 97 n 122))))

(defn- simple-statement
  "Return bounded lexical facts for exactly one complete SQL statement.

  This is deliberately not a SQL parser. Comments and common quoted forms are
  recognized only so their semicolons cannot create false compound statements.
  Multiple statements, leading/repeated terminators, unbalanced delimiters,
  over-bound input, and dialect-dependent dollar quoting fail closed."
  [sql]
  (when (and (string? sql) (<= (count sql) max-statement-scan))
    (let [n (count sql)]
      (loop [i 0 mode :code block-depth 0 paren-depth 0
             content? false terminated? false words []]
        (if (= i n)
          (when (and content?
                     (zero? paren-depth)
                     (contains? #{:code :line-comment} mode))
            {:first-word (first words)})
          (let [c (nth sql i)
                next-c (when (< (inc i) n) (nth sql (inc i)))]
            (case mode
              :line-comment
              (if (or (= c \newline) (= c \return))
                (recur (inc i) :code 0 paren-depth
                       content? terminated? words)
                (recur (inc i) :line-comment 0 paren-depth
                       content? terminated? words))

              :block-comment
              (cond
                (and (= c \/) (= next-c \*))
                (recur (+ i 2) :block-comment (inc block-depth) paren-depth
                       content? terminated? words)
                (and (= c \*) (= next-c \/))
                (let [depth (dec block-depth)]
                  (recur (+ i 2) (if (zero? depth) :code :block-comment)
                         depth paren-depth content? terminated? words))
                :else
                (recur (inc i) :block-comment block-depth paren-depth
                       content? terminated? words))

              :single
              (cond
                (= c \\) nil
                (and (= c \') (= next-c \'))
                (recur (+ i 2) :single 0 paren-depth
                       content? terminated? words)
                (= c \')
                (recur (inc i) :code 0 paren-depth
                       content? terminated? words)
                :else
                (recur (inc i) :single 0 paren-depth
                       content? terminated? words))

              :double
              (cond
                (= c \\) nil
                (and (= c \u0022) (= next-c \u0022))
                (recur (+ i 2) :double 0 paren-depth
                       content? terminated? words)
                (= c \u0022)
                (recur (inc i) :code 0 paren-depth
                       content? terminated? words)
                :else
                (recur (inc i) :double 0 paren-depth
                       content? terminated? words))

              :backtick
              (cond
                (= c \\) nil
                (and (= c \`) (= next-c \`))
                (recur (+ i 2) :backtick 0 paren-depth
                       content? terminated? words)
                (= c \`)
                (recur (inc i) :code 0 paren-depth
                       content? terminated? words)
                :else
                (recur (inc i) :backtick 0 paren-depth
                       content? terminated? words))

              ;; :code
              (cond
                (or (= c \space) (= c \tab) (= c \newline) (= c \return)
                    (= c \formfeed))
                (recur (inc i) :code 0 paren-depth
                       content? terminated? words)

                (and (= c \-) (= next-c \-))
                (recur (+ i 2) :line-comment 0 paren-depth
                       content? terminated? words)

                (and (= c \/) (= next-c \*))
                (recur (+ i 2) :block-comment 1 paren-depth
                       content? terminated? words)

                ;; Once the optional trailing terminator has been seen, only
                ;; whitespace and comments are valid.
                terminated? nil

                (= c \;)
                (when content?
                  (recur (inc i) :code 0 paren-depth true true words))

                (= c \')
                (recur (inc i) :single 0 paren-depth true false words)

                (= c \u0022)
                (recur (inc i) :double 0 paren-depth true false words)

                (= c \`)
                (recur (inc i) :backtick 0 paren-depth true false words)

                (= c \()
                (recur (inc i) :code 0 (inc paren-depth) true false words)

                (= c \))
                (when (pos? paren-depth)
                  (recur (inc i) :code 0 (dec paren-depth) true false words))

                (ascii-letter? c)
                (let [end (loop [j (inc i)]
                            (if (and (< j n) (ascii-letter? (nth sql j)))
                              (recur (inc j))
                              j))
                      word (str/upper-case (subs sql i end))]
                  (recur end :code 0 paren-depth true false
                         (if (zero? paren-depth) (conj words word) words)))

                ;; Dollar quoting and other unrecognized dollar-prefixed
                ;; forms are dialect-dependent; do not guess around embedded
                ;; semicolons.
                (or (= c \$) (= c \[) (= c \])) nil

                :else
                (recur (inc i) :code 0 paren-depth true false words)))))))))

(defn operation-name
  "Return a bounded, closed SQL operation name without retaining SQL text."
  [sql]
  (let [operation (:first-word (simple-statement sql))]
    ;; WITH/CTE classification needs a dialect-aware parser or driver-supplied
    ;; semantic operation. Treat it as unknown instead of guessing from tokens.
    (if (and (not= "WITH" operation)
             (contains? known-operations operation))
      operation
      "UNKNOWN")))

(defn- redact-quoted
  "Replace literals, quoted identifiers, and comments before fingerprinting."
  [sql]
  (let [n (count sql)]
    (loop [i 0 mode :code block-depth 0 out (transient [])]
      (if (= i n)
        (apply str (persistent! out))
        (let [c (nth sql i)
              next-c (when (< (inc i) n) (nth sql (inc i)))]
          (case mode
            :code
            (cond
              (and (= c \-) (= next-c \-))
              (recur (+ i 2) :line-comment 0 (conj! out \space))

              (and (= c \/) (= next-c \*))
              (recur (+ i 2) :block-comment 1 (conj! out \space))

              (= c \') (recur (inc i) :single 0 (conj! out \S))
              (= c \u0022) (recur (inc i) :double 0 (conj! out \I))
              (= c \`) (recur (inc i) :backtick 0 (conj! out \I))
              :else (recur (inc i) :code 0 (conj! out c)))

            :line-comment
            (if (or (= c \newline) (= c \return))
              (recur (inc i) :code 0 (conj! out \space))
              (recur (inc i) :line-comment 0 out))

            :block-comment
            (cond
              (and (= c \/) (= next-c \*))
              (recur (+ i 2) :block-comment (inc block-depth) out)
              (and (= c \*) (= next-c \/))
              (let [depth (dec block-depth)]
                (recur (+ i 2) (if (zero? depth) :code :block-comment)
                       depth (if (zero? depth) (conj! out \space) out)))
              :else (recur (inc i) :block-comment block-depth out))

            (let [quote (case mode :single \' :double \u0022 :backtick \`)]
              (cond
                (and (= c \\) next-c)
                (recur (+ i 2) mode block-depth out)
                (and (= c quote) (= next-c quote))
                (recur (+ i 2) mode block-depth out)
                (= c quote)
                (recur (inc i) :code 0 out)
                :else
                (recur (inc i) mode block-depth out)))))))))

(defn- statement-shape [sql]
  (-> (subs sql 0 (min max-statement-scan (count sql)))
      redact-quoted
      ;; Retain only SQL punctuation and token boundaries. Masking every other
      ;; token also covers Unicode and dialect-specific identifiers without
      ;; relying on one engine's identifier grammar.
      (str/replace #"[^\s(),.;?=<>+\-*/%]+" "I")
      (str/replace #"\s+" " ")
      str/trim))

(defn- hex32 [n]
  (loop [value n remaining 8 chars ()]
    (if (zero? remaining)
      (apply str chars)
      (recur (quot value 16) (dec remaining)
             (conj chars (nth hex-digits (mod value 16)))))))

(defn statement-fingerprint
  "Return a stable fingerprint of statement structure after removing comments,
  literals, identifiers, and excess whitespace. Raw SQL and parameter values
  are never retained."
  [sql]
  (when (simple-statement sql)
    (let [shape (str (operation-name sql) "|" (statement-shape sql))
          hash (reduce (fn [acc c]
                         (mod (* (bit-xor acc (int c)) 16777619) 4294967296))
                       2166136261 shape)]
      (str "fnv1a32:" (hex32 hash)))))

(defn- safe-descriptor [db-driver]
  (try
    (let [value (driver/descriptor db-driver)]
      (if (map? value) value {}))
    (catch Throwable _ {})))

(defn- system-name [descriptor]
  (case (:id descriptor)
    :chdb "clickhouse"
    :duckdb "duckdb"
    :sqlite "sqlite"
    :postgresql "postgresql"
    :postgres "postgresql"
    "other_sql"))

(defn- invocation [descriptor sql]
  (let [fingerprint (statement-fingerprint sql)]
    (cond-> {:operation (operation-name sql)
             :system (system-name descriptor)}
      fingerprint (assoc :statement-fingerprint fingerprint))))

(defn- row-count [operation result]
  (when (map? result)
    (cond
      (and (contains? result-operations operation) (vector? (:rows result)))
      {:row-count (count (:rows result)) :row-count-kind :returned}

      (and (contains? mutation-operations operation)
           (integer? (:count result))
           (<= 0 (:count result) max-cardinality))
      {:row-count (:count result) :row-count-kind :affected})))

(defn- returned [operation result]
  (merge {:outcome :ok} (row-count operation result)))

(defn- thrown [error]
  (let [candidate (try (some-> error class .getName)
                       (catch Throwable _ nil))
        safe? (and (string? candidate)
                   (pos? (count candidate))
                   (<= (count candidate) max-error-type-length)
                   (not-any? #(let [n (int %)] (or (< n 32) (= n 127)))
                             candidate))]
    {:outcome :error
     :error-type (if safe? candidate "UnknownExceptionType")}))

(defn around-execute
  "Record one synchronous driver operation through the canonical history ABI.

  The pack owns all shaping. It never records the driver, handle, parameters,
  SQL text, result values, or exception messages, and it preserves application
  result and Throwable identity."
  [join-point [db-driver _handle sql _params] proceed]
  (if-not history/*journal*
    (proceed)
    (let [descriptor (safe-descriptor db-driver)
          input (invocation descriptor sql)
          operation (:operation input)]
      (history/invoke! history/*journal* join-point input
                       {:return-fn #(returned operation %)
                        :throw-fn thrown}
                       proceed))))

(def aspect-provider
  {:schema 1
   :libraries {'jolt-lang/db seam-revision}
   :roles {:db/client
           {:fn 'jolt.aspect-packs.db.provider/around-execute
            :contract :args-v1}}})
