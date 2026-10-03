(ns zent.engine
  "zent's engine: the reusable orchestrator, with no knowledge of any
  particular component - only the *mechanism* (dependency-ordered local
  deploys, teardown, readiness); what to run comes from a catalog.

  A catalog is plain data:

    {:defaults   {cfg-key value}          ; merged into every component
     :components {name cfg}               ; the registry (primitives)
     :presets    {name overlay}           ; the named compositions
     :max-parallel n}                     ; deploys at once, default 3

  A kind is implemented by defmethod'ing deploy-kind! below, plus
  build-kind! (built before, so a reload keeps the old version up meanwhile),
  already-running? (adoption, liveness), follow-logs! (output printed
  elsewhere, e.g. containers) and zent.lifecycle/stop! for a kind whose
  handle has something to tear down. The engine ships the
  generic kinds (zent.kinds); a catalog is data (zent.catalog)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [zent.compose :as compose]
            [zent.lifecycle :as lifecycle]
            [zent.logs :as logs]
            [zent.probe :as probe]
            [zent.session :as session]
            [zent.source :refer [checkout-dir]]
            [zent.topo :as topo]
            [zent.watch :as watch]))

(defmulti deploy-kind!
  "Starts one component for real, dispatched on its :kind. Implementations
  may assume they were only called because the component should actually
  run - anything not :mode :on is peeled off by deploy-component! before
  dispatch, so a new mode never means touching every kind.

  Returns a lifecycle handle (see zent.lifecycle) for anything that stays
  running, nil for one-shots."
  (fn [_name cfg] (:kind cfg)))

(defmulti build-kind!
  "Builds what deploy-kind! will run, dispatched on :kind; nil, the default,
  when there's nothing to build. Apart from deploy-kind! so a reload builds
  while the running version stays up (reload!). Returns a map merged into
  the cfg deploy-kind! then gets - e.g. the source dir it resolved."
  (fn [_name cfg] (:kind cfg)))

(defmethod build-kind! :default [_ _] nil)

(defn- built
  "`cfg` once built - build-kind!'s facts merged in, marked ::built so the
  deploy-component! a reload! ends with doesn't build again."
  [name cfg]
  (if (::built cfg) cfg (merge cfg (build-kind! name cfg) {::built true})))

(defmulti follow-logs!
  "Starts following what a deployed `handle`'s containers print, into `name`'s
  log, from `since` (epoch ms) or from now: a {:pid :ps} for the handle's
  :follower, or nil - the default, for a kind whose own process already
  writes there. Run by the kind at deploy, and by adoption when a follower
  from an earlier serve died."
  (fn [_name cfg _handle _since] (:kind cfg)))

(defmethod follow-logs! :default [_ _ _ _] nil)

(defmulti already-running?
  "Whether cfg's component is genuinely up right now, independent of anything
  zent itself recorded - what verifies a session entry before adopting it,
  and what recheck-liveness! polls. nil when the kind can't tell (the
  default): never adopted, and liveness falls back to its :readiness. When
  checking a deployed or adopted component, cfg carries its handle (::handle)
  - what the deploy found out, worth reusing rather than asking again."
  (fn [_name cfg] (:kind cfg)))

(defmethod already-running? :default [_ _] nil)

(defn deploy-component!
  "Is this component part of the run at all? If so, hand it to its kind,
  then wait on its :readiness - here, not per kind, so dependents of any
  kind wait for it to answer, not just to be spawned. *How* it's provided
  (even \"by someone else\", :external) is its :kind."
  [name {:keys [mode readiness] :as cfg}]
  (if (= :on mode)
    (let [handle (deploy-kind! name (built name cfg))]
      (try (probe/wait-ready! name readiness)
           (catch Exception e
             ;; a failed deploy records no handle: a spawned process would run on
             ;; untracked, holding its port. Converging kinds are left as is.
             (when (instance? Process handle) (lifecycle/down! [handle]))
             (throw e)))
      handle)
    (do (println (format "[%s] mode=%s - nothing to do" name mode)) nil)))

(defn- pid-alive?
  "Whether a persisted {:kind :pid ...} handle's process is still the one
  recorded - same `ps` identity (zent.session), so a recycled PID isn't
  mistaken for it."
  [{:keys [pid ps]}]
  (boolean (and ps (= ps (session/pid-identity pid)))))

(defn- reuse-if-live
  "The session's :pid handle for `name`, if that process is still alive and
  not recycled - so a spawned component two runs share isn't started twice
  (two processes fighting over one port). Compose-based kinds converge on
  their own (`up -d --wait`), so only :pid handles short-circuit here."
  [name session-path]
  (when-let [handle (some #(when (= name (:component %)) %)
                          (:handles (session/read-session session-path)))]
    (when (and (= :pid (:kind handle)) (pid-alive? handle))
      handle)))

(def default-max-parallel
  "Deploys running at once unless the catalog sets :max-parallel."
  3)

(defn- slots
  "(fn [f]) calling f once one of `n` slots is free. A token queue: Jolt has
  no java.util.concurrent.Semaphore."
  [n]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)]
    (dotimes [_ n] (.put q :slot))
    (fn [f] (let [slot (.take q)] (try (f) (finally (.put q slot)))))))

(defn- deploy-into!
  "Deploys `ordered` ([name cfg] pairs in dependency order), each as soon as
  its in-scope :deps are up, up to `max-parallel` at once - calling (on-up
  name handle) as each completes. Partial results
  survive a failure, which is what makes a half-finished run tearable down.
  A failed component only skips what depends on it, transitively - the rest
  still deploys - and the first error, in `ordered` order, is rethrown once
  the batch is through."
  [on-up ordered deploy-fn session-path & [max-parallel]]
  (let [up? (into {} (map (fn [[name]] [name (promise)])) ordered)
        errors (atom {})
        with-slot (slots (or max-parallel default-max-parallel))
        deploy! (fn [name cfg]
                  (on-up name (if-let [live (reuse-if-live name session-path)]
                                ;; its log, and the mark that it holds secrets, stay as they are
                                (do (println (format "[%s] already running as pid %d - reusing" name (:pid live)))
                                    live)
                                ;; truncate here, not in the kinds: a log then covers one *run*, while
                                ;; a reload (see reload!) appends to it instead of erasing what came before.
                                (do (logs/start-run! name)
                                    (deploy-fn name cfg))))
                  true)
        workers (mapv (fn [[name cfg]]
                        (future
                          (deliver (up? name)
                                   ;; Throwable: whatever escapes leaves the promise
                                   ;; undelivered, every dependent waiting on it forever
                                   (try
                                     (if (every? #(deref (up? %)) (filter up? (:deps cfg)))
                                       (with-slot #(deploy! name cfg))
                                       (do (println (format "[%s] skipped - a dependency failed" name))
                                           false))
                                     (catch Throwable e (swap! errors assoc name e) false)))))
                      ordered)]
    (run! deref workers)
    (when-let [e (some (comp @errors first) ordered)] (throw e))))

;; ---------------------------------------------------------------------------
;; resident mode - watch declared paths and re-apply. See docs/watch-design.md.
;; ---------------------------------------------------------------------------

(defn- watch-targets
  "{name {:spec ... :scan ...}} for the resolved components that declare a
  :watch, in the dir the deploy runs from - a branch-pinned worktree rather
  than the main clone. checkout-dir, not resolve-source: this runs on serve!'s
  loop, which must not fetch and reset a worktree the deploy worker may be
  building from."
  [resolved]
  (into {}
        (keep (fn [[name {:keys [watch] :as cfg}]]
                (when watch
                  (let [dir (checkout-dir cfg)]
                    (if (.isDirectory (java.io.File. ^String dir))
                      [name {:spec watch :dir dir :scan (watch/scan dir watch)}]
                      ;; one bad entry (repo not cloned) loses only its own watch
                      (do (println (format "[%s] not watched: %s doesn't exist" name dir))
                          nil))))))
        resolved))

(defn- process-handle? [h] (or (instance? Process h) (= :pid (:kind h))))

(defn- reload!
  "Re-applies one component after a file change or a manual reload.

  Compose stacks, one-shots and k8s workloads converge in place, so they're
  NOT stopped first - a failed reload leaves what was running untouched.

  A spawned process (`current`) can't converge: a second instance can't bind
  the port while the first runs on untracked. So it's built first (build-kind!)
  while the old one still runs, then stopped, then run - a failed build
  leaves the old version up; only a failed start leaves it down."
  [name cfg deploy-fn current]
  (logs/append! name (format "\n--- reload %s ---" (java.util.Date.)))
  (try
    (let [cfg (if (= :on (:mode cfg)) (built name cfg) cfg)]
      (when (process-handle? current)
        (lifecycle/down! [current]))
      {:ok true :handle (deploy-fn name cfg)})
    (catch Exception e
      (println (format "[%s] reload failed: %s"
                       name (ex-message e)))
      {:ok false})))

;; serve!'s state, one atom:
;;   :active   {name cfg}     what each component runs as
;;   :handles  {name handle}  what it's running (nil: a finished one-shot)
;;   :parked   {name cfg}     stopped, kept so a :reload can bring it back
;;   :liveness {name [handle ok?]} the last verdict, per handle
;;   :busy     #{name}        a job is (re)deploying it; ::all during an apply
(defn- new-state []
  (atom {:active {} :handles {} :parked {} :liveness {} :busy #{}}))

(defn- put-handle!
  "Records `name`'s new handle. The one it replaces loses its log follower: a
  stack that converged in place runs on, but only the new handle follows it -
  two would print every line twice."
  [st name handle]
  (let [old (get-in (first (swap-vals! st assoc-in [:handles name] handle)) [:handles name])]
    (when (and (map? old) (:follower old) (not= (:follower old) (:follower handle)))
      (lifecycle/stop-follower! (assoc old :component name)))))

(defn- busy? [st name]
  (let [b (:busy @st)] (boolean (or (b ::all) (b name)))))

(defn- permanent?
  "A :permanent component is never torn down implicitly (preset switch,
  stop-everything, zent exiting) - only by its own explicit stop. Everything
  else zent tracks, adopted or not, is zent's to stop."
  [st name]
  (boolean (get-in @st [:active name :permanent])))

(defn- due?
  "Whether a component that has pending changes should be applied now: quiet
  for quiet-ms, or dirty for max-ms regardless (so a stream of saves can't
  postpone a reload for ever)."
  [{:keys [dirty-since last-change]} now quiet-ms max-ms]
  (and dirty-since
       (or (>= (- now last-change) quiet-ms)
           (>= (- now dirty-since) max-ms))))

(defn- current-cfg
  "`name`'s cfg as `catalog` resolves it now - from the preset it came from
  (::preset), or the registry for an adopted one - so a reload after a
  catalog change (reload-code) runs the change, not what the apply resolved
  back then. The recorded `cfg` when that's gone or no longer resolves."
  [catalog name cfg]
  (try
    (let [fresh (cond
                  (::preset cfg) (get (compose/resolve-preset catalog (::preset cfg)) name)
                  (::adopted cfg) (when-let [base (get-in catalog [:components name])]
                                    (merge (:defaults catalog) base {:mode :on})))]
      (if fresh
        (merge fresh (select-keys cfg [::preset ::adopted ::idle]))
        cfg))
    (catch Exception e
      (println (format "[%s] keeping its config - the catalog doesn't resolve it: %s" name (ex-message e)))
      cfg)))

(defn- changes
  "The top-level keys `cfg` sets differently from `cur`, {key [was now]} - why a
  redeploy happens, said to whoever sees it."
  [cur cfg]
  (let [cur (dissoc cur ::idle ::preset)]
    (into (sorted-map)
          (keep (fn [k] (let [was (get cur k) now (get cfg k)] (when (not= was now) [k [was now]]))))
          (set/union (set (keys cur)) (set (keys cfg))))))

(defn plan
  "What switching to `preset-name` would do from serve!'s `state` ({:active
  :handles}) - pure: preview shows it, switch-preset! runs it. nil
  `preset-name` is stop-everything.
    :stop      unneeded, neither :permanent nor kept warm
    :keep-warm unneeded :keep-warm ones, left running idle
    :redeploy  {name changes}: running, configured differently, not adopted
               (what it runs is unknown)
    :restart   those of :redeploy that can't converge in place - a process
               (its port), a :kind change (the old resource would linger)
    :deploy    redeploys and first deploys, [[name cfg] ...] in :deps order
    :unchanged running and needed as they are - an :off entry never stops one
    :merged    the preset's cfgs, bound for :active"
  [catalog preset-name {:keys [active handles]}]
  (let [resolved (if preset-name (compose/resolve-preset catalog preset-name) {})
        running? #(contains? handles %)
        unneeded (remove #(or (contains? resolved %) (get-in active [% :permanent])) (keys active))
        warm? #(and preset-name (get-in active [% :keep-warm]) (running? %))
        merged (into {} (remove (fn [[n cfg]] (and (running? n) (not= :on (:mode cfg))))) resolved)
        redeploy (into (sorted-map)
                       (keep (fn [[n cfg]]
                               (let [cur (get active n)]
                                 (when-let [ch (and (running? n) (not (::adopted cur)) (not-empty (changes cur cfg)))]
                                   [n ch]))))
                       merged)]
    {:preset preset-name
     :stop (vec (sort (remove warm? unneeded)))
     :keep-warm (vec (sort (filter warm? unneeded)))
     :redeploy redeploy
     :restart (into (sorted-set) (filter #(or (process-handle? (get handles %))
                                              (not= (:kind (get merged %)) (:kind (get active %)))))
                    (keys redeploy))
     :deploy (topo/topo-sort (into {} (filter (fn [[n _]] (or (redeploy n) (not (running? n))))) merged))
     :unchanged (vec (sort (filter #(and (running? %) (not (redeploy %))) (keys resolved))))
     ;; which preset each came from: what a reload re-resolves (current-cfg)
     :merged (update-vals merged #(assoc % ::preset preset-name))}))

(defn- shown
  "A plan's changes as one line: `:branch \"main\" -> \"feature/x\"`, long
  values cut."
  [changes]
  (let [short #(let [s (pr-str %)] (if (> (count s) 40) (str (subs s 0 37) "...") s))]
    (str/join ", " (map (fn [[k [was now]]] (str k " " (short was) " -> " (short now))) changes))))

(defn plan-summary
  "`p` as names only, plus a one-line why per redeploy - what preview shows.
  :start is :deploy less the redeploys."
  [{:keys [redeploy deploy] :as p}]
  (-> (select-keys p [:stop :keep-warm :unchanged])
      (assoc :start (into [] (comp (map first) (remove redeploy)) deploy)
             :redeploy (update-vals redeploy shown))))

(defn- deploy-plan!
  "Runs `p`'s additive half: :merged into :active, the :restart ones stopped
  first, then :deploy in dependency order. A failed redeploy gets its previous
  cfg back, so the same preset applied again retries it. `on-resolved` gets
  [preset-name active], the whole merged set."
  [{:keys [preset merged redeploy restart deploy]} st deploy-fn session-path on-resolved max-parallel]
  (let [{before :active handles :handles} @st]
    (swap! st update :active merge merged)
    (when on-resolved (on-resolved preset (:active @st)))
    (doseq [[name ch] redeploy]
      (println (format "[%s] configured differently by %s (%s) - %s" name preset (shown ch)
                       (if (restart name) "stopping it to redeploy" "redeploying in place")))
      (when (restart name) (lifecycle/down! [(get handles name)])))
    (deploy-into! (partial put-handle! st) deploy
                  (fn [name cfg]
                    (try (deploy-fn name cfg)
                         (catch Exception e
                           (when (redeploy name) (swap! st assoc-in [:active name] (get before name)))
                           (throw e))))
                  session-path max-parallel)))

(defn- stop-and-forget!
  "Tears down `name`'s handle if any, then drops it from :active/:handles
  regardless. A failed teardown is logged, not thrown: the rest of the batch
  or loop iteration shouldn't abort over one handle refusing to die.
  `on-stopping` fires first, when there is something to tear down - a stop
  can take seconds."
  [st name on-stopping]
  (when-let [h (get-in @st [:handles name])]
    (on-stopping name)
    (try (lifecycle/down! [h])
         (catch Exception e (println (format "[%s] stop failed: %s" name (ex-message e))))))
  (swap! st #(-> % (update :active dissoc name) (update :handles dissoc name))))

(defn- switch-preset!
  "Runs plan: what the new preset doesn't need is torn down (permanent? ones
  excepted) - switching replaces, not stacks. A :keep-warm one is left
  running instead, marked ::idle until a preset needs it again. nil
  `preset-name` is stop-everything, warm ones included.
  `stop!` (fn [name]) tears one down - serve!'s, the same a :stop uses."
  [catalog preset-name st session-path deploy-fn on-resolved stop!]
  (let [{:keys [stop keep-warm] :as p} (plan catalog preset-name @st)]
    (doseq [name keep-warm]
      (println (format "[%s] not needed by %s - kept warm" name preset-name))
      (swap! st assoc-in [:active name ::idle] true))
    (doseq [name stop]
      (stop! name))
    (when (seq stop) (session/drop-components! session-path stop))
    (if preset-name
      (do (println "Resolving preset:" preset-name)
          ;; the stops only touched unneeded ones: p's deploy half still holds
          (deploy-plan! p st deploy-fn session-path on-resolved (:max-parallel catalog)))
      ;; deploy-plan! calls on-resolved itself - here it's called with what's
      ;; left (permanent components only), so the UI's :preset label clears
      (when on-resolved (on-resolved nil (:active @st))))))

(defn- verified-alive?
  "Whether a persisted session handle's resource is genuinely still there -
  the session file is a claim, not a fact. A kind that can't tell, and a
  check that throws (a repo dir gone since), read as not alive: one bad
  entry must not stop serve! from starting."
  [handle cfg]
  (try
    (if (= :pid (:kind handle))
      (pid-alive? handle)
      (boolean (already-running? (:component handle) (assoc cfg ::handle handle))))
    (catch Exception _ false)))

(defn- adopt-session!
  "Takes over every session entry still verified-alive?, without
  redeploying - so a new serve! shows and can stop what an earlier run left
  up. Stale/unknown names are skipped. An adopted name already has a
  handle, and what config it runs is unknown (::adopted), so re-applying a
  preset won't re-converge it (reload does).

  A handle whose log follower died with an earlier serve gets a new one, from
  when that session was last written - not from the start, already logged.

  Returns the adopted [name cfg handle] triples: adoption bypasses
  deploy-fn, so the caller reports them (:on-seeded), after on-resolved -
  which would otherwise reset them to :pending."
  [{:keys [components defaults]} session-path st]
  (let [{:keys [handles written-at]} (session/read-session session-path)]
    (into []
          (keep (fn [{:keys [component follower] :as handle}]
                  (when-let [base (get components component)]
                    (let [cfg (merge defaults base {:mode :on ::adopted true})]
                      (when (verified-alive? handle cfg)
                        (let [handle (if (and follower (pid-alive? follower))
                                       handle
                                       (if-let [f (try (follow-logs! component cfg handle written-at)
                                                       (catch Exception _ nil))]
                                         (assoc handle :follower f)
                                         handle))]
                          (swap! st #(-> % (assoc-in [:active component] cfg)
                                         (assoc-in [:handles component] handle)))
                          [component cfg handle]))))))
          handles)))

(defn- check-liveness
  "Whether `name`'s deployed component still checks out, or nil when
  nothing can tell (unknown kind/handle, or the check threw). nil is
  deliberately not `false`: an inconclusive check must not be reported as
  a transition. Spawned processes read their handle (.isAlive, or
  pid-alive? for a reused :pid handle); other kinds ask already-running?,
  then the one-shot probe/ready? when it can't tell (:external) - wait-ready!
  would block the loop."
  [name {:keys [readiness] :as cfg} handle]
  (try
    (cond
      (instance? Process handle) (.isAlive ^Process handle)
      (= :pid (:kind handle)) (pid-alive? handle)
      :else (let [up (already-running? name (assoc cfg ::handle handle))]
              (if (and (nil? up) readiness) (probe/ready? readiness) up)))
    (catch Exception _ nil)))

(defn- recheck-liveness!
  "Re-verifies every deployed :on component - deploy-kind! checks readiness
  only once, so one that later dies (killed, torn down outside zent,
  crashed) would otherwise show healthy forever. Fires `on-check` on
  transitions only; `some?`, not when-let, so `false` (down) still counts
  while nil (inconclusive) doesn't. A busy one is skipped: mid-reload its
  process is down on purpose."
  [st on-check]
  (doseq [[name cfg] (:active @st)
          :let [handles (:handles @st)]
          :when (and (= :on (:mode cfg)) (contains? handles name) (not (busy? st name)))]
    (let [handle (get handles name)
          ok (check-liveness name cfg handle)]
      ;; remembered per handle: after a redeploy the last verdict is stale, so
      ;; the first check of the new handle always counts as a transition
      (when (and (some? ok) (not= [handle ok] (get-in @st [:liveness name])))
        (swap! st assoc-in [:liveness name] [handle ok])
        (on-check name cfg ok)))))

(defn- start-worker!
  "The one thread deploys run on, a job at a time in queue order - so they
  never block serve!'s loop (stops, liveness, watch, the dashboard stay
  live). A job is a fn catching its own errors; ::stop ends the thread."
  [jobs]
  (future
    (loop []
      (let [job (.take ^java.util.concurrent.LinkedBlockingQueue jobs)]
        (when-not (= ::stop job)
          ;; last line of defense: a job escaping its own try must not kill the worker
          (try (job) (catch Throwable e (println "zent: job failed:" (ex-message e))))
          (recur))))))

(defn serve!
  "The daemon's engine: adopts what the session still has running, then
  grows/shrinks via :apply commands on `control` (or :initial-preset), and
  re-applies a component when its :watch paths change. Blocks until
  interrupted or told to :shutdown. Keeps the session file current
  throughout; on exit tears down all but permanent? components.

  Deploys (an :apply, a :reload, a watched change, a :reload-code) run as
  jobs on one worker thread, in order, so the loop itself never waits on a
  build: a :stop, liveness and the watch go on meanwhile. A :stop of a
  component a job is deploying waits for that job.

  Options:
    :deploy-fn      - per-component deploy, default deploy-component!. Swap it
                      to drive a run without side effects.
    :on-resolved    - (fn [preset-name resolved]) before deploying, for
                      observers (see zent.ui.bridge).
    :session-path   - where to record the session.
    :initial-preset - applied synchronously before the loop starts.
    :control        - atom of [{:action :apply|:reload|:stop ...} ...],
                      drained once per loop iteration:
                        :apply  {:preset kw-or-nil} - see switch-preset!;
                                 nil stops everything but permanent ones.
                        :reload {:name kw} - re-applies now, regardless of
                                 :watch/due?; restarts one stopped earlier.
                        :stop   {:name kw} - tears down and drops from the
                                 watched set (a later file change can't
                                 bring it back).
                        :shutdown {} - waits for the running job, tears
                                 down (as on exit) and returns.
    :on-handled     - (fn [commands]) once those commands are done - a job's
                      when it ends, so not in queue order - how a client
                      learns its request is done.
    :on-stopping    - (fn [name]) as a stop (a :stop, or a switch dropping
                      it) starts tearing a component down.
    :on-stop        - (fn [name]) called once a :stop lands.
    :on-seeded      - (fn [name cfg handle]) called once per component
                      adopt-session! finds still running at start.
    :on-check       - (fn [name cfg ok?]) on a liveness transition (see
                      recheck-liveness!).
    :liveness-ms    - how often that recheck runs (default 30s): it shells
                      out (`docker compose ps`, kubectl) per component, far
                      too costly at every poll-ms tick.
    :catalog-ref    - atom holding the catalog, read on every :apply, so a
                      preset registered while running applies. Defaults to
                      `catalog`.
    :on-ready       - (fn []) once adoption is done, before :initial-preset -
                      so nobody it announces the run to sees a half-adopted
                      stack.
    :reload-code    - (fn []) run for a queued {:action :reload-code}, as a
                      job so never mid-deploy; without it the command is ignored.
    :planner        - atom, set to (fn [preset-name]) -> plan-summary from the
                      current state: what an :apply would do, for preview."
  [catalog & {:keys [poll-ms quiet-ms max-ms session-path deploy-fn on-resolved
                     control on-handled on-stopping on-stop on-seeded on-check initial-preset
                     catalog-ref on-ready reload-code liveness-ms planner]
             :or {poll-ms watch/default-poll-ms quiet-ms 200 max-ms 10000 liveness-ms 30000
                  session-path session/default-path
                  deploy-fn deploy-component!
                  control (atom [])
                  on-handled (constantly nil)
                  on-stopping (constantly nil)
                  on-stop (constantly nil)
                  on-seeded (constantly nil)
                  on-check (constantly nil)}}]
  (let [catalog-ref (or catalog-ref (atom catalog))
        st (new-state)
        jobs (java.util.concurrent.LinkedBlockingQueue.)
        checked-at (atom 0)
        torn-down (atom false)
        ;; set by :shutdown: jobs still queued are reported, not run
        stopping (atom false)
        ;; :stops waiting for the job deploying their component
        deferred (atom [])
        stop! (fn [name]
                (when-let [cfg (get-in @st [:active name])] (swap! st assoc-in [:parked name] cfg))
                (stop-and-forget! st name on-stopping)
                (on-stop name))
        apply! (fn [preset-name]
                 (switch-preset! @catalog-ref preset-name st session-path deploy-fn on-resolved stop!))
        _ (some-> planner (reset! #(plan-summary (plan @catalog-ref % @st))))
        persist! (fn [] (session/save! session-path (or initial-preset :serve) (seq (:handles @st))))
        ;; a reload runs the catalog as it is now (current-cfg), not as applied
        reload-one! (fn [name]
                      (if-let [cfg (and (not (get-in @st [:active name])) (get-in @st [:parked name]))]
                        (let [cfg (current-cfg @catalog-ref name cfg)]
                          (println (format "[%s] restarting" name))
                          (swap! st #(-> % (update :parked dissoc name) (assoc-in [:active name] cfg)))
                          (deploy-into! (partial put-handle! st) [[name cfg]] deploy-fn session-path))
                        (when-let [cfg (some->> (get-in @st [:active name]) (current-cfg @catalog-ref name))]
                          (swap! st assoc-in [:active name] cfg)
                          (let [{:keys [ok handle]} (reload! name cfg deploy-fn (get-in @st [:handles name]))]
                            (when ok (put-handle! st name handle))))))
        ;; queues `run` as a job over `names` (::all for an apply), reporting
        ;; `commands` handled - with :error if it threw - once it's done
        submit! (fn [names commands run]
                  (swap! st update :busy into names)
                  (.put jobs (fn []
                               ;; Throwable: an Error skipping on-handled would leave
                               ;; whoever queued the command waiting for ever
                               (let [error (try (if @stopping "zent is shutting down" (do (run) nil))
                                                (catch Throwable e
                                                  (println "zent:" (ex-message e))
                                                  (or (ex-message e) (str e)))
                                                (finally (swap! st update :busy #(apply disj % names))))]
                                 (try (persist!) (catch Exception e (println "zent: session not saved:" (ex-message e))))
                                 (when (seq commands)
                                   (on-handled (mapv #(cond-> % error (assoc :error error)) commands)))))))
        teardown! (fn []
                    (when (compare-and-set! torn-down false true)
                      (let [mine (remove (comp (partial permanent? st) key) (:handles @st))
                            ;; :external started nothing - not worth counting
                            live (remove #(= :external (:kind %)) (keep val mine))]
                        (println "\nzent serve: stopping" (count live) "component(s)")
                        (lifecycle/down! live)
                        (session/drop-components! session-path (map key mine)))))
        worker (start-worker! jobs)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. teardown!))
    (let [adopted (adopt-session! catalog session-path st)]
      (when (and on-resolved (seq adopted)) (on-resolved :inherited (:active @st)))
      ;; after on-resolved, not before - see adopt-session!'s docstring
      (doseq [[name cfg handle] adopted] (on-seeded name cfg handle)))
    (when on-ready (on-ready))
    (when initial-preset
      (try (apply! initial-preset)
           (catch Exception e (println (format "apply %s failed: %s" initial-preset (ex-message e))))))
    (persist!)

    (let [announce! (fn [targets]
                      (if (empty? targets)
                        (println "zent serve: no component declares :watch - nothing to watch")
                        (println (format "zent serve: watching %s (ctrl-c to stop everything)"
                                         (str/join ", " (map name (keys targets)))))))]
      (loop [[targets known-keys] [(doto (watch-targets (:active @st)) announce!) (set (keys (:active @st)))]]
        (Thread/sleep (long poll-ms))
        ;; one iteration = one try: whatever throws (a callback, the session
        ;; file, a scan) is logged and the loop carries on - a dead loop would
        ;; freeze the daemon while its HTTP server stays up
        (when-let [next-state
                   (try
                     (when (>= (- (System/currentTimeMillis) @checked-at) liveness-ms)
                       (reset! checked-at (System/currentTimeMillis))
                       (recheck-liveness! st on-check))
                     (let [commands (first (reset-vals! control []))]
                       (if (some #(= :shutdown (:action %)) commands)
                         ;; after the running job, but not the queued ones (they're
                         ;; reported instead): tearing down under a deploy would leak
                         ;; what it spawns. Deferred stops are moot - teardown! stops all.
                         (do (reset! stopping true)
                             (.put jobs ::stop) @worker
                             (teardown!)
                             (on-handled (into (vec commands) @deferred))
                             nil)
                         (let [by-action (group-by :action commands)
                               ;; stops of what a job is deploying wait for it
                               [later now-stops] ((juxt filter remove) #(busy? st (:name %))
                                                  (concat (first (reset-vals! deferred [])) (:stop by-action)))
                               _ (reset! deferred (vec later))
                               stops (set (map :name now-stops))]
                           (when (and reload-code (seq (:reload-code by-action)))
                             (submit! #{} (:reload-code by-action) reload-code))
                           (doseq [{:keys [preset] :as c} (:apply by-action)]
                             (submit! #{::all} [c] #(apply! preset)))
                           (doseq [{:keys [name] :as c} (:reload by-action)]
                             (submit! #{name} [c] #(reload-one! name)))
                           (let [cur-keys (set (keys (:active @st)))
                                 ;; a fresh scan baseline only for names a job just added
                                 new-targets (watch-targets (select-keys (:active @st) (set/difference cur-keys known-keys)))
                                 _ (when (seq new-targets) (announce! (merge targets new-targets)))
                                 targets (merge new-targets targets)
                                 ;; stopped names leave `targets`: a file change can't bring them back
                                 targets (reduce (fn [acc name]
                                                   ;; one throwing callback mustn't fail the iteration:
                                                   ;; its commands would never be reported handled
                                                   (try (stop! name)
                                                        (catch Exception e (println (format "[%s] stop failed: %s" name (ex-message e)))))
                                                   (dissoc acc name))
                                                 targets stops)
                                 ;; nor names a preset switch tore down
                                 targets (select-keys targets (keys (:active @st)))
                                 ;; drop-components!, not persist!: save! merges, so it would
                                 ;; carry a stopped name's stale entry forward
                                 _ (when (seq stops) (session/drop-components! session-path stops))
                                 now (System/currentTimeMillis)
                                 rescanned (reduce-kv (fn [acc name {:keys [dir spec scan] :as t}]
                                                        (let [fresh (watch/scan dir spec)]
                                                          (assoc acc name
                                                                 (if (= fresh scan)
                                                                   t
                                                                   (let [{:keys [changed removed]} (watch/changes scan fresh)]
                                                                     (println (format "[%s] %d change(s)%s"
                                                                                      name (+ (count changed) (count removed))
                                                                                      (if-let [f (first changed)]
                                                                                        (str ", e.g. " f) "")))
                                                                     (assoc t :scan fresh
                                                                            :dirty-since (or (:dirty-since t) now)
                                                                            :last-change now))))))
                                                      {} targets)
                                 ;; a due change is queued unless a job already has that
                                 ;; component - it stays dirty and is queued once it's free
                                 applied (reduce-kv (fn [acc name t]
                                                      (if (and (due? t now quiet-ms max-ms) (not (busy? st name)))
                                                        (do (submit! #{name} [] #(reload-one! name))
                                                            (assoc acc name (dissoc t :dirty-since :last-change)))
                                                        (assoc acc name t)))
                                                    {} rescanned)]
                             ;; done here and now: stops, and anything else not a job's
                             (when-let [inline (seq (remove (comp (cond-> #{:apply :reload} reload-code (conj :reload-code))
                                                                  :action)
                                                            (concat now-stops (remove #(= :stop (:action %)) commands))))]
                               (on-handled (vec inline)))
                             ;; after the stops: a stopped name re-added later needs a fresh watch target
                             [applied (set (keys (:active @st)))]))))
                     (catch Exception e
                       (println "zent serve: loop iteration failed, carrying on:" (ex-message e))
                       [targets known-keys]))]
          (recur next-state))))))

(defn down!
  "Tears down `handles` - or, with none, everything the session file lists
  (`zent down`, with no daemon running) - and drops what it stopped from the
  session file: what failed to stop stays listed, so a retry can find it."
  ([] (down! nil))
  ([handles & {:keys [session-path] :or {session-path session/default-path}}]
   (let [targets (or (seq handles) (:handles (session/read-session session-path)))
         {:keys [errors] :as result} (lifecycle/down! targets)]
     (if (seq errors)
       (session/drop-components! session-path (keep :component (:stopped result)))
       (session/clear! session-path))
     result)))
