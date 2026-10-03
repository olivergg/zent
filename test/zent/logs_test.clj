(ns zent.logs-test
  "Log files against a temp *dir* - never the real ~/.cache/zent/logs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [zent.logs :as logs]
            [zent.shell :as shell]))

(def ^:private dir
  (str (System/getProperty "java.io.tmpdir") "/zent-logs-test-" (System/currentTimeMillis)))

(use-fixtures :each (fn [t] (binding [logs/*dir* dir] (t))))

(deftest tail-of-nothing-test
  (testing "no log yet reads as empty, not as an error"
    (is (= [] (logs/tail :never-started 10)))))

(deftest previous-run-kept-test
  (testing "given a run's log, when the next run starts, then it's kept as .log.1
            - a crash's cause survives the redeploy - and the new log starts empty"
    (logs/start-run! :crashy)
    (logs/append! :crashy "OutOfMemoryError")
    (logs/start-run! :crashy)
    (logs/append! :crashy "booting again")
    (is (= ["booting again"] (logs/tail :crashy 10)))
    (is (= "OutOfMemoryError\n" (slurp (str (logs/log-file :crashy) ".1")))))
  (testing "given an empty log (nothing ran), then the kept run isn't overwritten by it"
    (logs/start-run! :crashy)
    (logs/start-run! :crashy)
    (is (= "booting again\n" (slurp (str (logs/log-file :crashy) ".1"))))))

(deftest strip-ansi-test
  (testing "colours, styles and OSC links go; the text stays"
    (is (= "INFO started in 1.2s"
           (logs/strip-ansi "\u001B[32mINFO\u001B[0m started in \u001B[1;34m1.2s\u001B[0m")))
    (is (= "see docs" (logs/strip-ansi "see \u001B]8;;http://x\u0007docs\u001B]8;;\u0007")))
    (is (= "no codes [main] here" (logs/strip-ansi "no codes [main] here")))))

(deftest sensitive-marks-test
  (testing "given a log marked as holding secrets, then the mark survives in
            the file - a later process sees it - until a fresh run truncates the log"
    (is (not (logs/sensitive? :relay)))
    (logs/mark-sensitive! :relay)
    (is (logs/sensitive? :relay))
    (logs/start-run! :relay)
    (is (not (logs/sensitive? :relay)))))

(deftest append-and-tail-test
  (logs/start-run! :api)
  (logs/append! :api "first")
  (logs/append! :api "second\n")

  (testing "appends are newline-terminated whether or not the caller says so"
    (is (= ["first" "second"] (logs/tail :api 10))))

  (testing "tail returns the last n lines, oldest first"
    (logs/append! :api "third")
    (is (= ["second" "third"] (logs/tail :api 2))))

  (testing "blank appends are dropped rather than padding the log"
    (logs/append! :api "")
    (logs/append! :api "   ")
    (is (= 3 (count (logs/tail :api 100)))))

  (testing "start-run! truncates, so a log covers one run only"
    (logs/start-run! :api)
    (is (= [] (logs/tail :api 10)))))

(deftest spawn-redirects-to-log-file-test
  (testing "a spawned child's stderr lands in its log file, not on zent's own"
    (let [path (logs/start-run! :child)
          proc (shell/spawn! "ls /definitely-not-a-real-path" :log-file path)]
      (.waitFor proc)
      (is (some #(re-find #"definitely-not-a-real-path" %) (logs/tail :child 20))))))
