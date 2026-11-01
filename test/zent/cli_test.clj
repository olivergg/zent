(ns zent.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zent.cli :as cli]))

(def ^:private ctx
  {:catalog {:components {:api {:kind :process :cmd "run"}}
             :presets {:full {:api {:mode :on}}}}
   :name :cli-test
   :preset-dir (str (System/getProperty "java.io.tmpdir") "/zent-cli-test-" (System/currentTimeMillis))})

(defn- run [& args] (with-out-str (cli/main ctx args)))

(deftest prune-refusal-test
  (let [handles [{:kind :pid :component :api}]]
    (testing "given a preset that doesn't resolve, then refused, naming it, daemon or not"
      (is (re-find #"broken doesn't resolve" (cli/prune-refusal #{:broken} [] {:port 1}))))
    (testing "given session entries and no daemon, then refused, naming them"
      (is (re-find #"lists api" (cli/prune-refusal #{} handles nil))))
    (testing "given a daemon to ask, or nothing in the session, then no refusal"
      (is (nil? (cli/prune-refusal #{} handles {:port 1})))
      (is (nil? (cli/prune-refusal #{} [] nil))))))

(deftest running-worktrees-test
  (testing "given a daemon's view, then the worktrees of what isn't stopped, a :source-env's included"
    (is (= {"/wt/api" #{(keyword "api (running)")}
            "/wt/lib" #{(keyword "api (running)") (keyword "seed (running)")}}
           (cli/running-worktrees
            {:api {:status "up" :source {:worktree "/wt/api"} :source-env {:LIB {:worktree "/wt/lib"}}}
             :seed {:status "deploying" :source-env {:LIB {:worktree "/wt/lib"}}}
             :old {:status "down" :source {:worktree "/wt/old"}}
             :off {:status "off" :source {:worktree "/wt/off"}}
             :clone {:status "up" :source {:dir "/w/clone"}}})))))

(deftest help-test
  (testing "given no verb, `help`, `-h` or `--help`, then every verb is listed with the catalog's presets"
    (doseq [args [[] ["help"] ["-h"] ["--help"]]]
      (let [out (apply run args)]
        (is (every? #(str/includes? out (str "  " (:verb %))) cli/verbs) (pr-str args))
        (is (str/includes? out "Presets: full")))))

  (testing "given a verb, then `help <verb>` and `<verb> --help` both show its usage and details"
    (is (= (run "help" "reload") (run "reload" "--help")))
    (is (str/starts-with? (run "help" "reload") "usage: zent reload <component>\n"))
    (is (str/includes? (run "help" "reload") "see also: logs, reload-code")))

  (testing "given color, then styles are added and the columns stay aligned on the plain text"
    (let [colored (cli/help-text nil ["full"] :color? true)]
      (is (str/includes? colored "\u001b[36mreload\u001b[0m \u001b[2m<component>\u001b[0m"))
      (is (= (cli/help-text nil ["full"]) (str/replace colored #"\u001b\[[0-9;]*m" "")))))

  (testing "given a preset or an unknown name, then help says what it is"
    (is (= "zent full is `zent apply full`"
           (try (cli/help-text "full" ["full"]) (catch Exception e (ex-message e)))))
    (is (str/includes? (try (cli/help-text "nope" ["full"]) (catch Exception e (ex-message e)))
                       "`zent help` lists them"))))

(deftest usage-errors-test
  (testing "given a verb missing its operand, then the error is its usage from `verbs`"
    (is (= "usage: zent reload <component> - zent help reload"
           (try (#'cli/dispatch ctx "reload" [] #{}) (catch Exception e (ex-message e)))))
    (is (= "usage: zent preset save <name> <edn> | rm <name> - zent help preset"
           (try (#'cli/dispatch ctx "preset" ["save"] #{}) (catch Exception e (ex-message e)))))))
