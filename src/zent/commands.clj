(ns zent.commands
  "Pure command construction, apart from zent.shell's execution so tests
  can check commands without running them. Every result is an argv vector,
  so a path holding a space stays one argument.")

(defn- compose
  "`docker compose` scoped to one repo's file (default docker-compose.yaml),
  project and profiles, `args` appended."
  [repo-dir {:keys [file project-name profiles]} & args]
  (-> ["docker" "compose" "-f" (str repo-dir "/" (or file "docker-compose.yaml"))]
      (cond-> project-name (conj "-p" project-name))
      (into (mapcat #(vector "--profile" %)) profiles)
      (into args)))

(defn- service-args
  "Trailing service names, for the commands that take a subset."
  [services]
  (map name services))

(defn docker-compose-cmd
  "`docker compose up -d --wait` for the whole stack. opts: :file (under
  repo-dir), :project-name, :profiles."
  ([repo-dir] (docker-compose-cmd repo-dir {}))
  ([repo-dir opts] (compose repo-dir opts "up" "-d" "--wait")))

(defn docker-compose-down-cmd
  "Teardown mirror of docker-compose-cmd - with the same --profile flags:
  without them `down` silently leaves profile-scoped services running."
  ([repo-dir] (docker-compose-down-cmd repo-dir {}))
  ([repo-dir opts] (compose repo-dir opts "down")))

(defn docker-compose-services-cmd
  "Brings up only the named services of a compose file - e.g. a webapp's
  infra without the webapp itself."
  [repo-dir services opts]
  (apply compose repo-dir opts "up" "-d" "--wait" (service-args services)))

(defn docker-compose-list-services-cmd
  "Which services a file declares, per compose itself: `config` resolves
  extends/includes/interpolation first, so it's the list `up` acts on."
  [repo-dir opts]
  (compose repo-dir opts "config" "--services"))

(defn docker-compose-ps-cmd
  "Per-service state/health for a compose stack, or just `services` of it."
  ([repo-dir opts] (docker-compose-ps-cmd repo-dir opts nil))
  ([repo-dir opts services]
   (apply compose repo-dir opts "ps" "-a" "--format" "{{.Service}}|{{.State}}|{{.Health}}|{{.Ports}}"
          (service-args services))))

(defn docker-compose-logs-cmd
  "Follows the containers' output - `services` of the stack, or all - from
  `since` (RFC 3339) or from now. Colour forced: it goes to a file."
  [repo-dir opts services since]
  (apply compose repo-dir opts "--ansi" "always" "logs" "-f"
         (concat (if since ["--since" since] ["--tail" "0"]) (service-args services))))

(defn docker-compose-services-rm-cmd
  "Tears down only the named services - `rm -sf`, not `down`, which would
  take the whole project (and its network) with it."
  [repo-dir services opts]
  (apply compose repo-dir opts "rm" "-sf" (service-args services)))

(defn quarkus-build-cmd
  "Host mvn, never ./mvnw."
  []
  ["mvn" "package" "-DskipTests" "-q"])

(defn quarkus-run-cmd
  "Runs the built quarkus-run.jar with jdk-home's java by absolute path:
  ProcessBuilder resolves a bare \"java\" against zent's own PATH, whatever
  the child's :env says."
  [jdk-home]
  [(str jdk-home "/bin/java") "-jar" "target/quarkus-app/quarkus-run.jar"])

(defn quarkus-env
  "Env pinning JAVA_HOME/PATH to `jdk-home` for a quarkus-app's build and
  run (so an older default JDK can't break the build), `extra-env` on top."
  [{:keys [jdk-home path extra-env]}]
  (merge {"JAVA_HOME" jdk-home
          "PATH" (str jdk-home "/bin" (when (seq path) (str ":" path)))}
         extra-env))

;; ---------------------------------------------------------------------------
;; kubectl - every command pins --context: kubectl's current-context may be prod.
;; ---------------------------------------------------------------------------

(defn- kubectl [context & args]
  (into ["kubectl" "--context" context] args))

(defn kubectl-apply-k-cmd
  "Applies a kustomization dir rendered by zent.k8s."
  [context dir]
  (kubectl context "apply" "-k" dir))

(defn kubectl-delete-k-cmd
  "Tears down exactly what kubectl-apply-k-cmd applied. --ignore-not-found:
  already gone (deleted by hand, a previous teardown) is success, not error."
  [context dir]
  (kubectl context "delete" "-k" dir "--ignore-not-found"))

(defn kubectl-rollout-status-cmd
  "Blocks until `workload` (deployment/x, statefulset/y) is rolled out, or
  fails after timeout-s - the k8s `up --wait`."
  [context namespace workload timeout-s]
  (kubectl context "-n" namespace "rollout" "status" workload (str "--timeout=" timeout-s "s")))

(defn kubectl-ready-replicas-cmd
  "Prints `workload`'s ready replica count (empty when none)."
  [context namespace workload]
  (kubectl context "-n" namespace "get" workload "-o" "jsonpath={.status.readyReplicas}"
           "--request-timeout=10s"))

(defn kubectl-logs-follow-cmd
  "Follows `workload`'s containers, each line prefixed with its pod/container,
  from `since` (RFC 3339) or from now."
  [context namespace workload since]
  (kubectl context "-n" namespace "logs" "-f" workload "--all-containers" "--prefix"
           (if since (str "--since-time=" since) "--tail=0")))

(defn kubectl-logs-tail-cmd
  [context namespace workload lines]
  (kubectl context "-n" namespace "logs" workload (str "--tail=" lines)))

(defn kubectl-get-namespace-cmd [context namespace]
  (kubectl context "get" "namespace" namespace))

(defn kubectl-create-namespace-cmd [context namespace]
  (kubectl context "create" "namespace" namespace))

(defn kubectl-delete-secret-cmd [context namespace secret]
  (kubectl context "-n" namespace "delete" "secret" secret "--ignore-not-found"))

(defn kubectl-create-registry-secret-cmd
  "A dockerconfigjson pull secret from a file, not --docker-password: the
  registry token never appears in argv (ps) or zent's own logs."
  [context namespace secret file]
  (kubectl context "-n" namespace "create" "secret" "generic" secret
           "--type=kubernetes.io/dockerconfigjson" (str "--from-file=.dockerconfigjson=" file)))
