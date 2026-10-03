#!/usr/bin/env jolt
;; Runs the project's test namespaces.
;; Add a require+symbol here when a new *_test.clj file is added - a generic
;; directory-walker isn't earned for a handful of namespaces.
(require '[clojure.test :as t]
         '[zent.schema-test]
         '[zent.compose-test]
         '[zent.presets-test]
         '[zent.catalog-test]
         '[zent.shell-test]
         '[zent.commands-test]
         '[zent.kinds-test]
         '[zent.topo-test]
         '[zent.lifecycle-test]
         '[zent.source-test]
         '[zent.logs-test]
         '[zent.session-test]
         '[zent.watch-test]
         '[zent.engine-test]
         '[zent.secrets-test]
         '[zent.branch-test]
         '[zent.ui.bridge-test]
         '[zent.ui.render-test]
         '[zent.k8s-test]
         '[zent.ui.server-test]
         '[zent.ui.logstream-test]
         '[zent.daemon-test]
         '[zent.client-test]
         '[zent.mcp-test]
         '[zent.reload-test]
)

(require '[zent.logs :as logs])

;; every log a deploy under test writes (and its secret marks) lands in a
;; temp dir, never the real ~/.cache/zent/logs
(let [{:keys [fail error]} (binding [logs/*dir* (str (System/getProperty "java.io.tmpdir")
                                                     "/zent-test-logs-" (System/currentTimeMillis))]
                             (t/run-tests 'zent.schema-test 'zent.compose-test
                                        'zent.presets-test 'zent.catalog-test
                                        'zent.shell-test 'zent.commands-test
                                        'zent.kinds-test 'zent.topo-test 'zent.lifecycle-test
                                        'zent.source-test 'zent.logs-test 'zent.session-test 'zent.watch-test 'zent.engine-test
                                        'zent.secrets-test
                                        'zent.branch-test 'zent.ui.bridge-test 'zent.ui.render-test 'zent.k8s-test
                                        'zent.ui.server-test 'zent.ui.logstream-test 'zent.daemon-test 'zent.client-test 'zent.mcp-test
                                        'zent.reload-test))]
  (System/exit (if (zero? (+ fail error)) 0 1)))
