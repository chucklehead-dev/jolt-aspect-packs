(ns jolt.aspect-packs.history)

;; Target-neutral semantic history capture. Library packs own input shaping,
;; provider maps, roles, and advice namespaces.

(def ^:dynamic *journal* nil)
(def ^:dynamic *parent-operation-id* nil)

(defn journal
  "Create a thread-safe semantic history journal."
  []
  (atom {:next-seq 0
         :next-operation-id 0
         :events []}))

(defn events
  "Return the journal's immutable event vector."
  [journal]
  (:events @journal))

(defn- append-event!
  [journal event]
  (let [state
        (swap! journal
               (fn [{:keys [next-seq] :as state}]
                 (-> state
                     (assoc :next-seq (inc next-seq))
                     (update :events conj (assoc event :seq next-seq)))))]
    (peek (:events state))))

(defn- begin-operation!
  [journal join-point input]
  (let [state
        (swap! journal
               (fn [{:keys [next-seq next-operation-id] :as state}]
                 (-> state
                     (assoc :next-seq (inc next-seq)
                            :next-operation-id (inc next-operation-id))
                     (update :events conj
                             {:seq next-seq
                              :operation-id next-operation-id
                              :parent-operation-id *parent-operation-id*
                              :phase :invoke
                              :op (:id join-point)
                              :input input}))))]
    (:operation-id (peek (:events state)))))

(defn invoke!
  "Record one synchronous semantic operation, preserving the exact application
  result or thrown value. `input` is already shaped by the library pack."
  [journal join-point input proceed]
  (let [operation-id (begin-operation! journal join-point input)]
    (try
      (let [result
            (binding [*parent-operation-id* operation-id]
              (proceed))]
        (append-event! journal
                       {:operation-id operation-id
                        :phase :return
                        :output :returned})
        result)
      (catch Throwable error
        (append-event! journal
                       {:operation-id operation-id
                        :phase :throw
                        :output :thrown})
        (throw error)))))

