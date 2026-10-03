(ns zent.catalog
  "A catalog as plain files, so a catalog repo holds no code:
  <dir>/catalog.edn ({:name :defaults :components ...}, `~/` strings expanded)
  and one preset per <dir>/presets/<name>.edn (zent.presets)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [zent.presets :as presets]))

(defn load-dir [dir]
  (let [f (io/file dir "catalog.edn")
        catalog (when (.exists f) (presets/expand-home (edn/read-string (slurp f))))]
    (when-not (keyword? (:name catalog))
      (throw (ex-info (format "%s: a map with a keyword :name (it keys the user's presets) expected"
                              (.getPath f))
                      {:file (.getPath f)})))
    (assoc catalog :presets (presets/load-files (io/file dir "presets")))))
