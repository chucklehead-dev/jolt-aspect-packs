(ns jolt.pending.interop-microbench
  (:require [clojure.data.json :as json]))

;; Characterize the operations around a counted string scan without assigning
;; their combined cost to loop/recur alone. The controls separate a global Var
;; read, lexical/function-argument bounds, = versus ==, and compiler-proven long
;; arithmetic. Results are characterization, not pass/fail thresholds.

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
(def global-limit (.length subject))
(def absent (str (char 1)))
(def control-class
  (re-pattern (str "[" (char 0) "-" (char 7) (char 11)
                   (char 14) "-" (char 31) "]")))

(defn- global-bound-loop []
  (loop [i 0]
    (if (== i global-limit) i (recur (unchecked-inc i)))))

(defn- lexical-bound-loop []
  (let [limit global-limit]
    (loop [i 0]
      (if (== i limit) i (recur (unchecked-inc i))))))

(defn- argument-bound-== [limit]
  (loop [i 0]
    (if (== i limit) i (recur (unchecked-inc i)))))

(defn- argument-bound-= [limit]
  (loop [i 0]
    (if (= i limit) i (recur (unchecked-inc i)))))

(defn- proven-bound-unchecked [^long limit]
  (loop [i (unchecked-long 0)]
    (if (== i limit) i (recur (unchecked-inc i)))))

(defn- proven-bound-inc [^long limit]
  (loop [i 0]
    (if (== i limit) i (recur (inc i)))))

(defn- median [xs]
  (nth (vec (sort xs)) (quot (count xs) 2)))

(defn- sample-ms [reps thunk expected]
  (let [start (System/nanoTime)
        result (loop [remaining reps
                      result nil]
                 (if (zero? remaining)
                   result
                   (recur (dec remaining) (thunk))))]
    (when-not (= expected result)
      (throw (ex-info "benchmark result changed" {:expected expected :actual result})))
    (/ (double (- (System/nanoTime) start)) 1e6 reps)))

(defn- bench [label reps thunk]
  (let [expected (thunk)]
    (dotimes [_ 3] (thunk))
    (let [samples (mapv (fn [_] (sample-ms reps thunk expected)) (range 5))
          ms (median samples)]
      (println (format "  %-48s %8.3f ms  %9.1f M units/s"
                       label ms (/ global-limit ms 1000.0)))
      (println "    samples-ms:" (pr-str samples)))))

(defn -main [& args]
  (println "characterization-mode:" (or (first args) "unspecified"))
  (println (format "payload: %d codepoints" global-limit))
  (bench "global bound, ==, unchecked-inc" 20 global-bound-loop)
  (bench "lexical bound, ==, unchecked-inc" 20 lexical-bound-loop)
  (bench "argument bound, ==, unchecked-inc" 20
         #(argument-bound-== global-limit))
  (bench "argument bound, =, unchecked-inc" 20
         #(argument-bound-= global-limit))
  (bench "proven ^long bound, ==, unchecked-inc" 20
         #(proven-bound-unchecked global-limit))
  (bench "proven ^long bound, ==, inc" 20
         #(proven-bound-inc global-limit))
  (bench "loop + .charAt" 20
         #(loop [i 0 acc 0]
            (if (== i global-limit)
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
  (bench "data.json write-str, one big string" 5
         #(json/write-str {"sql" subject})))
