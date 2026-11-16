(ns zent.shell-test
  "Exercises zent.shell/sh! with real, harmless sub-processes only -
  echo/pwd/printenv. No docker/mvn/kubectl, no destructive commands."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zent.shell :as shell]))

(deftest sh-basic-test
  (testing "runs a command and captures stdout/exit"
    (let [{:keys [exit out]} (shell/sh! "echo hello")]
      (is (= 0 exit))
      (is (= "hello\n" out)))))

(deftest sh-argv-test
  (testing "given an argv vector, then an argument holding spaces arrives whole"
    (is (= "a b  c\n" (:out (shell/sh! ["echo" "a b  c"])))))
  (testing "given a string (a catalog's :cmd), then it's split on whitespace"
    (is (= "a b c\n" (:out (shell/sh! "echo a   b c"))))))

(deftest sh-dir-test
  (testing ":dir overrides the sub-process's working directory"
    (let [{:keys [exit out]} (shell/sh! "pwd" :dir "/tmp")]
      (is (= 0 exit))
      (is (= "/tmp\n" out)))))

(deftest sh-timeout-test
  (testing "the output, :env merged; nil on a failure or a command that can't run; killed and nil past its timeout"
    (is (= "C\n" (shell/sh-timeout! ["sh" "-c" "echo $LC_ALL"] 5000 :env {"LC_ALL" "C"})))
    (is (nil? (shell/sh-timeout! ["sh" "-c" "exit 3"] 5000)))
    (is (nil? (shell/sh-timeout! ["zent-no-such-command"] 5000)))
    (let [t0 (System/currentTimeMillis)]
      (is (nil? (shell/sh-timeout! ["sleep" "5"] 300)))
      (is (< (- (System/currentTimeMillis) t0) 2000)))))

(defn- zombie-children []
  (let [me (str (.pid (java.lang.ProcessHandle/current)))]
    (->> (:out (shell/sh! ["ps" "-axo" "ppid=,stat="]))
         str/split-lines
         (map #(str/split (str/trim %) #"\s+"))
         (filter (fn [[ppid stat]] (and (= me ppid) (str/starts-with? stat "Z"))))
         count)))

(deftest no-zombie-test
  (testing "given a spawned child that exits unwaited, or a sh-timeout! child killed past its timeout, then neither stays a zombie"
    (let [before (zombie-children)]
      (shell/spawn! ["true"] :log-file (str (java.io.File/createTempFile "zent-reap" ".log")))
      (shell/sh-timeout! ["sleep" "5"] 100)
      (Thread/sleep 500)
      (is (= before (zombie-children))))))

(deftest sh-env-test
  (testing ":env is visible to the sub-process"
    (let [{:keys [exit out]} (shell/sh! "printenv ZENT_TEST_VAR"
                                         :env {"ZENT_TEST_VAR" "zent-was-here"
                                               ;; printenv itself needs PATH resolved
                                               "PATH" (System/getenv "PATH")})]
      (is (= 0 exit))
      (is (= "zent-was-here\n" out)))))
