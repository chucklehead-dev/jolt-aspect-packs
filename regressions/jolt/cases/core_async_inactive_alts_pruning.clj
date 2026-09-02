;; regression-id: issue-12
(require '[clojure.core.async :as async]
         '[jolt.scheme :as scheme])

(def pass-signature "REGRESSION PASS issue-12")
(def fail-signature "REGRESSION FAIL issue-12")

(defn finish! [ok details]
  (if ok
    (println pass-signature)
    (do
      (binding [*out* *err*]
        (println (str fail-signature ": " details)))
      (System/exit 1))))

;; This is the narrow native boundary from Jolt's own regression. The helper
;; installs one already-lost alts put registration on a full transformed
;; channel, then runs the notification fixed point while owning its mutex.
(scheme/eval-string
 "(begin
    (define (jolt-regression-issue12-install-stale! ch)
      (let ((h (alt-handler-alloc)))
        (alt-claim! h)
        (async-chan-alt-putters-set! ch (list (cons h 'stale)))
        (length (async-chan-alt-putters ch))))
    (define (jolt-regression-issue12-notify! ch)
      (jolt-with-mutex (async-chan-mu ch)
        (ac-notify! ch)
        (length (async-chan-alt-putters ch)))))")

(let [events (atom [])
      xform (fn [rf]
              (fn
                ([] (rf))
                ([result]
                 (swap! events conj :complete)
                 (rf result))
                ([result input]
                 (swap! events conj [:step input])
                 (rf result input))))
      channel (async/chan 1 xform)
      first-put (async/>!! channel :buffered)
      before (scheme/call "jolt-regression-issue12-install-stale!" channel)
      after-notify (scheme/call "jolt-regression-issue12-notify!" channel)
      _ (async/close! channel)
      after-close (scheme/call "length"
                               (scheme/call "async-chan-alt-putters" channel))
      actual {:first-put first-put
              :registrations-before before
              :registrations-after-notify after-notify
              :registrations-after-close after-close
              :events @events}
      expected {:first-put true
                :registrations-before 1
                :registrations-after-notify 0
                :registrations-after-close 0
                :events [[:step :buffered] :complete]}]
  (finish! (= expected actual)
           (pr-str {:expected expected :actual actual})))
