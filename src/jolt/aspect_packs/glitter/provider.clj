(ns jolt.aspect-packs.glitter.provider
  (:require [jolt.aspect-packs.history :as history]))

(def target-revision
  "f4e3eb83015566e4cadaedd7f5e8ad80dc57404f")

(defn- null-pointer-shaped?
  [value]
  (or (nil? value)
      (and (number? value) (zero? value))))

(defn- opaque-id
  [journal value]
  (when-not (null-pointer-shaped? value)
    (history/opaque-token! journal value)))

(defn around-list-box-reorder
  "Record Glitter's semantic child reorder without publishing raw native
  pointers or coupling the provider to GTK's FFI namespace."
  [join-point evaluated-args proceed]
  (if-not history/*journal*
    (proceed)
    (let [[_parent child sibling] evaluated-args]
      (history/invoke! history/*journal*
                       join-point
                       {:child-id (opaque-id history/*journal* child)
                        :sibling-id (opaque-id history/*journal* sibling)}
                       proceed))))

(def aspect-provider
  {:schema 1
   :libraries {'burinc/glitter target-revision}
   :roles {:ui/container-reorder
           {:fn 'jolt.aspect-packs.glitter.provider/around-list-box-reorder
            :contract :args-v1}}})
