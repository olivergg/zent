(ns zent.source
  "Workspace-first source resolution: no
  auto-cloning - fails with the clone command if the repo isn't under
  ~/workspace."
  (:require [clojure.string :as str]
            [zent.branch :as branch]
            [zent.shell :as shell]))

(defn- main-clone-dir [repo workspace-dir]
  (str (or workspace-dir (str (System/getenv "HOME") "/workspace")) "/" repo))

(defn- current-branch
  "The branch checked out in `dir`, or nil (missing dir, not a repo,
  detached HEAD). Read-only."
  [dir]
  (when (.isDirectory (java.io.File. dir))
    (let [{:keys [exit out]} (shell/sh! ["git" "-C" dir "rev-parse" "--abbrev-ref" "HEAD"])
          branch (str/trim (str out))]
      (when (and (zero? exit) (seq branch) (not= "HEAD" branch))
        branch))))

(defn- main-clone-on?
  "Whether `branch` is the one checked out in main clone `dir`: git refuses it
  a worktree then, and the main clone, as it is, is that branch already."
  [dir branch]
  (= branch (current-branch dir)))

(defn resolve-source
  ([repo] (resolve-source repo {}))
  ([repo {:keys [workspace-dir branch org]}]
   ;; a :branch runs from its own worktree, reset to origin/<branch> on every
   ;; call (zent.branch); the main clone is only ever read
   (let [dir (main-clone-dir repo workspace-dir)]
     (cond
       (not (.isDirectory (java.io.File. dir)))
       ;; the hint's org comes from the catalog - the engine doesn't know it
       (throw (ex-info (str "Repository not found: " repo)
                       {:expected dir
                        :hint (str "git clone git@github.com:" (or org "<your-org>")
                                   "/" repo ".git " dir)}))

       (not branch) dir

       (main-clone-on? dir branch)
       (do (println (str repo "@" branch " is checked out in " dir " - running from it, as it is"))
           dir)

       :else (branch/resolve-worktree dir repo branch)))))

(defn checkout-dir
  "The dir a component runs from - its worktree when :branch is pinned (unless
  the main clone has that branch checked out), else the main clone - WITHOUT
  resolve-source's side effects (creating, fetching and hard-resetting
  worktrees): what a liveness check or a display reads."
  [{:keys [repo branch workspace-dir]}]
  (let [main (main-clone-dir repo workspace-dir)
        worktree (when branch (branch/worktree-path repo branch))]
    ;; an existing worktree means git keeps the branch out of the main clone:
    ;; no git call on that common path
    (if (and worktree (or (.isDirectory (java.io.File. worktree)) (not (main-clone-on? main branch))))
      worktree
      main)))

(defn describe
  "Where a component's code actually comes from, for display:

    {:workdir  the main clone, always
     :worktree the dedicated worktree, only when it runs from one
     :dir      whichever of the two it actually runs from
     :branch   the branch currently checked out in :dir}

  Not built on resolve-source, which creates and hard-resets worktrees - a
  UI read must never do that. Never throws: a repo not checked out reports
  what it would be, with no :branch."
  [{:keys [repo workspace-dir] :as cfg}]
  (when repo
    (let [workdir (main-clone-dir repo workspace-dir)
          dir (checkout-dir cfg)]
      (cond-> {:workdir workdir :dir dir :branch (current-branch dir)}
        (not= dir workdir) (assoc :worktree dir)))))
