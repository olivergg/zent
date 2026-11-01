(ns zent.branch-test
  "worktree-path's naming; resolve-worktree on real throwaway repos (a local bare origin, no network),
  its worktrees redirected out of ~/.cache."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zent.branch :as branch]
            [zent.shell :as shell]))

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

(defn- git! [dir & args]
  (let [{:keys [exit err out]} (shell/sh! (into ["git" "-c" "user.name=t" "-c" "user.email=t@t" "-C" dir] args))]
    (assert (zero? exit) (str args ": " err))
    (str/trim out)))

(defn- commit! [dir text]
  (spit (str dir "/f.txt") text)
  (git! dir "commit" "--quiet" "-am" text))

(deftest resolve-worktree-test
  (let [root (str (System/getProperty "java.io.tmpdir") "/zent-worktree-" (System/nanoTime))
        origin (str root "/origin.git") clone (str root "/clone") wt (str root "/wt")]
    (.mkdirs (java.io.File. root))
    (git! root "init" "--quiet" "--bare" "--initial-branch=master" origin)
    (git! root "clone" "--quiet" origin clone)
    (spit (str clone "/f.txt") "base") (git! clone "add" ".") (git! clone "commit" "--quiet" "-m" "base")
    (git! clone "push" "--quiet" "origin" "master")
    (git! clone "checkout" "--quiet" "-b" "feature") (commit! clone "pushed") (git! clone "push" "--quiet" "origin" "feature")
    (commit! clone "local only")
    (git! clone "checkout" "--quiet" "master")
    (with-redefs [branch/worktree-path (fn [_ _] wt)]
      (let [local (git! clone "rev-parse" "feature")]
        (testing "given a local commit not pushed, then the worktree runs origin's, detached, the branch untouched"
          (branch/resolve-worktree clone "repo" "feature")
          (is (= (git! clone "rev-parse" "origin/feature") (git! wt "rev-parse" "HEAD")))
          (is (= "HEAD" (git! wt "rev-parse" "--abbrev-ref" "HEAD")))
          (is (= local (git! clone "rev-parse" "feature"))))
        (testing "and the main clone may still check the branch out"
          (git! clone "checkout" "--quiet" "feature")
          (is (= "feature" (git! clone "rev-parse" "--abbrev-ref" "HEAD")))
          (git! clone "checkout" "--quiet" "master"))
        (testing "given a worktree from before (holding the branch), then a sync detaches it first"
          (git! clone "worktree" "remove" "--force" wt)
          (git! clone "worktree" "add" "--quiet" wt "feature")
          (branch/resolve-worktree clone "repo" "feature")
          (is (= "HEAD" (git! wt "rev-parse" "--abbrev-ref" "HEAD")))
          (is (= local (git! clone "rev-parse" "feature"))))))))

(deftest resolve-worktree-no-sync-test
  (testing "given *sync* false (a reload), then an existing worktree keeps its commit though origin moved"
    (let [root (str (System/getProperty "java.io.tmpdir") "/zent-worktree-nosync-" (System/nanoTime))
          origin (str root "/origin.git") clone (str root "/clone") wt (str root "/wt")]
      (.mkdirs (java.io.File. root))
      (git! root "init" "--quiet" "--bare" "--initial-branch=master" origin)
      (git! root "clone" "--quiet" origin clone)
      (spit (str clone "/f.txt") "base") (git! clone "add" ".") (git! clone "commit" "--quiet" "-m" "base")
      (git! clone "push" "--quiet" "origin" "master")
      (with-redefs [branch/worktree-path (fn [_ _] wt)]
        (branch/resolve-worktree clone "repo" "master")
        (let [before (git! wt "rev-parse" "HEAD")]
          (commit! clone "moved") (git! clone "push" "--quiet" "origin" "master")
          (binding [branch/*sync* false] (branch/resolve-worktree clone "repo" "master"))
          (is (= before (git! wt "rev-parse" "HEAD")))
          (branch/resolve-worktree clone "repo" "master")
          (is (= (git! clone "rev-parse" "origin/master") (git! wt "rev-parse" "HEAD")) "an apply or a pull syncs it"))))))
