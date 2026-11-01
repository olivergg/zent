(ns zent.branch
  "Per-branch git worktrees, under ~/.cache/zent/worktrees/, detached at origin/<branch> (:from
  :origin, the branch pushed) or at the local refs/heads/<branch> (:from :local, no push needed).
  Detached: no local branch is ever moved or held, so the main clone stays free to check <branch>
  out. A sync (reset --hard) never drops work: it refuses a worktree with uncommitted files or
  commits no ref keeps (unreachable)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [zent.shell :as shell]))

(defn worktrees-root [] (str (System/getenv "HOME") "/.cache/zent/worktrees"))

(defn worktree-path
  "Where `repo-name`'s worktree for `branch` lives (`/` in the branch -> `_`).
  Pure, so testable without the filesystem."
  [repo-name branch]
  (str (worktrees-root) "/" repo-name "-" (str/replace branch "/" "_")))

(defn test-ref
  "The ref an :origin worktree's local test rebase (zent.source/rebase!) is kept under: the
  unreachable check spares it, the next sync drops it."
  [branch]
  (str "refs/zent/test/" branch))

;; One lock per main clone: components deploy in parallel, and two git
;; commands writing one repo's refs at once fail on its lock files.
(defonce ^:private repo-locks (atom {}))

(defn- repo-lock [dir]
  (get (swap! repo-locks update dir #(or % (Object.))) dir))

(def ^:dynamic *sync*
  "Whether resolve-worktree brings an existing worktree to its ref: an apply does, a reload
  restarts the commit already there (zent.engine binds it), a pull does again."
  true)

(defn git
  "A git read: `git -C dir args`'s trimmed stdout, nil on failure. --no-optional-locks: a
  background `status` mustn't take the index lock from the user's own git."
  [dir & args]
  (let [{:keys [exit out]} (shell/sh! (into ["git" "--no-optional-locks" "-C" dir] args))]
    (when (zero? exit) (str/trim (str out)))))

(defn sync-ref
  "The ref a worktree mode syncs to."
  [mode branch]
  (if (= :local mode) (str "refs/heads/" branch) (str "origin/" branch)))

(def unreachable-revs
  "rev-list's args for the commits HEAD has that no branch, remote ref or refs/zent/* ref keeps."
  ["HEAD" "--not" "--branches" "--remotes" "--glob=refs/zent/*"])

(defn- lines [s] (remove str/blank? (str/split-lines s)))

(defn at-risk
  "{:dirty n :unreachable n} of worktree `dir` - what a reset --hard would lose: files `git status`
  shows (untracked included, ignored ones not), unreachable-revs. nil where git can't tell, never 0."
  [dir]
  {:dirty (some-> (git dir "status" "--porcelain") lines count)
   :unreachable (some-> (apply git dir "rev-list" unreachable-revs) lines count)})

(defn- refuse-if-at-risk!
  "Throws, with how to keep it, when resetting `dir` would lose work - or when git can't tell."
  [dir branch]
  (let [{:keys [dirty unreachable]} (at-risk dir)]
    (when-not (and dirty unreachable)
      (throw (ex-info (str "can't tell what worktree " dir " holds: not syncing it")
                      {:dir dir :hint (str "git -C " dir " status")})))
    (when (pos? (+ dirty unreachable))
      (throw (ex-info (str "worktree " dir " holds "
                           (str/join " and " (cond-> []
                                               (pos? dirty) (conj (str dirty " uncommitted file(s)"))
                                               (pos? unreachable) (conj (str unreachable " commit(s) on no branch"))))
                           ": not syncing it (reset --hard would lose them)")
                      {:dir dir :dirty dirty :unreachable unreachable
                       :hint (if (pos? unreachable)
                               (str "git -C " dir " branch rescue/" branch " HEAD")
                               (str "git -C " dir " stash"))})))))

(defn- local-ref!
  "refs/heads/<branch> of `clone`, or throws saying how to make it."
  [clone branch]
  (let [ref (sync-ref :local branch)]
    (when-not (git clone "rev-parse" "--verify" "--quiet" (str ref "^{commit}"))
      (throw (ex-info (str "no local branch " branch " in " clone " (:from :local runs it)")
                      {:dir clone :hint (str "git -C " clone " branch " branch " origin/" branch ", or :from :origin")})))
    ref))

(defn resolve-worktree
  "Creates `repo-name`'s worktree for `branch` from `main-clone-dir` if missing, then - when
  *sync* - resets it to origin/<branch> (`from` :origin, the default, fetched first) or to the
  local <branch> (:local, no fetch); returns its path. A failed creation throws, so does a sync
  that would lose work; a failed fetch (offline) keeps the worktree as it is."
  ([main-clone-dir repo-name branch] (resolve-worktree main-clone-dir repo-name branch :origin))
  ([main-clone-dir repo-name branch from]
   (locking (repo-lock main-clone-dir)
     (let [worktree-dir (worktree-path repo-name branch)
           exists? (.exists (io/file worktree-dir))
           sync? (or *sync* (not exists?))
           local? (= :local from)
           ref (if local? (when sync? (local-ref! main-clone-dir branch)) (sync-ref from branch))
           shown (if local? branch ref)
           ;; the worktrees share the main clone's refs: fetching there updates origin/<branch> for both
           fetched? (or local? (not sync?)
                        (zero? (:exit (shell/sh! ["git" "-C" main-clone-dir "fetch" "origin" branch]))))]
       (if exists?
         (do
           ;; one made before worktrees were detached still holds <branch>: let it go first - reset --hard
           ;; would move it, and git would keep the main clone from checking it out
           (shell/sh-or-throw! ["git" "-C" worktree-dir "checkout" "--quiet" "--detach"])
           (cond
             (not *sync*) (println (str "Keeping worktree " worktree-dir " at its commit (`zent pull` brings " shown ")"))
             fetched? (do (refuse-if-at-risk! worktree-dir branch)
                          (println (str "Syncing worktree " worktree-dir " to " shown))
                          (shell/sh-or-throw! ["git" "-C" worktree-dir "reset" "--quiet" "--hard" ref])
                          (shell/sh! ["git" "-C" worktree-dir "update-ref" "-d" (test-ref branch)]))
             :else (println (str "fetch of " ref " failed, keeping " worktree-dir " as is"))))
         (do (println (str "Creating worktree for " repo-name "@" branch " -> " worktree-dir " (detached at " shown ")"))
             (shell/sh-or-throw! ["git" "-C" main-clone-dir "worktree" "add" "--detach" worktree-dir ref])))
       worktree-dir))))

(defn worktrees
  "Each dir under worktrees-root, as git sees it: {:path :clone (its main clone) :repo (that
  clone's dir name) :head (short sha) & at-risk's :dirty :unreachable}; :clone nil when it isn't
  a worktree any more."
  []
  (for [^java.io.File f (sort-by #(.getPath ^java.io.File %) (some-> (io/file (worktrees-root)) .listFiles seq))
        :when (.isDirectory f)
        :let [path (.getPath f)
              ;; its own checkout's top, not some repo enclosing a leftover dir
              own? (= (.getCanonicalPath f) (some-> (git path "rev-parse" "--show-toplevel") io/file .getCanonicalPath))
              clone (when own? (some-> (git path "rev-parse" "--path-format=absolute" "--git-common-dir") io/file .getParent))]]
    (merge {:path path :clone clone :repo (some-> clone io/file .getName) :head (git path "rev-parse" "--short" "HEAD")}
           (when clone (at-risk path)))))

(defn prune!
  "Removes the `entries` (worktrees') of `repos` - the root is the machine's, another catalog's stay -
  no preset uses - `users`: {path #{preset}} - and that hold nothing at-risk would lose, through the
  main clone's `git worktree remove`, then `worktree prune`; a local test's ref (test-ref) at its HEAD
  goes with it. {:removed [entry] :kept [[entry why]]}."
  [entries users repos]
  (reduce (fn [acc {:keys [path clone repo dirty unreachable] :as e}]
            (let [why (cond (nil? clone) "not a git worktree"
                            (not (contains? repos repo)) (str "repo " repo " isn't in this catalog")
                            (seq (get users path)) (str "used by " (str/join ", " (map name (sort (get users path)))))
                            (not (and dirty unreachable)) "can't tell what it holds"
                            (pos? dirty) (str dirty " uncommitted file(s)")
                            (pos? unreachable) (str unreachable " commit(s) on no branch"))
                  removed (when-not why
                            (locking (repo-lock clone)
                              (let [test-refs (some-> (git path "for-each-ref" "--format=%(refname)" "--points-at" "HEAD" "refs/zent/test/")
                                                      lines)
                                    {:keys [exit err]} (shell/sh! ["git" "-C" clone "worktree" "remove" path])]
                                (shell/sh! ["git" "-C" clone "worktree" "prune"])
                                (if (zero? exit)
                                  (do (doseq [r test-refs] (shell/sh! ["git" "-C" clone "update-ref" "-d" r]))
                                      :ok)
                                  (str "git worktree remove failed: " (str/trim (str err)))))))]
              (if (= :ok removed)
                (update acc :removed conj e)
                (update acc :kept conj [e (or why removed)]))))
          {:removed [] :kept []} entries))
