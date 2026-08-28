(ns jolt.aspect-packs.glimmer.mount-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.backend :as backend]
            [glimmer.core :as glimmer]
            [jolt.aspect-packs.glimmer.model :as model]
            [jolt.aspect-packs.glimmer.provider :as provider]
            [jolt.aspect-packs.history :as history]))

(def join-point {:id :glimmer.core/root-mount})

(defn- widget [tag props]
  (atom {:tag tag :props props :children []}))

(defn- backend
  [failure]
  {:name :central-glimmer-conformance
   :create! (fn [tag props]
              (when (= failure :create)
                (throw (ex-info "injected create failure" {:step :create})))
              (widget tag props))
   :apply-props! (fn [_tag target props]
                   (swap! target assoc :props props))
   :append-child! (fn [_tag parent child]
                    (when (= failure :append)
                      (throw (ex-info "injected append failure" {:step :append})))
                    (swap! parent update :children conj child))
   :remove-child! (fn [_tag parent child]
                    (swap! parent update :children
                           #(vec (remove (fn [candidate]
                                           (identical? candidate child)) %))))
   :replace-child! (fn [_tag parent old-child new-child]
                     (swap! parent update :children
                            #(mapv (fn [candidate]
                                     (if (identical? candidate old-child)
                                       new-child candidate)) %)))})

(defn- exercise!
  [failure]
  (backend/register! (backend failure))
  (let [container (widget :root {})
        journal (history/journal)
        thrown (binding [history/*journal* journal]
                 (try
                   (provider/around-mount
                    join-point [container :root [:vbox {} [:label {:label "private"}]]]
                    #(glimmer/mount
                      container :root [:vbox {} [:label {:label "private"}]]))
                   nil
                   (catch Throwable error error)))]
    {:container container :events (history/events journal) :thrown thrown}))

(deftest exact-source-mount-has-closed-privacy-safe-history
  (doseq [failure [nil :create :append]]
    (testing (str "mount failure boundary " failure)
      (let [{:keys [container events thrown]} (exercise! failure)]
        (if failure
          (do
            (is (= failure (:step (ex-data thrown))))
            (is (= [:enter :throw] (mapv :phase events))))
          (do
            (is (nil? thrown))
            (is (= [:vbox] (mapv #(-> % deref :tag) (:children @container))))
            (is (= [:enter :return] (mapv :phase events)))))
        (is (= {:root-kind :native-element} (:input (first events))))
        (is (not-any? #{"private" :root}
                      (tree-seq coll? seq events)))
        (is (= events (model/check! events)))))))
