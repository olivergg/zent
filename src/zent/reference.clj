(ns zent.reference
  "docs/reference.md, generated from zent.schema: what a catalog, a component
  of each kind and a preset may hold. `jolt run -m zent.reference >
  docs/reference.md`; zent.reference-test fails when the file is stale."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]
            [zent.schema :as schema]))

(defn- type-str [{:keys [type values of]}]
  (case type
    :int "int"
    :enum (str/join ", " values)
    :list (str "list of " (type-str of))
    :string-map "{string string}"
    (:map :group) "map"
    (name type)))

(defn- md [s]
  (-> s (str/replace "|" "\\|") (str/replace "<" "\\<")))

(defn- table [fields]
  (str/join "\n" (into ["| Key | Type | Required | What |" "|---|---|---|---|"]
                       (for [{:keys [key required description] :as f} fields]
                         (format "| `%s` | %s | %s | %s |" key (md (type-str f))
                                 (if required "yes" "") (md (or description "")))))))

(defn- fields [malli-map] (:fields (schema/describe-type (m/form (m/schema malli-map)))))

(defn- keys-str [ks] (str/join " " (map #(str "`" % "`") (sort ks))))

(defn markdown []
  (let [kinds (sort-by key (schema/describe-kinds))
        component-keys (into #{} (comp (mapcat val) (map :key)) kinds)
        shared (apply set/intersection (map #(set (map :key (val %))) kinds))]
    (str/join
     "\n\n"
     (concat
      ["# Reference"
       "Generated from `src/zent/schema.clj` - don't edit: `jolt run -m zent.reference > docs/reference.md`. The words: `docs/glossary.md`."
       "## A catalog (`catalog.edn`)"
       (table (fields schema/Catalog))
       "## `:defaults`"
       "Any component key below, merged under every component, and these, catalog-wide:"
       (table (remove (comp component-keys :key) (fields schema/Defaults)))
       "## Every component"
       "Whatever its `:kind`:"
       (table (filter (comp shared :key) (val (first kinds))))]
      (mapcat (fn [[kind fs]]
                [(str "## `:kind " kind "`")
                 (schema/kind-descriptions kind)
                 (if-let [own (seq (remove (comp shared :key) fs))] (table own) "No keys of its own.")])
              kinds)
      ["## In a preset"
       (str "A preset names components, each with keys overriding its catalog entry:\n\n"
            "- a repo preset (`presets/<name>.edn`, reviewed): any key but " (keys-str schema/catalog-only-keys) ";\n"
            "- your preset (`zent preset save`, the MCP's `save_preset`): only " (keys-str schema/runtime-preset-keys) ";\n"
            "- on a component with a `:secret-env`, either one: only " (keys-str schema/secret-safe-overrides) ".")]))))

(defn -main [] (println (markdown)))
