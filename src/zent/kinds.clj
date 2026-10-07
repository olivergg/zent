(ns zent.kinds
  "The component kinds the engine ships with (compose stacks, processes,
  Quarkus apps, one-shots, k8s workloads, :external), as defmethods of
  zent.engine's kind multimethods - the only kinds there are.

  Every method here may assume it should actually run - zent.engine's
  deploy-component! peels off anything not :mode :on before dispatch."
  (:require [clojure.string :as str]
            [zent.commands :as commands]
            [zent.engine :as engine]
            [zent.k8s :as k8s]
            [zent.logs :as logs]
            [zent.probe :as probe]
            [zent.schema :as schema]
            [zent.secrets :as secrets]
            [zent.session :as session]
            [zent.shell :as shell]
            [zent.source :refer [checkout-dir resolve-source]]))

(defn- source-dir
  "The dir build-kind! already resolved (::dir), else resolved now - once per
  deploy, since resolving a :branch fetches and resets its worktree."
  [{:keys [repo branch workspace-dir org] ::keys [dir]}]
  (or dir (resolve-source repo {:branch branch :workspace-dir workspace-dir :org org})))

(defn- source-env
  "Each :source-env var -> its source's dir (+ :path): zent.compose pinned the
  source's coordinates onto the entry, so it resolves like that source's own
  deploy would - a :branch worktree created and synced first."
  [{:keys [source-env]}]
  (into {} (map (fn [[var {:keys [path] :as ref}]] [var (cond-> (source-dir ref) path (str "/" path))]))
        source-env))

;; Writing to a file, not a terminal, tools drop their colours - asked back
;; here for what lands in a component's log (the Logs tab renders them).
;; Never for commands zent itself parses (git, ps, compose ps): codes would
;; corrupt what it reads. A component's own :env still wins.
(def ^:private color-env
  {"FORCE_COLOR" "1" "CLICOLOR_FORCE" "1"
   "QUARKUS_CONSOLE_COLOR" "true" "SPRING_OUTPUT_ANSI_ENABLED" "always"})

(defn- run-or-throw!
  "Runs `cmd` in `dir`, throwing ex-info naming the component on a non-zero
  exit. Output goes to the component's log, not zent's stdout, so a `mvn
  package` doesn't bury the deploy progress - as it's written
  (shell/run-logged!), so a long script can be followed. The ex-info's :err
  is the tail of what it wrote."
  [name cmd {:keys [dir env label]}]
  (println (format "[%s%s] %s" name (or label "") (shell/display cmd)))
  (logs/append! name (str "$ " (shell/display cmd)))
  (let [log-file (logs/log-file name)
        offset (.length (java.io.File. (str log-file)))
        {:keys [exit]} (shell/run-logged! cmd :dir dir :env (merge color-env env) :log-file log-file)]
    (when-not (zero? exit)
      (throw (ex-info (format "%s failed: `%s` exited %d" name (shell/display cmd) exit)
                      {:component name :cmd cmd :exit exit
                       :err (str/join "\n" (take-last 20 (:lines (logs/read-since name offset))))})))
    nil))

;; Local builds and scripts share the filesystem (~/.m2, node_modules, a
;; repo's git): one at a time across parallel deploys, unless the component
;; sets :allow-parallel.
(defonce ^:private local-commands-lock (Object.))

(defn- run-local!
  "run-or-throw! for a local build or script - see local-commands-lock."
  [name cmd {:keys [allow-parallel]} opts]
  (if allow-parallel
    (run-or-throw! name cmd opts)
    (locking local-commands-lock (run-or-throw! name cmd opts))))

;; ---------------------------------------------------------------------------
;; Log followers: a stack's containers print to docker/k8s, not to zent - a
;; `logs -f` per component writes them into its log, after the `up` output.
;; ---------------------------------------------------------------------------

(defn- follow!
  "Spawns `cmd` appending to `name`'s log, after a marker line: the
  handle's :follower, as a {:pid :ps} the session can persist."
  [name cmd]
  (logs/append! name "--- container logs ---")
  (let [pid (.pid ^Process (shell/spawn! cmd :log-file (logs/log-file name)))]
    {:pid pid :ps (session/pid-identity pid)}))

(defn- since-str [ms] (some-> ms java.time.Instant/ofEpochMilli str))

(defn- with-follower
  "`handle` following its containers, unless the catalog said :follow-logs false."
  [handle name cfg]
  (if-let [f (and (not (false? (:follow-logs cfg))) (engine/follow-logs! name cfg handle nil))]
    (assoc handle :follower f)
    handle))

(defmethod engine/follow-logs! :docker-compose [name _cfg {:keys [repo-dir compose-opts]} since]
  (follow! name (commands/docker-compose-logs-cmd repo-dir compose-opts nil (since-str since))))

;; its own services only: the project may hold somebody else's
(defmethod engine/follow-logs! :compose-services [name _cfg {:keys [repo-dir compose-opts services]} since]
  (follow! name (commands/docker-compose-logs-cmd repo-dir compose-opts services (since-str since))))

(defmethod engine/follow-logs! :k8s [name _cfg {:keys [context namespace workload]} since]
  (follow! name (commands/kubectl-logs-follow-cmd context namespace workload (since-str since))))

(defn- lines
  "Compose's line-per-record output, trimmed, blanks dropped."
  [out]
  (->> (str/split-lines (str out)) (map str/trim) (remove str/blank?)))

(defn- discover-services
  "Every service `compose-opts`' file declares, as keywords - asked of
  compose rather than parsed, with the same :profiles as the `up`."
  [name dir compose-opts]
  (let [cmd (commands/docker-compose-list-services-cmd dir compose-opts)
        {:keys [exit out err]} (shell/sh! cmd :dir dir)]
    (when-not (zero? exit)
      (throw (ex-info (format "%s: couldn't list compose services: `%s` exited %d"
                              name (shell/display cmd) exit)
                      {:component name :cmd cmd :exit exit :err err})))
    (mapv keyword (lines out))))

(defn- published-ports
  "The host ports a service publishes, from compose's
  `0.0.0.0:7777->22/tcp, [::]:7777->22/tcp` rendering - deduped, since each
  mapping is reported once per address family."
  [ports]
  (->> (re-seq #"(\d+)->" (str ports))
       (map second)
       (distinct)
       (vec)))

(defn- service-status
  "[{:service :redis :state \"running\" :ports [\"6379\"]} ...] for a compose
  stack, or nil if asking failed - display only, `up --wait` already
  decided whether the deploy succeeded."
  [dir compose-opts services]
  (try
    (let [{:keys [exit out]} (shell/sh! (commands/docker-compose-ps-cmd dir compose-opts services)
                                        :dir dir)]
      (when (zero? exit)
        (->> (lines out)
             (keep (fn [line]
                     (let [[svc state health ports] (str/split line #"\|")
                           published (published-ports ports)]
                       (when (seq svc)
                         (cond-> {:service (keyword svc) :state state}
                           (seq health) (assoc :health health)
                           (seq published) (assoc :ports published))))))
             (vec))))
    (catch Exception _ nil)))

(defn- fully-running?
  "Whether every one of `services` is reported \"running\". Any failure
  reads as false - a (re)deploy, never a false \"already up\"."
  [dir compose-opts services]
  (try
    (boolean (and (seq services)
                  (let [status (service-status dir compose-opts services)]
                    (and (= (count services) (count status))
                         (every? #(= "running" (:state %)) status)))))
    (catch Exception _ false)))

(defn- compose-opts
  "What zent.commands' compose fns take, from a cfg."
  [{:keys [compose-file project-name profiles]}]
  (cond-> {:file compose-file :project-name project-name}
    profiles (assoc :profiles profiles)))

(defn- known-services
  "discover-services, or nil when compose can't say - for checks and display,
  which must not fail over it."
  [name dir opts]
  (try (discover-services name dir opts) (catch Exception _ nil)))

;; the services the deploy found (the handle's), else asked of compose - a
;; liveness check every 30s needn't run `config --services` each time
(defmethod engine/already-running? :docker-compose
  [name cfg]
  (let [dir (checkout-dir cfg)
        opts (compose-opts cfg)]
    (fully-running? dir opts (or (seq (:services (::engine/handle cfg))) (known-services name dir opts)))))

(defn- build-in-source!
  "build-kind! for a kind with an optional :build-cmd run in its source dir,
  which it hands on to deploy-kind!."
  [name {:keys [repo build-cmd env] :as cfg}]
  ;; a refused secret context fails before a long build, not after
  (schema/check-secret-contexts! name cfg)
  (let [dir (when repo (source-dir cfg))]
    (when build-cmd
      (run-local! name build-cmd cfg {:dir dir :env env :label "-build"}))
    {::dir dir}))

(defmethod engine/build-kind! :docker-compose [name cfg] (build-in-source! name cfg))

(defmethod engine/deploy-kind! :docker-compose
  [name cfg]
  (let [dir (source-dir cfg)
        opts (compose-opts cfg)]
    (run-or-throw! name (commands/docker-compose-cmd dir opts) {:dir dir})
    ;; display only (the `up` isn't scoped): a failure here mustn't fail the deploy
    (let [services (known-services name dir opts)
          status (service-status dir opts nil)]
      (-> (cond-> {:kind :docker-compose :repo-dir dir :compose-opts opts}
            (seq services) (assoc :services services)
            (seq status) (assoc :service-status status))
          (with-follower name cfg)))))

(defn- services-to-run
  "Which services this component actually takes over: the declared
  allow-list, or everything the file has when there isn't one, minus
  :exclude either way."
  [name dir compose-opts {:keys [services exclude]}]
  (let [chosen (vec (remove (set exclude) (or (seq services)
                                              (discover-services name dir compose-opts))))]
    (when (empty? chosen)
      (throw (ex-info (format "%s: no compose services left to run (all excluded?)" name)
                      {:component name :services services :exclude exclude})))
    chosen))

(defmethod engine/already-running? :compose-services
  [name cfg]
  (let [dir (checkout-dir cfg)
        opts (compose-opts cfg)]
    (fully-running? dir opts (or (seq (:services (::engine/handle cfg)))
                                 (try (services-to-run name dir opts cfg)
                                      (catch Exception _ nil))))))

(defmethod engine/deploy-kind! :compose-services
  [name cfg]
  (let [dir (source-dir cfg)
        opts (compose-opts cfg)
        ;; resolved here, not in the cfg, so the handle (and the teardown
        ;; and the UI that read it) describe what actually came up
        services (services-to-run name dir opts cfg)]
    (run-or-throw! name (commands/docker-compose-services-cmd dir services opts) {:dir dir})
    ;; scoped to our own services: the project may hold somebody else's
    (let [status (service-status dir opts services)]
      (-> (cond-> {:kind :compose-services :repo-dir dir :compose-opts opts
                   :services services}
            (seq status) (assoc :service-status status))
          (with-follower name cfg)))))

(defmethod engine/deploy-kind! :external
  [name _cfg]
  (println (format "[%s] external - not started by zent" name))
  ;; its :readiness, waited on by deploy-component!, IS the deploy. Tracked
  ;; like any other, but zent.lifecycle leaves it alone
  {:kind :external})

(defmethod engine/build-kind! :process [name cfg] (build-in-source! name cfg))

(defmethod engine/deploy-kind! :process
  [name {:keys [repo cmd env port] :as cfg}]
  ;; secrets to the run only - a build has no use for them and its output
  ;; lands in errors
  (let [secret-env (secrets/secret-env! name cfg)
        dir (when repo (source-dir cfg))
        log-file (logs/log-file name)]
    (println (format "[%s] %s%s (logs -> %s)" name cmd
                     (if port (format " (port %d)" port) "") log-file))
    (shell/spawn! cmd :dir dir :env (merge color-env env (source-env cfg) secret-env) :log-file log-file)))

(defn- quarkus-full-env [{:keys [port env jdk-home]}]
  (commands/quarkus-env {:jdk-home jdk-home
                         :path (System/getenv "PATH")
                         :extra-env (assoc env "QUARKUS_HTTP_PORT" (str port))}))

(defmethod engine/build-kind! :quarkus-app
  [name cfg]
  (build-in-source! name (assoc cfg :build-cmd (commands/quarkus-build-cmd) :env (quarkus-full-env cfg))))

(defmethod engine/deploy-kind! :quarkus-app
  [name {:keys [port jdk-home] :as cfg}]
  (let [secret-env (secrets/secret-env! name cfg) ; see :process
        dir (source-dir cfg)
        log-file (logs/log-file name)
        run-cmd (commands/quarkus-run-cmd jdk-home)]
    (println (format "[%s] %s (port %d, logs -> %s)" name (shell/display run-cmd) port log-file))
    (shell/spawn! run-cmd :dir dir :env (merge color-env (quarkus-full-env cfg) (source-env cfg) secret-env)
                  :log-file log-file)))

(defmethod engine/deploy-kind! :one-shot
  [name {:keys [scripts pre-check env] :as cfg}]
  (let [dir (source-dir cfg)
        env (merge env (source-env cfg))]
    (probe/wait-ready! name pre-check)
    (doseq [s scripts]
      (run-local! name s cfg {:dir dir :env env}))
    nil))

(defn- check-k8s-cfg!
  "Refusals that must happen before any kubectl call: a context the catalog
  never allowed (schema/check-k8s-context!), and an image placeholder with no
  image to put there."
  [name {:keys [image-placeholder image] :as cfg}]
  (schema/check-k8s-context! name cfg)
  (when (and image-placeholder (not image))
    (throw (ex-info (format "%s: :image-placeholder %s set but no :image to deploy" name image-placeholder)
                    {:component name}))))

(defmethod engine/already-running? :k8s
  [_name {:keys [context namespace workload]}]
  (k8s/ready? context namespace workload))

(defmethod engine/deploy-kind! :k8s
  [name {:keys [context namespace workload pull-secret rollout-timeout] :as cfg}]
  (check-k8s-cfg! name cfg)
  (let [dir (k8s/render! name cfg (source-dir cfg))]
    (println (format "[%s] %s in %s/%s" name workload context namespace))
    (k8s/ensure-namespace! context namespace)
    (when pull-secret (k8s/ensure-pull-secret! context namespace dir pull-secret))
    (run-or-throw! name (commands/kubectl-apply-k-cmd context dir) {})
    (run-or-throw! name (commands/kubectl-rollout-status-cmd context namespace workload (or rollout-timeout 300)) {})
    ;; the last lines at rollout; the follower (with-follower) takes it from there
    (let [{:keys [out]} (shell/sh! (commands/kubectl-logs-tail-cmd context namespace workload 200))]
      (logs/append! name out))
    (with-follower {:kind :k8s :context context :namespace namespace :dir dir :workload workload} name cfg)))
