(ns zent.secrets-test
  "read-k8s-secret! against a REAL k8s secret, named by ZENT_TEST_SECRET
  (context/namespace/secret/key), one safe to read with your own kubectl
  rights - read-only, the value only checked for presence, NEVER printed.
  Skipped when unset or unreachable, so CI and other machines don't break."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zent.secrets :as secrets]
            [zent.shell :as shell]))

(def ^:private target
  (when-let [s (System/getenv "ZENT_TEST_SECRET")]
    (zipmap [:context :ns :secret :key] (str/split s #"/"))))

(defn- read! [secret k] (secrets/read-k8s-secret! (:context target) (:ns target) secret k))

(def ^:private reachable
  "Probed once per run: off-VPN, each kubectl call burns ~15s of retries.
  `config get-contexts` touches no network, so a missing context skips at once."
  (delay
    (and target
         (zero? (:exit (shell/sh! (str "kubectl config get-contexts " (:context target)))))
         (try (some? (read! (:secret target) (:key target)))
              (catch Exception _ false)))))

(defmacro ^:private when-reachable [& body]
  `(if @reachable
     (do ~@body)
     (println "SKIP real-Secret test - ZENT_TEST_SECRET unset or unreachable")))

(deftest read-k8s-secret-real-test
  (testing "reads a real secret key, decoded, non-empty - value itself never asserted/logged"
    (when-reachable
      (let [value (read! (:secret target) (:key target))]
        (is (string? value))
        (is (pos? (count value)))))))

(deftest read-k8s-secret-missing-fails-loudly-test
  (testing "a missing key or secret throws instead of returning \"\""
    (when-reachable
      (is (thrown? Exception (read! (:secret target) "THIS_KEY_DOES_NOT_EXIST_XYZ")))
      (is (thrown? Exception (read! "definitely-does-not-exist-xyz" "foo"))))))

(def ^:private cfg
  {:use-secret-env true :allow-secret-contexts ["dev"]
   :secret-env {"CLIENT_ID" {:context "dev" :namespace "ns" :secret "s" :key "ID"}}})

(deftest secret-env-test
  (let [reads (atom [])]
    (with-redefs [secrets/read-k8s-secret! (fn [& args] (swap! reads conj args) "s3cr3t-value")]
      (testing "off unless the preset asks: no read at all"
        (is (= {} (secrets/secret-env! :svc (dissoc cfg :use-secret-env))))
        (is (empty? @reads)))

      (testing "on: each var read from where the catalog points, and the value
                is masked from then on"
        (is (= {"CLIENT_ID" "s3cr3t-value"} (secrets/secret-env! :svc cfg)))
        (is (= [["dev" "ns" "s" "ID"]] @reads))
        (is (= "token=**** ok" (secrets/redact "token=s3cr3t-value ok")))
        (is (secrets/maskable? :svc) "its log is marked, and this process can mask it"))

      (testing "a shorter value read later can't cut through a longer one"
        (with-redefs [secrets/read-k8s-secret! (constantly "s3cr")]
          (secrets/secret-env! :other cfg))
        (is (= "token=**** ok" (secrets/redact "token=s3cr3t-value ok"))))

      (testing "a context outside :allow-secret-contexts is refused before any read"
        (reset! reads [])
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"aren't in :allow-secret-contexts"
                              (secrets/secret-env! :svc (assoc cfg :allow-secret-contexts ["other"]))))
        (is (empty? @reads))))))
