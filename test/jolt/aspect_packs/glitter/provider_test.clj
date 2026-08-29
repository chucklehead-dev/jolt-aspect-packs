(ns jolt.aspect-packs.glitter.provider-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [jolt.aspect-packs.glitter.model :as model]
            [jolt.aspect-packs.glitter.provider :as provider]
            [jolt.aspect-packs.history :as history]))

(def join-point {:id :glitter.widget/list-box-child-reorder
                 :site-id "glitter-test-site"
                 :build-identity "aspect-packs-test-build"})

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
          enter (first events)
          child-id (get-in enter [:input :child-id])
          sibling-id (get-in enter [:input :sibling-id])]
      (is (= [:invoke :return] (mapv :phase events)))
      (is (string? child-id))
      (is (string? sibling-id))
      (is (not= child-id sibling-id))
      (is (not= 202 child-id))
      (is (not= 303 sibling-id))
      (is (not-any? #(contains? (:input %) :parent) events))
      (is (= events (model/check! events))))))

(deftest opaque-identities-are-stable-only-within-the-journal
  (let [journal (history/journal)]
    (run-advice journal [101 202 303] (constantly nil))
    (run-advice journal [101 202 404] (constantly nil))
    (let [[first-enter second-enter]
          (filterv #(= :invoke (:phase %)) (history/events journal))]
      (is (= (get-in first-enter [:input :child-id])
             (get-in second-enter [:input :child-id])))
      (is (not= (get-in first-enter [:input :sibling-id])
                (get-in second-enter [:input :sibling-id])))
      (is (not-any? #{202 303 404}
                    (mapcat vals (map :input [first-enter second-enter])))))))

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
      (is (= [:invoke :throw] (mapv :phase events)))
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
