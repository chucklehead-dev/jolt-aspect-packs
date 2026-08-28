(ns jolt.aspect-packs.glimmer.provider-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [jolt.aspect-packs.glimmer.model :as model]
            [jolt.aspect-packs.glimmer.provider :as provider]
            [jolt.aspect-packs.history :as history]))

(def join-point {:id :glimmer.core/root-mount})

(defn- run-advice
  [journal args proceed]
  (binding [history/*journal* journal]
    (provider/around-mount join-point args proceed)))

(deftest records-only-coarse-root-shape-and-preserves-result
  (let [private-container (Object.)
        secret-props {:label "secret" :on-click (fn [] :secret)}
        roots [[(fn [] [:label secret-props])]
               [:label secret-props]
               "secret text"
               nil
               [{} secret-props]
               {:private "display value"}]
        expected-kinds [:component :native-element :text :nil
                        :unsupported-vector :display-value]
        journal (history/journal)]
    (doseq [root roots]
      (let [result (Object.)]
        (is (identical? result
                        (run-advice journal
                                    [private-container :private-container root]
                                    #(identity result))))))
    (let [events (history/events journal)
          enters (filterv #(= :enter (:phase %)) events)]
      (is (= expected-kinds (mapv #(get-in % [:input :root-kind]) enters)))
      (is (every? #(= #{:root-kind} (set (keys (:input %)))) enters))
      (is (not-any? #(or (= private-container %)
                         (= secret-props %)
                         (= "secret text" %)
                         (= :private-container %))
                    (tree-seq coll? seq events)))
      (is (= events (model/check! events))))))

(deftest preserves-thrown-identity
  (let [journal (history/journal)
        expected (ex-info "expected mount failure" {:private "not recorded"})
        observed (try
                   (run-advice journal [nil :box [:label {:label "private"}]]
                               #(throw expected))
                   nil
                   (catch Throwable error error))
        events (history/events journal)]
    (is (identical? expected observed))
    (is (= [:enter :throw] (mapv :phase events)))
    (is (= {:root-kind :native-element} (:input (first events))))
    (is (= events (model/check! events)))))

(deftest no-journal-is-inert
  (let [called (atom 0)
        result (Object.)]
    (is (identical? result
                    (provider/around-mount
                     join-point [nil :box [:label]]
                     #(do (swap! called inc) result))))
    (is (= 1 @called))))

(deftest manifest-and-provider-agree-on-pinned-target
  (let [manifest
        (edn/read-string
         (slurp "resources/META-INF/jolt/aspects/packs/glimmer-6dab559.edn"))]
    (is (= 1 (:schema manifest)))
    (is (= 'jolt-lang/glimmer (get-in manifest [:library :id])))
    (is (= provider/target-revision (get-in manifest [:library :version])))
    (is (= provider/target-revision
           (get-in provider/aspect-provider [:libraries 'jolt-lang/glimmer])))
    (is (= :ui/root-mount (get-in manifest [:aspects 0 :advice-role])))
    (is (= 1 (get-in manifest [:aspects 0 :expect :matches])))))
