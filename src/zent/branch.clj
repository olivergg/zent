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

(defn- out [dir & args]
  (let [{:keys [exit out]} (shell/sh! (into ["git" "-C" dir] args))]
    (when (zero? exit) (str/trim (str out)))))

(defn at-risk
  "{:dirty n :unreachable n} of worktree `dir`: files `git status` shows (untracked included,
  ignored ones not), commits HEAD has that no branch, remote ref or refs/zent/* ref keeps - what
  a reset --hard would lose."
  [dir]
  {:dirty (count (remove str/blank? (str/split-lines (or (out dir "status" "--porcelain") ""))))
   :unreachable (count (remove str/blank? (str/split-lines (or (out dir "rev-list" "HEAD" "--not" "--branches" "--remotes"
                                                                    "--glob=refs/zent/*")
                                                               ""))))})

(defn- refuse-if-at-risk!
  "Throws, with how to keep it, when resetting `dir` would lose work."
  [dir branch]
  (let [{:keys [dirty unreachable]} (at-risk dir)]
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
  (let [ref (str "refs/heads/" branch)]
    (when-not (out clone "rev-parse" "--verify" "--quiet" (str ref "^{commit}"))
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
           ref (if local? (when sync? (local-ref! main-clone-dir branch)) (str "origin/" branch))
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
              own? (= (.getCanonicalPath f) (some-> (out path "rev-parse" "--show-toplevel") io/file .getCanonicalPath))
              clone (when own? (some-> (out path "rev-parse" "--path-format=absolute" "--git-common-dir") io/file .getParent))]]
    (merge {:path path :clone clone :repo (some-> clone io/file .getName) :head (out path "rev-parse" "--short" "HEAD")}
           (when clone (at-risk path)))))

(defn prune!
  "Removes the `entries` (worktrees') no preset uses - `users`: {path #{preset}} - and that hold
  nothing at-risk would lose, through the main clone's `git worktree remove`, then `worktree prune`.
  {:removed [entry] :kept [[entry why]]}."
  [entries users]
  (reduce (fn [acc {:keys [path clone dirty unreachable] :as e}]
            (let [why (cond (nil? clone) "not a git worktree"
                            (seq (get users path)) (str "used by " (str/join ", " (map name (sort (get users path)))))
                            (pos? dirty) (str dirty " uncommitted file(s)")
                            (pos? unreachable) (str unreachable " commit(s) on no branch"))
                  removed (when-not why
                            (locking (repo-lock clone)
                              (let [{:keys [exit err]} (shell/sh! ["git" "-C" clone "worktree" "remove" path])]
                                (shell/sh! ["git" "-C" clone "worktree" "prune"])
                                (if (zero? exit) :ok (str "git worktree remove failed: " (str/trim (str err)))))))]
              (if (= :ok removed)
                (update acc :removed conj e)
                (update acc :kept conj [e (or why removed)]))))
          {:removed [] :kept []} entries))
