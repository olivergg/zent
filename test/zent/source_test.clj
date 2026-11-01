(ns zent.source-test
  (:require [clojure.test :refer [deftest is testing]]
            [zent.branch :as branch]
            [zent.source :as source])
  (:import [java.io File]))

(deftest resolve-source-default-workspace-test
  (testing "without :workspace-dir, both arities look under ~/workspace - read off
            the not-found error, so the test needs no real clone"
    (let [expected (str (System/getenv "HOME") "/workspace/zent-no-such-repo")
          expected-dir #(try (% "zent-no-such-repo") nil
                             (catch clojure.lang.ExceptionInfo e (:expected (ex-data e))))]
      (is (= expected (expected-dir source/resolve-source)))
      (is (= expected (expected-dir #(source/resolve-source % {})))))))

(deftest resolve-source-workspace-dir-override-test
  (testing ":workspace-dir overrides where the repo is looked up"
    (let [tmp (System/getProperty "java.io.tmpdir")
          root (str tmp "/zent-source-test-" (System/currentTimeMillis))
          repo-dir (str root "/some-repo")]
      (.mkdirs (File. repo-dir))
      (is (= repo-dir (source/resolve-source "some-repo" {:workspace-dir root}))))))

(deftest resolve-source-branch-wired-test
  (testing "branch delegates to zent.branch/resolve-worktree instead of the main clone dir"
    (let [tmp (System/getProperty "java.io.tmpdir")
          root (str tmp "/zent-source-test-branch-" (System/currentTimeMillis))
          repo-dir (str root "/another-repo")
          worktree-dir (str tmp "/fake-worktree-another-repo-feature_foo")]
      (.mkdirs (File. repo-dir))
      (with-redefs [branch/resolve-worktree (fn [main-clone-dir repo-name b from]
                                               (is (= repo-dir main-clone-dir))
                                               (is (= "another-repo" repo-name))
                                               (is (= "feature/foo" b))
                                               (str worktree-dir "@" (name from)))]
        (is (= (str worktree-dir "@origin")
               (source/resolve-source "another-repo" {:workspace-dir root :branch "feature/foo"})))
        (is (= (str worktree-dir "@local")
               (source/resolve-source "another-repo" {:workspace-dir root :branch "feature/foo" :from :local})))))))

(deftest resolve-source-clone-test
  (let [root (str (System/getProperty "java.io.tmpdir") "/zent-source-test-main-" (System/currentTimeMillis))
        repo-dir (str root "/some-repo")
        on (fn [b] (fn [_] {:exit 0 :out (str b "\n") :err ""}))]
    (.mkdirs (File. repo-dir))
    (testing "given :from :clone and the main clone on the branch, then the main clone as it is - no worktree"
      (with-redefs [zent.shell/sh! (on "feature/foo")
                    branch/resolve-worktree (fn [& _] (throw (ex-info "must not be called" {})))]
        (is (= repo-dir (source/resolve-source "some-repo" {:workspace-dir root :branch "feature/foo" :from :clone})))))
    (testing "given :from :clone and the main clone elsewhere, then it refuses, saying how to fix it - zent never switches it"
      (with-redefs [zent.shell/sh! (on "main")]
        (let [e (try (source/resolve-source "some-repo" {:workspace-dir root :branch "feature/foo" :from :clone}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (re-find #"must have feature/foo checked out - it has main" (ex-message e)))
          (is (= (str "git -C " repo-dir " checkout feature/foo") (:hint (ex-data e)))))))
    (testing "given no :from, then origin's worktree - even when the main clone is on the branch"
      (with-redefs [zent.shell/sh! (on "feature/foo")
                    branch/resolve-worktree (fn [_ _ b _] (str "/wt/" b))]
        (is (= "/wt/feature/foo" (source/resolve-source "some-repo" {:workspace-dir root :branch "feature/foo"})))))))

(deftest resolve-source-not-found-test
  (testing "fails loudly with the clone hint when the repo isn't checked out"
    (let [tmp (System/getProperty "java.io.tmpdir")
          root (str tmp "/zent-source-test-missing-" (System/currentTimeMillis))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Repository not found"
                             (source/resolve-source "nope" {:workspace-dir root}))))))

(deftest describe-test
  (let [tmp (System/getProperty "java.io.tmpdir")
        root (str tmp "/zent-source-describe-" (System/currentTimeMillis))
        repo-dir (str root "/some-repo")]
    (.mkdirs (File. repo-dir))

    (testing "no branch pin: the main clone, no worktree, branch read from git"
      (with-redefs [zent.shell/sh! (fn [cmd]
                                     (is (= ["git" "-C" repo-dir "rev-parse" "--abbrev-ref" "HEAD"] cmd))
                                     {:exit 0 :out "main\n" :err ""})]
        (is (= {:workdir repo-dir :dir repo-dir :branch "main"}
               (source/describe {:repo "some-repo" :workspace-dir root})))))

    (testing "a pinned branch reports the worktree WITHOUT creating or syncing
              one - resolve-worktree fetches and hard-resets, which a UI read
              must never trigger"
      (with-redefs [branch/resolve-worktree (fn [& _] (throw (ex-info "must not be called" {})))
                    zent.shell/sh! (fn [[_ _ dir]] {:exit 0 :out (if (= dir repo-dir) "main\n" "feature/x\n") :err ""})]
        (let [d (source/describe {:repo "some-repo" :workspace-dir root :branch "feature/x"})]
          (is (= repo-dir (:workdir d)))
          (is (= (branch/worktree-path "some-repo" "feature/x") (:worktree d)))
          (is (= (:worktree d) (:dir d))))))

    (testing "given :from :clone, then it reads the main clone; :from :local, the worktree"
      (with-redefs [zent.shell/sh! (fn [_] {:exit 0 :out "feature/x\n" :err ""})]
        (is (= {:workdir repo-dir :dir repo-dir :branch "feature/x"}
               (source/describe {:repo "some-repo" :workspace-dir root :branch "feature/x" :from :clone})))
        (is (= (branch/worktree-path "some-repo" "feature/x")
               (:worktree (source/describe {:repo "some-repo" :workspace-dir root :branch "feature/x" :from :local}))))))

    (testing "a detached HEAD or a non-repo directory reports no branch rather
              than the literal string git prints"
      (with-redefs [zent.shell/sh! (fn [_] {:exit 0 :out "HEAD\n" :err ""})]
        (is (nil? (:branch (source/describe {:repo "some-repo" :workspace-dir root}))))))

    (testing "a component with no :repo at all (a kind that resolves no
              checkout) describes nothing instead of guessing a path"
      (is (nil? (source/describe {:workspace-dir root}))))))

(deftest parse-status-test
  (testing "given porcelain v2 with --branch, then HEAD's sha and branch, and one dirty count for
            modified, renamed, conflicted and untracked paths alike"
    (is (= {:sha "abc123" :branch "feature/x" :dirty 4}
           (source/parse-status (str "# branch.oid abc123\n# branch.head feature/x\n# branch.upstream origin/feature/x\n"
                                     "# branch.ab +1 -0\n1 .M N... 100644 100644 100644 a b src/a.clj\n"
                                     "2 R. N... 100644 100644 100644 a b R100 new.clj\told.clj\n"
                                     "u UU N... 100644 100644 100644 100644 a b c conflict.clj\n? notes.txt\n")))))
  (testing "a detached HEAD has no branch, a repo with no commit no sha"
    (is (= {:dirty 0} (source/parse-status "# branch.oid (initial)\n# branch.head (detached)\n")))))

(deftest git-status-test
  (let [calls (atom [])
        git (fn [answers]
              (fn [cmd]
                (swap! calls conj cmd)
                (let [args (drop 4 cmd)]
                  (or (some (fn [[prefix out]] (when (= prefix (take (count prefix) args)) {:exit 0 :out out :err ""})) answers)
                      {:exit 128 :out "" :err "unexpected"}))))]
    (testing "given a branch pushed to origin, then behind counts against origin/HEAD's branch, ahead
              against origin/<branch> - local refs only, never a fetch"
      (with-redefs [zent.shell/sh! (git [[["status"] "# branch.oid abc\n# branch.head feature/x\n? new.txt\n"]
                                         [["for-each-ref"] (str "refs/remotes/origin/HEAD refs/remotes/origin/main\n"
                                                                "refs/remotes/origin/main \nrefs/remotes/origin/feature/x \n")]
                                         [["rev-list" "--count" "HEAD..origin/main"] "3\n"]
                                         [["rev-list" "--count" "origin/feature/x..HEAD"] "1\n"]
                                         [["rev-parse"] "/nowhere/FETCH_HEAD\n/nowhere\n"]])]
        (is (= {:sha "abc" :branch "feature/x" :dirty 1 :pushed true :base "main" :behind 3 :ahead 1}
               (source/git-status "/w/repo")))
        (is (every? #(= ["git" "--no-optional-locks" "-C" "/w/repo"] (take 4 %)) @calls))
        (is (not-any? #(some #{"fetch"} %) @calls))))
    (testing "given a branch never pushed and no origin/HEAD, then the base is master and ahead counts
              what no origin ref has"
      (with-redefs [zent.shell/sh! (git [[["status"] "# branch.oid abc\n# branch.head local\n"]
                                         [["for-each-ref"] "refs/remotes/origin/master \n"]
                                         [["rev-list" "--count" "HEAD..origin/master"] "0\n"]
                                         [["rev-list" "--count" "HEAD" "--not" "--remotes=origin"] "2\n"]])]
        (is (= {:sha "abc" :branch "local" :dirty 0 :pushed false :base "master" :behind 0 :ahead 2}
               (source/git-status "/w/repo")))))
    (testing "not a checkout: nil"
      (with-redefs [zent.shell/sh! (git [])]
        (is (nil? (source/git-status "/w/none")))))))

(defn- git! [dir & args]
  (let [{:keys [exit err out]} (zent.shell/sh! (into ["git" "-c" "user.name=t" "-c" "user.email=t@t" "-C" dir] args))]
    (assert (zero? exit) (str args ": " err))
    (clojure.string/trim out)))

(defn- repos
  "A bare origin, `clone` on feature (a commit of its own on f.txt), then origin's master one commit
  ahead (on `master-file`, from another clone)."
  [master-file]
  (let [root (str (System/getProperty "java.io.tmpdir") "/zent-rebase-" (System/nanoTime))
        origin (str root "/origin.git") clone (str root "/clone") other (str root "/other")
        write (fn [dir f text] (spit (str dir "/" f) text))]
    (.mkdirs (File. root))
    (git! root "init" "--quiet" "--bare" "--initial-branch=master" origin)
    (git! root "clone" "--quiet" origin clone)
    (write clone "f.txt" "base\n") (git! clone "add" ".") (git! clone "commit" "--quiet" "-m" "base")
    (git! clone "push" "--quiet" "origin" "master")
    (git! root "clone" "--quiet" origin other)
    (git! clone "checkout" "--quiet" "-b" "feature")
    (write clone "f.txt" "feature\n") (git! clone "commit" "--quiet" "-am" "feature")
    (write other master-file "master\n") (git! other "add" ".") (git! other "commit" "--quiet" "-m" "master")
    (git! other "push" "--quiet" "origin" "master")
    clone))

(defn- cfg-of
  "The cfg of a component running `clone`, with `more` (:branch :from)."
  [clone & {:as more}]
  (merge {:repo (.getName (File. clone)) :workspace-dir (.getParent (File. clone))} more))

(deftest rebase-refusal-test
  (let [st {:branch "feature/x" :base "master" :dirty 0 :behind 3}]
    (is (nil? (source/rebase-refusal st)))
    (is (re-find #"uncommitted" (source/rebase-refusal (assoc st :dirty 1))))
    (is (re-find #"already" (source/rebase-refusal (assoc st :behind 0))))
    (is (re-find #"itself" (source/rebase-refusal (assoc st :branch "master"))))
    (is (re-find #"detached" (source/rebase-refusal (dissoc st :branch))))))

(deftest rebase-test
  (testing "given origin's master ahead on another file, then the branch is rebased onto it"
    (let [clone (repos "g.txt")
          {:keys [ok message]} (source/rebase! (cfg-of clone))]
      (is ok message)
      (is (= (git! clone "rev-parse" "origin/master") (git! clone "merge-base" "HEAD" "origin/master")))
      (is (= "feature" (git! clone "rev-parse" "--abbrev-ref" "HEAD")))))
  (testing "given other branches on origin, then only the default one is fetched, nothing pruned"
    (let [clone (repos "g.txt")
          other (str (.getParent (File. clone)) "/other")]
      (git! other "push" "--quiet" "origin" "master:gone") (git! clone "fetch" "--quiet" "origin")
      (git! other "push" "--quiet" "origin" "master:new") (git! other "push" "--quiet" "origin" "--delete" "gone")
      (is (:ok (source/rebase! (cfg-of clone))))
      (is (not (clojure.string/blank? (git! clone "rev-parse" "--verify" "-q" "origin/gone"))) "not pruned")
      (is (not (zero? (:exit (zent.shell/sh! ["git" "-C" clone "rev-parse" "--verify" "-q" "origin/new"])))) "not fetched")))
  (testing "given a conflict, then nothing moves"
    (let [clone (repos "f.txt")
          before (git! clone "rev-parse" "HEAD")
          {:keys [ok message]} (source/rebase! (cfg-of clone))]
      (is (not ok))
      (is (re-find #"conflicts" message))
      (is (= before (git! clone "rev-parse" "HEAD")))))
  (testing "given an uncommitted file, then nothing moves"
    (let [clone (repos "g.txt")
          before (git! clone "rev-parse" "HEAD")]
      (spit (str clone "/f.txt") "wip\n")
      (is (not (:ok (source/rebase! (cfg-of clone)))))
      (is (= before (git! clone "rev-parse" "HEAD"))))))

(deftest rebase-local-test
  (let [clone (repos "g.txt")
        wt (str (.getParent (File. clone)) "/wt")
        cfg (cfg-of clone :branch "feature" :from :local)]
    (with-redefs [branch/worktree-path (fn [_ _] wt)]
      (git! clone "push" "--quiet" "origin" "feature")
      (branch/resolve-worktree clone "clone" "feature" :local)
      (testing "given the clone has feature checked out, then refused, saying to rebase it there"
        (let [before (git! clone "rev-parse" "feature")
              {:keys [ok message]} (source/rebase! cfg)]
          (is (not ok))
          (is (re-find #"is checked out in .*clone: rebase it there" message) message)
          (is (= before (git! clone "rev-parse" "feature")))
          (is (= "HEAD" (git! wt "rev-parse" "--abbrev-ref" "HEAD")))
          (is (re-find #"clone$" (:held-at (source/checkout-git cfg))) "the card knows before the click")))
      (testing "given the clone elsewhere, then the local feature is rebased in the worktree, left detached at it"
        (git! clone "checkout" "--quiet" "master")
        (let [old (git! clone "rev-parse" "feature")
              {:keys [ok message]} (source/rebase! cfg)]
          (is ok message)
          (is (re-find (re-pattern (str "branch -f feature " old)) message) "the undo")
          (is (re-find #"push --force-with-lease" message) "feature is on origin")
          (is (= (git! clone "rev-parse" "origin/master") (git! clone "merge-base" "feature" "origin/master")))
          (is (not= (git! clone "rev-parse" "origin/feature") (git! clone "rev-parse" "feature")) "origin untouched")
          (is (= "HEAD" (git! wt "rev-parse" "--abbrev-ref" "HEAD")))
          (is (= (git! clone "rev-parse" "feature") (git! wt "rev-parse" "HEAD"))))))))

(deftest rebase-origin-test
  (let [clone (repos "g.txt")
        wt (str (.getParent (File. clone)) "/wt")
        cfg (cfg-of clone :branch "feature")]
    (with-redefs [branch/worktree-path (fn [_ _] wt)]
      (git! clone "push" "--quiet" "origin" "feature")
      (branch/resolve-worktree clone "clone" "feature")
      (let [branches #(git! clone "for-each-ref" "--format=%(refname) %(objectname)" "refs/heads" "refs/remotes/origin/feature")
            before (branches)]
        (testing "given an :origin worktree behind master, then a local test: its HEAD rebased, no branch moved"
          (let [{:keys [ok message]} (source/rebase! cfg)]
            (is ok message)
            (is (re-find #"local test" message))
            (is (= before (branches)))
            (is (= (git! wt "rev-parse" "HEAD") (git! clone "rev-parse" (branch/test-ref "feature"))))
            (is (= (git! clone "rev-parse" "origin/master") (git! wt "merge-base" "HEAD" "origin/master")))
            (is (:test? (source/checkout-git cfg)) "the chip tells")))
        (testing "and the next sync resets it to origin/feature, dropping the test ref"
          (branch/resolve-worktree clone "clone" "feature")
          (is (= (git! clone "rev-parse" "origin/feature") (git! wt "rev-parse" "HEAD")))
          (is (not (zero? (:exit (zent.shell/sh! ["git" "-C" clone "rev-parse" "--verify" "-q" (branch/test-ref "feature")])))))
          (is (not (:test? (source/checkout-git cfg)))))))))

(deftest checkout-git-local-test
  (let [clone (repos "g.txt")
        wt (str (.getParent (File. clone)) "/wt")
        cfg (cfg-of clone :branch "feature" :from :local)]
    (with-redefs [branch/worktree-path (fn [_ _] wt)]
      (git! clone "push" "--quiet" "origin" "feature")
      (branch/resolve-worktree clone "clone" "feature" :local)
      (spit (str clone "/h.txt") "h") (git! clone "add" ".") (git! clone "commit" "--quiet" "-m" "local")
      (testing "given a new commit on the local branch, then the worktree may pull it, and it's unpushed on feature"
        (is (= {:from :local :pull 1 :unreachable 0 :local-ahead 1 :local-behind 0}
               (select-keys (source/checkout-git cfg) [:from :pull :unreachable :local-ahead :local-behind]))))
      (testing "given a commit made in the worktree itself, then it's on no branch"
        (spit (str wt "/w.txt") "w") (git! wt "add" ".") (git! wt "commit" "--quiet" "-m" "in wt")
        (is (= 1 (:unreachable (source/checkout-git cfg))))))))
