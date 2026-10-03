(ns zent.daemon-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [zent.daemon :as daemon]))

(def ^:private path (str (System/getProperty "java.io.tmpdir") "/zent-daemon-test.edn"))

(deftest register-and-live-test
  (try
    (testing "given no card, then there's no daemon"
      (daemon/clear! path)
      (is (nil? (daemon/live path))))

    (testing "given this process registered, then its card reads back, owner-only"
      (daemon/register! path {:port 1234 :token "t"})
      (is (= {:port 1234 :token "t" :pid (.pid (ProcessHandle/current))}
             (select-keys (daemon/live path) [:port :token :pid])))
      (is (re-find #"^-rw-------" (:out (sh/sh "ls" "-l" path)))))

    (testing "given a card whose process identity no longer matches (crashed,
              pid recycled), then it reads as no daemon and is removed"
      (spit path (pr-str (assoc (daemon/live path) :identity "stale")))
      (is (nil? (daemon/live path)))
      (is (not (.exists (io/file path)))))
    (finally (daemon/clear! path))))

(deftest outdated-test
  (testing "a serve on another version than this CLI is flagged; unknown on either side is not"
    (is (daemon/outdated? {:version "HEAD-aaa"} "HEAD-bbb"))
    (is (not (daemon/outdated? {:version "HEAD-aaa"} "HEAD-aaa")))
    (is (not (daemon/outdated? {} "HEAD-bbb")))
    (is (not (daemon/outdated? {:version "HEAD-aaa"} nil)))))
