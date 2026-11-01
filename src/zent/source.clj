(ns zent.source
  "Workspace-first source resolution: no auto-cloning - fails with the clone command if the repo
  isn't under ~/workspace. A :branch runs, by :from, from a worktree at origin/<branch> (:origin)
  or at the local <branch> (:local) - zent.branch - or from the main clone as it is (:clone)."
  (:require [clojure.string :as str]
            [zent.branch :as branch :refer [git sync-ref]]
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

(defn from
  "Where a :branch runs from: :origin (the default: a worktree at origin/<branch>), :local (a
  worktree at the local <branch>) or :clone (the main clone as it is); nil without a :branch."
  [{:keys [branch from]}]
  (when branch (or from :origin)))

(defn worktree?
  "Whether `cfg` runs from a worktree of its own."
  [cfg]
  (contains? #{:origin :local} (from cfg)))

(defn resolve-source
  ([repo] (resolve-source repo {}))
  ([repo {:keys [workspace-dir branch org] :as opts}]
   ;; resolving leaves the main clone's files and branches alone: a worktree for :origin/:local
   ;; (zent.branch), as it is for :clone, which must have the branch checked out - zent never switches it
   (let [dir (main-clone-dir repo workspace-dir)
         mode (from opts)]
     (cond
       (not (.isDirectory (java.io.File. dir)))
       ;; the hint's org comes from the catalog - the engine doesn't know it
       (throw (ex-info (str "Repository not found: " repo)
                       {:expected dir
                        :hint (str "git clone git@github.com:" (or org "<your-org>")
                                   "/" repo ".git " dir)}))

       (not mode) dir

       (not= :clone mode) (branch/resolve-worktree dir repo branch mode)

       (main-clone-on? dir branch) dir

       :else (throw (ex-info (str repo ": :from :clone runs " dir " as it is, which must have " branch " checked out - it has "
                                  (or (current-branch dir) "no branch"))
                             {:dir dir :hint (str "git -C " dir " checkout " branch)}))))))

(defn checkout-dir
  "The dir a component runs from - its worktree for :from :origin/:local, else the main clone -
  WITHOUT resolve-source's side effects (creating, fetching and hard-resetting worktrees): what a
  liveness check or a display reads."
  [{:keys [repo branch workspace-dir] :as cfg}]
  (if (worktree? cfg)
    (branch/worktree-path repo branch)
    (main-clone-dir repo workspace-dir)))

(defn pull-refusal
  "Why a pull (sync, then redeploy) means nothing for `cfg` - nil when it runs from a worktree."
  [cfg]
  (when-not (worktree? cfg)
    (str "it runs " (checkout-dir cfg) " as it is (" (if (:branch cfg) ":from :clone" "no :branch")
         "): nothing to pull - reload it")))

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

(defn- checked-out-at
  "The checkout (main clone or worktree) of `dir`'s repo that has branch `ref` checked out, or nil."
  [dir ref]
  (->> (str/split (or (git dir "worktree" "list" "--porcelain") "") #"\n\n")
       (some #(when (str/includes? (str % "\n") (str "\nbranch " ref "\n"))
                (subs (first (str/split-lines %)) (count "worktree "))))))

(defn checkout-git
  "git-status of the dir `cfg` runs from, with :from (zent.source/from). For a worktree, also :pull -
  commits its ref (origin/<branch> as of the last fetch, or the local branch) has that it doesn't -
  and :unreachable - commits on no branch, which block its next sync (zent.branch/at-risk); for
  :origin, :test? when it runs a local test rebase (rebase!); for :local, :local-ahead and
  :local-behind - the local branch against origin/<branch>, when that exists - and :held-at, the
  checkout that has it checked out, if any."
  [{:keys [branch] :as cfg}]
  (let [dir (checkout-dir cfg)
        mode (from cfg)]
    (when-let [st (git-status dir)]
      (cond-> st
        mode (assoc :from mode)
        (worktree? cfg) (assoc :pull (rev-count dir (str "HEAD.." (sync-ref mode branch)))
                               :unreachable (apply rev-count dir branch/unreachable-revs))
        (= :origin mode) (assoc :test? (boolean (and (:sha st) (= (:sha st) (git dir "rev-parse" "--verify" "--quiet" (branch/test-ref branch)))
                                                      (not= (:sha st) (git dir "rev-parse" "--verify" "--quiet" (str "origin/" branch))))))
        (= :local mode) (merge (when-let [ahead (rev-count dir (str "origin/" branch "..refs/heads/" branch))]
                                 {:local-ahead ahead :local-behind (rev-count dir (str "refs/heads/" branch "..origin/" branch))})
                               (when-let [at (checked-out-at dir (str "refs/heads/" branch))] {:held-at at}))))))

(defn rebase-refusal
  "Why `st` (checkout-git's, :branch the preset's in a worktree) can't be rebased onto its default
  branch here - nil when it can: a branch, nothing uncommitted, behind, for :from :local held by no
  other checkout. Where it's done depends on :from (rebase!)."
  [{:keys [branch base dirty behind held-at]}]
  (cond (not branch) "detached HEAD: no branch to rebase"
        held-at (str branch " is checked out in " held-at ": rebase it there")
        (not base) "no origin/HEAD, origin/master or origin/main to rebase onto"
        (= branch base) (str "on " base " itself")
        (pos? (or dirty 0)) (str dirty " uncommitted file(s): commit or stash them first")
        (not (pos? (or behind 0))) (str "already on origin/" base)))

(defn- run-rebase!
  "`git rebase onto` in `dir`, aborted if it stops: nil, or why it failed."
  [dir onto what]
  (let [{:keys [exit err]} (shell/sh! ["git" "-C" dir "rebase" "--quiet" onto])]
    (when-not (zero? exit)
      (shell/sh! ["git" "-C" dir "rebase" "--abort"])
      (str "the rebase stopped (" (str/trim (str err)) "), aborted: rebase " what " by hand"))))

(defn rebase!
  "Fetches origin's default branch - that one only, no prune: no other origin/* ref moves - then,
  when rebase-refusal has nothing against it and merge-tree sees no conflict, rebases onto it, by
  `cfg`'s :from:

    no :branch, :clone  the main clone's branch
    :local              the local <branch>, checked out in zent's worktree for the rebase (refused
                        when another checkout holds it), the worktree detached again after
    :origin             a local test: the worktree's detached HEAD, no branch moved, kept under
                        zent.branch/test-ref until the next sync drops it

  A rebase that stops anyway is aborted. {:ok true :message} or {:ok false :message}: what
  happened, the undo included, or why not. Never pushes."
  [{:keys [branch] :as cfg}]
  (let [mode (from cfg)
        dir (checkout-dir cfg)
        fetch (when-let [base (:base (git-status dir))]
                (shell/sh! ["git" "-C" dir "fetch" "--quiet" "--no-prune" "origin" base]))
        st (some-> (git-status dir) (cond-> (worktree? cfg) (assoc :branch branch)))
        onto (str "origin/" (:base st))
        local (str "refs/heads/" branch)
        tip (if (= :local mode) local "HEAD")
        st (cond-> st (and st (= :local mode)) (assoc :behind (rev-count dir (str local ".." onto))
                                                      :held-at (checked-out-at dir local)))
        {:keys [behind pushed]} st
        why (cond (and fetch (not (zero? (:exit fetch)))) (str "git fetch failed: " (str/trim (str (:err fetch))))
                  (nil? st) (str dir " isn't a checkout")
                  (and (= :local mode) (not (git dir "rev-parse" "--verify" "--quiet" local)))
                  (str "no local branch " branch " to rebase")
                  :else (or (rebase-refusal st)
                            (when-not (zero? (:exit (shell/sh! ["git" "-C" dir "merge-tree" "--write-tree" "--quiet" onto tip])))
                              (str (:branch st) " conflicts with " onto ": rebase it by hand"))))
        done (str " onto " onto " (" behind " commit(s))")]
    (cond
      why {:ok false :message why}

      (= :local mode)
      (let [old (git dir "rev-parse" local)
            head (git dir "rev-parse" "HEAD")
            {:keys [exit err]} (shell/sh! ["git" "-C" dir "checkout" "--quiet" branch])]
        (if-not (zero? exit)
          {:ok false :message (str branch " can't be checked out in zent's worktree (" (str/trim (str err)) "): rebase it in the clone")}
          (let [failed (run-rebase! dir onto branch)]
            (shell/sh! (cond-> ["git" "-C" dir "checkout" "--quiet" "--detach"] failed (conj head)))
            (if failed
              {:ok false :message failed}
              {:ok true
               :message (str "rebased " branch done " - undo: git -C " (main-clone-dir (:repo cfg) (:workspace-dir cfg))
                             " branch -f " branch " " old
                             (when (git dir "rev-parse" "--verify" "--quiet" (str "refs/remotes/origin/" branch))
                               (str "; origin/" branch " is now behind it: push --force-with-lease")))}))))

      (= :origin mode)
      (if-let [failed (run-rebase! dir onto (str "origin/" branch))]
        {:ok false :message failed}
        (do (shell/sh-or-throw! ["git" "-C" dir "update-ref" (branch/test-ref branch) "HEAD"])
            {:ok true
             :message (str "testing origin/" branch " rebased" done " in its worktree: a local test, no branch moved"
                           " - the next apply or pull reverts it")}))

      :else
      (if-let [failed (run-rebase! dir onto (:branch st))]
        {:ok false :message failed}
        {:ok true
         :message (str "rebased " (:branch st) done " - undo: git -C " dir " reset --hard ORIG_HEAD"
                       (when pushed (str "; origin/" (:branch st) " is now behind it: push --force-with-lease")))}))))

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
