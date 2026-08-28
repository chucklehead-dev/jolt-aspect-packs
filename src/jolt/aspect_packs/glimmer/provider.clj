(ns jolt.aspect-packs.glimmer.provider
  (:require [jolt.aspect-packs.history :as history]))

(def target-revision
  "6dab5597dc0d912793fe175d0d3cbb9e75f11426")

(defn- root-kind
  "Classify a root without retaining its values, function identity, element tag,
  props, text, children, metadata, or native container."
  [root]
  (cond
    (nil? root) :nil
    (or (string? root) (number? root)) :text
    (vector? root) (let [head (first root)]
                     (cond
                       (keyword? head) :native-element
                       (fn? head) :component
                       :else :unsupported-vector))
    :else :display-value))

(defn around-mount
  "Record Glimmer's toolkit-independent root-mount lifecycle. Only the coarse
  root shape is retained; potentially private UI content and native identities
  are deliberately excluded."
  [join-point evaluated-args proceed]
  (if-not history/*journal*
    (proceed)
    (history/invoke! history/*journal*
                     join-point
                     {:root-kind (root-kind (nth evaluated-args 2 nil))}
                     proceed)))

(def aspect-provider
  {:schema 1
   :libraries {'jolt-lang/glimmer target-revision}
   :roles {:ui/root-mount
           {:fn 'jolt.aspect-packs.glimmer.provider/around-mount
            :contract :args-v1}}})
