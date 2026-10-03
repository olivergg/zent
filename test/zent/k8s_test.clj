(ns zent.k8s-test
  "zent.k8s's rendering and cluster prep - no cluster ever reached: rendering
  is pure/filesystem, kubectl calls go through a stubbed zent.shell/sh!."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zent.k8s :as k8s]
            [zent.shell :as shell]))

(def ^:private real-manifest "test/fixtures/k8s/webui.yaml")

(defn- subsequence?
  "Whether `xs` appear in `ys` in the same order (gaps allowed)."
  [xs ys]
  (loop [xs (seq xs) ys (seq ys)]
    (cond (empty? xs) true
          (empty? ys) false
          (= (first xs) (first ys)) (recur (rest xs) (rest ys))
          :else (recur xs (rest ys)))))

(defn- tmp-dir []
  (str (System/getProperty "java.io.tmpdir") "/zent-k8s-test-" (System/currentTimeMillis)))

(deftest strip-affinity-real-manifest-test
  (let [text (slurp real-manifest)
        out (k8s/strip-affinity text)
        lines (str/split out #"\n" -1)]
    (testing "every affinity/tolerations block is gone"
      (is (str/includes? text "affinity:"))
      (is (not (str/includes? out "affinity:")))
      (is (not (str/includes? out "tolerations:")))
      (is (not (str/includes? out "back-spot"))))

    (testing "everything else survives byte for byte, in order"
      (is (subsequence? lines (str/split text #"\n" -1)))
      (is (str/includes? out "imagePullSecrets:"))
      (is (str/includes? out "containerPort: 5173"))
      (is (str/includes? out "kind: Service")))))

(deftest strip-affinity-test
  (testing "a nested block goes, siblings after it stay"
    (is (= "spec:\n  containers:\n  - name: a"
           (k8s/strip-affinity "spec:\n  affinity:\n    nodeAffinity:\n      x: y\n\n  containers:\n  - name: a"))))

  (testing "a sequence at its key's own indent still belongs to the key"
    (is (= "spec:\n  containers: []"
           (k8s/strip-affinity "spec:\n  tolerations:\n  - key: node.type\n    effect: NoSchedule\n  containers: []"))))

  (testing "text with nothing to strip is untouched, trailing newline included"
    (is (= "a: 1\nb: 2\n" (k8s/strip-affinity "a: 1\nb: 2\n")))))

(deftest render-manifest-test
  (let [text "spec:\n  affinity:\n    x: y\n  image: team/webui\n"]
    (testing "stripped when asked, placeholder swapped for registry/image"
      (is (= "spec:\n  image: reg.example/team/app:abc\n"
             (k8s/render-manifest text {:strip-affinity true
                                        :image-placeholder "team/webui"
                                        :image "team/app:abc"
                                        :image-registry "reg.example"}))))

    (testing "no registry: the image is used as-is; nothing stripped unless asked"
      (is (= "spec:\n  affinity:\n    x: y\n  image: team/app:abc\n"
             (k8s/render-manifest text {:image-placeholder "team/webui" :image "team/app:abc"}))))

    (testing "no placeholder: text passes through"
      (is (= text (k8s/render-manifest text {}))))))

(deftest kustomization-test
  (is (= "namespace: dev\nresources:\n- 0-a.yaml\n- 1-b.yaml\n"
         (k8s/kustomization "dev" ["0-a.yaml" "1-b.yaml"]))))

(deftest render!-test
  (let [src (tmp-dir) out (tmp-dir)]
    (spit (io/file (doto (io/file src "m") .mkdirs) "app.yaml") "image: PH\n")
    (spit (io/file src "m" "secret.yaml") "kind: Secret\n")
    (binding [k8s/*dir* out]
      (let [cfg {:manifests ["m/app.yaml" "m/secret.yaml"] :namespace "dev"
                 :image-placeholder "PH" :image "img:1"}]
        (spit (io/file (doto (io/file out "svc") .mkdirs) "stale.yaml") "old")
        (let [dir (k8s/render! :svc cfg src)]
          (testing "rendered files are named by position, into the component's own dir"
            (is (= (str out "/svc") dir))
            (is (= "image: img:1\n" (slurp (io/file dir "0-app.yaml"))))
            (is (= "kind: Secret\n" (slurp (io/file dir "1-secret.yaml")))))

          (testing "the kustomization lists exactly those files, in the namespace"
            (is (= "namespace: dev\nresources:\n- 0-app.yaml\n- 1-secret.yaml\n"
                   (slurp (io/file dir "kustomization.yaml")))))

          (testing "a re-render clears whatever a previous render left behind"
            (is (not (.exists (io/file dir "stale.yaml"))))))))))

(deftest docker-config-json-test
  (let [json (k8s/docker-config-json "reg.example" "AWS" "s3cr3t")
        auth (second (re-find #"\"auth\":\"([^\"]+)\"" json))]
    (is (str/includes? json "\"reg.example\""))
    (is (= "AWS:s3cr3t" (String. (.decode (java.util.Base64/getDecoder) auth))))))

(deftest ready?-test
  (let [ready-with (fn [result]
                     (with-redefs [shell/sh! (fn [& _] (if (instance? Exception result) (throw result) result))]
                       (k8s/ready? "ctx" "ns" "deployment/x")))]
    (testing "a ready replica count >= 1 is ready"
      (is (true? (ready-with {:exit 0 :out "2"}))))
    (testing "no ready replicas yet (empty jsonpath) is not"
      (is (false? (ready-with {:exit 0 :out ""}))))
    (testing "kubectl failing (unreachable, workload missing) is not ready, not a throw"
      (is (false? (ready-with {:exit 1 :out ""})))
      (is (false? (ready-with (ex-info "boom" {})))))))
