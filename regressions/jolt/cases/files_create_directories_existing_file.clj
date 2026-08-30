;; regression-id: issue-32
(import '[java.nio.file Files]
        '[java.nio.file.attribute FileAttribute])

(def pass-signature "REGRESSION PASS issue-32")
(def fail-signature "REGRESSION FAIL issue-32")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(let [attrs (into-array FileAttribute [])
      root (Files/createTempDirectory "jolt-regression-32-" attrs)
      directory (.resolve root "directory")
      file (.resolve root "file")]
  (try
    (Files/createDirectories directory attrs)
    (let [idempotent-result (Files/createDirectories directory attrs)]
      (Files/createFile file attrs)
      (Files/write file (.getBytes "sentinel")
                   (into-array java.nio.file.OpenOption []))
      (let [threw?
            (try
              (Files/createDirectories file attrs)
              false
              (catch java.nio.file.FileAlreadyExistsException _ true))
            contents (apply str (map char (Files/readAllBytes file)))]
        (finish!
          (and (= directory idempotent-result)
               (Files/isDirectory directory
                                  (into-array java.nio.file.LinkOption []))
               threw?
               (= "sentinel" contents))
          "an existing directory is idempotent but an existing regular file must raise")))
    (finally
      (Files/deleteIfExists file)
      (Files/deleteIfExists directory)
      (Files/deleteIfExists root))))
