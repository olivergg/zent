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

(defn- repos
  "A bare origin and `clone`, both with master and feature (one commit each), the clone on feature;
  `other`, a second clone, to move origin. Worktrees go to `wt`."
  []
  (let [root (str (System/getProperty "java.io.tmpdir") "/zent-worktree-from-" (System/nanoTime))
        origin (str root "/origin.git") clone (str root "/clone") other (str root "/other")]
    (.mkdirs (java.io.File. root))
    (git! root "init" "--quiet" "--bare" "--initial-branch=master" origin)
    (git! root "clone" "--quiet" origin clone)
    (spit (str clone "/f.txt") "base") (spit (str clone "/.gitignore") "target/\n")
    (git! clone "add" ".") (git! clone "commit" "--quiet" "-m" "base")
    (git! clone "push" "--quiet" "origin" "master")
    (git! clone "checkout" "--quiet" "-b" "feature") (commit! clone "pushed") (git! clone "push" "--quiet" "origin" "feature")
    (git! root "clone" "--quiet" origin other)
    {:clone clone :other other :wt (str root "/wt")}))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest resolve-worktree-local-test
  (let [{:keys [clone other wt]} (repos)]
    (with-redefs [branch/worktree-path (fn [_ _] wt)]
      (commit! clone "local only")
      (testing "given :local and the clone on feature, then the worktree runs the local commit, detached"
        (branch/resolve-worktree clone "repo" "feature" :local)
        (is (= (git! clone "rev-parse" "feature") (git! wt "rev-parse" "HEAD")))
        (is (= "HEAD" (git! wt "rev-parse" "--abbrev-ref" "HEAD")))
        (is (= "feature" (git! clone "rev-parse" "--abbrev-ref" "HEAD")) "the clone keeps it checked out"))
      (testing "given a new local commit and origin moved, then a sync takes the local one, fetching nothing"
        (let [origin-ref (git! clone "rev-parse" "origin/feature")]
          (git! other "fetch" "--quiet" "origin") (git! other "checkout" "--quiet" "feature")
          (commit! other "on origin") (git! other "push" "--quiet" "origin" "feature")
          (commit! clone "local again")
          (branch/resolve-worktree clone "repo" "feature" :local)
          (is (= (git! clone "rev-parse" "feature") (git! wt "rev-parse" "HEAD")))
          (is (= origin-ref (git! clone "rev-parse" "origin/feature")) "no fetch")))
      (testing "given a switch to :origin and back, then a reset each time - an ignored build output survives"
        (.mkdirs (java.io.File. (str wt "/target")))
        (spit (str wt "/target/app.jar") "built")
        (branch/resolve-worktree clone "repo" "feature" :origin)
        (is (= (git! clone "rev-parse" "origin/feature") (git! wt "rev-parse" "HEAD")))
        (branch/resolve-worktree clone "repo" "feature" :local)
        (is (= (git! clone "rev-parse" "feature") (git! wt "rev-parse" "HEAD")))
        (is (= "built" (slurp (str wt "/target/app.jar"))))))))

(deftest resolve-worktree-never-loses-work-test
  (let [{:keys [clone wt]} (repos)]
    (with-redefs [branch/worktree-path (fn [_ _] wt)]
      (branch/resolve-worktree clone "repo" "feature" :local)
      (commit! clone "to sync")
      (testing "given an uncommitted file, then the sync refuses, the file and the commit kept, saying how"
        (spit (str wt "/wip.txt") "wip")
        (let [head (git! wt "rev-parse" "HEAD")
              e (refusal #(branch/resolve-worktree clone "repo" "feature" :local))]
          (is (re-find #"1 uncommitted file" (ex-message e)))
          (is (= (str "git -C " wt " stash") (:hint (ex-data e))))
          (is (= head (git! wt "rev-parse" "HEAD")))
          (is (= "wip" (slurp (str wt "/wip.txt"))))))
      (testing "given *sync* false, then nothing is checked nor changed"
        (binding [branch/*sync* false] (branch/resolve-worktree clone "repo" "feature" :local))
        (is (= "wip" (slurp (str wt "/wip.txt")))))
      (testing "given a commit of its own, on no branch, then the sync refuses, the commit kept"
        (git! wt "add" ".") (git! wt "commit" "--quiet" "-m" "in the worktree")
        (let [head (git! wt "rev-parse" "HEAD")
              e (refusal #(branch/resolve-worktree clone "repo" "feature" :origin))]
          (is (re-find #"1 commit\(s\) on no branch" (ex-message e)))
          (is (= (str "git -C " wt " branch rescue/feature HEAD") (:hint (ex-data e))))
          (is (= head (git! wt "rev-parse" "HEAD")))
          (testing "and once a branch keeps it, the sync goes through"
            (git! wt "branch" "rescue/feature" "HEAD")
            (branch/resolve-worktree clone "repo" "feature" :local)
            (is (= (git! clone "rev-parse" "feature") (git! wt "rev-parse" "HEAD")))))))))

(deftest resolve-worktree-no-local-branch-test
  (testing "given :local and no local branch, then an error saying how to make it"
    (let [{:keys [clone wt]} (repos)]
      (with-redefs [branch/worktree-path (fn [_ _] wt)]
        (git! clone "checkout" "--quiet" "master") (git! clone "branch" "--quiet" "-D" "feature")
        (let [e (refusal #(branch/resolve-worktree clone "repo" "feature" :local))]
          (is (re-find #"no local branch feature" (ex-message e)))
          (is (= (str "git -C " clone " branch feature origin/feature, or :from :origin") (:hint (ex-data e))))
          (is (not (.exists (java.io.File. wt)))))))))

(deftest at-risk-cant-tell-test
  (let [{:keys [clone wt]} (repos)
        not-git (str wt "-not-git")]
    (testing "given a dir git can't read, then nil counts, not 0"
      (.mkdirs (java.io.File. not-git))
      (is (= {:dirty nil :unreachable nil} (branch/at-risk not-git))))
    (testing "given a worktree git can't read, then a sync refuses instead of resetting"
      (with-redefs [branch/worktree-path (fn [_ _] wt)]
        (branch/resolve-worktree clone "repo" "feature")
        (let [head (git! wt "rev-parse" "HEAD")
              e (with-redefs [branch/at-risk (constantly {:dirty nil :unreachable 0})]
                  (refusal #(branch/resolve-worktree clone "repo" "master")))]
          (is (re-find #"can't tell what worktree" (ex-message e)))
          (is (= (str "git -C " wt " status") (:hint (ex-data e))))
          (is (= head (git! wt "rev-parse" "HEAD"))))))
    (testing "given one prune can't read, then kept, saying why"
      (let [entry {:path "/wt/x" :clone clone :repo "clone" :dirty 0 :unreachable nil}]
        (is (= {:removed [] :kept [[entry "can't tell what it holds"]]} (branch/prune! [entry] {} #{"clone"})))))))

(deftest worktrees-prune-test
  (let [{:keys [clone]} (repos)
        root (str (.getParent (java.io.File. clone)) "/worktrees")]
    (with-redefs [branch/worktrees-root (constantly root)]
      (git! clone "checkout" "--quiet" "master")
      (git! clone "branch" "--quiet" "a" "feature") (git! clone "branch" "--quiet" "b" "feature")
      (doseq [b ["a" "b" "feature"]] (branch/resolve-worktree clone "repo" b :local))
      (spit (str (branch/worktree-path "repo" "b") "/wip.txt") "wip")
      (git! (branch/worktree-path "repo" "a") "update-ref" (branch/test-ref "a") "HEAD")
      (let [entries (branch/worktrees)
            used (branch/worktree-path "repo" "feature")]
        (testing "given three worktrees, then each with its clone, commit and what's at risk"
          (is (= [["clone" 0] ["clone" 1] ["clone" 0]] (map (juxt :repo :dirty) entries)) "the repo: its clone's dir")
          (is (every? :head entries)))
        (testing "given another catalog's repos, then all kept, clean and unused or not"
          (let [{:keys [removed kept]} (branch/prune! entries {} #{"elsewhere"})]
            (is (empty? removed))
            (is (= #{"repo clone isn't in this catalog"} (set (map second kept))))
            (is (every? #(.exists (java.io.File. ^String (:path %))) entries))))
        (testing "given one used by a preset and one dirty, then prune removes only the third"
          (let [{:keys [removed kept]} (branch/prune! entries {used #{:mine}} #{"clone"})]
            (is (= [(branch/worktree-path "repo" "a")] (map :path removed)))
            (is (= #{"used by mine" "1 uncommitted file(s)"} (set (map second kept))))
            (is (not (.exists (java.io.File. (branch/worktree-path "repo" "a")))))
            (is (.exists (java.io.File. (str (branch/worktree-path "repo" "b") "/wip.txt"))))
            (is (not (re-find #"repo-a\b" (git! clone "worktree" "list"))) "git forgot it too")
            (is (not (zero? (:exit (zent.shell/sh! ["git" "-C" clone "rev-parse" "--verify" "--quiet" (branch/test-ref "a")]))))
                "its local test's ref went with it")))))))
