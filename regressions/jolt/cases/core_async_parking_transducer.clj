;; regression-id: issue-21
(require '[clojure.core.async :as a]
         '[jolt.host :as host])

(def pass-signature "REGRESSION PASS issue-21")
(def fail-signature "REGRESSION FAIL issue-21")
(def timed-out ::timed-out)

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

(defn await [value timeout-ms]
  (deref value timeout-ms timed-out))

(defn take-bounded [ch]
  (let [[value port] (a/alts!! [ch (a/timeout 2000)] :priority true)]
    (if (= port ch) value timed-out)))

;; A single carrier makes the sibling result meaningful: the reducer must park
;; the fiber, not its carrier. Registering B on the same channel while A is
;; parked additionally proves that A is not holding the channel mutex.
(alter-var-root #'a/*fiber-carrier-count* (constantly 1))

(let [events (atom [])
      entered (promise)
      release (promise)
      b-ack (promise)
      b-ack-count (atom 0)
      xf (fn [rf]
           (fn
             ([] (rf))
             ([result] result)
             ([result input]
              (swap! events conj [:step-start input (host/fiber?)])
              (when (= input :A)
                (deliver entered true)
                (when (= timed-out (await release 5000))
                  (swap! events conj [:harness-timeout])))
              (swap! events conj [:step-end input (host/fiber?)])
              (rf result input))))
      ch (a/chan 1 xf)
      writer (binding [a/*go-backend* :fiber]
               (a/go (a/>! ch :A)))
      entered-result (await entered 1000)
      sibling (binding [a/*go-backend* :fiber]
                (a/go
                  (swap! events conj [:sibling])
                  :sibling))
      registrar (future
                  (let [registered
                        (a/put! ch :B
                                #(do (swap! b-ack-count inc)
                                     (swap! events conj [:ack :B %])
                                     (deliver b-ack %)))]
                    (swap! events conj [:registered :B registered])
                    registered))
      sibling-before-release (take-bounded sibling)
      registered-before-release (await registrar 2000)
      ack-before-release (await b-ack 100)]
  ;; Always release the parked reducer before checking the property so a bad
  ;; runtime reports a bounded failure instead of relying on the outer timeout.
  (deliver release true)
  (let [writer-result (take-bounded writer)
        _ (a/close! ch)
        values [(take-bounded ch) (take-bounded ch) (take-bounded ch)]
        ack-after-release (await b-ack 2000)
        history @events
        step-events (filterv #(contains? #{:step-start :step-end} (first %))
                             history)
        ack-index (first (keep-indexed #(when (= [:ack :B true] %2) %1)
                                       history))
        b-end-index (first (keep-indexed
                            #(when (= [:step-end :B false] %2) %1)
                            history))
        actual {:entered entered-result
                :sibling-before-release sibling-before-release
                :registered-before-release registered-before-release
                :ack-before-release ack-before-release
                :writer writer-result
                :ack-after-release ack-after-release
                :ack-count @b-ack-count
                :values values
                :steps step-events
                :ack-after-b-commit? (and (some? ack-index)
                                          (some? b-end-index)
                                          (< b-end-index ack-index))
                :harness-timeout? (boolean (some #{[:harness-timeout]} history))}
        expected {:entered true
                  :sibling-before-release :sibling
                  :registered-before-release true
                  :ack-before-release timed-out
                  :writer true
                  :ack-after-release true
                  :ack-count 1
                  :values [:A :B nil]
                  :steps [[:step-start :A true]
                          [:step-end :A true]
                          [:step-start :B false]
                          [:step-end :B false]]
                  :ack-after-b-commit? true
                  :harness-timeout? false}]
    (finish! (= expected actual)
             (pr-str {:expected expected :actual actual :history history}))))
