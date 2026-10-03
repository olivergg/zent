(ns zent.session-test
  "Session round-tripping against a temp path - never the real
  ~/.cache/zent/session.edn."
  (:require [clojure.test :refer [deftest is testing]]
            [zent.session :as session])
  (:import [java.io File]))

(defn- tmp-path []
  (str (System/getProperty "java.io.tmpdir") "/zent-session-test-"
       (System/currentTimeMillis) "/session.edn"))

(defn- sleeper
  "A real (harmless) child process, so PID/cmd/start-time come from the OS
  rather than from a stub - that identity is the whole point of describe."
  []
  (.start (ProcessBuilder. ["sleep" "30"])))

(deftest describe-test
  (testing "a docker-compose handle is already EDN, and gains its component name"
    (is (= {:kind :docker-compose :repo-dir "/repos/broker" :compose-opts {} :component :broker}
           (session/describe :broker {:kind :docker-compose :repo-dir "/repos/broker"
                                     :compose-opts {}}))))

  (testing "a compose-services handle is described the same way - it's just as
            tearable-down by a later daemon as a whole-stack one"
    (is (= {:kind :compose-services :repo-dir "/repos/webapp" :compose-opts {}
            :services [:postgres] :component :webapp-deps}
           (session/describe :webapp-deps {:kind :compose-services :repo-dir "/repos/webapp"
                                            :compose-opts {} :services [:postgres]}))))

  (testing "any kind's map handle is persisted as is"
    (is (= {:kind :widget :id 1 :component :w} (session/describe :w {:kind :widget :id 1}))))

  (testing "nothing a later run could tear down is not described"
    (is (nil? (session/describe :broker-seed nil)))
    (is (nil? (session/describe :x (Object.))))
    (is (nil? (session/describe :webapp {:kind :external}))))

  (testing "a live process is described by pid plus enough identity to re-check it"
    (let [proc (sleeper)]
      (try
        (let [d (session/describe :notifier proc)]
          (is (= :pid (:kind d)))
          (is (= (.pid proc) (:pid d)))
          (is (= :notifier (:component d)))
          (is (re-find #"sleep 30" (:ps d)) "argv, so a recycled pid is detectable"))
        (finally (.destroy proc))))))

(deftest pid-identity-test
  (testing "a dead pid has no identity, so stop-pid! can tell 'gone' from 'recycled'"
    (let [proc (.start (ProcessBuilder. ["sleep" "0.01"]))
          pid (.pid proc)]
      (.waitFor proc)
      (is (nil? (session/pid-identity pid))))))

(deftest save-read-clear-test
  (let [path (tmp-path)
        proc (sleeper)]
    (try
      (testing "save! writes an EDN session, dropping handles with nothing to stop"
        (session/save! path :sms-pipeline
                       [[:broker {:kind :docker-compose :repo-dir "/repos/broker" :compose-opts {}}]
                        [:broker-seed nil]
                        [:notifier proc]])
        (let [{:keys [preset handles written-at]} (session/read-session path)]
          (is (= :sms-pipeline preset))
          (is (pos-int? written-at))
          (is (= [:broker :notifier] (mapv :component handles)))
          (is (= [:docker-compose :pid] (mapv :kind handles))))
        (is (not (.exists (File. (str path ".tmp")))) "written via a temp file renamed in"))

      (testing "a run that touches none of the previous run's components merges
                onto it rather than replacing it - those stay tracked for a
                later `zent down` until something actually stops them"
        (session/save! path :other-preset [[:ingestor nil]])
        (is (= [:broker :notifier] (mapv :component (:handles (session/read-session path))))
            "broker/notifier weren't touched by :other-preset, so they're still there")
        (is (= :other-preset (:preset (session/read-session path)))
            "the recorded preset is only the most recent invocation"))

      (testing "a run redeploying a previously-tracked component replaces its
                entry rather than duplicating it"
        (let [proc2 (sleeper)]
          (try
            (session/save! path :sms-pipeline [[:notifier proc2]])
            (is (= [(.pid proc2)]
                   (keep #(when (= :notifier (:component %)) (:pid %))
                         (:handles (session/read-session path)))))
            (finally (.destroy proc2)))))

      (testing "clearing then an all-one-shot run still writes a session - an
                absent file means \"no run\", not \"nothing running\""
        (session/clear! path)
        (session/save! path :seed-only [[:broker-seed nil]])
        (is (= [] (:handles (session/read-session path)))))

      (testing "no session at all reads as nil, and clearing twice doesn't throw"
        (session/clear! path)
        (is (nil? (session/read-session path)))
        (session/clear! path)
        (is (nil? (session/read-session path))))

      (testing "a corrupt session is an error, not a silent \"nothing running\""
        (spit (doto (File. path) (-> .getParentFile .mkdirs)) "{:handles [")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unreadable zent session"
                              (session/read-session path))))
      (finally
        (.destroy proc)
        (session/clear! path)))))

(deftest drop-components-test
  (let [path (tmp-path)]
    (try
      (session/save! path :sms-pipeline
                     [[:broker {:kind :docker-compose :repo-dir "/repos/broker" :compose-opts {}}]
                      [:webapp-deps {:kind :compose-services :repo-dir "/repos/webapp"
                                      :compose-opts {} :services [:postgres]}]])

      (testing "drops only the named components, leaving the rest untouched -
                for a serve that tore down what it started but must leave
                what's still running tracked"
        (session/drop-components! path [:broker])
        (is (= [:webapp-deps] (mapv :component (:handles (session/read-session path))))))

      (testing "dropping everything left clears the file rather than writing
                an empty session under a stale :preset"
        (session/drop-components! path [:webapp-deps])
        (is (nil? (session/read-session path))))

      (testing "nothing to drop against no session at all is a no-op, not a throw"
        (is (nil? (session/drop-components! path [:broker]))))
      (finally (session/clear! path)))))

(deftest k8s-handle-round-trip-test
  (let [path (tmp-path)
        h {:kind :k8s :context "local-k8s" :namespace "dev"
           :dir "/cache/k8s/front" :workload "deployment/x"}]
    (try
      (testing "a :k8s handle is plain data already - kept as-is, plus its component"
        (is (= (assoc h :component :front) (session/describe :front h))))

      (testing "and it survives the session file, so a later `zent down` can delete it"
        (session/save! path :k8s-preset [[:front h]])
        (is (= [(assoc h :component :front)] (:handles (session/read-session path)))))
      (finally (session/clear! path)))))
