(ns zent.ui.server-test
  "Exercises zent.ui.server/app directly (no real server, no socket) - it's a
  plain ring handler, so a request map in and a response map out is enough."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [zent.logs :as logs]
            [zent.secrets :as secrets]
            [ring-chez.websocket :as ws]
            [zent.ui.server :as server]
            [zent.ui.state :as state]))

(def ^:private snapshot-message #'server/snapshot-message)

(def catalog
  {:defaults {:org "acme"}
   :components {:db {:kind :docker-compose :repo "db"}
                :ui {:kind :process :repo "ui" :cmd "npm start" :deps [:db]}}
   :presets {:full {:db {:mode :on} :ui {:mode :on}}}})

(use-fixtures :each (fn [t]
                      (reset! state/catalog nil)
                      (reset! state/view-state state/empty-state)
                      (reset! server/csrf-token "test-token")
                      (t)))

(defn- get-json [uri]
  (let [{:keys [status body]} (server/app {:request-method :get :uri uri})]
    [status (json/read-str body :key-fn keyword)]))

(defn- post-json [uri & {:keys [token origin]}]
  (let [{:keys [status body]}
        (server/app (cond-> {:request-method :post :uri uri
                             :headers (cond-> {} token (assoc "x-zent-token" token)
                                              origin (assoc "origin" origin))}))]
    [status (json/read-str body :key-fn keyword)]))

(defn- get-events [query]
  (let [{:keys [body]} (server/app {:request-method :get :uri "/api/events" :query-string query})]
    (json/read-str body :key-fn keyword)))

(deftest events-long-poll-test
  (reset! state/view-state (assoc-in state/empty-state [:components :db] {:status :pending}))
  (testing "given nothing new, then it holds the request max-wait-ms, then returns no event"
    (with-redefs [server/max-wait-ms 100]
      (let [t0 (System/currentTimeMillis)
            {:keys [events]} (get-events "since=0&id=1")]
        (is (= [] events))
        (is (>= (- (System/currentTimeMillis) t0) 100)))))

  (testing "given a transition while it waits, then it wakes with it, numbered"
    (let [reply (future (get-events "since=0&id=99"))]
      (Thread/sleep 50)
      (state/observe-deploy-start! :db)
      (is (= [{:seq 1 :component "db" :from "pending" :to "deploying"}]
             (mapv #(dissoc % :at) (:events @reply))))))

  (testing "given the command handled, then it returns at once, with no event to repeat"
    (state/observe-handled! [{:id 3}])
    (is (= {:events [] :seq 1 :done true} (get-events "since=1&id=3"))))

  (testing "the journal stays off /api/view: the push and snapshots don't carry it"
    (let [[_ view] (get-json "/api/view")]
      (is (not (contains? view :events)))
      (is (= 1 (:event-seq view))))))

(deftest push-off-the-swapping-thread-test
  (testing "given a client whose send stalls (a sleeping tab), when view-state
            changes, then the swap! returns at once - it's the engine's thread -
            and the client still gets the latest view"
    (let [sent (atom [])]
      (with-redefs [ws/send! (fn [s msg] (Thread/sleep 500) (swap! sent conj [s msg]) true)]
        (reset! server/clients #{:stalled-tab})
        (server/install-broadcast-watch!)
        (try
          (let [t0 (System/currentTimeMillis)]
            (swap! state/view-state assoc :preset :first)
            (swap! state/view-state assoc :preset :latest)
            (is (< (- (System/currentTimeMillis) t0) 200)))
          (Thread/sleep 1300)
          (is (str/includes? (second (last @sent)) "latest"))
          (finally
            (server/uninstall-broadcast-watch!)
            (reset! server/clients #{})))))))

(deftest token-endpoint-test
  (testing "a fresh per-process token is handed out over GET, same-origin only"
    (is (= {:token "test-token"} (second (get-json "/api/token"))))))

(deftest post-without-token-is-rejected-test
  (testing "no header at all: rejected before the endpoint's own logic ever runs"
    (let [[status body] (post-json "/api/trigger/notifier")]
      (is (= 403 status))
      (is (:error body)))))

(deftest post-with-wrong-token-is-rejected-test
  (let [[status _] (post-json "/api/trigger/notifier" :token "not-it")]
    (is (= 403 status))))

(deftest post-with-correct-token-passes-the-guard-test
  (testing "right token, same-origin implied (no Origin header) - reaches the
            endpoint, which queues the request and reports success"
    (swap! state/view-state assoc-in [:components :notifier] {:status :up})
    (let [[status body] (post-json "/api/trigger/notifier" :token "test-token")]
      (is (= 200 status))
      (is (true? (:ok body))))
    (testing "an unknown component is refused before reaching the engine"
      (is (= 404 (first (post-json "/api/trigger/typo" :token "test-token")))))))

(deftest post-rebase-queues-test
  (testing "a rebase is queued like any write, not run in the request; no checkout there: refused"
    (with-redefs [state/request-git! (fn [op k _] (when (= :svc k) ({:rebase 7 :checkout 8} op)))]
      (let [[status body] (post-json "/api/rebase/svc" :token "test-token")]
        (is (= 200 status))
        (is (= 7 (:id body))))
      (is (= 8 (:id (second (post-json "/api/checkout/svc" :token "test-token")))) "a checkout too")
      (is (= 404 (first (post-json "/api/rebase/none" :token "test-token"))))))
  (testing "a push and a sync too; an undo once, by the id of the job that left it"
    (with-redefs [state/request-git! (fn [op k _] (when (= :svc k) ({:push 9 :sync 10} op)))
                  state/request-undo! (fn [id] (when (= 10 id) 11))]
      (is (= 9 (:id (second (post-json "/api/push/svc" :token "test-token")))))
      (is (= 10 (:id (second (post-json "/api/sync/svc" :token "test-token")))))
      (is (= 11 (:id (second (post-json "/api/undo/10" :token "test-token")))))
      (is (= 404 (first (post-json "/api/undo/12" :token "test-token")))))))

(deftest branches-endpoint-test
  (testing "given the applied preset's components, then a project per checkout, its components with it"
    (reset! state/catalog catalog)
    (swap! state/view-state assoc-in [:components :ui] {:status :up :deployed-sha "abc"})
    (with-redefs [state/described-cfgs (constantly {:ui {:kind :process :repo "ui"} :db {:kind :docker-compose :repo "db"}
                                                    :web {:kind :process}})
                  zent.graph/project (fn [cfg] (when (= "ui" (:repo cfg)) {:commits [] :from nil}))
                  zent.branch/worktrees (constantly [])]
      (let [[status {:keys [projects unused]}] (get-json "/api/branches")
            by-repo (into {} (map (juxt :repo identity) projects))]
        (is (= 200 status))
        (is (= #{"ui" "db"} (set (keys by-repo))) "no repo, no project")
        (is (= [{:name "ui" :kind "process" :status "up" :deployed-sha "abc"}] (:components (by-repo "ui"))))
        (is (re-find #"isn't a checkout" (:error (by-repo "db"))))
        (is (empty? unused)))))
  (testing "given a component run from the catalog's own repo, then no project for it"
    (reset! state/catalog (assoc catalog :dir "/w/cat"))
    (with-redefs [state/described-cfgs (constantly {:ui {:kind :process :repo "ui" :workspace-dir "/w"}
                                                    :seed {:kind :one-shot :repo "cat" :workspace-dir "/w"}})
                  zent.graph/project (constantly {:commits []})
                  zent.branch/worktrees (constantly [])]
      (is (= ["ui"] (map :repo (:projects (second (get-json "/api/branches"))))))))
  (testing "given a preset that doesn't resolve, then the prune is refused"
    (reset! state/catalog (assoc-in catalog [:presets :broken] {:nope {:mode :on}}))
    (with-redefs [zent.branch/prune! (fn [& _] (throw (ex-info "must not prune" {})))]
      (let [[status body] (post-json "/api/worktrees/prune" :token "test-token")]
        (is (= 409 status))
        (is (re-find #"broken doesn't resolve" (:error body)))))))

(deftest post-from-a-foreign-origin-is-rejected-even-with-the-right-token-test
  (testing "belt-and-suspenders: a correct token from a non-localhost Origin still fails -
            this only matters if a token ever leaked, the token check alone already stops
            the real attack (a page that never had the token to begin with)"
    (let [[status _] (post-json "/api/trigger/notifier"
                                :token "test-token" :origin "https://evil.example")]
      (is (= 403 status)))))

(deftest post-with-no-token-configured-is-always-rejected-test
  (testing "before start! has ever run (no token generated yet), every write is refused,
            not silently allowed through"
    (reset! server/csrf-token nil)
    (let [[status _] (post-json "/api/trigger/notifier" :token "anything")]
      (is (= 403 status)))))

(deftest catalog-endpoint-without-a-catalog-test
  (testing "before any catalog is recorded, the endpoint says so rather than 500ing"
    (let [[status body] (get-json "/api/catalog")]
      (is (= 409 status))
      (is (:error body)))))

(deftest catalog-endpoint-test
  (state/set-catalog! catalog)
  (let [[status body] (get-json "/api/catalog")]
    (is (= 200 status))
    (is (= "acme" (get-in body [:defaults :org])))
    (testing ":presets and components round-trip untouched"
      (is (= "db" (get-in body [:components :db :repo])))
      (is (= ["db"] (get-in body [:components :ui :deps]))))))

(deftest index-page-embeds-the-csrf-token-test
  (testing "the token is baked into the page at request time, not fetched separately"
    (let [{:keys [status body]} (server/app {:request-method :get :uri "/"})]
      (is (= 200 status))
      (is (str/includes? body "content=\"test-token\""))
      (is (not (str/includes? body "{{CSRF_TOKEN}}")))))

  (testing "the explanations' texts come with the page, as JSON the page reads"
    (let [{:keys [body]} (server/app {:request-method :get :uri "/"})]
      (is (not (str/includes? body "{{TIPS}}")))
      (is (str/includes? body "<script id=\"tips\" type=\"application/json\">{\""))
      (is (str/includes? body "\"tab\\/stack\":\"Stack\\n"))))

  (testing "the daemon's version is shown in the masthead, escaped"
    (with-redefs [server/version (constantly "HEAD-abc1234<")]
      (is (str/includes? (:body (server/app {:request-method :get :uri "/"}))
                         ">HEAD-abc1234&lt;</small>")))))

(deftest htmx-is-vendored-locally-test
  (testing "served from the classpath, not a CDN - the dashboard works offline"
    (let [{:keys [status headers body]} (server/app {:request-method :get :uri "/htmx.min.js"})]
      (is (= 200 status))
      (is (str/includes? (get headers "Content-Type") "javascript"))
      (is (str/includes? body "htmx")))))

(deftest page-style-and-code-test
  (testing "the page links its own css and js, served uncached so an edit shows on reload"
    (let [page (:body (server/app {:request-method :get :uri "/"}))]
      (is (str/includes? page "href=\"/index.css\""))
      (is (str/includes? page "src=\"/index.js\"")))
    (doseq [[uri type marker] [["/index.css" "text/css" "--linen"] ["/index.js" "javascript" "\"use strict\""]]]
      (let [{:keys [status headers body]} (server/app {:request-method :get :uri uri})]
        (is (= [200 "no-cache"] [status (get headers "Cache-Control")]) uri)
        (is (str/includes? (get headers "Content-Type") type) uri)
        (is (str/includes? body marker) uri)))))

(deftest preview-endpoint-test
  (state/set-catalog! catalog)

  (testing "resolves the preset without deploying anything - every component
            reads :pending, and the card carries no reload/stop buttons"
    (let [{:keys [status body]} (server/app {:request-method :get :uri "/api/preview/full"})
          {:keys [components]} (json/read-str body :key-fn keyword)]
      (is (= 200 status))
      (is (= "pending" (get-in components [:db :status])))
      (is (not (str/includes? (get-in components [:db :card]) "act reload")))))

  (testing "an unknown preset is a clean 400, not a 500"
    (let [{:keys [status body]} (server/app {:request-method :get :uri "/api/preview/ghost"})]
      (is (= 400 status))
      (is (:error (json/read-str body :key-fn keyword))))))

(deftest detail-endpoint-test
  (testing "an unknown/torn-down name reads as nothing selected, not a 404 -
            the client can't always know the selection is still valid"
    (let [{:keys [status body]} (server/app {:request-method :get :uri "/api/detail/ghost"})]
      (is (= 200 status))
      (is (str/includes? body "Select a component"))))

  (testing "a known component renders its facts"
    (swap! state/view-state assoc-in [:components :svc] {:kind :process :status :up :deps []})
    (let [{:keys [status body]} (server/app {:request-method :get :uri "/api/detail/svc"})]
      (is (= 200 status))
      (is (str/includes? body "<span class=\"kind\">process</span>")))))

(deftest snapshot-message-carries-rendered-fragments-test
  (testing "the websocket push adds a rendered :card per component plus
            top-level summary/preset fragments, alongside the raw data the
            client still needs for its own layout math"
    (state/set-catalog! catalog)
    (let [state (assoc state/empty-state
                        :preset :full
                        :components {:svc {:kind :process :status :up :deps []}})
          msg (json/read-str (snapshot-message state) :key-fn keyword)]
      (is (str/includes? (get-in msg [:data :components :svc :card]) "act reload"))
      ;; the structured field the layout math still needs is untouched
      (is (= "up" (get-in msg [:data :components :svc :status])))
      (is (str/includes? (:summary-html msg) "1</b> up"))
      (is (str/includes? (:presets-html msg) "full"))))

  (testing "given one-shots attached to a host, then they ride as buttons on its card - but one waiting for
            anything else, even one other component, is :lone, drawn on its own; mid-apply a queued card says
            what it waits for"
    (let [shot (fn [deps] {:kind :one-shot :status :pending :attached-to :db :deps deps})
          state (assoc state/empty-state :preset :full
                       :components {:db {:kind :process :status :up :deps []}
                                    :api {:kind :process :status :deploying :deps [:db]}
                                    :web {:kind :process :status :pending :deps [:db :api]}
                                    :seed (shot [:db])
                                    :reset (shot [:seed])
                                    :connect (shot [:db :api])
                                    :replay (shot [:db :api :web])})
          comps (get-in (json/read-str (snapshot-message state) :key-fn keyword) [:data :components])]
      (is (str/includes? (get-in comps [:db :card]) "data-shot=\"seed\""))
      (is (not (str/includes? (get-in comps [:db :card]) "data-shot=\"replay\"")))
      (is (= [false false true true] (map #(get-in comps [% :lone]) [:seed :reset :connect :replay]))
          "waiting only for its host or a step on it: a button; for one other component: on its own")
      (is (str/includes? (get-in comps [:web :card]) "<span class=\"note wait\">Waits for api</span>"))
      (is (not (str/includes? (get-in comps [:api :card]) "Waits for")))))

  (testing "the done ids go out - the recent ones, in order - so a spinner waits for
            its own command: they finish out of order"
    (let [msg (json/read-str (snapshot-message (assoc state/empty-state :done (set (range 1 301))
                                                      :events [{:seq 1}]))
                             :key-fn keyword)]
      (is (= (range 101 301) (get-in msg [:data :done])))
      (is (not (contains? (:data msg) :events))))))

(deftest schema-endpoint-test
  (state/set-catalog! catalog)
  (let [[status body] (get-json "/api/schema")]
    (is (= 200 status))
    (testing "every kind is digested to its fields"
      ;; JSON round-trip keywordizes map KEYS, not string values, so a field's
      ;; own :key comes back as a string
      (is (some #(= "compose-file" (:key %)) (:docker-compose body))))))

(deftest presets-endpoint-test
  (state/set-catalog! catalog)
  (let [post (fn [uri body]
               (server/app (cond-> {:request-method :post :uri uri :headers {"x-zent-token" "test-token"}}
                             body (assoc :body (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8"))))))]
    (testing "a valid spec is registered in the running catalog - applicable by name"
      (is (= 200 (:status (post "/api/presets/mine" "[:db]"))))
      (is (= {:db {:mode :on}} (get-in @state/catalog [:presets :mine]))))

    (testing "an invalid one is refused with the reason, and nothing changes"
      (let [{:keys [status body]} (post "/api/presets/bad" "[:nope]")]
        (is (= 400 status))
        (is (str/includes? body "unknown component")))
      (is (not (contains? (:presets @state/catalog) :bad))))

    (testing "delete drops it"
      (is (= 200 (:status (post "/api/presets/mine/delete" nil))))
      (is (not (contains? (:presets @state/catalog) :mine))))))

(deftest logs-endpoint-redacts-secrets-test
  (testing "given an app that echoes a credential zent read for it, /api/logs masks it"
    (with-redefs [secrets/read-k8s-secret! (constantly "hunter2-value")
                  logs/tail (constantly ["connecting with hunter2-value"])]
      (secrets/secret-env! :relay {:use-secret-env true :allow-secret-contexts ["c"]
                                   :secret-env {"P" {:context "c" :namespace "n" :secret "s" :key "k"}}})
      (let [[status body] (get-json "/api/logs/relay")]
        (is (= 200 status))
        (is (= ["connecting with ****"] (:lines body))))))

  (testing "given a log an earlier zent serve wrote with secrets this process never
            read, then it's withheld rather than served unmasked"
    (logs/mark-sensitive! :old-relay)
    (with-redefs [logs/tail (constantly ["connecting with other-secret"])]
      (let [[_ body] (get-json "/api/logs/old-relay")]
        (is (re-find #"^log withheld" (first (:lines body))))
        (is (not (some #(re-find #"other-secret" %) (:lines body)))))))

  (testing "given the component restarted with a fresh log, then it's served again"
    (logs/start-run! :old-relay)
    (with-redefs [logs/tail (constantly ["clean line"])]
      (is (= ["clean line"] (:lines (second (get-json "/api/logs/old-relay"))))))))

(deftest dns-rebinding-guard-test
  (testing "a request addressed to another host (a rebound domain) is refused, reads included"
    (is (= 403 (:status (server/app {:request-method :get :uri "/api/view"
                                     :headers {"host" "evil.example:8765"}}))))
    (is (= 200 (:status (server/app {:request-method :get :uri "/api/view"
                                     :headers {"host" "127.0.0.1:8765"}}))))))

(deftest ws-guard-test
  (let [allowed? #'server/ws-allowed?
        upgrade (fn [headers] {:uri "/ws/view" :headers headers})]
    (testing "the dashboard's own upgrade passes"
      (is (allowed? (upgrade {"host" "127.0.0.1:8765" "origin" "http://127.0.0.1:8765"}))))
    (testing "a foreign page, a missing Origin, a rebinding Host or another path are refused"
      (is (not (allowed? (upgrade {"host" "127.0.0.1:8765" "origin" "https://evil.example"}))))
      (is (not (allowed? (upgrade {"host" "127.0.0.1:8765"}))))
      (is (not (allowed? (upgrade {"host" "evil.example" "origin" "http://127.0.0.1:8765"}))))
      (is (not (allowed? {:uri "/other" :headers {"host" "127.0.0.1" "origin" "http://127.0.0.1"}}))))))

(defn- post-run
  "POST /api/checks/run with `query` as Ring passes it: apart from the uri."
  [query & {:keys [token]}]
  (let [{:keys [status body]} (server/app {:request-method :post :uri "/api/checks/run" :query-string query
                                           :headers (cond-> {} token (assoc "x-zent-token" token))})]
    [status (json/read-str body :key-fn keyword)]))

(deftest checks-endpoints-test
  (let [dir (str (System/getProperty "java.io.tmpdir") "/zent-server-checks-" (System/nanoTime))
        result (fn [at only status] (cond-> {:status status :started-at at :preset :full :check :smoke :ns 'full.smoke
                                             :counts {:tests 1 :assertions 1 :failed 0 :errors 0}
                                             :tests [{:name "t" :status status :assertions 1 :failures []}]}
                                      only (assoc :only only)))
        release (promise)
        ran (atom [])]
    (doseq [[at only status] [[1 nil :fail] [2 ["t"] :pass]]]
      (let [f (java.io.File. (str dir "/full/smoke/" at ".edn"))]
        (.mkdirs (.getParentFile f))
        (spit f (pr-str (result at only status)))))
    (reset! state/catalog (assoc catalog :checks {:full [{:name :smoke :ns 'full.smoke :root "checks"
                                                          :observes [:db] :description "smoke"}]}))
    (swap! state/view-state assoc :preset :full)
    (binding [zent.checks/*dir* dir]
      (testing "GET /api/checks: the active preset's checks, their runs and each test's latest result"
        (let [[status body] (get-json "/api/checks")
              c (first (:checks body))]
          (is (= 200 status))
          (is (= ["full" "smoke" ["db"] 2 false "check-full-smoke"]
                 [(:preset body) (:name c) (:observes c) (count (:runs c)) (:running c) (:log c)]))
          (is (= [["t" "pass" 2]] (map (juxt :name :status :at) (:tests (:current c)))))))
      (with-redefs [zent.checks/run! (fn [preset chk tests] (swap! ran conj [preset (:name chk) tests]) @release)]
        (testing "POST /api/checks/run starts it off the request thread and answers at once"
          (is (= [200 {:ok true}]
                 (post-run "check=smoke&only=t" :token "test-token")))
          (is (true? (:running (first (:checks (second (get-json "/api/checks"))))))))
        (testing "a second run of the same check is refused while one runs"
          (is (= 409 (first (post-run "check=smoke" :token "test-token")))))
        (testing "an unknown check, a bad test name, a missing token: refused"
          (is (= 404 (first (post-run "check=nope" :token "test-token"))))
          (is (= 400 (first (post-run "check=smoke&only=x)%20(boom" :token "test-token"))))
          (is (= 403 (first (post-run "check=smoke")))))
        (deliver release nil)
        (testing "once the run is over, the check isn't running any more"
          (is (some (fn [_] (Thread/sleep 20) (false? (:running (first (:checks (second (get-json "/api/checks")))))))
                    (range 100)))
          (is (= [[:full :smoke ["t"]]] @ran)))))))
