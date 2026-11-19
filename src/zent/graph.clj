(ns zent.graph
  "A component's repo drawn the way a git graph tool draws it, for the dashboard's Branches tab: the
  refs that matter - the default branch and the preset's, local and on origin, the clone's HEAD, zent's
  worktrees - and the commits between them, newest first. `read-repo` reads (git reads only, never a
  fetch); `layout` gives each commit its column and the lines into its row."
  (:require [clojure.string :as str]
            [zent.branch :as branch :refer [git]]
            [zent.shell :as shell]
            [zent.source :as source]))

(def max-commits
  "Rows per repo: past it the graph stops (:truncated) - a default branch far ahead stays readable."
  300)

(defn- free-slot [lanes] (or (first (keep-indexed (fn [j l] (when (nil? l) j)) lanes)) (count lanes)))
(defn- put [lanes j l] (assoc (cond-> lanes (= j (count lanes)) (conj nil)) j l))

(defn layout
  "`commits` ([{:sha :parents}], newest first, topo order) with each one's :col and :segs - the lines
  coming into its row, [column above, column here] - as git graph tools lay them out: a commit takes
  the lane waiting for it (else the first free one), its first parent continues that lane, any other
  opens one. Lanes waiting for the same commit join on its row. Parents outside `commits` are dropped:
  no line runs off the bottom."
  [commits]
  (let [known (set (map :sha commits))]
    (first
     (reduce (fn [[rows lanes] {:keys [sha parents] :as c}]
               (let [col (or (first (keep-indexed (fn [j l] (when (= sha (:sha l)) j)) lanes)) (free-slot lanes))
                     segs (vec (keep-indexed (fn [j l] (when l [(:from l) (if (= sha (:sha l)) col j)])) lanes))
                     [p & more] (filter known parents)
                     ;; the lanes going on through this row, at their own column
                     through (vec (map-indexed (fn [j l] (when (and l (not= sha (:sha l))) (assoc l :from j))) lanes))
                     lanes (reduce (fn [ls q] (put ls (free-slot ls) {:sha q :from col}))
                                   (put through col (when p {:sha p :from col}))
                                   more)]
                 [(conj rows (assoc c :col col :segs segs))
                  (loop [ls lanes] (if (and (seq ls) (nil? (peek ls))) (recur (pop ls)) ls))]))
             [[] []] commits))))

(defn- dirty-files
  "`dir`'s modified and untracked paths, 30 at most - not through `git`, which trims the first line's
  leading status column."
  [dir]
  (->> (:out (shell/sh! ["git" "--no-optional-locks" "-C" dir "status" "--porcelain"]))
       branch/lines (map #(subs % 3)) (take 30) vec))

(defn- canonical [path] (.getCanonicalPath (java.io.File. (str path))))

(defn- ahead-behind
  "Commits on `a` not on `b`, and the other way round."
  [dir a b]
  (when-let [[x y] (some-> (git dir "rev-list" "--left-right" "--count" (str a "..." b)) (str/split #"\s+"))]
    {:ahead (parse-long x) :behind (parse-long y)}))

(def ^:private field (re-pattern (str (char 31))))

(defn read-repo
  "`clone`'s graph around `branch` (the preset's, or nil) and `worktree` (the checkout zent runs, when
  not the clone):

    {:base      the default branch
     :head      the clone's checkout: {:sha :branch :dirty [path]}
     :worktree  zent's worktree it runs, if any: {:path :sha :dirty [path]}
     :others    zent's other worktrees of this repo: [{:path :sha}]
     :refs      [{:name :branch :sha :remote?}], a local branch with its :ahead/:behind against
                origin/<it> when that exists
     :commits   layout's rows, each also :author :time (ms) :subject
     :truncated more commits than max-commits
     :fetched-at}

  The commits: what the refs reach, down to five below where they all meet. nil when `clone` isn't a
  checkout."
  [clone {:keys [branch worktree]}]
  ;; git processes, ~10 ms each to start: what doesn't wait on another runs at the same time - the
  ;; status, every branch's commit in one for-each-ref, the checkouts; then the counts, the dirty files
  (let [outs (future (mapv #(update % :path canonical) (branch/checkouts clone)))
        sha-of (future (into {} (keep #(let [[r s] (str/split % #" ")] (when (seq r) [r s])))
                             (str/split-lines (or (git clone "for-each-ref" "--format=%(refname) %(objectname)"
                                                       "refs/heads/" "refs/remotes/origin/")
                                                  ""))))]
  (when-let [{:keys [sha base fetched-at] :as st} (source/git-status clone)]
    (let [sha-of @sha-of
          refs (vec (for [n (distinct (remove nil? [base branch (:branch st)])), remote? [false true]
                          :let [s (sha-of (str (if remote? "refs/remotes/origin/" "refs/heads/") n))] :when s]
                      {:name (if remote? (str "origin/" n) n) :branch n :sha s :remote? remote?}))
          on-origin (set (keep #(when (:remote? %) (:branch %)) refs))
          refs (mapv deref (mapv (fn [{n :branch :keys [remote?] :as r}]
                                   (if (and (not remote?) (on-origin n))
                                     (future (merge r (ahead-behind clone (str "refs/heads/" n) (str "refs/remotes/origin/" n))))
                                     (delay r)))
                                 refs))
          head-dirty (future (if (pos? (:dirty st)) (dirty-files clone) []))
          root (canonical (branch/worktrees-root))
          outs @outs
          wt (when worktree (first (filter #(= (canonical worktree) (:path %)) outs)))
          wt-dirty (future (when wt (dirty-files (:path wt))))
          others (vec (for [o outs :when (and (str/starts-with? (:path o) root) (not= (:path o) (:path wt)))]
                        (select-keys o [:path :sha])))
          tips (vec (distinct (remove nil? (concat [sha] (map :sha refs) [(:sha wt)]))))
          meet (when (seq tips) (apply git clone "merge-base" "--octopus" tips))
          floor (when meet (git clone "rev-parse" "--verify" "--quiet" (str meet "~5")))
          rows (when (seq tips)
                 (for [l (branch/lines (apply git clone "log" "--topo-order" (str "-n" (inc max-commits))
                                       "--format=%H%x1f%P%x1f%an%x1f%ct%x1f%s"
                                       (cond-> tips floor (conj (str "^" floor)))))
                       :let [[s ps author t subject] (str/split l field)]]
                   {:sha s :parents (vec (remove str/blank? (str/split (str ps) #" "))) :author author
                    :time (some-> t parse-long (* 1000)) :subject (or subject "")}))]
      {:base base
       :head {:sha sha :branch (:branch st) :dirty @head-dirty}
       :worktree (when wt {:path (:path wt) :sha (:sha wt) :dirty @wt-dirty})
       :others others
       :refs refs
       :commits (layout (take max-commits rows))
       :truncated (> (count rows) max-commits)
       :fetched-at fetched-at}))))

(defn project
  "What the Branches tab shows of the repo `cfg` runs: read-repo's, plus :from, :branch (the preset's),
  :git (zent.source/checkout-git), :dir (what runs) and :clone, and :conflicts - what rebasing what it
  runs onto origin/<base> would conflict on (zent.source/merge-conflicts), when it's behind. nil when the
  repo isn't checked out."
  [cfg]
  (let [dir (source/checkout-dir cfg)
        workdir (source/clone-dir cfg)
        worktree (when (source/worktree? cfg) dir)
        from (source/from cfg)
        ;; two independent reads: what runs, and the repo's graph
        g (future (source/checkout-git cfg))
        r (read-repo workdir {:branch (:branch cfg) :worktree worktree})
        g @g]
    (when r
      (cond-> (assoc r :from from :branch (:branch cfg) :git g :dir dir :clone workdir)
        (and (:base g) (pos? (or (:behind g) 0)))
        (assoc :conflicts (source/merge-conflicts dir (str "origin/" (:base g))
                                                  (if (= :local-worktree from) (str "refs/heads/" (:branch cfg)) "HEAD")))))))
