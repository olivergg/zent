(ns zent.ui.server
  "The daemon's HTTP API and dashboard: view-state pushed over /ws/view,
  plus the endpoints the dashboard, the CLI
  (zent.client) and MCP all send. zent.engine is never required from here:
  writes are queued for the run loop (zent.ui.bridge) to act on.

  Jolt-native: ring-chez-adapter + clojure.data.json."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ring-chez.adapter :as adapter]
            [ring-chez.websocket :as ws]
            [zent.branch :as branch]
            [zent.checks :as checks]
            [zent.compose :as compose]
            [zent.graph :as graph]
            [zent.kinds :as kinds]
            [zent.logs :as logs]
            [zent.presets :as presets]
            [zent.schema :as schema]
            [zent.secrets :as secrets]
            [zent.source :as source]
            [zent.ui.logstream :as logstream]
            [zent.ui.render :as render]
            [zent.ui.state :as state]))

;; ---------------------------------------------------------------------------
;; Write guard: any page in your browser can fire a blind POST at this port
;; (CSRF), and a POST starts processes - so every write needs the
;; per-process token only same-origin JS (or zent.daemon's 0600 card) can
;; read.
;; ---------------------------------------------------------------------------

(defonce csrf-token (atom nil))

(defn- generate-token []
  (let [bytes (byte-array 24)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (.encodeToString (java.util.Base64/getUrlEncoder) bytes)))

(def ^:private local-authority "(localhost|127\\.0\\.0\\.1)(:\\d+)?")

(defn- safe-origin?
  "true when Origin is absent (same-origin/non-browser requests, and some
  browsers omit it for simple same-origin GETs) or explicitly localhost -
  belt-and-suspenders alongside the token check, not a replacement for it."
  [origin]
  (or (nil? origin) (boolean (re-matches (re-pattern (str "https?://" local-authority)) origin))))

(defn- local-host?
  "DNS-rebinding guard for every request, reads included: a hostile page
  whose domain re-resolves to 127.0.0.1 is same-origin to the browser, so
  only the Host header still says who the request was meant for. nil only
  outside real HTTP/1.1 (the adapter refuses a request without one)."
  [host]
  (or (nil? host) (boolean (re-matches (re-pattern local-authority) host))))

(defn- authorized-write?
  [req]
  (and (safe-origin? (get-in req [:headers "origin"]))
       (some? @csrf-token)
       (= @csrf-token (get-in req [:headers "x-zent-token"]))))

(defn- error-response [status message]
  {:status status :headers {"Content-Type" "application/json"}
   :body (json/write-str {:error message})})

(defn- ws-allowed?
  "The websocket upgrade's own guard - it never reaches `app`, and browsers
  don't apply the same-origin policy to websockets: without this any page you
  visit could read every snapshot. A browser always sends Origin on an
  upgrade, so here a missing one is refused too."
  [{:keys [headers uri]}]
  (and (= "/ws/view" uri)
       (local-host? (get headers "host"))
       (some? (get headers "origin"))
       (safe-origin? (get headers "origin"))))

(defn- forbidden []
  {:status 403 :headers {"Content-Type" "application/json"}
   :body (json/write-str {:error "missing/invalid X-Zent-Token header, or untrusted Origin"})})

;; connected websocket sessions - best effort: a send! returning false (slow
;; or gone client) just drops it.

(defonce clients (atom #{}))

(defn- with-cards
  "Each component gets a rendered :card fragment (zent.ui.render/component-
  card) alongside its raw data - the client still needs :status/:deps as
  structured data to run the DAG layout, so this adds to the payload
  rather than replacing it."
  [components & [members]]
  (let [now (System/currentTimeMillis)
        members (some-> members seq set)
        listening (:listening @state/view-state)
        host #(or (:attached-to (get components %)) %)
        ;; :lone - an :attached-to one-shot waiting for anything but its host (or a step on it): drawn on
        ;; its own, so its wires end on it rather than on its host's plate
        lone? (fn [[_ c]] (boolean (when-let [h (:attached-to c)] (seq (disj (set (map host (:deps c))) h)))))
        ;; a host's card carries its other one-shots, as buttons - the preset's own, and any that ran
        shown? (fn [[k c]] (or (nil? members) (members k) (not (#{:stopped :off} (:status c)))))
        shots (group-by (comp :attached-to val) (filter shown? (remove lone? (sort-by key components))))
        waited (set (mapcat :deps (vals components)))
        settled? (comp #{:up :done :external :warm} :status)
        ;; mid-apply: the first dependency a waiting one still waits for
        waits-for (fn [c] (when (and (= :waiting (:status c)) (not (:preview? c)))
                            (first (remove #(settled? (get components % {:status :up})) (:deps c)))))]
    (reduce-kv (fn [acc name c]
                 (assoc acc name (assoc c :lone (lone? [name c])
                                        :card (render/component-card name (assoc c :now now :listening listening :shots (get shots name)
                                                                                    ;; everything leads to it, nothing waits for it
                                                                                    :terminus (and (lone? [name c]) (not (waited name)))
                                                                                    :waits-for (waits-for c))))))
               {} components)))

(defn- snapshot-message
  "The websocket push: the raw state (for the client's own layout math) plus
  server-rendered fragments for the pieces that don't need it - see
  zent.ui.render's docstring for the client/server split."
  [state]
  (json/write-str
   {:type "snapshot"
    ;; :done, the recent ones: commands finish out of order, so a spinner
    ;; waits for its own id, not for the highest one done
    :data (-> state
              ;; :listening is already on the cards, as lit sockets
              (dissoc :events :listening)
              (update :done #(take-last 200 (sort %)))
              (update :components with-cards (:members state)))
    :summary-html (render/run-summary (state/shown-components state))
    :presets-html (render/preset-picker (keys (:presets @state/catalog)) (:preset state))}))

(defn- broadcast!
  "Sends the CURRENT view to every client, serialized: send! takes no lock,
  and pushes come from the pusher and new sessions - two interleaved would
  garble a frame, or land an older snapshot last. Nothing is rendered with
  no client to send it to."
  []
  (locking clients
    (when (seq @clients)
      (let [msg (snapshot-message @state/view-state)]
        (doseq [session @clients]
          (when-not (ws/send! session msg)
            (swap! clients disj session)))))))

;; Every view-state change pushes the whole snapshot - small enough not to
;; diff - from the pusher's own thread, never the swap!'s: that one is the
;; engine's (its loop, deploy worker, deploys), and a stalled client (a
;; sleeping tab, a full TCP buffer) blocks a send up to the adapter's write
;; timeout. A burst of changes is one push of the latest view.
(defonce ^:private wakeups (java.util.concurrent.LinkedBlockingQueue.))

(defn- request-push! [] (.offer wakeups ::push))

(defn- start-pusher! []
  (future
    (loop []
      (let [burst (loop [acc [(.take wakeups)]]
                    (if-let [w (.poll wakeups)] (recur (conj acc w)) acc))]
        (when-not (some #{::stop} burst)
          ;; a throw must not end the pusher: the dashboard would freeze for good
          (try (broadcast!)
               (catch Exception e (println "[zent.ui] push failed:" (ex-message e))))
          (recur))))))

(defn install-broadcast-watch! []
  (start-pusher!)
  (add-watch state/view-state ::broadcast (fn [_ _ _ _] (request-push!))))

(defn uninstall-broadcast-watch! []
  (remove-watch state/view-state ::broadcast)
  (.offer wakeups ::stop))

;; The adapter hands any websocket upgrade here, before `app`. Blocks for the
;; connection's lifetime; push-only, so recv! only watches for :close.

(defn- ws-session-handler [session]
  (try
    (locking clients
      (swap! clients conj session)
      (ws/send! session (snapshot-message @state/view-state)))
    (loop []
      (let [{:keys [type] :as msg} (ws/recv! session)]
        (when (and msg (not= :close type))
          (recur))))
    ;; a recv! that throws must not leave a dead session for every push to try
    (finally (swap! clients disj session))))

(defn- json-response [data]
  {:status 200 :headers {"Content-Type" "application/json"} :body (json/write-str data)})

(defn- queued [id] (json-response {:ok true :id id :seq (:event-seq @state/view-state 0)}))

(def ^:private max-wait-ms
  "How long /api/events holds a request with nothing new - under any client
  read timeout, and one worker thread parked per waiting client."
  20000)

(defn- query-param
  "Query param `k` of `req`, URL-decoded, or nil."
  [req k]
  (some-> (some->> (:query-string req) (re-find (re-pattern (str "(?:^|&)" k "=([^&]*)"))) second)
          (java.net.URLDecoder/decode "UTF-8")))

(defn- query-long
  "The integer query param `k` of `req`, or `default`."
  [req k default]
  (or (some->> (query-param req k) (re-matches #"\d+") parse-long) default))

;; A component name as it lands in a log path (zent.logs): `../x` must not
;; reach a file outside the logs dir.
(defn- log-name? [s] (boolean (and s (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" s))))

(defn- query-names
  "Query param `k` as a comma-separated list of component names."
  [req k]
  (into [] (comp (filter log-name?) (distinct) (map keyword))
        (some-> (query-param req k) (str/split #","))))

(defn- events-response
  "Status transitions past ?since=, once there are some or command ?id= is
  handled - a long-poll, so a client sees every transition in order instead
  of sampling snapshots."
  [req]
  (json-response (state/await-events (query-long req "since" 0) (query-long req "id" Long/MAX_VALUE)
                                     max-wait-ms)))

(defn- not-found [] {:status 404 :body "not found"})

;; catalog + schema: what this catalog can run and what each kind takes -
;; static, so fetched rather than pushed.

(defn- catalog-response []
  (if-let [{:keys [defaults components presets]} @state/catalog]
    (json-response {:defaults defaults
                    :components components
                    :presets presets})
    (error-response 409 "no catalog recorded - was the UI started with one?")))

(defn- schema-response []
  (json-response (schema/describe-kinds)))

;; preview: a preset resolved as an apply would (pure) and rendered with the
;; live cards, all :waiting and :preview? - so no reload/stop buttons - plus
;; its :plan from what runs now.

(defn- previewed
  "`preset-name` as it would run, a view of its own (zent.ui.state/set-preset): every component
  :preview?, with what's worth seeing before applying - :source-env (what a one-shot will read), the
  services a compose one would run, and each checkout's git, read now (no fetch, in parallel): a wrong
  branch, a delay or uncommitted files show while they're cheap to fix. Throws on a preset that
  doesn't resolve."
  [preset-name]
  (let [catalog @state/catalog
        resolved (compose/resolve-preset catalog preset-name)
        preview (state/set-preset state/empty-state preset-name resolved
                                  {:repo-url-template (get-in catalog [:defaults :repo-url-template])})]
    (assoc preview :components
           (into {} (pmap (fn [[k c]]
                            (let [cfg (resolved k)]
                              [k (cond-> (assoc c :preview? true :preview-of (name preset-name))
                                   (:source-env cfg) (assoc :source-env (state/source-env-view cfg))
                                   (= :compose-services (:kind cfg)) (assoc :services (kinds/planned-services k cfg))
                                   (:repo cfg) (assoc :source (source/describe cfg)
                                                      :git (source/checkout-git cfg)))]))
                          (:components preview))))))

(defn- preview-response [preset-name]
  (try
    (let [preview (previewed preset-name)]
      (json-response
       {:preset preset-name
        :components (with-cards (:components preview))
        :graph (:graph preview)
        :informational (:informational preview)
        :plan (when-let [f @state/planner] (f preset-name))}))
    (catch Exception e
      (error-response 400 (ex-message e)))))

(defn- component-command
  "Queues `action` on a component the view knows - an unknown name is
  refused here rather than reaching the engine."
  [action component]
  (if (contains? (:components @state/view-state) (keyword component))
    (queued (state/request! action component))
    (error-response 404 (str "unknown component " component))))

(defn- register-preset!
  "Validates and adds `text`'s preset to the running catalog (nil `text`
  removes it), then pushes a snapshot so the picker shows the change."
  [preset-name text]
  (try
    (presets/check-name! preset-name)
    (if text
      (let [overlay (presets/read-spec text)]
        (presets/check-runtime-overlay! preset-name overlay)
        (presets/validate! @state/catalog preset-name overlay)
        (swap! state/catalog assoc-in [:presets preset-name] overlay))
      (swap! state/catalog update :presets dissoc preset-name))
    (request-push!)
    (json-response {:ok true})
    (catch Exception e
      (error-response 400 (ex-message e)))))

;; The active preset's checks (zent.checks): read-only, so run straight from
;; here - on a future, never on the resident loop - one run per check at a time.
;; Their output streams like a log (/api/logs/stream, checks/log-name).

(defonce ^:private running-checks (atom #{}))
(def ^:private check-history 20)

(defn- active-checks []
  (let [preset (some-> (:preset @state/view-state) keyword)]
    [preset (get-in @state/catalog [:checks preset])]))

(defn- checks-response []
  (let [[preset checks] (active-checks)]
    (json-response
     {:preset preset
      :checks (for [{cname :name :as c} checks
                    :let [runs (checks/history preset cname check-history)]]
                (assoc (select-keys c [:name :description :observes :problem :writes])
                       :runs runs
                       :current (checks/current runs)
                       :running (contains? @running-checks [preset cname])
                       :log (name (checks/log-name preset cname))))})))

(defn- run-check!
  "POST /api/checks/run?check=x[&only=a,b]: starts the run and answers at once."
  [req]
  (let [[preset checks] (active-checks)
        wanted (query-param req "check")
        chk (first (filter #(= wanted (name (:name %))) checks))
        tests (checks/only (query-param req "only"))
        k [preset (:name chk)]]
    (cond
      (nil? chk) (error-response 404 (format "no check %s in the active preset" wanted))
      (:problem chk) (error-response 409 (:problem chk))
      ;; refuses a bad test name before anything starts
      (not (try (checks/run-cmd chk tests) (catch clojure.lang.ExceptionInfo _ nil)))
      (error-response 400 "only: test names, comma-separated")
      (contains? (first (swap-vals! running-checks conj k)) k)
      (error-response 409 (format "%s is already running" (name (:name chk))))
      :else
      (do (future
            (try (checks/run! preset chk tests)
                 (catch Exception e
                   (binding [*out* *err*] (println "check" (name (:name chk)) "failed to run:" (ex-message e))))
                 (finally (swap! running-checks disj k))))
          (json-response {:ok true})))))

;; The Branches tab: each repo the shown preset runs (?preset=: a previewed one, else the applied one),
;; as zent.graph draws it - git reads, in parallel, on the request. Its writes: a fetch (here, on the
;; request: it moves origin/* refs only), the git jobs (state/request-git!), the unused worktrees' prune.

(defn- shown-cfgs
  "{name cfg} of the components with a repo: `preset` as it resolves, else the applied one's - but the
  catalog's own repo, which runs scripts, not a project of the stack."
  [preset]
  (let [{:keys [dir]} @state/catalog
        catalog-repo? #(and dir (= dir (.getCanonicalPath (java.io.File. ^String (source/clone-dir %)))))]
    (into {} (filter (fn [[_ cfg]] (and (:repo cfg) (not (catalog-repo? cfg)))))
          (if preset (compose/resolve-preset @state/catalog preset) (state/described-cfgs)))))

;; compose/worktree-users resolves every preset (~400 ms on a real catalog): once per catalog value
(defonce ^:private preset-worktrees (atom nil))

(defn- worktree-users
  "{worktree-path #{user}}: the catalog's presets and what runs now - what the prune keeps - and the
  presets that don't resolve."
  []
  (let [catalog @state/catalog
        [seen known] @preset-worktrees
        {:keys [users unresolved]} (if (identical? seen catalog)
                                     known
                                     (second (reset! preset-worktrees [catalog (compose/worktree-users catalog)])))]
    [(merge-with into users (compose/running-worktrees (:components @state/view-state))) unresolved]))

(defn- branches-response [req]
  (let [cfgs (shown-cfgs (some-> (query-param req "preset") keyword))
        view (:components @state/view-state)
        ;; independent reads, at the same time: the worktrees no preset uses, and each project's git
        unused (future (let [[users] (worktree-users)
                             repos (compose/catalog-repos @state/catalog)]
                         (vec (for [{:keys [path repo head]} (branch/worktrees)
                                    :when (and (contains? repos repo) (empty? (get users path)))]
                                {:path path :repo repo :head head :modified (.lastModified (java.io.File. ^String path))}))))
        ;; realized here, while `unused` runs: pmap is lazy
        projects (doall
                  (pmap (fn [[dir kcs]]
                        (let [kcs (sort-by key kcs)
                              cfg (val (first kcs))]
                          (merge {:repo (:repo cfg)
                                  :components (for [[k c] kcs]
                                                (merge {:name k :kind (:kind c)}
                                                       (select-keys (get view k) [:status :deployed-sha])))}
                                 (try (if-let [{:keys [git from branch] :as p} (graph/project cfg)]
                                        ;; a worktree is detached: its branch is the preset's
                                        (assoc p :where (render/runs-from (assoc git :from from :branch (if (source/worktree-from from) branch (:branch git)))
                                                                          dir (System/currentTimeMillis)))
                                        {:error (str dir " isn't a checkout")})
                                      (catch Exception e {:error (ex-message e)})))))
                        (group-by (comp source/checkout-dir val) cfgs)))]
    (json-response {:projects projects :unused @unused})))

(defn- fetch-response
  "POST /api/branches/fetch[?preset=]: `git fetch origin` in every clone shown, in parallel."
  [req]
  (let [clones (distinct (for [[_ cfg] (shown-cfgs (some-> (query-param req "preset") keyword))]
                           (source/clone-dir cfg)))
        failed (into {} (keep (fn [[dir why]] (when why [dir why]))) (pmap (juxt identity source/fetch!) clones))]
    (json-response {:ok (empty? failed) :fetched (count clones) :failed failed})))

(defn- prune-response
  "POST /api/worktrees/prune: removes the worktrees no preset uses and nothing runs from
  (branch/prune!) - refused while a preset doesn't resolve: it may use any."
  []
  (let [[users unresolved] (worktree-users)]
    (if (seq unresolved)
      (error-response 409 (str "not pruning: " (str/join ", " (map name (sort unresolved))) " doesn't resolve, it may use any worktree"))
      (let [{:keys [removed kept]} (branch/prune! (branch/worktrees) users (compose/catalog-repos @state/catalog))]
        (json-response {:removed (map :path removed) :kept (for [[e why] kept] {:path (:path e) :why why})})))))

(defn- resource-page [path]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (slurp (io/resource path))})

(defn version "This zent's version, as bin/zent computed it." [] (System/getenv "ZENT_VERSION"))

(def ^:private static-types
  {"/htmx.min.js" "text/javascript; charset=utf-8" "/ansi_up.js" "text/javascript; charset=utf-8"
   "/favicon.svg" "image/svg+xml" "/logo.svg" "image/svg+xml"})

(defn app [req]
  (cond
    (not (local-host? (get-in req [:headers "host"])))
    {:status 403 :body "zent only answers requests addressed to localhost/127.0.0.1"}

    ;; the token is baked into the page (a <meta> htmx reads back), and the
    ;; version this daemon was started as (bin/zent's ZENT_VERSION)
    (and (= (:request-method req) :get) (contains? #{"/" "/index.html"} (:uri req)))
    (update (resource-page "zent/ui/index.html") :body
            #(-> % (str/replace "{{CSRF_TOKEN}}" (or @csrf-token ""))
                 (str/replace "{{VERSION}}" (render/esc (or (version) "")))
                 ;; in a script tag: no "</" may close it early
                 (str/replace "{{TIPS}}" (str/replace (json/write-str render/tips) "</" "<\\/"))))

    ;; the page's own style and code: read on each request, and no-cache, so
    ;; an edit shows on the next reload
    (and (= (:request-method req) :get) (#{"/index.css" "/index.js"} (:uri req)))
    {:status 200
     :headers {"Content-Type" (if (str/ends-with? (:uri req) ".css") "text/css; charset=utf-8" "text/javascript; charset=utf-8")
               "Cache-Control" "no-cache"}
     :body (slurp (io/resource (str "zent/ui" (:uri req))))}

    ;; the icons, and the libs vendored, not CDN-loaded: the dashboard works
    ;; offline. ansi_up (MIT) renders the logs' terminal colours
    (and (= (:request-method req) :get) (static-types (:uri req)))
    {:status 200 :headers {"Content-Type" (static-types (:uri req))}
     :body (slurp (io/resource (str "zent/ui" (:uri req))))}

    (and (= (:request-method req) :get) (= (:uri req) "/api/token"))
    (json-response {:token @csrf-token})

    ;; every write passes here first
    (and (= (:request-method req) :post) (not (authorized-write? req)))
    (forbidden)

    (and (= (:request-method req) :get) (= (:uri req) "/api/checks"))
    (checks-response)

    (and (= (:request-method req) :post) (= (:uri req) "/api/checks/run"))
    (run-check! req)

    (and (= (:request-method req) :get) (= (:uri req) "/api/view"))
    (json-response (dissoc @state/view-state :events))

    (and (= (:request-method req) :get) (= (:uri req) "/api/events"))
    (events-response req)

    (and (= (:request-method req) :get) (= (:uri req) "/api/catalog"))
    (catalog-response)

    (and (= (:request-method req) :get) (= (:uri req) "/api/schema"))
    (schema-response)

    (and (= (:request-method req) :get) (str/starts-with? (:uri req) "/api/preview/"))
    (preview-response (keyword (subs (:uri req) (count "/api/preview/"))))

    ;; fetched on click, not pushed: which node is selected is client-only state
    (and (= (:request-method req) :get) (str/starts-with? (:uri req) "/api/detail/"))
    (let [name (keyword (subs (:uri req) (count "/api/detail/")))]
      {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
       ;; selected in a preview: the component as that preset would run it, not as what runs now has it
       :body (let [comps (:components (if-let [p (query-param req "preset")] (previewed (keyword p)) @state/view-state))
                   [preset checks] (active-checks)]
               (render/detail-panel
                name
                (some-> (get comps name)
                        (assoc :listening (:listening @state/view-state)
                               :waited-by (sort (for [[k c] comps :when (some #{name} (:deps c))] k))
                               ;; the checks judging it (:zent/observes), each with its last verdict
                               :checks (for [{check :name :keys [observes]} checks :when (some #{name} observes)]
                                         {:name check :status (:status (checks/current (checks/history preset check check-history)))})))))})

    ;; ?c=a,b&from=a:123 - see zent.ui.logstream
    (and (= (:request-method req) :get) (= (:uri req) "/api/logs/stream"))
    (let [names (query-names req "c")]
      (if (seq names)
        (logstream/response names (into {} (keep (fn [pair]
                                                   (let [[n o] (str/split pair #":")]
                                                     (when (and (log-name? n) (re-matches #"\d+" (str o)))
                                                       [(keyword n) (parse-long o)]))))
                                      (some-> (query-param req "from") (str/split #","))))
        (error-response 400 "c: the components to stream, comma-separated")))

    (and (= (:request-method req) :get) (str/starts-with? (:uri req) "/api/logs/"))
    (let [n (subs (:uri req) (count "/api/logs/"))
          name (keyword n)]
      (cond
        (not (log-name? n)) (error-response 400 (str "not a component name: " n))
        ;; redacted: an app may echo credentials zent read for it (zent.secrets).
        ;; Plain text: this is what MCP and scripts read - the Logs tab streams.
        (secrets/maskable? name)
        (json-response {:component name :lines (mapv (comp secrets/redact logs/strip-ansi) (logs/tail name logstream/backlog-lines))})
        :else (json-response {:component name :lines [logstream/withheld-line]})))

    ;; Writes only queue onto state/control, which the engine's loop drains
    ;; (this ns never requires zent.engine). Each answers {:ok true :id n :seq s}:
    ;; done once n is in :done; /api/events from s shows what it caused
    ;; (zent.client/await!).
    (and (= (:request-method req) :get) (= (:uri req) "/api/branches"))
    (branches-response req)

    (and (= (:request-method req) :post) (= (:uri req) "/api/branches/fetch"))
    (fetch-response req)

    (and (= (:request-method req) :post) (= (:uri req) "/api/worktrees/prune"))
    (prune-response)

    (and (= (:request-method req) :post) (re-matches #"/api/undo/\d+" (:uri req)))
    (if-let [id (state/request-undo! (parse-long (subs (:uri req) (count "/api/undo/"))))]
      (queued id)
      (error-response 404 "nothing to undo there - already undone, or too old"))

    (and (= (:request-method req) :post) (re-matches #"/api/(rebase|checkout|push|fast-forward)/.*" (:uri req)))
    (let [[_ op n] (re-matches #"/api/(rebase|checkout|push|fast-forward)/(.*)" (:uri req))
          ;; from a preview: the component as that preset resolves it, not as the applied one does
          previewed (some-> (query-param req "preset") keyword)]
      (if-let [id (state/request-git! (keyword op) (keyword n)
                                      (when previewed (get (compose/resolve-preset @state/catalog previewed) (keyword n))))]
        (queued id)
        (error-response 404 (str n ": no checkout in the preset"))))

    (and (= (:request-method req) :post) (str/starts-with? (:uri req) "/api/reload/"))
    (component-command :reload (subs (:uri req) (count "/api/reload/")))

    (and (= (:request-method req) :post) (str/starts-with? (:uri req) "/api/sync/"))
    (component-command :sync (subs (:uri req) (count "/api/sync/")))

    (and (= (:request-method req) :post) (str/starts-with? (:uri req) "/api/stop/"))
    (component-command :stop (subs (:uri req) (count "/api/stop/")))

    ;; switch to a preset: what it doesn't name is torn down, :permanent excepted
    (and (= (:request-method req) :post) (str/starts-with? (:uri req) "/api/apply/"))
    (queued (state/request-apply! (subs (:uri req) (count "/api/apply/"))))

    ;; same switch, to nothing
    (and (= (:request-method req) :post) (= (:uri req) "/api/stop-all"))
    (queued (state/request-apply! nil))

    ;; POST /api/shutdown - tears down (permanent ones excepted) and ends the
    ;; resident loop; the process exits once it has.
    (and (= (:request-method req) :post) (= (:uri req) "/api/shutdown"))
    (queued (state/request-shutdown!))

    ;; POST /api/reload-catalog-and-code - re-reads the catalog and the daemon's code (zent.reload),
    ;; keeping its state; the result lands in /api/view's :code-reload
    (and (= (:request-method req) :post) (= (:uri req) "/api/reload-catalog-and-code"))
    (queued (state/request-reload-catalog-and-code!))

    ;; POST /api/presets/:name (body: EDN spec) registers a preset in the
    ;; running catalog, /api/presets/:name/delete drops it. Memory only: the
    ;; client owns the file (zent.presets/save!), so this also works for a
    ;; preset saved while no daemon ran.
    (and (= (:request-method req) :post) (str/starts-with? (:uri req) "/api/presets/"))
    (let [[preset-name op] (str/split (subs (:uri req) (count "/api/presets/")) #"/")]
      (register-preset! (keyword preset-name) (when-not (= op "delete") (some-> (:body req) slurp))))

    :else (not-found)))

(defonce ^:private server (atom nil))

(defn start!
  "Returns the write token - what zent.daemon publishes for non-browser clients."
  [& {:keys [port] :or {port 8765}}]
  (reset! csrf-token (generate-token))
  (install-broadcast-watch!)
  (reset! server (adapter/run-server #'app {:port port :ws-handler ws-session-handler :ws-guard ws-allowed?}))
  ;; 127.0.0.1, not localhost: the adapter binds IPv4 only, and a page opened
  ;; via localhost (::1 first) leaves Firefox's websocket stuck forever
  (println (format "[zent.ui] listening on http://127.0.0.1:%d" port))
  @csrf-token)

(defn stop! []
  (when-let [s @server]
    (adapter/stop-server s)
    (reset! server nil))
  (uninstall-broadcast-watch!)
  (reset! csrf-token nil))
