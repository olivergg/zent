(ns zent.lifecycle
  "Tearing components back down. Handle shapes
  (produced by zent.kinds) and how each stops:
    - a java.lang.Process - stop-process! (SIGTERM, grace, SIGKILL).
    - {:kind :docker-compose ...} - `docker compose down`.
    - {:kind :compose-services ...} - `docker compose rm -sf` of its services.
    - {:kind :k8s ...} - `kubectl delete -k` on the rendered kustomization.
    - {:kind :pid ...} - a process read back from the session file -
      stop-pid!, which refuses a recycled PID.
    - {:kind :external} - nothing to stop.
  A handle's :follower (the process copying its containers' output into its
  log) is stopped first, whatever the kind.

  The stop! methods delegate to plain functions, so tests can with-redefs
  each."
  (:require [zent.commands :as commands]
            [zent.session :as session]
            [zent.shell :as shell]))

(def ^:private grace-period-ms
  "Wait after SIGTERM before SIGKILL."
  2000)

(defn- await-death!
  "Polls until `alive?` goes false or grace-period-ms elapses, answering
  whether it died. Polling: Jolt has no .waitFor(timeout)/.onExit."
  [alive?]
  (let [deadline (+ (System/currentTimeMillis) grace-period-ms)]
    (loop []
      (cond
        (not (alive?)) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 50) (recur))))))

(defn descendants-of
  "ProcessHandles descending from `pid`, empty if it's gone. zent records one
  PID per component but trees are deeper (npm forks node, jbang forks java);
  killing a process group would cover them, but ProcessBuilder can't set
  one, so the tree is walked instead. .iterator: descendants() is a Stream
  on the JVM, a vector under Jolt."
  [pid]
  (if-let [handle (.orElse (ProcessHandle/of pid) nil)]
    (vec (iterator-seq (.iterator (.descendants handle))))
    []))

(defn- stop-handle!
  [^ProcessHandle handle]
  (.destroy handle)
  (when-not (await-death! #(.isAlive handle))
    (.destroyForcibly handle)))

(defn stop-process!
  "Stops a java.lang.Process: SIGTERM, grace period, SIGKILL; throws if it
  still won't die. Descendants are captured first and any that outlive the
  parent are stopped too: the parent gets the clean SIGTERM (npm forwards it),
  but a launcher that doesn't - or a SIGKILL - would leave its children
  holding their port."
  [^Process proc]
  (let [kids (descendants-of (.pid proc))]
    (try
      (.destroy proc)
      (when-not (await-death! #(.isAlive proc))
        (.destroyForcibly proc)
        (when-not (await-death! #(.isAlive proc))
          (throw (ex-info "process did not exit even after destroyForcibly"
                          {:pid (try (.pid proc) (catch Exception _ nil))}))))
      (finally
        (run! stop-handle! (filter #(.isAlive %) kids))))))

(defn stop-pid!
  "Stops a process recorded in the session, and its descendants. Refuses
  when its `ps` identity no longer matches, or was never recorded - a
  recycled PID is somebody else's."
  [{:keys [pid component ps]}]
  (if-let [handle (.orElse (ProcessHandle/of pid) nil)]
    (let [live (session/pid-identity pid)]
      (if (or (nil? ps) (and live (not= ps live)))
        (println (format "[%s] pid %d is now a different process - not touching it" component pid))
        (let [kids (descendants-of pid)]
          (when (seq kids)
            (println (format "[%s] pid %d has %d descendant(s) to stop too" component pid (count kids))))
          ;; each stopped explicitly, so order can't orphan a leaf
          (run! stop-handle! kids)
          (stop-handle! handle)
          nil)))
    (println (format "[%s] pid %d already gone" component pid))))

(defn stop-docker-compose!
  "`docker compose down` with the same :file/:project-name the up used, so
  it targets the same stack."
  [{:keys [repo-dir compose-opts]}]
  (shell/sh-or-throw! (commands/docker-compose-down-cmd repo-dir compose-opts)))

(defn stop-compose-services!
  "`docker compose rm -sf` of exactly the services it brought up - never
  `down`: the project may hold services another component depends on."
  [{:keys [repo-dir compose-opts services]}]
  (shell/sh-or-throw! (commands/docker-compose-services-rm-cmd repo-dir services compose-opts)))

(defn stop-k8s!
  "Deletes exactly what a {:kind :k8s ...} handle applied (its rendered
  kustomization dir, see zent.k8s). Never the namespace or the pull secret -
  every component in the namespace shares those."
  [{:keys [context dir]}]
  (shell/sh-or-throw! (commands/kubectl-delete-k-cmd context dir)))

(defmulti stop!
  "Tears down one handle, dispatched on its :kind (::process for a
  java.lang.Process)."
  (fn [handle] (if (instance? Process handle) ::process (:kind handle))))

(defmethod stop! ::process [h] (stop-process! h))
(defmethod stop! :docker-compose [h] (stop-docker-compose! h))
(defmethod stop! :compose-services [h] (stop-compose-services! h))
(defmethod stop! :k8s [h] (stop-k8s! h))
(defmethod stop! :pid [h] (stop-pid! h))
(defmethod stop! :external [_] nil) ; zent only probed it
(defmethod stop! :default [handle]
  (throw (ex-info "unknown handle - don't know how to stop it" {:handle handle})))

(defn stop-follower!
  "Stops the process a handle runs to follow its containers' output into the
  log (:follower, a {:pid :ps} - zent.engine/follow-logs!), if any. Never
  throws: a follower is only a log, it mustn't keep its component up."
  [{:keys [follower component]}]
  (when follower
    (try (stop-pid! (assoc follower :component component))
         (catch Exception e (println (format "[%s] log follower: %s" component (ex-message e)))))))

(defn down!
  "Stops every handle in `running` - its log follower first; one that fails is
  recorded and doesn't abort the rest. Returns {:stopped [...] :errors
  [{:handle h :error ex}]}."
  [running]
  (reduce (fn [{:keys [stopped errors]} handle]
            (try
              (when (map? handle) (stop-follower! handle))
              (stop! handle)
              {:stopped (conj stopped handle) :errors errors}
              (catch Exception e
                {:stopped stopped :errors (conj errors {:handle handle :error e})})))
          {:stopped [] :errors []}
          running))
