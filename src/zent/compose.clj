(ns zent.compose
  "Means of combination: merging
  component overlays into a resolved, validated component set.

  An overlay is {component-name {cfg-key value}} - the same shape whether
  it's a mode assignment, a reusable group, or a whole preset. There is no
  preset type: a preset *is* an overlay, so presets can be diffed, merged
  and select-keys'd as plain data without re-running any logic.

  This namespace knows nothing about any particular component - it works
  over whatever catalog it's handed (see zent.engine's docstring for the
  catalog shape)."
  (:require [zent.schema :as schema]))

(defn run
  "A preset as it reads: the components to run, some with overrides.

  Takes any mix of component names, override maps and groups of either -
  nested vectors are spliced, so a group is a plain vector of names that
  composes by being mentioned. A name turns its component on; a map applies
  to the name just before it:

    (run [:broker :broker-seed]         ; a group, on
         :api {:kind :external})        ; on, with overrides

  Returns the same overlay shape resolve-preset already takes - naming a
  component IS turning it on, so :mode never appears in a catalog, but it's
  still what comes out, and the engine is none the wiser."
  [& specs]
  (:overlay
   (reduce (fn [{:keys [overlay current] :as state} x]
             (cond
               (keyword? x)
               {:overlay (update overlay x merge {:mode :on}) :current x}

               ;; after the name it qualifies, so overrides win over the
               ;; :mode this just put there - `:a {:mode :off}` still works
               (map? x)
               (if current
                 (assoc state :overlay (update overlay current merge x))
                 (throw (ex-info "overrides with no component before them"
                                 {:overrides x})))

               :else
               (throw (ex-info "a preset takes component names, override maps, and groups of those"
                               {:got x :type (type x)}))))
           {:overlay {} :current nil}
           (flatten specs))))

(defn refuse-overrides!
  "Throws if `over` (a preset's overrides for component `k`) sets a key
  `allowed?` rejects - the shape of every preset restriction."
  [preset-name k over allowed? why]
  (when-let [ks (seq (remove allowed? (keys over)))]
    (throw (ex-info (format "preset %s sets %s on %s - %s" (name preset-name) (vec ks) k why)
                    {:preset preset-name :component k :keys (vec ks)}))))

(defn- refuse-unknown-refs!
  "Refuses a :deps/:related/:attached-to naming no catalog component: a dep
  outside the preset is skipped (zent.topo), so a typo'd one would let its
  dependent start without waiting."
  [k cfg known]
  (doseq [ref-key [:deps :related :attached-to]
          :let [v (get cfg ref-key)
                unknown (remove known (if (keyword? v) [v] v))]
          :when (seq unknown)]
    (throw (ex-info (format "%s: %s names unknown component(s) %s" k ref-key (vec unknown))
                    {:component k :key ref-key :unknown (vec unknown)}))))

(defn resolve-preset
  "Resolves `preset-name` in `catalog` to {component-name validated-cfg}.

  Each component's cfg is its catalog :defaults, then its registry entry,
  then the preset's overlay - later winning. Pure: no source resolution, no
  secrets read, no processes started, so this is what tests and a UI can
  call freely."
  [catalog preset-name]
  (let [{:keys [components presets defaults]} (schema/validate-catalog! catalog)
        ;; the registry is checked closed, before any merging: a typo'd key
        ;; in a hand-written entry should be named, not silently ignored
        _ (schema/validate-registry! components)
        overlay (or (get presets preset-name)
                    (throw (ex-info (str "unknown preset: " preset-name)
                                    {:preset preset-name :known (vec (keys presets))})))]
    (into {}
          (map (fn [[k over]]
                 (let [base (or (get components k)
                                (throw (ex-info (str "preset " preset-name
                                                     " references unknown component " k)
                                                {:preset preset-name :component k})))]
                   (refuse-overrides! preset-name k over (complement schema/catalog-only-keys)
                                      "only the catalog may")
                   (when (:secret-env base)
                     (refuse-overrides! preset-name k over schema/secret-safe-overrides
                                        (str "it reads secrets, only " (vec (sort schema/secret-safe-overrides))
                                             " may be set there")))
                   (let [cfg (schema/validate-component! k (update (merge defaults base over) :deps vec))]
                     (refuse-unknown-refs! k cfg (set (keys components)))
                     [k cfg]))))
          overlay)))
