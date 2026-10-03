(ns zent.ui.logstream
  "Component logs streamed to the dashboard as Server-Sent Events: one stream
  per open log view, carrying only what's new in the logs of the components
  it asked for. Each event is one component's batch:

    {:c name :l [lines] :o next-offset :t read-at-ms :b backlog? :r reset?}

  :t is when zent read the lines, not when they were written - the files carry
  no per-line time (zent.logs: a file, not a pipe), and ~one tick is precise
  enough to interleave components. Backlog (the last lines already there when
  the stream opened) carries no :t. Values are masked like /api/logs, and a
  log this process can't mask (zent.secrets/maskable?) is withheld."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [ring-chez.sse :as sse]
            [zent.logs :as logs]
            [zent.secrets :as secrets]))

(def backlog-lines
  "Lines a log view opens with - /api/logs serves as many."
  500)
(def ^:private tick-ms 150)
;; a comment line now and then: the only way to notice a client that left
;; while its components stay quiet, and so end its stream
(def ^:private ping-ms 15000)

(def withheld-line
  (str "log withheld: written with secrets by an earlier zent serve, which this one can't mask"
       " - `zent reload <name>` reads them again"))

(defn- send! [ch event]
  (sse/send! ch {:data (json/write-str event)}))

(defn- backlog
  "The event opening `name`'s part of a stream, and the offset to follow from:
  its last lines, or from `from` when a reconnecting client already has them."
  [name from]
  (if from
    [nil from]
    (let [{:keys [lines offset]} (logs/read-since name 0)]
      [{:c name :l (mapv secrets/redact (take-last backlog-lines lines)) :o offset :b true} offset])))

(defn- step
  "One tick for `name` at `offset`: [event-or-nil next-offset]."
  [name offset now]
  (let [{:keys [lines offset reset?]} (logs/read-since name offset)]
    [(when (or (seq lines) reset?)
       (cond-> {:c name :l (mapv secrets/redact lines) :o offset :t now}
         reset? (assoc :r true)))
     offset]))

(defn stream!
  "Streams `names`' logs on `ch` until the client goes away (a send returns
  false) - on its own thread. `from` maps a name to the byte offset a
  reconnecting client already has."
  [ch names from]
  (future
    (try
      (loop [offsets {}, withheld #{}, pinged (System/currentTimeMillis)]
        (let [now (System/currentTimeMillis)
              ;; per tick: a log becomes maskable once a reload re-read its secrets
              [events offsets withheld]
              (reduce (fn [[evs offs held] name]
                        (cond
                          (not (secrets/maskable? name))
                          [(cond-> evs (not (held name)) (conj {:c name :l [withheld-line] :b true}))
                           (dissoc offs name) (conj held name)]

                          (contains? offs name)
                          (let [[ev off] (step name (offs name) now)]
                            [(cond-> evs ev (conj ev)) (assoc offs name off) (disj held name)])

                          :else
                          (let [[ev off] (backlog name (when-not (held name) (get from name)))]
                            [(cond-> evs ev (conj ev)) (assoc offs name off) (disj held name)])))
                      [[] offsets withheld] names)
              ping? (>= (- now pinged) ping-ms)
              open? (and (every? #(send! ch %) events)
                         (or (not ping?) (async/>!! ch ": ping\r\n\r\n")))]
          (when open?
            (Thread/sleep (long tick-ms))
            (recur offsets withheld (if ping? now pinged)))))
      ;; a stream that broke must not take the daemon down - its client reconnects
      (catch Throwable e (println "[zent.ui] log stream ended:" (ex-message e)))
      (finally (async/close! ch)))))

(defn response
  "The SSE response streaming `names` (see stream!)."
  [names from]
  (let [ch (async/chan 16)]
    (stream! ch names from)
    (sse/event-response ch)))
