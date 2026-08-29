(ns jolt.aspect-packs.glitter.model
  (:require [hegel.trace :as trace]))

(def identified-child
  (trace/rule
   :glitter/identified-reorder-child
   (fn [events]
     (every? (fn [event]
               (or (not= :invoke (:phase event))
                   (contains? (:input event) :child-id)))
             events))))

(def rules
  [(trace/contiguous-sequence :glitter/contiguous-history 1)
   (trace/closed-lifecycles :glitter/closed-reorder-lifecycles)
   (trace/synchronous-parentage :glitter/synchronous-parentage)
   (trace/context-coherence :glitter/context-coherence)
   identified-child])

(defn check!
  [events]
  (trace/check! events rules {:max-events 256}))
