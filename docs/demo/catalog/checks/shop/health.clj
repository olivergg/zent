(ns shop.health
  {:zent/observes [:catalog-api :orders-api :storefront]
   :zent/description "every service of the shop answers its health check"}
  (:require [clojure.test :refer [deftest is]]
            [demo.http :refer [status]]))

(deftest ^{:doc "catalog-api, orders-api and storefront answer 200 on /health." :zent/kind :invariant}
  services-healthy
  (doseq [port [7103 7104 7107]] (is (= 200 (status port "/health")) (str ":" port))))

(deftest ^{:doc "Asking twice gives the same answer." :zent/kind :metamorphic}
  health-stable
  (is (= (status 7104 "/health") (status 7104 "/health"))))
