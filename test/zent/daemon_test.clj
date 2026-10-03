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
      (is (re-find #"^-rw-------" (:out (sh/sh "ls" "-l" path))))
      (is (= (System/getProperty "user.dir") (:engine-dir (daemon/live path)))))

    (testing "given a card whose process identity no longer matches (crashed,
              pid recycled), then it reads as no daemon and is removed"
      (spit path (pr-str (assoc (daemon/live path) :identity "stale")))
      (is (nil? (daemon/live path)))
      (is (not (.exists (io/file path)))))
    (finally (daemon/clear! path))))

(deftest engine-gone-test
  (testing "a serve whose engine dir was removed (package upgrade) is flagged;
            an existing dir, or a card from before :engine-dir, is not"
    (is (daemon/engine-gone? {:engine-dir "/no/such/zent/install"}))
    (is (not (daemon/engine-gone? {:engine-dir (System/getProperty "user.dir")})))
    (is (not (daemon/engine-gone? {})))))
