(ns jolt.aspect-packs.mycelium.provider-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hegel.clojure-test :as hegel-test]
            [hegel.generator :as g]
            [jolt.aspect-packs.history :as history]
            [jolt.aspect-packs.mycelium.model :as model]
            [jolt.aspect-packs.mycelium.provider :as provider]))

(def workflow-join-point
  {:id :mycelium.workflow/lifecycle
   :site-id "mycelium-workflow-event-site"
   :build-identity "mycelium-provider-test-build"})

(def edge-join-point
  {:id :mycelium.workflow/edge-decision
   :site-id "mycelium-edge-event-site"
   :build-identity "mycelium-provider-test-build"})

(def execution-id "123e4567-e89b-42d3-a456-426614174000")
(def second-execution-id "223e4567-e89b-42d3-a456-426614174001")
(def graph-id
  "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

(def graph
  {:schema 1
   :graph-id graph-id
   :entry {:node :start :cell :worker/start}
   :terminals [{:node :finish :cell :worker/finish}]
   :nodes [{:node :finish :cell :worker/finish}
           {:node :start :cell :worker/start}]
   :edges [{:edge-key [:finish :always :end]
            :source :finish :label :always :target :end}
           {:edge-key [:start :done :finish]
            :source :start :label :done :target :finish}
           {:edge-key [:start :retry :start]
            :source :start :label :retry :target :start}]})

(defn workflow-event
  ([phase] (workflow-event execution-id phase))
  ([id phase]
   (cond-> {:schema 1 :graph-id graph-id :execution-id id
            :kind :async :phase phase}
     (= :invoke phase) (assoc :graph graph))))

(defn edge-event
  ([edge-key] (edge-event execution-id edge-key))
  ([id edge-key]
   {:schema 1 :execution-id id :edge-key edge-key}))

(defn- workflow! [journal event]
  (binding [history/*journal* journal history/*context-id* :request-17]
    (provider/around-workflow-event workflow-join-point [event]
                                    #(identity event))))

(defn- edge! [journal event]
  ;; Deliberately use a different ambient context. The provider must restore
  ;; the context captured by the lifecycle invoke for async edge callbacks.
  (binding [history/*journal* journal history/*context-id* :callback-noise]
    (provider/around-edge-event edge-join-point [event]
                                #(identity event))))

(defn- complete-history
  ([phase] (complete-history phase [[:start :done :finish]]))
  ([phase edge-keys]
   (let [journal (history/journal)]
     (workflow! journal (workflow-event :invoke))
     (doseq [edge-key edge-keys]
       (edge! journal (edge-event edge-key)))
     (workflow! journal (workflow-event phase))
     (history/events journal))))

(use-fixtures :each
  (fn [f]
    (provider/reset-state!)
    (f)
    (provider/reset-state!)))

(deftest lifecycle-and-edge-events-produce-canonical-correlated-history
  (doseq [[source-terminal history-terminal outcome]
          [[:return :return :ok]
           [:throw :throw :error]
           [:cancel :return :cancel]]]
    (testing source-terminal
      (let [events (complete-history source-terminal)
            [lifecycle-invoke edge-invoke edge-return lifecycle-terminal] events]
        (is (= [:invoke :invoke :return history-terminal]
               (mapv :phase events)))
        (is (= :mycelium.workflow/lifecycle
               (:operation lifecycle-invoke)))
        (is (= :mycelium.workflow/edge-decision (:operation edge-invoke)))
        (is (= (:operation-id lifecycle-invoke)
               (:parent-operation-id edge-invoke)))
        (is (= :request-17 (:context-id lifecycle-invoke)
               (:context-id edge-invoke)))
        (is (not= execution-id (:context-id lifecycle-invoke)))
        (is (= [] (:causal-links edge-invoke)))
        (is (= graph (get-in lifecycle-invoke [:input :graph])))
        (is (= {:execution-id execution-id
                :graph-id graph-id
                :edge-key [:start :done :finish]}
               (:input edge-invoke)))
        (is (= {:outcome :selected} (:value edge-return)))
        (is (= {:outcome outcome} (:value lifecycle-terminal)))
        (is (= events (model/check! events)))))))

(deftest exact-once-lifecycles-and-edge-membership-fail-closed
  (let [journal (history/journal)]
    (workflow! journal (workflow-event :invoke))
    (is (thrown-with-msg? Exception #"invalid Mycelium semantic event"
                          (workflow! journal (workflow-event :invoke))))
    (is (thrown-with-msg? Exception #"invalid Mycelium semantic event"
                          (edge! journal (edge-event [:start :secret :finish]))))
    (is (= (workflow-event :return)
           (workflow! journal (workflow-event :return))))
    (is (thrown-with-msg? Exception #"invalid Mycelium semantic event"
                          (workflow! journal (workflow-event :return))))
    (is (true? (history/assert-complete! journal)))
    (is (= (history/events journal)
           (model/check! (history/events journal))))))

(deftest lifecycle-identity-must-match-the-open-invocation
  (let [journal (history/journal)]
    (workflow! journal (workflow-event :invoke))
    (is (thrown? Exception
                 (workflow! journal
                            (assoc (workflow-event :return)
                                   :graph-id
                                   "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))))
    (is (thrown? Exception
                 (workflow! journal (assoc (workflow-event :return)
                                           :kind :ring))))
    (workflow! journal (workflow-event :return))))

(deftest interleaved-executions-do-not-cross-correlate
  (let [journal (history/journal)]
    (workflow! journal (workflow-event execution-id :invoke))
    (workflow! journal (workflow-event second-execution-id :invoke))
    (edge! journal (edge-event second-execution-id
                               [:finish :always :end]))
    (edge! journal (edge-event execution-id [:start :done :finish]))
    (workflow! journal (workflow-event execution-id :return))
    (workflow! journal (workflow-event second-execution-id :cancel))
    (let [events (history/events journal)
          invokes (filter #(= :invoke (:phase %)) events)
          lifecycles (into {}
                           (map (fn [event]
                                  [(get-in event [:input :execution-id])
                                   (:operation-id event)]))
                           (filter #(= :mycelium.workflow/lifecycle
                                       (:operation %))
                                   invokes))]
      (doseq [edge (filter #(= :mycelium.workflow/edge-decision
                                (:operation %))
                           invokes)]
        (is (= (get lifecycles (get-in edge [:input :execution-id]))
               (:parent-operation-id edge))))
      (is (= events (model/check! events)))
      (is (true? (history/assert-complete! journal))))))

(deftest lost-thread-bindings-recover-the-owning-journal
  (let [journal (history/journal)
        invoke (workflow-event :invoke)
        selected (edge-event [:start :done :finish])
        terminal (workflow-event :return)]
    (workflow! journal invoke)
    (is (identical?
         selected
         @(future
            (binding [history/*journal* nil history/*context-id* nil]
              (provider/around-edge-event edge-join-point [selected]
                                          #(identity selected))))))
    (is (identical?
         terminal
         @(future
            (binding [history/*journal* nil history/*context-id* nil]
              (provider/around-workflow-event workflow-join-point [terminal]
                                              #(identity terminal))))))
    (let [events (history/events journal)
          [lifecycle edge] (filter #(= :invoke (:phase %)) events)]
      (is (= (:operation-id lifecycle) (:parent-operation-id edge)))
      (is (= :request-17 (:context-id lifecycle) (:context-id edge)))
      (is (= events (model/check! events)))
      (is (true? (history/assert-complete! journal))))
    ;; The terminal removes registry ownership: without a journal, the same
    ;; marker is plain-build inert and cannot append another terminal.
    (let [before (history/events journal)]
      (is (identical?
           terminal
           (binding [history/*journal* nil history/*context-id* nil]
             (provider/around-workflow-event workflow-join-point [terminal]
                                             #(identity terminal)))))
      (is (= before (history/events journal))))
    ;; Cleanup also permits the globally unique id to be opened again.
    (let [next-journal (history/journal)]
      (workflow! next-journal invoke)
      (workflow! next-journal terminal)
      (is (true? (history/assert-complete! next-journal))))))

(deftest recovered-events-reject-a-conflicting-ambient-journal
  (let [owner (history/journal)
        other (history/journal)]
    (workflow! owner (workflow-event :invoke))
    (is (thrown-with-msg?
         Exception #"invalid Mycelium semantic event"
         (binding [history/*journal* other]
           (provider/around-edge-event
            edge-join-point [(edge-event [:start :done :finish])]
            #(edge-event [:start :done :finish])))))
    (binding [history/*journal* nil history/*context-id* nil]
      (provider/around-workflow-event
       workflow-join-point [(workflow-event :cancel)]
       #(workflow-event :cancel)))
    (is (true? (history/assert-complete! owner)))
    (is (empty? (history/events other)))))

(deftest event-shaping-excludes-workflow-data-resources-and-errors
  (let [journal (history/journal)
        invoke (workflow-event :invoke)
        observed (workflow! journal invoke)]
    (is (identical? invoke observed))
    (edge! journal (edge-event [:start :done :finish]))
    (workflow! journal (workflow-event :throw))
    (let [rendered (pr-str (history/events journal))]
      (doseq [secret ["private-input" "private-resource" "private-error"
                      "password" "token"]]
        (is (not (.contains rendered secret)))))
    (doseq [bad [(assoc (workflow-event :invoke) :data "private-input")
                 (assoc (workflow-event :return) :error "private-error")
                 (assoc (edge-event [:start :done :finish])
                        :predicate "private predicate")]]
      (is (thrown? Exception
                   (if (= :invoke (:phase bad))
                     (workflow! (history/journal) bad)
                     (if (contains? bad :edge-key)
                       (edge! (history/journal) bad)
                       (workflow! (history/journal) bad))))))))

(deftest graph-keywords-are-portably-bounded
  (let [oversized (keyword (apply str (repeat 256 "x")))
        oversized-graph (assoc-in graph [:nodes 0 :cell] oversized)]
    (is (thrown-with-msg?
         Exception #"invalid Mycelium semantic event"
         (workflow! (history/journal)
                    (assoc (workflow-event :invoke) :graph oversized-graph))))
    (let [journal (history/journal)]
      (workflow! journal (workflow-event :invoke))
      (is (thrown-with-msg?
           Exception #"invalid Mycelium semantic event"
           (edge! journal (edge-event [:start oversized :finish]))))
      (workflow! journal (workflow-event :return)))))

(deftest absent-journal-is-inert-and-does-not-validate-or-retain
  (let [private-event {:private "secret"}]
    (is (identical? private-event
                    (provider/around-workflow-event
                     workflow-join-point [private-event]
                     #(identity private-event))))
    (is (identical? private-event
                    (provider/around-edge-event
                     edge-join-point [private-event]
                     #(identity private-event))))))

(deftest model-rejects-broken-causal-relations-and-unknown-edges
  (let [events (complete-history
                :return
                [[:start :done :finish] [:finish :always :end]])
        invoke-indexes (keep-indexed
                        (fn [index event]
                          (when (= :invoke (:phase event)) index))
                        events)
        [_ first-edge-index second-edge-index] invoke-indexes]
    (is (= [] (get-in events [first-edge-index :causal-links])))
    (is (= [1] (get-in events [second-edge-index :causal-links])))
    (is (= events (model/check! events)))
    (doseq [broken [(update events first-edge-index dissoc :causal-links)
                    (assoc-in events [first-edge-index :causal-links] [99])
                    (assoc-in events [first-edge-index :causal-links] [2])
                    (assoc-in events [second-edge-index :causal-links] [0 0])
                    (assoc-in events [second-edge-index :causal-links] [1 0])
                    (assoc-in events [second-edge-index :causal-links] [0])
                    (assoc-in events [second-edge-index :causal-links] [0 1])
                    (assoc-in events [second-edge-index :context-id]
                              :wrong-request)
                    (assoc-in events [first-edge-index :input :edge-key]
                              [:start :unknown :finish])]]
      (is (thrown? Exception (model/check! broken))))))

(deftest generated-valid-histories-obey-the-model
  (hegel-test/with
    {:name "mycelium-history-conformance-v1"
     :test-cases 100
     :database ""
     :derandomize? true
     :verbosity :quiet}
    [edge-keys (g/recursive
                {:max-depth 24 :max-leaves 1}
                (g/sampled-from
                 [[[:start :done :finish]]
                  [[:start :done :finish]
                   [:finish :always :end]]])
                (fn [tail]
                  (g/fmap #(into [[:start :retry :start]] %)
                          tail)))
     terminal (g/sampled-from [:return :throw :cancel])]
    (let [events (complete-history terminal edge-keys)]
      (is (= events (model/check! events)))
      (is (not (.contains (pr-str events) "private"))))))

(deftest manifest-provider-and-exact-revisions-agree
  (let [manifest (edn/read-string
                  (slurp "resources/META-INF/jolt/aspects/packs/mycelium-dd13b4b.edn"))
        by-id (into {} (map (juxt :id identity)) (:aspects manifest))]
    (is (= "ff9cfd8cf6bf08c0f61b71cc98eee8c354efa861"
           provider/target-revision))
    (is (= provider/seam-revision (get-in manifest [:library :version])))
    (is (= 'yogthos/samizdat (get-in manifest [:library :id])))
    (is (= provider/seam-revision
           (get-in provider/aspect-provider [:libraries 'yogthos/samizdat])))
    (is (= {:entry 'mycelium.execution/workflow-event! :arity 1}
           (get-in by-id [:mycelium.workflow/lifecycle :match])))
    (is (= {:entry 'mycelium.execution/edge-event! :arity 1}
           (get-in by-id [:mycelium.workflow/edge-decision :match])))
    (is (= :mycelium/workflow
           (get-in by-id [:mycelium.workflow/lifecycle :advice-role])))
    (is (= :mycelium/edge-decision
           (get-in by-id [:mycelium.workflow/edge-decision :advice-role])))
    (is (= 1 (get-in by-id [:mycelium.workflow/lifecycle :expect :matches])))
    (is (= 1 (get-in by-id [:mycelium.workflow/edge-decision :expect :matches])))
    (is (= 'jolt.aspect-packs.mycelium.provider/around-workflow-event
           (get-in provider/aspect-provider
                   [:roles :mycelium/workflow :fn])))
    (is (= 'jolt.aspect-packs.mycelium.provider/around-edge-event
           (get-in provider/aspect-provider
                   [:roles :mycelium/edge-decision :fn])))))
