;; Measures the interop primitives that decide whether a text-processing
;; algorithm is viable on Jolt. No dependencies: it builds its own payload, a
;; JSONEachRow-shaped body of the kind a telemetry exporter hands to a database
;; driver, and reports milliseconds for one pass over it.
;;
;; The result that matters is the ratio between the first two rows. When a bare
;; loop costs more per iteration than a native scan costs per character, no
;; character-at-a-time algorithm can win, and every string routine has to be
;; expressed as a chain of native String calls instead.

(defn- payload []
  (apply str
         "insert into otel_logs (Timestamp, TraceId, Body, ServiceName)"
         " FORMAT JSONEachRow\n"
         (map (fn [i]
                (format (str "{\"Timestamp\":\"2026-08-27 01:02:03.000000000\","
                             "\"TraceId\":\"%032x\","
                             "\"Body\":\"request %d completed for tenant %d\","
                             "\"ServiceName\":\"checkout-%d\"}\n")
                        i i (mod i 97) (mod i 8)))
              (range 512))))

(def ^String subject (payload))
(def n (.length subject))
(def absent (str (char 1)))
(def control-class
  (re-pattern (str "[" (char 0) "-" (char 7) (char 11) (char 14) "-" (char 31) "]")))

(defn- bench [label reps thunk]
  (dotimes [_ 2] (thunk))
  (let [start (System/nanoTime)]
    (dotimes [_ reps] (thunk))
    (let [ms (/ (double (- (System/nanoTime) start)) 1e6 reps)]
      (println (format "  %-44s %8.3f ms  %9.1f M chars/s"
                       label ms (/ n ms 1000.0))))))

(println (format "payload: %d chars" n))
(bench "bare loop, no body" 20 #(loop [i 0] (if (== i n) i (recur (unchecked-inc i)))))
(bench "loop + .charAt" 20 #(loop [i 0 acc 0]
                              (if (== i n)
                                acc
                                (recur (unchecked-inc i)
                                       (unchecked-add acc (int (.charAt subject i)))))))
(bench ".indexOf char, absent" 20 #(.indexOf subject (int 1)))
(bench ".indexOf string, absent" 20 #(.indexOf subject absent))
(bench ".replace, absent needle" 10 #(.replace subject absent "x"))
(bench ".replace, 15360 hits" 10 #(.replace subject "\"" "\\\""))
(bench ".getBytes UTF-8" 20 #(alength (.getBytes subject "UTF-8")))
(bench ".toCharArray" 20 #(alength (.toCharArray subject)))
(bench "re-find, 3-range char class" 10 #(re-find control-class subject))
(when-let [write-str (try (require 'clojure.data.json)
                               (resolve 'clojure.data.json/write-str)
                               (catch Throwable _ nil))]
  (bench "data.json write-str, one big string" 5 #(write-str {"sql" subject})))
