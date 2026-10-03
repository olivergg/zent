(ns zent.shell-test
  "Exercises zent.shell/sh! with real, harmless sub-processes only -
  echo/pwd/printenv. No docker/mvn/kubectl, no destructive commands."
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest sh-env-test
  (testing ":env is visible to the sub-process"
    (let [{:keys [exit out]} (shell/sh! "printenv ZENT_TEST_VAR"
                                         :env {"ZENT_TEST_VAR" "zent-was-here"
                                               ;; printenv itself needs PATH resolved
                                               "PATH" (System/getenv "PATH")})]
      (is (= 0 exit))
      (is (= "zent-was-here\n" out)))))
