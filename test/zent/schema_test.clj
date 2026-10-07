(ns zent.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [zent.schema :as schema]))

(deftest validate-component-valid-test
  (testing "a correct quarkus-app config passes through unchanged"
    (let [cfg {:kind :quarkus-app :repo "notifier" :port 9093}]
      (is (= cfg (schema/validate-component! :notifier cfg))))))

(deftest validate-one-shot-env-test
  (testing "given a one-shot with :env and :source-env, then it's valid; a :source-env
            entry with neither a :component nor a :repo isn't"
    (let [cfg {:kind :one-shot :repo "seeder" :scripts ["./seed.sh"] :env {"A" "1"}
               :source-env {"SRC" {:component :api :path "core"}}}]
      (is (= cfg (schema/validate-component! :seed cfg)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (schema/validate-component! :seed (assoc cfg :source-env {"SRC" {:path "core"}})))))))

(deftest validate-component-invalid-test
  (testing "a quarkus-app config missing :port is rejected with an exploitable message"
    (let [cfg {:kind :quarkus-app :repo "notifier"}
          ex (try
               (schema/validate-component! :notifier cfg)
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "should throw ex-info")
      (is (= {:port ["missing required key"]}
             (:errors (ex-data ex)))))))

(deftest describe-kinds-test
  (testing "every field of a builtin kind is described, :kind itself excluded"
    (let [fields (get (schema/describe-kinds) :quarkus-app)
          by-key (into {} (map (juxt :key identity)) fields)]
      (is (not (contains? by-key :kind)))
      (is (= {:key :repo :required true :type :string} (:repo by-key)))
      (is (= {:key :port :required true :type :int} (:port by-key)))
      (is (= {:key :env :required false :type :string-map} (:env by-key)))
      (is (= {:key :mode :required false :type :enum :values [:on :off]}
             (:mode by-key)))))

  (testing "a nested :map schema (readiness/watch) digests to a :group of fields"
    (let [watch (:watch (into {} (map (juxt :key identity))
                              (get (schema/describe-kinds) :one-shot)))]
      (is (= :group (:type watch)))
      (is (= #{:paths :exts :ignore} (into #{} (map :key) (:fields watch))))))

  (testing "every builtin kind is in the digest"
    (is (= #{:docker-compose :compose-services :process :external :quarkus-app :one-shot :k8s}
           (set (keys (schema/describe-kinds)))))))

(deftest validate-registry-closed-test
  (let [entry {:kind :quarkus-app :repo "r" :port 8080}]
    (testing "a well-formed entry passes, and the registry is handed back"
      (is (= {:a entry} (schema/validate-registry! {:a entry}))))

    (testing "an unknown key is a typo, not an extension - a registry entry is
              written by hand, and :workspce-dir would otherwise be ignored in
              silence while the component ran from the default workspace"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid registry entry for :a.*disallowed key"
                            (schema/validate-registry!
                             {:a (assoc entry :workspce-dir "/tmp")}))))))

(deftest k8s-kind-schema-test
  (let [entry {:kind :k8s :repo "manifests" :manifests ["m.yaml"] :workload "deployment/x"
               :context "local-k8s" :namespace "dev"
               :image-placeholder "team/x" :strip-affinity true
               :pull-secret {:name "regcred" :server "reg" :username "AWS" :password-cmd "echo t"}}]
    (testing "a well-formed :k8s registry entry passes, closed validation included"
      (is (= {:a entry} (schema/validate-registry! {:a entry}))))

    (testing ":context is required - the kind must never fall back to kubectl's own
              current-context"
      (is (thrown? clojure.lang.ExceptionInfo
                   (schema/validate-registry! {:a (dissoc entry :context)}))))))

(deftest argv-safe-values-test
  (let [valid? #(try (schema/validate-component! :c %) true (catch clojure.lang.ExceptionInfo _ false))
        k8s {:kind :k8s :repo "r" :manifests ["m.yaml"] :workload "deployment/x"
             :context "c" :namespace "n"}]
    (testing "given values a runtime preset may set, then real ones pass"
      (is (valid? {:kind :process :cmd "run" :branch "feature/abc-123_some_fix"}))
      (is (valid? {:kind :compose-services :repo "r" :services [:postgres :my_db.1] :exclude [:sftp]}))
      (is (valid? {:kind :docker-compose :repo "r" :profiles ["mailcatcher"]}))
      (is (valid? (assoc k8s :image "team/web/webui-prod:dff27479"
                         :image-registry "123456789012.dkr.ecr.eu-west-1.amazonaws.com"))))

    (testing "given one read as an option by git or compose, then it's refused"
      (is (not (valid? {:kind :process :cmd "run" :branch "--upload-pack=touch /tmp/x"})))
      (is (not (valid? {:kind :compose-services :repo "r" :services [:-v]})))
      (is (not (valid? {:kind :compose-services :repo "r" :exclude [(keyword "a b")]})))
      (is (not (valid? {:kind :docker-compose :repo "r" :profiles ["--project-directory=/"]}))))

    (testing "given an image carrying a newline, then it can't add YAML to the manifest"
      (is (not (valid? (assoc k8s :image "img:1\n  securityContext: {privileged: true}"))))
      (is (not (valid? (assoc k8s :image-registry "reg #x")))))))

(deftest process-without-repo-test
  (testing "a :process needs no checkout - a `kubectl port-forward` next to a :k8s
            component runs from nowhere in particular"
    (let [entry {:kind :process :cmd "kubectl --context c port-forward deployment/x 1:2"}]
      (is (= {:a entry} (schema/validate-registry! {:a entry}))))))

(deftest validate-catalog-closed-test
  (let [errors #(try (schema/validate-catalog! (merge {:components {} :presets {}} %)) nil
                     (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (testing "any key a kind declares, or a catalog-wide one, is a valid default"
      (is (nil? (errors {:name :acme
                         :defaults {:org "acme" :jdk-home "/jdk" :repo-url-template "https://x/{repo}"
                                    :allow-secret-contexts ["dev"] :allow-k8s-contexts ["dev"]}}))))
    (testing "a typo'd or mistyped default, or an unknown top-level key, is refused"
      (is (= {:defaults {:jdk-hom ["disallowed key"] :allow-secret-contexts ["invalid type"]}}
             (errors {:defaults {:jdk-hom "/jdk" :allow-secret-contexts "dev"}})))
      (is (= {:defaults. ["disallowed key"]} (errors {:defaults. {}}))))))

(deftest watch-needs-repo-test
  (testing "a resolved cfg watching without a :repo would scan ~/workspace whole"
    (is (= {:watch ["needs a :repo"]}
           (try (schema/validate-component! :ide {:kind :external :watch {:paths ["src"]}}) nil
                (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))))
    (is (some? (schema/validate-component! :ui {:kind :process :repo "ui" :cmd "x" :watch {:paths ["src"]}}))))
  (testing "a registry entry may still get its :repo from :defaults"
    (is (some? (schema/validate-registry! {:ui {:kind :process :cmd "x" :watch {:paths ["src"]}}})))))

(deftest describe-secret-env-test
  (testing ":secret-env is a map of refs, not of strings"
    (let [f (some #(when (= :secret-env (:key %)) %) (get (schema/describe-kinds) :process))]
      (is (= :map (:type f)))
      (is (= #{:context :namespace :secret :key} (into #{} (map :key) (get-in f [:of :fields])))))))

(deftest allow-k8s-contexts-names-test
  (testing "both context allowlists take k8s names only"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate-catalog! {:components {} :presets {} :defaults {:allow-k8s-contexts ["--context=prod"]}})))))
