(ns zent.ui.state
  "The daemon's live view as one atom of plain EDN, updated by observer fns zent.ui.bridge wires
  into the engine - which never requires this ns. Also holds the running
  catalog and the control queue the HTTP API feeds."
  (:require [clojure.string :as str]
            [zent.engine :as engine]
            [zent.source :as source]
            [zent.usage :as usage]))

;; ---------------------------------------------------------------------------
;; the state atom - the single source of truth pushed to websocket clients
;; ---------------------------------------------------------------------------

(def empty-state
  "Shape of the snapshot pushed to websocket clients (/api/view too). No
  logs: they're served from their files (zent.logs)."
  {:components {}   ;; name -> {:kind ... :status ... :deps [...] :handle-summary ...}
   :graph []         ;; [[name dep] ...] edges, straight from topo/topo-sort input
   :informational [] ;; [[name related-name] ...] - declared, not orchestrated
   :preset nil
   :started-at nil
   :members []       ;; the preset's own components - :components also keeps earlier presets'
   :handled 0        ;; highest control command :id the engine has processed
   :done #{}         ;; the recent ones it has - in no set order: a deploy is a job
   :events []        ;; status transitions, oldest first - never pushed (await-events)
   :event-seq 0})    ;; :seq of the last of them

(defonce view-state (atom empty-state))

(def ^:private max-events 500)

(defn- stamp
  "Component `c` with :since (when it entered its status) and :took (how long its last start ran, :deploying
  to whatever came next), carried over from `b`, its state before."
  [at b c]
  (cond
    (= (:status c) (:status b)) (merge c (select-keys b [:since :took]))
    (= :deploying (:status b)) (cond-> (assoc c :since at) (:since b) (assoc :took (- at (:since b))))
    :else (cond-> (assoc c :since at) (and (:took b) (not= :deploying (:status c))) (assoc :took (:took b)))))

(defn record-transitions
  "`after` with each component status that differs from `before` appended
  to :events, numbered and timestamped: the order things happened in, which
  a client comparing snapshots loses. Each component is stamped (`stamp`)."
  ([before after] (record-transitions (System/currentTimeMillis) before after))
  ([at before after]
  (let [was (update-vals (:components before) :status)
        after (update after :components #(into {} (for [[k c] %] [k (stamp at (get-in before [:components k]) c)])))
        changes (for [[k {:keys [status]}] (:components after)
                      :when (not= status (get was k))]
                  [k (get was k) status])]
    (if (empty? changes)
      after
      ;; fnil and the 0 default: a state from before the journal existed
      ;; survives a code reload (defonce)
      (-> (reduce (fn [s [k from to]]
                    (let [n (inc (:event-seq s 0))]
                      (-> s
                          (assoc :event-seq n)
                          (update :events (fnil conj []) {:seq n :at at :component k :from from :to to}))))
                  after changes)
          (update :events #(vec (take-last max-events %))))))))

(defn own-components
  "The preset's own components: :members, else - before the first apply after a code reload - the graph's ends."
  [{:keys [members graph]}]
  (set (or (seq members) (flatten graph))))

(defn shown-components
  "What the dashboard shows of :components (which keeps earlier presets'): the preset's own, plus whatever
  still runs outside it."
  [{:keys [components] :as state}]
  (let [own (own-components state)]
    (if (empty? own)
      components
      (into {} (filter (fn [[k c]] (or (own k) (not (#{:down :off} (:status c))))) components)))))

(defn- swap-view!
  "swap! on view-state, recording the status transitions `f` makes."
  [f & args]
  (swap! view-state (fn [s] (record-transitions s (apply f s args)))))

(defn- done? [s id] (contains? (:done s) id))

(defn await-events
  "Blocks until view-state has events past `since` or has done command `id`,
  or `timeout-ms` elapses: {:events (those past since) :seq :done}. A watch
  and a promise, so waiting costs nothing while nothing changes."
  [since id timeout-ms]
  (let [ready? (fn [s] (or (> (:event-seq s 0) since) (done? s id)))
        woken (promise)
        k (gensym "await-events")]
    (add-watch view-state k (fn [_ _ _ s] (when (ready? s) (deliver woken true))))
    (try
      ;; checked after add-watch: a change landing in between would be missed
      (when-not (ready? @view-state) (deref woken timeout-ms nil))
      (let [s @view-state]
        {:events (filterv #(> (:seq %) since) (:events s))
         :seq (:event-seq s 0)
         :done (done? s id)})
      (finally (remove-watch view-state k)))))

;; ---------------------------------------------------------------------------
;; the running catalog - read per HTTP request, never pushed; only presets
;; change at runtime (/api/presets), and the engine reads it on every apply
;; ---------------------------------------------------------------------------

(defonce catalog (atom nil))

;; what an apply would do from the running state - set by zent.engine/serve!
;; (its :planner option), read by GET /api/preview
(defonce planner (atom nil))

(defn set-catalog!
  [c]
  (reset! catalog c))

;; ---------------------------------------------------------------------------
;; pure update helpers
;; ---------------------------------------------------------------------------

(defn- repo-url
  "The catalog's :repo-url-template expanded with a component's :org/:repo;
  nil when any is missing - no forge URL is ever invented here."
  [{:keys [org repo]} template]
  (when (and template repo org)
    (-> template
        (str/replace "{org}" (str org))
        (str/replace "{repo}" (str repo)))))

(declare resting-status)

(defn- with-idle
  "A component the engine keeps warm (:zent.engine/idle) reads :idle, and
  back to its resting status once a preset needs it again."
  [c cfg]
  (cond
    (:zent.engine/idle cfg) (assoc c :status :idle)
    (= :idle (:status c)) (assoc c :status (resting-status cfg))
    :else c))

(defn set-preset
  "`state` showing `resolved` as preset `preset-name`. :source is filled in
  later (observe-preset-resolved!), since it shells out to git. A component
  already shown keeps its status - a shared one mustn't flash back to
  :pending on every apply naming it."
  [state preset-name resolved & [{:keys [repo-url-template]}]]
  (-> state
      (assoc :preset preset-name :started-at (System/currentTimeMillis))
      ;; :components keeps what earlier presets ran: these are this one's own
      (assoc :members (vec (keys resolved)))
      (assoc :graph (vec (for [[k cfg] resolved dep (:deps cfg)] [k dep])))
      ;; :related - declared couplings zent doesn't orchestrate, drawn apart
      ;; from :graph
      (assoc :informational (vec (for [[k cfg] resolved dep (:related cfg)] [k dep])))
      (update :components
              (fn [comps]
                (reduce (fn [acc [k cfg]]
                          (assoc acc k
                                 (with-idle
                                  (merge {:kind (:kind cfg)
                                         :mode (:mode cfg)
                                         :deps (vec (:deps cfg))
                                         :links (:links cfg)
                                         :port (:port cfg)
                                         :repo (:repo cfg)
                                         ;; the preset's :branch, against the one on disk (:git)
                                         :wanted-branch (:branch cfg)
                                         ;; where it runs from (zent.source/from), nil without a :branch
                                         :from (source/from cfg)
                                         :permanent (:permanent cfg)
                                         :attached-to (:attached-to cfg)
                                         :scripts (:scripts cfg)
                                         :description (:description cfg)
                                         :confirm (:confirm cfg)
                                         :repo-url (repo-url cfg repo-url-template)
                                         ;; replaced by the discovered list on deploy
                                         :services (:services cfg)
                                         :status :pending}
                                        (select-keys (get comps k)
                                                     [:status :service-status :handle-summary :error :git :deployed-sha]))
                                  cfg)))
                        comps resolved)))))

(defn mark-status
  "`state` with `name`'s status set - a no-op for a name it doesn't show,
  rather than inventing a component with no :kind."
  [state name status & [extra]]
  (cond-> state
    (contains? (:components state) name) (update-in [:components name] merge {:status status} extra)))

(defn- assoc-shown
  "`state` with `name`'s `k` set to `v` - a no-op for a name it doesn't show, like mark-status."
  [state name k v]
  (cond-> state (contains? (:components state) name) (assoc-in [:components name k] v)))

;; ---------------------------------------------------------------------------
;; observers - wired into the engine by zent.ui.bridge
;; ---------------------------------------------------------------------------

(defn source-env-view
  "`cfg`'s :source-env as shown: each var's dir (+ :path) and the branch
  checked out there - what the component will read, before it runs. Shells
  out to git, like source/describe."
  [cfg]
  (not-empty (update-vals (:source-env cfg)
                          (fn [{:keys [path] :as ref}] (cond-> (source/describe ref) path (update :dir str "/" path))))))

;; the :on cfgs the last preset described - what refresh-sources! re-reads
(defonce ^:private described (atom {}))

(defn- describe-sources!
  "Sets `k`'s :source and :source-env from `cfg` - only when they changed, so
  an unchanged refresh pushes nothing to the clients."
  [k cfg]
  (let [now (into {} (remove (comp nil? val)) {:source (source/describe cfg) :source-env (source-env-view cfg)})]
    (when (and (contains? (:components @view-state) k)
               (not= now (select-keys (get-in @view-state [:components k]) [:source :source-env])))
      (swap! view-state update-in [:components k] #(merge (dissoc % :source :source-env) now)))))

(defn refresh-sources!
  "Re-reads every described component's checkouts: a branch switched on disk
  shows without re-applying. One failing (a repo gone) doesn't stop the rest."
  []
  (doseq [[k cfg] @described]
    (try (describe-sources! k cfg)
         (catch Exception e (println (format "[%s] source refresh failed: %s" k (ex-message e)))))))

(defn- set-git!
  "Sets `k`'s :git (zent.source/git-status) - only when it changed: no push otherwise."
  [k cfg]
  (when (:repo cfg)
    (let [g (source/checkout-git cfg)]
      (when (not= g (get-in @view-state [:components k :git]))
        (swap! view-state assoc-shown k :git g)))))

(defn- try-set-git!
  "set-git! that logs a failure instead of throwing."
  [k cfg]
  (try (set-git! k cfg)
       (catch Exception e (println (format "[%s] git refresh failed: %s" k (ex-message e))))))

(defn refresh-git!
  "Re-reads every described component's :git. One failing doesn't stop the rest."
  []
  (doseq [[k cfg] @described] (try-set-git! k cfg)))

;; :idle, :running, or :again - asked for while running: it runs once more
(defonce ^:private git-refresh (atom :idle))
(defonce ^:private git-refreshed-at (atom 0))
(def ^:private git-refresh-ms 30000)

(defn- refresh-git-async!
  "refresh-git! on a thread of its own: five git calls per component stay off the serve loop."
  []
  (reset! git-refreshed-at (System/currentTimeMillis))
  (when (= :idle (first (swap-vals! git-refresh {:idle :running :running :again :again :again})))
    (future
      (loop []
        (try (refresh-git!)
             (catch Throwable e (println "[zent.ui] git refresh failed:" (ex-message e))))
        (when (= :again (first (swap-vals! git-refresh {:running :idle :again :running})))
          (recur))))))

(defn set-usage
  "`state` with each shown component's :usage from `usage` (zent.usage/shown), dropped where it has
  none: stopped, or nothing measurable (a one-shot, :external)."
  [state usage]
  (update state :components
          (fn [comps] (into {} (for [[k c] comps] [k (if-let [u (get usage k)] (assoc c :usage u) (dissoc c :usage))])))))

;; the last zent.usage/sample! (its CPU times are the next one's baseline), and whether one runs
(defonce ^:private usage-sample (atom nil))
(defonce ^:private usage-sampling (atom false))

(defn- refresh-usage-async!
  "A usage sample of `handles` on a thread of its own (docker stats takes ~2s), skipped while the
  last one still runs. Swaps only on a visible change: a push per sample would redraw every card."
  [handles]
  (when (compare-and-set! usage-sampling false true)
    (future
      (try
        (let [s (usage/sample! handles @usage-sample)
              shown (usage/shown (:usage s))]
          (reset! usage-sample s)
          (when (not= (set-usage @view-state shown) @view-state)
            (swap! view-state set-usage shown)))
        (catch Throwable e (println "[zent.ui] usage sample failed:" (ex-message e)))
        (finally (reset! usage-sampling false))))))

(defn refresh!
  "The serve loop's periodic refresh: checkouts every call, git status every git-refresh-ms (both off
  the loop), CPU and memory of `handles` ({name handle}, what the engine runs) every call."
  ([] (refresh! nil))
  ([handles]
   (refresh-sources!)
   (when (>= (- (System/currentTimeMillis) @git-refreshed-at) git-refresh-ms)
     (refresh-git-async!))
   (when handles (refresh-usage-async! handles))))

(defn observe-preset-resolved! [preset-name resolved]
  ;; the skeleton first, so a client sees the whole run before git answers
  (swap-view! set-preset preset-name resolved
         {:repo-url-template (get-in @catalog [:defaults :repo-url-template])})
  ;; :external included: which branch the IDE-run checkout is on matters too
  (reset! described (into {} (filter #(= :on (:mode (val %)))) resolved))
  (refresh-sources!)
  ;; git status at the loop's next refresh
  (reset! git-refreshed-at 0))

(defn observe-deployed!
  "Records the commit `name` was just deployed from (:deployed-sha) and re-reads its :git: the card
  tells when the disk moves on. Not for :external (zent runs nothing) nor an adopted component (its
  commit is unknown). Never throws: a deploy that worked stays one."
  [name cfg]
  (when (and (:repo cfg) (not= :external (:kind cfg)))
    (try
      (let [g (source/checkout-git cfg)]
        (swap! view-state #(-> % (assoc-shown name :deployed-sha (:sha g)) (assoc-shown name :git g))))
      (catch Exception e (println (format "[%s] deployed commit not read: %s" name (ex-message e)))))))

(defn observe-deploy-start! [name]
  (swap-view! mark-status name :deploying))

(defn- resting-status
  "What a component settles into once deployed - from mode and kind, not
  the handle: a nil handle means both \"finished one-shot\" and \"off\".
  A one-shot rests at :done, like a completed k8s Job - or :ready when it's
  :on-demand and an apply skipped it: shown, with its reload, unlike :off."
  [{:keys [mode kind] :as cfg}]
  (cond
    (not= :on mode) :off
    (engine/on-demand-skip? cfg) :ready
    (= :external kind) :external
    (= :one-shot kind) :done
    :else :up))

(defn observe-deploy-ok! [name cfg handle]
  (swap-view! mark-status name (resting-status cfg)
         (cond-> {:handle-summary (when handle (pr-str (type handle)))}
           ;; what actually came up wins over what the cfg declared
           (:services handle) (assoc :services (:services handle))
           (:service-status handle) (assoc :service-status (:service-status handle)))))

(defn observe-skipped!
  "An apply skipped `name`: `failed` (its deps) didn't come up, so nothing runs it - shown as such,
  not left looking queued."
  [name failed]
  (swap-view! mark-status name :skipped {:error (str "skipped: " (str/join ", " (map clojure.core/name failed))
                                                     " failed - reload it once that's fixed")}))

(defn observe-deploy-failed! [name ex]
  (swap-view! mark-status name :failed {:error (ex-message ex)}))

(defn observe-code-reload!
  "zent.reload/reload!'s result, for `zent reload-code` to report. The swap
  also re-pushes every card, rendered by the freshly loaded code."
  [result]
  (swap! view-state assoc :code-reload
         (-> result (update :reloaded count) (update :failed #(some-> % str)))))

(defn observe-stopping! [name]
  (swap-view! mark-status name :stopping))

(defn observe-manual-stop! [name]
  (swap-view! mark-status name :down))

(defn observe-liveness-check!
  "A deployed component's liveness flipped: down reads as plain :failed,
  like any broken dependency; :unready (running, its readiness failing) as
  :unready - restarting it is the cure, not looking for what killed it.
  :error is always set, so recovery clears it."
  [name cfg verdict]
  (let [[status error] (case verdict
                         true [(resting-status cfg) nil]
                         :unready [:unready (str "running, not ready: " (get-in cfg [:readiness :url]) " doesn't answer")]
                         [:failed "liveness check failed - no longer reachable"])]
    (swap-view! mark-status name status {:error error})))

;; ---------------------------------------------------------------------------
;; the control queue - fed by zent.ui.server's POSTs, drained by
;; zent.engine/serve! (its :control option)
;; ---------------------------------------------------------------------------

(defonce control (atom []))
(defonce ^:private last-id (atom 0))

(defn- enqueue!
  "Queues `command` tagged with a fresh :id, returned so a client can wait
  for it in view-state's :done."
  [command]
  (let [id (swap! last-id inc)]
    (swap! control conj (assoc command :id id))
    id))

(defn request! [action name] (enqueue! {:action action :name (keyword name)}))
(defn request-apply! [preset-name] (enqueue! {:action :apply :preset (keyword preset-name)}))
(defn request-shutdown! [] (enqueue! {:action :shutdown}))
(defn request-reload-code! [] (enqueue! {:action :reload-code}))

(defn request-rebase!
  "Queues zent.source/rebase! of `k`'s checkout as a job: git writes there, so never next to a deploy.
  What it did lands in :command-messages under the id, why not in :command-errors; its :git is re-read
  (the card shows ≠ reload: the disk moved on from what runs). `cfg`: `k` as a previewed preset resolves
  it, else the applied one's. The id, or nil when `k` has no repo there."
  [k & [cfg]]
  (let [cfg (or cfg (get @described k))]
    (when (:repo cfg)
      (enqueue! {:action :job :name k
                 :run (fn [id]
                        (let [{:keys [ok message]} (source/rebase! cfg)]
                          (try-set-git! k cfg)
                          (when-not ok (throw (ex-info message {})))
                          (swap! view-state assoc-in [:command-messages id] message)))}))))

(defn observe-handled!
  "Marks `commands` processed; a failed one's :error lands in :command-errors
  under its id, for the client that queued it. Both it and :command-messages keep the recent ones."
  [commands]
  (swap! view-state
         (fn [s] (let [handled (max (:handled s 0) (reduce max 0 (keep :id commands)))
                       ;; the last 1000 are plenty for any client still waiting
                       recent? (fn [id] (> id (- handled 1000)))]
                   (-> s
                       (assoc :handled handled)
                       (update :done #(into #{} (filter recent?) (into (or % #{}) (keep :id commands))))
                       (update :command-messages #(into {} (filter (comp recent? key)) %))
                       (update :command-errors
                               #(into {} (filter (comp recent? key))
                                      (merge % (into {} (keep (fn [c] (when (:error c) [(:id c) (:error c)]))) commands)))))))))
