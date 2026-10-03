(ns zent.k8s
  "Rendering and cluster prep for the :k8s kind (pre-built images): read
  a component's manifests, strip affinity/tolerations for a single-node
  cluster, swap an image placeholder for a real ref, inject the namespace,
  apply.

  The namespace goes through kustomize (`apply -k`), which also rewrites a
  hardcoded metadata.namespace - no YAML parser needed. The rendered dir
  lives in ~/.cache/zent/k8s/<component>/ so a later teardown deletes
  exactly what was applied."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [zent.commands :as commands]
            [zent.shell :as shell]))

(def ^:dynamic *dir*
  "Where rendered kustomizations live. Dynamic so tests can redirect it."
  (str (System/getenv "HOME") "/.cache/zent/k8s"))

(defn- indent-of [line] (- (count line) (count (str/triml line))))

(defn strip-affinity
  "Drops every `affinity:`/`tolerations:` block - a single-node cluster
  lacks the node labels they need, so the pod would sit Pending. Line/indent
  based, so the rest survives byte for byte. A `- ` item at the block's own
  indent stays in the block (valid YAML) instead of dangling."
  [text]
  (loop [[line & more :as lines] (str/split text #"\n" -1)
         skip-indent nil
         out []]
    (if (empty? lines)
      (str/join "\n" out)
      (let [stripped (str/triml line)
            indent (indent-of line)]
        (cond
          (or (str/starts-with? stripped "affinity:") (str/starts-with? stripped "tolerations:"))
          (recur more indent out)

          (and skip-indent
               (or (str/blank? stripped)
                   (> indent skip-indent)
                   (and (= indent skip-indent) (str/starts-with? stripped "- "))))
          (recur more skip-indent out)

          :else (recur more nil (conj out line)))))))

(defn image-ref
  "The full image ref to deploy: `image` under `registry` when there is one,
  `image` as-is otherwise."
  [{:keys [image image-registry]}]
  (if image-registry (str image-registry "/" image) image))

(defn render-manifest
  "One manifest's text as it should be applied - affinity stripped when
  asked, then the image placeholder replaced (a naive `.replace`)."
  [text {:keys [image-placeholder] :as cfg}]
  (cond-> text
    (:strip-affinity cfg) strip-affinity
    image-placeholder (str/replace image-placeholder (image-ref cfg))))

(defn kustomization
  "kustomization.yaml text for `files` in `namespace`."
  [namespace files]
  (str "namespace: " namespace "\n"
       "resources:\n"
       (apply str (map #(str "- " % "\n") files))))

(defn render!
  "Writes `component`'s rendered manifests plus their kustomization.yaml into a
  fresh dir and returns it. Manifests are read relative to `source-dir`
  (the component's resolved :repo); each is renamed by position so two
  manifests with the same basename can't collide."
  [component {:keys [manifests namespace] :as cfg} source-dir]
  (let [dir (str *dir* "/" (name component))
        files (map-indexed (fn [i path] [path (str i "-" (.getName (io/file path)))]) manifests)]
    (doseq [f (.listFiles (io/file dir))] (io/delete-file f true))
    (doseq [[path out] files]
      (let [target (str dir "/" out)]
        (io/make-parents target)
        (spit target (render-manifest (slurp (str source-dir "/" path)) cfg))))
    (spit (str dir "/kustomization.yaml") (kustomization namespace (map second files)))
    dir))

(defn ensure-namespace!
  "Creates `namespace` unless it already exists. Never deleted by zent: every
  component deployed into it shares it."
  [context namespace]
  (when-not (zero? (:exit (shell/sh! (commands/kubectl-get-namespace-cmd context namespace))))
    (shell/sh-or-throw! (commands/kubectl-create-namespace-cmd context namespace))))

(defn docker-config-json
  "A dockerconfigjson for one registry - the shape `kubectl create secret
  docker-registry` would build, written by hand so the password never has to
  go through argv."
  [server username password]
  (let [auth (.encodeToString (java.util.Base64/getEncoder) (.getBytes (str username ":" password)))]
    (str "{\"auths\":{\"" server "\":{\"auth\":\"" auth "\"}}}")))

(defn ensure-pull-secret!
  "(Re)creates the registry pull secret from a fresh :password-cmd token
  (ECR tokens expire). The token goes through a file in `dir`, removed
  straight after - never argv or logs. Shared by the namespace, so never
  deleted on teardown."
  [context namespace dir {:keys [name server username password-cmd]}]
  (let [{:keys [exit out]} (shell/sh! password-cmd)]
    (when-not (zero? exit)
      (throw (ex-info (format "pull secret %s: `%s` exited %d" name password-cmd exit)
                      {:cmd password-cmd :exit exit})))
    (let [file (str dir "/.dockerconfigjson")]
      (try
        (spit file (docker-config-json server username (str/trim out)))
        (shell/sh-or-throw! (commands/kubectl-delete-secret-cmd context namespace name))
        (shell/sh-or-throw! (commands/kubectl-create-registry-secret-cmd context namespace name file))
        (finally (io/delete-file file true))))))

(defn ready?
  "Whether `workload` has at least one ready replica - best-effort: any
  failure (cluster unreachable, workload missing) reads as not ready."
  [context namespace workload]
  (try
    (let [{:keys [exit out]} (shell/sh! (commands/kubectl-ready-replicas-cmd context namespace workload))]
      (and (zero? exit) (pos? (parse-long (or (not-empty (str/trim out)) "0")))))
    (catch Exception _ false)))
