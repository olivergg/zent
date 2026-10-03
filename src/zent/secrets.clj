(ns zent.secrets
  "Env vars read from k8s Secrets, with the
  user's own kubectl rights - no elevation, values never logged, and masked
  by redact wherever zent serves output. kubectl runs without a shell (its
  args checked by schema's secret refs) and decoding
  happens in-process."
  (:require [clojure.string :as str]
            [zent.logs :as logs]
            [zent.schema :as schema]
            [zent.shell :as shell]))

(defn read-k8s-secret!
  "Reads `key` from k8s Secret `secret` in `namespace` on `context`, base64-
  decoded. Throws if kubectl fails or the value is empty - never \"\".
  --request-timeout: kubectl's default is none, and an unreachable context
  (no VPN) would otherwise stall for minutes."
  [context namespace secret key]
  (let [{:keys [exit out err]}
        (shell/sh! ["kubectl" "--context" context "-n" namespace "get" "secret" secret
                    ;; a dot in the key (tls.crt) would read as a nested field
                    "-o" (str "jsonpath={.data." (str/replace key "." "\\.") "}")
                    "--request-timeout=5s"])]
    (when-not (zero? exit)
      (throw (ex-info (str "read-k8s-secret!: kubectl failed for "
                            secret "." key
                            " (namespace=" namespace ", context=" context ")"
                            " - check your kubectl access to that context/namespace")
                       {:context context :namespace namespace :secret secret
                        :key key :exit exit :err err})))
    (let [value (try
                  (String. (.decode (java.util.Base64/getDecoder) (str/trim out)))
                  (catch Exception _ ""))]
      (if (str/blank? value)
        (throw (ex-info (str "read-k8s-secret!: empty/missing " secret "." key
                              " (namespace=" namespace ", context=" context ")"
                              " - check your kubectl access to that context/namespace")
                         {:context context :namespace namespace :secret secret :key key}))
        value))))

;; Values this process has read, memory only - what redact masks. A daemon
;; serving logs (zent.ui.server's /api/logs, the MCP `logs` tool through it)
;; must not hand an app's own echo of its credentials to whoever asks.
(defonce ^:private revealed (atom #{}))

;; Components whose secrets this process read - whose logs it can mask.
(defonce ^:private read-for (atom #{}))

(defn secret-env!
  "The env vars `cfg`'s :secret-env points at, read now - {} unless
  :use-secret-env. Refuses a context outside :allow-secret-contexts before
  any kubectl call. Only the var names and where they came from are printed."
  [name {:keys [secret-env use-secret-env] :as cfg}]
  (if-not use-secret-env
    {}
    (do (schema/check-secret-contexts! name cfg)
        (println (format "[%s] reading %s from k8s secrets" name (str/join ", " (keys secret-env))))
        (logs/mark-sensitive! name)
        (let [env (into {} (map (fn [[var {:keys [context namespace secret key]}]]
                                  (let [v (read-k8s-secret! context namespace secret key)]
                                    (swap! revealed conj v)
                                    [var v])))
                        secret-env)]
          ;; only once every value is known to redact
          (swap! read-for conj name)
          env))))

(defn maskable?
  "Whether redact can be trusted with `component`'s log: it holds no secret,
  or this process read them. False after a restart, for a log a previous
  daemon's run wrote with secrets."
  [component]
  (or (not (logs/sensitive? component)) (contains? @read-for component)))

(defn redact
  "`text` with every secret value this process has read masked - longest
  first: masking a short value first could cut through a longer one that
  contains it, leaving the rest of that one in clear."
  [text]
  (reduce #(str/replace %1 %2 "****") text (sort-by (comp - count) @revealed)))
