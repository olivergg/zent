(ns zent.branch
  "Per-branch git worktrees, under
  ~/.cache/zent/worktrees/, synced to origin/<branch> - so a branch must be
  pushed. The main clone is never fetched or reset."
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

(defn resolve-worktree
  "Creates `repo-name`'s worktree for `branch` from `main-clone-dir` if
  missing, then syncs it to origin/<branch>; returns its path. A failed
  creation throws; a failed fetch (offline) keeps the worktree as it is."
  [main-clone-dir repo-name branch]
  (locking (repo-lock main-clone-dir)
    (let [worktree-dir (worktree-path repo-name branch)]
      (when-not (.exists (io/file worktree-dir))
        (println (str "Creating worktree for " repo-name "@" branch " -> " worktree-dir))
        (shell/sh-or-throw! ["git" "-C" main-clone-dir "worktree" "add" worktree-dir branch]))
      (println (str "Syncing worktree " worktree-dir " to origin/" branch))
      ;; reset only after a successful fetch
      (let [{:keys [exit err]} (shell/sh! ["git" "-C" worktree-dir "fetch" "origin" branch])]
        (if (zero? exit)
          (shell/sh-or-throw! ["git" "-C" worktree-dir "reset" "--hard" (str "origin/" branch)])
          (println (str "fetch of origin/" branch " failed, keeping " worktree-dir " as is: " (str/trim (str err))))))
      worktree-dir)))
