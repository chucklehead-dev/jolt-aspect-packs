(ns jolt.aspect-packs.db.corpus-live-test
  (:require [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is]]
            [hegel.core :as h]
            [hegel.corpus :as corpus]
            [hegel.generator :as g]
            [hegel.host :as host]
            [jolt.aspect-packs.db.corpus-consumer :as consumer]
            [jolt.aspect-packs.db.corpus-generator :as generator]
            [jolt.aspect-packs.db.corpus-generator-test]
            [jolt.aspect-packs.db.corpus-live :as live]))

(def ^:dynamic *producer-version* nil)

(defn- checked-producer-version [version]
  (when (and (= :jvm (host/runtime)) (not= version (clojure-version)))
    (throw (ex-info "producer JVM Clojure version does not match selected runtime"
                    {:type ::runtime-version-mismatch
                     :expected version :actual (clojure-version)})))
  version)

(deftest jvm-producer-version-must-match-the-running-runtime
  (with-redefs [host/runtime (constantly :jvm)]
    (is (= (clojure-version) (checked-producer-version (clojure-version))))
    (is (= ::runtime-version-mismatch
           (:type (try (checked-producer-version "deliberately-wrong")
                       nil
                       (catch clojure.lang.ExceptionInfo error (ex-data error))))))))

(defn- options []
  (-> (edn/read-string (slurp "corpus-fixtures/db-generation.edn"))
      (assoc-in [:provenance :runtime]
                {:host (host/runtime) :version *producer-version*
                 :os (System/getProperty "os.name")
                 :arch (System/getProperty "os.arch")})))

(defn- result-values [opts envelope]
  ;; Test-only candidate acceptance: producer configuration is known here.
  ;; The baked consumer instead loads its independently reviewed static pin.
  (:values (consumer/consume! {:sha256 (:sha256 envelope)
                              :provenance (:provenance opts)
                              :count (:count opts)
                              :valid-case-policy :exact-valid-count}
                             envelope)))

(defn- failure [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo error error)))

(deftest live-materialization-repeats-and-wraps-with-the-same-model
  (let [opts (options)
        first-run (live/generate! opts)
        second-run (live/generate! opts)
        values (result-values opts first-run)
        zero-opts (assoc opts :seed "0" :count 1)
        zero-values (result-values zero-opts (live/generate! zero-opts))]
    (is (= first-run second-run))
    (is (= 4 (count values)))
    (is (> (count (distinct values)) 1))
    (is (= (second values) (first zero-values)))
    (is (= first-run (corpus/decode (corpus/encode first-run))))))

(deftest equal-candidates-and-rejections-preserve-exact-count
  (let [opts (assoc (options) :count 3)
        value (generator/witness 0 1 "sqlite" "INSERT")]
    (with-redefs [generator/generator (fn [] (g/just value))]
      (is (= [value value value] (result-values opts (live/generate! opts)))))
    (let [attempts (atom 0)]
      (with-redefs [generator/generator
                    (fn [] (g/composite-fn
                            (fn [_]
                              ;; Give the engine another choice to explore;
                              ;; rejecting a choice-free constant exhausts it.
                              (h/draw! (g/integer 0 100))
                              (h/assume! (even? (swap! attempts inc)))
                              value)))]
        (is (= [value value value] (result-values opts (live/generate! opts))))
        (is (= 6 @attempts))))))

(deftest exhausted-choice-free-rejection-is-not-padded
  (with-redefs [generator/generator
                (fn [] (g/composite-fn (fn [_] (h/assume! false))))]
    (let [data (ex-data (failure #(live/generate! (options))))]
      (is (= :hegel.materialize/materialization-failed (:type data)))
      (is (zero? (:valid-test-cases data)))
      (is (zero? (:callback-count data))))))

(deftest model-failure-and-flaky-candidates-do-not-publish
  (let [opts (assoc (options) :count 1)
        good (generator/witness 0 1 "sqlite" "INSERT")
        bad (assoc-in good [2 :value :row-count] -1)]
    (with-redefs [generator/generator (fn [] (g/just bad))]
      (is (= :hegel.materialize/materialization-failed
             (:type (ex-data (failure #(live/generate! opts)))))))
    (let [attempts (atom 0)]
      (with-redefs [generator/generator
                    (fn [] (g/composite-fn
                            (fn [_] (if (odd? (swap! attempts inc)) bad good))))]
        (let [data (ex-data (failure #(live/generate! opts)))]
          (is (= :hegel.materialize/materialization-failed (:type data)))
          (is (true? (:flaky? data))))))))

(defn -main [& args]
  (when-not (and (= 1 (count args)) (seq (first args)))
    (throw (ex-info "supply the selected producer runtime version" {})))
  (binding [*producer-version* (checked-producer-version (first args))]
    (let [{:keys [fail error]} (t/run-tests
                              'jolt.aspect-packs.db.corpus-generator-test
                              'jolt.aspect-packs.db.corpus-live-test)]
      (when (pos? (+ fail error))
        (throw (ex-info "db corpus live controls failed" {:fail fail :error error}))))))
