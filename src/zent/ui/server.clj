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
            [zent.compose :as compose]
            [zent.logs :as logs]
            [zent.presets :as presets]
            [zent.schema :as schema]
            [zent.secrets :as secrets]
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
  [components]
  (reduce-kv (fn [acc name c] (assoc acc name (assoc c :card (render/component-card name c))))
             {} components))

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
              (dissoc :events)
              (update :done #(take-last 200 (sort %)))
              (update :components with-cards))
    :summary-html (render/run-summary (:components state))
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
;; live cards, all :pending and :preview? - so no reload/stop buttons - plus
;; its :plan from what runs now.

(defn- preview-response [preset-name]
  (try
    (let [catalog @state/catalog
          resolved (compose/resolve-preset catalog preset-name)
          template (get-in catalog [:defaults :repo-url-template])
          preview (state/set-preset state/empty-state preset-name resolved
                                    {:repo-url-template template})]
      (json-response
       {:preset preset-name
        ;; :source-env too: what a one-shot will read is the point of looking first
        :components (with-cards (into {} (map (fn [[k c]]
                                                [k (cond-> (assoc c :preview? true)
                                                     (:source-env (resolved k))
                                                     (assoc :source-env (state/source-env-view (resolved k))))]))
                                      (:components preview)))
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

(defn- resource-page [path]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (slurp (io/resource path))})

(defn app [req]
  (cond
    (not (local-host? (get-in req [:headers "host"])))
    {:status 403 :body "zent only answers requests addressed to localhost/127.0.0.1"}

    ;; the token is baked into the page (a <meta> htmx reads back)
    (and (= (:request-method req) :get) (contains? #{"/" "/index.html"} (:uri req)))
    (update (resource-page "zent/ui/index.html") :body
            str/replace "{{CSRF_TOKEN}}" (or @csrf-token ""))

    (and (= (:request-method req) :get) (= (:uri req) "/tokens.css"))
    {:status 200 :headers {"Content-Type" "text/css; charset=utf-8"}
     :body (slurp (io/resource "zent/ui/tokens.css"))}

    ;; vendored, not CDN-loaded: the dashboard works offline. ansi_up (MIT)
    ;; renders the logs' terminal colours
    (and (= (:request-method req) :get) (#{"/htmx.min.js" "/ansi_up.js"} (:uri req)))
    {:status 200 :headers {"Content-Type" "text/javascript; charset=utf-8"}
     :body (slurp (io/resource (str "zent/ui" (:uri req))))}

    (and (= (:request-method req) :get) (= (:uri req) "/api/token"))
    (json-response {:token @csrf-token})

    ;; every write passes here first
    (and (= (:request-method req) :post) (not (authorized-write? req)))
    (forbidden)

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
       :body (render/detail-panel name (get-in @state/view-state [:components name]))})

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
    (and (= (:request-method req) :post) (str/starts-with? (:uri req) "/api/trigger/"))
    (component-command :reload (subs (:uri req) (count "/api/trigger/")))

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

    ;; POST /api/reload-code - reloads the daemon's code (zent.reload), keeping
    ;; its state; the result lands in /api/view's :code-reload
    (and (= (:request-method req) :post) (= (:uri req) "/api/reload-code"))
    (queued (state/request-reload-code!))

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
