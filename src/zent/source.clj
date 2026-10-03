(ns zent.source
  "Workspace-first source resolution: no
  auto-cloning - fails with the clone command if the repo isn't under
  ~/workspace."
  (:require [clojure.string :as str]
            [zent.branch :as branch]
            [zent.shell :as shell]))

(defn- main-clone-dir [repo workspace-dir]
  (str (or workspace-dir (str (System/getenv "HOME") "/workspace")) "/" repo))

(defn resolve-source
  ([repo] (resolve-source repo {}))
  ([repo {:keys [workspace-dir branch org]}]
   ;; a :branch runs from its own worktree, reset to origin/<branch> on every
   ;; call (zent.branch); the main clone is only ever read
   (let [dir (main-clone-dir repo workspace-dir)]
     (if (.isDirectory (java.io.File. dir))
       (if branch
         (branch/resolve-worktree dir repo branch)
         dir)
       ;; the hint's org comes from the catalog - the engine doesn't know it
       (throw (ex-info (str "Repository not found: " repo)
                        {:expected dir
                         :hint (str "git clone git@github.com:" (or org "<your-org>")
                                    "/" repo ".git " dir)}))))))

(defn- current-branch
  "The branch checked out in `dir`, or nil (missing dir, not a repo,
  detached HEAD). Read-only."
  [dir]
  (when (.isDirectory (java.io.File. dir))
    (let [{:keys [exit out]} (shell/sh! ["git" "-C" dir "rev-parse" "--abbrev-ref" "HEAD"])
          branch (str/trim (str out))]
      (when (and (zero? exit) (seq branch) (not= "HEAD" branch))
        branch))))

(defn checkout-dir
  "The dir a component runs from - its worktree when :branch is pinned, else
  the main clone - WITHOUT resolve-source's side effects (creating, fetching
  and hard-resetting worktrees): what a liveness check or a display reads."
  [{:keys [repo branch workspace-dir]}]
  (if branch (branch/worktree-path repo branch) (main-clone-dir repo workspace-dir)))

(defn describe
  "Where a component's code actually comes from, for display:

    {:workdir  the main clone, always
     :worktree the dedicated worktree, only when :branch is pinned
     :dir      whichever of the two it actually runs from
     :branch   the branch currently checked out in :dir}

  Not built on resolve-source, which creates and hard-resets worktrees - a
  UI read must never do that. Never throws: a repo not checked out reports
  what it would be, with no :branch."
  [{:keys [repo branch workspace-dir] :as cfg}]
  (when repo
    (let [workdir (main-clone-dir repo workspace-dir)
          worktree (when branch (branch/worktree-path repo branch))
          dir (checkout-dir cfg)]
      (cond-> {:workdir workdir :dir dir :branch (current-branch dir)}
        worktree (assoc :worktree worktree)))))
