(ns zent.logs
  "Per-component log files, not pipes or memory: a spawned process outlives
  the zent process that started it, and a pipe nobody reads blocks it once
  full. A file needs no reader and survives the run. Read them with `zent
  logs [-f]`, the dashboard or the MCP `logs` tool."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [zent.session :as session]))

(def ^:dynamic *dir*
  "Where log files live. Dynamic so tests can redirect it."
  (str (System/getenv "HOME") "/.cache/zent/logs"))

(defn- path-of [component] (str *dir* "/" (name component) ".log"))

(defn log-file
  "Absolute path of `component`'s log file, parent dir created."
  [component]
  (doto (path-of component) io/make-parents))

;; Which components' current log was written by a run holding secrets - names
;; only, persisted so a later daemon, which never read those values and so
;; can't mask them (zent.secrets/redact), knows to withhold the log instead.
(defonce ^:private sensitive-lock (Object.))

(defn- sensitive-file [] (str *dir* "/sensitive.edn"))

(defn- marks []
  (let [f (io/file (sensitive-file))]
    (if (.exists f) (edn/read-string (slurp f)) #{})))

(defn sensitive? [component] (contains? (marks) component))

(defn- update-sensitive! [f component]
  (locking sensitive-lock
    ;; renamed in, not spit over: sensitive? reads it without the lock
    (session/private-file! (sensitive-file) (pr-str (f (marks) component)))))

(defn mark-sensitive!
  "Records that `component`'s log may now hold secret values."
  [component]
  (update-sensitive! conj component))

(defn start-run!
  "Starts `component`'s log afresh so it covers this run only - an appended-to
  log across runs makes a failure's cause impossible to place - keeping the
  previous run as <component>.log.1: what a redeploy after a crash would
  otherwise erase. A fresh log holds no secret yet."
  [component]
  (let [path (log-file component)
        f (io/file path)]
    (when (pos? (.length f)) (.renameTo f (io/file (str path ".1"))))
    (spit path "")
    (update-sensitive! disj component)
    path))

(defn append!
  "Appends `text` (output zent captured itself, e.g. a build) to
  `component`'s log, newline-terminated."
  [component text]
  (when-not (str/blank? text)
    (spit (log-file component) (cond-> text (not (str/ends-with? text "\n")) (str "\n"))
          :append true)))

(defn read-since
  "`component`'s complete lines written past byte `offset`:
  {:lines [...] :offset where the next read starts :reset? true when the log
  shrank below `offset` - a new run truncated it, so this reads it from the
  start}. A trailing line still being written waits for its newline."
  [component offset]
  (let [f (io/file (path-of component))
        len (if (.exists f) (.length f) 0)
        reset? (< len offset)
        from (if reset? 0 offset)
        bs (when (> len from)
             (with-open [in (java.io.FileInputStream. f)] (.skip in from) (.readAllBytes in)))
        end (when bs (loop [i (dec (alength bs))] (cond (neg? i) nil (= 10 (aget bs i)) i :else (recur (dec i)))))]
    {:lines (if end (str/split-lines (String. ^bytes bs 0 (int (inc end)) "UTF-8")) [])
     :offset (if end (+ from end 1) from)
     :reset? reset?}))

(defn strip-ansi
  "`line` without terminal escape codes (colours, OSC links) - for a reader
  that isn't a terminal, an agent through MCP: noise, and tokens for nothing."
  [line]
  (str/replace line #"\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\))" ""))

(defn tail
  "Last `n` lines of `component`'s log, oldest first; empty when there's none."
  [component n]
  (let [path (path-of component)
        content (when (.exists (io/file path)) (slurp path))]
    ;; split-lines of "" is [""] - a phantom line
    (if (str/blank? content)
      []
      (vec (take-last n (str/split-lines content))))))
