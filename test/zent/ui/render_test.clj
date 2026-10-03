(ns zent.ui.render-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zent.ui.render :as render]))

(deftest component-card-status-label-test
  (testing "each status gets its label-of wording, :external reading as
            plain \"up\""
    (doseq [[status label] {:pending "pending" :deploying "starting" :up "up"
                             :done "done" :idle "idle" :failed "failed" :external "up" :off "off" :down "stopped"}]
      (is (str/includes? (render/component-card :svc {:kind :process :status status})
                          (str "<span class=\"state\">" label "</span>"))))))

(deftest component-card-button-gating-test
  (testing "reload: everything but :off/:external, disabled while :deploying"
    (is (not (str/includes? (render/component-card :svc {:kind :process :status :off}) "act reload")))
    (is (not (str/includes? (render/component-card :svc {:kind :external :status :external}) "act reload")))
    (is (str/includes? (render/component-card :svc {:kind :process :status :up}) "act reload"))
    (let [html (render/component-card :svc {:kind :process :status :deploying})]
      (is (str/includes? html "act reload"))
      (is (str/includes? html "disabled"))))

  (testing "stop: only :up/:deploying/:idle"
    (is (str/includes? (render/component-card :svc {:kind :process :status :up}) "act stop"))
    (is (str/includes? (render/component-card :svc {:kind :process :status :deploying}) "act stop"))
    (is (not (str/includes? (render/component-card :svc {:kind :process :status :off}) "act stop")))
    (is (not (str/includes? (render/component-card :job {:kind :one-shot :status :done}) "act stop")))
    (is (not (str/includes? (render/component-card :svc {:kind :external :status :external}) "act stop"))))

  (testing "action buttons post to the component's own trigger/stop routes"
    (let [html (render/component-card :svc {:kind :process :status :up})]
      (is (str/includes? html "hx-post=\"/api/trigger/svc\""))
      (is (str/includes? html "hx-post=\"/api/stop/svc\"")))))

(deftest component-card-permanent-badge-test
  (testing "a :permanent component says so - it's why a stop-everything leaves it up"
    (is (str/includes? (render/component-card :deps {:kind :process :status :up :permanent true})
                       "class=\"permanent\""))
    (is (not (str/includes? (render/component-card :svc {:kind :process :status :up}) "permanent")))))

(deftest component-card-preview-test
  (testing ":preview? drops the actions regardless of status - nothing here
            is actually deployed yet, so there's nothing to reload/stop"
    (let [html (render/component-card :svc {:kind :process :status :up :preview? true})]
      (is (not (str/includes? html "act reload")))
      (is (not (str/includes? html "act stop"))))))

(deftest component-card-error-gating-test
  (testing "the error banner shows only while actually :failed - never lingers
            on a card that's since recovered"
    (is (str/includes? (render/component-card :svc {:kind :process :status :failed :error "boom"})
                        "<span class=\"fray\">boom</span>"))
    (is (not (str/includes? (render/component-card :svc {:kind :process :status :up :error "boom"}) "fray")))))

(deftest component-card-escaping-test
  (testing "untrusted text - error, repo, service names - can't inject markup"
    (let [html (render/component-card :svc {:kind :process :status :failed
                                              :error "<script>alert(1)</script>"
                                              :repo "<b>repo</b>"})]
      (is (not (str/includes? html "<script>")))
      (is (str/includes? html "&lt;script&gt;"))
      (is (not (str/includes? html "<b>repo</b>")))
      (is (str/includes? html "&lt;b&gt;repo&lt;/b&gt;")))))

(deftest component-card-links-test
  (testing "declared links show only once actually reachable (up/external/deploying)"
    (let [cfg {:kind :process :links [{:url "http://x" :label "admin"}]}]
      (is (str/includes? (render/component-card :svc (assoc cfg :status :up)) "http://x"))
      (is (str/includes? (render/component-card :svc (assoc cfg :status :external)) "http://x"))
      (is (str/includes? (render/component-card :svc (assoc cfg :status :deploying)) "http://x"))
      (is (not (str/includes? (render/component-card :svc (assoc cfg :status :off)) "http://x")))
      (is (not (str/includes? (render/component-card :svc (assoc cfg :status :failed)) "http://x"))))))

(deftest component-card-service-rows-test
  (testing "each service gets its own health-classed row"
    (let [html (render/component-card :db {:kind :docker-compose :status :up
                                            :service-status [{:service :redis :state "running" :ports ["6379"]}
                                                              {:service :pg :state "running" :health "unhealthy"}]})]
      (is (str/includes? html "data-health=\"ok\""))
      (is (str/includes? html "data-health=\"bad\""))
      (is (str/includes? html ":6379"))))

  (testing "before the stack is up, declared names show with no health yet"
    (let [html (render/component-card :db {:kind :docker-compose :status :pending :services [:redis]})]
      (is (str/includes? html "svc-name\">redis"))
      (is (str/includes? html "data-health=\"\"")))))

(deftest component-card-source-test
  (testing "a pinned branch shows a worktree badge; the workdir path is
            home-tildified for display but the raw path stays in the title
            for copyCd"
    (let [html (render/component-card :svc {:kind :process :status :up
                                              :source {:dir "/home/me/workspace/svc"
                                                        :branch "feature/x" :worktree "/x"}})]
      (is (str/includes? html "<span class=\"branch\">feature/x</span>"))
      (is (str/includes? html "~/workspace/svc"))
      (is (str/includes? html "Copy `cd /home/me/workspace/svc`"))
      (is (str/includes? html "worktree</em>")))))

(deftest run-summary-test
  (testing "no components at all"
    (is (= "<div id=\"summary\" class=\"quiet\">Nothing running.</div>" (render/run-summary {}))))

  (testing "tally counts by label, so :up and :external share one bucket"
    (let [html (render/run-summary {:a {:status :up} :b {:status :external} :c {:status :deploying}})]
      (is (str/includes? html "<b>2</b> up"))
      (is (str/includes? html "<b>1</b> starting"))))

  (testing "a failed component blocks and is named"
    (let [html (render/run-summary {:a {:status :up} :b {:status :failed}})]
      (is (str/includes? html "Blocked by b."))))

  (testing "nothing failed, something still starting"
    (is (str/includes? (render/run-summary {:a {:status :deploying}}) "Still starting.")))

  (testing "escapes a component name in the blocked-by list"
    (is (str/includes? (render/run-summary {"<a>" {:status :failed}}) "&lt;a&gt;"))))

(deftest preset-picker-test
  (testing "no presets in the catalog"
    (is (= "<ul class=\"picker\" id=\"preset-picker\"><li class=\"quiet\">No presets in this catalog.</li></ul>"
           (render/preset-picker [] nil))))

  (testing "sorted, active preset gets a stop button and the data-active flag"
    (let [html (render/preset-picker [:one :all] :all)]
      (is (< (str/index-of html "all") (str/index-of html "one")) "sorted")
      (is (str/includes? html "data-active=\"true\""))
      (is (str/includes? html "hx-post=\"/api/apply/all\""))
      (is (str/includes? html "hx-post=\"/api/stop-all\""))))

  (testing "an inactive preset has no stop button of its own"
    (let [html (render/preset-picker [:one] :two)]
      (is (not (str/includes? html "stop-all"))))))

(deftest detail-panel-test
  (testing "nothing selected / component unknown"
    (is (str/includes? (render/detail-panel :ghost nil) "Select a component")))

  (testing "facts, deps joined, handle and error shown only when present/failed"
    (let [html (render/detail-panel :svc {:kind :process :status :failed :deps [:a :b]
                                          :handle-summary "Process" :error "boom"})]
      (is (str/includes? html "<dt>kind</dt><dd>process</dd>"))
      (is (str/includes? html "<dt>waits for</dt><dd>a, b</dd>"))
      (is (str/includes? html "<dt>handle</dt><dd>Process</dd>"))
      (is (str/includes? html "<dt>error</dt><dd>boom</dd>"))))

  (testing "no deps reads as \"nothing\", no error shown once recovered"
    (let [html (render/detail-panel :svc {:kind :process :status :up :error "stale"})]
      (is (str/includes? html "<dt>waits for</dt><dd>nothing</dd>"))
      (is (not (str/includes? html "error"))))))

(deftest attached-one-shot-card-test
  (testing "given an :attached-to one-shot, then its card is a one-line strip:
            name, state and actions, no repo/meta block"
    (let [html (render/component-card :seed {:kind :one-shot :status :done :repo "r" :attached-to :broker
                                              :scripts ["./SeedA.java" "bin/seed-b.sh some/arg.tsv"] :description "seeds things"})]
      (is (str/includes? html "SeedA → seed-b") "the scripts it runs, not its name")
      (is (str/includes? html "title=\"seed\""))
      (is (str/includes? html "seeds things"))
      (is (str/includes? html "done"))
      (is (str/includes? html "act reload"))
      (is (not (str/includes? html "class=\"meta\"")))
      (is (not (str/includes? html "class=\"repo\""))))))

(deftest one-shot-labels-test
  (testing "a one-shot reads as a job: not run / queued / running, never stopped"
    (doseq [[status label] {:off "not run" :down "not run" :pending "queued" :deploying "running" :done "done"}]
      (is (str/includes? (render/component-card :job {:kind :one-shot :status status})
                         (str "<span class=\"state\">" label "</span>"))
          (str status)))
    (is (str/includes? (render/component-card :svc {:kind :process :status :down}) ">stopped<"))))

(deftest stopping-card-test
  (testing "a stopping card says so and offers neither reload nor stop"
    (let [html (render/component-card :svc {:kind :process :status :stopping})]
      (is (str/includes? html ">stopping<"))
      (is (not (str/includes? html "act reload")))
      (is (not (str/includes? html "act stop"))))))
