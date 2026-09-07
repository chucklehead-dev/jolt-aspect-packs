(ns jolt.aspect-packs.chdb-durable.provider
  (:require [clojure.string :as str]
            [jolt.aspect-packs.history :as history]))

(def seam-revision
  "edc86af07d5982a185c4ef953c19c848a719da0e")

(defn- writer-summary [journal token]
  (when (and (map? token)
             (string? (:owner token))
             (string? (:instance token))
             (integer? (:generation token)))
    {:writer (history/opaque-token! journal
                                    [(:owner token) (:instance token)])
     :generation (:generation token)}))

(defn- reference-summary [journal reference]
  (when (map? reference)
    (let [key (get reference "key")
          match (when (string? key)
                  (re-matches
                   #"(wal|checkpoints)/(\d+)-(\d+)-[0-9a-f]{8}\.(jsonl|tar\.gz)"
                   key))]
      (when match
        {:kind (if (= "wal" (nth match 1)) :wal :checkpoint)
         :generation (parse-long (nth match 2))
         :sequence (parse-long (nth match 3))
         :object (history/opaque-token!
                  journal [(get reference "sha256") (get reference "size")])
         :attempt (history/opaque-token! journal key)}))))

(defn- head-summary [journal head]
  (when (map? head)
    (let [lease (get head "lease")
          manifest (get head "manifest")
          owner (get lease "owner")
          instance (get lease "instance")
          wal (get manifest "wal")]
      (cond-> {:generation (get lease "generation")
               :writer (when (and (string? owner) (string? instance))
                         (history/opaque-token! journal [owner instance]))
               :manifest-sequence (get manifest "seq")}
        (or (map? (get manifest "base")) (seq wal))
        (assoc :last-reference
               (reference-summary journal
                                  (or (peek wal) (get manifest "base"))))))))

(defn- command-input [journal operation args]
  (case operation
    :durable/acquire
    (let [options (second args)]
      {:command :acquire
       :writer (:writer (writer-summary
                         journal
                         {:owner (:owner options)
                          :instance (:instance options)
                          :generation 0}))
       :now (:now options)
       :expires-at (:expires-at options)
       :force? (true? (:force? options))})

    :durable/publish-wal
    {:command :publish
     :writer (writer-summary journal (second args))
     :kind :wal
     :payload-size (when (bytes? (nth args 2 nil))
                     (alength ^bytes (nth args 2)))}

    :durable/publish-checkpoint
    {:command :checkpoint-publish
     :writer (writer-summary journal (second args))}

    :durable/commit-reference
    (let [options (nth args 2 nil)]
      {:command :commit-attempt
       :writer (writer-summary journal (second args))
       :kind (:kind options)
       :reference (reference-summary journal (:reference options))})

    :durable/renew
    {:command :renew-attempt
     :writer (writer-summary journal (second args))
     :expires-at (nth args 2 nil)}

    :durable/release
    {:command :release-attempt
     :writer (writer-summary journal (second args))}

    {:command :unknown}))

(defn- return-summary [journal value]
  (cond-> {:outcome (:status value)}
    (map? (:head value)) (assoc :head (head-summary journal (:head value)))
    (map? (:reference value))
    (assoc :reference (reference-summary journal (:reference value)))))

(defn- throw-summary [error]
  (let [type (:type (ex-data error))]
    {:outcome :error
     :error-type (if (keyword? type) type :unknown)}))

(defn around-control
  "Capture privacy-shaped Durable commands for offline trace validation.

  Advice records no stores, bytes, paths, object keys, digests, credentials,
  owner/instance values, or exception messages. It is inert without a journal."
  [join-point evaluated-args proceed]
  (if-not history/*journal*
    (proceed)
    (let [journal history/*journal*]
      (history/invoke! journal join-point
                       (command-input journal (:id join-point) evaluated-args)
                       {:return-fn #(return-summary journal %)
                        :throw-fn throw-summary}
                       proceed))))

(def aspect-provider
  {:schema 1
   :libraries {'io.github.chucklehead-dev/jolt-chdb seam-revision}
   :roles {:durable/control
           {:fn 'jolt.aspect-packs.chdb-durable.provider/around-control
            :contract :args-v1}}})
