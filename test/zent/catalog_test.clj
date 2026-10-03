(ns zent.catalog-test
  "load-dir against test/fixtures/catalog - catalog.edn plus presets/*.edn,
  which double as a demonstration of the format a catalog repo holds."
  (:require [clojure.test :refer [deftest is testing]]
            [zent.catalog :as catalog]
            [zent.cli :as cli]))

(def ^:private home (System/getenv "HOME"))
(def ^:private no-user-presets (str (System/getProperty "java.io.tmpdir") "/zent-no-presets"))

(deftest load-dir-test
  (let [c (catalog/load-dir "test/fixtures/catalog")]
    (testing "catalog.edn is read as is, ~/ strings expanded"
      (is (= :fixture (:name c)))
      (is (= (str home "/jdk") (get-in c [:defaults :jdk-home]))))
    (testing "presets/: one per file, keyed by file name, run through zent.compose/run"
      (is (= {:full {:db {:mode :on} :api {:mode :on :workspace-dir (str home "/work")}}
              :standalone-ui {:ui {:mode :on}}}
             (:presets c)))))
  (testing "a dir without a catalog.edn carrying a :name is refused"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keyword :name"
                          (catalog/load-dir "test/fixtures")))))

(deftest entry-point-registers-kinds-test
  (testing "zent.cli loads the built-in kinds itself - a catalog.edn can't require them"
    (is (re-find #"\[zent\.kinds\]" (slurp "src/zent/cli.clj")))))

(deftest check-test
  (let [ctx {:catalog #(catalog/load-dir "test/fixtures/catalog") :preset-dir no-user-presets}]
    (testing "given valid presets, check passes"
      (is (re-find #"2 preset\(s\) ok" (with-out-str (cli/check! ctx)))))
    (testing "given a preset naming an unknown component, check fails and names it"
      (let [ctx (update ctx :catalog (fn [f] #(assoc-in (f) [:presets :broken] {:nope {:mode :on}})))
            out (java.io.StringWriter.)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"1 of 3 preset\(s\) refused"
                              (binding [*out* out] (cli/check! ctx))))
        (is (re-find #"broken: .*unknown component" (str out)))))))
