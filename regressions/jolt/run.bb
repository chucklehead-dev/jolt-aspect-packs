#!/usr/bin/env bb
(ns jolt-regression-matrix.runner
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def repo-root
  (-> *file* fs/parent fs/parent fs/parent fs/canonicalize))

(defn die! [message]
  (binding [*out* *err*]
    (println (str "jolt regression matrix: " message)))
  (System/exit 2))

(defn executable! [label value]
  (when (str/blank? value)
    (die! (str label " must name an explicit absolute executable")))
  (let [path (fs/path value)]
    (when-not (fs/absolute? path)
      (die! (str label " must be absolute: " value)))
    (when-not (and (fs/regular-file? path) (fs/executable? path))
      (die! (str label " is not an executable regular file: " value)))
    (str (fs/canonicalize path))))

(defn bounded-text [s]
  (let [limit 16384]
    (if (> (count s) limit)
      (str (subs s 0 limit) "\n... output truncated by matrix runner ...")
      s)))

(defn run-case! [binary variant case]
  (let [script (fs/canonicalize (fs/path repo-root (:script case)))
        _ (when-not (fs/regular-file? script)
            (die! (str "missing case script for " (:id case) ": " script)))
        run-root (fs/create-temp-dir {:prefix "jolt-regression-matrix-"})
        dirs (into {}
                   (for [name ["home" "tmp" "cache" "build" "config" "data"]
                         :let [path (fs/path run-root name)]]
                     (do (fs/create-dirs path) [name (str path)])))
        timeout-ms (:timeout-ms case)
        timeout-seconds (format "%.3fs" (/ timeout-ms 1000.0))
        command ["/usr/bin/timeout" "--signal=TERM" "--kill-after=2s"
                 timeout-seconds binary "-Srepro" (str script)]
        env {"HOME" (dirs "home")
             "TMPDIR" (dirs "tmp")
             "TMP" (dirs "tmp")
             "TEMP" (dirs "tmp")
             "XDG_CACHE_HOME" (dirs "cache")
             "XDG_CONFIG_HOME" (dirs "config")
             "XDG_DATA_HOME" (dirs "data")
             "JOLT_CACHE_DIR" (str (fs/path (dirs "cache") "jolt"))
             "JOLT_GATEBOOT_BUILD_DIR" (dirs "build")
             "REGRESSION_CASE_DIR" (str run-root)}]
    (try
      (let [{:keys [exit out err]}
            @(process/process command {:dir (str run-root)
                                       :extra-env env
                                       :out :string
                                       :err :string})
            out (bounded-text out)
            err (bounded-text err)
            combined (str out "\n" err)
            pass? (str/includes? combined (get-in case [:expected :pass]))
            fail? (str/includes? combined (get-in case [:expected :fail]))
            stderr-marker (get-in case [:expected :stderr-contains])
            stderr-matched? (or (nil? stderr-marker)
                                (str/includes? err stderr-marker))
            timed-out? (contains? #{124 137} exit)
            status
            (cond
              timed-out? :error
              (and (= variant :unfixed) (zero? exit) pass? (not fail?)
                   stderr-matched?) :xpass
              (and (= variant :unfixed) (not (zero? exit)) fail? (not pass?)) :fail
              (and (= variant :fixed) (zero? exit) pass? (not fail?)
                   stderr-matched?) :pass
              (and (= variant :fixed) (not (zero? exit)) fail? (not pass?)) :fail
              :else :error)]
        {:variant variant
         :status status
         :exit exit
         :timed-out? timed-out?
         :stderr-required? (some? stderr-marker)
         :stderr-matched? stderr-matched?
         :stdout out
         :stderr err})
      (catch Throwable error
        {:variant variant
         :status :error
         :exit nil
         :timed-out? false
         :stdout ""
         :stderr (str (.getName (class error)) ": " (ex-message error))})
      (finally
        (fs/delete-tree run-root)))))

(defn valid-case! [case]
  (doseq [key [:id :script :issue :bad-sha :fixed-sha :timeout-ms
               :expected :provenance]]
    (when-not (contains? case key)
      (die! (str "case missing " key ": " (pr-str (:id case))))))
  (let [expected (:expected case)
        expected-keys (when (map? expected) (set (keys expected)))]
    (when-not (and (pos-int? (:timeout-ms case))
                   (contains? #{#{:pass :fail}
                                #{:pass :fail :stderr-contains}}
                              expected-keys)
                   (string? (:pass expected))
                   (not (str/blank? (:pass expected)))
                   (string? (:fail expected))
                   (not (str/blank? (:fail expected)))
                   (or (not (contains? expected :stderr-contains))
                       (and (string? (:stderr-contains expected))
                            (not (str/blank?
                                  (:stderr-contains expected))))))
      (die! (str "invalid timeout/signatures for " (:id case)))))
  case)

(let [catalog-path (fs/path repo-root "regressions/jolt/cases.edn")
      catalog (edn/read-string (slurp (str catalog-path)))
      _ (when-not (= 1 (:schema catalog))
          (die! (str "unsupported catalog schema " (:schema catalog))))
      cases (mapv valid-case! (:cases catalog))
      _ (when-not (= (count cases) (count (set (map :id cases))))
          (die! "duplicate case ids in catalog"))
      unfixed (executable! "JOLT_UNFIXED" (System/getenv "JOLT_UNFIXED"))
      fixed (executable! "JOLT_FIXED" (System/getenv "JOLT_FIXED"))
      _ (when (= unfixed fixed)
          (die! "JOLT_UNFIXED and JOLT_FIXED resolve to the same executable"))
      matrix
      (mapv (fn [case]
              {:case (select-keys case
                                  [:id :issue :bad-sha :fixed-sha :timeout-ms
                                   :provenance])
               :unfixed (run-case! unfixed :unfixed case)
               :fixed (run-case! fixed :fixed case)})
            cases)
      results (mapcat (juxt :unfixed :fixed) matrix)
      frequencies (frequencies (map :status results))
      ok? (every? (fn [{:keys [unfixed fixed]}]
                    (and (contains? #{:fail :xpass} (:status unfixed))
                         (= :pass (:status fixed))))
                  matrix)
      report {:schema 1
              :ok? ok?
              :binaries {:unfixed unfixed :fixed fixed}
              :summary {:pass (get frequencies :pass 0)
                        :fail (get frequencies :fail 0)
                        :xpass (get frequencies :xpass 0)
                        :error (get frequencies :error 0)}
              :matrix matrix}]
  (prn report)
  (shutdown-agents)
  (System/exit (if ok? 0 1)))
