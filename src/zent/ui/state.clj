(ns zent.ui.state
  "The daemon's live view as one atom of plain EDN, updated by observer fns zent.ui.bridge wires
  into the engine - which never requires this ns. Also holds the running
  catalog and the control queue the HTTP API feeds."
  (:require [clojure.string :as str]
            [zent.source :as source]))

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
   :handled 0        ;; highest control command :id the engine has processed
   :done #{}         ;; the recent ones it has - in no set order: a deploy is a job
   :events []        ;; status transitions, oldest first - never pushed (await-events)
   :event-seq 0})    ;; :seq of the last of them

(defonce view-state (atom empty-state))

(def ^:private max-events 500)

(defn record-transitions
  "`after` with each component status that differs from `before` appended
  to :events, numbered and timestamped: the order things happened in, which
  a client comparing snapshots loses."
  [before after]
  (let [was (update-vals (:components before) :status)
        at (System/currentTimeMillis)
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
          (update :events #(vec (take-last max-events %)))))))

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
                                         :permanent (:permanent cfg)
                                         :attached-to (:attached-to cfg)
                                         :scripts (:scripts cfg)
                                         :description (:description cfg)
                                         :repo-url (repo-url cfg repo-url-template)
                                         ;; replaced by the discovered list on deploy
                                         :services (:services cfg)
                                         :status :pending}
                                        (select-keys (get comps k)
                                                     [:status :service-status :handle-summary :error]))
                                  cfg)))
                        comps resolved)))))

(defn mark-status
  "`state` with `name`'s status set - a no-op for a name it doesn't show,
  rather than inventing a component with no :kind."
  [state name status & [extra]]
  (cond-> state
    (contains? (:components state) name) (update-in [:components name] merge {:status status} extra)))

;; ---------------------------------------------------------------------------
;; observers - wired into the engine by zent.ui.bridge
;; ---------------------------------------------------------------------------

(defn observe-preset-resolved! [preset-name resolved]
  ;; the skeleton first, so a client sees the whole run before git answers
  (swap-view! set-preset preset-name resolved
         {:repo-url-template (get-in @catalog [:defaults :repo-url-template])})
  ;; :external included: which branch the IDE-run checkout is on matters too
  (doseq [[k cfg] resolved]
    (when (= :on (:mode cfg))
      (when-let [d (source/describe cfg)]
        (swap! view-state assoc-in [:components k :source] d)))))

(defn observe-deploy-start! [name]
  (swap-view! mark-status name :deploying))

(defn- resting-status
  "What a component settles into once deployed - from mode and kind, not
  the handle: a nil handle means both \"finished one-shot\" and \"off\".
  A one-shot rests at :done, like a completed k8s Job."
  [{:keys [mode kind]}]
  (cond
    (not= :on mode) :off
    (= :external kind) :external
    (= :one-shot kind) :done
    :else :up))

(defn observe-deploy-ok! [name cfg handle]
  (swap-view! mark-status name (resting-status cfg)
         (cond-> {:handle-summary (when handle (pr-str (type handle)))}
           ;; what actually came up wins over what the cfg declared
           (:services handle) (assoc :services (:services handle))
           (:service-status handle) (assoc :service-status (:service-status handle)))))

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
  like any broken dependency. :error is always set, so recovery clears it."
  [name cfg ok?]
  (swap-view! mark-status name (if ok? (resting-status cfg) :failed)
         {:error (when-not ok? "liveness check failed - no longer reachable")}))

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

(defn observe-handled!
  "Marks `commands` processed; a failed one's :error lands in :command-errors
  under its id, for the client that queued it."
  [commands]
  (swap! view-state
         (fn [s] (let [handled (max (:handled s 0) (reduce max 0 (keep :id commands)))
                       ;; the last 1000 are plenty for any client still waiting
                       recent? (fn [id] (> id (- handled 1000)))]
                   (-> s
                       (assoc :handled handled)
                       (update :done #(into #{} (filter recent?) (into (or % #{}) (keep :id commands))))
                       (update :command-errors
                               #(into {} (filter (comp recent? key))
                                      (merge % (into {} (keep (fn [c] (when (:error c) [(:id c) (:error c)]))) commands)))))))))
