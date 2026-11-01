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
  ([repo {:keys [workspace-dir branch local org]}]
   ;; a :branch runs origin/<branch> from its own worktree (zent.branch); with :local, the main clone
   ;; as it is, which must have it checked out - zent never switches it; the main clone is only read
   (let [dir (main-clone-dir repo workspace-dir)]
     (cond
       (not (.isDirectory (java.io.File. dir)))
       ;; the hint's org comes from the catalog - the engine doesn't know it
       (throw (ex-info (str "Repository not found: " repo)
                       {:expected dir
                        :hint (str "git clone git@github.com:" (or org "<your-org>")
                                   "/" repo ".git " dir)}))

       (not branch) dir

       (not local) (branch/resolve-worktree dir repo branch)

       (main-clone-on? dir branch) dir

       :else (throw (ex-info (str repo ": :local runs " dir " as it is, which must have " branch " checked out - it has "
                                  (or (current-branch dir) "no branch"))
                             {:dir dir :hint (str "git -C " dir " checkout " branch)}))))))

(defn checkout-dir
  "The dir a component runs from - its worktree for a :branch, the main clone
  without one or with :local - WITHOUT
  resolve-source's side effects (creating, fetching and hard-resetting
  worktrees): what a liveness check or a display reads."
  [{:keys [repo branch local workspace-dir]}]
  (if (and branch (not local))
    (branch/worktree-path repo branch)
    (main-clone-dir repo workspace-dir)))

(defn- git
  "`git -C dir args`'s trimmed stdout, nil on failure. --no-optional-locks: a
  background `status` mustn't take the index lock from the user's own git."
  [dir & args]
  (let [{:keys [exit out]} (shell/sh! (into ["git" "--no-optional-locks" "-C" dir] args))]
    (when (zero? exit) (str/trim (str out)))))

(defn parse-status
  "`git status --porcelain=v2 --branch` -> {:sha :branch :dirty}: :branch nil
  when detached, :dirty the modified and untracked paths."
  [out]
  (reduce (fn [acc line]
            (cond
              (str/starts-with? line "# branch.oid ") (let [v (subs line 13)] (cond-> acc (not= "(initial)" v) (assoc :sha v)))
              (str/starts-with? line "# branch.head ") (let [v (subs line 14)] (cond-> acc (not= "(detached)" v) (assoc :branch v)))
              (or (str/blank? line) (str/starts-with? line "#")) acc
              :else (update acc :dirty inc)))
          {:dirty 0} (str/split-lines (str out))))

(defn- rev-count [dir & args]
  (some-> (apply git dir "rev-list" "--count" args) parse-long))

(defn git-status
  "`dir`'s checkout from local refs only - never fetches:

    {:sha :branch :dirty  parse-status's
     :base                the default branch: origin/HEAD's, else master or main
     :behind              commits on origin/<base> not in HEAD
     :ahead               commits in HEAD not on origin/<branch> - not on any
                          origin ref when that one doesn't exist (:pushed false)
     :fetched-at          the last fetch (FETCH_HEAD's mtime, worktree's or clone's)}

  nil when `dir` isn't a checkout."
  [dir]
  (when-let [{:keys [sha branch] :as st} (some-> (git dir "status" "--porcelain=v2" "--branch") parse-status)]
    (let [origin "refs/remotes/origin/"
          refs (into {} (keep #(let [[r sym] (str/split % #" ")] (when (seq r) [r sym])))
                     (str/split-lines (or (apply git dir "for-each-ref" "--format=%(refname) %(symref)"
                                                 (cond-> (mapv #(str origin %) ["HEAD" "master" "main"])
                                                   branch (conj (str origin branch))))
                                          "")))
          base (or (some-> (get refs (str origin "HEAD")) not-empty (str/replace origin ""))
                   (first (filter #(contains? refs (str origin %)) ["master" "main"])))
          pushed (and branch (contains? refs (str origin branch)))
          fetched-at (when-let [[own common] (some-> (git dir "rev-parse" "--path-format=absolute" "--git-path" "FETCH_HEAD"
                                                          "--git-common-dir")
                                                     str/split-lines)]
                       (let [t (max (.lastModified (java.io.File. (str own))) (.lastModified (java.io.File. (str common "/FETCH_HEAD"))))]
                         (when (pos? t) t)))]
      (cond-> (assoc st :pushed (boolean pushed))
        base (assoc :base base :behind (rev-count dir (str "HEAD..origin/" base)))
        sha (assoc :ahead (if pushed
                            (rev-count dir (str "origin/" branch "..HEAD"))
                            (rev-count dir "HEAD" "--not" "--remotes=origin")))
        fetched-at (assoc :fetched-at fetched-at)))))

(defn checkout-git
  "git-status of the dir `cfg` runs from; for a :branch worktree (detached at origin/<branch>), also
  :pull - commits origin/<branch> has that it doesn't, as of the last fetch."
  [{:keys [branch local] :as cfg}]
  (let [dir (checkout-dir cfg)]
    (when-let [st (git-status dir)]
      (cond-> st
        (and branch (not local)) (assoc :pull (rev-count dir (str "HEAD..origin/" branch)))))))

(defn rebase-refusal
  "Why `st` (git-status's) can't be rebased onto its default branch here - nil when it can: a branch
  of its own, nothing uncommitted, behind. `worktree?`: zent resets it to origin on each deploy, a
  local rebase there would be undone."
  [{:keys [branch base dirty behind]} worktree?]
  (cond worktree? (str "its own worktree runs origin/" (or branch "<branch>") ": rebase the branch where you work on"
                       " it, push it (--force-with-lease), then pull")
        (not branch) "detached HEAD: no branch to rebase"
        (not base) "no origin/HEAD, origin/master or origin/main to rebase onto"
        (= branch base) (str "on " base " itself")
        (pos? (or dirty 0)) (str dirty " uncommitted file(s): commit or stash them first")
        (not (pos? (or behind 0))) (str "already on origin/" base)))

(defn rebase!
  "Fetches origin's default branch - that one only, no prune: no other origin/* ref moves - then rebases
  `dir`'s branch onto it, only when rebase-refusal has nothing against it and merge-tree sees no
  conflict; a rebase that stops anyway is aborted. {:ok true :message} or {:ok false :message}: what
  happened, or why not."
  [dir worktree?]
  (let [fetch (when-let [base (:base (git-status dir))]
                (shell/sh! ["git" "-C" dir "fetch" "--quiet" "--no-prune" "origin" base]))
        {:keys [branch base behind pushed] :as st} (git-status dir)
        onto (str "origin/" base)
        why (cond (and fetch (not (zero? (:exit fetch)))) (str "git fetch failed: " (str/trim (str (:err fetch))))
                  (nil? st) (str dir " isn't a checkout")
                  :else (or (rebase-refusal st worktree?)
                            (when-not (zero? (:exit (shell/sh! ["git" "-C" dir "merge-tree" "--write-tree" "--quiet" onto "HEAD"])))
                              (str branch " conflicts with " onto ": rebase it by hand"))))]
    (if why
      {:ok false :message why}
      (let [{:keys [exit err]} (shell/sh! ["git" "-C" dir "rebase" "--quiet" onto])]
        (if (zero? exit)
          {:ok true
           :message (str "rebased " branch " onto " onto " (" behind " commit(s)) - undo: git -C " dir " reset --hard ORIG_HEAD"
                         (when pushed (str "; origin/" branch " is now behind it: push --force-with-lease")))}
          (do (shell/sh! ["git" "-C" dir "rebase" "--abort"])
              {:ok false :message (str "the rebase stopped (" (str/trim (str err)) "), aborted: rebase " branch " by hand")}))))))

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
