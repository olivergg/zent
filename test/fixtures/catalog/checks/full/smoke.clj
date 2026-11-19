(ns full.smoke
  "A check of the fixture preset :full - zent.checks-test runs it for real
  when ZENT_TEST_CLOJURE is set."
  {:zent/observes [:db :api] :zent/title "fixture check"}
  (:require [clojure.test :refer [deftest is testing]]
            [fixture.helpers :as h]))

(deftest ^{:doc "One equals one." :zent/oracle :invariant} passes
  (is (= (h/one) 1)) (is true)
  (clojure.test/do-report {:type :zent/note :note "2 assertions"}))

(deftest ^{:doc "One equals two,\n  on two lines."} fails (testing "ctx" (is (= 1 2) [:label "x"])))

(deftest throws (throw (ex-info "kaput" {})))

(deftest ^{:zent/title "Totals kept"} described
  (clojure.test/do-report {:type :zent/verdict :verdict "1 of 2 lost"})
  (clojure.test/do-report {:type :zent/fact :label "compared" :value 2})
  (clojure.test/do-report {:type :fail :expected "000764" :actual "0000000000076405" :zent/group "Total" :zent/context {:order 7}}))

(deftest known (is true) (clojure.test/do-report {:type :zent/accepted :reason "ticket 42"}))
