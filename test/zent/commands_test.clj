(ns zent.commands-test
  "Pure command-construction tests - no execution, no side effects. Shapes
  are compared as one line (shell/display) for readability."
  (:require [clojure.test :refer [deftest is testing]]
            [zent.commands :as commands]
            [zent.shell :as shell]))

(def ^:private line shell/display)

(deftest docker-compose-cmd-test
  (is (= "docker compose -f /repos/broker-stack/docker-compose.yaml up -d --wait"
         (line (commands/docker-compose-cmd "/repos/broker-stack")))))

(deftest docker-compose-down-cmd-test
  (is (= "docker compose -f /repos/broker-stack/docker-compose.yaml down"
         (line (commands/docker-compose-down-cmd "/repos/broker-stack")))))

(deftest path-with-a-space-test
  (testing "a repo dir holding a space stays one argument - argv, not a split string"
    (is (= ["docker" "compose" "-f" "/Users/me/my repos/broker/docker-compose.yaml" "down"]
           (commands/docker-compose-down-cmd "/Users/me/my repos/broker")))
    (is (= "/opt/my jdk/bin/java" (first (commands/quarkus-run-cmd "/opt/my jdk"))))))

(deftest docker-compose-services-cmd-test
  (testing "only the named services, and --wait (unlike the whole-stack form):
            whoever asked for a scoped service is about to depend on it"
    (is (= (str "docker compose -f /repos/webapp/docker/local-docker-compose.yaml"
                " up -d --wait postgres redis smtp sftp")
           (line (commands/docker-compose-services-cmd
                  "/repos/webapp" [:postgres :redis :smtp :sftp]
                  {:file "docker/local-docker-compose.yaml"})))))

  (testing "teardown is rm -sf on those services only - `down` would take the
            whole project, including services another component still uses"
    (is (= (str "docker compose -f /repos/webapp/docker/local-docker-compose.yaml"
                " rm -sf postgres redis")
           (line (commands/docker-compose-services-rm-cmd
                  "/repos/webapp" [:postgres :redis]
                  {:file "docker/local-docker-compose.yaml"}))))))

(deftest docker-compose-cmd-opts-test
  (testing "opts: non-root compose file, project name, profiles"
    (is (= (str "docker compose -f /repos/idp/release/docker/docker-compose.yaml"
                " -p idp-local --profile mailcatcher up -d --wait")
           (line (commands/docker-compose-cmd "/repos/idp"
                                              {:file "release/docker/docker-compose.yaml"
                                               :project-name "idp-local"
                                               :profiles ["mailcatcher"]})))))

  (testing "down needs --profile too - a profile-scoped service (mailcatcher)
            is otherwise left running, not torn down (verified against a
            real docker compose down)"
    (is (= (str "docker compose -f /repos/idp/release/docker/docker-compose.yaml"
                " -p idp-local --profile mailcatcher down")
           (line (commands/docker-compose-down-cmd "/repos/idp"
                                                   {:file "release/docker/docker-compose.yaml"
                                                    :project-name "idp-local"
                                                    :profiles ["mailcatcher"]})))))

  (testing "nil opts degrade to the plain repo-root form"
    (is (= (commands/docker-compose-cmd "/repos/broker-stack")
           (commands/docker-compose-cmd "/repos/broker-stack"
                                        {:file nil :project-name nil :profiles nil})))))

(deftest quarkus-build-cmd-test
  (is (= "mvn package -DskipTests -q" (line (commands/quarkus-build-cmd)))))

(deftest quarkus-run-cmd-test
  (testing "jdk-home's own java, by absolute path - a bare \"java\" would
            resolve against the OS's PATH regardless of what :env overrides
            on the child (see quarkus-run-cmd's docstring)"
    (is (= "/opt/jdk25/bin/java -jar target/quarkus-app/quarkus-run.jar"
           (line (commands/quarkus-run-cmd "/opt/jdk25"))))))

(deftest quarkus-env-test
  (testing "pins JAVA_HOME/PATH, prepends jdk bin onto the caller's PATH, extra-env wins on collision"
    (is (= {"JAVA_HOME" "/opt/jdk25"
            "PATH" "/opt/jdk25/bin:/usr/bin"
            "FOO" "bar"}
           (commands/quarkus-env {:jdk-home "/opt/jdk25"
                                   :path "/usr/bin"
                                   :extra-env {"FOO" "bar"}}))))
  (testing "no caller PATH -> just the jdk bin dir"
    (is (= "/opt/jdk25/bin"
           (get (commands/quarkus-env {:jdk-home "/opt/jdk25"}) "PATH")))))

(deftest kubectl-cmds-test
  (testing "every kubectl command pins --context - kubectl's own current-context
            may be prod, never something to inherit silently"
    (doseq [cmd [(commands/kubectl-apply-k-cmd "ctx" "/d")
                 (commands/kubectl-delete-k-cmd "ctx" "/d")
                 (commands/kubectl-rollout-status-cmd "ctx" "ns" "deployment/x" 300)
                 (commands/kubectl-ready-replicas-cmd "ctx" "ns" "deployment/x")
                 (commands/kubectl-logs-tail-cmd "ctx" "ns" "deployment/x" 200)
                 (commands/kubectl-get-namespace-cmd "ctx" "ns")
                 (commands/kubectl-create-namespace-cmd "ctx" "ns")
                 (commands/kubectl-delete-secret-cmd "ctx" "ns" "regcred")
                 (commands/kubectl-create-registry-secret-cmd "ctx" "ns" "regcred" "/d/f")]]
      (is (= ["kubectl" "--context" "ctx"] (take 3 cmd)) (line cmd))))

  (testing "the exact shapes the :k8s kind relies on"
    (is (= "kubectl --context ctx apply -k /d" (line (commands/kubectl-apply-k-cmd "ctx" "/d"))))
    (is (= "kubectl --context ctx delete -k /d --ignore-not-found" (line (commands/kubectl-delete-k-cmd "ctx" "/d"))))
    (is (= "kubectl --context ctx -n ns rollout status statefulset/y --timeout=120s"
           (line (commands/kubectl-rollout-status-cmd "ctx" "ns" "statefulset/y" 120))))
    (is (= "kubectl --context ctx -n ns get deployment/x -o jsonpath={.status.readyReplicas} --request-timeout=10s"
           (line (commands/kubectl-ready-replicas-cmd "ctx" "ns" "deployment/x"))))
    (is (= (str "kubectl --context ctx -n ns create secret generic regcred"
                " --type=kubernetes.io/dockerconfigjson --from-file=.dockerconfigjson=/d/f")
           (line (commands/kubectl-create-registry-secret-cmd "ctx" "ns" "regcred" "/d/f")))
        "the token goes through a file, never argv")))
