(ns zent.ui.render
  "Server-side HTML for the dashboard (htmx), as pure functions of
  zent.ui.state's maps. The DAG layout stays client-side - it needs
  :status/:deps as data - so snapshots carry the raw data plus a rendered
  :card per component.

  No hiccup lib (the vocabulary is small, and Jolt compatibility isn't
  free). `esc` is the one thing that has to be right: :error/:repo/...
  carry subprocess- and catalog-sourced text."
  (:require [clojure.string :as str]
            [zent.source :as source]
            [zent.usage :as usage]))

(defn esc
  "HTML-escapes `x` for use in text content or a double-quoted attribute."
  [x]
  (-> (str x)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(def ^:private state-label
  "Display text per :status. :external reads \"up\" - the card already names
  the kind."
  {:pending "pending" :deploying "starting" :up "up"
   :done "done" :idle "idle" :stopping "stopping" :failed "failed" :external "up" :off "off" :down "stopped"
   :ready "ready" :unready "unready" :skipped "skipped"})

(def ^:private error-shown?
  "Statuses whose :error is shown - only while in them: a stale error must not linger after recovery."
  #{:failed :unready :skipped})

(def ^:private one-shot-label
  "A job's own wording where a service's would mislead - nothing to stop."
  {:off "not run" :down "not run" :pending "queued" :deploying "running" :ready "on demand"})

(defn label-of
  "A :status as shown - the dashboard's wording, also the CLI's/MCP's."
  ([status] (get state-label status (name status)))
  ([kind status] (or (when (= :one-shot kind) (one-shot-label status)) (label-of status))))

(defn duration
  "Milliseconds as a short clock: 850ms, 42s, 3m05s, 1h02m."
  [ms]
  (let [s (quot ms 1000)]
    (cond (< ms 1000) (str ms "ms")
          (< s 60) (str s "s")
          (< s 3600) (format "%dm%02ds" (quot s 60) (mod s 60))
          :else (format "%dh%02dm" (quot s 3600) (mod (quot s 60) 60)))))

(defn- clock
  "Next to a state: how long it has lasted while moving (index.js ticks it from data-since), else how long the
  last start took."
  [{:keys [status since took]}]
  (cond
    (and since (#{:pending :deploying :stopping} status))
    (str "<span class=\"clock\" data-since=\"" since "\" title=\"Time in this state\"></span>")
    took (str "<span class=\"clock\" title=\"Time its last start took\">" (duration took) "</span>")))

(defn- script-name
  "`./scripts/seed-db.sh` -> seed-db: a strip names the scripts it
  runs - the command only, an argument path mustn't stand in for it."
  [s]
  (-> s (str/split #"\s+") first (str/replace #"^.*/" "") (str/replace #"\.[^.]*$" "")))

(def ^:private kind-family
  "The ear's colour: what runs it, as you'd say it - compose-services is still Docker containers."
  {:docker-compose "compose" :compose-services "compose" :quarkus-app "quarkus" :process "process"
   :one-shot "one-shot" :external "external" :k8s "k8s"})

(def ^:private kind-tip
  "What zent does with each kind, the ear's hover."
  {:docker-compose "Docker containers: zent runs the whole compose file (up --wait) and tears it down with the stack"
   :compose-services "Docker containers: only these services of the compose file run, the rest of the file stays down"
   :quarkus-app "A JVM service: zent builds it with Maven, then runs the jar - ready once its health endpoint answers"
   :process "A command run from its repo, as a plain process zent tracks by pid"
   :one-shot "A script that runs once and exits"
   :external "Started outside zent (your IDE): zent only checks that it answers"
   :k8s "Kubernetes workloads zent rolls out to the cluster"})

(defn- kind-line
  "Under the name: the kind as you'd say it - for a compose one, how much of the file runs."
  [{:keys [kind services service-status]}]
  (let [names (map (comp name #(:service % %)) (or (seq service-status) services))
        n (count names)]
    (case kind
      :docker-compose "compose · whole file"
      :compose-services (cond (zero? n) "compose · some services"
                              (<= n 3) (str "compose · " (str/join ", " names))
                              :else (str "compose · " n " services"))
      (name kind))))

(defn- ear
  "The plate's left ear, anodised in its kind's family colour; gold screws when :permanent."
  [{:keys [kind]}]
  (str "<span class=\"ear\" data-family=\"" (get kind-family kind "process") "\" title=\"" (esc (str (name kind) "\n" (kind-tip kind)))
       "\"><i></i><i></i></span>"))

(defn- ports-of
  "Its ports, as [port live? service]: its own :port, or a compose one's published ports. live?: something
  listens on it (:listening, the ports this machine listens on) - when that isn't known, its container
  runs, or it is up."
  [{:keys [port service-status status listening]}]
  (let [live? (fn [p guess] (if listening (contains? listening (str p)) guess))]
    (distinct (or (seq (for [{:keys [service ports state]} service-status, p ports] [p (live? p (= "running" state)) (name service)]))
                  (when port [[port (live? port (contains? #{:up :external} status))]])))))

(defn- port-sockets
  "Its ports, as sockets on the plate's right edge, the number beside each, lit when something listens."
  [c]
  (when-let [ports (seq (ports-of c))]
    ;; .room: a zero-width float as tall as the sockets, so the plate never ends above its last one
    ;; past four, they sit closer: a plate with nine ports stays its own height
    (apply str "<i class=\"room" (when (> (count ports) 4) " dense") "\" style=\"--n:" (count ports) "\"></i>"
           (map-indexed (fn [i [p live?]]
                          (str "<span class=\"socket" (when live? " live") "\" style=\"--i:" i "\" title=\"localhost:" (esc p)
                               (if live? "\nSomething listens here" "\nNothing listens yet: the ring lights once it does") "\"><b>" (esc p) "</b></span>"))
                        ports))))

(defn- tildify [path]
  (let [home (System/getenv "HOME")]
    (if (and path home (str/starts-with? path (str home "/"))) (str "~" (subs path (count home))) path)))

(defn- svc-class
  [{:keys [state health]}]
  (cond
    (nil? state) ""
    (or (= health "unhealthy") (not= state "running")) "bad"
    (= health "starting") "wait"
    :else "ok"))

(defn- service-rows
  "Per-container status/health for a compose-backed component; declared
  names only, before the stack is up or when docker didn't answer."
  [{:keys [service-status services]}]
  (let [rows (or (seq service-status) (map (fn [s] {:service s}) services))]
    (if (seq rows)
      (str "<span class=\"services\">"
           (apply str
                  (map (fn [{:keys [service state health]}]
                         (str "<span class=\"svc\" data-health=\"" (svc-class {:state state :health health}) "\" title=\""
                              (esc (when state (str state (when health (str " / " health)))))
                              "\"><span class=\"svc-name\">" (esc (name service)) "</span></span>"))
                       rows))
           "</span>")
      "")))

(defn cpu-text "75%" [cpu] (str (long cpu) "%"))

(defn mem-text
  "Bytes as a card can show them: 640M, 1.2G."
  [rss]
  (let [mib (/ (double rss) 1048576)]
    (if (< mib 1000) (str (long mib) "M") (format "%.1fG" (/ mib 1024)))))

(def ^:private meter-full
  "What fills a meter: two cores, 4 GiB - past that it stays full, its colour says the rest."
  {:cpu 200 :rss (* 4096 1048576)})

(defn- meter
  [metric v text]
  (str "<span class=\"meter " (name metric) "\" data-level=\"" (name (usage/level metric v)) "\">"
       "<i style=\"--v:" (format "%.2f" (min 1.0 (/ (double v) (meter-full metric)))) "\"></i>"
       "<b>" text "</b></span>"))

(defn- usage-title
  "The meters' hover: the exact figures, what they mean, then per service or the process count."
  [{:keys [cpu rss procs parts]}]
  (let [{{cb :busy cs :stressed} :cpu {mb :busy ms :stressed} :rss} usage/thresholds]
    (->> (concat [(str "CPU " (cpu-text cpu) " of one core (busy from " cb "%, stressed from " cs "%)")
                  (str "memory " (mem-text rss) " resident (busy from " (mem-text mb) ", stressed from " (mem-text ms) ")")]
                 (when (> (or procs 0) 1) [(str procs " processes")])
                 (map (fn [{:keys [name cpu rss]}] (str (clojure.core/name name) ": " (cpu-text cpu) ", " (mem-text rss))) parts))
         (map esc)
         (str/join "&#10;"))))

(defn usage-row
  "A card's CPU and memory, two small meters - grey while calm, weld when busy, madder when
  stressed - the exact figures on hover. nil without :usage."
  [{:keys [cpu rss] :as u}]
  (when u
    (str "<span class=\"usage\" data-level=\"" (name (:level u)) "\" title=\"" (usage-title u) "\">"
         (meter :cpu cpu (cpu-text cpu)) (meter :rss rss (mem-text rss)) "</span>")))

(defn- can-reload? [status] (not (contains? #{:off :external :stopping} status)))
(defn- can-stop? [status] (contains? #{:up :deploying :idle :unready} status))
(defn- links-visible? [status] (contains? #{:up :external :deploying} status))

(defn- short-sha [sha] (when sha (subs sha 0 (min 7 (count sha)))))

(def ^:private running? #{:up :unready :idle :done})

(defn git-chip
  "A card's git state: its :git (zent.source/checkout-git) plus what the preset wanted; nil before git
  answered. :from where it runs from (zent.source/from); :default? on the default branch, none wanted
  (no label then); :off? not the preset's :branch - without one, the main clone runs as it is, whatever
  its branch; :wt? in its own worktree; :lose? commits on no branch there, which the next sync refuses
  to drop; :test? an :origin worktree running a local test rebase; :stale? running, but the disk moved
  on from the commit deployed. Its counts are never nil: 0 when git didn't tell."
  [{:keys [git source wanted-branch from deployed-sha status preview-of]}]
  (when-let [{:keys [branch sha base unreachable] :as git}
             (when git (cond-> (merge-with #(or %2 %1) {:behind 0 :ahead 0 :dirty 0 :pull 0 :unreachable 0} git)
                         ;; a worktree is detached: the branch is the preset's
                         (and (:worktree source) (nil? (:branch git))) (assoc :branch wanted-branch)))]
    (let [wt? (boolean (:worktree source))]
      (assoc git
             :wanted-branch wanted-branch
             :from (or from (:from git))
             :default? (and (not wanted-branch) (contains? (if base #{base} #{"main" "master"}) branch))
             :off? (boolean (and wanted-branch (not= wanted-branch branch)))
             :wt? wt?
             :lose? (boolean (and wt? (pos? unreachable)))
             :test? (boolean (:test? git))
             :stale? (boolean (and deployed-sha sha (running? status) (not= deployed-sha sha)))
             :deployed-sha deployed-sha
             :preview-of preview-of))))

(defn- bare-branch
  "A branch without its feature/, bug/... prefix."
  [branch]
  (str/replace (str branch) #"^(feature|bugfix|bug|fix|hotfix|chore|release)/" ""))

(defn short-branch
  "A branch as a card can show it: no feature/, bug/... prefix, cut in the middle - a ticket number
  usually starts it, what it's about ends it."
  [branch]
  (let [b (bare-branch branch)]
    (if (<= (count b) 22) b (str (subs b 0 12) "\u2026" (subs b (- (count b) 9))))))

(defn- git-facts
  "A chip's facts, one sentence each (nil when there's nothing to say): the `cd` hover lists them all,
  each piece of the git row shows its own."
  [{:keys [wanted-branch from branch sha base behind ahead pushed dirty pull unreachable local-ahead local-behind
           fetched-at deployed-sha off? wt? lose? test? stale?] :as chip} now]
  (let [ago (if fetched-at (str " as of the last fetch, " (duration (max 0 (- now fetched-at))) " ago") "")
        sync (str " An apply that redeploys it or a pull "
                  (if (= :local from) (str "resets it to your " branch) (str "fetches, then resets it to origin/" branch))
                  " - refused while it holds uncommitted files or commits on no branch; a reload keeps its commit")]
    {:branch (str (or branch (str "detached at " (short-sha sha))) "\n"
                  (cond (and off? (= :clone from)) (str "the preset's :from :clone wants " wanted-branch " checked out here:"
                                                        " the deploy refuses until it is (the checkout button, once nothing"
                                                        " is uncommitted)")
                        off? (str "the preset wants " wanted-branch " - not this one")
                        (= :clone from) "the preset's :branch, :from :clone: your clone runs it as it is"
                        (= :local from) "the preset's :branch, :from :local: your local branch, in a worktree"
                        wanted-branch "the preset's :branch: origin's, in a worktree"
                        :else "no :branch in the preset: runs whatever this clone has checked out"))
     :from (cond
             (and wt? test?) (str "runs origin/" branch " rebased onto origin/" base " in its own worktree: a local test, no"
                                  " branch moved - the next apply or pull reverts it")
             (and wt? (= :local from)) (str "runs your local " branch " in its own worktree, detached - commit to it to run"
                                            " a change, no push needed." sync
                                            (when (pos? pull) (str "; your " branch " has " pull " more commit(s): pull to run them")))
             wt? (str "runs origin/" branch " in its own worktree, detached - push to run a change." sync
                      (when (pos? pull) (str "; origin has " pull " more commit(s)" ago ": pull to run them")))
             :else "runs your local clone as it is, uncommitted changes included - nothing syncs it, a reload restarts what's on disk")
     :behind (when (pos? behind)
               (str behind " commit(s) on origin/" base " not in this checkout - "
                    (or (source/rebase-refusal chip)
                        (case from
                          :origin (str "test on " base ": the button fetches, then rebases the worktree's commit onto it, locally"
                                       " - no branch moves, the next apply or pull reverts it")
                          :local (str "rebase: the button fetches, then rebases your " branch " if nothing conflicts"
                                      " (refused while another checkout has it out)")
                          "rebase: the button fetches, then rebases if nothing conflicts"))
                    (if fetched-at (str " (" (subs ago 1) ")") " (never fetched)")))
     :ahead (when (pos? ahead)
              (str ahead " commit(s)" (if pushed (str " not on origin/" branch) " pushed nowhere")
                   (when-not wt? " - safe here: the clone runs as it is")))
     :lose (when lose?
             (str unreachable " commit(s) on no branch, made in this worktree: the next sync refuses until you keep them"
                  " (git branch rescue/" branch " HEAD)"))
     :local (when (and (= :local from) local-ahead (pos? (+ local-ahead (or local-behind 0))))
              (str "your " branch ": " local-ahead " commit(s) not on origin/" branch ", " local-behind " on origin/" branch
                   " not on yours" (when (pos? (or local-behind 0)) " - pull --rebase in your clone")))
     :dirty (when (pos? dirty)
              (str dirty " modified or untracked file(s)"
                   (if wt? " - the next sync refuses until you commit or stash them" " - they run too: the clone runs as it is")))
     :stale (when stale? (str "running " (short-sha deployed-sha) ", the disk is at " (short-sha sha) ": reload to run it"))}))

(defn- git-title
  "The `cd` hover: every fact, then the commit and the dir."
  [chip dir now]
  (->> (conj (mapv (git-facts chip now) [:branch :from :behind :ahead :lose :local :dirty :stale])
             (when-not (:fetched-at chip) "never fetched") (:sha chip) (tildify dir))
       (remove nil?)
       (map #(str/replace (esc %) "\n" "&#10;"))
       (str/join "&#10;")))

(defn- git-button
  "A git action on the card: posts to `path`, asking `confirm` first when given."
  [title path confirm label]
  (str "<button type=\"button\" class=\"act git-act\" title=\"" (esc title) "\" hx-post=\"" (esc path)
       "\" hx-swap=\"none\" hx-disabled-elt=\"this\"" (when confirm (str " hx-confirm=\"" (esc confirm) "\"")) ">"
       label "</button>"))

(defn- rebase-button
  "The rebase a click away when `chip` is behind and nothing refuses it - what it moves depends on
  :from (zent.source/rebase!)."
  [cname {:keys [from branch base behind pushed local-ahead preview-of] :as chip}]
  (when (and (pos? behind) (not (source/rebase-refusal chip)))
    (let [onto (str "origin/" base)
          path (str "/api/rebase/" (name cname) (when preview-of (str "?preset=" preview-of)))]
      (case from
        :origin (git-button (str "Fetch, then run origin/" branch " rebased onto " onto " in its worktree - no branch moves")
                            path (str "Run origin/" branch " rebased onto " onto " in its worktree? A local test: no branch"
                                      " moves, the next apply or pull reverts it.")
                            (str "test on " (esc base)))
        :local (git-button (str "Fetch, then rebase your local " branch " onto " onto " if nothing conflicts")
                           path (str "Rebase your local branch " branch " onto " onto "? It moves " branch
                                     " (refused while another checkout has it out)."
                                     (when local-ahead " It is on origin: push --force-with-lease afterwards."))
                           "rebase")
        (git-button (str "Fetch, then rebase " branch " onto " onto " if nothing conflicts")
                    path (str "Rebase " branch " onto " onto "?" (when pushed " It is on origin: push --force-with-lease afterwards."))
                    "rebase")))))

(defn- checkout-button
  "The preset's :branch a click away when a :from :clone runs another one and nothing refuses it
  (zent.source/checkout-refusal)."
  [cname {:keys [wanted-branch branch preview-of] :as chip}]
  (when-not (source/checkout-refusal chip)
    (git-button (str "Check out " wanted-branch " in your clone, fetched from origin when you have no local one")
                (str "/api/checkout/" (name cname) (when preview-of (str "?preset=" preview-of)))
                (str "Check out " wanted-branch " in your clone" (when branch (str ", now on " branch))
                     "? Every component running this clone moves with it.")
                "checkout")))

(defn- git-row
  "The chip, a section of its own - nil when it has nothing to say: the branch unless the default one
  (red when not the preset's :branch, dashed in a worktree), where the code comes from (origin's or
  your local branch in a worktree, or the clone as it is), then each count with its word - each piece
  with its fact on hover. In a preview, what an apply's sync would do."
  [cname {:keys [from branch sha base behind ahead dirty pull unreachable local-ahead local-behind wanted-branch
                 default? off? wt? lose? test? stale? preview-of] :as chip} now]
  (let [facts (git-facts chip now)
        span (fn [cls fact body] (str "<span class=\"" cls "\" title=\"" (str/replace (esc (facts fact fact)) "\n" "&#10;") "\">" body "</span>"))
        local? (and wt? (= :local from) local-ahead)
        counts (str (when (pos? behind) (str (span "behind" :behind (str "&#8595;" behind " " (esc base))) (rebase-button cname chip)))
                    (cond lose? (span "ahead lose" :lose (str "&#9888;" unreachable " on no branch"))
                          test? nil
                          local? (str (when (pos? local-ahead) (span "ahead" :local (str "&#8593;" local-ahead " unpushed on " (esc (short-branch branch)))))
                                      (when (pos? (or local-behind 0)) (span "behind" :local (str "&#8595;" local-behind " on origin"))))
                          (pos? ahead) (span "ahead" :ahead (str "&#8593;" ahead " unpushed")))
                    (when (pos? dirty) (span (str "dirty" (when wt? " lose")) :dirty (str "&#9679;" dirty " uncommitted")))
                    (when stale? (span "stale" :stale "&#8800; reload"))
                    ;; a preview: what applying it would do to the worktree, as of the last fetch
                    (when (and wt? preview-of)
                      (cond (pos? (+ dirty unreachable))
                            (span "plan lose" (str "an apply that redeploys it refuses to sync: " (+ dirty unreachable) " uncommitted file(s) or commit(s) on no branch")
                                  (str "&#9995; sync refused: " (+ dirty unreachable) " at risk"))
                            (pos? pull) (span "plan" (str "an apply that redeploys it resets the worktree, " pull " commit(s) ahead")
                                              (str "apply: reset +" pull))
                            :else (span "plan" "an apply keeps the worktree at its commit: nothing new" "apply: keeps"))))]
    (when (or (not default?) (seq counts))
      (str "<span class=\"git\">"
           (when-not default?
             (str (span (str "branch" (when wt? " wt") (when off? " off")) :branch
                        ;; its window is the plate's width: the name whole, cut by the window
                        (esc (if branch (bare-branch branch) (short-sha sha))))
                  (when off? (str (span "want" (str "the preset wants " wanted-branch ", this is " branch)
                                        (str "Wants " (esc (bare-branch wanted-branch))))
                                  (checkout-button cname chip)))
                  (cond (not wt?) (span "from clone" :from "&#9998; clone")
                        test? (span "from origin test" :from (str "&#10548; " (esc base) " (test)"))
                        (= :local from) (span (str "from local" (when (pos? pull) " moved")) :from
                                              (str "&#9095; local" (when (pos? pull) (str " +" pull))))
                        :else (span (str "from origin" (when (pos? pull) " moved")) :from
                                    (str "&#10227; origin" (when (pos? pull) (str " +" pull)))))
                  (when (and wt? (not preview-of) (or test? (pos? pull)))
                    (git-button (cond test? (str "Drop the test: sync the worktree back to origin/" branch ", then redeploy")
                                      (= :local from) (str "Sync the worktree to your " branch ", then redeploy")
                                      :else (str "Sync the worktree to origin/" branch ", then redeploy"))
                                (str "/api/pull/" (name cname)) nil (if test? "undo test" "pull")))))
           counts "</span>"))))

(defn- checkout
  "A checkout in one line, the details on hover: its branch (not main/master; dashed in a worktree) unless
  git was read (`cd-title`, git-title: its row shows it), then `cd` - its dir, a click copies `cd <dir>`."
  [{:keys [branch dir worktree]} & [cd-title]]
  (str (when (and branch (not cd-title) (not (#{"main" "master"} branch)))
         (str "<span class=\"branch" (when worktree " wt") "\" title=\"" (esc branch)
              (when worktree " - its own worktree") "\">" (esc branch) "</span>"))
       (when dir
         (str "<button type=\"button\" class=\"workdir-open\" data-dir=\"" (esc dir) "\" title=\""
              (or cd-title (esc (tildify dir))) "&#10;Copy `cd " (esc dir) "`\">cd</button>"))))

(defn- source-env-rows
  "What a :source-env component reads: one row per var (its name the tooltip)."
  [source-env]
  (apply str (map (fn [[var src]] (str "<span class=\"source-env\" title=\"" (esc var) "\">" (checkout src) "</span>"))
                  (sort-by key source-env))))

(defn- facts
  "A card's one line of facts, the details on hover: its checkout, ↗ (the repo) - then the state."
  [{:keys [kind status repo repo-url source] :as c} cd-title]
  (str "<span class=\"meta\">"
       (checkout source cd-title)
       (when repo
         (if repo-url
           (str "<a class=\"repo-link\" href=\"" (esc repo-url) "\" target=\"_blank\" rel=\"noopener\" title=\"" (esc repo) "\">&#8599;</a>")
           (str "<span class=\"repo-link\" title=\"" (esc repo) "\">&#8599;</span>")))
       "<span class=\"state\">" (esc (label-of kind status)) (clock c) "</span></span>"))

(def ^:private shot-state
  "A one-shot's button, by :status: what its pip and its press say."
  {:done "done" :deploying "running" :ready "ondemand" :failed "failed" :skipped "failed" :unready "failed"})

(defn- shot-state-of
  "on demand until it runs: an apply never starts it."
  [{:keys [status on-demand]}]
  (get shot-state status (if on-demand "ondemand" "idle")))

(defn- shot-buttons
  "A host's :attached-to one-shots (:shots, [name c] pairs), as buttons in a recess of its plate: the pip
  is the state, pressed in once done, hatched when on demand; what it does and why it failed on hover. A
  click selects it; its ▶ runs it (again), asking first when it has a :confirm."
  [shots preview?]
  (when (seq shots)
    (str "<span class=\"shots\">"
         (apply str
                (map (fn [[n {:keys [kind status description error confirm] :as shot}]]
                       (str "<span class=\"shot\" data-shot=\"" (esc (name n)) "\" data-state=\"" (shot-state-of shot) "\" title=\""
                            (esc (str (name n) " - " (label-of kind status)
                                      (when description (str "\n" description))
                                      (when (and error (error-shown? status)) (str "\n" error))))
                            "\"><i></i><span>" (esc (name n)) "</span>"
                            (when (and (not preview?) (can-reload? status) (not= :deploying status))
                              (str "<button type=\"button\" class=\"act run\" title=\"" (if (= :ready status) "Run" "Run again") "\" hx-post=\"/api/trigger/"
                                   (esc (name n)) "\" hx-swap=\"none\" hx-disabled-elt=\"this\""
                                   (when confirm (str " hx-confirm=\"" (esc confirm) "\"")) ">&#9654;</button>"))
                            "</span>"))
                     shots))
         "</span>")))

(defn- card-body
  "A full card's content - everything but the error and the actions. :now: the render's clock (ms)."
  [cname {:keys [status source-env links permanent source now] :as c}]
  (let [chip (git-chip c)
        cd-title (when chip (git-title chip (:dir source) now))]
    (str
     (ear c)
     "<span class=\"head\"><span class=\"name\">" (esc (name cname)) "</span>"
     "<span class=\"led\" title=\"" (esc (label-of (:kind c) status)) "\"></span></span>"
     (when permanent
       "<span class=\"permanent\" title=\"Permanent: stays up across preset switches, stop-everything and zent exiting - only its own Stop reaches it\">Permanent</span>")
     "<span class=\"kind\">" (esc (kind-line c)) "</span>"
     (port-sockets c)
     (when-let [dep (:waits-for c)] (str "<span class=\"note wait\">Waits for " (esc (name dep)) "</span>"))
     (facts c cd-title)
     (usage-row (:usage c))
     (if chip
       (git-row cname chip now)
       ;; a preview's :branch never deployed: its worktree doesn't exist yet
       (when-let [b (and (:preview? c) (:worktree source) (:wanted-branch c))]
         (let [local? (= :local (:from c))]
           (str "<span class=\"git\"><span class=\"branch wt\" title=\"" (esc b) "\">&#9095; " (esc (short-branch b)) "</span>"
                "<span class=\"from " (if local? "local" "origin") " pending\" title=\"not checked out anywhere yet: apply creates its worktree from "
                (if local? (str "your local " (esc b) " - what's committed there, nothing uncommitted")
                    (str "origin/" (esc b) " - what's pushed there, nothing local"))
                "\">&#9888; created at apply from " (if local? (esc b) (str "origin/" (esc b))) "</span></span>"))))
     (service-rows c)
     (shot-buttons (:shots c) (:preview? c))
     (source-env-rows source-env)
     ;; always listed, a link only once reachable: what you'll open is part of what the card says
     (when (seq links)
       (str "<span class=\"endpoints\">"
            (apply str (map (fn [{:keys [url label]}]
                               ;; reachable: its port listens - or, when that isn't known, the component is up
                               (if (if-let [port (and (:listening c) (second (re-find #":(\d+)" (str url))))]
                                     (contains? (:listening c) port)
                                     (links-visible? status))
                                 (str "<a class=\"endpoint\" href=\"" (esc url) "\" target=\"_blank\" rel=\"noopener\" title=\"" (esc url) "\">" (esc label) "</a>")
                                 (str "<span class=\"endpoint idle\" title=\"Not served yet: opens once it is up\">" (esc label) "</span>")))
                             links))
            "</span>")))))

(defn component-card
  "The inner markup of one node card; the client owns the wrapper <div>
  (layout and interaction). Buttons carry their hx-post - the client calls
  htmx.process() once after injecting. :preview? drops the actions:
  nothing in a preview is deployed. An :attached-to one-shot gets a
  strip drawn under its host's card: the scripts it runs, its state and
  :description; the component name is the tooltip."
  [cname {:keys [kind status error preview? attached-to scripts description source-env confirm] :as c}]
  (let [reload? (and (not preview?) (can-reload? status))
        stop? (and (not preview?) (can-stop? status))]
    (str
     ;; a one-shot with a place of its own (no host, or waiting for several others): a capsule
     (if (= :one-shot kind)
       (str "<span class=\"capsule\" data-state=\"" (shot-state-of c) "\" title=\""
            (esc (str/join "\n" (remove nil? [description (when (seq scripts) (str "runs " (str/join " → " (map script-name scripts))))])))
            "\"><i></i><span class=\"name\">" (esc (name cname)) "</span>"
            "<span class=\"state\">"
            ;; until it runs: what it is to the stack, rather than "queued"
            (cond (not (#{:pending :down :off :ready} status)) (str (esc (label-of kind status)) (clock c))
                  (:terminus c) "the point of it"
                  (:on-demand c) "on demand"
                  :else (str (esc (label-of kind status)) (clock c)))
            "</span></span>"
            (source-env-rows source-env))
       (card-body cname c))
     (when (and error (error-shown? status)) (str "<span class=\"fray\">" (esc error) "</span>"))
     "<span class=\"actions\">"
     (when reload?
       ;; same trigger, but a one-shot has nothing loaded to reload: it runs its scripts
       (let [one-shot? (= :one-shot kind)]
         (str "<button type=\"button\" class=\"act reload\" title=\""
              (cond (not one-shot?) "Reload now" (= status :ready) "Run" :else "Run again") "\" "
              "hx-post=\"/api/trigger/" (esc (name cname)) "\" hx-swap=\"none\" hx-disabled-elt=\"this\""
              ;; a destructive one asks first (htmx: nothing is posted on cancel)
              (when confirm (str " hx-confirm=\"" (esc confirm) "\""))
              (when (= status :deploying) " disabled")
              ">" (if one-shot? "&#9654;" "&#8635;") "</button>")))
     (when stop?
       (str "<button type=\"button\" class=\"act stop\" title=\"Stop\" "
            "hx-post=\"/api/stop/" (esc (name cname)) "\" hx-swap=\"none\" hx-disabled-elt=\"this\">&#9632;</button>"))
     "</span>")))

(def ^:private summary-order ["up" "done" "ready" "idle" "starting" "stopping" "pending" "unready" "skipped" "failed" "off" "stopped"])

(defn- git-summary
  "What the chips ask of you, one line each, with the components' names: not the preset's :branch,
  work in a worktree its next sync refuses to drop, a disk ahead of what runs, behind the default branch. Nothing to do:
  nothing shown."
  [components]
  (let [chips (keep (fn [[n c]] (some-> (git-chip c) (assoc :name (name n)))) components)
        line (fn [k head pred show]
               (when-let [cs (seq (sort-by :name (filter pred chips)))]
                 (str "<li class=\"" k "\">" head " " (esc (str/join ", " (map show cs))) "</li>")))
        lines (remove nil? [(line "off" "&#9095; not the preset's branch:" :off? :name)
                            (line "lose" "&#9888; blocks the next sync:" #(and (:wt? %) (pos? (+ (:unreachable %) (:dirty %))))
                                  #(str (:name %) " (" (+ (:unreachable %) (:dirty %)) ")"))
                            (line "stale" "&#8800; to reload:" :stale? :name)
                            (line "behind" "&#8595; behind:" #(pos? (:behind %)) #(str (:name %) " " (:behind %) " " (:base %)))])]
    (when (seq lines)
      (str "<ul class=\"git-summary\">" (apply str lines) "</ul>"))))

(defn- usage-summary
  "The heaviest components, three per metric, each named in its own level's colour, after the
  total. Below 5% CPU nobody is named: an idle stack has no top consumer."
  [components]
  (let [us (keep (fn [[n c]] (some-> (:usage c) (assoc :name (name n)))) components)
        top (fn [metric text floor]
              (when-let [xs (seq (take 3 (sort-by (comp - metric) (filter #(>= (metric %) floor) us))))]
                (str/join " · " (map #(str "<span data-level=\"" (name (usage/level metric (metric %))) "\">"
                                           (esc (:name %)) " " (text (metric %)) "</span>")
                                     xs))))]
    (when (seq us)
      (let [cpu (top :cpu cpu-text 5)
            mem (top :rss mem-text 0)]
        (str "<ul class=\"usage-top\" title=\"CPU in % of one core, memory resident - sampled every few seconds\">"
             "<li><b>&#931;</b> " (cpu-text (reduce + (map :cpu us))) " &#183; " (mem-text (reduce + (map :rss us))) "</li>"
             (when cpu (str "<li><b>cpu</b> " cpu "</li>"))
             (when mem (str "<li><b>mem</b> " mem "</li>"))
             "</ul>")))))

(defn run-summary
  "The whole #summary element (outerHTML, class included)."
  [components]
  (if (empty? components)
    "<div id=\"summary\" class=\"quiet\">Nothing running.</div>"
    (let [tally (reduce (fn [acc [_ c]] (update acc (label-of (:status c)) (fnil inc 0))) {} components)
          failed (keep (fn [[n c]] (when (= :failed (:status c)) n)) components)]
      (str "<div id=\"summary\">"
           "<div class=\"tally\">"
           (apply str (keep (fn [k] (when-let [n (get tally k)]
                                       (str "<span class=\"t-" k "\"><b>" n "</b> " k "</span>")))
                             summary-order))
           "</div>"
           (usage-summary components)
           (git-summary components)
           (cond
             (seq failed) (str "<p class=\"blocked\">Blocked by " (esc (str/join ", " (map name failed))) ".</p>")
             (get tally "starting") "<p class=\"quiet\">Still starting.</p>"
             :else "")
           "</div>"))))

(defn preset-picker
  "The whole #preset-picker element. A name click previews the preset
  (data-preset, GET /api/preview/:name); the play button applies it."
  [presets active-preset]
  (str "<ul class=\"picker\" id=\"preset-picker\">"
       (if (seq presets)
         (apply str (map (fn [p]
                            (let [active? (= p active-preset)]
                              (str "<li class=\"preset-row\"" (when active? " data-active=\"true\"") ">"
                                   "<span class=\"preset-name\" data-preset=\"" (esc (name p))
                                   "\" title=\"Preview this preset's components without starting it\">" (esc (name p)) "</span>"
                                   "<button type=\"button\" class=\"act play\" title=\"Switch to this preset\" "
                                   "hx-post=\"/api/apply/" (esc (name p)) "\" hx-swap=\"none\" hx-disabled-elt=\"this\">&#9654;</button>"
                                   (when active?
                                     (str "<button type=\"button\" class=\"act stop\" title=\"Stop everything\" "
                                          "hx-post=\"/api/stop-all\" hx-swap=\"none\" hx-disabled-elt=\"this\">&#9632;</button>"))
                                   "</li>")))
                          (sort-by name presets)))
         "<li class=\"quiet\">No presets in this catalog.</li>")
       "</ul>"))

(defn detail-panel
  "The whole #detail element (GET /api/detail/:name); nil `c` reads as
  nothing selected."
  [cname c]
  (if-not c
    "<div id=\"detail\" class=\"quiet\">Select a component to see its status.</div>"
    (let [{:keys [kind status deps error source]} c
          {:keys [branch from behind base wanted-branch off?] :as chip} (git-chip c)
          row (fn [k v & [cls]] (str "<div class=\"row\"><span>" k "</span><span" (when cls (str " class=\"" cls "\"")) ">" v "</span></div>"))
          section (fn [title & rows] (when-let [rows (seq (remove nil? rows))] (str "<h3>" title "</h3><div class=\"rows\">" (apply str rows) "</div>")))
          names #(if (seq %) (esc (str/join ", " (map name %))) "nothing")
          act (fn [cls label path & [confirm]]
                (str "<button type=\"button\" class=\"btn " cls "\" hx-post=\"" path (esc (name cname)) "\" hx-swap=\"none\" hx-disabled-elt=\"this\""
                     (when confirm (str " hx-confirm=\"" (esc confirm) "\"")) ">" label "</button>"))]
      (str "<div id=\"detail\">"
           "<div class=\"head\"><span class=\"kind\">" (esc (kind-line c)) "</span>"
           "<span class=\"led\" data-state=\"" (cond (#{:up :external :done} status) "up" (= :deploying status) "starting"
                                                      (error-shown? status) "failed" :else "idle") "\"></span></div>"
           "<h2 class=\"title\">" (esc (name cname)) "</h2>"
           "<div class=\"kind" (when (error-shown? status) " bad") "\">"
           (esc (or (when (error-shown? status) error) (label-of kind status))) (clock c) "</div>"
           "<div class=\"btns\">"
           ;; a preview: nothing is deployed, nothing to reload
           (when (and (can-reload? status) (not (:preview? c)))
             (act (if (= :failed status) "primary" "") (cond (not= :one-shot kind) "Reload" (= :ready status) "Run" :else "Run again")
                  "/api/trigger/" (:confirm c)))
           ;; its output lives in the Logs tab, opened on this component alone
           "<button type=\"button\" class=\"btn open-logs\" data-name=\"" (esc (name cname)) "\">Show log</button>"
           (when (can-stop? status) (act "danger" "Stop" "/api/stop/"))
           "</div>"
           (when-let [d (:description c)] (str "<p class=\"about\">" (esc d) "</p>"))
           (section "Source"
                    (when chip (row "Branch" (esc (or branch "detached")) (when off? "bad")))
                    (when off? (row "Preset wants" (esc wanted-branch)))
                    (when chip (row "Runs from" (case from :origin "origin, in a worktree" :local "your local branch, in a worktree" "your clone, as it is")))
                    (when (and chip (pos? behind)) (row (str "Behind " (esc base)) (str behind " commit" (when (> behind 1) "s"))))
                    (when-let [dir (:dir source)] (row "Folder" (esc (tildify dir)))))
           (apply section "Ports"
                  (for [[p live? svc] (ports-of c)]
                    (row (str "localhost:" (esc p) (when svc (str " <small>" (esc svc) "</small>")))
                         (if live? "Listening" "Closed") (when live? "ok"))))
           (section "Connections"
                    (row "Waits for" (names deps))
                    (row "Waited for by" (names (:waited-by c))))
           (apply section "Checks"
                  (for [{check :name verdict :status} (:checks c)]
                    (row (esc (name check)) (case verdict :pass "Passed" :fail "Failed" "Can't tell") (name (or verdict :unknown)))))
           "</div>"))))
