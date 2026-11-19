(ns zent.ui.render-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zent.ui.render :as render]))

(deftest component-card-source-env-test
  (testing "given a strip reading other checkouts, then each dir shows with its branch
            and its var as the tooltip - what it reads, before it runs"
    (let [html (render/component-card :seed {:kind :one-shot :status :on-demand :attached-to :db
                                             :source-env {"SCHEMA" {:dir "/w/api/db" :branch "feature/x"}}})]
      (is (str/includes? html "title=\"SCHEMA\""))
      (is (str/includes? html "<span class=\"branch\" title=\"feature/x\">feature/x</span>"))
      (is (str/includes? html "data-dir=\"/w/api/db\"")))))

(deftest component-card-repo-link-test
  (testing "given a repo on github.com, then its link is GitHub's mark; elsewhere, an arrow"
    (let [card #(render/component-card :svc {:kind :process :status :up :repo "svc" :repo-url %})]
      (is (str/includes? (card "https://github.com/acme/svc") "<svg class=\"gh\""))
      (is (str/includes? (card "https://gitlab.example.com/acme/svc") "&#8599;</a>"))
      (is (not (str/includes? (card "https://gitlab.example.com/acme/svc") "class=\"gh\""))))))

(deftest component-card-status-label-test
  (testing "each status gets its label-of wording, :external reading as
            plain \"up\""
    (doseq [[status label] {:waiting "waiting" :deploying "starting" :up "up"
                             :done "done" :warm "warm" :failed "failed" :external "up" :off "off" :stopped "stopped"
                             :unready "unready"}]
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

  (testing "given a one-shot, then the button runs it: \"Run\" while :on-demand (
            never run), \"Run again\" once :done - a service keeps \"Reload\""
    (is (str/includes? (render/component-card :seed {:kind :one-shot :status :on-demand}) "title=\"Run\""))
    (is (str/includes? (render/component-card :seed {:kind :one-shot :status :on-demand})
                       "<span class=\"state\">on demand</span>"))
    (is (str/includes? (render/component-card :seed {:kind :one-shot :status :done}) "title=\"Run again\""))
    (is (str/includes? (render/component-card :svc {:kind :process :status :up}) "title=\"Reload\"")))

  (testing "given a :confirm, then the button asks it before posting; without, it doesn't ask"
    (is (str/includes? (render/component-card :seed {:kind :one-shot :status :on-demand :confirm "Wipe the DB?"})
                       "hx-confirm=\"Wipe the DB?\""))
    (is (not (str/includes? (render/component-card :seed {:kind :one-shot :status :on-demand}) "hx-confirm"))))

  (testing "stop: only :up/:deploying/:warm/:unready (still running)"
    (is (str/includes? (render/component-card :svc {:kind :process :status :up}) "act stop"))
    (is (str/includes? (render/component-card :svc {:kind :process :status :unready}) "act stop"))
    (is (str/includes? (render/component-card :svc {:kind :process :status :unready}) "act reload"))
    (is (str/includes? (render/component-card :svc {:kind :process :status :deploying}) "act stop"))
    (is (not (str/includes? (render/component-card :svc {:kind :process :status :off}) "act stop")))
    (is (not (str/includes? (render/component-card :job {:kind :one-shot :status :done}) "act stop")))
    (is (not (str/includes? (render/component-card :svc {:kind :external :status :external}) "act stop"))))

  (testing "action buttons post to the component's own trigger/stop routes"
    (let [html (render/component-card :svc {:kind :process :status :up})]
      (is (str/includes? html "hx-post=\"/api/reload/svc\""))
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
  (testing "the error banner shows only while actually :failed or :unready - never
            lingers on a card that's since recovered"
    (is (str/includes? (render/component-card :svc {:kind :process :status :failed :error "boom"})
                        "<span class=\"fray\">boom</span>"))
    (is (str/includes? (render/component-card :svc {:kind :process :status :unready :error "not ready"})
                        "<span class=\"fray\">not ready</span>"))
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
      (is (not (str/includes? (render/component-card :svc (assoc cfg :status :failed)) "http://x")))
      (is (str/includes? (render/component-card :svc (assoc cfg :status :off)) "<span class=\"endpoint idle\" title=\"Not served yet: opens once it is up\">admin</span>")
          "listed all the same, not clickable"))))

(deftest tips-test
  (testing "every explanation has a heading line then a body"
    (doseq [[k t] render/tips]
      (is (re-matches #"[^\n]+\n[^\n]+" t) k))))

(deftest component-card-shots-test
  (let [shots [[:seed {:kind :one-shot :status :done :description "Load the data"}]
               [:purge {:kind :one-shot :status :on-demand :confirm "Delete rows?"}]
               [:replay {:kind :one-shot :status :deploying}]]
        card #(render/component-card :db (merge {:kind :process :status :up :shots shots} %))]
    (testing "given a host's one-shots, then one button each: its state, what it does on hover, a run that asks first"
      (let [html (card {})]
        (is (str/includes? html "data-shot=\"seed\" data-state=\"done\" title=\"seed - done\nLoad the data\""))
        (is (str/includes? html "data-shot=\"purge\" data-state=\"on-demand\""))
        (is (str/includes? html "title=\"Run\" hx-post=\"/api/reload/purge\" hx-swap=\"none\" hx-disabled-elt=\"this\" hx-confirm=\"Delete rows?\""))
        (is (str/includes? html "title=\"Run again\" hx-post=\"/api/reload/seed\""))
        (is (not (str/includes? html "/api/reload/replay")) "running: nothing to run")))
    (testing "given an :on-demand one never run, then it says so whatever its status - an apply won't start it"
      (is (str/includes? (card {:shots [[:purge {:kind :one-shot :status :waiting :on-demand true}]]}) "data-state=\"on-demand\""))
      (is (str/includes? (card {:shots [[:seed {:kind :one-shot :status :waiting}]]}) "data-state=\"idle\"")))
    (testing "in a preview, nothing runs"
      (is (not (str/includes? (card {:preview? true}) "/api/reload/"))))))

(deftest component-card-plate-test
  (testing "the ear says the kind's family - compose-services is compose too - and its port is a socket, lit once up"
    (let [card #(render/component-card :svc (merge {:kind :quarkus-app :status :up :port 9091} %))]
      (is (str/includes? (card {}) "<span class=\"ear\" data-family=\"quarkus\""))
      (is (str/includes? (card {:kind :compose-services :services [:pg :redis]}) "data-family=\"compose\""))
      (is (str/includes? (card {:kind :compose-services :services [:pg :redis]}) "<span class=\"kind\">compose · pg, redis</span>"))
      (is (str/includes? (card {:kind :compose-services :services [:a :b :c :d]}) "<span class=\"kind\">compose · 4 services</span>"))
      (is (str/includes? (card {}) "<span class=\"socket live\" style=\"--i:0\" title=\"localhost:9091\nSomething listens here\"><b>9091</b>"))
      (is (str/includes? (card {:status :deploying}) "<span class=\"socket\" style=\"--i:0\" title=\"localhost:9091\nNothing listens yet"))
      (testing "given the ports this machine listens on, then a socket follows them, not the component's state"
        (is (str/includes? (card {:status :deploying :listening #{"9091"}}) "<span class=\"socket live\""))
        (is (str/includes? (card {:listening #{"80"}}) "<span class=\"socket\" style"))))))

(deftest component-card-service-rows-test
  (testing "each service gets its own health-classed row"
    (let [html (render/component-card :db {:kind :docker-compose :status :up
                                            :service-status [{:service :redis :state "running" :ports ["6379"]}
                                                              {:service :pg :state "running" :health "unhealthy"}]})]
      (is (str/includes? html "data-health=\"ok\""))
      (is (str/includes? html "data-health=\"bad\""))
      (is (str/includes? html "<span class=\"socket live\" style=\"--i:0\" title=\"localhost:6379\nSomething listens here\"><b>6379</b>"))))

  (testing "before the stack is up, declared names show with no health yet"
    (let [html (render/component-card :db {:kind :docker-compose :status :waiting :services [:redis]})]
      (is (str/includes? html "svc-name\">redis"))
      (is (str/includes? html "data-health=\"\"")))))

(deftest component-card-source-test
  (testing "a pinned branch is marked as a worktree; the dir, home-tildified, and the
            `cd` a click copies are on hover"
    (let [dir (str (System/getenv "HOME") "/workspace/svc")
          html (render/component-card :svc {:kind :process :status :up
                                              :source {:dir dir
                                                        :branch "feature/x" :worktree "/x"}})]
      (is (str/includes? html "<span class=\"branch wt\" title=\"feature/x"))
      (is (str/includes? html "~/workspace/svc"))
      (is (str/includes? html (str "Copy `cd " dir "`")))))

  (testing "main/master go without saying"
    (is (not (str/includes? (render/component-card :svc {:kind :process :status :up :source {:dir "/w/svc" :branch "main"}})
                            "class=\"branch")))))

(deftest component-card-clock-test
  (testing "moving: a clock index.js ticks from :since; settled: how long the last start took"
    (is (str/includes? (render/component-card :svc {:kind :process :status :deploying :since 1234}) "data-since=\"1234\""))
    (is (str/includes? (render/component-card :svc {:kind :process :status :up :since 1234 :took 65000}) ">1m05s</span>"))
    (is (not (str/includes? (render/component-card :svc {:kind :process :status :up :since 1234}) "clock"))))
  (testing "durations"
    (is (= ["850ms" "42s" "3m05s" "1h02m"] (map render/duration [850 42000 185000 3720000])))))

(deftest run-summary-test
  (testing "no components at all"
    (is (= "<div id=\"summary\" class=\"quiet\">Nothing running.</div>" (render/run-summary {}))))

  (testing "tally counts by label, so :up and :external share one bucket"
    (let [html (render/run-summary {:a {:status :up} :b {:status :external} :c {:status :deploying}})]
      (is (str/includes? html "<b>2</b> up"))
      (is (str/includes? html "<b>1</b> starting"))))

  (testing "a one-shot counts under its own label"
    (let [html (render/run-summary {:a {:kind :one-shot :status :deploying} :b {:kind :one-shot :status :on-demand}})]
      (is (str/includes? html "<span class=\"t-running\"><b>1</b> running</span>"))
      (is (str/includes? html "<span class=\"t-on-demand\"><b>1</b> on demand</span>"))
      (is (str/includes? html "Still starting."))))

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

(deftest runs-from-test
  (testing "given :origin-worktree, then origin's branch labelled, its commit, the fetch's age, and what moves it"
    (let [html (render/runs-from {:from :origin-worktree :branch "feature/x" :sha "abcdef1234" :fetched-at 0} "/wt" 120000)]
      (is (str/includes? html "<span class=\"bn\" data-kind=\"remote\" title=\"origin/feature/x\"><i class=\"ico\" data-i=\"remote\"></i>origin/x</span>"))
      (is (str/includes? html "<code class=\"sha\">abcdef1</code> <span class=\"runs-meta fetched\">fetched 2 min ago</span>"))
      (is (str/includes? html "Push to run a change"))))
  (testing "given :local-worktree, then your branch labelled, in zent's worktree"
    (is (re-find #"data-kind=\"local\".*zent's worktree, on your local branch.*Commit to run a change"
                 (render/runs-from {:from :local-worktree :branch "feature/x" :sha "abcdef1234"} "/wt" 0))))
  (testing "given the main worktree, then its dir and its branch labelled, and that zent never touches it"
    (let [html (render/detail-panel :svc {:kind :process :status :up :git {:sha "abcdef1234" :branch "main"}
                                          :source {:dir "/w/svc"} :now 0})]
      (is (str/includes? html "<span>Runs</span><span><span class=\"runs\"><span class=\"runs-what\"><span class=\"bn\" data-kind=\"folder\""))
      (is (str/includes? html "data-kind=\"local\" title=\"main\"><i class=\"ico\" data-i=\"local\"></i>main</span>"))
      (is (str/includes? html "zent never touches it")))))

(deftest detail-panel-test
  (testing "nothing selected / component unknown"
    (is (str/includes? (render/detail-panel :ghost nil) "Select a component")))

  (testing "its kind and state, the error while failed, what it waits for, what to do about it"
    (let [html (render/detail-panel :svc {:kind :process :status :failed :deps [:a :b]
                                          :handle-summary "Process" :error "boom"})]
      (is (str/includes? html "<span class=\"kind\">process</span><span class=\"led\" data-state=\"failed\">"))
      (is (str/includes? html "<div class=\"kind bad\">boom</div>"))
      (is (str/includes? html "<div class=\"row\"><span>Waits for</span><span>a, b</span></div>"))
      (is (str/includes? html "class=\"btn primary\" hx-post=\"/api/reload/svc\"") "failed: reload is the thing to do")
      (is (not (str/includes? html "/api/stop/")) "nothing to stop")))

  (testing "no deps reads as \"nothing\", no error shown once recovered"
    (let [html (render/detail-panel :svc {:kind :process :status :up :error "stale"})]
      (is (str/includes? html "<span>Waits for</span><span>nothing</span>"))
      (is (str/includes? html "<div class=\"kind\">up</div>"))
      (is (not (str/includes? html "stale")))))

  (testing "given its ports, what waits for it and the checks judging it, then a section each"
    (let [html (render/detail-panel :svc {:kind :quarkus-app :status :up :port 9091 :waited-by [:replay]
                                          :checks [{:name :events :status :fail} {:name :chain :status nil}]})]
      (is (str/includes? html "<h3>Ports</h3><div class=\"rows\"><div class=\"row\"><span>localhost:9091</span><span class=\"ok\">Listening</span>"))
      (is (str/includes? html "<span>Waited for by</span><span>replay</span>"))
      (is (str/includes? html "<span>events</span><span class=\"fail\">Failed</span>"))
      (is (str/includes? html "<span>chain</span><span class=\"unknown\">Can't tell</span>"))
      (is (str/includes? html "class=\"btn danger\" hx-post=\"/api/stop/svc\""))))

  (testing "selected in a preview: nothing is deployed, so nothing to reload or stop"
    (let [html (render/detail-panel :svc {:kind :process :status :waiting :preview? true})]
      (is (not (str/includes? html "hx-post")))
      (is (str/includes? html "Show log")))))

(deftest attached-one-shot-card-test
  (testing "given a one-shot drawn on its own, then its card is a capsule: name, state and actions,
            what it runs on hover, no repo/meta block"
    (let [html (render/component-card :seed {:kind :one-shot :status :done :repo "r" :attached-to :broker
                                              :scripts ["./SeedA.java" "bin/seed-b.sh some/arg.tsv"] :description "seeds things"})]
      (is (str/includes? html "title=\"seeds things\nruns SeedA → seed-b\""))
      (is (str/includes? html "<span class=\"capsule\" data-state=\"done\""))
      (is (str/includes? html "<span class=\"name\">seed</span>"))
      (is (str/includes? html "seeds things"))
      (is (str/includes? html "done"))
      (is (str/includes? html "act reload"))
      (is (not (str/includes? html "class=\"meta\"")))
      (is (not (str/includes? html "class=\"repo\""))))))

(deftest capsule-wording-test
  (testing "until it runs, a capsule says what it is to the stack: where everything leads, or on demand"
    (let [state #(second (re-find #"class=\"state\">([^<]*)" (render/component-card :job (merge {:kind :one-shot :status :waiting} %))))]
      (is (= "the point of it" (state {:terminus true :on-demand true})))
      (is (= "on demand" (state {:on-demand true})))
      (is (= "waiting" (state {})))
      (is (= "running" (state {:terminus true :status :deploying})) "once it runs, its state"))))

(deftest link-follows-its-port-test
  (let [card #(render/component-card :svc (merge {:kind :process :status :up :links [{:url "http://localhost:8081/x" :label "admin"}]} %))]
    (testing "given the ports listened on, then a link opens once its own port listens - whatever the component's state"
      (is (str/includes? (card {:listening #{"8081"} :status :deploying}) "href=\"http://localhost:8081/x\""))
      (is (str/includes? (card {:listening #{"9"}}) "<span class=\"endpoint idle\"")))
    (testing "ports unknown: the component's state decides"
      (is (str/includes? (card {}) "href=\"http://localhost:8081/x\"")))))

(deftest one-shot-labels-test
  (testing "a one-shot reads as a job: not run / waiting / running, never stopped"
    (doseq [[status label] {:off "not run" :stopped "not run" :waiting "waiting" :deploying "running" :done "done"}]
      (is (str/includes? (render/component-card :job {:kind :one-shot :status status})
                         (str "<span class=\"state\">" label "</span>"))
          (str status)))
    (is (str/includes? (render/component-card :svc {:kind :process :status :stopped}) ">stopped<"))))

(deftest stopping-card-test
  (testing "a stopping card says so and offers neither reload nor stop"
    (let [html (render/component-card :svc {:kind :process :status :stopping})]
      (is (str/includes? html ">stopping<"))
      (is (not (str/includes? html "act reload")))
      (is (not (str/includes? html "act stop"))))))

(deftest git-chip-test
  (let [git {:sha "abcdef1234" :branch "main" :base "main" :behind 0 :ahead 0 :dirty 0 :pushed true}
        chip #(render/git-chip (merge {:status :up :source {:dir "/w/svc"}} %))]
    (testing "not read yet: no chip"
      (is (nil? (chip {}))))
    (testing "no :branch and the default one on disk: nothing to flag"
      (is (= [true false false false false] ((juxt :default? :off? :wt? :lose? :stale?) (chip {:git git})))))
    (testing "given no :branch, the main worktree runs as it is: a feature branch there isn't off"
      (is (= [false false] ((juxt :default? :off?) (chip {:git (assoc git :branch "feature/x")})))))
    (testing "given a :branch, then off is any other branch - the default one included"
      (is (:off? (chip {:git git :wanted-branch "feature/x"})))
      (is (not (:off? (chip {:git (assoc git :branch "feature/x") :wanted-branch "feature/x"})))))
    (testing "commits on no branch are at risk only in a worktree: its next sync would reset it"
      (is (not (:lose? (chip {:git (assoc git :ahead 2)}))))
      (is (not (:lose? (chip {:git (assoc git :ahead 2) :source {:dir "/wt" :worktree "/wt"}}))) "pushed or on a branch")
      (is (:lose? (chip {:git (assoc git :ahead 2 :unreachable 1) :source {:dir "/wt" :worktree "/wt"}}))))
    (testing "stale: running, deployed from another commit than the disk's"
      (is (:stale? (chip {:git git :deployed-sha "0000000"})))
      (is (not (:stale? (chip {:git git :deployed-sha "abcdef1234"}))))
      (is (not (:stale? (chip {:git git :deployed-sha "0000000" :status :down})))))))

(deftest short-branch-test
  (is (= "jira15165_defaut_tech" (render/short-branch "feature/jira15165_defaut_tech")))
  (is (= "jira14606_ic\u2026rame_reco" (render/short-branch "feature/jira14606_ican_trame_reco")))
  (is (= "kafka2" (render/short-branch "kafka2"))))

(deftest component-card-git-chip-test
  (let [card #(render/component-card :svc (merge {:kind :process :status :up :from :origin-worktree
                                                  :source {:dir "/w/svc" :worktree "/w/svc"}} %))
        git {:sha "abcdef1234" :base "master" :behind 3 :ahead 1 :unreachable 1 :dirty 2 :pushed false :from :origin-worktree}]
    (testing "given a last fetch, then its age comes from the render's :now: same input, same card"
      (let [args {:git (assoc git :fetched-at 1000) :wanted-branch "feature/y" :now 61000}]
        (is (str/includes? (card args) "(as of the last fetch, 1m00s ago)"))
        (is (= (card args) (card args)))))
    (testing "given every flag up in a :origin-worktree worktree, then each count says what it is, what blocks the next sync
              in red, and a test rebase a click away"
      (let [html (card {:git git :wanted-branch "feature/y" :deployed-sha "0000000"})]
        (is (str/includes? html ">y</span>") "its window shows the bare branch")
        (is (str/includes? html "<span class=\"branch wt\" title=\"feature/y&#10;the preset's :branch, :from :origin-worktree: what's pushed, in a worktree\""))
        (is (str/includes? html "<span class=\"from origin-worktree\" title=\"runs origin/feature/y in its own worktree"))
        (is (str/includes? html "&#10227; origin worktree</span>"))
        (is (str/includes? html "(never fetched)\">&#8595;3 master</span>"))
        (is (not (str/includes? html "act git-act")) "uncommitted files: no rebase")
        (is (str/includes? html "&#9888;1 on no branch</span>"))
        (is (str/includes? html "<span class=\"dirty lose\" title=\"2 modified or untracked file(s) - the next sync refuses until you commit or stash them\">&#9679;2 uncommitted</span>"))
        (is (str/includes? html "reload to run it\">&#8800; reload</span>"))
        (testing "and the facts on hover: what runs, what a sync and a reload do"
          (is (str/includes? html "An apply that redeploys it or a sync fetches, then resets it to origin/feature/y - refused while it holds uncommitted files or commits on no branch; a reload keeps its commit"))
          (is (str/includes? html "the next sync refuses until you keep them (git branch rescue/feature/y HEAD)"))
          (is (str/includes? html "running 0000000, the disk is at abcdef1: reload to run it")))))
    (testing "given a clean :origin-worktree worktree behind, then a trial rebase a click away"
      (let [html (card {:git (assoc git :dirty 0 :unreachable 0) :wanted-branch "feature/y"})]
        (is (str/includes? html "trial rebase onto master</button>"))
        (is (str/includes? html "A trial rebase: no branch moves, the next sync reverts it.") "its confirmation")))
    (testing "given a trial rebase, then its badge and a way to drop it, no unpushed count"
      (let [html (card {:git (assoc git :trial? true :behind 0 :unreachable 0 :dirty 0 :ahead 2) :wanted-branch "feature/y"})]
        (is (str/includes? html "&#10548; master (trial)</span>"))
        (is (str/includes? html "a trial rebase, no branch moved - the next sync reverts it"))
        (is (str/includes? html ">drop trial</button>"))
        (is (not (str/includes? html "unpushed")))))
    (testing "given :from :local-worktree, then its badge, what syncs it, its branch against origin's, and a rebase of it"
      (let [html (card {:from :local-worktree :wanted-branch "feature/y"
                        :git {:sha "abc" :base "master" :behind 2 :ahead 3 :from :local-worktree :unsynced 1 :local-ahead 3 :local-behind 2}})]
        (is (str/includes? html "<span class=\"from local-worktree moved\" title=\"runs your local feature/y in its own worktree, detached - commit to it to run a change, no push needed. An apply that redeploys it or a sync resets it to your feature/y"))
        (is (str/includes? html "&#9095; local worktree +1</span>"))
        (is (str/includes? html "hx-post=\"/api/sync/svc\""))
        (is (str/includes? html "&#8593;3 unpushed on y</span>"))
        (is (str/includes? html "&#8595;2 on origin</span>"))
        (is (str/includes? html "pull --rebase in your main worktree"))
        (is (str/includes? html "hx-confirm=\"Rebase your local branch feature/y onto origin/master? It moves feature/y (refused while another checkout has it out). It is on origin: push --force-with-lease afterwards.\""))
        (is (str/includes? html ">rebase</button>")))))
  (testing "given the main worktree on a feature branch, no :branch: it runs as it is, nothing red"
    (let [html (render/component-card :svc {:kind :process :status :up :source {:dir "/w/svc"}
                                            :git {:sha "abc" :branch "feature/z" :base "main" :ahead 4 :pushed true}})]
      (is (str/includes? html "<span class=\"branch\" title=\"feature/z&#10;no :branch in the preset"))
      (is (str/includes? html "<span class=\"from main-worktree\" title=\"runs your main worktree as it is, uncommitted changes included - nothing syncs it, a reload restarts what's on disk\">&#9998; main worktree</span>"))
      (is (str/includes? html "safe here: your main worktree runs as it is\">&#8593;4 unpushed</span>"))))
  (testing "given a clean main worktree behind its default branch, then a rebase button, warning when the branch is on origin"
    (let [card #(render/component-card :svc {:kind :process :status :up :source {:dir "/w/svc"}
                                             :git (merge {:sha "abc" :branch "feature/z" :base "main" :behind 2 :pushed true} %)})]
      (is (str/includes? (card {}) "hx-post=\"/api/rebase/svc\""))
      (is (str/includes? (card {}) "hx-confirm=\"Rebase feature/z onto origin/main? It is on origin: push --force-with-lease afterwards.\""))
      (is (str/includes? (card {:dirty 1}) "Its 1 uncommitted file(s) are stashed, then put back.") "a main worktree's: autostashed")
      (is (not (str/includes? (card {:dirty 1 :from :local-worktree}) "act git-act")) "a worktree's uncommitted files: no button")
      (is (str/includes? (card {:branch "main"}) "hx-confirm=\"Rebase main onto origin/main?\"") "on main, nothing of its own: a fast-forward")
      (is (str/includes? (card {:branch "main" :ahead 1}) "push --force-with-lease afterwards") "on main with commits of its own")))
  (testing "given origin moved past a worktree's commit, then +N and a sync button"
    (let [html (render/component-card :svc {:kind :process :status :up :wanted-branch "feature/w" :from :origin-worktree
                                            :source {:dir "/wt/svc" :worktree "/wt/svc"}
                                            :git {:sha "abc" :base "main" :unsynced 3}})]
      (is (str/includes? html "&#10227; origin worktree +3</span>"))
      (is (str/includes? html "hx-post=\"/api/sync/svc\""))
      (is (str/includes? html "origin has 3 more commit(s)"))))
  (testing "given :from :main-worktree and the main worktree on another branch, then red, saying the deploy refuses, and a
            checkout a click away - unless something is uncommitted, or it runs from a worktree"
    (let [card #(render/component-card :svc (merge {:kind :process :status :up :wanted-branch "feature/w" :from :main-worktree
                                                    :source {:dir "/w/svc"} :git {:sha "abc" :branch "main" :base "main"}} %))
          html (card {})]
      (is (str/includes? html "class=\"branch off\""))
      (is (str/includes? html "the preset's :from :main-worktree wants feature/w checked out here"))
      (is (str/includes? html "&#9998; main worktree</span>"))
      (is (str/includes? html "hx-post=\"/api/checkout/svc\""))
      (is (str/includes? html "hx-confirm=\"Check out feature/w in your main worktree, now on main? Every component running this main worktree moves with it.\""))
      (is (str/includes? (card {:preview-of "mine"}) "hx-post=\"/api/checkout/svc?preset=mine\""))
      (is (not (str/includes? (card {:git {:sha "abc" :branch "main" :base "main" :dirty 1}}) "/api/checkout/")))
      (is (not (str/includes? (card {:from :origin-worktree :source {:dir "/wt/svc" :worktree "/wt/svc"}}) "/api/checkout/")))))
  (testing "in a preview: the rebase names its preset, a :branch never deployed shows its worktree to come, and
            an existing one what the apply would do to it"
    (is (str/includes? (render/component-card :svc {:kind :process :status :off :preview? true :preview-of "mine"
                                                    :source {:dir "/w/svc"}
                                                    :git {:sha "abc" :branch "feature/z" :base "main" :behind 2}})
                       "hx-post=\"/api/rebase/svc?preset=mine\""))
    (is (str/includes? (render/component-card :svc {:kind :process :status :off :preview? true :wanted-branch "feature/new"
                                                    :from :origin-worktree :source {:dir "/wt/svc" :worktree "/wt/svc"}})
                       "<span class=\"from origin-worktree uncreated\""))
    (is (str/includes? (render/component-card :svc {:kind :process :status :off :preview? true :wanted-branch "feature/new"
                                                    :from :local-worktree :source {:dir "/wt/svc" :worktree "/wt/svc"}})
                       "&#9888; created at apply from feature/new</span>"))
    (let [pv #(render/component-card :svc {:kind :process :status :off :preview? true :preview-of "mine" :wanted-branch "feature/w"
                                           :from :origin-worktree :source {:dir "/wt/svc" :worktree "/wt/svc"}
                                           :git (merge {:sha "abc" :base "main" :from :origin-worktree} %)})]
      (is (str/includes? (pv {:unsynced 2}) "apply: reset +2</span>"))
      (is (not (str/includes? (pv {:unsynced 2}) "/api/sync/")) "no sync from a preview")
      (is (str/includes? (pv {}) "apply: keeps</span>"))
      (is (str/includes? (pv {:unsynced 2 :dirty 1}) "&#9995; sync refused: 1 at risk</span>"))))
  (testing "on the default branch, clean and up to date: no row, the facts still on `cd`'s hover"
    (let [html (render/component-card :svc {:kind :process :status :up :source {:dir "/w/svc"}
                                            :git {:sha "abc" :branch "main" :base "main" :behind 0 :ahead 0 :dirty 0}})]
      (is (not (str/includes? html "class=\"git")))
      (is (not (str/includes? html "class=\"branch")))
      (is (str/includes? html "never fetched&#10;abc&#10;/w/svc&#10;Copy `cd /w/svc`")))))

(deftest run-summary-git-test
  (let [git {:sha "a" :branch "main" :base "main" :behind 0 :ahead 0 :dirty 0}
        c (fn [g & {:as more}] (merge {:status :up :source {:dir "/w"}} {:git (merge git g)} more))]
    (testing "what asks for an action, one line each, with the components' names"
      (let [html (render/run-summary {:a (c {:branch "feature/a" :behind 2})
                                      :b (c {:branch "feature/b" :ahead 1 :unreachable 1} :source {:dir "/wt" :worktree "/wt"} :wanted-branch "feature/c")
                                      :d (c {:sha "b"} :deployed-sha "a")
                                      :e (c {:branch "feature/e" :ahead 3})})]
        (is (str/includes? html (str "<ul class=\"git-summary\">"
                                     "<li class=\"off\">&#9095; not the preset's branch: b</li>"
                                     "<li class=\"lose\">&#9888; blocks the next sync: b (1)</li>"
                                     "<li class=\"stale\">&#8800; to reload: d</li>"
                                     "<li class=\"behind\">&#8595; behind: a 2 main</li></ul>")))))
    (testing "a clone on a feature branch, unpushed commits in it: nothing to do, no line"
      (is (not (str/includes? (render/run-summary {:a (c {:branch "feature/a" :ahead 3})}) "git-summary"))))))

(deftest skipped-test
  (testing "given an apply skipped a one-shot, then it reads skipped with why, not queued"
    (let [html (render/component-card :replay {:kind :one-shot :status :skipped
                                               :error "skipped: alerte failed - reload it once that's fixed"})]
      (is (str/includes? html ">skipped<"))
      (is (str/includes? html "skipped: alerte failed")))))

(deftest card-screen-test
  (testing "given a deployed card, then its display says its state, in capitals, its load under it - none in a preview"
    (let [card #(render/component-card :svc (merge {:kind :process :status :up :usage {:cpu 12 :rss 1048576 :level :calm}} %))]
      (is (str/includes? (card {}) "<span class=\"screen\"><span class=\"line\"><b>UP</b>"))
      (is (re-find #"class=\"screen\">.*class=\"readout\"" (card {})))
      (is (not (str/includes? (card {:preview? true}) "class=\"screen\"")))
      (testing "and each figure in seven segments, coloured by its own level"
        (is (str/includes? (card {:usage {:cpu 200 :rss 1048576 :level :stressed}}) "<span class=\"fig\" data-level=\"stressed\">"))))))

(deftest vfd-figure-test
  (testing "given 2.1G, then a lit 2, a dot, a lit 1 and the unit beside them"
    (let [svg (render/vfd-figure "2.1G")
          lit (fn [part] (count (re-seq #"<polygon class=\"on\"" part)))
          [two one] (str/split svg #"<circle")]
      (is (= 5 (lit two)) "2: a b g e d")
      (is (= 2 (lit one)) "1: b c")
      (is (= 14 (count (re-seq #"<polygon" svg))) "every segment drawn, the unlit ones as a ghost")
      (is (str/includes? svg "<text class=\"on unit\" x=\"38\" y=\"9\">G</text>")))))

(deftest readout-test
  (testing "given a card's :usage, then two figures, each in its own level, the exact figures and the
            per-service split on hover"
    (let [html (render/component-card :kafka {:kind :docker-compose :status :up
                                              :usage {:cpu 295 :rss (* 1800 1048576) :level :stressed
                                                      :parts [{:name :kafka :cpu 290 :rss (* 1200 1048576)}]}})]
      (is (str/includes? html "<span class=\"fig\" data-level=\"stressed\"><svg"))
      (is (str/includes? html ">%</text></svg><small>CPU</small>"))
      (is (str/includes? html "<span class=\"fig\" data-level=\"busy\"><svg"))
      (is (str/includes? html ">G</text></svg><small>MEM</small>"))
      (is (str/includes? html "kafka: 290%, 1.2G"))))
  (testing "given no :usage (stopped, a one-shot, :external), then no figures"
    (is (not (str/includes? (render/component-card :svc {:kind :process :status :up}) "class=\"readout\""))))
  (testing "memory below a gigabyte in megabytes"
    (is (= "640M" (render/mem-text (* 640 1048576))))
    (is (= "1.2G" (render/mem-text (* 1229 1048576))))))

(deftest usage-summary-test
  (testing "given components' usage, then the total and the three heaviest per metric, each in its
            level - nobody named for CPU under 5%"
    (let [u (fn [cpu mib] {:status :up :usage {:cpu cpu :rss (* mib 1048576)}})
          html (render/run-summary {:kafka (u 295 1800) :db (u 10 300) :api (u 0 100) :ui (u 0 640) :web (u 70 90)})]
      (is (str/includes? html "<li><b>&#931;</b> 375% &#183; 2.9G</li>"))
      (is (str/includes? html (str "<li><b>cpu</b> <span data-level=\"stressed\">kafka 295%</span> · "
                                   "<span data-level=\"busy\">web 70%</span> · <span data-level=\"calm\">db 10%</span></li>")))
      (is (str/includes? html "<span data-level=\"busy\">kafka 1.8G</span> · <span data-level=\"calm\">ui 640M</span>"))))
  (testing "given nothing measured, then no usage list"
    (is (not (str/includes? (render/run-summary {:a {:status :up}}) "usage-top")))))
