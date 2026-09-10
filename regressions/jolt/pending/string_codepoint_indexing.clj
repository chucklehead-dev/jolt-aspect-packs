;; regression-id: pending-string-codepoint-indexing
;;
;; Jolt indexes java.lang.String by Unicode codepoint. The JVM indexes it by
;; UTF-16 code unit. Every program that walks a string by index and reasons
;; about what it finds there is therefore host-dependent, and the failure is
;; silent: no exception, no truncation, just a different answer.
;;
;; The concrete way this bites is \uXXXX escaping. A JSON or SQL writer that
;; formats (.charAt s i) as %04x produces a five-hex-digit escape on Jolt for
;; any character above the BMP, because .charAt hands back the whole codepoint
;; rather than a surrogate. A reader then takes the first four digits and
;; leaves the fifth as a literal character, so U+1D11E round-trips as U+1D11
;; followed by "e". This was found in a Durable WAL writer, where it would have
;; corrupted any statement carrying an astral character.

(def pass-signature "REGRESSION PASS pending-string-codepoint-indexing")
(def fail-signature "REGRESSION FAIL pending-string-codepoint-indexing")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

;; U+1D11E MUSICAL SYMBOL G CLEF, built without a source literal so the reader
;; cannot be the thing under test.
(def clef (str (char 0x1D11E)))
(def subject (str "a" clef "b"))

(let [length (.length subject)
      units (mapv (fn [i] (int (.charAt subject i))) (range length))
      index-of-b (.indexOf subject (int 98))
      escaped (apply str (map (fn [u] (format "\\u%04x" u)) units))]
  (finish!
   (and
    ;; The JVM sees four UTF-16 code units: 'a', D834, DD1E, 'b'.
    (= 4 length)
    (= [97 0xD834 0xDD1E 98] units)
    (= 3 index-of-b)
    ;; And so every unit fits the four-digit escape the format string assumes.
    (= 24 (count escaped)))
   (str "String must be indexed by UTF-16 code unit: "
        "length=" length " units=" (mapv (fn [u] (format "U+%04X" u)) units)
        " indexOf=" index-of-b " escaped=" (pr-str escaped))))
