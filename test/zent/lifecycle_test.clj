(ns zent.lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [zent.lifecycle :as lifecycle]
            [zent.session :as session]
            [zent.shell :as shell]))

(deftest down-dispatches-process-handles-test
  (testing "a java.lang.Process handle is routed to stop-process!"
    (let [calls (atom [])]
      (with-redefs [lifecycle/stop-process! (fn [p] (swap! calls conj p))
                    lifecycle/stop-docker-compose! (fn [_] (throw (ex-info "should not be called" {})))]
        (let [fake-proc (proxy [java.lang.Process] []
                          (destroy [] nil))
              result (lifecycle/down! [fake-proc])]
          (is (= [fake-proc] @calls))
          (is (= [fake-proc] (:stopped result)))
          (is (empty? (:errors result))))))))

(deftest down-dispatches-docker-compose-handles-test
  (testing "a {:kind :docker-compose ...} handle is routed to stop-docker-compose!"
    (let [calls (atom [])
          handle {:kind :docker-compose :repo-dir "/repos/broker-stack"}]
      (with-redefs [lifecycle/stop-process! (fn [_] (throw (ex-info "should not be called" {})))
                    lifecycle/stop-docker-compose! (fn [h] (swap! calls conj h))]
        (let [result (lifecycle/down! [handle])]
          (is (= [handle] @calls))
          (is (= [handle] (:stopped result)))
          (is (empty? (:errors result))))))))

(deftest down-collects-errors-and-keeps-going-test
  (testing "a failing handle is recorded in :errors, teardown continues to the rest"
    (let [ok-handle {:kind :docker-compose :repo-dir "/repos/ok"}
          bad-handle {:kind :docker-compose :repo-dir "/repos/bad"}
          third-handle {:kind :docker-compose :repo-dir "/repos/third"}
          stopped (atom [])]
      (with-redefs [lifecycle/stop-docker-compose!
                    (fn [{:keys [repo-dir] :as h}]
                      (if (= repo-dir "/repos/bad")
                        (throw (ex-info "boom" {:repo-dir repo-dir}))
                        (swap! stopped conj h)))]
        (let [result (lifecycle/down! [ok-handle bad-handle third-handle])]
          (is (= [ok-handle third-handle] (:stopped result)))
          (is (= 1 (count (:errors result))))
          (is (= bad-handle (:handle (first (:errors result)))))
          (is (= "boom" (.getMessage ^Exception (:error (first (:errors result))))))
          (is (= [ok-handle third-handle] @stopped)))))))

(deftest down-external-handle-is-a-noop-test
  (testing "an :external handle (an IDE-run service) stops cleanly without shelling out -
            zent never started it, so there's nothing to tear down"
    (with-redefs [lifecycle/stop-docker-compose!
                  (fn [_] (throw (ex-info "should not be called" {})))]
      (let [result (lifecycle/down! [{:kind :external}])]
        (is (= [{:kind :external}] (:stopped result)))
        (is (empty? (:errors result)))))))

(deftest descendants-test
  (testing "a launcher's forked children are discovered - the npm/jbang case"
    ;; sh forks rather than exec-ing when the script has more than one command
    (let [parent (.start (ProcessBuilder. ["sh" "-c" "sleep 30; true"]))]
      (try
        (Thread/sleep 300)
        (let [kids (lifecycle/descendants-of (.pid parent))]
          (is (seq kids))
          (is (every? #(instance? java.lang.ProcessHandle %) kids)))
        (finally (.destroyForcibly parent)))))

  (testing "no children, no descendants; a dead pid answers empty rather than throwing"
    (let [proc (.start (ProcessBuilder. ["sleep" "30"]))
          pid (.pid proc)]
      (is (= [] (lifecycle/descendants-of pid)))
      (.destroyForcibly proc)
      (.waitFor proc)
      (is (= [] (lifecycle/descendants-of pid))))))

(deftest stop-process-sweeps-descendants-test
  (testing "a live Process whose launcher doesn't forward SIGTERM (sh here) doesn't
            orphan its children - found checking npm/jbang/mvn trees: npm forwards,
            sh (and a SIGKILL escalation) doesn't"
    (let [parent (.start (ProcessBuilder. ["sh" "-c" "sleep 30; true"]))]
      (try
        (Thread/sleep 300)
        (let [kids (lifecycle/descendants-of (.pid parent))]
          (is (seq kids) "precondition: sh forked a sleep")
          (lifecycle/stop-process! parent)
          (is (not (.isAlive parent)))
          (is (not-any? #(.isAlive %) kids) "no orphaned child left behind"))
        (finally (.destroyForcibly parent))))))

(deftest stop-pid-test
  (testing "a pid handle from an earlier run stops the real process"
    (let [proc (.start (ProcessBuilder. ["sleep" "30"]))
          pid (.pid proc)]
      (try
        (is (.isAlive proc))
        (lifecycle/stop-pid! {:kind :pid :component :notifier :pid pid
                              :ps (session/pid-identity pid)})
        (is (not (.isAlive proc)))
        (finally (.destroyForcibly proc)))))

  (testing "a recycled pid is left alone: same pid, different process identity"
    (let [proc (.start (ProcessBuilder. ["sleep" "30"]))]
      (try
        (lifecycle/stop-pid! {:kind :pid :component :notifier :pid (.pid proc)
                              :ps "Thu Jan  1 00:00:00 1970 java -jar something-else.jar"})
        (is (.isAlive proc) "must not kill a process it can't identify as its own")
        (finally (.destroyForcibly proc)))))

  (testing "a pid with no recorded identity is left alone too - it can't be proven ours"
    (let [proc (.start (ProcessBuilder. ["sleep" "30"]))]
      (try
        (lifecycle/stop-pid! {:kind :pid :component :x :pid (.pid proc) :ps nil})
        (is (.isAlive proc))
        (finally (.destroyForcibly proc)))))

  (testing "descendants die too - a launcher's children would otherwise survive"
    (let [parent (.start (ProcessBuilder. ["sh" "-c" "sleep 30; true"]))
          pid (.pid parent)]
      (try
        (Thread/sleep 300)
        (let [kids (lifecycle/descendants-of pid)]
          (is (seq kids) "precondition: sh forked a sleep")
          (lifecycle/stop-pid! {:kind :pid :component :webui :pid pid
                                :ps (session/pid-identity pid)})
          (is (not (.isAlive parent)))
          (is (not-any? #(.isAlive %) kids) "no orphaned grandchild left behind"))
        (finally (.destroyForcibly parent)))))

  (testing "a pid that's already gone is reported, not an error"
    (let [proc (.start (ProcessBuilder. ["sleep" "0.01"]))
          pid (.pid proc)]
      (.waitFor proc)
      (is (nil? (lifecycle/stop-pid! {:kind :pid :component :gone :pid pid}))))))

(deftest down-unknown-handle-is-an-error-not-a-crash-test
  (testing "an unrecognized handle shape is caught and reported, not thrown out of down!"
    (let [result (lifecycle/down! [{:kind :mystery}])]
      (is (empty? (:stopped result)))
      (is (= 1 (count (:errors result)))))))

(defmethod lifecycle/stop! ::widget [h] (swap! (:calls h) conj :stopped))

(deftest down-any-kind-handle-test
  (testing "any :kind's handle stops through its own stop! method"
    (let [h {:kind ::widget :calls (atom [])}
          result (lifecycle/down! [h])]
      (is (= [:stopped] @(:calls h)))
      (is (= [h] (:stopped result))))))

(deftest down-k8s-handle-test
  (testing "a {:kind :k8s ...} handle deletes exactly its rendered kustomization,
            with its own --context - never the namespace or the pull secret"
    (let [cmds (atom [])]
      (with-redefs [shell/sh! (fn [cmd & _] (swap! cmds conj (shell/display cmd)) {:exit 0 :out "" :err ""})]
        (let [h {:kind :k8s :context "local-k8s" :namespace "dev"
                 :dir "/cache/k8s/front" :workload "deployment/x"}
              result (lifecycle/down! [h])]
          (is (= ["kubectl --context local-k8s delete -k /cache/k8s/front --ignore-not-found"] @cmds))
          (is (= [h] (:stopped result)))))))

  (testing "a failed delete is recorded as an error, not thrown"
    (with-redefs [shell/sh! (fn [& _] {:exit 1 :out "" :err "unreachable"})]
      (is (= 1 (count (:errors (lifecycle/down! [{:kind :k8s :context "c" :dir "/d"}]))))))))
