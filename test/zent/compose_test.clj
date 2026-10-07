(ns zent.compose-test
  "Engine-level composition tests, against a synthetic catalog."
  (:require [clojure.test :refer [deftest is testing]]
            [zent.compose :as compose]))

(def catalog
  {:defaults {:org "acme" :jdk-home "/opt/jdk"}
   :components {:db {:kind :docker-compose :repo "db"}
                :api {:kind :quarkus-app :repo "api" :port 8080 :deps [:db]}
                :seed {:kind :one-shot :repo "db" :scripts ["./seed.sh"] :deps [:db]}
                :ui {:kind :process :repo "ui" :cmd "npm start" :deps [:api]}}
   :presets {:full (compose/run [:db :api :seed :ui])
             :standalone-ui {:ui {:mode :on :deps []}}}})

(deftest run-test
  (testing "naming a component IS turning it on - :mode never appears in a
            catalog, but it's still what comes out"
    (is (= {:db {:mode :on} :api {:mode :on}} (compose/run :db :api))))

  (testing "groups are plain vectors of names, spliced however they nest, so
            they compose by being mentioned rather than by being merged"
    (is (= (compose/run :db :api :seed)
           (compose/run [:db :api] :seed)
           (compose/run [[:db] [:api :seed]]))))

  (testing "a map applies to the name just before it, and wins over the :mode
            that name put there"
    (is (= {:db {:mode :on} :api {:mode :on :port 9999}}
           (compose/run :db :api {:port 9999})))
    (is (= {:db {:mode :off}} (compose/run :db {:mode :off}))))

  (testing "a group carrying its own overrides is just a vector that starts
            with a name - that's how a reusable variant is written"
    (let [java17 [:api {:port 9999}]]
      (is (= {:db {:mode :on} :api {:mode :on :port 9999}}
             (compose/run :db java17)))))

  (testing "overrides with nothing to attach to are a mistake, not a silent
            no-op, and so is anything that's neither a name nor a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no component before them"
                          (compose/run {:port 1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"component names, override maps"
                          (compose/run :db "api")))))

(deftest resolve-preset-test
  (testing "cfg is catalog defaults, then the registry entry, then the overlay"
    (let [{:keys [api]} (compose/resolve-preset catalog :full)]
      (is (= "acme" (:org api)))
      (is (= "/opt/jdk" (:jdk-home api)))
      (is (= "api" (:repo api)))
      (is (= :on (:mode api)))))

  (testing "an overlay beats the registry, which beats the defaults"
    (let [c (assoc-in catalog [:presets :full :api] {:mode :on :org "other"})]
      (is (= "other" (get-in (compose/resolve-preset c :full) [:api :org])))))

  (testing "a preset may override :deps; a component without any gets []"
    (is (= [:api] (get-in (compose/resolve-preset catalog :full) [:ui :deps])))
    (is (= [] (get-in (compose/resolve-preset catalog :standalone-ui) [:ui :deps])))
    (is (= [] (get-in (compose/resolve-preset catalog :full) [:db :deps]))))

  (testing "a registry entry is validated against its kind before any merging -
            that's where the bad value was written"
    (let [c (assoc-in catalog [:components :ui :port] "big")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid registry entry for :ui"
                            (compose/resolve-preset c :standalone-ui)))))

  (testing "a preset's overlay is still checked against the kind, and there a
            leftover key is fine - it's what lets a preset swap :kind and
            leave the registry's own fields riding along"
    (let [c (assoc-in catalog [:presets :full :api] {:mode :on :port "nope"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid component config for :api"
                            (compose/resolve-preset c :full))))
    (is (compose/resolve-preset (assoc-in catalog [:presets :full :api]
                                          {:mode :on :leftover-from-a-kind-swap true})
                                :full)))

  (testing "a bad readiness spec fails at resolve time, not halfway through a deploy"
    (let [c (assoc-in catalog [:presets :full :db]
                      {:mode :on :readiness {:type :http-get :url "http://x"}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid component config for :db"
                            (compose/resolve-preset c :full)))))

  (testing "unknown preset and unknown component are named clearly"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown preset: :nope"
                          (compose/resolve-preset catalog :nope)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"references unknown component :ghost"
                          (compose/resolve-preset (assoc-in catalog [:presets :full :ghost] {})
                                                  :full))))

  (testing "a malformed catalog is rejected as one error, before any resolution"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid catalog"
                          (compose/resolve-preset (assoc catalog :components {:db {}}) :full)))))

(deftest catalog-only-keys-test
  (testing "a preset can't widen what the catalog allows - :allow-k8s-contexts
            is set by the catalog only, whoever wrote the preset"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only the catalog may"
                          (compose/resolve-preset
                           (assoc-in catalog [:presets :sneaky] {:db {:mode :on :allow-k8s-contexts ["prod"]}})
                           :sneaky)))))

(deftest secret-bearing-component-test
  (let [cat (-> catalog
                (assoc-in [:components :relay] {:kind :process :cmd "run"
                                                :secret-env {"ID" {:context "c" :namespace "n" :secret "s" :key "k"}}})
                (assoc-in [:defaults :allow-secret-contexts] ["c"]))
        resolve #(compose/resolve-preset (assoc-in cat [:presets :p] %) :p)]
    (testing "a preset may switch secrets on, and set what can't change what runs"
      (is (true? (get-in (resolve {:relay {:mode :on :use-secret-env true :branch "x"}}) [:relay :use-secret-env]))))

    (testing "but nothing that changes what runs with them - that could print them"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"it reads secrets"
                            (resolve {:relay {:mode :on :use-secret-env true :cmd "env"}}))))

    (testing "nor point a component at secrets of its own"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only the catalog may"
                            (resolve {:api {:mode :on :secret-env {}}}))))

    (testing "secret refs can't smuggle kubectl arguments"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid"
                            (compose/resolve-preset
                             (-> cat
                                 (assoc-in [:components :relay :secret-env "ID" :key] "k} --kubeconfig=/x")
                                 (assoc-in [:presets :p] {:relay {:mode :on}}))
                             :p))))))

(deftest preset-deps-override-test
  (testing "a preset's :deps replaces the registry's, as runtime-preset-keys allows"
    (let [c (assoc-in catalog [:presets :p] {:api {:mode :on :deps []}})]
      (is (= [] (get-in (compose/resolve-preset c :p) [:api :deps])))))
  (testing "without one, the registry's still applies"
    (is (= [:db] (get-in (compose/resolve-preset catalog :full) [:api :deps])))))

(deftest unknown-references-test
  (let [resolve #(compose/resolve-preset (assoc-in catalog [:components :api] %) :full)]
    (testing "a dep outside the preset is fine, one naming no component is a typo"
      (is (map? (compose/resolve-preset (assoc-in catalog [:presets :api-only] {:api {:mode :on}}) :api-only)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":deps names unknown component\(s\) \[:dbb\]"
                            (resolve {:kind :quarkus-app :repo "api" :port 8080 :deps [:dbb]}))))
    (testing ":related and :attached-to too"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":related names unknown"
                            (resolve {:kind :quarkus-app :repo "api" :port 8080 :related [:nope]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":attached-to names unknown"
                            (resolve {:kind :one-shot :repo "api" :scripts ["x"] :attached-to :nope}))))
    (testing "and a :source-env's :component"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":source-env names unknown component\(s\) \[:nope\]"
                            (resolve {:kind :one-shot :repo "api" :scripts ["x"]
                                      :source-env {"DIR" {:component :nope}}}))))))

(deftest source-env-test
  (let [seed {:kind :one-shot :repo "db" :scripts ["./seed.sh"]
              :source-env {"UI_DIR" {:component :ui :path "src"}
                           "UI_DATA" {:component :ui :main-clone true}}}
        cat (-> catalog (assoc-in [:components :seed] seed) (assoc-in [:components :ext] {:kind :external}))
        pinned #(get-in (compose/resolve-preset (assoc-in cat [:presets :p] (apply compose/run %)) :p)
                        [:seed :source-env])]
    (testing "given a source the preset doesn't name, then it's pinned to its catalog
              entry - the main clone, the current state on disk"
      (is (= {:component :ui :path "src" :repo "ui" :org "acme"} ((pinned [:seed]) "UI_DIR"))))
    (testing "given the preset puts the source on a branch, without running it, then
              that branch is pinned - except for a :main-clone entry"
      (let [p (pinned [:seed :ui {:mode :off :branch "feature/x"}])]
        (is (= "feature/x" (:branch (p "UI_DIR"))))
        (is (not (contains? (p "UI_DATA") :branch)))))
    (testing "given a source with no :repo, then the preset is refused - it has no dir"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names :ext, which has no :repo"
                            (compose/resolve-preset
                             (-> cat (assoc-in [:components :seed :source-env] {"X" {:component :ext}})
                                 (assoc-in [:presets :p] (compose/run :seed)))
                             :p))))))
