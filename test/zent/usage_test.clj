(ns zent.usage-test
  (:require [clojure.test :refer [deftest is testing]]
            [zent.shell :as shell]
            [zent.usage :as usage]))

(def ^:private mib 1048576)

;; a quarkus-like tree (100 -> 101 -> 102), a lone process (200), an unrelated one (300)
(def ^:private ps-out
  (str "  100     1   0:01.00   0.0  1024\n"
       "  101   100   0:02.50   1.5  2048\n"
       "  102   101  12:00.00  40.0 4096\n"
       "  200     1   1-02:03:04   3.0  512\n"
       "  300     1   0:00.00   0.0  8\n"
       "garbage\n"))

(deftest cputime-test
  (testing "given ps's TIME on macOS (min:sec) and Linux ([dd-]hh:mm:ss), then seconds"
    (is (= 4665.66 (usage/cputime "77:45.66")))
    (is (= 3723.0 (usage/cputime "01:02:03")))
    (is (= 93784.0 (usage/cputime "1-02:03:04")))
    (is (nil? (usage/cputime "n/a")))))

(deftest parse-ps-test
  (testing "given ps output, then each pid's parent, CPU time, %cpu and RSS in bytes; junk skipped"
    (let [procs (usage/parse-ps ps-out)]
      (is (= 5 (count procs)))
      (is (= {:ppid 101 :cpu-s 720.0 :pcpu 40.0 :rss (* 4096 1024)} (procs 102))))))

(deftest tree-test
  (testing "given a root, then it and every descendant - not its siblings; a gone root, nothing"
    (let [procs (usage/parse-ps ps-out)]
      (is (= #{100 101 102} (usage/tree procs 100)))
      (is (= #{200} (usage/tree procs 200)))
      (is (= #{} (usage/tree procs 999))))))

(deftest tree-usage-test
  (let [procs (usage/parse-ps ps-out)]
    (testing "given no previous sample, then ps's %cpu summed over the tree, RSS summed"
      (is (= {:cpu 41.5 :rss (* 7168 1024) :procs 3} (usage/tree-usage procs #{100 101 102} nil nil))))
    (testing "given a previous sample 10s ago, then the CPU time spent since over 10s: 102 spent 5s
              (50%), 101 is new since (2.5s counts), 100 idle; a pid with less time than before
              (reused) counts 0, not negative"
      (is (= 75.0 (:cpu (usage/tree-usage procs #{100 101 102} {100 1.0 102 715.0} 10.0))))
      (is (= 0.0 (:cpu (usage/tree-usage procs #{100} {100 50.0} 10.0)))))))

(def ^:private docker-ps-out
  (str "aaa|/w/kafka/docker-compose.yaml|kafka\n"
       "bbb|/w/kafka/docker-compose.yaml|kafka-ui\n"
       "ccc|/w/app/docker/local.yaml,/w/app/docker/override.yaml|postgres\n"
       "ddd|/w/app/docker/local.yaml|redis\n"
       "eee||\n"))

(def ^:private docker-stats-out
  (str "aaa|292.72%|1.796GiB / 11.65GiB\n"
       "bbb|0.07%|648.4MiB / 11.65GiB\n"
       "ccc|10.14%|319.2MiB / 11.65GiB\n"
       "ddd|--|0B / 0B\n"))

(deftest parse-docker-test
  (testing "given docker ps, then each compose container's id, first file and service; others skipped"
    (is (= [{:id "aaa" :file "/w/kafka/docker-compose.yaml" :service :kafka}
            {:id "bbb" :file "/w/kafka/docker-compose.yaml" :service :kafka-ui}
            {:id "ccc" :file "/w/app/docker/local.yaml" :service :postgres}
            {:id "ddd" :file "/w/app/docker/local.yaml" :service :redis}]
           (usage/parse-docker-ps docker-ps-out))))
  (testing "given docker stats, then CPU % and the used half of MemUsage in bytes; a starting
            container's `--` skipped"
    (let [stats (usage/parse-docker-stats docker-stats-out)]
      (is (= #{"aaa" "bbb" "ccc"} (set (keys stats))))
      (is (= {:cpu 292.72 :rss (long (* 1.796 1073741824))} (stats "aaa")))))
  (testing "sizes in both unit families"
    (is (= 648400000 (usage/size "648.4MB")))
    (is (= (* 2 mib) (usage/size "2MiB")))
    (is (= 0 (usage/size "0B")))
    (is (nil? (usage/size "12 parsecs")))))

(def ^:private handles
  {:api {:kind :pid :component :api :pid 100}
   :gone {:kind :pid :component :gone :pid 999}
   :kafka {:kind :docker-compose :repo-dir "/w/kafka" :compose-opts {:file nil} :services [:kafka :kafka-ui]
           :follower {:pid 300}}
   ;; scoped to redis: postgres in the same file is somebody else's
   :cache {:kind :compose-services :repo-dir "/w/app" :compose-opts {:file "docker/local.yaml"} :services [:redis]}
   :db {:kind :compose-services :repo-dir "/w/app" :compose-opts {:file "docker/local.yaml"} :services [:postgres]}
   :ide {:kind :external}})

(def ^:private sample
  {:procs (usage/parse-ps ps-out) :containers (usage/parse-docker-ps docker-ps-out)
   :stats (usage/parse-docker-stats docker-stats-out)})

(deftest usage-test
  (let [u (usage/usage handles sample)]
    (testing "given a pid handle, then its whole tree"
      (is (= 3 (get-in u [:api :procs]))))
    (testing "given a compose handle, then its own services' containers, busiest first - not a
              follower's pid, nor another component's service of the same file"
      (is (< 292.7 (get-in u [:kafka :cpu]) 292.9))
      (is (= [:kafka :kafka-ui] (map :name (get-in u [:kafka :parts]))))
      (is (= [:postgres] (map :name (get-in u [:db :parts])))))
    (testing "given nothing measured - a gone pid, a container without stats, :external - then no
              entry: unknown, not zero"
      (is (= #{:api :kafka :db} (set (keys u)))))))

(deftest shown-test
  (testing "given raw usage, then CPU to 5%, RSS to two digits, and the worse level of the two"
    (let [u (usage/shown {:a {:cpu 292.72 :rss (* 1234 mib)}
                          :b {:cpu 1.9 :rss (* 87 mib) :parts [{:name :x :cpu 61.0 :rss (* 87 mib)}]}
                          :c {:cpu 3.0 :rss (* 1100 mib)}})]
      (is (= {:cpu 295 :rss (* 1200 mib) :level :stressed} (u :a)))
      (is (= {:cpu 0 :rss (* 87 mib) :level :calm :parts [{:name :x :cpu 60 :rss (* 87 mib)}]} (u :b)))
      (is (= :busy (get-in u [:c :level]))))
    (testing "two samples apart only by noise compare equal: nothing to push"
      (is (= (usage/shown {:a {:cpu 41.0 :rss (* 1210 mib)}}) (usage/shown {:a {:cpu 39.0 :rss (* 1190 mib)}}))))))

(deftest sample-test
  (testing "given handles, then one ps, one docker ps and one docker stats for only our containers,
            and the CPU times kept for the next sample"
    (let [calls (atom [])]
      (with-redefs [usage/out-of (fn [cmd & _]
                                   (swap! calls conj cmd)
                                   (condp = (take 2 cmd)
                                     ["ps" "-axo"] ps-out
                                     ["docker" "ps"] docker-ps-out
                                     ["docker" "stats"] docker-stats-out))]
        (let [s (usage/sample! handles nil)]
          (is (= 3 (count @calls)))
          (is (= #{"aaa" "bbb" "ddd" "ccc"} (set (drop 6 (last @calls)))))
          (is (= 720.0 (get-in s [:prev 102])))
          (is (= #{:api :kafka :db} (set (keys (:usage s)))))))))
  (testing "given only pid handles, then docker isn't asked; given docker failing, then no container usage"
    (let [calls (atom [])]
      (with-redefs [usage/out-of (fn [cmd & _] (swap! calls conj cmd) (when (= "ps" (first cmd)) ps-out))]
        (is (= #{:api} (set (keys (:usage (usage/sample! (select-keys handles [:api]) nil))))))
        (is (= 1 (count @calls)))
        (is (= #{:api} (set (keys (:usage (usage/sample! handles nil))))))))))

(deftest out-of-test
  (testing "the output under LC_ALL=C; nil on a failure; killed and nil past its timeout"
    (is (= "C\n" (usage/out-of ["sh" "-c" "echo $LC_ALL"])))
    (is (nil? (usage/out-of ["sh" "-c" "exit 3"])))
    (let [t0 (System/currentTimeMillis)]
      (is (nil? (usage/out-of ["sleep" "5"] 300)))
      (is (< (- (System/currentTimeMillis) t0) 2000)))))

(deftest pid-of-test
  (testing "given a persisted {:kind :pid}, then its pid; a compose or :external handle, nil"
    (is (= 42 (usage/pid-of {:kind :pid :pid 42})))
    (is (nil? (usage/pid-of {:kind :docker-compose :repo-dir "/w"})))
    (is (nil? (usage/pid-of {:kind :external})))))
