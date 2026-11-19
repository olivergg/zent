(ns zent.schema
  "Malli validation: a catalog's shape (its root, :defaults), its registry
  entries, and resolved component configs, dispatched on :kind.

  The kinds are builtin-kinds, implemented in zent.kinds. A kind is declared
  as just its extra map entries - the shared keys below are spliced in."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def Mode
  "Whether a component takes part in this run at all - *how* is its :kind
  (:external included). Absent means :off."
  [:enum :on :off])

(def Readiness
  "A readiness/pre-check spec (zent.probe/wait-ready!), :readiness being
  waited on after any kind's deploy (zent.engine/deploy-component!). Closed
  on :type so a typo like :http-get fails at resolve time rather than at
  deploy time, halfway through bringing a stack up."
  [:map
   [:type [:enum :http-poll]]
   [:url :string]
   ;; how long to wait before failing the deploy (default 60): an app built then started on
   ;; deploy can take minutes
   [:timeout-s {:optional true} pos-int?]])

(def Watch
  "Which files, relative to the component's resolved source dir, should
  trigger a re-apply (zent.watch). Absent = never watched."
  [:map
   [:paths [:vector :string]]
   [:exts {:optional true} [:vector :string]]
   [:ignore {:optional true} [:vector :string]]])

;; Values a runtime preset (CLI, MCP) may set that reach git or docker compose
;; as argv elements, or a manifest's text: no leading `-` read as an option
;; (`:branch "--upload-pack=..."`, `:services [:-v]`), no whitespace or newline
;; (an `:image` with a newline adds YAML to the pod).
(def ^:private git-ref [:re #"^[A-Za-z0-9_][A-Za-z0-9._/-]*$"])
(def ^:private compose-name-re #"^[A-Za-z0-9][A-Za-z0-9._-]*$")
(def ^:private compose-name [:re compose-name-re])
(def ^:private compose-service
  [:and :keyword [:fn {:error/message "should be a compose service name"}
                  #(boolean (re-matches compose-name-re (name %)))]])
(def ^:private image-ref [:re #"^[A-Za-z0-9][A-Za-z0-9._/:@-]*$"])

(def ^:private shared-entries
  "Entries every kind gets, whatever it is - the engine handles all of these
  generically (zent.engine/deploy-component!, zent.compose, zent.source)."
  [[:deps {:optional true :description "Components it starts after, each once ready (its :readiness)."}
    [:vector :keyword]]
   [:mode {:optional true :description "Whether it takes part in the run. Naming it in a preset sets :on."}
    Mode]
   [:readiness {:optional true :description "What \"ready\" means: the probe an apply waits on after deploying it (:type :http-poll, :url, :timeout-s - 60 by default)."}
    Readiness]
   [:watch {:optional true :description "Files whose change redeploys it: :paths (relative to its checkout), :exts, :ignore. Not for a component running from zent's worktree."}
    Watch]
   [:branch {:optional true :description "The branch the preset wants it on; :from says which checkout runs it. Without one, its main worktree runs as it is."}
    git-ref]
   [:from {:optional true :description "With :branch, which checkout runs it (docs/glossary.md): :origin-worktree (the default - zent's worktree at origin/<branch>, what's pushed), :local-worktree (that worktree at your local <branch>, what's committed) or :main-worktree (your clone as it is, refused unless on <branch>)."}
    [:enum :origin-worktree :local-worktree :main-worktree]]
   [:workspace-dir {:optional true :description "Where its main worktree lives: <workspace-dir>/<repo>, ~/workspace by default."}
    :string]
   [:org {:optional true :description "The GitHub organisation of its repo, for the git clone command zent prints when the repo is missing."}
    :string]
   ;; NOT :deps: doesn't order anything or gate readiness
   [:related {:optional true :description "Components it's coupled to, shown in the dashboard - no start order, no readiness wait (that's :deps)."}
    [:vector :keyword]]
   [:permanent {:optional true :description "Never stopped implicitly: a preset switch, a stop-everything or zent exiting leave it up; only a stop naming it does. For a shared dependency meant to outlive any one run."}
    :boolean]
   [:keep-warm {:optional true :description "Left running, warm, by a preset switch that doesn't need it, for the next one that does; a stop-everything or zent exiting still stop it."}
    :boolean]
   ;; declared, never derived: a readiness URL is a health-check target, a :port alone says no path
   [:links {:optional true :description "URLs worth opening, each {:url :label}: the card's links."}
    [:vector [:map [:url :string] [:label :string]]]]])

;; Passed to kubectl as argv elements and, for :key, into a jsonpath - so no
;; braces or quotes, and no leading `-` kubectl could read as a flag (a
;; namespace of "--context=prod" retargeting the command).
(def ^:private k8s-name [:re #"^[A-Za-z0-9][A-Za-z0-9._:/@-]*$"])
(def ^:private secret-key [:re #"^[A-Za-z0-9._-]+$"])

(def ^:private secret-entries
  "Env vars read from k8s Secrets at deploy time (zent.secrets/secret-env!),
  on kinds that take an :env. :secret-env is the catalog's pointer - where
  to read - and only :use-secret-env (off by default) is a preset's to set;
  see catalog-only-keys/secret-safe-overrides."
  [[:secret-env {:optional true :description "Env vars read from a k8s Secret at deploy time, {VAR {:context :namespace :secret :key}}: where to read, the catalog's only. Off until :use-secret-env."}
    [:map-of :string [:map
                      [:context k8s-name]
                      [:namespace k8s-name]
                      [:secret k8s-name]
                      [:key secret-key]]]]
   [:use-secret-env {:optional true :description "Read its :secret-env (false by default) - from a context the catalog's :allow-secret-contexts lists."}
    :boolean]])

;; zent.compose pins it, zent.kinds resolves it at deploy
(def ^:private source-env
  [:source-env {:optional true :description "Env vars set to a checkout dir, {VAR {:component | :repo, :path}}: a :component's, as the preset runs it (its worktree, else its main worktree), or a :repo's main worktree, as it is on disk."}
   [:map-of :string [:and
                     [:map
                      [:component {:optional true} :keyword]
                      [:repo {:optional true} :string]
                      [:path {:optional true} :string]]
                     [:fn {:error/message "needs a :component or a :repo"}
                      #(boolean (or (:component %) (:repo %)))]]]])

;; zent.kinds/local-commands-lock
(def ^:private allow-parallel
  [:allow-parallel {:optional true :description "Its local build or scripts run alongside other components' instead of one at a time."}
   :boolean])

;; zent.kinds/with-follower
(def ^:private follow-logs
  [:follow-logs {:optional true :description "false keeps its containers' output out of its log (a chatty one's)."}
   :boolean])

(def builtin-kinds
  "Kinds the engine itself implements (zent.kinds), as extra map entries to
  splice onto shared-entries."
  (let [repo [:repo {:description "Its repo's dir name under :workspace-dir: what runs from its main worktree, or zent's worktree of it."} :string]
        env [:env {:optional true :description "Env vars for its build and run, {\"VAR\" \"value\"}."} [:map-of :string :string]]
        build-cmd [:build-cmd {:optional true :description "A command run in its checkout before it starts (a build)."} :string]
        compose-file [:compose-file {:optional true :description "The compose file, relative to its checkout (compose's default file when absent)."} :string]
        project-name [:project-name {:optional true :description "The compose project name (compose's default when absent)."} :string]]
    {:docker-compose [repo build-cmd allow-parallel compose-file project-name
                      [:profiles {:optional true :description "Compose profiles to enable."} [:vector compose-name]]
                      follow-logs]
     :external []
     :process (into [(assoc-in repo [1 :optional] true)
                     [:cmd {:description "The command it runs, in its checkout (or zent's dir without :repo), until stopped."} :string]
                     build-cmd env source-env
                     [:port {:optional true :description "The port it listens on: shown on its card and in its log."} pos-int?]
                     allow-parallel]
                    secret-entries)
     :quarkus-app (into [repo
                         [:port {:description "Its HTTP port: QUARKUS_HTTP_PORT."} pos-int?]
                         env source-env
                         [:jdk-home {:optional true :description "The JDK it's built (mvn package) and run with: JAVA_HOME and PATH."} :string]
                         allow-parallel]
                        secret-entries)
     :one-shot (into [repo
                      [:scripts {:description "Commands run one after the other in its checkout; the first failing one fails it."} [:vector :string]]
                      [:attached-to {:optional true :description "Display only: drawn as a strip under that component's card (the one it prepares)."} :keyword]
                      [:description {:optional true :description "Display only: one line on what its scripts do."} :string]
                      [:pre-check {:optional true :description "A probe waited on before its scripts run, as :readiness is after."} Readiness]
                      [:on-demand {:optional true :description "Never run by an apply, only by a reload (the card's Run): listed in a preset, it waits \"on demand\" - for a destructive one."} :boolean]
                      [:confirm {:optional true :description "Display only: the question the dashboard's Run button asks first."} :string]
                      env source-env allow-parallel]
                     secret-entries)
     :compose-services [repo
                        [:services {:optional true :description "The services to run, an allow-list: all the file declares when absent."} [:vector compose-service]]
                        [:exclude {:optional true :description "Services to drop from the file's."} [:vector compose-service]]
                        compose-file project-name follow-logs]
     ;; :context also in :allow-k8s-contexts: kubectl's own current-context may be prod
     :k8s [repo
           [:manifests {:description "Manifest files, relative to its checkout."} [:vector :string]]
           [:workload {:description "A kubectl ref (deployment/x, statefulset/y): its rollout, readiness and logs."} k8s-name]
           [:context {:description "The kubectl context, always explicit - one :allow-k8s-contexts lists."} k8s-name]
           [:namespace {:description "The namespace it's applied to."} k8s-name]
           [:allow-k8s-contexts {:optional true :description "The contexts a :context may name: the catalog's only."} [:vector k8s-name]]
           [:strip-affinity {:optional true :description "Drop affinity and tolerations blocks: a one-node cluster lacks their node labels."} :boolean]
           [:image-placeholder {:optional true :description "Text in the manifests replaced by the image."} :string]
           [:image {:optional true :description "The image the placeholder becomes."} image-ref]
           [:image-registry {:optional true :description "The registry the image is pulled from."} image-ref]
           [:pull-secret {:optional true :description "The image pull secret it creates, {:name :server :username :password-cmd}."}
            [:map
             [:name k8s-name]
             [:server k8s-name]
             [:username k8s-name]
             [:password-cmd :string]]]
           [:rollout-timeout {:optional true :description "Seconds to wait for its rollout."} pos-int?]
           follow-logs]}))

(def kind-descriptions
  "What each builtin kind is, for docs/reference.md."
  {:docker-compose "A whole compose file: up --wait, down."
   :compose-services "Some services of a compose file, not the whole stack - for a file that carries both a webapp and the infra another component wants alone; torn down by service, not by project."
   :external "Provided by something zent didn't start (an IDE-run service): nothing is launched, the :readiness probe is its deploy - which lets dependents still start after it."
   :process "A long-lived command, in a checked-out repo and built first, or none (a kubectl port-forward); tracked by its pid."
   :quarkus-app "A Quarkus app: mvn package, then target/quarkus-app/quarkus-run.jar."
   :one-shot "Scripts run once, ending done or failed (a seed, a replay, a purge)."
   :k8s "Pre-built manifests applied to a k8s cluster - not implemented yet. Reaching it locally is a separate :process running kubectl port-forward."})

(def catalog-only-keys
  "Keys no preset may set: they bound what presets can do."
  #{:allow-k8s-contexts :allow-secret-contexts :secret-env})

(def runtime-preset-keys
  "All a runtime preset (saved from the CLI or by prompt through MCP, never
  reviewed like the catalog's own) may set: which components, on which
  branch/image/port/context - nothing that decides what code runs (:cmd,
  :env, :scripts, :repo, :workspace-dir...), since that could reach around
  every allowlist here, e.g. a :cmd running kubectl against prod."
  #{:mode :kind :deps :branch :from :port :image :context :namespace
    :services :exclude :profiles :permanent :keep-warm :use-secret-env})

(def secret-safe-overrides
  "All any preset may set on a component that reads secrets: nothing that
  changes what runs with them, which could print them into a log."
  #{:mode :kind :deps :branch :from :use-secret-env})

(defn check-secret-contexts!
  "Refuses reading secrets (:use-secret-env) from a context the catalog's
  :allow-secret-contexts doesn't list - a list apart from
  :allow-k8s-contexts: reading a Secret isn't deploying there."
  [name {:keys [secret-env use-secret-env allow-secret-contexts]}]
  (when use-secret-env
    (doseq [{:keys [context]} (vals secret-env)
            :when (not (some #{context} allow-secret-contexts))]
      (throw (ex-info (format "%s: secrets from context %s aren't in :allow-secret-contexts %s - refusing"
                              name context (vec allow-secret-contexts))
                      {:component name :context context :allowed allow-secret-contexts})))))

(defn check-k8s-context!
  "Refuses a resolved cfg whose :context the catalog never allowed (the
  kubeconfig may well hold a prod context). Only
  cfgs carrying a :context are concerned."
  [name {:keys [context allow-k8s-contexts]}]
  (when (and context (not (some #{context} allow-k8s-contexts)))
    (throw (ex-info (format "%s: k8s context %s isn't in :allow-k8s-contexts %s - refusing"
                            name context (vec allow-k8s-contexts))
                    {:component name :context context :allowed allow-k8s-contexts}))))

(def Defaults
  "A catalog's :defaults, merged under every component: any key some kind
  declares (its first declaration's type), plus the catalog-wide ones.
  Closed, like the registry: a typo'd default would silently apply to none."
  (into [:map {:closed true}
         [:repo-url-template {:optional true :description "A repo's web URL, with {org} and {repo} replaced by a component's :org and :repo: the dashboard's link to it."}
          :string]
         [:allow-secret-contexts {:optional true :description "The k8s contexts a :secret-env may read from: the catalog's only."}
          [:vector k8s-name]]]
        (map (fn [[k a b]] (if (map? a) [k (assoc a :optional true) b] [k {:optional true} a])))
        (->> (concat shared-entries (apply concat (vals builtin-kinds)))
             (group-by first)
             vals
             (map first))))

(def Catalog
  "A catalog's own shape, checked first so a malformed one is one clear
  error. Component cfgs only need :kind here - per-kind validation happens
  in resolve-preset, after defaults and overlay are merged."
  [:map {:closed true}
   ;; required, but by zent.catalog/load-dir, which can name the file
   [:name {:optional true :description "The catalog's name, required: it keys your saved presets (~/.config/zent/presets/<name>/)."}
    :keyword]
   [:defaults {:optional true :description "Keys merged under every component (below)."}
    Defaults]
   [:components {:description "The components, {name {:kind ... & its keys}}."}
    [:map-of :keyword [:map [:kind :keyword]]]]
   [:presets {:description "Not written in catalog.edn: read from presets/<name>.edn."}
    [:map-of :keyword [:map-of :keyword [:map-of :keyword :any]]]]
   [:checks {:optional true :description "Not written in catalog.edn: found under checks/<preset>/."}
    [:map-of :keyword [:vector [:map [:name :keyword] [:ns :symbol]]]]]
   [:dir {:optional true :description "Not written in catalog.edn: the dir it was loaded from."}
    :string]
   [:max-parallel {:optional true :description "Components deployed at once (3 by default)."}
    pos-int?]])

(defn validate-catalog!
  "Validates `catalog` against Catalog, throwing ex-info with a humanized
  report if invalid. Returns it unchanged when valid."
  [catalog]
  (if (m/validate Catalog catalog)
    catalog
    (let [errors (me/humanize (m/explain Catalog catalog))]
      (throw (ex-info (str "Invalid catalog: " errors)
                      {:errors errors})))))

(def ^:private component-schema
  "The :multi schema over builtin-kinds."
  (into [:multi {:dispatch :kind}]
        (map (fn [[kind entries]]
               [kind (into [:map [:kind [:= kind]]] (concat shared-entries entries))]))
        builtin-kinds))

(def ^:private registry-schema
  "component-schema, but CLOSED.

  Closed only here: a hand-written registry entry with a typo'd key
  (`:workspce-dir`) should fail loudly, not run from the default workspace
  in silence. A *resolved* cfg legitimately carries extras (the catalog's
  :defaults, leftovers from a preset's :kind swap - see
  docs/resource-kinds.md §3), so closing that one too would cost more than
  it catches."
  (into [:multi {:dispatch :kind}]
        (map (fn [[kind extra]]
               [kind (into [:map {:closed true} [:kind [:= kind]]]
                           (concat shared-entries extra))]))
        builtin-kinds))

(defn- repo-errors
  "A :watch without a :repo would resolve to ~/workspace itself
  (zent.source/checkout-dir), scanning every repo there. (A :branch without
  one is inert: nothing reads it.) A :from means nothing without a :branch."
  [cfg]
  (not-empty (cond-> {}
               (and (:watch cfg) (not (:repo cfg))) (assoc :watch ["needs a :repo"])
               ;; on the resolved cfg: a catalog entry may set :from, its presets the :branch
               (and (:from cfg) (not (:branch cfg))) (assoc :from ["needs a :branch"]))))

(defn- check!
  "Throws on `cfg` failing `schema`, then on `more-errors` of it, if given."
  [schema what name cfg & [more-errors]]
  (when-let [errors (if (m/validate schema cfg)
                      (when more-errors (more-errors cfg))
                      (me/humanize (m/explain schema cfg)))]
    (throw (ex-info (format "Invalid %s for %s: %s" what name errors)
                    {:component name :cfg cfg :errors errors}))))

(defn validate-registry!
  "Every registry entry against its own kind, closed. Returns `components`
  unchanged when they all pass."
  [components]
  (doseq [[name cfg] components]
    (check! registry-schema "registry entry" name cfg))
  components)

(defn validate-component!
  "`cfg` against its kind, throwing ex-info with a humanized report if invalid."
  [name cfg]
  ;; on the resolved cfg only: a :repo may come from :defaults
  (check! component-schema "component config" name cfg repo-errors)
  cfg)

;; ---------------------------------------------------------------------------
;; schema -> field digest, for anything describing kinds to a reader
;; (/api/schema, MCP describe_catalog)
;; ---------------------------------------------------------------------------

(declare describe-field)

(defn describe-type
  "A malli schema form -> {:type ...}, walked as plain data: the forms here
  are always our own literal vectors."
  [form]
  (cond
    (= form :string) {:type :string}
    (= form :boolean) {:type :boolean}
    (= form :keyword) {:type :keyword}
    (= form 'pos-int?) {:type :int}
    (and (vector? form) (= (first form) :enum)) {:type :enum :values (vec (rest form))}
    ;; the argv-safe refinements above: their base type is what a reader needs
    (and (vector? form) (= (first form) :re)) {:type :string}
    (and (vector? form) (= (first form) :and)) (describe-type (second form))
    (and (vector? form) (= (first form) :vector)) {:type :list :of (describe-type (second form))}
    ;; only string keys show up in practice: :env (string values),
    ;; :secret-env/:source-env (a map each), so this isn't a general [:map-of k v] renderer
    (and (vector? form) (= (first form) :map-of))
    (let [v (describe-type (nth form 2))]
      (if (= :string (:type v)) {:type :string-map} {:type :map :of v}))
    (and (vector? form) (= (first form) :map))
    {:type :group :fields (mapv describe-field (remove map? (rest form)))}
    :else {:type :any}))

(defn- describe-field
  "One malli map entry - [k], [k schema], [k props], or [k props schema] -
  normalized to {:key :required? :description? & (describe-type schema)}."
  [[k a b]]
  (let [[props schema] (if (map? a) [a b] [nil a])]
    (cond-> (merge {:key k :required (not (:optional props))}
                   (describe-type schema))
      (:description props) (assoc :description (:description props)))))

(defn describe-kinds
  "{kind [field-descriptor...]} for every kind, excluding :kind itself -
  generated, so a new kind describes itself."
  []
  (into {}
        (map (fn [[kind child]]
               ;; :multi entries wrap each branch in a :malli.core/val schema
               ;; (an implementation detail of dispatch, not part of the
               ;; shape we declared) - unwrap it to reach the actual :map form.
               (let [raw (m/form child)
                     [_map & fields] (cond-> raw (= (first raw) :malli.core/val) second)]
                 [kind (mapv describe-field (remove #(= (first %) :kind) fields))])))
        (m/entries component-schema {:naked-keys true})))
