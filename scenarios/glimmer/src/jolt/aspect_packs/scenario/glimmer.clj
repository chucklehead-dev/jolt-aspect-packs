(ns jolt.aspect-packs.scenario.glimmer
  (:require [glimmer.backend :as backend]
            [glimmer.core :as glimmer]
            [jolt.aspect-packs.history :as history]))

(defn- widget [tag props]
  (atom {:tag tag :props props :children []}))

(def headless-backend
  {:name :aspect-pack-headless
   :create! widget
   :apply-props! (fn [_tag target props]
                   (swap! target assoc :props props))
   :append-child! (fn [_tag parent child]
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

(defn -main [& _]
  (backend/register! headless-backend)
  (let [container (widget :root {})
        journal (history/journal)
        mounted (binding [history/*journal* journal]
                  (glimmer/mount container :root
                                 [:label {:label "private scenario text"}]))
        events (history/events journal)]
    (when-not (= :native (:type @mounted))
      (throw (ex-info "headless Glimmer scenario did not mount" {})))
    (when-not (= [:enter :return] (mapv :phase events))
      (throw (ex-info "woven Glimmer advice did not close its history"
                      {:events events})))
    (when-not (= {:root-kind :native-element} (:input (first events)))
      (throw (ex-info "woven Glimmer advice exposed unexpected input"
                      {:events events}))))
  (println "Glimmer aspect scenario ran"))
