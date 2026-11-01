(ns zent.ui.render-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zent.ui.render :as render]))

(deftest component-card-source-env-test
  (testing "given a strip reading other checkouts, then each dir shows with its branch
            and its var as the tooltip - what it reads, before it runs"
    (let [html (render/component-card :seed {:kind :one-shot :status :ready :attached-to :db
                                             :source-env {"SCHEMA" {:dir "/w/api/db" :branch "feature/x"}}})]
      (is (str/includes? html "title=\"SCHEMA\""))
      (is (str/includes? html "<span class=\"branch\" title=\"feature/x\">feature/x</span>"))
      (is (str/includes? html "data-dir=\"/w/api/db\"")))))

(deftest component-card-status-label-test
  (testing "each status gets its label-of wording, :external reading as
            plain \"up\""
    (doseq [[status label] {:pending "pending" :deploying "starting" :up "up"
                             :done "done" :idle "idle" :failed "failed" :external "up" :off "off" :down "stopped"
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

  (testing "given a one-shot, then the button runs it: \"Run\" while :ready (on demand,
            never run), \"Run again\" once :done - a service keeps \"Reload now\""
    (is (str/includes? (render/component-card :seed {:kind :one-shot :status :ready}) "title=\"Run\""))
    (is (str/includes? (render/component-card :seed {:kind :one-shot :status :ready})
                       "<span class=\"state\">on demand</span>"))
    (is (str/includes? (render/component-card :seed {:kind :one-shot :status :done}) "title=\"Run again\""))
    (is (str/includes? (render/component-card :svc {:kind :process :status :up}) "title=\"Reload now\"")))

  (testing "given a :confirm, then the button asks it before posting; without, it doesn't ask"
    (is (str/includes? (render/component-card :seed {:kind :one-shot :status :ready :confirm "Wipe the DB?"})
                       "hx-confirm=\"Wipe the DB?\""))
    (is (not (str/includes? (render/component-card :seed {:kind :one-shot :status :ready}) "hx-confirm"))))

  (testing "stop: only :up/:deploying/:idle/:unready (still running)"
    (is (str/includes? (render/component-card :svc {:kind :process :status :up}) "act stop"))
    (is (str/includes? (render/component-card :svc {:kind :process :status :unready}) "act stop"))
    (is (str/includes? (render/component-card :svc {:kind :process :status :unready}) "act reload"))
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

(deftest git-chip-test
  (let [git {:sha "abcdef1234" :branch "main" :base "main" :behind 0 :ahead 0 :dirty 0 :pushed true}
        chip #(render/git-chip (merge {:status :up :source {:dir "/w/svc"}} %))]
    (testing "not read yet: no chip"
      (is (nil? (chip {}))))
    (testing "no :branch and the default one on disk: nothing to flag"
      (is (= [true false false false false] ((juxt :default? :off? :wt? :lose? :stale?) (chip {:git git})))))
    (testing "given no :branch, the clone runs as it is: a feature branch there isn't off"
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
  (let [card #(render/component-card :svc (merge {:kind :process :status :up :from :origin
                                                  :source {:dir "/w/svc" :worktree "/w/svc"}} %))
        git {:sha "abcdef1234" :base "master" :behind 3 :ahead 1 :unreachable 1 :dirty 2 :pushed false :from :origin}]
    (testing "given a last fetch, then its age comes from the render's :now: same input, same card"
      (let [args {:git (assoc git :fetched-at 1000) :wanted-branch "feature/y" :now 61000}]
        (is (str/includes? (card args) "(as of the last fetch, 1m00s ago)"))
        (is (= (card args) (card args)))))
    (testing "given every flag up in an :origin worktree, then each count says what it is, what blocks the next sync
              in red, and a test rebase a click away"
      (let [html (card {:git git :wanted-branch "feature/y" :deployed-sha "0000000"})]
        (is (str/includes? html "&#9095; y</span>"))
        (is (str/includes? html "<span class=\"branch wt\" title=\"feature/y&#10;the preset's :branch: origin's, in a worktree\""))
        (is (str/includes? html "<span class=\"from origin\" title=\"runs origin/feature/y in its own worktree"))
        (is (str/includes? html "&#10227; origin</span>"))
        (is (str/includes? html "(never fetched)\">&#8595;3 master</span>"))
        (is (not (str/includes? html "act git-act")) "uncommitted files: no rebase")
        (is (str/includes? html "&#9888;1 on no branch</span>"))
        (is (str/includes? html "<span class=\"dirty lose\" title=\"2 modified or untracked file(s) - the next sync refuses until you commit or stash them\">&#9679;2 uncommitted</span>"))
        (is (str/includes? html "reload to run it\">&#8800; reload</span>"))
        (testing "and the facts on hover: what runs, what a sync and a reload do"
          (is (str/includes? html "An apply that redeploys it or a pull fetches, then resets it to origin/feature/y - refused while it holds uncommitted files or commits on no branch; a reload keeps its commit"))
          (is (str/includes? html "the next sync refuses until you keep them (git branch rescue/feature/y HEAD)"))
          (is (str/includes? html "running 0000000, the disk is at abcdef1: reload to run it")))))
    (testing "given a clean :origin worktree behind, then a local test rebase a click away"
      (let [html (card {:git (assoc git :dirty 0 :unreachable 0) :wanted-branch "feature/y"})]
        (is (str/includes? html "test on master</button>"))
        (is (str/includes? html "A local test: no branch moves, the next apply or pull reverts it.") "its confirmation")))
    (testing "given a local test rebase, then its badge and an undo, no unpushed count"
      (let [html (card {:git (assoc git :test? true :behind 0 :unreachable 0 :dirty 0 :ahead 2) :wanted-branch "feature/y"})]
        (is (str/includes? html "&#10548; master (test)</span>"))
        (is (str/includes? html "a local test, no branch moved - the next apply or pull reverts it"))
        (is (str/includes? html ">undo test</button>"))
        (is (not (str/includes? html "unpushed")))))
    (testing "given :from :local, then its badge, what syncs it, its branch against origin's, and a rebase of it"
      (let [html (card {:from :local :wanted-branch "feature/y"
                        :git {:sha "abc" :base "master" :behind 2 :ahead 3 :from :local :pull 1 :local-ahead 3 :local-behind 2}})]
        (is (str/includes? html "<span class=\"from local moved\" title=\"runs your local feature/y in its own worktree, detached - commit to it to run a change, no push needed. An apply that redeploys it or a pull resets it to your feature/y"))
        (is (str/includes? html "&#9095; local +1</span>"))
        (is (str/includes? html "hx-post=\"/api/pull/svc\""))
        (is (str/includes? html "&#8593;3 unpushed on y</span>"))
        (is (str/includes? html "&#8595;2 on origin</span>"))
        (is (str/includes? html "pull --rebase in your clone"))
        (is (str/includes? html "hx-confirm=\"Rebase your local branch feature/y onto origin/master? It moves feature/y (refused while another checkout has it out). It is on origin: push --force-with-lease afterwards.\""))
        (is (str/includes? html ">rebase</button>")))))
  (testing "given the main clone on a feature branch, no :branch: it runs as it is, nothing red"
    (let [html (render/component-card :svc {:kind :process :status :up :source {:dir "/w/svc"}
                                            :git {:sha "abc" :branch "feature/z" :base "main" :ahead 4 :pushed true}})]
      (is (str/includes? html "<span class=\"branch\" title=\"feature/z&#10;no :branch in the preset"))
      (is (str/includes? html "<span class=\"from clone\" title=\"runs your local clone as it is, uncommitted changes included - nothing syncs it, a reload restarts what's on disk\">&#9998; clone</span>"))
      (is (str/includes? html "safe here: the clone runs as it is\">&#8593;4 unpushed</span>"))))
  (testing "given a clean clone behind its default branch, then a rebase button, warning when the branch is on origin"
    (let [card #(render/component-card :svc {:kind :process :status :up :source {:dir "/w/svc"}
                                             :git (merge {:sha "abc" :branch "feature/z" :base "main" :behind 2 :pushed true} %)})]
      (is (str/includes? (card {}) "hx-post=\"/api/rebase/svc\""))
      (is (str/includes? (card {}) "hx-confirm=\"Rebase feature/z onto origin/main? It is on origin: push --force-with-lease afterwards.\""))
      (is (not (str/includes? (card {:dirty 1}) "act git-act")) "uncommitted files: no button")))
  (testing "given origin moved past a worktree's commit, then +N and a pull button"
    (let [html (render/component-card :svc {:kind :process :status :up :wanted-branch "feature/w" :from :origin
                                            :source {:dir "/wt/svc" :worktree "/wt/svc"}
                                            :git {:sha "abc" :base "main" :pull 3}})]
      (is (str/includes? html "&#10227; origin +3</span>"))
      (is (str/includes? html "hx-post=\"/api/pull/svc\""))
      (is (str/includes? html "origin has 3 more commit(s)"))))
  (testing "given :from :clone and the clone on another branch, then red, saying the deploy refuses"
    (let [html (render/component-card :svc {:kind :process :status :up :wanted-branch "feature/w" :from :clone
                                            :source {:dir "/w/svc"} :git {:sha "abc" :branch "main" :base "main"}})]
      (is (str/includes? html "class=\"branch off\""))
      (is (str/includes? html "the preset's :from :clone wants feature/w checked out here"))
      (is (str/includes? html "&#9998; clone</span>"))))
  (testing "in a preview: the rebase names its preset, a :branch never deployed shows its worktree to come, and
            an existing one what the apply would do to it"
    (is (str/includes? (render/component-card :svc {:kind :process :status :off :preview? true :preview-of "mine"
                                                    :source {:dir "/w/svc"}
                                                    :git {:sha "abc" :branch "feature/z" :base "main" :behind 2}})
                       "hx-post=\"/api/rebase/svc?preset=mine\""))
    (is (str/includes? (render/component-card :svc {:kind :process :status :off :preview? true :wanted-branch "feature/new"
                                                    :from :origin :source {:dir "/wt/svc" :worktree "/wt/svc"}})
                       "<span class=\"from origin pending\""))
    (is (str/includes? (render/component-card :svc {:kind :process :status :off :preview? true :wanted-branch "feature/new"
                                                    :from :local :source {:dir "/wt/svc" :worktree "/wt/svc"}})
                       "&#9888; created at apply from feature/new</span>"))
    (let [pv #(render/component-card :svc {:kind :process :status :off :preview? true :preview-of "mine" :wanted-branch "feature/w"
                                           :from :origin :source {:dir "/wt/svc" :worktree "/wt/svc"}
                                           :git (merge {:sha "abc" :base "main" :from :origin} %)})]
      (is (str/includes? (pv {:pull 2}) "apply: reset +2</span>"))
      (is (not (str/includes? (pv {:pull 2}) "/api/pull/")) "no pull from a preview")
      (is (str/includes? (pv {}) "apply: keeps</span>"))
      (is (str/includes? (pv {:pull 2 :dirty 1}) "&#9995; sync refused: 1 at risk</span>"))))
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

(deftest usage-row-test
  (testing "given a card's :usage, then two meters with their figures, each in its own level, the
            exact figures and the per-service split on hover"
    (let [html (render/component-card :kafka {:kind :docker-compose :status :up
                                              :usage {:cpu 295 :rss (* 1800 1048576) :level :stressed
                                                      :parts [{:name :kafka :cpu 290 :rss (* 1200 1048576)}]}})]
      (is (str/includes? html "<span class=\"usage\" data-level=\"stressed\""))
      (is (str/includes? html "<span class=\"meter cpu\" data-level=\"stressed\"><i style=\"--v:1.00\"></i><b>295%</b></span>"))
      (is (str/includes? html "<span class=\"meter rss\" data-level=\"busy\"><i style=\"--v:0.44\"></i><b>1.8G</b></span>"))
      (is (str/includes? html "kafka: 290%, 1.2G"))))
  (testing "given no :usage (stopped, a one-shot, :external), then no row"
    (is (not (str/includes? (render/component-card :svc {:kind :process :status :up}) "class=\"usage\""))))
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
