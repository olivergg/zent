(ns zent.branch-test
  "Tests worktree-path (pure cache-path naming logic) only - no real git
  worktree creation, no network. resolve-worktree itself isn't exercised
  here since it shells out to git and touches the filesystem cache."
  (:require [clojure.test :refer [deftest is testing]]
            [zent.branch :as branch]))

(deftest worktree-path-simple-branch-test
  (testing "simple branch name -> <repo>-<branch> under the cache dir"
    (is (= (str (System/getenv "HOME") "/.cache/zent/worktrees/webapp-mybranch")
           (branch/worktree-path "webapp" "mybranch")))))

(deftest worktree-path-slash-branch-test
  (testing "slashes in branch names are replaced with underscores"
    (is (= (str (System/getenv "HOME") "/.cache/zent/worktrees/webapp-feature_FOO-123")
           (branch/worktree-path "webapp" "feature/FOO-123")))))

(deftest worktree-path-multiple-slashes-test
  (testing "all slashes are replaced, not just the first"
    (is (= (str (System/getenv "HOME") "/.cache/zent/worktrees/repo-a_b_c")
           (branch/worktree-path "repo" "a/b/c")))))
