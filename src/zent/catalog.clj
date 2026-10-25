(ns zent.catalog
  "A catalog as plain files: <dir>/catalog.edn ({:name :defaults :components
  ...}, `~/` strings expanded), one preset per <dir>/presets/<name>.edn
  (zent.presets) - and, the only code a catalog may hold, its presets' checks
  under <dir>/checks/ (zent.checks)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [zent.checks :as checks]
            [zent.presets :as presets]))

(defn load-dir [dir]
  (let [f (io/file dir "catalog.edn")
        catalog (when (.exists f) (presets/expand-home (edn/read-string (slurp f))))]
    (when-not (keyword? (:name catalog))
      (throw (ex-info (format "%s: a map with a keyword :name (it keys the user's presets) expected"
                              (.getPath f))
                      {:file (.getPath f)})))
    (let [found (checks/discover dir)]
      (cond-> (assoc catalog :presets (presets/load-files (io/file dir "presets")))
        (seq found) (assoc :checks found)))))
