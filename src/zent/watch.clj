(ns zent.watch
  "File watching, by scanning mtimes: Jolt has no WatchService (and macOS's
  JVM one lags ~10s). Affordable because a :watch spec is narrow, not a
  repo root - see docs/watch-design.md."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-poll-ms 400)

(defn- watchable?
  [^java.io.File f exts]
  (or (empty? exts)
      (some #(str/ends-with? (.getName f) %) exts)))

(defn scan
  "{absolute-path -> mtime} for one watch spec rooted at `dir` - a map, so
  plain = on two scans catches creates, modifies and deletes alike."
  [dir {:keys [paths exts ignore]}]
  (let [ignore (set (or ignore ["target" "node_modules" ".git"]))
        roots (map #(io/file dir %) (or (seq paths) ["."]))]
    (loop [stack (vec (filter #(.exists %) roots)), acc {}]
      (if (empty? stack)
        acc
        (let [^java.io.File cur (peek stack), rest- (pop stack)]
          (cond
            (.isDirectory cur)
            (if (ignore (.getName cur))
              (recur rest- acc)
              ;; a symlink below a root isn't followed: one to an ancestor never ends
              (recur (into rest- (remove #(java.nio.file.Files/isSymbolicLink (.toPath %)) (.listFiles cur)))
                     acc))

            (watchable? cur exts)
            (recur rest- (assoc acc (.getPath cur) (.lastModified cur)))

            :else (recur rest- acc)))))))

(defn changes
  "{:changed #{path} :removed #{path}} between two scans - to report what
  triggered a reload."
  [before after]
  {:changed (into #{} (comp (remove (fn [[p mtime]] (= mtime (get before p)))) (map key)) after)
   :removed (into #{} (remove (partial contains? after)) (keys before))})
