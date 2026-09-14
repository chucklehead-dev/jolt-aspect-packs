(ns jolt.aspect-packs.compatibility-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.aspect-packs.compatibility :as compatibility]))

(def universe-path "compatibility/known-universe.edn")
(def report-path "test/fixtures/compatibility/http-client-aspects.edn")

(defn fixture []
  {:universe (compatibility/read-edn universe-path)
   :report (compatibility/read-edn report-path)
   :observation
   {:pack-sha "30f5c94859321f3b3938437619a688766d0295d0"
    :target-sha "eab6b78d5957f88690faf6768360572a3f185341"
    :upstream-sha "b98833b8338b66d435cdbffa480ba2b59c005a2e"
    :compiler-sha "2d39e854a90926d8f8e9bd5d3ddbb109d657afe1"
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

(deftest stale-site-identity-cannot-pass
  (let [{:keys [universe report observation fixtures]} (fixture)
        report (assoc-in report [:aspects 0 :sites 0 :site-id] "stale-site")
        result (compatibility/check universe :otel/http-client observation
                                    report fixtures)]
    (is (= :incompatible (:status result)))
    (is (some #(= :report/site-id (:code %)) (:problems result)))))

(deftest stale-build-identity-cannot-pass
  (let [{:keys [universe report observation fixtures]} (fixture)
        report (assoc report :identity "stale-build")
        result (compatibility/check universe :otel/http-client observation
                                    report fixtures)]
    (is (= :incompatible (:status result)))
    (is (some #(= :report/build-identity (:code %)) (:problems result)))))

(deftest malformed-source-compatibility-id-is-rejected
  (let [{:keys [universe report observation fixtures]} (fixture)
        universe (assoc-in universe [:entries 0 :seam :id]
                           "invalid compatibility id")
        result (compatibility/check universe :otel/http-client observation
                                    report fixtures)]
    (is (= :incompatible (:status result)))
    (is (= :entry/seam-id (-> result :problems first :code)))))

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
