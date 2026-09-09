(ns jolt.aspect-packs.chdb-durable.provider-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [jolt.aspect-packs.chdb-durable.model :as model]
            [jolt.aspect-packs.chdb-durable.provider :as provider]
            [jolt.aspect-packs.history :as history]))

(defn- join-point [operation]
  {:id operation
   :site-id (str "test-site-" (name operation))
   :build-identity "chdb-durable-provider-test-build"})

(defn- head [generation owner instance sequence last-reference]
  {"lease" {"generation" generation "owner" owner "instance" instance}
   "manifest" (cond-> {"seq" sequence "base" nil "wal" []}
                last-reference (assoc "wal" [last-reference]))})

(defn- invoke [journal operation args result]
  (binding [history/*journal* journal history/*context-id* :durable-test]
    (provider/around-control (join-point operation) args #(identity result))))

(deftest records-model-checkable-control-commands-without-private-data
  (let [journal (history/journal)
        store {:private "store-secret"}
        token {:owner "owner-secret" :instance "instance-secret" :generation 1}
        reference {"key" "wal/1-1-deadbeef.jsonl"
                   "size" 18
                   "sha256" "digest-secret"}
        active (head 1 "owner-secret" "instance-secret" 0 nil)
        committed (head 1 "owner-secret" "instance-secret" 1 reference)
        released (head 1 nil nil 1 reference)]
    (invoke journal :durable/acquire
            [store {:owner "owner-secret" :instance "instance-secret"
                    :now 10 :expires-at 20 :force? false}]
            {:status :acquired :head active :token token :etag "etag-secret"})
    (invoke journal :durable/publish-wal
            [store token (.getBytes "private-wal-bytes" "UTF-8")
             {:stopped? (fn [] false) :private "retry-secret"}]
            {:status :published :reference reference :etag "etag-secret"})
    (invoke journal :durable/commit-reference
            [store token {:kind :wal :reference reference
                          :verify-reference! (fn [_ _] true)}]
            {:status :committed :head committed :token token
             :etag "etag-secret"})
    (invoke journal :durable/renew
            [store token 30
             {:stopped? (fn [] false) :private "retry-secret"}]
            {:status :committed :head committed :token token})
    (invoke journal :durable/release
            [store token {:stopped? (fn [] false) :private "retry-secret"}]
            {:status :committed :head released :token nil})
    (let [events (history/events journal)
          commands (model/commands events)
          printed (pr-str events)]
      (is (= events (model/check! events)))
      (is (= [:acquire :publish :commit-attempt :renew-attempt :release-attempt]
             (mapv :command commands)))
      (is (= [0 1 1 1]
             [(get-in commands [0 :result :head :manifest-sequence])
              (get-in commands [1 :result :reference :sequence])
              (get-in commands [2 :result :head :manifest-sequence])
              (get-in commands [4 :result :head :manifest-sequence])]))
      (is (= (get-in commands [0 :writer])
             (get-in commands [2 :writer :writer])))
      (is (apply = (map :durable-object commands)))
      (is (apply = (map #(get-in % [:result :durable-object]) commands)))
      (is (= (get-in commands [0 :durable-object])
             (get-in commands [0 :result :durable-object])))
      (is (nil? (get-in commands [4 :result :head :writer])))
      (doseq [secret ["store-secret" "owner-secret" "instance-secret"
                      "private-wal-bytes" "deadbeef" "digest-secret"
                      "etag-secret" "retry-secret"]]
        (is (not (.contains printed secret))))
      (is (true? (history/assert-complete! journal))))))

(deftest partitions-interleaved-control-histories-by-opaque-durable-object
  (let [journal (history/journal)
        store-a {:root "/private/a"}
        store-b {:root "/private/b"}
        active-a (head 1 "owner-a" "instance-a" 0 nil)
        active-b (head 1 "owner-b" "instance-b" 0 nil)]
    (invoke journal :durable/acquire
            [store-a {:owner "owner-a" :instance "instance-a"
                      :now 10 :expires-at 20 :force? false}]
            {:status :acquired :head active-a})
    (invoke journal :durable/acquire
            [store-b {:owner "owner-b" :instance "instance-b"
                      :now 11 :expires-at 21 :force? false}]
            {:status :acquired :head active-b})
    (let [events (history/events journal)
          object-a (get-in events [0 :input :durable-object])
          object-b (get-in events [2 :input :durable-object])
          wrong-terminal
          (assoc-in events [1 :value :durable-object] object-b)
          collapsed
          (-> events
              (assoc-in [2 :input :durable-object] object-a)
              (assoc-in [3 :value :durable-object] object-a))]
      (is (not= object-a object-b))
      (is (= events (model/check! events)))
      (is (thrown? Exception (model/check! wrong-terminal)))
      (is (thrown? Exception (model/check! collapsed)))
      (doseq [secret ["/private/a" "/private/b" "owner-a" "owner-b"
                      "instance-a" "instance-b"]]
        (is (not (.contains (pr-str events) secret)))))))

(deftest checkpoint-publication-and-errors-use-bounded-shapes
  (let [journal (history/journal)
        token {:owner "private-owner" :instance "private-instance"
               :generation 7}
        reference {"key" "checkpoints/7-4-cafebabe.tar.gz"
                   "size" 999 "sha256" "private-digest"}]
    (invoke journal :durable/publish-checkpoint
            [:private-store token "/private/checkpoint.tar.gz"]
            {:status :reconciled :reference reference})
    (let [expected (ex-info "private error message"
                            {:type :jdbc.chdb.durable.control/lease-fenced
                             :owner "private-owner"})
          observed (try
                     (binding [history/*journal* journal]
                       (provider/around-control
                        (join-point :durable/release)
                        [:private-store token]
                        #(throw expected)))
                     nil
                     (catch Throwable error error))
          events (history/events journal)]
      (is (identical? expected observed))
      (is (= :jdbc.chdb.durable.control/lease-fenced
             (get-in events [3 :value :error-type])))
      (doseq [secret ["private-owner" "private-instance" "private-digest"
                      "/private/checkpoint.tar.gz" "private error message"]]
        (is (not (.contains (pr-str events) secret))))
      (is (= events (model/check! events))))))

(deftest model-rejects-a-commit-that-skips-manifest-sequence
  (let [journal (history/journal)
        token {:owner "owner" :instance "instance" :generation 1}
        reference {"key" "wal/1-1-deadbeef.jsonl" "size" 1 "sha256" "x"}
        active (head 1 "owner" "instance" 0 nil)
        committed (head 1 "owner" "instance" 1 reference)]
    (invoke journal :durable/acquire
            [nil {:owner "owner" :instance "instance"
                  :now 1 :expires-at 2}]
            {:status :acquired :head active})
    (invoke journal :durable/commit-reference
            [nil token {:kind :wal :reference reference}]
            {:status :committed :head committed})
    (let [events (history/events journal)
          mutated (assoc-in events [3 :value :head :manifest-sequence] 2)]
      (is (= events (model/check! events)))
      (is (thrown? Exception (model/check! mutated))))))

(deftest pack-targets-terminal-retry-arities-within-the-opaque-seam-revision
  (let [pack (edn/read-string
              (slurp (io/resource
                      "META-INF/jolt/aspects/packs/chdb-durable-4a0b821.edn")))
        target-resource
        (io/resource "META-INF/jolt/aspects/jolt-chdb-durable.edn")
        target (some-> target-resource slurp edn/read-string)]
    (is (some? target-resource))
    (is (= (:library pack) (:library target)))
    (is (= provider/seam-revision (get-in pack [:library :version])))
    (is (= (:aspects target) (:aspects pack)))
    (is (= {:durable/acquire 2
            :durable/publish-wal 4
            :durable/publish-checkpoint 4
            :durable/commit-reference 3
            :durable/renew 4
            :durable/release 3}
           (into {} (map (juxt :id #(get-in % [:match :arity])))
                 (:aspects pack))))))

(deftest absent-journal-is-inert
  (let [result (Object.)
        calls (atom 0)]
    (is (identical?
         result
         (provider/around-control
          (join-point :durable/acquire)
          [:private-store {:owner "private" :instance "private"}]
          #(do (swap! calls inc) result))))
    (is (= 1 @calls))))
