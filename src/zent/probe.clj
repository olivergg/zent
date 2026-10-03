(ns zent.probe
  "Readiness probing - waiting for something to answer before dependents
  proceed. A spec is data, {:type :http-poll
  :url \"...\"}; nil means nothing to wait for."
  (:require [zent.shell :as shell]))

(defn- http-ok?
  "One GET. --max-time: a server that accepts but never answers (an IDE-run
  app paused on a breakpoint) must not hang a liveness check or a wait."
  [url]
  (zero? (:exit (shell/sh! ["curl" "-sf" "--max-time" "5" url]))))

(defn wait-http-ready!
  "Polls `url` until it answers or `timeout-ms` elapses."
  [url & {:keys [interval-ms timeout-ms] :or {interval-ms 2000 timeout-ms 60000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (http-ok? url) true
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info (str "Timed out waiting for " url) {:url url}))
        :else (do (Thread/sleep (long interval-ms)) (recur))))))

(defn- check-type! [{:keys [type] :as spec}]
  (when-not (= :http-poll type)
    (throw (ex-info (str "unknown readiness :type " type) {:readiness spec}))))

(defn ready?
  "One-shot, non-blocking check of `spec` (true when there's none) - for
  liveness rechecks, where a retrying wait would block the loop."
  [spec]
  (or (nil? spec) (do (check-type! spec) (http-ok? (:url spec)))))

(defn wait-ready!
  "Waits on readiness spec `spec` for component `name`, if any; true when
  there was nothing to wait for."
  [name spec]
  (or (nil? spec)
      (do (check-type! spec)
          (println (format "[%s] waiting for %s" name (:url spec)))
          (wait-http-ready! (:url spec)))))
