(ns jolt.aspect-packs.glitter.lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.ffi :as g]
            [glitter.widget :as widget]
            [jolt.aspect-packs.glitter.model :as model]
            [jolt.aspect-packs.glitter.provider :as provider]
            [jolt.aspect-packs.history :as history]))

(def parent 101)
(def child 202)
(def sibling 303)
(def child-wrapper 404)
(def sibling-wrapper 505)
(def join-point
  {:id :glitter.widget/list-box-child-reorder
   :site-id "glitter-reorder-conformance-site"
   :build-identity "glitter-reorder-conformance-build"})

(defn- event-count [events event]
  (count (filter #(= event %) events)))

(defn- exercise-list-reorder!
  [failure]
  (let [native-events (atom [])
        trace-journal (history/journal)
        step! (fn [step result]
                (swap! native-events conj step)
                (when (= failure step)
                  (throw (ex-info "injected Glitter reorder failure"
                                  {:step step})))
                result)
        thrown
        (with-redefs
          [g/gtk-widget-get-parent
           (fn [pointer]
             (step! (if (= pointer child) :child-parent :sibling-parent)
                    (if (= pointer child) child-wrapper sibling-wrapper)))
           g/g-object-ref-sink
           (fn [_] (step! :ref child))
           g/gtk-list-box-remove
           (fn [_ _]
             (is (widget/suppressing? parent))
             (step! :remove nil))
           g/gtk-list-box-row-get-index
           (fn [_]
             (is (not (widget/suppressing? parent)))
             (step! :index 7))
           g/gtk-list-box-insert
           (fn [actual-parent actual-child index]
             (is (= [parent child 8]
                    [actual-parent actual-child index]))
             (step! :reinsert nil))
           g/g-object-unref
           (fn [_] (step! :unref nil))]
          (binding [history/*journal* trace-journal]
            (try
              (provider/around-list-box-reorder
               join-point [parent child sibling]
               #(widget/reorder-child! :list-box parent child sibling))
              nil
              (catch Throwable error error))))]
    {:native-events @native-events
     :trace-events (history/events trace-journal)
     :thrown thrown}))

(deftest exact-revision-balances-native-lifecycle-and-semantic-history
  (doseq [failure [nil :remove :index :reinsert]]
    (testing (str "failure boundary " failure)
      (let [{:keys [native-events trace-events thrown]}
            (exercise-list-reorder! failure)]
        (if failure
          (is (= failure (:step (ex-data thrown))))
          (is (nil? thrown)))
        (is (= 1 (event-count native-events :ref)))
        (is (= 1 (event-count native-events :unref)))
        (is (= :unref (last native-events)))
        (is (not (widget/suppressing? parent)))
        (is (= (if failure [:invoke :throw] [:invoke :return])
               (mapv :phase trace-events)))
        (is (= trace-events (model/check! trace-events)))))))
