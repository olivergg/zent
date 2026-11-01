(ns zent.ui.state-test
  (:require [clojure.test :refer [deftest is testing]]
            [zent.ui.state :as state]))

(defn- step [s at components] (state/record-transitions at s (assoc s :components components)))

(deftest set-preset-members-test
  (testing "the view keeps an earlier preset's components, :members names this one's own"
    (let [s (-> state/empty-state
                (state/set-preset :a {:db {:kind :process :mode :on} :api {:kind :process :mode :on}})
                (state/set-preset :b {:db {:kind :process :mode :on}}))]
      (is (= #{:db :api} (set (keys (:components s)))))
      (is (= [:db] (:members s))))))

(deftest shown-components-test
  (let [comps {:db {:status :up} :api {:status :down} :old {:status :down} :warm {:status :idle}}]
    (testing "the preset's own, stopped or not, plus what still runs outside it"
      (is (= #{:db :api :warm} (set (keys (state/shown-components {:components comps :members [:db :api]}))))))
    (testing "no :members yet (a code reload): the graph's ends"
      (is (= #{:db :api :warm} (set (keys (state/shown-components {:components comps :graph [[:api :db]]}))))))
    (testing "neither: everything"
      (is (= comps (state/shown-components {:components comps}))))))

(deftest record-transitions-clock-test
  (let [s0 (step {} 1000 {:api {:status :pending}})
        s1 (step s0 3000 {:api {:status :deploying}})
        s2 (step s1 3500 {:api {:status :deploying}})
        s3 (step s2 45000 {:api {:status :up}})
        s4 (step s3 60000 {:api {:status :up}})
        s5 (step s4 70000 {:api {:status :deploying}})]
    (testing "a component is stamped when it enters a status, kept while it stays"
      (is (= 1000 (get-in s0 [:components :api :since])))
      (is (= 3000 (get-in s2 [:components :api :since]))))
    (testing "leaving :deploying keeps how long the start took, until the next start"
      (is (= 42000 (get-in s3 [:components :api :took])))
      (is (= {:status :up :since 45000 :took 42000} (get-in s4 [:components :api])))
      (is (nil? (get-in s5 [:components :api :took]))))))
