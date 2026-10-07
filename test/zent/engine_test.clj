(ns zent.engine-test
  (:require [clojure.test :refer [deftest is testing]]
            [zent.compose :as compose]
            [zent.engine :as engine]
            [zent.lifecycle :as lifecycle]
            [zent.logs :as logs]
            [zent.probe :as probe]
            [zent.session :as session]))

(def ^:private due? #'engine/due?)
(def ^:private reload! #'engine/reload!)
(defn- apply-preset!
  "switch-preset!'s additive half alone: deploys, never stops."
  [catalog preset st deploy-fn session-path on-resolved]
  (#'engine/deploy-plan! (engine/plan catalog preset @st) st deploy-fn session-path on-resolved nil))
(def ^:private switch-preset! #'engine/switch-preset!)
(def ^:private adopt-session! #'engine/adopt-session!)
(def ^:private recheck-liveness! #'engine/recheck-liveness!)
(def ^:private verified-alive? #'engine/verified-alive?)
(def ^:private deploy-into! #'engine/deploy-into!)
(def ^:private stop-and-forget! #'engine/stop-and-forget!)

(defn- state
  "serve!'s state atom, with `m` merged in."
  [& [m]]
  (doto (#'engine/new-state) (swap! merge m)))

(defn- collect
  "An on-up for deploy-into!, conj'ing [name handle] onto `pairs`."
  [pairs]
  #(swap! pairs conj [%1 %2]))

(defn- stopper
  "What serve! hands switch-preset! as stop!: tear down, forget, report."
  [st & {:keys [on-stopping on-stop] :or {on-stopping (constantly nil) on-stop (constantly nil)}}]
  (fn [n] (stop-and-forget! st n on-stopping) (on-stop n)))

(def catalog
  {:components {:svc {:kind :quarkus-app :repo "svc" :port 8080}}
   :presets {:one {:svc {:mode :on}}}})

(deftest due-debounce-test
  (testing "nothing pending is never due"
    (is (not (due? {} 1000 200 10000))))

  (testing "a change becomes due once it has been quiet for quiet-ms"
    (let [t {:dirty-since 1000 :last-change 1000}]
      (is (not (due? t 1100 200 10000)) "still inside the quiet period")
      (is (due? t 1200 200 10000))))

  (testing "a stream of saves re-arms the quiet period, so nothing reloads mid-edit"
    (is (not (due? {:dirty-since 1000 :last-change 5000} 5100 200 10000))))

  (testing "but the ceiling forces a reload eventually, whatever the edit rate"
    (is (due? {:dirty-since 1000 :last-change 11000} 11050 200 10000))))

(deftest reload-tolerates-failure-test
  (testing "a failing reload reports and yields :ok false - the caller keeps the
            old handle, so a broken edit can't take the running version down"
    (is (= {:ok false}
           (reload! :svc {:kind :quarkus-app :mode :on}
                    (fn [_ _] (throw (ex-info "build broke" {}))) nil))))

  (testing "a successful reload hands back the new handle"
    (with-redefs [engine/build-kind! (fn [_ _] nil)]
      (is (= {:ok true :handle :new-handle}
             (reload! :svc {:kind :quarkus-app :mode :on} (fn [_ _] :new-handle) nil)))))

  (testing "reloads go through the injected deploy-fn, which is what lets the UI
            observe a whole serve and not just its first deploy"
    (let [seen (atom [])]
      (reload! :svc {:kind :quarkus-app} (fn [n _] (swap! seen conj n) :h) nil)
      (is (= [:svc] @seen)))))

(deftest reload-stops-a-running-process-first-test
  (testing "a live process is stopped before its replacement starts - otherwise
            the new one can't bind the port and the old one runs on untracked"
    (let [proc (.start (ProcessBuilder. ["sleep" "30"]))
          alive-at-deploy (atom nil)]
      (try
        (reload! :svc {:kind :process} (fn [_ _] (reset! alive-at-deploy (.isAlive proc)) :new) proc)
        (is (false? @alive-at-deploy))
        (finally (.destroy proc)))))

  (testing "given a process, then it's built while the old one still runs, and a
            failed build leaves the old one up and deploy-fn never reached"
    (let [proc (.start (ProcessBuilder. ["sleep" "30"]))
          alive-at-build (atom nil)
          deployed (atom false)]
      (try
        (with-redefs [engine/build-kind! (fn [_ _] (reset! alive-at-build (.isAlive proc))
                                           (throw (ex-info "mvn exited 1" {})))]
          (is (= {:ok false}
                 (reload! :svc {:kind :process :mode :on} (fn [_ _] (reset! deployed true)) proc))))
        (is (true? @alive-at-build))
        (is (.isAlive proc) "a broken build can't take the running version down")
        (is (false? @deployed))
        (finally (.destroy proc)))))

  (testing "given a successful build, then deploy-fn gets the built cfg and
            deploy-component! won't build it again"
    (let [builds (atom 0)]
      (with-redefs [engine/build-kind! (fn [_ _] (swap! builds inc) {:built-dir "/d"})
                    engine/deploy-kind! (fn [_ cfg] (:built-dir cfg))]
        (is (= {:ok true :handle "/d"}
               (reload! :svc {:kind :process :mode :on} engine/deploy-component! nil))))
      (is (= 1 @builds))))

  (testing "a compose/k8s handle is left alone - those converge in place, and a
            failed reload must leave them running"
    (let [stopped (atom [])]
      (with-redefs [lifecycle/down! (fn [hs] (swap! stopped into hs) {:stopped hs :errors []})]
        (reload! :db {:kind :docker-compose} (fn [_ _] :h) {:kind :docker-compose :repo-dir "/x"})
        (reload! :web {:kind :k8s} (fn [_ _] :h) {:kind :k8s :dir "/x"}))
      (is (= [] @stopped)))))

(deftest reuse-if-live-test
  (let [path (str (System/getProperty "java.io.tmpdir") "/zent-engine-reuse-test-session.edn")
        proc (.start (ProcessBuilder. ["sleep" "30"]))]
    (try
      (session/save! path :one [[:svc proc]])
      (logs/start-run! :svc)
      (logs/append! :svc "echo of a secret from the earlier run")
      (logs/mark-sensitive! :svc)
      (testing "a component the session already has alive - shared with a previous
                preset, say - isn't spawned a second time"
        (let [calls (atom [])
              up (atom [])]
          (deploy-into! (collect up) [[:svc (get-in catalog [:components :svc])]]
                        (fn [n _] (swap! calls conj n) :should-not-be-called) path)
          (is (= [] @calls) "deploy-fn was never reached - the live pid was reused instead")
          (is (= (.pid proc) (:pid (second (first @up)))))))
      (testing "its log isn't truncated, nor its secret mark cleared - the process
                still runs, and may still echo what it was started with"
        (is (= ["echo of a secret from the earlier run"] (logs/tail :svc 10)))
        (is (logs/sensitive? :svc)))
      (finally (.destroy proc) (session/clear! path)))))

(deftest apply-preset-test
  ;; :external - no fields to fill in, and deploy-fn is stubbed: this exercises the merge/dedup logic without any real deploy-kind!
  (let [growable-catalog {:components {:db {:kind :external}
                                       :api {:kind :external :deps [:db]}}
                          :presets {:just-db {:db {:mode :on}}
                                    :both {:db {:mode :on} :api {:mode :on}}}}
        st (state)
        calls (atom [])
        deploy-fn (fn [n _] (swap! calls conj n) :h)
        session-path (str (System/getProperty "java.io.tmpdir")
                          "/zent-apply-preset-test-session.edn")]
    (testing "merges the resolved preset into active and deploys its components"
      (apply-preset! growable-catalog :just-db st deploy-fn session-path nil)
      (is (= #{:db} (set (keys (:active @st)))))
      (is (= [:db] @calls)))

    (testing "a later apply merges without redeploying what already has a handle"
      (apply-preset! growable-catalog :both st deploy-fn session-path nil)
      (is (= #{:db :api} (set (keys (:active @st)))))
      (is (= [:db :api] @calls) "only :api is new - :db already has a handle"))

    (testing "on-resolved sees the whole merged set, not just what this call added"
      (let [seen (atom nil)]
        (apply-preset! growable-catalog :just-db st deploy-fn session-path
                       (fn [preset-name resolved] (reset! seen [preset-name (set (keys resolved))])))
        (is (= [:just-db #{:db :api}] @seen))))))

(deftest apply-preset-reconfigures-test
  (let [catalog {:components {:db {:kind :external}}
                 :presets {:main {:db {:mode :on}}
                           :feature {:db {:mode :on :branch "x"}}
                           :db-off {:db {:mode :off}}}}
        st (state)
        calls (atom [])
        deploy-fn (fn [n cfg] (swap! calls conj [n (:branch cfg)]) :h)
        session-path (str (System/getProperty "java.io.tmpdir") "/zent-apply-reconfigure-test.edn")]
    (apply-preset! catalog :main st deploy-fn session-path nil)
    (testing "given :db running, when a preset configures it differently, then it's redeployed"
      (apply-preset! catalog :feature st deploy-fn session-path nil)
      (is (= [[:db nil] [:db "x"]] @calls))
      (is (= "x" (get-in @st [:active :db :branch]))))
    (testing "when a preset marks it :off, then it keeps running as it was"
      (apply-preset! catalog :db-off st deploy-fn session-path nil)
      (is (= 2 (count @calls)))
      (is (= :on (get-in @st [:active :db :mode]))))
    (testing "given an adopted :db (config unknown), then a preset doesn't redeploy it"
      (swap! st assoc-in [:active :db :zent.engine/adopted] true)
      (apply-preset! catalog :main st deploy-fn session-path nil)
      (is (= 2 (count @calls))))))

(deftest apply-preset-kind-change-and-failed-redeploy-test
  (let [catalog {:components {:db {:kind :process :repo "db" :cmd "run"}}
                 :presets {:local {:db {:mode :on}}
                           :ext {:db {:mode :on :kind :external}}
                           :bad {:db {:mode :on :branch "broken"}}}}
        st (state)
        downed (atom [])
        deploy-fn (fn [_ cfg] (if (= "broken" (:branch cfg)) (throw (ex-info "build failed" {})) {:kind (:kind cfg)}))
        apply! #(apply-preset! catalog % st deploy-fn
                               (str (System/getProperty "java.io.tmpdir") "/zent-kind-change-test.edn") nil)]
    (with-redefs [lifecycle/down! (fn [hs] (swap! downed into hs) {:stopped hs :errors []})]
      (apply! :local)
      (testing "given :db running, when a preset changes its :kind, then the old handle is stopped first"
        (apply! :ext)
        (is (= [{:kind :process}] @downed))
        (is (= {:db {:kind :external}} (:handles @st)) "the new handle replaces the old one"))
      (testing "when a redeploy fails, then :db keeps its previous cfg, so a retry isn't a no-op"
        (is (thrown? clojure.lang.ExceptionInfo (apply! :bad)))
        (is (= :external (get-in @st [:active :db :kind])))
        (is (nil? (get-in @st [:active :db :branch])))))))

(deftest plan-test
  (let [catalog {:components {:db {:kind :external} :api {:kind :external :deps [:db]}
                              :old {:kind :external} :warm {:kind :external :keep-warm true}
                              :perm {:kind :external :permanent true}}
                 :presets {:base {:db {:mode :on} :old {:mode :on} :warm {:mode :on} :perm {:mode :on}}
                           :p {:db {:mode :on :branch "x"} :api {:mode :on}}}}
        active (compose/resolve-preset catalog :base)
        handles {:db :h :old :h :warm :h :perm :h}]
    (testing "given db/old/warm/perm running, when planning :p, then each lands in its bucket"
      (let [p (engine/plan catalog :p {:active active :handles handles})]
        (is (= [:old] (:stop p)))
        (is (= [:warm] (:keep-warm p)))
        (is (= {:db {:branch [nil "x"]}} (:redeploy p)) "and says why")
        (is (= #{} (:restart p)) "not a process, same kind: converges in place")
        (is (= [:db :api] (map first (:deploy p))))
        (is (= {:start [:api] :redeploy {:db ":branch nil -> \"x\""}}
               (select-keys (engine/plan-summary p) [:start :redeploy])) "preview's printable form")))
    (testing "when stopping everything, then warm ones stop too, permanent stays"
      (is (= [:db :old :warm] (:stop (engine/plan catalog nil {:active active :handles handles})))))))

(deftest switch-preset-keep-warm-test
  (let [catalog {:components {:broker {:kind :external :keep-warm true}
                              :app {:kind :external :deps [:broker]}
                              :other {:kind :external}}
                 :presets {:with-broker {:broker {:mode :on} :app {:mode :on}}
                           :without {:other {:mode :on}}}}
        st (state)
        calls (atom []) stopped (atom [])
        deploy-fn (fn [n _] (swap! calls conj n) :h)
        switch! (fn [preset]
                  (switch-preset! catalog preset st
                                  (str (System/getProperty "java.io.tmpdir") "/zent-keep-warm-test.edn")
                                  deploy-fn nil (stopper st :on-stop #(swap! stopped conj %))))]
    (with-redefs [lifecycle/down! (fn [_] nil)]
      (switch! :with-broker)
      (testing "given :broker :keep-warm, when switching to a preset without it, then
                it keeps running, marked idle, while :app is stopped"
        (switch! :without)
        (is (= [:app] @stopped))
        (is (true? (get-in @st [:active :broker :zent.engine/idle]))))
      (testing "when switching back, then :broker isn't redeployed and is no longer idle"
        (switch! :with-broker)
        (is (= [:broker :app :other :app] @calls))
        (is (nil? (get-in @st [:active :broker :zent.engine/idle]))))
      (testing "when stopping everything, then a warm component stops too"
        (switch! :without)
        (switch! nil)
        (is (some #{:broker} @stopped))))))

(deftest deploy-into-failure-skips-only-dependents-test
  (testing "given :a failing, then :b (depends on it) is skipped, independent :c
            still deploys, and :a's error is rethrown at the end"
    (let [started (atom []) calls (atom [])
          deploy-fn (fn [n _] (swap! calls conj n) (if (= :a n) (throw (ex-info "a broke" {})) :h))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a broke"
                            (deploy-into! (collect started) [[:a {}] [:b {:deps [:a]}] [:c {}]] deploy-fn
                                          (str (System/getProperty "java.io.tmpdir") "/zent-deploy-into-test.edn"))))
      (is (= #{:a :c} (set @calls)) "deployed concurrently, so in no set order")
      (is (= [[:c :h]] @started))))

  (testing "given a chain a <- b <- c with :a failing, then the skip is transitive"
    (let [calls (atom [])]
      (is (thrown? Exception
                   (deploy-into! (collect (atom [])) [[:a {}] [:b {:deps [:a]}] [:c {:deps [:b]}]]
                                 (fn [n _] (swap! calls conj n) (throw (ex-info "a broke" {})))
                                 (str (System/getProperty "java.io.tmpdir") "/zent-deploy-into-test.edn"))))
      (is (= [:a] @calls)))))

(deftest deploy-into-parallel-test
  (testing "given :a and :b independent and :c depending on both, when each takes
            300ms, then :a and :b overlap and :c starts only once both are up"
    (let [events (atom [])
          deploy-fn (fn [n _] (swap! events conj [:start n]) (Thread/sleep 300) (swap! events conj [:end n]) n)
          t0 (System/currentTimeMillis)]
      (deploy-into! (collect (atom [])) [[:a {}] [:b {}] [:c {:deps [:a :b]}]] deploy-fn
                    (str (System/getProperty "java.io.tmpdir") "/zent-deploy-into-test.edn"))
      (is (< (- (System/currentTimeMillis) t0) 850) "3 x 300ms sequentially would be 900ms+")
      (is (= #{[:start :a] [:start :b]} (set (take 2 @events))))
      (is (= [[:start :c] [:end :c]] (take-last 2 @events)))))

  (testing "given 4 independent components and :max-parallel 2, then at most 2
            deploy at once"
    (let [inside (atom 0) most (atom 0)
          deploy-fn (fn [n _] (swap! most max (swap! inside inc)) (Thread/sleep 100) (swap! inside dec) n)]
      (deploy-into! (collect (atom [])) (mapv (fn [n] [n {}]) [:a :b :c :d]) deploy-fn
                    (str (System/getProperty "java.io.tmpdir") "/zent-deploy-into-test.edn") 2)
      (is (= 2 @most)))))

(deftest stopping-is-signalled-before-stopped-test
  (testing "given a running component, when a switch drops it, then on-stopping
            fires before the teardown and on-stop after it"
    (let [catalog {:components {:svc {:kind :external}}
                   :presets {:p {:svc {:mode :on}}}}
          st (state)
          events (atom [])
          switch! (fn [preset]
                    (switch-preset! catalog preset st
                                    (str (System/getProperty "java.io.tmpdir") "/zent-stopping-test.edn")
                                    (fn [_ _] :h) nil
                                    (stopper st
                                             :on-stopping #(swap! events conj [:stopping %])
                                             :on-stop #(swap! events conj [:stopped %]))))]
      (with-redefs [lifecycle/down! (fn [_] (swap! events conj [:down]) nil)]
        (switch! :p)
        (switch! nil))
      (is (= [[:stopping :svc] [:down] [:stopped :svc]] @events)))))

(deftest switch-preset-test
  ;; :external - no fields to fill in - and a deploy-fn returning nil (no handle) so there's nothing for lifecycle/
  ;; down! to fail shelling out for - only the active/stopped bookkeeping is
  ;; under test here, not real teardown.
  (let [growable-catalog {:components {:db {:kind :external}
                                       :api {:kind :external :deps [:db]}
                                       :cache {:kind :external :permanent true}}
                          :presets {:with-api {:db {:mode :on} :api {:mode :on}}
                                    :with-cache {:db {:mode :on} :cache {:mode :on}}}}
        st (state)
        stopped (atom [])
        on-stop (fn [n] (swap! stopped conj n))
        deploy-fn (fn [_ _] nil)
        session-path (str (System/getProperty "java.io.tmpdir")
                          "/zent-switch-preset-test-session.edn")
        switch! #(switch-preset! growable-catalog % st session-path deploy-fn nil (stopper st :on-stop on-stop))]
    (try
      (testing "switching to a preset that shares :db but drops :api tears :api
                down - only what the new preset doesn't need"
        (switch! :with-api)
        (switch! :with-cache)
        (is (= #{:db :cache} (set (keys (:active @st)))))
        (is (= [:api] @stopped)))

      (testing "a :permanent component is never torn down by a switch, even when
                the newly-switched-to preset doesn't name it"
        (reset! stopped [])
        (switch! :with-api)
        (is (= #{:db :api :cache} (set (keys (:active @st)))))
        (is (= [] @stopped)))

      (testing "nil (stop everything) tears down all but the permanent ones - adopted
                or not, there's no other protection"
        (reset! stopped [])
        (switch! nil)
        (is (= #{:cache} (set (keys (:active @st)))))
        (is (= #{:db :api} (set @stopped))))
      (finally (session/clear! session-path)))))

(deftest adopt-session-test
  (let [path (str (System/getProperty "java.io.tmpdir") "/zent-engine-seed-test-session.edn")
        proc (.start (ProcessBuilder. ["sleep" "30"]))]
    (try
      (testing "a session-tracked, verifiably-alive component populates active/handles
                without deploying - the dashboard should show it immediately, not wait
                for an apply"
        (session/save! path :one [[:svc proc]])
        (let [st (state)
              seeded (adopt-session! catalog path st)]
          (is (= {:mode :on :kind :quarkus-app :repo "svc" :port 8080 :zent.engine/adopted true}
                 (get-in @st [:active :svc]))
              "flagged: what config it really runs is unknown")
          (is (= [:svc] (keys (:handles @st))))
          (is (= [:svc] (mapv first seeded))
              "returned so the caller can report it settled (see zent.engine/serve!)")))

      (testing "a session entry that no longer verifies (process gone, pid recycled) is
                skipped, not trusted blind - it still gets a normal (re)deploy"
        (session/clear! path)
        (session/save! path :one [[:svc {:kind :pid :component :svc :pid (.pid proc) :ps "stale"}]])
        (let [st (state)]
          (is (= [] (adopt-session! catalog path st)))
          (is (= {} (:active @st)))
          (is (= {} (:handles @st)))))

      (testing "a session name the catalog's registry no longer has is skipped, not a throw"
        (session/clear! path)
        (session/save! path :one [[:ghost proc]])
        (let [st (state)]
          (adopt-session! catalog path st)
          (is (= {} (:active @st)))
          (is (= {} (:handles @st)))))

      (testing "already-running? throwing for one stale entry (its repo dir gone,
                say) is skipped like any other unverified entry - not a crash that
                refuses to start the whole session over a single bad entry"
        (session/clear! path)
        (session/save! path :one [[:svc {:kind :docker-compose :component :svc :repo-dir "/x" :compose-opts {}}]])
        (let [dc-catalog {:components {:svc {:kind :docker-compose :repo "svc"}}
                          :presets {:one {:svc {:mode :on}}}}
              st (state)]
          (with-redefs [engine/already-running? (fn [_ _] (throw (ex-info "docker: not found" {})))]
            (is (= [] (adopt-session! dc-catalog path st))))
          (is (= {} (:active @st)))
          (is (= {} (:handles @st)))))
      (finally (.destroy proc) (session/clear! path)))))

(deftest recheck-liveness-external-test
  (let [external-cfg {:kind :external :mode :on :readiness {:type :http-poll :url "http://x"}}
        st (state {:active {:svc external-cfg} :handles {}})
        calls (atom [])
        on-check (fn [name cfg ok?] (swap! calls conj [name cfg ok?]))]
    (testing "not yet deployed (no handle) - never probed, so never reported"
      (with-redefs [probe/ready? (fn [_] false)]
        (recheck-liveness! st on-check))
      (is (= [] @calls)))

    (swap! st assoc-in [:handles :svc] {:kind :external})

    (testing "first check after deploy reports the transition from unknown"
      (with-redefs [probe/ready? (fn [_] true)]
        (recheck-liveness! st on-check))
      (is (= [[:svc external-cfg true]] @calls)))

    (testing "steady state - no repeated calls while nothing changes"
      (with-redefs [probe/ready? (fn [_] true)]
        (recheck-liveness! st on-check))
      (is (= [[:svc external-cfg true]] @calls)))

    (testing "a liveness flip is reported exactly once"
      (with-redefs [probe/ready? (fn [_] false)]
        (recheck-liveness! st on-check))
      (is (= [[:svc external-cfg true] [:svc external-cfg false]] @calls)))

    (testing "a component with no :readiness is never probed - nothing to check it against"
      (reset! calls [])
      (swap! st assoc :active {:svc (dissoc external-cfg :readiness)})
      (with-redefs [probe/ready? (fn [_] (throw (ex-info "should not be called" {})))]
        (recheck-liveness! st on-check))
      (is (= [] @calls))))

  (testing "a check that throws (missing repo dir, docker/curl unavailable) is
            inconclusive, not a reported \"down\" - and doesn't escape to kill
            the caller's poll loop"
    (let [cfg {:kind :external :mode :on :readiness {:type :http-poll :url "http://x"}}
          st (state {:active {:svc cfg} :handles {:svc {:kind :external}}})
          calls (atom [])]
      (with-redefs [probe/ready? (fn [_] (throw (ex-info "curl: not found" {})))]
        (is (nil? (recheck-liveness! st (fn [& args] (swap! calls conj args))))))
      (is (= [] @calls))
      (is (= {} (:liveness @st)) "no transition recorded - the exception said nothing about the real state"))))

(defmethod engine/deploy-kind! ::spawned [_ _] (.start (ProcessBuilder. ["sleep" "30"])))

(deftest deploy-component-readiness-test
  (testing "any kind's :readiness is waited on before its deploy counts as done"
    (let [waited (atom [])
          proc (with-redefs [probe/wait-ready! (fn [n spec] (swap! waited conj [n spec]) true)]
                 (engine/deploy-component! :svc {:kind ::spawned :mode :on :readiness {:url "u"}}))]
      (try
        (is (= [[:svc {:url "u"}]] @waited))
        (is (.isAlive proc))
        (finally (.destroy proc)))))

  (testing "a spawned process that never gets ready is stopped, not leaked untracked"
    (let [spawned (atom nil)]
      (with-redefs [engine/deploy-kind! (fn [_ _] (reset! spawned (.start (ProcessBuilder. ["sleep" "30"]))))
                    probe/wait-ready! (fn [_ _] (throw (ex-info "Timed out" {})))]
        (is (thrown? Exception (engine/deploy-component! :svc {:kind ::spawned :mode :on :readiness {:url "u"}}))))
      (is (not (.isAlive @spawned))))))

(deftest on-demand-test
  (let [ran (atom [])
        cfg {:kind :one-shot :mode :on :on-demand true}]
    (with-redefs [engine/deploy-kind! (fn [n _] (swap! ran conj n) nil)]
      (testing "given an :on-demand one-shot, when an apply deploys it, then it isn't run"
        (engine/deploy-component! :seed cfg)
        (is (= [] @ran)))
      (testing "when it's reloaded, then it runs"
        (reload! :seed cfg engine/deploy-component! nil)
        (is (= [:seed] @ran))))))

(defmethod engine/already-running? ::checkable [_ _] false)

(deftest recheck-liveness-any-kind-test
  (testing "any kind is checked through its own already-running?"
    (let [cfg {:kind ::checkable :mode :on}
          calls (atom [])]
      (recheck-liveness! (state {:active {:w cfg} :handles {:w {:kind ::checkable}}})
                         (fn [& args] (swap! calls conj args)))
      (is (= [[:w cfg false]] @calls))))

  (testing "a kind that can't tell falls back to its :readiness"
    (let [cfg {:kind ::unknowable :mode :on :readiness {:type :http-poll :url "http://x"}}
          calls (atom [])]
      (with-redefs [probe/ready? (fn [_] true)]
        (recheck-liveness! (state {:active {:w cfg} :handles {:w {:kind ::unknowable}}})
                           (fn [& args] (swap! calls conj args))))
      (is (= [[:w cfg true]] @calls)))))

(deftest recheck-liveness-compose-test
  (let [compose-cfg {:kind :docker-compose :mode :on :repo "db"}
        st (state {:active {:db compose-cfg} :handles {:db {:kind :docker-compose}}})
        calls (atom [])
        on-check (fn [name cfg ok?] (swap! calls conj [name cfg ok?]))]
    (testing "reuses already-running? - the same check a fresh deploy already
              calls to decide whether to touch something at all"
      (with-redefs [engine/already-running? (fn [_ _] false)]
        (recheck-liveness! st on-check))
      (is (= [[:db compose-cfg false]] @calls)))

    (testing "recovering is reported too"
      (with-redefs [engine/already-running? (fn [_ _] true)]
        (recheck-liveness! st on-check))
      (is (= [[:db compose-cfg false] [:db compose-cfg true]] @calls))))

  (testing "an :off component is never checked, even if its kind is
            otherwise checkable - already-running? querying docker for
            something deliberately turned off would be pure waste"
    (let [st (state {:active {:db {:kind :docker-compose :mode :off :repo "db"}} :handles {:db {:kind :docker-compose}}})
          calls (atom [])]
      (with-redefs [engine/already-running? (fn [_ _] (throw (ex-info "should not be called" {})))]
        (recheck-liveness! st (fn [& args] (swap! calls conj args))))
      (is (= [] @calls))))

  (testing "an unrecognized handle shape (neither a Process nor a :pid map)
            is skipped, not guessed at"
    (let [st (state {:active {:svc {:kind :process :mode :on :repo "svc" :cmd "run"}} :handles {:svc (Object.)}})
          calls (atom [])]
      (recheck-liveness! st (fn [& args] (swap! calls conj args)))
      (is (= [] @calls)))))

(deftest recheck-liveness-process-test
  (testing "a live java.lang.Process handle - .isAlive itself, no shelling out"
    (let [proc (.start (ProcessBuilder. ["sleep" "30"]))
          cfg {:kind :quarkus-app :mode :on :repo "svc" :port 8080}
          st (state {:active {:svc cfg} :handles {:svc proc}})
          calls (atom [])]
      (try
        (recheck-liveness! st (fn [name cfg ok?] (swap! calls conj [name cfg ok?])))
        (is (= [[:svc cfg true]] @calls))

        (.destroy proc)
        (.waitFor proc)
        (recheck-liveness! st (fn [name cfg ok?] (swap! calls conj [name cfg ok?])))
        (is (= [[:svc cfg true] [:svc cfg false]] @calls))
        (finally (.destroy proc)))))

  (testing "a :pid handle (reuse-if-live handed this back instead of redeploying,
            mid-session) - the same recorded-ps check verified-alive? uses"
    (let [proc (.start (ProcessBuilder. ["sleep" "30"]))
          real-ps (session/pid-identity (.pid proc))
          cfg {:kind :process :mode :on :repo "svc" :cmd "run"}
          st (state {:active {:svc cfg}})
          calls (atom [])]
      (try
        (let [_ (swap! st assoc :handles {:svc {:kind :pid :pid (.pid proc) :ps real-ps}})]
          (recheck-liveness! st (fn [name cfg ok?] (swap! calls conj [name cfg ok?])))
          (is (= [[:svc cfg true]] @calls)))

        (testing "a recycled pid (ps no longer matches) reads as gone"
          (let [_ (swap! st assoc :handles {:svc {:kind :pid :pid (.pid proc) :ps "stale"}})]
            (recheck-liveness! st (fn [name cfg ok?] (swap! calls conj [name cfg ok?])))
            (is (= [[:svc cfg true] [:svc cfg false]] @calls))))
        (finally (.destroy proc))))))

(deftest apply-failure-does-not-kill-the-resident-loop-test
  (testing "a failing apply (a bad deploy, a component that throws) is logged
            and left behind, not allowed to crash serve!'s own loop - a later
            command still gets processed, proving the loop is still alive"
    (let [attempts (atom [])
          fail-catalog {:components {:bad {:kind :quarkus-app :repo "x" :port 8080}
                                     :good {:kind :quarkus-app :repo "y" :port 8081}}
                       :presets {:bad {:bad {:mode :on}} :good {:good {:mode :on}}}}
          deploy-fn (fn [name _cfg]
                     (swap! attempts conj name)
                     (if (= name :bad) (throw (ex-info "boom" {})) (Object.)))
          control (atom [{:action :apply :preset :bad}])
          session-path (str (System/getProperty "java.io.tmpdir")
                            "/zent-engine-apply-survives-test.edn")
          handled (atom [])
          fut (future (engine/serve! fail-catalog :poll-ms 5 :control control
                                     :deploy-fn deploy-fn :session-path session-path
                                     :on-handled #(swap! handled into %)))]
      (try
        (Thread/sleep 100)
        (is (= [:bad] @attempts) "the failing apply did run")
        (is (= [:bad] (map :preset @handled)) "and still counts as handled")

        (swap! control conj {:action :apply :preset :good})
        (Thread/sleep 100)
        (is (= [:bad :good] @attempts)
            "a later command still gets processed - the loop survived the failure")
        (finally
          (future-cancel fut)
          (session/clear! session-path))))))

(deftest serve-apply-via-control-test
  (testing "an :apply command queued before serve! starts is drained on the first
            loop iteration, growing the empty active set - the mechanism the UI
            uses to pick a preset after the server's already up"
    (let [control (atom [{:action :apply :preset :one}])
          calls (atom [])]
      (future (Thread/sleep 300) (swap! control conj {:action :shutdown}))
      (engine/serve! catalog :poll-ms 5 :control control
                     :deploy-fn (fn [n _] (swap! calls conj n) nil)
                     :session-path (str (System/getProperty "java.io.tmpdir") "/zent-serve-apply-test.edn"))
      (is (= [:svc] @calls)))))

(deftest serve-reload-restarts-a-stopped-component-test
  (testing "given :svc stopped by a :stop, when it's reloaded, then it's deployed
            again with the same cfg - the dashboard's reload on a stopped card"
    (let [catalog {:components {:svc {:kind :external :branch "b"}}
                   :presets {:p {:svc {:mode :on}}}}
          control (atom [])
          deployed (atom [])]
      (future (Thread/sleep 150) (swap! control conj {:action :stop :name :svc})
              (Thread/sleep 150) (swap! control conj {:action :reload :name :svc})
              (Thread/sleep 300) (swap! control conj {:action :shutdown}))
      (with-redefs [lifecycle/down! (fn [_] nil)]
        (engine/serve! catalog :initial-preset :p :poll-ms 5 :control control
                       :session-path (str (System/getProperty "java.io.tmpdir") "/zent-reload-stopped-test.edn")
                       :deploy-fn (fn [n cfg] (swap! deployed conj [n (:branch cfg)]) {:kind :external})))
      (is (= [[:svc "b"] [:svc "b"]] @deployed)))))

(deftest serve-shutdown-test
  (testing "given a running preset with one :permanent component, when :shutdown
            is queued, then serve! returns, having torn down all but the
            permanent one, and reports the batch handled"
    (let [catalog {:components {:svc {:kind :external}
                                :keep {:kind :external :permanent true}}
                   :presets {:both {:svc {:mode :on} :keep {:mode :on}}}}
          procs (atom {})
          events (atom [])
          deploy-fn (fn [name _] (let [p (.start (ProcessBuilder. ["sleep" "30"]))]
                                   (swap! events conj name)
                                   (swap! procs assoc name p)
                                   p))
          handled (atom nil)
          session-path (str (System/getProperty "java.io.tmpdir") "/zent-serve-shutdown-test.edn")]
      (try
        (engine/serve! catalog :initial-preset :both :poll-ms 5 :session-path session-path
                       :deploy-fn deploy-fn :on-handled #(reset! handled %)
                       :on-ready #(swap! events conj :ready)
                       :control (atom [{:action :shutdown :id 7}]))
        (is (= :ready (first @events)) "ready once adopted, before the initial preset deploys")
        (is (not (.isAlive (:svc @procs))))
        (is (.isAlive (:keep @procs)))
        (is (= [{:action :shutdown :id 7}] @handled))
        (is (= [:keep] (map :component (:handles (session/read-session session-path)))))
        (finally
          (run! #(.destroy %) (vals @procs))
          (session/clear! session-path))))))

(deftest serve-loop-not-blocked-by-a-deploy-test
  (let [catalog {:components {:slow {:kind :external}
                              :quick {:kind :external}}
                 :presets {:p {:slow {:mode :on} :quick {:mode :on}}}}
        control (atom [])
        handled (atom [])
        stopped (atom [])
        stop-names (atom [])
        armed (atom false)
        release (promise)
        ;; once armed, :slow's deploy hangs until released: a long build
        deploy-fn (fn [n _] (when (and (= :slow n) @armed) @release) {:kind :external})
        session-path (str (System/getProperty "java.io.tmpdir") "/zent-serve-unblocked-test.edn")
        handled-ids #(set (map :id @handled))
        wait-for (fn [pred] (loop [i 0] (when (and (not (pred)) (< i 200)) (Thread/sleep 10) (recur (inc i)))))
        serving (future
                  (with-redefs [lifecycle/down! (fn [hs] (swap! stopped into hs) {:stopped hs :errors []})]
                    (engine/serve! catalog :initial-preset :p :poll-ms 5 :control control
                                   :session-path session-path :deploy-fn deploy-fn
                                   :on-stop #(swap! stop-names conj %)
                                   :on-handled #(swap! handled into %))))]
    (try
      (swap! control conj {:action :stop :name :ignored :id 1})
      (wait-for #(contains? (handled-ids) 1))
      (testing "given :slow's reload hanging, when :quick is stopped and :slow
                too, then :quick stops at once and :slow waits for its deploy"
        (reset! armed true)
        (swap! control conj {:action :reload :name :slow :id 2})
        (Thread/sleep 50)
        (swap! control conj {:action :stop :name :quick :id 3} {:action :stop :name :slow :id 4})
        (wait-for #(contains? (handled-ids) 3))
        (is (contains? (handled-ids) 3) "the loop wasn't stuck behind the reload")
        (is (not (contains? (handled-ids) 2)))
        (is (not (contains? (handled-ids) 4)) "deferred: :slow is mid-deploy"))
      (testing "when the reload ends, then it's reported done, and the deferred stop runs"
        (deliver release true)
        (wait-for #(contains? (handled-ids) 4))
        (is (every? (handled-ids) [1 2 3 4]))
        (is (= [:quick :slow] (remove #{:ignored} @stop-names)) ":slow only once its reload was through"))
      (finally
        (deliver release true)
        (swap! control conj {:action :shutdown :id 9})
        (deref serving 2000 nil)
        (session/clear! session-path)))))

(deftest watch-targets-no-git-test
  (let [root (str (System/getProperty "java.io.tmpdir") "/zent-watch-targets-" (System/currentTimeMillis))]
    (.mkdirs (java.io.File. (str root "/repo/src")))
    (testing "given a watched component, then its dir is read without resolve-source -
              no fetch/reset under a build, from serve!'s loop"
      (with-redefs [zent.source/resolve-source (fn [& _] (throw (ex-info "must not run git" {})))]
        (is (= #{:svc} (set (keys (#'engine/watch-targets
                                   {:svc {:watch {:paths ["src"]} :repo "repo" :workspace-dir root}})))))))
    (testing "given its repo not cloned, then it's just not watched"
      (is (= {} (#'engine/watch-targets {:svc {:watch {:paths ["src"]} :repo "nope" :workspace-dir root}}))))))

(deftest serve-commands-always-reported-test
  (let [catalog {:components {:svc {:kind :external}}
                 :presets {:p {:svc {:mode :on}}}}
        control (atom [])
        handled (atom [])
        release (promise)
        deploys (atom 0)
        ;; deploy 1: the initial preset; 2: a reload throwing an Error; 3: one hanging
        deploy-fn (fn [_ _] (case (swap! deploys inc)
                              2 (throw (AssertionError. "boom"))
                              3 @release
                              nil)
                    {:kind :external})
        by-id #(some (fn [c] (when (= % (:id c)) c)) @handled)
        wait-for (fn [pred] (loop [i 0] (when (and (not (pred)) (< i 300)) (Thread/sleep 10) (recur (inc i)))))
        serving (future
                  (with-redefs [lifecycle/down! (fn [hs] {:stopped hs :errors []})]
                    (engine/serve! catalog :initial-preset :p :poll-ms 5 :control control
                                   :session-path (str (System/getProperty "java.io.tmpdir") "/zent-serve-reported-test.edn")
                                   :deploy-fn deploy-fn
                                   :on-stop (fn [_] (throw (ex-info "observer broke" {})))
                                   :on-handled #(swap! handled into %))))]
    (try
      (testing "given a reload throwing an Error, not an Exception, then it's still
                reported, with its error"
        (swap! control conj {:action :reload :name :svc :id 1})
        (wait-for #(by-id 1))
        (is (= "boom" (:error (by-id 1)))))
      (testing "given an on-stop throwing, then the stop is still reported"
        (swap! control conj {:action :stop :name :ghost :id 2})
        (wait-for #(by-id 2))
        (is (by-id 2)))
      (testing "given a job queued behind a running one, when :shutdown comes, then
                it's reported as not run rather than run and torn down at once"
        (swap! control conj {:action :reload :name :svc :id 3})
        (Thread/sleep 50)
        (swap! control conj {:action :apply :preset :p :id 4})
        (Thread/sleep 50)
        (swap! control conj {:action :shutdown :id 5})
        (Thread/sleep 50)
        (deliver release true)
        (is (not= ::timeout (deref serving 3000 ::timeout)) "serve! returned")
        (is (= "zent is shutting down" (:error (by-id 4))))
        (is (by-id 5)))
      (finally (deliver release true) (swap! control conj {:action :shutdown :id 99})))))

(defmethod engine/already-running? ::followed [_ _] true)
(defmethod engine/follow-logs! ::followed [_ _ _ since] {:pid 7 :ps (str "since " since)})

(deftest log-follower-handover-test
  (testing "given a stack redeployed in place, then the replaced handle's follower
            stops - the new one follows it, two would print every line twice"
    (let [st (state {:handles {:db {:kind ::followed :follower {:pid 1 :ps "a"}}}})
          stopped (atom [])]
      (with-redefs [lifecycle/stop-follower! (fn [h] (swap! stopped conj (:pid (:follower h))))]
        (#'engine/put-handle! st :db {:kind ::followed :follower {:pid 2 :ps "b"}})
        (#'engine/put-handle! st :db {:kind ::followed :follower {:pid 2 :ps "b"}}))
      (is (= [1] @stopped) "only a follower actually replaced")
      (is (= 2 (get-in @st [:handles :db :follower :pid])))))

  (testing "given a session handle whose follower died with the earlier serve,
            then adoption starts one, from when that session was written"
    (let [path (str (System/getProperty "java.io.tmpdir") "/zent-follower-adopt-test.edn")
          st (state)]
      (try
        (session/save! path :p [[:db {:kind ::followed :follower {:pid 999999 :ps "gone"}}]])
        (let [written-at (:written-at (session/read-session path))]
          (#'engine/adopt-session! {:components {:db {:kind ::followed}}} path st)
          (is (= {:pid 7 :ps (str "since " written-at)} (get-in @st [:handles :db :follower]))))
        (finally (session/clear! path))))))

(deftest current-cfg-test
  (let [catalog {:components {:svc {:kind :external :branch "old"}}
                 :presets {:p {:svc {:mode :on}}}}
        applied (assoc (get (compose/resolve-preset catalog :p) :svc) :zent.engine/preset :p)
        edited (assoc-in catalog [:components :svc :branch] "new")]
    (testing "given the catalog changed since the apply, then a reload resolves it as it is now"
      (is (= "new" (:branch (#'engine/current-cfg edited :svc applied))))
      (is (= :p (:zent.engine/preset (#'engine/current-cfg edited :svc applied)))))
    (testing "given its preset gone, then the config it was applied with"
      (is (= applied (#'engine/current-cfg (update edited :presets dissoc :p) :svc applied))))
    (testing "given an adopted one (no preset), then its registry entry as it is now"
      (is (= "new" (:branch (#'engine/current-cfg edited :svc {:kind :external :mode :on :zent.engine/adopted true})))))))

(deftest serve-reload-runs-the-current-catalog-test
  (testing "given the catalog edited while serving (reload-code), when a component
            is reloaded, then it's deployed as the catalog now says - not as applied"
    (let [catalog {:components {:svc {:kind :external :branch "old"}}
                   :presets {:p {:svc {:mode :on}}}}
          catalog-ref (atom catalog)
          control (atom [])
          deployed (atom [])]
      (future (Thread/sleep 150)
              (swap! catalog-ref assoc-in [:components :svc :branch] "new")
              (swap! control conj {:action :reload :name :svc})
              (Thread/sleep 300) (swap! control conj {:action :shutdown}))
      (with-redefs [lifecycle/down! (fn [_] nil)]
        (engine/serve! catalog :catalog-ref catalog-ref :initial-preset :p :poll-ms 5 :control control
                       :session-path (str (System/getProperty "java.io.tmpdir") "/zent-reload-catalog-test.edn")
                       :deploy-fn (fn [_ cfg] (swap! deployed conj (:branch cfg)) {:kind :external})))
      (is (= ["old" "new"] @deployed)))))

(deftest serve-reload-code-test
  (testing "given a queued :reload-code, then serve! runs :reload-code, and a
            throwing one leaves the loop alive for the next command"
    (let [control (atom [{:action :reload-code :id 1}])
          calls (atom 0)
          handled (atom [])]
      (engine/serve! {:components {} :presets {}} :poll-ms 5
                     :session-path (str (System/getProperty "java.io.tmpdir") "/zent-serve-reload-code-test.edn")
                     :control control
                     :on-handled #(swap! handled into (map :id %))
                     :reload-code (fn []
                                    (swap! calls inc)
                                    (swap! control conj {:action :shutdown :id 2})
                                    (throw (ex-info "boom" {}))))
      (is (= 1 @calls))
      (is (= [1 2] @handled)))))

(deftest serve-liveness-interval-test
  (testing "given a process that dies after 200ms, then a recheck every tick
            sees it go down, while one every minute only saw it up"
    (let [run (fn [liveness-ms]
                (let [checks (atom [])
                      control (atom [])
                      catalog {:components {:svc {:kind :external}}
                               :presets {:p {:svc {:mode :on}}}}]
                  (future (Thread/sleep 600) (swap! control conj {:action :shutdown}))
                  (engine/serve! catalog :initial-preset :p :poll-ms 5 :liveness-ms liveness-ms
                                 :session-path (str (System/getProperty "java.io.tmpdir") "/zent-liveness-interval-test.edn")
                                 :control control
                                 :deploy-fn (fn [_ _] (.start (ProcessBuilder. ["sleep" "0.2"])))
                                 :on-check (fn [_ _ ok?] (swap! checks conj ok?)))
                  @checks))]
      (is (= [true false] (run 0)))
      (is (= [true] (run 60000))))))

(deftest serve-reuses-a-live-session-test
  (let [path (str (System/getProperty "java.io.tmpdir") "/zent-engine-test-session.edn")
        proc (.start (ProcessBuilder. ["sleep" "30"]))
        ;; queued before the loop starts: serve! returns after the initial apply
        shutdown #(atom [{:action :shutdown}])]
    (try
      (testing "a component the session has alive is reused, not redeployed - so
                a preset sharing one with what already runs doesn't start it
                twice"
        (session/save! path :one [[:svc proc]])
        (let [calls (atom [])]
          (engine/serve! catalog :initial-preset :one :session-path path :control (shutdown)
                         :deploy-fn (fn [n _] (swap! calls conj n)))
          (is (= [] @calls) "deploy-fn was never reached for :svc")))

      (testing "a stale, no-longer-alive session entry doesn't block or get reused -
                it just falls through to a normal (re)deploy"
        (session/clear! path)
        (session/save! path :one [[:svc {:kind :docker-compose :repo-dir "/x" :compose-opts {}}]])
        (let [calls (atom [])]
          (engine/serve! catalog :initial-preset :one :session-path path :control (shutdown)
                         :deploy-fn (fn [n _] (swap! calls conj n) nil))
          (is (= [:svc] @calls))))
      (finally (.destroy proc) (session/clear! path)))))

(deftest recheck-liveness-k8s-test
  (testing "a deployed :k8s component is rechecked through already-running? - the
            same readiness check a fresh apply uses, like the compose kinds"
    (let [cfg {:kind :k8s :mode :on :context "c" :namespace "n" :workload "deployment/x"}
          calls (atom [])]
      (with-redefs [engine/already-running? (fn [name _] (swap! calls conj name) false)]
        (recheck-liveness! (state {:active {:front cfg} :handles {:front {:kind :k8s}}})
                           (fn [name _ ok?] (swap! calls conj [name ok?]))))
      (is (= [:front [:front false]] @calls)))))

(deftest verified-alive-k8s-test
  (testing "a :k8s session entry seeded at startup is trusted only if its workload
            still has a ready replica"
    (with-redefs [engine/already-running? (fn [_ _] true)]
      (is (true? (verified-alive? {:kind :k8s :component :front} {:kind :k8s}))))
    (with-redefs [engine/already-running? (fn [_ _] false)]
      (is (false? (verified-alive? {:kind :k8s :component :front} {:kind :k8s}))))))
