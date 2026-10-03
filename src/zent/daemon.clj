(ns zent.daemon
  "The resident serve's address card: which process serves, on which port,
  with which write token - what a CLI or MCP client reads to reach it
  (zent.client). 0600: the token authorizes starting and stopping processes.

  Liveness is the recorded pid's `ps` identity (zent.session/pid-identity),
  so a card left by a crashed or killed serve reads as no daemon, not as a
  recycled pid's."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [zent.session :as session]))

(def default-path (str (System/getenv "HOME") "/.cache/zent/daemon.edn"))

(def log-path
  "Where a detached serve's own output goes (`zent attach` follows it)."
  (str (System/getenv "HOME") "/.cache/zent/serve.log"))

(defn clear! [path] (io/delete-file path true))

(defn live
  "The card at `path` if its process is still the one that wrote it, else
  nil - a stale card is removed on the way."
  [path]
  (when (.exists (io/file path))
    (let [{:keys [pid identity] :as card} (edn/read-string (slurp path))]
      (if (and identity (= identity (session/pid-identity pid)))
        card
        (do (clear! path) nil)))))

(defn register!
  "Records the current process as the daemon, with `info` ({:port :token})."
  [path info]
  (let [pid (.pid (ProcessHandle/current))]
    (session/private-file! path (pr-str (assoc info :pid pid :identity (session/pid-identity pid)
                                       :started-at (System/currentTimeMillis)
                                       ;; bin/zent runs us from the engine root
                                       :engine-dir (System/getProperty "user.dir"))))))

(defn engine-gone?
  "Whether `card`'s serve runs from an engine dir since removed - a package
  upgrade deletes the old one under it, and its UI and reload-code then fail."
  [{:keys [engine-dir]}]
  (boolean (and engine-dir (not (.isDirectory (io/file engine-dir))))))
