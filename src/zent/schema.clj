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
   [:url :string]])

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
  [[:deps {:optional true} [:vector :keyword]]
   [:mode {:optional true} Mode]
   [:readiness {:optional true} Readiness]
   [:watch {:optional true} Watch]
   [:branch {:optional true} git-ref]
   [:workspace-dir {:optional true} :string]
   [:org {:optional true} :string]
   ;; NOT :deps: doesn't order anything or gate readiness. A declared fact
   ;; (this component is functionally coupled to that one) for couplings
   ;; zent doesn't orchestrate or that are preset-specific, not a property
   ;; of the component itself.
   [:related {:optional true} [:vector :keyword]]
   ;; Never torn down implicitly - a preset switch, a stop-everything or
   ;; zent exiting leave it up; only an explicit stop of that component
   ;; reaches it (zent.engine/permanent?). For a shared dependency meant to
   ;; outlive any one run.
   [:permanent {:optional true} :boolean]
   ;; Not stopped by a preset switch that doesn't need it: left running idle
   ;; for the next preset that does (zent.engine/switch-preset!). Unlike
   ;; :permanent, a stop-everything or zent exiting still stops it.
   [:keep-warm {:optional true} :boolean]
   ;; Named URLs worth opening for this component. Declared, never derived: a readiness URL
   ;; is a health-check target, not a click target, and a :port alone
   ;; doesn't say which path is worth opening.
   [:links {:optional true} [:vector [:map [:url :string] [:label :string]]]]])

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
  [[:secret-env {:optional true} [:map-of :string [:map
                                                   [:context k8s-name]
                                                   [:namespace k8s-name]
                                                   [:secret k8s-name]
                                                   [:key secret-key]]]]
   [:use-secret-env {:optional true} :boolean]])

;; Env vars set to a checkout dir (zent.compose pins it, zent.kinds resolves it
;; at deploy): a :component's - its :branch worktree in the preset, else its
;; main clone - or a :repo's main clone, as it is on disk.
(def ^:private source-env
  [:source-env {:optional true} [:map-of :string [:and
                                                  [:map
                                                   [:component {:optional true} :keyword]
                                                   [:repo {:optional true} :string]
                                                   [:path {:optional true} :string]]
                                                  [:fn {:error/message "needs a :component or a :repo"}
                                                   #(boolean (or (:component %) (:repo %)))]]]])

;; On kinds running local builds or scripts: let them run alongside other
;; components' instead of one at a time (zent.kinds/local-commands-lock).
(def ^:private allow-parallel [:allow-parallel {:optional true} :boolean])

;; On kinds whose containers print elsewhere: false keeps a chatty one's
;; output out of its log (zent.kinds/with-follower).
(def ^:private follow-logs [:follow-logs {:optional true} :boolean])

(def builtin-kinds
  "Kinds the engine itself implements (zent.kinds), as extra map entries to
  splice onto shared-entries."
  {:docker-compose [[:repo :string]
                    [:build-cmd {:optional true} :string]
                    allow-parallel
                    [:compose-file {:optional true} :string]
                    [:project-name {:optional true} :string]
                    [:profiles {:optional true} [:vector compose-name]]
                    follow-logs]
   ;; Provided by something zent didn't start (an IDE-run service, e.g.).
   ;; Nothing is launched; the readiness probe IS the deploy, which is what
   ;; lets dependents still order after it. No entries of its own.
   :external []
   ;; A long-lived command, optionally in a checked-out repo and built first.
   ;; No :repo for one that needs no checkout (a `kubectl port-forward`).
   :process (into [[:repo {:optional true} :string]
                   [:cmd :string]
                   [:build-cmd {:optional true} :string]
                   [:env {:optional true} [:map-of :string :string]]
                   source-env
                   [:port {:optional true} pos-int?]
                   allow-parallel]
                  secret-entries)
   :quarkus-app (into [[:repo :string]
                       [:port pos-int?]
                       [:env {:optional true} [:map-of :string :string]]
                       source-env
                       [:jdk-home {:optional true} :string]
                       allow-parallel]
                      secret-entries)
   ;; :attached-to - display only: drawn as a strip under that component's
   ;; card (the one it prepares) rather than as a node of its own.
   :one-shot [[:repo :string]
              [:scripts [:vector :string]]
              [:attached-to {:optional true} :keyword]
              ;; display only: one line on what the scripts do
              [:description {:optional true} :string]
              [:pre-check {:optional true} Readiness]
              ;; never run by an apply, only by a reload (zent.engine/deploy-component!):
              ;; listed in a preset, it waits as "not run" - for a destructive one
              [:on-demand {:optional true} :boolean]
              ;; display only: the question the dashboard's Run button asks first
              [:confirm {:optional true} :string]
              [:env {:optional true} [:map-of :string :string]]
              source-env
              allow-parallel]
   ;; Some named services of a compose file, not the whole stack - for when
   ;; a repo's file carries both a webapp and the infra another component
   ;; wants alone. Separate from :docker-compose because the two tear down
   ;; differently (whole project vs named services - zent.lifecycle). :services is an allow-list;
   ;; omit it to take everything the file declares, :exclude to drop some.
   :compose-services [[:repo :string]
                      [:services {:optional true} [:vector compose-service]]
                      [:exclude {:optional true} [:vector compose-service]]
                      [:compose-file {:optional true} :string]
                      [:project-name {:optional true} :string]
                      follow-logs]
   ;; Pre-built manifests applied to a k8s cluster (zent.k8s). :manifests
   ;; are read relative to :repo. :context is always explicit and must also
   ;; appear in :allow-k8s-contexts: kubectl's own current-context may be prod.
   ;; :workload is a kubectl ref (deployment/x, statefulset/y) - rollout
   ;; status, readiness and logs all go through it. Reaching it locally is a
   ;; separate :process running `kubectl port-forward`.
   :k8s [[:repo :string]
         [:manifests [:vector :string]]
         [:workload k8s-name]
         [:context k8s-name]
         [:namespace k8s-name]
         [:allow-k8s-contexts {:optional true} [:vector k8s-name]]
         [:strip-affinity {:optional true} :boolean]
         [:image-placeholder {:optional true} :string]
         [:image {:optional true} image-ref]
         [:image-registry {:optional true} image-ref]
         [:pull-secret {:optional true} [:map
                                         [:name k8s-name]
                                         [:server k8s-name]
                                         [:username k8s-name]
                                         [:password-cmd :string]]]
         [:rollout-timeout {:optional true} pos-int?]
         follow-logs]})

(def catalog-only-keys
  "Keys no preset may set: they bound what presets can do."
  #{:allow-k8s-contexts :allow-secret-contexts :secret-env})

(def runtime-preset-keys
  "All a runtime preset (saved from the CLI or by prompt through MCP, never
  reviewed like the catalog's own) may set: which components, on which
  branch/image/port/context - nothing that decides what code runs (:cmd,
  :env, :scripts, :repo, :workspace-dir...), since that could reach around
  every allowlist here, e.g. a :cmd running kubectl against prod."
  #{:mode :kind :deps :branch :port :image :context :namespace
    :services :exclude :profiles :permanent :keep-warm :use-secret-env})

(def secret-safe-overrides
  "All any preset may set on a component that reads secrets: nothing that
  changes what runs with them, which could print them into a log."
  #{:mode :kind :deps :branch :use-secret-env})

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
         [:repo-url-template {:optional true} :string]
         [:allow-secret-contexts {:optional true} [:vector k8s-name]]]
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
   [:name {:optional true} :keyword]
   [:defaults {:optional true} Defaults]
   [:components [:map-of :keyword [:map [:kind :keyword]]]]
   [:presets [:map-of :keyword [:map-of :keyword [:map-of :keyword :any]]]]
   [:max-parallel {:optional true} pos-int?]])

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
  one is inert: nothing reads it.)"
  [cfg]
  (when (and (:watch cfg) (not (:repo cfg)))
    {:watch ["needs a :repo"]}))

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

(defn- describe-type
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
    {:type :group :fields (mapv describe-field (rest form))}
    :else {:type :any}))

(defn- describe-field
  "One malli map entry - [k], [k schema], [k props], or [k props schema] -
  normalized to {:key :required? & (describe-type schema)}."
  [[k a b]]
  (let [[props schema] (if (map? a) [a b] [nil a])]
    (merge {:key k :required (not (:optional props))}
           (describe-type schema))))

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
