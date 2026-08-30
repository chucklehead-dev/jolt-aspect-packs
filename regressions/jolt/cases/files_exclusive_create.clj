;; regression-id: issue-31
(import '[java.nio.file Files]
        '[java.nio.file.attribute FileAttribute])

(def pass-signature "REGRESSION PASS issue-31")
(def fail-signature "REGRESSION FAIL issue-31")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(let [attrs (into-array FileAttribute [])
      root (Files/createTempDirectory "jolt-regression-31-" attrs)
      existing (.resolve root "existing")
      fresh (.resolve root "fresh")]
  (try
    (Files/createFile existing attrs)
    (Files/write existing (.getBytes "sentinel")
                 (into-array java.nio.file.OpenOption []))
    (let [threw?
          (try
            (Files/createFile existing attrs)
            false
            (catch java.nio.file.FileAlreadyExistsException _ true))
          contents (apply str (map char (Files/readAllBytes existing)))
          fresh-result (Files/createFile fresh attrs)]
      (finish!
        (and threw?
             (= "sentinel" contents)
             (= fresh fresh-result)
             (Files/exists fresh (into-array java.nio.file.LinkOption [])))
        "an existing file must raise without truncation while a fresh file is created"))
    (finally
      (Files/deleteIfExists fresh)
      (Files/deleteIfExists existing)
      (Files/deleteIfExists root))))
