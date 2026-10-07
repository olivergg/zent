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
      (with-redefs [branch/resolve-worktree (fn [main-clone-dir repo-name b]
                                               (is (= repo-dir main-clone-dir))
                                               (is (= "another-repo" repo-name))
                                               (is (= "feature/foo" b))
                                               worktree-dir)]
        (is (= worktree-dir
               (source/resolve-source "another-repo" {:workspace-dir root :branch "feature/foo"})))))))

(deftest resolve-source-branch-checked-out-in-main-clone-test
  (testing "given the main clone has the branch checked out, then it runs from the
            main clone as it is - no worktree, which git would refuse anyway"
    (let [root (str (System/getProperty "java.io.tmpdir") "/zent-source-test-main-" (System/currentTimeMillis))
          repo-dir (str root "/some-repo")]
      (.mkdirs (File. repo-dir))
      (with-redefs [zent.shell/sh! (fn [_] {:exit 0 :out "feature/foo\n" :err ""})
                    branch/resolve-worktree (fn [& _] (throw (ex-info "must not be called" {})))]
        (is (= repo-dir (source/resolve-source "some-repo" {:workspace-dir root :branch "feature/foo"})))))))

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

    (testing "given the pinned branch is the one checked out in the main clone, then it
              reads the main clone - git refuses that branch a worktree"
      (with-redefs [zent.shell/sh! (fn [_] {:exit 0 :out "feature/x\n" :err ""})]
        (is (= {:workdir repo-dir :dir repo-dir :branch "feature/x"}
               (source/describe {:repo "some-repo" :workspace-dir root :branch "feature/x"})))))

    (testing "a detached HEAD or a non-repo directory reports no branch rather
              than the literal string git prints"
      (with-redefs [zent.shell/sh! (fn [_] {:exit 0 :out "HEAD\n" :err ""})]
        (is (nil? (:branch (source/describe {:repo "some-repo" :workspace-dir root}))))))

    (testing "a component with no :repo at all (a kind that resolves no
              checkout) describes nothing instead of guessing a path"
      (is (nil? (source/describe {:workspace-dir root}))))))
