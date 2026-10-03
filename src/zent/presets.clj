(ns zent.presets
  "Presets as EDN files - a preset is a `zent.compose/run` spec, which is
  already EDN, so one can be written without touching Clojure. One file per
  preset, named after it; `~/` strings expand against $HOME.

  Two sources: a catalog's own (reviewed with it - zent.catalog) and
  a user's, built at runtime from the CLI or MCP (load-files/save!/delete!),
  which may do less (check-runtime-overlay!)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [zent.compose :as compose]
            [zent.schema :as schema]))

(defn expand-home
  "`x` with every `~/`-prefixed string expanded against $HOME (EDN has no getenv)."
  [x]
  (walk/postwalk #(if (and (string? %) (str/starts-with? % "~/"))
                    (str (System/getenv "HOME") (subs % 1))
                    %)
                 x))

(defn read-spec
  "The overlay a preset's EDN text (a `compose/run` spec) describes."
  [text]
  (let [spec (edn/read-string text)]
    (when-not (vector? spec)
      (throw (ex-info "a preset is a vector of component names and override maps"
                      {:got spec})))
    (compose/run (expand-home spec))))

(defn check-name!
  "Refuses a preset name that isn't a plain file name: one becomes
  <dir>/<name>.edn, so `x/../../f` would write or delete outside `dir`."
  [preset-name]
  ;; the whole keyword, not (name k): (keyword "../x") has name "x"
  (let [text (if (keyword? preset-name) (subs (str preset-name) 1) (str preset-name))]
    (when-not (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" text)
      (throw (ex-info (format "invalid preset name %s - letters, digits, . _ - only" (pr-str text))
                      {:preset preset-name})))))

(defn load-files
  "{preset-name overlay} for every *.edn file directly under directory `dir`
  (a missing dir is just no presets). A file that doesn't read is skipped
  with a warning - one bad file mustn't take every other preset down."
  [dir]
  (into {}
        (keep (fn [^java.io.File f]
                (when (str/ends-with? (.getName f) ".edn")
                  (let [preset-name (keyword (str/replace (.getName f) #"\.edn$" ""))]
                    (try
                      (check-name! preset-name)
                      [preset-name (read-spec (slurp f))]
                      (catch Exception e
                        (binding [*out* *err*]
                          (println "ignoring preset" (.getPath f) "-" (or (ex-message e) (str e))))
                        nil))))))
        (some-> dir io/file .listFiles)))

(defn user-dir
  "Where `catalog-name`'s runtime-built presets live - per catalog, since a
  preset only resolves against the components it was written for."
  [catalog-name]
  (str (System/getenv "HOME") "/.config/zent/presets/" (name catalog-name)))

(defn- file [dir preset-name] (io/file dir (str (name preset-name) ".edn")))

(defn user-preset? [dir preset-name] (.exists (file dir preset-name)))

(defn validate!
  "Resolves `overlay` as `preset-name` against `catalog` (same checks as any
  apply: registry, kind schemas), plus the context allowlists a deploy
  would enforce - so a preset built by prompt is refused when saved, not
  halfway through starting. Returns the resolved components."
  [catalog preset-name overlay]
  (let [resolved (compose/resolve-preset (assoc-in catalog [:presets preset-name] overlay) preset-name)]
    (doseq [[k cfg] resolved]
      (schema/check-k8s-context! k cfg)
      (schema/check-secret-contexts! k cfg))
    resolved))

(defn check-runtime-overlay!
  "Refuses an overlay setting anything beyond schema/runtime-preset-keys -
  the rule for every preset not shipped with the catalog."
  [preset-name overlay]
  (doseq [[k over] overlay]
    (compose/refuse-overrides! preset-name k over schema/runtime-preset-keys
                               (str "a user preset may only set " (vec (sort schema/runtime-preset-keys))))))

(defn save!
  "Validates `text` against `catalog`, then writes it as a user preset.
  Refuses a name the catalog itself ships - a user preset never shadows it."
  [catalog dir preset-name text]
  (check-name! preset-name)
  (when (and (contains? (:presets catalog) preset-name) (not (user-preset? dir preset-name)))
    (throw (ex-info (format "%s is one of the catalog's own presets - pick another name" (name preset-name))
                    {:preset preset-name})))
  (let [overlay (read-spec text)]
    (check-runtime-overlay! preset-name overlay)
    (validate! catalog preset-name overlay))
  (let [f (file dir preset-name)]
    (io/make-parents f)
    (spit f text)))

(defn delete!
  "Removes a user preset; a catalog preset isn't this fn's to delete."
  [dir preset-name]
  (check-name! preset-name)
  (when-not (user-preset? dir preset-name)
    (throw (ex-info (format "%s isn't a user preset" (name preset-name)) {:preset preset-name})))
  (io/delete-file (file dir preset-name)))
