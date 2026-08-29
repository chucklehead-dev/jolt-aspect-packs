(ns jolt.aspect-packs.scenario.mycelium
  (:require [jolt.aspect-packs.history :as history]
            [mycelium.cell :as cell]
            [mycelium.core :as mycelium]))

(defmethod cell/cell-spec :aspect-scenario/start [_]
  {:id :aspect-scenario/start
   :handler (fn [_ data] (assoc data :attempt 1))
   :schema {:input :map :output :map}})

(defmethod cell/cell-spec :aspect-scenario/choose [_]
  {:id :aspect-scenario/choose
   :handler (fn [_ data] (assoc data :decision :done))
   :schema {:input :map :output :map}})

(defmethod cell/cell-spec :aspect-scenario/finish [_]
  {:id :aspect-scenario/finish
   :handler (fn [_ data] (assoc data :answer "private scenario result"))
   :schema {:input :map :output :map}})

(def workflow
  {:cells {:start :aspect-scenario/start
           :choose :aspect-scenario/choose
           :finish :aspect-scenario/finish}
   :edges {:start :choose
           :choose {:done :finish :retry :start}
           :finish :end}
   :dispatches {:choose [[:done (fn [data] (= :done (:decision data)))]
                         [:retry (constantly true)]]}})

(def expected-edges
  #{[:start :always :choose]
    [:choose :done :finish]
    [:finish :always :end]})

(defn- assert-woven-history! [journal]
  (let [events (history/events journal)
        invokes (filterv #(= :invoke (:phase %)) events)
        lifecycle (first (filter #(= :mycelium.workflow/lifecycle
                                     (:operation %)) invokes))
        edges (filterv #(= :mycelium.workflow/edge-decision
                           (:operation %)) invokes)]
    (history/assert-complete! journal)
    (when-not (= 8 (count events))
      (throw (ex-info "woven Mycelium history has the wrong event count"
                      {:events events})))
    (when-not (and (= :sync (get-in lifecycle [:input :kind]))
                   (string? (get-in lifecycle [:input :execution-id]))
                   (pos? (count (get-in lifecycle [:input :execution-id]))))
      (throw (ex-info "woven lifecycle input is incomplete"
                      {:lifecycle lifecycle})))
    (when-not (= (get-in lifecycle [:input :graph-id])
                 (get-in lifecycle [:input :graph :graph-id]))
      (throw (ex-info "woven lifecycle graph identity is inconsistent"
                      {:lifecycle lifecycle})))
    (when-not (= expected-edges (set (map #(get-in % [:input :edge-key]) edges)))
      (throw (ex-info "woven Mycelium history lost selected graph edges"
                      {:edges edges})))
    (when-not (every? #(= (:operation-id lifecycle)
                           (:parent-operation-id %)) edges)
      (throw (ex-info "woven edge histories are not children of the workflow"
                      {:lifecycle lifecycle :edges edges})))
    (when-not (every? #(= :mycelium-scenario (:context-id %)) invokes)
      (throw (ex-info "woven Mycelium history lost caller context"
                      {:invokes invokes})))
    (when-not (= [[]
                  [(:operation-id (nth edges 0))]
                  [(:operation-id (nth edges 1))]]
                 (mapv :causal-links edges))
      (throw (ex-info "woven Mycelium edges lost exact causal progression"
                      {:edges edges})))
    (when (.contains (pr-str events) "private")
      (throw (ex-info "woven Mycelium history retained workflow data" {})))))

(defn -main [& args]
  (let [plain? (= ["plain"] (vec args))
        compiled (mycelium/pre-compile workflow)
        journal (history/journal)
        result (binding [history/*journal* journal
                         history/*context-id* :mycelium-scenario]
                 (mycelium/run-compiled compiled {} {}))]
    (when-not (= {:attempt 1 :decision :done
                  :answer "private scenario result"}
                 (select-keys result [:attempt :decision :answer]))
      (throw (ex-info "Mycelium compiled scenario result changed"
                      {:result result})))
    (if plain?
      (when (seq (history/events journal))
        (throw (ex-info "plain Mycelium build ran aspect advice"
                        {:events (history/events journal)})))
      (assert-woven-history! journal)))
  (println (if (= ["plain"] (vec args))
             "Mycelium plain scenario remained uninstrumented"
             "Mycelium aspect scenario ran")))
