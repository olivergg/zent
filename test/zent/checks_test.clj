(ns zent.checks-test
  "zent.checks against test/fixtures/catalog/checks/full/smoke.clj. The run
  itself is stubbed (zent.shell/run-logged!) with lines a real run printed;
  set ZENT_TEST_CLOJURE to also run it through the JVM `clojure` CLI."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zent.catalog :as catalog]
            [zent.checks :as checks]
            [zent.cli :as cli]
            [zent.daemon :as daemon]
            [zent.logs :as logs]
            [zent.shell :as shell]))

(def ^:private fixture "test/fixtures/catalog")

;; what the fixture's run printed (zent.checks/run-cmd), around unrelated output
(def ^:private real-run
  ["Reflection warning, ignore me"
   "#zent/check {:event :begin, :test \"passes\", :doc \"One equals one.\", :kind :invariant}"
   "#zent/check {:event :pass, :test \"passes\"}"
   "#zent/check {:event :pass, :test \"passes\"}"
   "#zent/check {:event :end, :test \"passes\"}"
   "#zent/check {:event :begin, :test \"fails\", :doc \"One equals two,\\n  on two lines.\"}"
   "#zent/check {:event :fail, :test \"fails\", :message \"[:label \\\"x\\\"]\", :expected \"(= 1 2)\", :actual \"(not (= 1 2))\", :file \"smoke.clj\", :line 9, :context \"ctx\"}"
   "#zent/check {:event :end, :test \"fails\"}"
   "#zent/check {:event :begin, :test \"throws\"}"
   "#zent/check {:event :error, :test \"throws\", :message \"Uncaught exception, not in assertion.\", :expected \"nil\", :actual \"clojure.lang.ExceptionInfo: kaput\", :file \"smoke.clj\", :line 11, :context nil}"
   "#zent/check {:event :end, :test \"throws\"}"
   "#zent/check {:event :done}"])

(defn- done [lines] (concat lines [(last real-run)]))

(defn- tmp-dir [] (str (System/getProperty "java.io.tmpdir") "/zent-checks-" (System/nanoTime)))

(deftest discover-test
  (testing "given checks/<preset>/<check>.clj, its ns attr-map gives what it observes"
    (is (= {:full [{:name :smoke :ns 'full.smoke :root (str fixture "/checks")
                    :observes [:db :api] :description "fixture check"}]}
           (checks/discover fixture))))
  (testing "given a dir without checks/, no checks"
    (is (= {} (checks/discover "test/fixtures/k8s"))))
  (testing "given :zent/writes true, the check is marked as writing - nothing else is"
    (let [dir (tmp-dir)
          write! #(doto (io/file dir "checks" "my_preset" %1) io/make-parents (spit %2))]
      (write! "scenario.clj" "(ns my_preset.scenario {:zent/observes [:db] :zent/writes true})")
      (write! "reads.clj" "(ns my_preset.reads {:zent/observes [:db] :zent/writes \"yes\"})")
      (is (= {:reads nil :scenario true}
             (into {} (map (juxt :name :writes)) (:my-preset (checks/discover dir)))))))
  (testing "given a ns not matching its path or bad :zent/observes, the check carries a :problem"
    (let [dir (tmp-dir)
          write! #(doto (io/file dir "checks" "my_preset" %1) io/make-parents (spit %2))]
      (write! "a.clj" "(ns other.ns)")
      (write! "b_c.clj" "(ns my_preset.b-c {:zent/observes [\"db\"]})")
      (write! "notes.txt" "ignored")
      (is (= [[:a "its ns is other.ns, its path wants my_preset.a"]
              [:b-c ":zent/observes is a vector of component keywords"]]
             (map (juxt :name :problem) (:my-preset (checks/discover dir))))))))

(deftest load-dir-carries-checks-test
  (is (= [:smoke] (map :name (:full (:checks (catalog/load-dir fixture)))))))

(deftest problems-test
  (let [c (catalog/load-dir fixture)]
    (testing "given checks observing their preset's components, no problem"
      (is (empty? (checks/problems c))))
    (testing "given an observed component the preset doesn't run, it is named"
      (is (= ["full/smoke: observes api, which the preset doesn't run"]
             (checks/problems (update-in c [:presets :full] dissoc :api)))))
    (testing "given checks for a preset that doesn't exist, the dir is named"
      (is (= ["checks/gone_one: no preset gone-one"]
             (checks/problems (assoc c :checks {:gone-one [{:name :x :ns 'gone_one.x}]})))))))

(deftest run-cmd-test
  (let [[_ _ _ expr :as argv] (checks/run-cmd {:ns 'full.smoke} nil)]
    (testing "a JVM clojure evaluating the ns's deftests in file order"
      (is (= ["clojure" "-M" "-e"] (take 3 argv)))
      (is (str/includes? expr "(require 'clojure.test 'full.smoke)"))
      (is (str/includes? expr "(ns-interns 'full.smoke)"))))
  (testing "given test names, only those vars"
    (is (str/includes? (last (checks/run-cmd {:ns 'full.smoke} ["fails" "throws"]))
                       "(clojure.test/test-vars [#'full.smoke/fails #'full.smoke/throws])")))
  (testing "given a test name that isn't a symbol, refused before reaching argv"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid test name"
                          (checks/run-cmd {:ns 'full.smoke} ["x) (System/exit 1"])))))

(deftest parse-test
  (testing "given a real run, tests in order with their assertions and failures"
    (let [{:keys [status tests counts]} (checks/parse real-run 0)]
      (is (= :fail status))
      (is (= [["passes" :pass 2] ["fails" :fail 1] ["throws" :error 0]]
             (map (juxt :name :status :assertions) tests)))
      (is (= ["One equals one." "One equals two,\n  on two lines." nil] (map :doc tests)) "a deftest's docstring")
      (is (= [:invariant nil nil] (map :kind tests)) "a deftest's :zent/kind")
      (is (= {:message "[:label \"x\"]" :expected "(= 1 2)" :actual "(not (= 1 2))"
              :file "smoke.clj" :line 9 :context "ctx"}
             (first (:failures (second tests)))))
      (is (= {:tests 3 :assertions 3 :failed 1 :errors 1} counts))))
  (testing "given only passes, :pass"
    (is (= :pass (:status (checks/parse (done (take 5 real-run)) 0)))))
  (testing "given passes but no :done, :unknown - the output was cut, whatever the exit"
    (is (= [:unknown true] ((juxt :status :incomplete) (checks/parse (take 5 real-run) 0)))))
  (testing "given an error and no failure, :unknown - it couldn't judge"
    (is (= :unknown (:status (checks/parse (concat (take 5 real-run) (drop 8 real-run)) 0)))))
  (testing "given a test begun but never ended, it errored: the run died in it"
    (is (= [:pass :error] (map :status (:tests (checks/parse (take 6 real-run) 1)))))
    (is (= :unknown (:status (checks/parse (take 6 real-run) 1)))))
  (testing "given a test that failed then died, the failure stands"
    (is (= [:pass :fail] (map :status (:tests (checks/parse (take 7 real-run) 1)))))
    (is (= :fail (:status (checks/parse (take 7 real-run) 1)))))
  (testing "given notes, each under its test, in order - on a passing test too"
    (is (= [["passes" ["63 plug-ins" "largest gap 1 min"]] ["fails" nil]]
           (map (juxt :name :notes)
                (:tests (checks/parse (concat (take 2 real-run)
                                              ["#zent/check {:event :note, :test \"passes\", :note \"63 plug-ins\"}"
                                               "#zent/check {:event :note, :test \"passes\", :note \"largest gap 1 min\"}"]
                                              (drop 2 (take 8 real-run)))
                                      0))))))
  (testing "given a run that printed nothing (ns didn't load), :unknown with its exit"
    (is (= {:status :unknown :exit 1 :incomplete true :tests []
            :counts {:tests 0 :assertions 0 :failed 0 :errors 0}}
           (checks/parse ["Syntax error compiling"] 1)))))

(deftest report-test
  (testing "given a failing run, a line per check and test, each failure under its test"
    (is (= ["✗ full/smoke  fail - 3 test(s), 2 not passing, 3 assertion(s), 1.2 s"
            "  ✓ passes [invariant]  2"
            "  ✗ fails  1"
            "      # One equals two,"
            "      # on two lines."
            "      ctx [:label \"x\"]  (smoke.clj:9)"
            "      expected: (= 1 2)"
            "        actual: (not (= 1 2))"
            "  ! throws  0"
            "      Uncaught exception, not in assertion.  (smoke.clj:11)"
            "      expected: nil"
            "        actual: clojure.lang.ExceptionInfo: kaput"]
           (checks/report (assoc (checks/parse real-run 0) :preset :full :check :smoke :duration-ms 1234)))))
  (testing "given a passing test's notes, they show under it, a line each"
    (is (= ["✓ full/smoke  pass - 1 test(s), 1 assertion(s), 0.0 s"
            "  ✓ passes [invariant]  1"
            "      · 63 plug-ins"
            "      · largest gap 1 min"
            "      · on two lines"]
           (checks/report {:preset :full :check :smoke :status :pass
                           :counts {:tests 1 :assertions 1 :failed 0 :errors 0}
                           :tests [{:name "passes" :status :pass :assertions 1 :kind :invariant :doc "One."
                                    :failures [] :notes ["63 plug-ins" "largest gap 1 min\non two lines"]}]}))))
  (testing "given a run that couldn't start, its stderr and the tests it was limited to"
    (is (= ["? full/smoke  unknown - 0 test(s), 0 assertion(s), 0.0 s" "  only: fails" "  | Syntax error"]
           (checks/report (assoc (checks/parse [] 1) :preset :full :check :smoke :only ["fails"]
                                 :error "Syntax error"))))))

(deftest run!-test
  (let [dir (tmp-dir)
        [chk] (:full (checks/discover fixture))
        calls (atom [])
        stub (fn [lines exit]
               (fn [cmd & {:keys [dir log-file]}]
                 (swap! calls conj [dir (str log-file)])
                 (spit log-file (str/join "\n" lines) :append true)
                 {:exit exit}))]
    (with-redefs [shell/run-logged! (stub real-run 0)]
      (let [r (binding [checks/*dir* dir] (checks/run! :full chk ["fails"]))]
        (testing "run from the checks root through a log file, the result named and kept as EDN"
          (is (= [[(str fixture "/checks") (str (logs/log-file :check-full-smoke))]] @calls))
          (is (= [:fail :full :smoke ["fails"] nil] ((juxt :status :preset :check :only :error) r)))
          (is (= r (read-string (slurp (first (filter #(.isFile %) (file-seq (io/file dir)))))))))))
    (testing "given a run cut short, :unknown, its other output kept as :error - and the log starts afresh"
      (with-redefs [shell/run-logged! (stub ["Syntax error compiling" (second real-run)] 1)]
        (let [r (binding [checks/*dir* dir] (checks/run! :full chk nil))]
          (is (= :unknown (:status r)))
          (is (= "the run ended before it finished (exit 1)\nSyntax error compiling" (:error r)))
          (is (= 2 (count (str/split-lines (slurp (logs/log-file :check-full-smoke)))))))))
    (testing "given a run that finished but exited non-zero, the error says so"
      (with-redefs [shell/run-logged! (stub (done (take 5 real-run)) 1)]
        (is (= "the run finished, then exited 1\nReflection warning, ignore me"
               (:error (binding [checks/*dir* dir] (checks/run! :full chk nil)))))))))

(deftest real-clojure-run-test
  (if-not (System/getenv "ZENT_TEST_CLOJURE")
    (println "skipped real-clojure-run-test (ZENT_TEST_CLOJURE unset)")
    (let [[chk] (:full (checks/discover fixture))
          r (binding [checks/*dir* (tmp-dir)] (checks/run! :full chk nil))]
      (is (= [["passes" :pass "One equals one." :invariant] ["fails" :fail "One equals two,\n  on two lines." nil]
              ["throws" :error nil nil]]
             (map (juxt :name :status :doc :kind) (:tests r))))
      (is (= "clojure.lang.ExceptionInfo: kaput" (-> r :tests (nth 2) :failures first :actual)))
      (is (= [["2 assertions"] nil nil] (map :notes (:tests r))) "a :zent/note reported by the test"))))

(deftest verify-test
  (let [ctx {:catalog #(catalog/load-dir fixture) :preset-dir (tmp-dir)}
        ran (atom [])
        passing (assoc (checks/parse (done (take 5 real-run)) 0) :preset :full :check :smoke)]
    (with-redefs [daemon/live (constantly nil)
                  checks/run! (fn [preset chk tests] (swap! ran conj [preset (:name chk) tests]) passing)]
      (testing "given a preset, its checks run and print; all passing, verify returns"
        (is (str/starts-with? (with-out-str (cli/verify! ctx :full nil nil)) "✓ full/smoke  pass"))
        (is (= [[:full :smoke nil]] @ran)))
      (testing "given --only=, the tests reach the run"
        (reset! ran [])
        (with-out-str (#'cli/dispatch ctx "verify" ["full" "smoke"] #{"--only=fails,throws"}))
        (is (= [[:full :smoke ["fails" "throws"]]] @ran)))
      (testing "given a check that isn't the preset's, or a preset without checks, refused by name"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no check nope - its checks: smoke"
                              (cli/verify! ctx :full ["nope"] nil)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"standalone-ui has no checks"
                              (cli/verify! ctx :standalone-ui nil nil))))
      (testing "given no preset and no daemon, the usage"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"usage: zent verify" (cli/verify! ctx nil nil nil))))
      (testing "given a check that writes, it's skipped unless named, and says so"
        (let [ctx (assoc ctx :catalog #(update-in (catalog/load-dir fixture) [:checks :full] conj
                                                   {:name :scenario :ns 'full.scenario :writes true}))]
          (reset! ran [])
          (is (str/includes? (with-out-str (cli/verify! ctx :full nil nil)) "skipped, it writes - name it to run it: scenario"))
          (is (= [[:full :smoke nil]] @ran))
          (reset! ran [])
          (with-out-str (cli/verify! ctx :full ["scenario"] nil))
          (is (= [[:full :scenario nil]] @ran)))))
    (testing "given a check not passing, verify fails after printing it"
      (with-redefs [daemon/live (constantly nil)
                    checks/run! (fn [& _] (assoc (checks/parse real-run 0) :preset :full :check :smoke))]
        (let [out (java.io.StringWriter.)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"1 of 1 check\(s\) not passing"
                                (binding [*out* out] (cli/verify! ctx :full nil nil))))
          (is (str/includes? (str out) "✗ fails")))))))

(deftest history-and-current-test
  (let [dir (tmp-dir)
        run (fn [at only & statuses]
              (cond-> {:started-at at :status :pass
                       :tests (mapv (fn [[n s]] {:name n :status s :assertions 1 :failures []}) (partition 2 statuses))}
                only (assoc :only only)))
        runs [(run 1 nil "a" :pass "b" :pass) (run 2 nil "a" :fail "b" :pass) (run 3 ["a"] "a" :pass)]]
    (doseq [r runs] (doto (io/file dir "full" "smoke" (str (:started-at r) ".edn")) io/make-parents (spit (pr-str r))))
    (spit (io/file dir "full" "smoke" "notes.txt") "ignored")
    (testing "history: the last n results, oldest first"
      (is (= [2 3] (map :started-at (binding [checks/*dir* dir] (checks/history :full :smoke 2))))))
    (testing "current: the last full run, each test overlaid by the partial runs after it"
      (let [c (checks/current runs)]
        (is (= [["a" :pass 3] ["b" :pass nil]] (map (juxt :name :status :at) (:tests c))))
        (is (= [:pass 2 [{:started-at 3 :only ["a"]}]] ((juxt :status :started-at :overlaid) c)))))
    (testing "current: nil without a full run"
      (is (nil? (checks/current [(run 3 ["a"] "a" :pass)]))))))
