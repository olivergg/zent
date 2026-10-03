(ns zent.shell
  "Real process execution - one-off commands and long-lived spawns, never
  through a shell (no quoting, no globbing, no injection). A command is
  either an argv vector, passed as is - what zent builds (zent.commands),
  so a path may hold spaces - or a string a catalog wrote (:cmd, :build-cmd,
  :scripts), split on whitespace."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn- argv [cmd]
  (if (string? cmd) (str/split (str/trim cmd) #"\s+") (mapv str cmd)))

(defn display
  "`cmd` as one line, for logs and messages."
  [cmd]
  (if (string? cmd) cmd (str/join " " cmd)))

(defn sh!
  "Runs `cmd` and returns {:exit :out :err}. :env is MERGED onto our own
  environment, not replacing it (replacing meant every caller re-exporting
  PATH/HOME); :dir is the working directory."
  [cmd & {:keys [env dir]}]
  (let [parts (argv cmd)
        ;; sh/sh's :env replaces outright - merge here
        merged (when env (merge (into {} (System/getenv)) env))
        opts (cond-> []
               merged (into [:env merged])
               dir (into [:dir dir]))]
    (apply sh/sh (concat parts opts))))

(defn sh-or-throw!
  "sh! that throws ex-info on a non-zero exit - for steps whose failure
  must stop what's calling them (a teardown, cluster prep)."
  [cmd]
  (let [{:keys [exit err]} (sh! cmd)]
    (when-not (zero? exit)
      (throw (ex-info (format "`%s` failed: exit %d" (display cmd) exit) {:cmd cmd :exit exit :err err})))
    nil))

(defn spawn!
  "Starts `cmd` in the background and returns its java.lang.Process. Beyond
  sh!'s :env/:dir, :log-file gets stdout+stderr appended (else the child
  inherits our streams) - a file, not a pipe: the child outlives zent, and
  a pipe nobody reads eventually blocks it."
  [cmd & {:keys [env dir log-file]}]
  (let [pb (ProcessBuilder. (argv cmd))]
    (when dir (.directory pb (java.io.File. (str dir))))
    ;; environment() starts as a copy of ours: putting overrides in merges
    (when env
      (let [pb-env (.environment pb)]
        (doseq [[k v] env] (.put pb-env k v))))
    (if log-file
      (let [f (java.io.File. (str log-file))]
        (.redirectOutput pb (java.lang.ProcessBuilder$Redirect/appendTo f))
        (.redirectErrorStream pb true))
      (.inheritIO pb))
    (.start pb)))
