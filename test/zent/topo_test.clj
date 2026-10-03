(ns zent.topo-test
  (:require [clojure.test :refer [deftest is testing]]
            [zent.topo :as topo]))

(deftest topo-sort-simple-order-test
  (testing "a component comes after everything in its :deps"
    (let [components {:broker-seed {:kind :one-shot}
                       :notifier {:kind :quarkus-app :deps [:broker-seed]}
                       :once-relay {:kind :quarkus-app :deps [:broker-seed :notifier]}}
          order (mapv first (topo/topo-sort components))
          idx (zipmap order (range))]
      (is (= #{:broker-seed :notifier :once-relay} (set order)))
      (is (< (idx :broker-seed) (idx :notifier)))
      (is (< (idx :notifier) (idx :once-relay))))))

(deftest topo-sort-ignores-out-of-scope-deps-test
  (testing "a :deps entry not present in the components map is silently ignored"
    (let [components {:notifier {:kind :quarkus-app :deps [:broker-seed :not-in-scope]}}
          result (topo/topo-sort components)]
      (is (= [[:notifier (get components :notifier)]] result)))))

(deftest topo-sort-cycle-detected-test
  (testing "a dependency cycle raises ex-info naming the involved keywords"
    (let [components {:a {:deps [:b]}
                       :b {:deps [:a]}}]
      (try
        (topo/topo-sort components)
        (is false "expected topo-sort to throw on a cycle")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::topo/cycle (:type (ex-data e))))
          (is (= #{:a :b} (set (:cycle (ex-data e))))))))))

(deftest topo-sort-cfg-preserved-test
  (testing "returned pairs carry the original cfg, not just the key"
    (let [components {:broker {:kind :docker-compose :repo "broker-stack"}}]
      (is (= [[:broker {:kind :docker-compose :repo "broker-stack"}]]
             (topo/topo-sort components))))))
