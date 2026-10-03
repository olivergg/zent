(ns zent.cli
  "The `zent` command line, for any catalog. One resident daemon (`serve`)
  owns the running stack; every other verb is a client of its HTTP API
  (zent.client), same as the dashboard and the MCP server (zent.mcp).

    zent serve [--detach] [--ui]  run the daemon: in this terminal, or in the
                                  background (output in ~/.cache/zent/serve.log)
    zent watch <preset> [--ui]    serve, with <preset> applied at start
    zent apply <preset>           switch to <preset>, starting the daemon if
    zent <preset>                 needed, and wait for it to settle
    zent status                   what's running
    zent stop [<component>...]    stop all but :permanent ones / just these
    zent reload <component>       redeploy now
    zent shutdown                 stop the daemon (:permanent ones stay up)
    zent reload-code              reload the daemon's code, keeping what runs
    zent presets                  list presets (* = active, + = yours)
    zent preview <preset>         what a preset would run, and change in what runs now
    zent check                    every preset resolves and validates (a catalog's test)
    zent preset save <name> <edn> save a preset of your own (zent.presets),
    zent preset rm <name>         registering it with a running daemon
    zent logs [-f] <component>    a component's output (see zent.logs)
    zent attach                   follow a detached daemon's output
    zent version                  this zent's version (brew: HEAD-<sha>; a clone: git describe)
    zent mcp                      MCP server on stdio (zent.mcp)
    zent down                     no daemon: tear down what the session lists

  `logs -f`, `attach` and `version` are handled by the launcher script (bin/zent).

  `ctx` below: {:catalog c :name kw :bin launcher-path}.
  :catalog may be a fn, called on each use so an edited catalog is picked up; :name
  keys the user's preset dir (zent.presets/user-dir); :bin is what --detach
  spawns."
  (:require [clojure.string :as str]
            [zent.client :as client]
            [zent.daemon :as daemon]
            [zent.engine :as engine]
            ;; the built-in kinds' defmethods: a data catalog requires nothing
            [zent.kinds]
            [zent.logs :as logs]
            [zent.presets :as presets]
            [zent.session :as session]
            [zent.shell :as shell]
            [zent.ui.bridge :as bridge]
            [zent.ui.render :as render]
            [zent.ui.server :as server]))

(def log-tail-lines 200)
(def ^:private port
  ;; ZENT_PORT: a second daemon (another $HOME, e.g. docs/demo) beside the usual one
  (or (some-> (System/getenv "ZENT_PORT") parse-long) 8765))
(def ui-url (str "http://127.0.0.1:" port))
(def ^:private detach-timeout-ms 120000)

(defn- fail! [msg] (throw (ex-info msg {})))

(defn- preset-dir [ctx] (or (:preset-dir ctx) (presets/user-dir (:name ctx))))

(defn catalog
  "The catalog plus the user's own presets - re-read on every call, so a
  long-lived caller (zent.mcp) sees a preset it just saved."
  [ctx]
  (let [mine (into {} (filter (fn [[p overlay]]
                                ;; a file edited by hand gets the same rule as one saved here
                                (try (presets/check-runtime-overlay! p overlay) true
                                     (catch clojure.lang.ExceptionInfo e
                                       (binding [*out* *err*] (println "ignoring" (ex-message e)))
                                       false))))
                   (presets/load-files (preset-dir ctx)))]
    (update (let [c (:catalog ctx)] (if (fn? c) (c) c)) :presets merge mine)))

(defn- open-browser!
  "Best-effort `open` (macOS) or `xdg-open` (Linux) - a dashboard nobody looks
  at because they forgot the URL isn't worth failing over, so any hiccup is silent."
  [url]
  (let [opener (if (str/starts-with? (System/getProperty "os.name") "Mac") "open" "xdg-open")]
    (try (shell/sh! [opener url]) (catch Exception _ nil))))

(defn- live [] (daemon/live daemon/default-path))

(defn- version [] (System/getenv "ZENT_VERSION"))

(defn- warn-if-stale!
  "`d`, after a warning when it runs another zent version than this one."
  [d]
  (when (daemon/outdated? d (version))
    (binding [*out* *err*]
      (println (format (str "warning: zent serve (pid %d) runs zent %s, this is %s - "
                            "`zent shutdown && zent serve --detach` moves it here, keeping what runs")
                       (:pid d) (:version d) (version)))))
  d)

(defn- refuse-if-running! []
  (when-let [{:keys [pid]} (live)]
    (fail! (format "zent serve already running (pid %d) - `zent status`, `zent attach`" pid))))

(defn- serve! [ctx preset ui?]
  (refuse-if-running!)
  (let [token (server/start! :port port)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(daemon/clear! daemon/default-path)))
    ;; the card goes up only once the session is adopted: a client (or
    ;; --detach, waiting on it) never sees a stack that's still being filled in
    (bridge/serve! (catalog ctx) :initial-preset preset
                   :catalog-fn #(catalog ctx)
                   :on-ready (fn []
                               (daemon/register! daemon/default-path {:port port :token token :version (version)})
                               (when ui? (open-browser! ui-url)))))
  ;; only reached on :shutdown - the server's own thread would keep us alive
  (System/exit 0))

(defn detach!
  "Starts `<bin> serve` in the background and waits for its card. nohup:
  closing this terminal must not take the daemon with it."
  [ctx ui?]
  (refuse-if-running!)
  (spit daemon/log-path "")
  (let [proc (shell/spawn! (cond-> ["nohup" (:bin ctx) "serve"] ui? (conj "--ui"))
                           :log-file daemon/log-path)
        deadline (+ (System/currentTimeMillis) detach-timeout-ms)]
    (loop []
      (if-let [d (live)]
        (do (println (format "zent serve running (pid %d) - %s, `zent attach` for its output" (:pid d) ui-url))
            d)
        (if (or (not (.isAlive proc)) (> (System/currentTimeMillis) deadline))
          (do (run! println (take-last 20 (str/split-lines (slurp daemon/log-path))))
              (fail! "zent serve didn't come up - see its output above"))
          (do (Thread/sleep 300) (recur)))))))

(defn- daemon! []
  (warn-if-stale! (or (live) (fail! "no zent serve running - `zent serve --detach`"))))

(defn- print-change [n old new]
  (println (format "  %-28s %s -> %s" (name n) (or old "-") new)))

(defn- print-view [{:keys [preset components]}]
  (println "preset:" (or preset "none"))
  (doseq [[n {:keys [kind status permanent error]}] (sort-by key components)]
    (println (str/trimr (format "  %-28s %-17s %-10s %s%s" (name n) kind
                                (render/label-of (keyword kind) (keyword status))
                                (if permanent "permanent " "")
                                (if (and error (= "failed" status)) error ""))))))

(defn- run-command!
  "POSTs `path`, waits for the daemon to process it, prints the result -
  and fails (non-zero exit) if the daemon reports the command failed."
  [d path]
  (let [queued (client/request d :post path)
        view (client/await! d queued print-change)]
    (print-view view)
    (when-let [error (get-in view [:command-errors (keyword (str (:id queued)))])]
      (fail! error))))

(defn- check-preset! [ctx preset]
  (let [known (:presets (catalog ctx))]
    (when-not (contains? known preset)
      (fail! (format "unknown preset %s - known: %s" (name preset)
                     (str/join ", " (sort (map name (keys known)))))))))

(defn- check-components! [d names]
  (let [known (set (keys (:components (client/request d :get "/api/view"))))]
    (when-let [unknown (seq (remove known (map keyword names)))]
      (fail! (format "not in the running stack: %s" (str/join ", " (map name unknown)))))))

(defn apply! [ctx preset]
  (check-preset! ctx preset)
  (let [d (or (some-> (live) warn-if-stale!) (detach! ctx false))]
    (run-command! d (str "/api/apply/" (name preset)))))

(defn status! []
  (if-let [{:keys [pid started-at] :as d} (some-> (live) warn-if-stale!)]
    (do (println (format "zent serve pid %d (zent %s), up %d min - %s" pid (:version d "unknown")
                         (quot (- (System/currentTimeMillis) started-at) 60000) ui-url))
        (print-view (client/request d :get "/api/view")))
    (let [recorded (:handles (session/read-session session/default-path))]
      (println "no zent serve running")
      (when (seq recorded)
        (println "session still lists (`zent serve` adopts them, `zent down` tears them down):"
                 (str/join ", " (map (comp name :component) recorded)))))))

(defn stop! [names]
  (let [d (daemon!)]
    (if (seq names)
      (do (check-components! d names)
          ;; all queued first: the last id covers the whole batch, events from the first's :seq
          (let [queued (mapv #(client/request d :post (str "/api/stop/" (name %))) names)]
            (print-view (client/await! d {:id (:id (peek queued)) :seq (:seq (first queued))}
                                       print-change))))
      (run-command! d "/api/stop-all"))))

(defn reload! [component]
  (let [d (daemon!)]
    (check-components! d [component])
    (run-command! d (str "/api/trigger/" (name component)))))

(defn reload-code! []
  (let [d (daemon!)
        view (client/await! d (client/request d :post "/api/reload-code") print-change)
        {:keys [reloaded failed error]} (:code-reload view)]
    (if failed
      (fail! (format "reloaded %d namespace(s), then %s failed: %s" reloaded failed error))
      (println (format "reloaded %d namespace(s)" reloaded)))))

(defn- shutdown! []
  (let [d (daemon!)]
    (client/request d :post "/api/shutdown")
    (while (live) (Thread/sleep 300))
    (println "zent serve stopped")))

(defn presets! [ctx]
  (let [active (some-> (live) (client/request :get "/api/view") :preset)]
    (doseq [p (sort (map name (keys (:presets (catalog ctx)))))]
      (println (str (if (= p active) "*" " ")
                    (if (presets/user-preset? (preset-dir ctx) p) "+" " ")
                    " " p)))))

(defn preview! [ctx preset]
  (check-preset! ctx preset)
  (let [c (catalog ctx)]
    (doseq [[n {:keys [kind deps permanent context use-secret-env secret-env]}]
            (sort-by key (presets/validate! c preset (get-in c [:presets preset])))]
      (println (str/trimr (format "  %-28s %-17s %s" (name n) (name kind)
                                  (str/join " " (cond-> []
                                                  permanent (conj "permanent")
                                                  context (conj (str "context " context))
                                                  use-secret-env (conj (str "secrets from " (str/join "," (distinct (map :context (vals secret-env))))))
                                                  (seq deps) (conj (str "waits for " (str/join ", " (map name deps)))))))))))
  ;; what an apply would change, from what the running daemon has up
  (when-let [{:keys [redeploy] :as p} (some-> (live) (client/request :get (str "/api/preview/" (name preset))) :plan)]
    (let [row (fn [k label] (when (seq (k p)) [[label (str/join ", " (map name (k p)))]]))
          lines (concat (row :stop "stop") (row :keep-warm "keep warm")
                        (for [[n why] redeploy] ["redeploy" (str (name n) " (" why ")")])
                        (row :start "start") (row :unchanged "unchanged"))]
      (println "\nApplied now, it would:")
      (if (seq lines)
        (doseq [[what who] lines] (println (format "  %-10s %s" what who)))
        (println "  change nothing")))))

(defn check!
  "Validates every preset as an apply would; fails if any is refused."
  [ctx]
  (let [c (catalog ctx)
        all (sort (keys (:presets c)))
        bad (filterv (fn [p]
                       (try (presets/validate! c p (get-in c [:presets p])) false
                            (catch clojure.lang.ExceptionInfo e
                              (println (format "  %s: %s" (name p) (ex-message e)))
                              true)))
                     all)]
    (if (seq bad)
      (fail! (format "%d of %d preset(s) refused" (count bad) (count all)))
      (println (format "%d preset(s) ok" (count all))))))

(defn save-preset!
  "Validates and saves `text` as the user's preset `preset`, and registers it
  with a running daemon so it can be applied right away."
  [ctx preset text]
  (presets/save! (catalog ctx) (preset-dir ctx) preset text)
  (println (format "saved %s/%s.edn" (preset-dir ctx) (name preset)))
  (when-let [d (live)]
    (client/request d :post (str "/api/presets/" (name preset)) text)
    (println "registered with the running zent serve")))

(defn delete-preset! [ctx preset]
  (presets/delete! (preset-dir ctx) preset)
  (println "deleted" (name preset))
  (when-let [d (live)]
    (client/request d :post (str "/api/presets/" (name preset) "/delete"))))

(defn- down! []
  (when-let [{:keys [pid]} (live)]
    (fail! (format "zent serve (pid %d) owns the session - `zent stop` or `zent shutdown`" pid)))
  (let [{:keys [stopped errors]} (engine/down!)]
    (println (format "stopped %d, %d error(s)" (count stopped) (count errors)))
    (doseq [{:keys [handle error]} errors]
      (println "  failed:" (pr-str handle) "-" (ex-message error)))))

(defn- print-log [component lines]
  (if (seq lines)
    (run! println lines)
    (println (format "no log for %s - has it been started?" (name component)))))

(defn logs! [component n]
  (print-log component (logs/tail (keyword component) n)))

(defn redacted-logs!
  "logs! for a reader who mustn't see secrets (zent.mcp): through the daemon,
  which masks what it read (zent.secrets/redact). Without one, only a
  component that reads no secrets is served from the file directly. Plain
  text either way: colour codes are noise to an agent."
  [ctx component n]
  (if-let [d (live)]
    (print-log component (take-last n (:lines (client/request d :get (str "/api/logs/" (name component))))))
    (if (get-in (catalog ctx) [:components (keyword component) :secret-env])
      (fail! (format "%s reads secrets - its log is only served through a running zent serve, which masks them"
                     (name component)))
      (print-log component (map logs/strip-ansi (logs/tail (keyword component) n))))))

(defn- dispatch [ctx verb args ui?]
  (let [[arg arg2] args
        need (fn [x what] (or x (fail! (str "usage: zent " verb " " what))))]
    (case verb
      "serve" (serve! ctx nil ui?)
      "watch" (serve! ctx (keyword (need arg "<preset>")) ui?)
      "apply" (apply! ctx (keyword (need arg "<preset>")))
      "status" (status!)
      "stop" (stop! args)
      "reload" (reload! (need arg "<component>"))
      "shutdown" (shutdown!)
      "reload-code" (reload-code!)
      "presets" (presets! ctx)
      "preview" (preview! ctx (keyword (need arg "<preset>")))
      "check" (check! ctx)
      "preset" (case arg
                 "save" (save-preset! ctx (keyword (need arg2 "save <name> <edn>"))
                                      (need (nth args 2 nil) "save <name> <edn>"))
                 "rm" (delete-preset! ctx (keyword (need arg2 "rm <name>")))
                 (fail! "usage: zent preset save <name> <edn> | rm <name>"))
      "mcp" ((requiring-resolve 'zent.mcp/serve!) ctx)
      "down" (down!)
      "logs" (logs! (need arg "<component>") log-tail-lines)
      (if (and verb (contains? (:presets (catalog ctx)) (keyword verb)))
        (apply! ctx (keyword verb))
        (fail! (str (when verb (str "unknown verb or preset: " verb "\n"))
                    "usage: zent serve|watch|apply|status|stop|reload|reload-code|shutdown|presets|preview|check|preset|logs|attach|mcp|down|version - see zent.cli"))))))

(defn main
  "Runs the `zent` command line `args` against `ctx`."
  [ctx args]
  (let [flags (set (filter #(str/starts-with? % "--") args))
        [verb & operands] (remove flags args)]
    (try
      (if (and (= verb "serve") (flags "--detach"))
        (detach! ctx (flags "--ui"))
        (dispatch ctx verb operands (flags "--ui")))
      ;; ex-info is how every verb reports a user-facing error (bad preset,
      ;; no daemon, refused config) - anything else is a bug, trace and all
      (catch clojure.lang.ExceptionInfo e
        (println (ex-message e))
        (System/exit 1)))))
