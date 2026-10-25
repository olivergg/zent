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
