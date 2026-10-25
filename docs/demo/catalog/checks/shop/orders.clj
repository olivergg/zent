(ns shop.orders
  {:zent/observes [:orders-api :postgres]
   :zent/description "the seeded orders are served by orders-api"}
  (:require [clojure.test :refer [deftest is]]
            [demo.http :refer [status]]))

;; fails on purpose: the demo's fake orders-api only answers /health
(deftest ^{:doc "GET /orders lists the 2,000 seeded orders." :zent/kind :pseudo-oracle}
  orders-listed
  (is (= 200 (status 7104 "/orders")) "GET /orders"))

(deftest ^{:doc "An unknown order is a 404, not a 500." :zent/kind :heuristic}
  unknown-order-404
  (is (= 404 (status 7104 "/orders/0"))))
