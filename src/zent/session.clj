(ns zent.session
  "What's running, persisted as EDN - handles die with the zent process
  while what they point at runs on, so a later daemon adopts them and
  `zent down` can still stop them.

  A spawned process is recorded as its PID plus `ps` identity (start time +
  argv), re-checked before anything kills it: a recycled PID must never
  get an unrelated process killed. `ps`, since Jolt lacks
  ProcessHandle.info(); the string is locale-dependent, fine for comparing
  two readings on one machine."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [zent.shell :as shell]))

(def default-path
  "Machine-global: one local dev stack per machine."
  (str (System/getenv "HOME") "/.cache/zent/session.edn"))

(defn pid-identity
  "`ps`'s start-time + argv line for `pid`, or nil if there's no such process."
  [pid]
  (let [{:keys [exit out]} (shell/sh! ["ps" "-p" pid "-o" "lstart=,args="])]
    (when (and (zero? exit) (not (str/blank? out)))
      (str/trim out))))

(defn describe
  "Serializable descriptor for `handle`, or nil when a later run would have
  nothing to tear down (a finished one-shot, or a component zent only
  readiness-probed). A map handle is persisted as is, so a kind's handle
  must be plain EDN data."
  [name handle]
  (cond
    ;; no identity (already dead): nothing safe to record - a bare PID could
    ;; later be somebody else's
    (instance? Process handle) (let [pid (.pid ^Process handle)]
                                 (when-let [ps (pid-identity pid)]
                                   {:kind :pid :component name :pid pid :ps ps}))
    ;; includes :pid, already persisted shape (zent.engine/reuse-if-live)
    (and (map? handle) (:kind handle) (not= :external (:kind handle))) (assoc handle :component name)
    :else nil))

(defn read-session
  "The session at `path`, or nil if there isn't one. A corrupt file is
  reported rather than silently treated as \"no session\" - it means
  something IS running that we can no longer describe."
  [path]
  (when (.exists (io/file path))
    (try
      (edn/read-string (slurp path))
      (catch Exception e
        (throw (ex-info (str "unreadable zent session at " path
                             " - components may still be running; inspect/remove it by hand")
                        {:path path} e))))))

(defn private-file!
  "Writes `content` to `path` readable by the owner only - restricted before
  the content lands, then renamed in, so neither another user nor a reader
  racing the write (or a kill mid-write) ever sees a partial file.
  .renameTo, not Files/move: Jolt's deletes the target before renaming."
  [path content]
  (let [f (io/file path)
        tmp (io/file (str path ".tmp"))]
    (io/make-parents f)
    (spit tmp "")
    (.setReadable tmp false false) (.setReadable tmp true true)
    (.setWritable tmp false false) (.setWritable tmp true true)
    ;; :append - a plain spit recreates the file under Jolt, dropping the mode
    (spit tmp content :append true)
    (.renameTo tmp f)))

(defn- write-session! [path preset handles]
  (private-file! path (pr-str {:preset preset :written-at (System/currentTimeMillis) :handles (vec handles)})))

;; save! and drop-components! read, modify and write: serve!'s loop and its
;; deploy worker both call them, so they take turns
(defonce ^:private write-lock (Object.))

(defn save!
  "Writes `named-handles` ([[name handle] ...]) to `path`, dropping those
  with nothing to tear down. Merges onto the existing session: an entry
  this run didn't touch stays tracked until something actually stops it
  (drop-components!). `:preset` is informational only."
  [path preset named-handles]
  (locking write-lock
    (let [this-run (keep (fn [[name handle]] (describe name handle)) named-handles)
          touched (into #{} (map :component this-run))
          carried (remove (comp touched :component) (:handles (read-session path)))]
      (write-session! path preset (into (vec carried) this-run)))))

(defn clear! [path]
  (io/delete-file path true))

(defn drop-components!
  "Removes the entries for `names` from the session at `path`, leaving the
  rest - what a run forgets once it has actually stopped them."
  [path names]
  (locking write-lock
    (when-let [{:keys [preset handles]} (read-session path)]
      (let [remaining (remove (comp (set names) :component) handles)]
        (if (seq remaining)
          (write-session! path preset remaining)
          (clear! path))))))
