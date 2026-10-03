(ns zent.kinds-test
  "The builtin kinds (zent.kinds), compose stacks above all. zent.shell is
  stubbed throughout: no docker, kubectl or build ever runs."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [zent.engine :as engine]
            [zent.kinds]
            [zent.logs :as logs]
            [zent.secrets :as secrets]
            [zent.shell :as shell])
  (:import [java.io File]))

;; nothing spawned for real either: a deployed stack starts its log follower
;; (zent.engine/follow-logs!) - recorded here instead
(def ^:private spawned (atom []))
(use-fixtures :each (fn [t]
                      (reset! spawned [])
                      (with-redefs [shell/spawn! (fn [cmd & _]
                                                   (swap! spawned conj (shell/display cmd))
                                                   (proxy [java.lang.Process] [] (pid [] 0)))]
                        (t))))

(def ^:private services-to-run #'zent.kinds/services-to-run)

(defn- with-tmp-repo [f]
  (let [root (str (System/getProperty "java.io.tmpdir") "/zent-kinds-test-"
                  (System/currentTimeMillis))
        repo-dir (str root "/some-repo")]
    (.mkdirs (File. repo-dir))
    (f root repo-dir)))

(def ^:private declared "postgres\nredis\nsftp\nsmtp\nftp-mock\n")

(deftest services-to-run-test
  (testing "no :services: takes over every service the file declares, asking
            compose rather than reading the yaml"
    (with-redefs [shell/sh! (fn [cmd & _]
                              (is (re-find #"config --services" (shell/display cmd)))
                              {:exit 0 :out declared :err ""})]
      (is (= [:postgres :redis :sftp :smtp :ftp-mock]
             (services-to-run :x "/repo" {} {})))))

  (testing "…minus :exclude - the file, but not all of it"
    (with-redefs [shell/sh! (fn [& _] {:exit 0 :out declared :err ""})]
      (is (= [:postgres :redis]
             (services-to-run :x "/repo" {} {:exclude [:sftp :smtp :ftp-mock]})))))

  (testing "an explicit :services list wins, and doesn't ask compose at all"
    (with-redefs [shell/sh! (fn [& _] (throw (ex-info "should not discover" {})))]
      (is (= [:postgres] (services-to-run :x "/repo" {} {:services [:postgres]})))))

  (testing "excluding everything is a config mistake, not a silent no-op"
    (with-redefs [shell/sh! (fn [& _] {:exit 0 :out declared :err ""})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no compose services left"
                            (services-to-run :x "/repo" {} {:services [:redis]
                                                            :exclude [:redis]})))))

  (testing "a failing discovery says so instead of coming up with nothing"
    (with-redefs [shell/sh! (fn [& _] {:exit 1 :out "" :err "no such file"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"couldn't list compose services"
                            (services-to-run :x "/repo" {} {}))))))

(deftest deploy-docker-compose-reports-its-services-test
  (with-tmp-repo
    (fn [root _repo-dir]
      (testing "a whole-stack component still brings up everything (the `up`
                takes no service args), and reports what's inside for display"
        (let [cmds (atom [])]
          (with-redefs [shell/sh! (fn [cmd & _]
                                    (swap! cmds conj (shell/display cmd))
                                    {:exit 0
                                     :out (if (re-find #"config --services" (shell/display cmd))
                                            "db\nsso\nmailcatcher\n" "")
                                     :err ""})]
            (let [handle (engine/deploy-kind!
                          :idp {:kind :docker-compose :repo "some-repo"
                                :workspace-dir root :profiles ["mailcatcher"]})]
              (is (some #(re-find #"--profile mailcatcher up -d --wait$" %) @cmds))
              (is (= [:db :sso :mailcatcher] (:services handle)))))))

      (testing "discovery is display-only, so a failing one leaves the deploy
                alone instead of taking a working stack down with it"
        (with-redefs [shell/sh! (fn [cmd & _]
                                  (if (re-find #"config --services" (shell/display cmd))
                                    {:exit 1 :out "" :err "boom"}
                                    {:exit 0 :out "" :err ""}))]
          (let [handle (engine/deploy-kind! :idp {:kind :docker-compose :repo "some-repo"
                                                  :workspace-dir root})]
            (is (= :docker-compose (:kind handle)))
            (is (not (contains? handle :services)))))))))

(deftest local-commands-one-at-a-time-test
  (with-tmp-repo
    (fn [root _repo-dir]
      (let [inside (atom 0) most (atom 0)
            deploy-two (fn [extra]
                         (reset! most 0)
                         (binding [logs/*dir* (str root "/logs")]
                           (with-redefs [shell/sh! (fn [& _]
                                                     (swap! most max (swap! inside inc))
                                                     (Thread/sleep 100)
                                                     (swap! inside dec)
                                                     {:exit 0 :out "" :err ""})]
                             (run! deref (mapv #(future (engine/deploy-kind!
                                                         % (merge {:kind :one-shot :repo "some-repo"
                                                                   :workspace-dir root :scripts ["./seed.sh"]}
                                                                  extra)))
                                               [:a :b]))))
                         @most)]
        (testing "given two components' scripts deployed at once, then they run
                  one at a time - they share the filesystem"
          (is (= 1 (deploy-two {}))))
        (testing "given :allow-parallel, then they overlap"
          (is (= 2 (deploy-two {:allow-parallel true}))))))))

(deftest deploy-compose-services-test
  (with-tmp-repo
    (fn [root repo-dir]
      (let [cmds (atom [])]
        (with-redefs [shell/sh! (fn [cmd & _]
                                  (swap! cmds conj (shell/display cmd))
                                  {:exit 0
                                   :out (if (re-find #"config --services" (shell/display cmd)) declared "")
                                   :err ""})]
          (let [handle (engine/deploy-kind!
                        :deps {:kind :compose-services :repo "some-repo"
                               :workspace-dir root
                               :compose-file "docker/compose.yaml"
                               :exclude [:ftp-mock]})]
            (testing "it brings up exactly the services it resolved, scoped to
                      the file, and waits for them"
              (is (some #(re-find #"-f .*/docker/compose\.yaml up -d --wait postgres redis sftp smtp$" %)
                        @cmds)))

            (testing "the handle carries the RESOLVED list, not the cfg's - it's
                      what teardown and the dashboard both read"
              (is (= {:kind :compose-services
                      :repo-dir repo-dir
                      :compose-opts {:file "docker/compose.yaml" :project-name nil}
                      :services [:postgres :redis :sftp :smtp]
                      :follower {:pid 0 :ps nil}}
                     handle)))))))))

(deftest already-running-test
  (with-tmp-repo
    (fn [root _repo-dir]
      (testing ":compose-services - every resolved service running reads as already
                up: what adoption and the liveness check rely on"
        (with-redefs [shell/sh! (fn [cmd & _]
                                  (cond
                                    (re-find #"config --services" (shell/display cmd)) {:exit 0 :out declared :err ""}
                                    (re-find #"ps -a" (shell/display cmd))
                                    {:exit 0 :out "postgres|running||\nredis|running||\nsftp|running||\nsmtp|running||\n"
                                     :err ""}
                                    :else {:exit 0 :out "" :err ""}))]
          (is (true? (engine/already-running? :deps {:kind :compose-services :repo "some-repo"
                                                      :workspace-dir root
                                                      :exclude [:ftp-mock]})))))

      (testing "…but not if even one of them isn't up yet"
        (with-redefs [shell/sh! (fn [cmd & _]
                                  (cond
                                    (re-find #"config --services" (shell/display cmd)) {:exit 0 :out declared :err ""}
                                    (re-find #"ps -a" (shell/display cmd))
                                    {:exit 0 :out "postgres|running||\nredis|exited||\nsftp|running||\nsmtp|running||\n"
                                     :err ""}
                                    :else {:exit 0 :out "" :err ""}))]
          (is (false? (engine/already-running? :deps {:kind :compose-services :repo "some-repo"
                                                       :workspace-dir root
                                                       :exclude [:ftp-mock]})))))

      (testing ":docker-compose - same check, over whatever the file declares"
        (with-redefs [shell/sh! (fn [cmd & _]
                                  (cond
                                    (re-find #"config --services" (shell/display cmd)) {:exit 0 :out "db\n" :err ""}
                                    (re-find #"ps -a" (shell/display cmd)) {:exit 0 :out "db|running||\n" :err ""}
                                    :else {:exit 0 :out "" :err ""}))]
          (is (true? (engine/already-running? :idp {:kind :docker-compose :repo "some-repo"
                                                     :workspace-dir root})))))

      (testing "a docker hiccup answers false, not a throw - never a false claim
                that something about to be touched is safe to leave alone"
        (with-redefs [shell/sh! (fn [& _] (throw (ex-info "docker gone" {})))]
          (is (false? (engine/already-running? :deps {:kind :compose-services :repo "some-repo"
                                                       :workspace-dir root}))))))))

(def ^:private service-status #'zent.kinds/service-status)

(deftest service-status-test
  (testing "one row per container, with health and published ports only when
            there are any - compose reports each mapping twice (v4 then v6),
            so the ports are deduped - from a `|`-separated Go template"
    (with-redefs [shell/sh! (fn [cmd & _]
                              (is (re-find #"ps -a --format \{\{\.Service\}\}" (shell/display cmd)))
                              {:exit 0
                               :out (str "sftp|running||0.0.0.0:7777->22/tcp, [::]:7777->22/tcp\n"
                                         "ftp-mock|running|healthy|\n"
                                         "postgres|exited||\n")
                               :err ""})]
      (is (= [{:service :sftp :state "running" :ports ["7777"]}
              {:service :ftp-mock :state "running" :health "healthy"}
              {:service :postgres :state "exited"}]
             (service-status "/repo" {} nil)))))

  (testing "best-effort: it's what the dashboard shows, never what the deploy
            depends on, so a failure is nil rather than an exception"
    (with-redefs [shell/sh! (fn [& _] {:exit 1 :out "" :err "boom"})]
      (is (nil? (service-status "/repo" {} nil))))
    (with-redefs [shell/sh! (fn [& _] (throw (ex-info "docker gone" {})))]
      (is (nil? (service-status "/repo" {} nil))))))

(deftest deploy-k8s-refusals-test
  (let [calls (atom [])
        record (fn [& args] (swap! calls conj args) {:exit 0 :out "" :err ""})
        cfg {:kind :k8s :mode :on :repo "manifests" :manifests ["m.yaml"] :workload "deployment/x"
             :namespace "dev" :allow-k8s-contexts ["local-k8s"]}]
    (testing "a context the catalog never allowed (prod, say) is refused before any
              kubectl call - not even a render or a namespace check"
      (with-redefs [shell/sh! record shell/spawn! record]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"isn't in :allow-k8s-contexts"
                              (engine/deploy-kind! :front (assoc cfg :context "prod")))))
      (is (= [] @calls)))

    (testing "no allowlist at all refuses too - opting in is the catalog's job"
      (with-redefs [shell/sh! record shell/spawn! record]
        (is (thrown? clojure.lang.ExceptionInfo
                     (engine/deploy-kind! :front (-> cfg (dissoc :allow-k8s-contexts)
                                                     (assoc :context "local-k8s"))))))
      (is (= [] @calls)))

    (testing "an image placeholder with no image to put there throws"
      (with-redefs [shell/sh! record shell/spawn! record]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :image"
                              (engine/deploy-kind! :front (assoc cfg :context "local-k8s"
                                                                  :image-placeholder "team/x")))))
      (is (= [] @calls)))))

(deftest already-running-k8s-test
  (testing "a :k8s component is up when its workload has a ready replica, via the
            same explicit --context"
    (let [seen (atom nil)]
      (with-redefs [shell/sh! (fn [cmd & _] (reset! seen (shell/display cmd)) {:exit 0 :out "1"})]
        (is (true? (engine/already-running? :front {:kind :k8s :context "c" :namespace "n"
                                                     :workload "deployment/x"}))))
      (is (str/includes? @seen "--context c")))))

(deftest already-running-reuses-the-handle-test
  (testing "given a handle that knows its services, then a liveness check asks
            compose only for their state - no `config --services` each time"
    (let [cmds (atom [])]
      (with-redefs [shell/sh! (fn [cmd & _]
                                (swap! cmds conj (shell/display cmd))
                                {:exit 0 :out "db|running||\napi|running||\n" :err ""})]
        (doseq [kind [:docker-compose :compose-services]]
          (is (true? (engine/already-running? :s {:kind kind :repo "r"
                                                  :zent.engine/handle {:services [:db :api]}})))))
      (is (= 2 (count @cmds)))
      (is (not-any? #(re-find #"config --services" %) @cmds)))))

(deftest log-follower-test
  (with-tmp-repo
    (fn [root _repo-dir]
      (binding [logs/*dir* (str root "/logs")]
        (with-redefs [shell/sh! (fn [cmd & _]
                                  {:exit 0 :out (if (re-find #"config --services" (shell/display cmd)) declared "") :err ""})]
          (let [cfg {:kind :compose-services :repo "some-repo" :workspace-dir root :services [:postgres :redis]}]
            (testing "given a deployed stack, then its own services' output follows into its log, from now"
              (let [handle (engine/deploy-kind! :deps cfg)]
                (is (re-find #" --ansi always logs -f --tail 0 postgres redis$" (last @spawned)))
                (is (= {:pid 0 :ps nil} (:follower handle)))
                (is (some #{"--- container logs ---"} (logs/tail :deps 20)))))

            (testing "given a daemon taking it over after its follower died, then it follows from a time"
              (reset! spawned [])
              (engine/follow-logs! :deps cfg {:repo-dir "/r" :compose-opts {} :services [:postgres]} 1790908005461)
              (is (re-find #"logs -f --since 2026-10-02T02:26:45.461Z postgres$" (last @spawned))))

            (testing "given :follow-logs false, then nothing follows it"
              (reset! spawned [])
              (is (nil? (:follower (engine/deploy-kind! :deps (assoc cfg :follow-logs false)))))
              (is (= [] @spawned)))))))))

(deftest stop-stops-the-follower-first-test
  (testing "given a stack with a log follower, when it's stopped, then the follower goes too, first"
    (let [stopped (atom [])]
      (with-redefs [zent.lifecycle/stop-pid! (fn [h] (swap! stopped conj [:follower (:pid h)]))
                    zent.lifecycle/stop-compose-services! (fn [_] (swap! stopped conj [:stack]))]
        (zent.lifecycle/down! [{:kind :compose-services :follower {:pid 42 :ps "x"}}]))
      (is (= [[:follower 42] [:stack]] @stopped)))))

(deftest deploy-process-secret-env-test
  (testing "secrets go to the spawned run's env only - the build never sees them"
    (let [calls (atom [])]
      (with-redefs [secrets/read-k8s-secret! (constantly "v")
                    shell/sh! (fn [cmd & {:keys [env]}] (swap! calls conj [:build cmd env]) {:exit 0 :out "" :err ""})
                    shell/spawn! (fn [cmd & {:keys [env]}] (swap! calls conj [:run cmd env]) nil)]
        (engine/deploy-component! :svc {:kind :process :mode :on :cmd "run" :build-cmd "build"
                                   :env {"A" "1" "FORCE_COLOR" "0"}
                                   :use-secret-env true :allow-secret-contexts ["c"]
                                   :secret-env {"ID" {:context "c" :namespace "n" :secret "s" :key "k"}}})
        (let [[[_ build-cmd build-env] [_ run-cmd run-env]] @calls]
          (is (= ["build" "run"] [build-cmd run-cmd]))
          (is (not (contains? build-env "ID")))
          (is (= "v" (run-env "ID")))
          (testing "and both are asked for colour, the Logs tab renders it - unless the component says otherwise"
            (is (= "1" (build-env "CLICOLOR_FORCE") (run-env "CLICOLOR_FORCE")))
            (is (= "0" (build-env "FORCE_COLOR") (run-env "FORCE_COLOR")))))))))
