(ns jolt.aspect-packs.compatibility-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.aspect-packs.compatibility :as compatibility]))

(def universe-path "compatibility/known-universe.edn")
(def report-path "test/fixtures/compatibility/http-client-aspects.edn")

(defn fixture []
  {:universe (compatibility/read-edn universe-path)
   :report (compatibility/read-edn report-path)
   :observation
   {:pack-sha "40679054dc4786e43577cb9788f69db0f9a2a401"
    :target-sha "a00979f5e55bc8deb98291993643ff1ccf50a57b"
    :upstream-sha "04825632ed96a77a1c6ba1921c0a31280a3daade"
    :compiler-sha "c666a2d0175923cb7edeb36fb99c7e2c657af375"
    :pack-clean? true
    :target-clean? true
    :compiler-clean? true}
   :fixtures
   {:woven {:exit 0
            :stdout-lines
            ["OK: woven HTTP client span, metric, and tracecontext propagation"]}
    :plain {:exit 0
            :stdout-lines ["OK: plain build remains uninstrumented"]}}})

(deftest exact-known-universe-entry-is-compatible
  (let [{:keys [universe report observation fixtures]} (fixture)
        result (compatibility/check universe :otel/http-client
                                    observation report fixtures)]
    (is (= :compatible (:status result)))
    (is (= {:aspects 1 :sites 1} (:report result)))
    (is (= :not-consulted (:clock result)))))

(deftest known-bad-pack-pin-is-rejected
  (let [{:keys [universe report observation fixtures]} (fixture)
        result (compatibility/check
                universe :otel/http-client
                (assoc observation :pack-sha
                       "0000000000000000000000000000000000000000")
                report fixtures)]
    (is (= :incompatible (:status result)))
    (is (= [:observation/pack-sha]
           (mapv :code (:problems result))))))

(deftest empty-report-cannot-pass-vacuously
  (let [{:keys [universe report observation fixtures]} (fixture)
        result (compatibility/check universe :otel/http-client observation
                                    (assoc report :aspects []) fixtures)
        codes (set (map :code (:problems result)))]
    (testing "the exact aspect and exact site cardinality are both required"
      (is (= :incompatible (:status result)))
      (is (contains? codes :report/aspects))
      (is (contains? codes :report/sites)))))

(deftest zero-site-report-cannot-pass-vacuously
  (let [{:keys [universe report observation fixtures]} (fixture)
        report (assoc-in report [:aspects 0 :sites] [])
        result (compatibility/check universe :otel/http-client observation
                                    report fixtures)]
    (is (= :incompatible (:status result)))
    (is (some #(= :report/sites (:code %)) (:problems result)))))

(deftest compiled-plain-woven-differential-is-required
  (let [{:keys [universe report observation fixtures]} (fixture)
        good fixtures
        compatible (compatibility/check universe :otel/http-client observation
                                         report good)
        known-bad (compatibility/check universe :otel/http-client observation
                                        report (assoc good :plain (:woven good)))]
    (is (= :compatible (:status compatible)))
    (is (= {:woven 0 :plain 0} (:fixtures compatible)))
    (is (= :incompatible (:status known-bad)))
    (is (some #(= :fixture/plain-stdout (:code %))
              (:problems known-bad)))))
