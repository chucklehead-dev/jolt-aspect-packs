(ns jolt.pending.string-indexing-portability
  (:require [clojure.data.json :as json]))

;; Jolt indexes String by Unicode scalar value; the JVM indexes by UTF-16 code
;; unit. Neither model is treated as a regression here. This witness makes the
;; portability hazard causal and verifies that the owned data.json fork handles
;; both representations safely.

(def pass-signature "WITNESS PASS string-indexing-portability")
(def fail-signature "WITNESS FAIL string-indexing-portability")

(defn- finish! [ok details]
  (if ok
    (println (str pass-signature ": " details))
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(defn- naive-unicode-escape [^String s]
  (apply str
         (map (fn [i] (format "\\u%04x" (int (.charAt s i))))
              (range (.length s)))))

(defn -main [& _]
  ;; U+1D11E MUSICAL SYMBOL G CLEF, built without a source literal or the JSON
  ;; reader under test. Jolt accepts the scalar directly; the JVM accepts its
  ;; UTF-16 surrogate pair.
  (let [clef (try
               (str (char 0x1D11E))
               (catch IllegalArgumentException _
                 (str (char 0xD834) (char 0xDD1E))))
        subject (str "a" clef "b")
        length (.length subject)
        units (mapv (fn [i] (int (.charAt subject i))) (range length))
        index-of-b (.indexOf subject (int 98))
        scalar-model? (= [97 0x1D11E 98] units)
        utf16-model? (= [97 0xD834 0xDD1E 98] units)
        naive (naive-unicode-escape clef)
        naive-json (str "\"" naive "\"")
        naive-roundtrip (json/read-str naive-json)
        safe-json (json/write-str clef :escape-unicode true)
        safe-roundtrip (json/read-str safe-json)
        model (cond scalar-model? "unicode-scalar"
                    utf16-model? "utf-16"
                    :else "unknown")]
    (finish!
     (and (or (and scalar-model? (= 3 length) (= 2 index-of-b))
              (and utf16-model? (= 4 length) (= 3 index-of-b)))
          ;; The naive formatter is safe only under JVM-shaped UTF-16 indexing.
          (= utf16-model? (= clef naive-roundtrip))
          ;; The owned writer must be safe under either host representation.
          (= clef safe-roundtrip)
          (= "\"\\ud834\\udd1e\"" safe-json))
     (str "model=" model
          " naive=" (pr-str naive)
          " naive-roundtrip=" (= clef naive-roundtrip)
          " data.json=" (pr-str safe-json)))))
