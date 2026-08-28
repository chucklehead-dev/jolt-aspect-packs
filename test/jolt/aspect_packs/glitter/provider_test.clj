(ns jolt.aspect-packs.glitter.provider-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [jolt.aspect-packs.glitter.model :as model]
            [jolt.aspect-packs.glitter.provider :as provider]
            [jolt.aspect-packs.history :as history]))

(def join-point {:id :glitter.widget/list-box-child-reorder})

(defn- run-advice
  [journal args proceed]
  (binding [history/*journal* journal]
    (provider/around-list-box-reorder join-point args proceed)))

(deftest records-opaque-reorder-identity-and-preserves-result
  (let [journal (history/journal)
        result (Object.)]
    (is (identical? result
                    (run-advice journal [101 202 303] #(identity result))))
    (let [events (history/events journal)
          enter (first events)]
      (is (= [:enter :return] (mapv :phase events)))
      (is (= {:child-id (hash 202) :sibling-id (hash 303)}
             (:input enter)))
      (is (not-any? #(contains? (:input %) :parent) events))
      (is (= events (model/check! events))))))

(deftest null-sibling-and-thrown-identity-are-preserved
  (doseq [sibling [nil 0]]
    (let [journal (history/journal)
          expected (ex-info "expected reorder failure" {:sibling sibling})
          observed (try
                     (run-advice journal [101 202 sibling] #(throw expected))
                     nil
                     (catch Throwable error error))
          events (history/events journal)]
      (is (identical? expected observed))
      (is (= [:enter :throw] (mapv :phase events)))
      (is (nil? (get-in events [0 :input :sibling-id])))
      (is (= events (model/check! events))))))

(deftest manifest-and-provider-agree-on-pinned-target
  (let [manifest
        (edn/read-string
         (slurp "resources/META-INF/jolt/aspects/packs/glitter-f4e3eb8.edn"))]
    (is (= 'burinc/glitter (get-in manifest [:library :id])))
    (is (= provider/target-revision
           (get-in manifest [:library :version])))
    (is (= provider/target-revision
           (get-in provider/aspect-provider [:libraries 'burinc/glitter])))
    (is (= 1 (get-in manifest [:aspects 0 :expect :matches])))))

