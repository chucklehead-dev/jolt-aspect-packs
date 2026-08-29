(ns jolt.aspect-packs.glimmer.model
  (:require [hegel.trace :as trace]))

(def allowed-root-kinds
  #{:nil :text :native-element :component :unsupported-vector :display-value})

(def privacy-safe-root-shape
  (trace/rule
   :glimmer/privacy-safe-root-shape
   (fn [events]
     (every? (fn [event]
               (or (not= :invoke (:phase event))
                   (and (= #{:root-kind} (set (keys (:input event))))
                        (contains? allowed-root-kinds
                                   (get-in event [:input :root-kind])))))
             events))))

(def rules
  [(trace/contiguous-sequence :glimmer/contiguous-history 1)
   (trace/closed-lifecycles :glimmer/closed-mount-lifecycles)
   (trace/synchronous-parentage :glimmer/synchronous-parentage)
   (trace/context-coherence :glimmer/context-coherence)
   privacy-safe-root-shape])

(defn check!
  [events]
  (trace/check! events rules {:max-events 256}))
