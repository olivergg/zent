(ns zent.ui.bridge-test
  "The UI observer path, driven by an injected deploy-fn - no docker, no
  processes, a synthetic catalog."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [zent.compose :as compose]
            [zent.lifecycle :as lifecycle]
            [zent.ui.bridge :as bridge]
            [zent.ui.state :as state]))

(def catalog
  {:components {:db {:kind :docker-compose :repo "db"}
                :api {:kind :quarkus-app :repo "api" :port 8080 :deps [:db]}
                :seed {:kind :one-shot :repo "db" :scripts ["./seed.sh"] :deps [:api]}
                :migrate {:kind :one-shot :repo "db" :scripts ["./migrate.sh"] :deps [:api]}}
   :presets {:all {:db {:mode :on}
                   :api {:mode :on :kind :external
                         :readiness {:type :http-poll :url "http://localhost:8080/health"}}
                   :seed {:mode :off}
                   :migrate {:mode :on}}
             :failing {:db {:mode :on} :api {:mode :on :deps []}}}})

;; serve! records a session; keep it out of the real ~/.cache/zent/session.edn.
(def ^:private session-path
  (str (System/getProperty "java.io.tmpdir") "/zent-bridge-test-session.edn"))

(use-fixtures :each (fn [t] (reset! state/view-state state/empty-state) (t)))

(defn- up!
  "bridge/serve! applying `preset`, then shut down: a :shutdown queued before
  the loop starts is drained right after the initial apply."
  [preset deploy-fn]
  (with-redefs [lifecycle/down! (fn [hs] {:stopped hs :errors []})]
    ;; no liveness recheck: these fakes would read as down
    (bridge/serve! catalog :initial-preset preset :deploy-fn deploy-fn :poll-ms 5
                   :liveness-ms Long/MAX_VALUE
                   :session-path session-path :control (atom [{:action :shutdown}])
                   :on-handled (constantly nil))))

(defn- statuses [] (update-vals (:components @state/view-state) :status))

(deftest up-reports-graph-and-resting-statuses-test
  (let [handle (Object.)
        deployed (atom [])
        fake (fn [name cfg]
               (swap! deployed conj name)
               (case (:kind cfg) :external {:kind :external} (when-not (#{:seed :migrate} name) handle)))]
    (up! :all fake)

    (testing "every component is reported, in dependency order"
      (is (= [:db :api] (take 2 @deployed)))
      (is (= #{:seed :migrate} (set (drop 2 @deployed)))))

    (testing "resting status distinguishes local/external/off/done, so an off
              component and a finished one-shot don't both read as up"
      (is (= {:db :up :api :external :seed :off :migrate :done} (statuses))))

    (testing "the graph is the dep edges, for the frontend to draw"
      (is (= #{[:api :db] [:seed :api] [:migrate :api]} (set (:graph @state/view-state)))))

    (testing "a component with no live handle carries no handle summary"
      (is (nil? (get-in @state/view-state [:components :seed :handle-summary])))
      (is (some? (get-in @state/view-state [:components :db :handle-summary]))))))

(deftest up-records-failure-test
  (let [fake (fn [name _cfg]
               (when (= name :api) (throw (ex-info "mvn exited 1" {})))
               (Object.))]
    (up! :failing fake)

    (testing "the failed component is marked and its message kept for the UI,
              while what already came up stays up"
      (is (= {:db :up :api :failed} (statuses)))
      (is (= "mvn exited 1" (get-in @state/view-state [:components :api :error]))))))

(deftest observe-liveness-check-test
  (let [external-cfg {:mode :on :kind :external}
        compose-cfg {:mode :on :kind :docker-compose}]
    (swap! state/view-state assoc-in [:components :api] {:status :external})
    (testing "a deployed :external component going unreachable reads as :failed,
              same as any other broken dependency - no dedicated status to teach"
      (state/observe-liveness-check! :api external-cfg false)
      (is (= :failed (get-in @state/view-state [:components :api :status])))
      (is (some? (get-in @state/view-state [:components :api :error]))))

    (testing "coming back restores the kind's own resting status (:external here)
              and clears the error along with it"
      (state/observe-liveness-check! :api external-cfg true)
      (is (= :external (get-in @state/view-state [:components :api :status])))
      (is (nil? (get-in @state/view-state [:components :api :error]))))

    (testing "a compose-backed component recovers to :up, not :external -
              resting-status is read from its own cfg, not hardcoded"
      (swap! state/view-state assoc-in [:components :db] {:status :failed})
      (state/observe-liveness-check! :db compose-cfg true)
      (is (= :up (get-in @state/view-state [:components :db :status]))))))

(deftest reload-code-refreshes-the-catalog-test
  (testing "given :code-prefixes and :catalog-fn, when :reload-code is handled,
            then the reload is reported and the catalog re-read"
    (let [control (atom [{:action :reload-code :id 1}])
          fresh {:components {:new {:kind :one-shot}} :presets {}}]
      (bridge/serve! {:components {} :presets {}} :poll-ms 5 :control control
                     :session-path session-path
                     :code-prefixes ["zent.no-such-prefix."]
                     :catalog-fn (fn [] (swap! control conj {:action :shutdown :id 2}) fresh))
      (is (= fresh @state/catalog))
      (is (= {:reloaded 0 :failed nil} (:code-reload @state/view-state))))))

(deftest idle-status-test
  (testing "given a running component the engine keeps warm, then it reads :idle,
            and its resting status again once a preset needs it"
    (swap! state/view-state state/set-preset :a {:db {:kind :docker-compose :mode :on}})
    (state/observe-deploy-ok! :db {:kind :docker-compose :mode :on} nil)
    (swap! state/view-state state/set-preset :b {:db {:kind :docker-compose :mode :on :zent.engine/idle true}})
    (is (= :idle (get-in @state/view-state [:components :db :status])))
    (swap! state/view-state state/set-preset :a {:db {:kind :docker-compose :mode :on}})
    (is (= :up (get-in @state/view-state [:components :db :status])))))

(deftest mark-status-and-command-errors-test
  (testing "a status for a name the view doesn't show invents nothing"
    (is (= state/empty-state (state/mark-status state/empty-state :typo :down))))
  (testing "a handled command's :error is kept under its id"
    (state/observe-handled! [{:id 4} {:id 5 :error "unknown preset"}])
    (is (= 5 (:handled @state/view-state)))
    (is (= {5 "unknown preset"} (:command-errors @state/view-state))))
  (testing "given 1000 commands later, then that error is dropped, like its id
            from :done - neither grows for the daemon's whole life"
    (state/observe-handled! [{:id 1005 :error "later"}])
    (is (= {1005 "later"} (:command-errors @state/view-state)))
    (is (not (contains? (:done @state/view-state) 5)))))

(deftest refresh-sources-test
  (let [branch (atom "main")]
    (with-redefs [zent.source/describe (fn [_] {:dir "/w/db" :branch @branch})]
      (state/observe-preset-resolved! :all (compose/resolve-preset catalog :all))
      (testing "given a checkout switched on disk, when the sources are refreshed, then
                the card shows the new branch - no re-apply"
        (reset! branch "feature/x")
        (state/refresh-sources!)
        (is (= "feature/x" (get-in @state/view-state [:components :db :source :branch]))))
      (testing "given nothing changed, then a refresh leaves the state as it is - no push"
        (let [before @state/view-state]
          (state/refresh-sources!)
          (is (identical? before @state/view-state)))))))
