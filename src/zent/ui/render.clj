(ns zent.ui.render
  "Server-side HTML for the dashboard (htmx), as pure functions of
  zent.ui.state's maps. The DAG layout stays client-side - it needs
  :status/:deps as data - so snapshots carry the raw data plus a rendered
  :card per component.

  No hiccup lib (the vocabulary is small, and Jolt compatibility isn't
  free). `esc` is the one thing that has to be right: :error/:repo/...
  carry subprocess- and catalog-sourced text."
  (:require [clojure.string :as str]
            [zent.source :as source]))

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
   :ready "ready" :unready "unready"})

(def ^:private error-shown?
  "Statuses whose :error is shown - only while in them: a stale error must not linger after recovery."
  #{:failed :unready})

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

(def ^:private kind-glyph
  "A mark per kind - geometric glyphs, not emoji (which render per platform)."
  {:process "▸" :quarkus-app "▸" :docker-compose "▤"
   :compose-services "▥" :one-shot "↓" :external "◇" :k8s "⎈"})

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

(defn- port-tag [ports]
  (if (seq ports)
    (str "<span class=\"ports\">" (str/join " " (map #(str ":" (esc %)) ports)) "</span>")
    ""))

(defn- service-rows
  "Per-container status/health for a compose-backed component; declared
  names only, before the stack is up or when docker didn't answer."
  [{:keys [service-status services]}]
  (let [rows (or (seq service-status) (map (fn [s] {:service s}) services))]
    (if (seq rows)
      (str "<span class=\"services\">"
           (apply str
                  (map (fn [{:keys [service state health ports]}]
                         (str "<span class=\"svc\" data-health=\"" (svc-class {:state state :health health}) "\" title=\""
                              (esc (when state (str state (when health (str " / " health)))))
                              "\"><span class=\"svc-name\">" (esc (name service)) "</span>" (port-tag ports) "</span>"))
                       rows))
           "</span>")
      "")))

(defn- can-reload? [status] (not (contains? #{:off :external :stopping} status)))
(defn- can-stop? [status] (contains? #{:up :deploying :idle :unready} status))
(defn- links-visible? [status] (contains? #{:up :external :deploying} status))

(defn- short-sha [sha] (when sha (subs sha 0 (min 7 (count sha)))))

(def ^:private running? #{:up :unready :idle :done})

(defn git-chip
  "A card's git state: its :git (zent.source/git-status) plus what the preset wanted; nil before git
  answered. :default? on the default branch, none wanted (no label then); :off? not the preset's
  :branch - without one, the main clone runs as it is, whatever its branch; :wt? in its own worktree;
  :lose? commits that worktree's next deploy (reset --hard) drops; :stale? running, but the disk moved
  on from the commit deployed. Its counts are never nil: 0 when git didn't tell."
  [{:keys [git source wanted-branch deployed-sha status preview-of]}]
  (when-let [{:keys [branch sha base ahead] :as git} (when git (merge-with #(or %2 %1) {:behind 0 :ahead 0 :dirty 0} git))]
    (let [wt? (boolean (:worktree source))]
      (assoc git
             :wanted-branch wanted-branch
             :default? (and (not wanted-branch) (contains? (if base #{base} #{"main" "master"}) branch))
             :off? (boolean (and wanted-branch (not= wanted-branch branch)))
             :wt? wt?
             :lose? (boolean (and wt? (pos? ahead)))
             :stale? (boolean (and deployed-sha sha (running? status) (not= deployed-sha sha)))
             :deployed-sha deployed-sha
             :preview-of preview-of))))

(defn short-branch
  "A branch as a card can show it: no feature/, bug/... prefix, cut in the middle - a ticket number
  usually starts it, what it's about ends it."
  [branch]
  (let [b (str/replace (str branch) #"^(feature|bugfix|bug|fix|hotfix|chore|release)/" "")]
    (if (<= (count b) 22) b (str (subs b 0 12) "\u2026" (subs b (- (count b) 9))))))

(defn- git-facts
  "A chip's facts, one sentence each (nil when there's nothing to say): the `cd` hover lists them all,
  each piece of the git row shows its own."
  [{:keys [wanted-branch branch sha base behind ahead pushed dirty fetched-at deployed-sha off? wt? lose? stale?] :as chip} now]
  {:branch (str (or branch (str "detached at " (short-sha sha))) "\n"
                (cond off? (str "the preset wants " wanted-branch " - not this one")
                      wanted-branch "the preset's :branch"
                      :else "no :branch in the preset: runs whatever this clone has checked out"))
   :from (if wt?
           (str "runs origin/" branch ": its own worktree, reset to it on each deploy - push to run a change")
           "runs your local clone as it is, uncommitted changes included")
   :behind (when (pos? behind)
             (str behind " commit(s) on origin/" base " not in this checkout - "
                  (or (source/rebase-refusal chip wt?) "rebase: the button fetches, then rebases if nothing conflicts")
                  (if fetched-at (str " (as of the last fetch, " (duration (max 0 (- now fetched-at))) " ago)") " (never fetched)")))
   :ahead (when (pos? ahead)
            (str ahead " commit(s)" (if pushed (str " not on origin/" branch) " pushed nowhere")
                 (if lose? " - the next deploy's reset --hard drops them: push first" " - safe here: the clone runs as it is")))
   :dirty (when (pos? dirty)
            (str dirty " modified or untracked file(s)"
                 (if wt? " - the next deploy drops them" " - they run too: the clone runs as it is")))
   :stale (when stale? (str "running " (short-sha deployed-sha) ", the disk is at " (short-sha sha) ": reload to run it"))})

(defn- git-title
  "The `cd` hover: every fact, then the commit and the dir."
  [chip dir now]
  (->> (conj (mapv (git-facts chip now) [:branch :from :behind :ahead :dirty :stale])
             (when-not (:fetched-at chip) "never fetched") (:sha chip) (tildify dir))
       (remove nil?)
       (map #(str/replace (esc %) "\n" "&#10;"))
       (str/join "&#10;")))

(defn- git-row
  "The chip, a section of its own - nil when it has nothing to say: the branch unless the default one
  (red when not the preset's :branch, dashed in a worktree), where the code comes from (origin: a
  worktree reset on deploy; local: the clone as it is), then each count with its word - each piece
  with its fact on hover."
  [cname {:keys [branch sha base behind ahead pushed dirty wanted-branch default? off? wt? lose? stale? preview-of] :as chip} now]
  (let [facts (git-facts chip now)
        span (fn [cls fact body] (str "<span class=\"" cls "\" title=\"" (str/replace (esc (facts fact fact)) "\n" "&#10;") "\">" body "</span>"))
        rebase (when (and (pos? behind) (not (source/rebase-refusal chip wt?)))
                 (str "<button type=\"button\" class=\"act rebase\" title=\"Fetch, then rebase " (esc branch) " onto origin/"
                      (esc base) " if nothing conflicts\" hx-post=\"/api/rebase/" (esc (name cname)) (when preview-of (str "?preset=" (esc preview-of)))
                      "\" hx-swap=\"none\" hx-disabled-elt=\"this\" hx-confirm=\""
                      (esc (str "Rebase " branch " onto origin/" base "?"
                                (when pushed (str " It is on origin: push --force-with-lease afterwards."))))
                      "\">rebase</button>"))
        counts (str (when (pos? behind) (str (span "behind" :behind (str "&#8595;" behind " " (esc base))) rebase))
                    (when (pos? ahead)
                      (if lose?
                        (span "ahead lose" :ahead (str "&#8593;" ahead " lost on next deploy &#9888;"))
                        (span "ahead" :ahead (str "&#8593;" ahead " unpushed"))))
                    (when (pos? dirty) (span (str "dirty" (when wt? " lose")) :dirty (str "&#9679;" dirty " uncommitted")))
                    (when stale? (span "stale" :stale "&#8800; reload")))]
    (when (or (not default?) (seq counts))
      (str "<span class=\"git\">"
           (when-not default?
             (str (span (str "branch" (when wt? " wt") (when off? " off")) :branch
                        (str "&#9095; " (esc (if branch (short-branch branch) (short-sha sha)))))
                  (when off? (span "want" (str "the preset wants " wanted-branch ", this is " branch)
                                   (str "&#8800; " (esc (short-branch wanted-branch)))))
                  (span (if wt? "from origin" "from local") :from (if wt? "&#10227; origin" "&#9998; local"))))
           counts "</span>"))))

(defn- checkout
  "A checkout in one line, the details on hover: its branch (not main/master; dashed in a worktree) unless
  git was read (`cd-title`, git-title: its row shows it), then `cd` - its dir, a click copies `cd <dir>`."
  [{:keys [branch dir worktree]} & [cd-title]]
  (str (when (and branch (not cd-title) (not (#{"main" "master"} branch)))
         (str "<span class=\"branch" (when worktree " wt") "\" title=\"" (esc branch)
              (when worktree " - its own worktree, reset to origin on each deploy") "\">" (esc branch) "</span>"))
       (when dir
         (str "<button type=\"button\" class=\"workdir-open\" data-dir=\"" (esc dir) "\" title=\""
              (or cd-title (esc (tildify dir))) "&#10;Copy `cd " (esc dir) "`\">cd</button>"))))

(defn- source-env-rows
  "What a :source-env component reads: one row per var (its name the tooltip)."
  [source-env]
  (apply str (map (fn [[var src]] (str "<span class=\"source-env\" title=\"" (esc var) "\">" (checkout src) "</span>"))
                  (sort-by key source-env))))

(defn- facts
  "A card's one line of facts, the details on hover: the kind as its glyph, its checkout, ↗ (the repo), its
  own port - then the state."
  [{:keys [kind status repo repo-url port services service-status source] :as c} cd-title]
  (str "<span class=\"meta\">"
       "<i class=\"glyph\" title=\"" (esc (name kind)) "\">" (esc (get kind-glyph kind "·")) "</i>"
       (checkout source cd-title)
       (when repo
         (if repo-url
           (str "<a class=\"repo-link\" href=\"" (esc repo-url) "\" target=\"_blank\" rel=\"noopener\" title=\"" (esc repo) "\">&#8599;</a>")
           (str "<span class=\"repo-link\" title=\"" (esc repo) "\">&#8599;</span>")))
       (when (and port (not (or (seq service-status) (seq services))))
         (str "<span class=\"ports own\">:" (esc port) "</span>"))
       "<span class=\"state\">" (esc (label-of kind status)) (clock c) "</span></span>"))

(defn- card-body
  "A full card's content - everything but the error and the actions."
  [cname {:keys [status source-env links permanent source] :as c}]
  (let [chip (git-chip c)
        cd-title (when chip (git-title chip (:dir source) (System/currentTimeMillis)))]
    (str
     "<span class=\"head\"><span class=\"name\">" (esc (name cname)) "</span>"
     (when permanent
       "<span class=\"permanent\" title=\"Permanent: stays up across preset switches, stop-everything and zent exiting - only its own Stop reaches it\">&#8734;</span>")
     "</span>"
     (facts c cd-title)
     (if chip
       (git-row cname chip (System/currentTimeMillis))
       ;; a preview's :branch never deployed: its worktree doesn't exist yet
       (when (and (:preview? c) (:worktree source) (:wanted-branch c))
         (str "<span class=\"git\"><span class=\"branch wt\" title=\"" (esc (:wanted-branch c)) "\">&#9095; "
              (esc (short-branch (:wanted-branch c))) "</span><span class=\"from origin\" title=\"its worktree is created at apply, from origin/"
              (esc (:wanted-branch c)) "\">&#10227; origin, created at apply</span></span>")))
     (service-rows c)
     (source-env-rows source-env)
     (when (and (links-visible? status) (seq links))
       (str "<span class=\"endpoints\">"
            (apply str (map (fn [{:keys [url label]}]
                               (str "<a class=\"endpoint\" href=\"" (esc url) "\" target=\"_blank\" rel=\"noopener\">" (esc label) "</a>"))
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
     (if attached-to
       (str "<i class=\"glyph\">" (esc (get kind-glyph kind "·")) "</i>"
            "<span class=\"name\" title=\"" (esc (name cname)) "\">"
            (esc (if (seq scripts) (str/join " → " (map script-name scripts)) (name cname))) "</span>"
            "<span class=\"state\">" (esc (label-of kind status)) (clock c) "</span>"
            (when description (str "<span class=\"desc\">" (esc description) "</span>"))
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

(def ^:private summary-order ["up" "done" "ready" "idle" "starting" "stopping" "pending" "unready" "failed" "off" "stopped"])

(defn- git-summary
  "What the chips ask of you, one line each, with the components' names: not the preset's :branch,
  commits the next deploy drops, a disk ahead of what runs, behind the default branch. Nothing to do:
  nothing shown."
  [components]
  (let [chips (keep (fn [[n c]] (some-> (git-chip c) (assoc :name (name n)))) components)
        line (fn [k head pred show]
               (when-let [cs (seq (sort-by :name (filter pred chips)))]
                 (str "<li class=\"" k "\">" head " " (esc (str/join ", " (map show cs))) "</li>")))
        lines (remove nil? [(line "off" "&#9095; not the preset's branch:" :off? :name)
                            (line "lose" "&#9888; lost on next deploy:" #(or (:lose? %) (and (:wt? %) (pos? (:dirty %))))
                                  #(str (:name %) " (" (+ (if (:lose? %) (:ahead %) 0) (if (:wt? %) (:dirty %) 0)) ")"))
                            (line "stale" "&#8800; to reload:" :stale? :name)
                            (line "behind" "&#8595; behind:" #(pos? (:behind %)) #(str (:name %) " " (:behind %) " " (:base %)))])]
    (when (seq lines)
      (str "<ul class=\"git-summary\">" (apply str lines) "</ul>"))))

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
    (str "<div id=\"detail\">"
         "<dl class=\"facts\">"
         "<dt>name</dt><dd>" (esc (name cname)) "</dd>"
         "<dt>kind</dt><dd>" (esc (name (:kind c))) "</dd>"
         "<dt>state</dt><dd>" (esc (label-of (:kind c) (:status c))) "</dd>"
         "<dt>waits for</dt><dd>" (esc (if-let [deps (seq (:deps c))] (str/join ", " (map name deps)) "nothing")) "</dd>"
         (when-let [hs (:handle-summary c)] (str "<dt>handle</dt><dd>" (esc hs) "</dd>"))
         (when (and (:error c) (error-shown? (:status c))) (str "<dt>error</dt><dd>" (esc (:error c)) "</dd>"))
         "</dl>"
         ;; its output lives in the Logs tab, opened on this component alone
         "<button type=\"button\" class=\"open-logs\" data-name=\"" (esc (name cname)) "\""
         " title=\"Open the Logs tab on this component\">&#10530; Logs</button>"
         "</div>")))
