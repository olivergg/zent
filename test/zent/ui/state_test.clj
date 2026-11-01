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

(deftest git-view-test
  (testing "given a preset pinning a branch, then the card knows what was wanted; an apply keeps
            the git state and deployed commit already read"
    (let [s (-> state/empty-state
                (state/set-preset :a {:api {:kind :process :mode :on :repo "api" :branch "feature/x"}})
                (assoc-in [:components :api :git] {:sha "abc"})
                (assoc-in [:components :api :deployed-sha] "abc")
                (state/set-preset :b {:api {:kind :process :mode :on :repo "api"}}))]
      (is (nil? (get-in s [:components :api :wanted-branch])))
      (is (= {:sha "abc"} (get-in s [:components :api :git])))
      (is (= "abc" (get-in s [:components :api :deployed-sha])))))
  (let [cfg {:kind :process :mode :on :repo "api"}]
    (with-redefs [zent.source/describe (constantly nil)
                  zent.source/checkout-dir (constantly "/w/api")
                  zent.source/git-status (constantly {:sha "abc" :branch "main"})]
      (reset! state/view-state state/empty-state)
      (state/observe-preset-resolved! :a {:api cfg :ide (assoc cfg :kind :external)})
      (testing "given a deploy, then its commit is recorded and git read at once"
        (state/observe-deployed! :api cfg)
        (is (= "abc" (get-in @state/view-state [:components :api :deployed-sha])))
        (is (= {:sha "abc" :branch "main"} (get-in @state/view-state [:components :api :git]))))
      (testing "an :external one has no deployed commit: zent ran nothing"
        (state/observe-deployed! :ide (assoc cfg :kind :external))
        (is (nil? (get-in @state/view-state [:components :ide :deployed-sha]))))
      (testing "a refresh reads every described component; unchanged, it pushes nothing"
        (state/refresh-git!)
        (is (= {:sha "abc" :branch "main"} (get-in @state/view-state [:components :ide :git])))
        (let [before @state/view-state]
          (state/refresh-git!)
          (is (identical? before @state/view-state)))))
    (testing "a git failure doesn't fail the deploy it follows"
      (with-redefs [zent.source/checkout-dir (fn [_] (throw (ex-info "repo gone" {})))]
        (is (nil? (state/observe-deployed! :api cfg)))))))
