(ns zent.branch
  "Per-branch git worktrees, under
  ~/.cache/zent/worktrees/, synced to origin/<branch> - so a branch must be
  pushed. Detached at that commit: no local branch is ever moved or held, so
  the main clone stays free to check <branch> out, its unpushed commits safe."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [zent.shell :as shell]))

(defn worktree-path
  "Where `repo-name`'s worktree for `branch` lives (`/` in the branch -> `_`).
  Pure, so testable without the filesystem."
  [repo-name branch]
  (let [home (System/getenv "HOME")
        safe-branch (str/replace branch "/" "_")]
    (str home "/.cache/zent/worktrees/" repo-name "-" safe-branch)))

;; One lock per main clone: components deploy in parallel, and two git
;; commands writing one repo's refs at once fail on its lock files.
(defonce ^:private repo-locks (atom {}))

(defn- repo-lock [dir]
  (get (swap! repo-locks update dir #(or % (Object.))) dir))

(def ^:dynamic *sync*
  "Whether resolve-worktree brings an existing worktree to origin/<branch>: an apply does, a reload
  restarts the commit already there (zent.engine binds it), a pull does again."
  true)

(defn resolve-worktree
  "Creates `repo-name`'s worktree for `branch` from `main-clone-dir` if
  missing, then - when *sync* - syncs it to origin/<branch>; returns its path.
  A failed creation throws; a failed fetch (offline) keeps the worktree as it is."
  [main-clone-dir repo-name branch]
  (locking (repo-lock main-clone-dir)
    (let [worktree-dir (worktree-path repo-name branch)
          exists? (.exists (io/file worktree-dir))
          origin (str "origin/" branch)
          ;; the worktrees share the main clone's refs: fetching there updates origin/<branch> for both
          fetched? (when (or *sync* (not exists?))
                     (zero? (:exit (shell/sh! ["git" "-C" main-clone-dir "fetch" "origin" branch]))))]
      (if exists?
        (do
          ;; one made before worktrees were detached still holds <branch>: let it go first - reset --hard
          ;; would move it, and git would keep the main clone from checking it out
          (shell/sh-or-throw! ["git" "-C" worktree-dir "checkout" "--quiet" "--detach"])
          (cond
            (not *sync*) (println (str "Keeping worktree " worktree-dir " at its commit (`zent pull` brings " origin ")"))
            fetched? (do (println (str "Syncing worktree " worktree-dir " to " origin))
                         (shell/sh-or-throw! ["git" "-C" worktree-dir "reset" "--quiet" "--hard" origin]))
            :else (println (str "fetch of " origin " failed, keeping " worktree-dir " as is"))))
        (do (println (str "Creating worktree for " repo-name "@" branch " -> " worktree-dir " (detached at " origin ")"))
            (shell/sh-or-throw! ["git" "-C" main-clone-dir "worktree" "add" "--detach" worktree-dir origin])))
      worktree-dir)))
