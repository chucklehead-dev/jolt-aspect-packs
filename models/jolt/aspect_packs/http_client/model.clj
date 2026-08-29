(ns jolt.aspect-packs.http-client.model
  (:require [hegel.trace :as trace]))

(def rules
  "Target-specific semantic rules for completed synchronous HTTP requests."
  [(trace/contiguous-sequence :http-client/contiguous-history 1)
   (trace/closed-lifecycles :http-client/closed-request-lifecycles)
   (trace/synchronous-parentage :http-client/synchronous-parentage)
   (trace/context-coherence :http-client/context-coherence)])

(defn check!
  [events]
  (trace/check! events rules {:max-events 256}))
