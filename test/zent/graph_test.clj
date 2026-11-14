(ns zent.graph-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zent.graph :as graph])
  (:import [java.io File]))

(deftest layout-test
  (testing "given a line, then one column and a straight line into every row but the first"
    (is (= [[0 []] [0 [[0 0]]] [0 [[0 0]]]]
           (map (juxt :col :segs) (graph/layout [{:sha "c" :parents ["b"]} {:sha "b" :parents ["a"]} {:sha "a" :parents []}])))))
  (testing "given a branch off a, then it takes column 0 and main column 1, both joining on a"
    (is (= [[0 []] [0 [[0 0]]] [1 [[0 0]]] [0 [[0 0] [1 0]]]]
           (map (juxt :col :segs) (graph/layout [{:sha "f2" :parents ["f1"]} {:sha "f1" :parents ["a"]}
                                                 {:sha "m1" :parents ["a"]} {:sha "a" :parents []}])))))
  (testing "given a merge, then its second parent opens a lane, closed on that parent's row"
    (is (= [[0 []] [0 [[0 0] [0 1]]] [1 [[0 0] [1 1]]] [0 [[0 0] [1 0]]]]
           (map (juxt :col :segs) (graph/layout [{:sha "m" :parents ["b" "s"]} {:sha "b" :parents ["a"]}
                                                 {:sha "s" :parents ["a"]} {:sha "a" :parents []}])))))
  (testing "given a parent outside the commits, then no lane waits for it"
    (is (= [[0 []] [0 [[0 0]]]]
           (map (juxt :col :segs) (graph/layout [{:sha "b" :parents ["a"]} {:sha "a" :parents ["gone"]}]))))))

(defn- git! [dir & args]
  (let [{:keys [exit err out]} (zent.shell/sh! (into ["git" "-c" "user.name=t" "-c" "user.email=t@t" "-C" dir] args))]
    (assert (zero? exit) (str args ": " err))
    (str/trim out)))

(defn- repos
  "A bare origin with master (base, then one more), `clone` on feature off base with a pushed commit
  and an unpushed one, and an uncommitted file."
  []
  (let [root (str (System/getProperty "java.io.tmpdir") "/zent-graph-" (System/nanoTime))
        origin (str root "/origin.git") clone (str root "/clone") other (str root "/other")
        commit (fn [dir f msg] (spit (str dir "/" f) msg) (git! dir "add" ".") (git! dir "commit" "--quiet" "-m" msg))]
    (.mkdirs (File. root))
    (git! root "init" "--quiet" "--bare" "--initial-branch=master" origin)
    (git! root "clone" "--quiet" origin clone)
    (commit clone "f.txt" "base") (git! clone "push" "--quiet" "origin" "master")
    (git! clone "checkout" "--quiet" "-b" "feature")
    (commit clone "g.txt" "pushed") (git! clone "push" "--quiet" "origin" "feature")
    (commit clone "g.txt" "local")
    (git! root "clone" "--quiet" origin other)
    (commit other "h.txt" "on master") (git! other "push" "--quiet" "origin" "master")
    (git! clone "fetch" "--quiet" "origin")
    (spit (str clone "/wip.txt") "x")
    clone))

(deftest read-repo-test
  (let [clone (repos)
        {:keys [base head refs commits truncated]} (graph/read-repo clone {:branch "feature"})
        by-name (into {} (map (juxt :name identity) refs))]
    (testing "given a clone on feature, then the default branch, HEAD and its uncommitted file"
      (is (= "master" base))
      (is (= {:branch "feature" :dirty ["wip.txt"]} (select-keys head [:branch :dirty]))))
    (testing "then both branches, local and on origin, a local one counted against origin's"
      (is (= #{"master" "origin/master" "feature" "origin/feature"} (set (keys by-name))))
      (is (= [1 0] ((juxt :ahead :behind) (by-name "feature"))))
      (is (= [0 1] ((juxt :ahead :behind) (by-name "master")))))
    (testing "then every commit the refs reach down to where they meet, newest first, laid out"
      (is (= ["local" "pushed" "on master" "base"] (sort-by #(.indexOf ["local" "pushed" "on master" "base"] %) (map :subject commits))))
      (is (= 4 (count commits)))
      (is (= "local" (:subject (first commits))))
      (is (= "base" (:subject (last commits))))
      (is (every? :col commits))
      (is (false? truncated)))))
