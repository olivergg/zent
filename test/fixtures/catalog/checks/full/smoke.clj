(ns full.smoke
  "A check of the fixture preset :full - zent.checks-test runs it for real
  when ZENT_TEST_CLOJURE is set."
  {:zent/observes [:db :api] :zent/description "fixture check"}
  (:require [clojure.test :refer [deftest is testing]]
            [fixture.helpers :as h]))

(deftest ^{:doc "One equals one." :zent/kind :invariant} passes
  (is (= (h/one) 1)) (is true)
  (clojure.test/do-report {:type :zent/note :note "2 assertions"}))

(deftest ^{:doc "One equals two,\n  on two lines."} fails (testing "ctx" (is (= 1 2) [:label "x"])))

(deftest throws (throw (ex-info "kaput" {})))
