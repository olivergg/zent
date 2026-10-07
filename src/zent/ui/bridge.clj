(ns zent.ui.bridge
  "Runs the engine while reporting into zent.ui.state. The dependency points
  this way on purpose: zent.engine never requires the UI."
  (:require [zent.engine :as engine]
            [zent.reload :as reload]
            [zent.ui.state :as state]))

(defn- reporting
  "Wraps a deploy fn so each component's start/success/failure lands in
  zent.ui.state."
  [deploy-fn]
  (fn [name cfg]
    (state/observe-deploy-start! name)
    (try
      (let [handle (deploy-fn name cfg)]
        (state/observe-deploy-ok! name cfg handle)
        handle)
      (catch Exception e
        (state/observe-deploy-failed! name e)
        (throw e)))))

(defn serve!
  "zent.engine/serve! - the daemon - wired to zent.ui.state by default: its
  control queue (the HTTP API's writes), and an observer for every event
  (deploys, handled commands, stops, adopted components, liveness). The
  catalog lives in state/catalog, so a preset registered at runtime applies.
  `zent reload-code` (zent.reload) reloads the :code-prefixes namespaces
  (default zent.*); :catalog-fn, if given, is re-read after a clean reload.
  Callbacks default to vars, not fn values, so a code reload reaches them."
  [catalog & {:keys [deploy-fn control on-handled on-stopping on-stop on-seeded on-check code-prefixes catalog-fn]
             :or {code-prefixes ["zent."]
                  deploy-fn #'engine/deploy-component!
                  control state/control
                  on-handled #'state/observe-handled!
                  on-stopping #'state/observe-stopping!
                  on-stop #'state/observe-manual-stop!
                  on-seeded #'state/observe-deploy-ok!
                  on-check #'state/observe-liveness-check!}
             :as opts}]
  (state/set-catalog! catalog)
  (engine/serve! catalog
                (assoc opts
                       :catalog-ref state/catalog
                       :deploy-fn (reporting deploy-fn)
                       :on-resolved #'state/observe-preset-resolved!
                       :reload-code #(let [result (reload/reload! code-prefixes)]
                                       ;; always observed: the CLI reads this result
                                       (state/observe-code-reload!
                                        (try (when (and catalog-fn (not (:failed result)))
                                               (state/set-catalog! (catalog-fn)))
                                             result
                                             (catch Exception e
                                               (assoc result :failed "the catalog" :error (ex-message e))))))
                       :control control
                       :planner state/planner
                       :on-handled on-handled
                       :on-stopping on-stopping
                       :on-stop on-stop
                       :on-seeded on-seeded
                       :on-check on-check
                       ;; a branch switched on disk shows without re-applying
                       :on-refresh #'state/refresh-sources!)))
