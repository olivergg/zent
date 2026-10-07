(ns zent.ui.render
  "Server-side HTML for the dashboard (htmx), as pure functions of
  zent.ui.state's maps. The DAG layout stays client-side - it needs
  :status/:deps as data - so snapshots carry the raw data plus a rendered
  :card per component.

  No hiccup lib (the vocabulary is small, and Jolt compatibility isn't
  free). `esc` is the one thing that has to be right: :error/:repo/...
  carry subprocess- and catalog-sourced text."
  (:require [clojure.string :as str]))

(defn- esc
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
   :ready "ready"})

(def ^:private one-shot-label
  "A job's own wording where a service's would mislead - nothing to stop."
  {:off "not run" :down "not run" :pending "queued" :deploying "running" :ready "on demand"})

(defn label-of
  "A :status as shown - the dashboard's wording, also the CLI's/MCP's."
  ([status] (get state-label status (name status)))
  ([kind status] (or (when (= :one-shot kind) (one-shot-label status)) (label-of status))))

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
(defn- can-stop? [status] (contains? #{:up :deploying :idle} status))
(defn- links-visible? [status] (contains? #{:up :external :deploying} status))

(defn- workdir
  "A checkout as shown: its branch, its dir (a click copies `cd <dir>`), whether it's a worktree."
  [{:keys [branch dir worktree]}]
  (str (when branch (str "<span class=\"branch\">" (esc branch) "</span>"))
       "<span class=\"workdir\">"
       "<button type=\"button\" class=\"workdir-open\" data-dir=\"" (esc dir) "\" title=\"Copy `cd " (esc dir) "`\">"
       (esc (tildify dir)) "</button>"
       (when worktree " <em class=\"wt\">worktree</em>")
       "</span>"))

(defn- source-env-rows
  "What a :source-env component reads: one row per var (its name the tooltip)."
  [source-env]
  (apply str (map (fn [[var src]] (str "<span class=\"source-env\" title=\"" (esc var) "\">" (workdir src) "</span>"))
                  (sort-by key source-env))))

(defn- card-body
  "A full card's content - everything but the error and the actions."
  [cname {:keys [kind status repo repo-url port services service-status source source-env links permanent] :as c}]
  (let [show-links? (links-visible? status)]
    (str
     "<span class=\"name\">" (esc (name cname)) "</span>"
     (when permanent
       "<span class=\"permanent\" title=\"Stays up across preset switches, stop-everything and zent exiting - only its own Stop reaches it\">permanent</span>")
     "<span class=\"meta\"><span class=\"kind\"><i class=\"glyph\">" (esc (get kind-glyph kind "·"))
     "</i>" (esc (name kind)) "</span>"
     "<span class=\"state\">" (esc (label-of kind status)) "</span></span>"
     (when repo
       (str "<span class=\"repo\">"
            (if repo-url
              (str "<a class=\"repo-link\" href=\"" (esc repo-url) "\" target=\"_blank\" rel=\"noopener\">" (esc repo) "</a>")
              (esc repo))
            "</span>"))
     (service-rows c)
     (when (and port (not (or (seq service-status) (seq services))))
       (str "<span class=\"ports own\">:" (esc port) "</span>"))
     (when source (workdir source))
     (source-env-rows source-env)
     (when show-links?
       (apply str (map (fn [{:keys [url label]}]
                          (str "<a class=\"endpoint\" href=\"" (esc url) "\" target=\"_blank\" rel=\"noopener\">" (esc label) "</a>"))
                        links))))))

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
            "<span class=\"state\">" (esc (label-of kind status)) "</span>"
            (when description (str "<span class=\"desc\">" (esc description) "</span>"))
            (source-env-rows source-env))
       (card-body cname c))
     ;; only while :failed - a stale error must not linger after recovery
     (when (and error (= status :failed)) (str "<span class=\"fray\">" (esc error) "</span>"))
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

(def ^:private summary-order ["up" "done" "ready" "idle" "starting" "stopping" "pending" "failed" "off" "stopped"])

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
         (when (and (:error c) (= :failed (:status c))) (str "<dt>error</dt><dd>" (esc (:error c)) "</dd>"))
         "</dl>"
         ;; its output lives in the Logs tab, opened on this component alone
         "<button type=\"button\" class=\"open-logs\" data-name=\"" (esc (name cname)) "\""
         " title=\"Open the Logs tab on this component\">&#10530; Logs</button>"
         "</div>")))
