(ns zent.main
  "`zent <verb>` against the catalog repo bin/zent found ($ZENT_CATALOG,
  zent.catalog)."
  (:require [zent.catalog :as catalog]
            [zent.cli :as cli]))

(defn -main [& args]
  (let [dir (or (System/getenv "ZENT_CATALOG") (System/getProperty "user.dir"))]
    ;; a fn, re-read on each use: CLI verbs see an edited catalog.edn at once,
    ;; a running daemon on its next reload-code
    (cli/main {:catalog #(catalog/load-dir dir)
               :name (:name (catalog/load-dir dir))
               ;; set by bin/zent; else whatever `zent` is on PATH
               :bin (or (System/getenv "ZENT_BIN") "zent")}
              args)
    ;; sh's stderr reader is a future: its pool would hold the process ~60s
    ;; after the verb is done (jolt >= 0.8.16, as the JVM). serve never returns.
    (shutdown-agents)))
