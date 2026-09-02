;; regression-id: issue-13
(require '[clojure.core.async :as async]
         '[jolt.scheme :as scheme])

(def pass-signature "REGRESSION PASS issue-13")
(def fail-signature "REGRESSION FAIL issue-13")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

;; Read both native registration queues while owning the same mutex as
;; ac-notify!. The decimal encoding keeps the raw Scheme value crossing to a
;; number: putters * 10 + takers.
(scheme/eval-string
 "(define (jolt-regression-issue13-registration-code ch)
    (jolt-with-mutex (async-chan-mu ch)
      (+ (* 10 (length (async-chan-alt-putters ch)))
         (length (async-chan-alt-takers ch)))))")

(defn result-view [result channel]
  (if (vector? result)
    [(first result) (= channel (second result))]
    result))

(let [channel (async/chan)
      mixed (future (async/__do-alts [[channel :self] channel] true))
      registration
      (loop [remaining 100]
        (let [code (scheme/call
                    "jolt-regression-issue13-registration-code" channel)
              result (deref mixed 0 :pending)]
          (cond
            (= 11 code) {:registered? true :code code}
            (not= :pending result) {:registered? false
                                    :code code
                                    :early-result result}
            (zero? remaining) {:registered? false :code code}
            :else (do (Thread/sleep 5) (recur (dec remaining))))))
      external (when (:registered? registration)
                 (future (async/__do-alts [[channel :external]] false)))
      mixed-result (deref mixed 1000 :timed-out)
      external-result (if external
                        (deref external 1000 :timed-out)
                        :not-started)
      after-code (scheme/call
                  "jolt-regression-issue13-registration-code" channel)
      _ (async/close! channel)
      actual {:registered? (:registered? registration)
              :registration-code (:code registration)
              :early-result (result-view (:early-result registration) channel)
              :mixed (result-view mixed-result channel)
              :external (result-view external-result channel)
              :registration-code-after after-code}
      expected {:registered? true
                :registration-code 11
                :early-result nil
                :mixed [:external true]
                :external [true true]
                :registration-code-after 0}]
  (finish! (= expected actual)
           (pr-str {:expected expected :actual actual})))
