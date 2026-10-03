(ns zent.mcp-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [zent.daemon :as daemon]
            [zent.mcp :as mcp]))

;; never reach a real daemon that may be running on this machine
(use-fixtures :each (fn [t] (with-redefs [daemon/live (constantly nil)] (t))))

(def ^:private ctx
  {:catalog {:components {:db {:kind :docker-compose :repo "db"}
                          :api {:kind :process :cmd "run" :deps [:db]}}
             :presets {:full {:db {:mode :on} :api {:mode :on}}}}
   :name :mcp-test
   :preset-dir (str (System/getProperty "java.io.tmpdir") "/zent-mcp-test-" (System/currentTimeMillis))})

(defn- call [tool args]
  (:result (mcp/handle ctx {:id 1 :method "tools/call" :params {:name tool :arguments args}})))

(deftest stop-empty-list-test
  (testing "given `stop` with an empty list, then it's refused - not read as
            omitted, which stops everything"
    (let [stopped (atom nil)]
      (with-redefs [zent.cli/stop! (fn [names] (reset! stopped names))]
        (let [{:keys [isError content]} (call "stop" {:components []})]
          (is isError)
          (is (str/includes? (:text (first content)) "non-empty"))))
      (is (nil? @stopped) "nothing reached the daemon"))))

(deftest protocol-test
  (testing "initialize echoes the client's protocol version and offers tools"
    (let [{:keys [result jsonrpc id]} (mcp/handle ctx {:id 1 :method "initialize"
                                                       :params {:protocolVersion "2025-03-26"}})]
      (is (= ["2.0" 1 "2025-03-26"] [jsonrpc id (:protocolVersion result)]))
      (is (= {:tools {}} (:capabilities result)))))

  (testing "a notification gets no response, an unknown method an error"
    (is (nil? (mcp/handle ctx {:method "notifications/initialized"})))
    (is (= -32601 (get-in (mcp/handle ctx {:id 2 :method "nope"}) [:error :code]))))

  (testing "tools/list describes every tool with an input schema"
    (let [tools (get-in (mcp/handle ctx {:id 3 :method "tools/list"}) [:result :tools])]
      (is (every? #(= "object" (get-in % [:inputSchema :type])) tools))
      (is (= ["preset"] (get-in (first (filter #(= "apply" (:name %)) tools)) [:inputSchema :required]))))))

(deftest tools-test
  (testing "given a saved preset, preview shows what it resolves to"
    (is (false? (:isError (call "save_preset" {:name "mine" :spec "[:api]"}))))
    (let [{:keys [content isError]} (call "preview" {:preset "mine"})]
      (is (false? isError))
      (is (str/includes? (:text (first content)) "api"))))

  (testing "a refused spec is a tool error carrying the reason"
    (let [{:keys [content isError]} (call "save_preset" {:name "bad" :spec "[:nope]"})]
      (is isError)
      (is (str/includes? (:text (first content)) "unknown component"))))

  (testing "describe_catalog lists components and kind fields"
    (is (str/includes? (:text (first (:content (call "describe_catalog" {})))) ":kind-fields")))

  (testing "unknown tool"
    (is (:isError (call "nope" {}))))

  (call "delete_preset" {:name "mine"}))

(deftest argument-validation-test
  (testing "a missing or mistyped argument is named back to the agent"
    (let [{:keys [isError content]} (call "apply" {})]
      (is isError)
      (is (re-find #"argument preset: expected a non-empty string" (:text (first content)))))
    (let [{:keys [isError content]} (call "logs" {:component "x" :lines "50"})]
      (is isError)
      (is (re-find #"argument lines: expected an integer" (:text (first content)))))))
