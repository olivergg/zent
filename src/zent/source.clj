(ns zent.source
  "Workspace-first source resolution: no auto-cloning - fails with the clone command if the repo
  isn't under ~/workspace. A :branch runs, by :from, from a worktree at origin/<branch> (:origin-worktree)
  or at the local <branch> (:local-worktree) - zent.branch - or from the main worktree as it is (:main-worktree)."
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
  "Which code of a :branch runs: :origin-worktree (the default: a worktree at origin/<branch>), :local-worktree (a
  worktree at the local <branch>) or :main-worktree (the main worktree as it is); nil without a :branch."
  [{:keys [branch from]}]
  (when branch (or from :origin-worktree)))

(def worktree-from
  "The :from values that run a worktree of zent's - the rest is the main worktree."
  #{:origin-worktree :local-worktree})

(defn worktree?
  "Whether `cfg` runs from a worktree of its own."
  [cfg]
  (contains? worktree-from (from cfg)))

(defn resolve-source
  ([repo] (resolve-source repo {}))
  ([repo {:keys [workspace-dir branch org] :as opts}]
   ;; resolving leaves the main worktree's files and branches alone: a worktree for :origin-worktree/:local-worktree
   ;; (zent.branch), as it is for :main-worktree, which must have the branch checked out - only checkout! switches it
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

       (not= :main-worktree mode) (branch/resolve-worktree dir repo branch mode)

       (main-clone-on? dir branch) dir

       :else (throw (ex-info (str repo ": :from :main-worktree runs " dir " as it is, which must have " branch " checked out - it has "
                                  (or (current-branch dir) "no branch"))
                             {:dir dir :hint (str "git -C " dir " checkout " branch)}))))))

(defn clone-dir "`cfg`'s main clone, whatever it runs from - no git call." [{:keys [repo workspace-dir]}]
  (main-clone-dir repo workspace-dir))

(defn checkout-dir
  "The dir a component runs from - its worktree for :from :origin-worktree/:local-worktree, else the main worktree -
  WITHOUT resolve-source's side effects (creating, fetching and hard-resetting worktrees): what a
  liveness check or a display reads."
  [{:keys [repo branch workspace-dir] :as cfg}]
  (if (worktree? cfg)
    (branch/worktree-path repo branch)
    (main-clone-dir repo workspace-dir)))

(defn sync-refusal
  "Why a sync (zent's worktree to its ref, then redeploy) means nothing for `cfg` - nil when it runs
  from a worktree."
  [cfg]
  (when-not (worktree? cfg)
    (str "it runs " (checkout-dir cfg) " as it is (" (if (:branch cfg) ":from :main-worktree" "no :branch")
         "): nothing to sync - reload it")))

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
  ;; git processes, ~10 ms each to start: the independent ones at the same time - status (the slow one
  ;; on a big repo), origin's refs, the fetch time; then both counts
  (let [origin "refs/remotes/origin/"
        status (future (some-> (git dir "status" "--porcelain=v2" "--branch") parse-status))
        refs (future (into {} (keep #(let [[r sym] (str/split % #" ")] (when (seq r) [r sym])))
                           (str/split-lines (or (git dir "for-each-ref" "--format=%(refname) %(symref)" origin) ""))))
        fetched-at (future (when-let [[own common] (some-> (git dir "rev-parse" "--path-format=absolute" "--git-path" "FETCH_HEAD"
                                                                "--git-common-dir")
                                                           str/split-lines)]
                             (let [t (max (.lastModified (java.io.File. (str own))) (.lastModified (java.io.File. (str common "/FETCH_HEAD"))))]
                               (when (pos? t) t))))]
    (when-let [{:keys [sha branch] :as st} @status]
      (let [refs @refs
            base (or (some-> (get refs (str origin "HEAD")) not-empty (str/replace origin ""))
                     (first (filter #(contains? refs (str origin %)) ["master" "main"])))
            pushed (and branch (contains? refs (str origin branch)))
            behind (when base (future (rev-count dir (str "HEAD..origin/" base))))
            ahead (when sha (future (if pushed
                                      (rev-count dir (str "origin/" branch "..HEAD"))
                                      (rev-count dir "HEAD" "--not" "--remotes=origin"))))]
        (cond-> (assoc st :pushed (boolean pushed))
          base (assoc :base base :behind @behind)
          sha (assoc :ahead @ahead)
          @fetched-at (assoc :fetched-at @fetched-at))))))

(defn- checked-out-at
  "The checkout (main clone or worktree) of `dir`'s repo that has branch `ref` checked out, or nil."
  [dir ref]
  (some #(when (= ref (:ref %)) (:path %)) (branch/checkouts dir)))

(defn checkout-git
  "git-status of the dir `cfg` runs from, with :from (zent.source/from). For a worktree, also :unsynced -
  commits its ref (origin/<branch> as of the last fetch, or the local branch) has that it doesn't -
  and :unreachable - commits on no branch, which block its next sync (zent.branch/at-risk); for
  :origin-worktree, :trial? when it runs a trial rebase (rebase!); for :local-worktree, :local-ahead and
  :local-behind - the local branch against origin/<branch>, when that exists - and :held-at, the
  checkout that has it checked out, if any."
  [{:keys [branch] :as cfg}]
  (let [dir (checkout-dir cfg)
        mode (from cfg)]
    (when-let [st (git-status dir)]
      (cond-> st
        mode (assoc :from mode)
        (worktree? cfg) (assoc :unsynced (rev-count dir (str "HEAD.." (sync-ref mode branch)))
                               :unreachable (apply rev-count dir branch/unreachable-revs))
        (= :origin-worktree mode) (assoc :trial? (boolean (and (:sha st) (= (:sha st) (git dir "rev-parse" "--verify" "--quiet" (branch/trial-ref branch)))
                                                      (not= (:sha st) (git dir "rev-parse" "--verify" "--quiet" (str "origin/" branch))))))
        (= :local-worktree mode) (merge (when-let [ahead (rev-count dir (str "origin/" branch "..refs/heads/" branch))]
                                 {:local-ahead ahead :local-behind (rev-count dir (str "refs/heads/" branch "..origin/" branch))})
                               (when-let [at (checked-out-at dir (str "refs/heads/" branch))] {:held-at at}))))))

(defn rebase-refusal
  "Why `st` (checkout-git's, :branch the preset's in a worktree) can't be rebased onto its default
  branch here - nil when it can: a branch, behind, for :from :local-worktree held by no other checkout, and
  nothing uncommitted in zent's worktrees (a main worktree's is autostashed). Where it's done depends on
  :from (rebase!)."
  [{:keys [branch base dirty behind held-at from]}]
  (let [worktree? (worktree-from from)]
    (cond (not branch) "detached HEAD: no branch to rebase"
          held-at (str branch " is checked out in " held-at ": rebase it there")
          (not base) "no origin/HEAD, origin/master or origin/main to rebase onto"
          ;; a main worktree on its default branch catches up with origin's (a fast-forward unless it has commits of its own)
          (and worktree? (= branch base)) (str "on " base " itself")
          (and worktree? (pos? (or dirty 0))) (str dirty " uncommitted file(s): commit or stash them first")
          (not (pos? (or behind 0))) (str "already on origin/" base))))

(defn merge-conflicts
  "What rebasing `tip` onto `onto` in `dir`'s repo would conflict on, by git merge-tree (no checkout
  touched): nil when it merges clean, else the conflicted paths - [] when merge-tree failed outright."
  [dir onto tip]
  (let [{:keys [exit out]} (shell/sh! ["git" "-C" dir "merge-tree" "--write-tree" "--name-only" "--no-messages" onto tip])]
    (when-not (zero? exit)
      (if (= 1 exit) (vec (distinct (rest (remove str/blank? (str/split-lines (str out)))))) []))))

(defn- run-rebase!
  "`git rebase onto` in `dir` - `--autostash` if `autostash?` - aborted if it stops: {:failed why}
  or {:stash-conflict? true} when the rebase went through but the autostash didn't apply back
  cleanly (git keeps it in the stash), else {}."
  [dir onto what & [autostash?]]
  (let [stash #(git dir "rev-parse" "--verify" "--quiet" "refs/stash")
        before (stash)
        {:keys [exit err]} (shell/sh! (cond-> ["git" "-C" dir "rebase" "--quiet"] autostash? (conj "--autostash") true (conj onto)))]
    (cond
      (not (zero? exit))
      (do (shell/sh! ["git" "-C" dir "rebase" "--abort"])
          {:failed (str "the rebase stopped (" (str/trim (str err)) "), aborted: rebase " what " by hand")})
      ;; an autostash that applies back is dropped: a new stash entry left means it conflicted (git's
      ;; message is localised, the ref isn't)
      (not= before (stash)) {:stash-conflict? true}
      :else {})))

(defn rebase!
  "Fetches origin's default branch - that one only, no prune: no other origin/* ref moves - then,
  when rebase-refusal has nothing against it and merge-tree sees no conflict, rebases onto it, by
  `cfg`'s :from:

    no :branch, :main-worktree  the main worktree's branch, its uncommitted files autostashed
    :local-worktree             the local <branch>, checked out in zent's worktree for the rebase (refused
                                when another checkout holds it), the worktree detached again after
    :origin-worktree            a trial rebase: the worktree's detached HEAD, no branch moved, kept under
                                zent.branch/trial-ref until the next sync drops it

  A rebase that stops anyway is aborted. {:ok true :message} or {:ok false :message}: what
  happened, the undo included, or why not. Never pushes."
  [{:keys [branch] :as cfg}]
  (let [mode (from cfg)
        dir (checkout-dir cfg)
        fetch (when-let [base (:base (git-status dir))]
                (shell/sh! ["git" "-C" dir "fetch" "--quiet" "--no-prune" "origin" base]))
        st (some-> (git-status dir) (assoc :from mode) (cond-> (worktree? cfg) (assoc :branch branch)))
        onto (str "origin/" (:base st))
        local (str "refs/heads/" branch)
        tip (if (= :local-worktree mode) local "HEAD")
        st (cond-> st (and st (= :local-worktree mode)) (assoc :behind (rev-count dir (str local ".." onto))
                                                      :held-at (checked-out-at dir local)))
        {:keys [behind pushed]} st
        why (cond (and fetch (not (zero? (:exit fetch)))) (str "git fetch failed: " (str/trim (str (:err fetch))))
                  (nil? st) (str dir " isn't a checkout")
                  (and (= :local-worktree mode) (not (git dir "rev-parse" "--verify" "--quiet" local)))
                  (str "no local branch " branch " to rebase")
                  :else (or (rebase-refusal st)
                            (when-let [files (merge-conflicts dir onto tip)]
                              (str (:branch st) " conflicts with " onto (when (seq files) (str " on " (str/join ", " files)))
                                   ": rebase it by hand"))))
        done (str " onto " onto " (" behind " commit(s))")]
    (cond
      why {:ok false :message why}

      (= :local-worktree mode)
      (let [old (git dir "rev-parse" local)
            head (git dir "rev-parse" "HEAD")
            {:keys [exit err]} (shell/sh! ["git" "-C" dir "checkout" "--quiet" branch])]
        (if-not (zero? exit)
          {:ok false :message (str branch " can't be checked out in zent's worktree (" (str/trim (str err)) "): rebase it in your main worktree")}
          (let [{:keys [failed]} (run-rebase! dir onto branch)]
            (shell/sh! (cond-> ["git" "-C" dir "checkout" "--quiet" "--detach"] failed (conj head)))
            (if failed
              {:ok false :message failed}
              {:ok true
               :undo ["git" "-C" (main-clone-dir (:repo cfg) (:workspace-dir cfg)) "branch" "-f" branch old]
               :message (str "rebased " branch done " - undo: git -C " (main-clone-dir (:repo cfg) (:workspace-dir cfg))
                             " branch -f " branch " " old
                             (when (git dir "rev-parse" "--verify" "--quiet" (str "refs/remotes/origin/" branch))
                               (str "; origin/" branch " is now behind it: push --force-with-lease")))}))))

      (= :origin-worktree mode)
      (if-let [failed (:failed (run-rebase! dir onto (str "origin/" branch)))]
        {:ok false :message failed}
        (do (shell/sh-or-throw! ["git" "-C" dir "update-ref" (branch/trial-ref branch) "HEAD"])
            {:ok true
             :message (str "running origin/" branch " rebased" done " in its worktree: a trial rebase, no branch moved"
                           " - the next sync reverts it")}))

      :else
      (let [own (rev-count dir (str onto "..HEAD"))
            {:keys [failed stash-conflict?]} (run-rebase! dir onto (:branch st) true)]
        (if failed
          {:ok false :message failed}
          {:ok true
           ;; ORIG_HEAD is the commit before the rebase; --keep refuses rather than lose an uncommitted change
           :undo ["git" "-C" dir "reset" "--quiet" "--keep" (git dir "rev-parse" "ORIG_HEAD")]
           :message (str "rebased " (:branch st) done
                         (when (pos? (or (:dirty st) 0))
                           (if stash-conflict?
                             (str "; your " (:dirty st) " uncommitted file(s) conflict with it: kept in the stash, git -C "
                                  dir " stash pop to resolve")
                             (str ", your " (:dirty st) " uncommitted file(s) put back")))
                         " - undo: git -C " dir " reset --keep ORIG_HEAD"
                         ;; only commits of its own were rewritten: a fast-forward leaves origin's branch as it was
                         (when (and pushed (pos? (or own 0)))
                           (str "; origin/" (:branch st) " is now behind it: push --force-with-lease")))})))))

(defn checkout-refusal
  "Why the main worktree of `st` (checkout-git's, with the preset's :wanted-branch) can't be switched to
  that branch - nil when it can: :from :main-worktree, on another branch, nothing uncommitted (untracked
  files included: git might carry them over, zent doesn't guess)."
  [{:keys [from wanted-branch branch dirty]}]
  (cond (not= :main-worktree from) "only :from :main-worktree runs the main worktree's own branch: nothing to check out"
        (= wanted-branch branch) (str "already on " branch)
        (pos? (or dirty 0)) (str dirty " uncommitted file(s): commit or stash them first")))

(defn checkout!
  "Checks the preset's :branch out in `cfg`'s main clone, when checkout-refusal has nothing against
  it - fetched from origin first (that branch only, no prune) when there's no local one. Every
  component running that clone moves with it. {:ok true :message} or {:ok false :message}: what
  happened, the undo included, or why not."
  [{:keys [branch] :as cfg}]
  (let [dir (checkout-dir cfg)
        st (git-status dir)
        why (if st
              (checkout-refusal (assoc st :from (from cfg) :wanted-branch branch))
              (str dir " isn't a checkout"))]
    (if why
      {:ok false :message why}
      (do (when-not (git dir "rev-parse" "--verify" "--quiet" (str "refs/heads/" branch))
            (shell/sh! ["git" "-C" dir "fetch" "--quiet" "--no-prune" "origin" branch]))
          (let [{:keys [exit err]} (shell/sh! ["git" "-C" dir "checkout" "--quiet" branch "--"])]
            (if (zero? exit)
              {:ok true
               :undo (into ["git" "-C" dir "checkout" "--quiet"] (if (:branch st) [(:branch st) "--"] ["--detach" (:sha st)]))
               :message (str "checked out " branch " - undo: git -C " dir " checkout " (or (:branch st) (:sha st)))}
              {:ok false :message (str branch " can't be checked out (" (str/trim (str err)) ")")}))))))

(def ^:private no-prompt
  "A git that needs credentials fails rather than waiting on a prompt nobody sees."
  {"GIT_TERMINAL_PROMPT" "0"})

(defn fetch!
  "`git fetch origin` in `dir`: every origin/* ref, none pruned, no local branch moved. nil, or why it failed."
  [dir]
  (let [{:keys [exit err]} (shell/sh! ["git" "-C" dir "fetch" "--quiet" "--no-prune" "origin"] :env no-prompt)]
    (when-not (zero? exit) (str/trim (str err)))))

(defn push-refusal
  "Why the local `branch` can't be pushed - nil when it can: a branch, not the default one, with commits
  origin doesn't have."
  [{:keys [branch base sha ahead]}]
  (cond (not branch) "detached HEAD: no branch to push"
        (some #{branch} ["main" "master" base]) (str branch " is the default branch: push it by hand")
        (not sha) (str "no local branch " branch)
        (not (pos? (or ahead 0))) (str branch " has nothing origin doesn't")))

(defn push!
  "Pushes the local branch `cfg` runs (its :branch, else what its main clone has out) to origin: a plain
  push, or --force-with-lease pinned to origin's commit when origin has commits it doesn't (a rebase) -
  never the default branch. {:ok :message}."
  [cfg]
  (let [dir (main-clone-dir (:repo cfg) (:workspace-dir cfg))
        st (git-status dir)
        branch (or (:branch cfg) (:branch st))
        sha (when branch (git dir "rev-parse" "--verify" "--quiet" (str "refs/heads/" branch)))
        theirs (when sha (git dir "rev-parse" "--verify" "--quiet" (str "refs/remotes/origin/" branch)))
        ahead (when sha (if theirs (rev-count dir (str theirs ".." sha)) (rev-count dir sha "--not" "--remotes=origin")))
        behind (if theirs (rev-count dir (str sha ".." theirs)) 0)
        why (push-refusal {:branch branch :base (:base st) :sha sha :ahead ahead})]
    (if why
      {:ok false :message why}
      (let [force? (pos? behind)
            {:keys [exit err]} (shell/sh! (-> ["git" "-C" dir "push" "--quiet"]
                                              (cond-> force? (conj (str "--force-with-lease=refs/heads/" branch ":" theirs)))
                                              (conj "origin" (str "refs/heads/" branch ":refs/heads/" branch)))
                                          :env no-prompt)]
        (if (zero? exit)
          {:ok true :message (str "pushed " branch ", " ahead " commit(s)"
                                  (when force? (str ", replacing " behind " on origin (force-with-lease)")))}
          {:ok false :message (str "the push failed: " (str/trim (str err)))})))))

(defn fast-forward!
  "Fast-forwards the branch `cfg`'s main worktree has out to origin/<it>: refused when detached, when it has
  commits origin doesn't (rebase it by hand), or when there's nothing to take. {:ok :message :undo}."
  [cfg]
  (let [dir (main-clone-dir (:repo cfg) (:workspace-dir cfg))
        {:keys [branch sha]} (git-status dir)
        theirs (when branch (git dir "rev-parse" "--verify" "--quiet" (str "refs/remotes/origin/" branch)))
        behind (when theirs (rev-count dir (str "HEAD.." theirs)))
        ahead (when theirs (rev-count dir (str theirs "..HEAD")))
        why (cond (not branch) "detached HEAD: nothing to fast-forward"
                  (not theirs) (str "no origin/" branch " to fast-forward to")
                  (pos? ahead) (str branch " has " ahead " commit(s) origin doesn't: rebase it by hand")
                  (not (pos? behind)) (str branch " is already on origin/" branch))]
    (if why
      {:ok false :message why}
      (let [{:keys [exit err]} (shell/sh! ["git" "-C" dir "merge" "--quiet" "--ff-only" theirs])]
        (if (zero? exit)
          {:ok true
           ;; --keep: what's uncommitted stays
           :undo ["git" "-C" dir "reset" "--quiet" "--keep" sha]
           :message (str "fast-forwarded " branch " to origin/" branch ", " behind " commit(s) - undo: git -C " dir
                         " reset --keep " (subs sha 0 7))}
          {:ok false :message (str "the fast-forward stopped: " (str/trim (str err)))})))))

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
