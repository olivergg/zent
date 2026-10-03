(ns zent.ui.logstream-test
  "The SSE log stream against log files in the runner's temp logs dir - no
  socket: events are read straight off the channel stream! writes to."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zent.logs :as logs]
            [zent.secrets :as secrets]
            [zent.ui.logstream :as logstream]
            [zent.ui.server :as server]))

(defn- next-event
  "The next data event on `ch` (pings skipped), parsed, or ::none after 2s."
  [ch]
  (loop []
    (let [[v _] (async/alts!! [ch (async/timeout 2000)])]
      (cond
        (nil? v) ::none
        (str/starts-with? v ":") (recur)
        :else (json/read-str (subs (str/trim v) (count "data: ")) :key-fn keyword)))))

(deftest stream-test
  (logs/start-run! :api)
  (logs/append! :api "booting")
  (logs/append! :api "ready")
  (let [ch (async/chan 16)]
    (logstream/stream! ch [:api] {})
    (try
      (testing "given a log with lines already, then the stream opens with them, as backlog"
        (is (= {:c "api" :l ["booting" "ready"] :b true} (dissoc (next-event ch) :o))))

      (testing "given a line written, then it comes next, stamped with when it was read"
        (logs/append! :api "GET /health 200")
        (let [{:keys [l t b]} (next-event ch)]
          (is (= ["GET /health 200"] l))
          (is (pos-int? t))
          (is (nil? b))))

      (testing "given a half-written line, then it waits for its newline"
        (spit (logs/log-file :api) "partial" :append true)
        (Thread/sleep 400)
        (spit (logs/log-file :api) " line\n" :append true)
        (is (= ["partial line"] (:l (next-event ch)))))

      (testing "given a new run truncating the log, then the stream says so and reads it from the start"
        (logs/start-run! :api)
        (logs/append! :api "second run")
        (let [{:keys [l r]} (next-event ch)]
          (is r)
          (is (= ["second run"] l))))

      (testing "given the client gone, then the stream ends rather than read for nobody"
        (async/close! ch)
        (Thread/sleep 400)
        (is (nil? (async/poll! ch))))
      (finally (async/close! ch)))))

(deftest stream-masks-and-withholds-test
  (testing "given a secret this process read, then it's masked in the stream too"
    (with-redefs [secrets/read-k8s-secret! (constantly "hunter2-value")]
      (secrets/secret-env! :relay {:use-secret-env true :allow-secret-contexts ["c"]
                                   :secret-env {"P" {:context "c" :namespace "n" :secret "s" :key "k"}}}))
    (logs/append! :relay "connecting with hunter2-value")
    (let [ch (async/chan 16)]
      (logstream/stream! ch [:relay] {})
      (try (is (= ["connecting with ****"] (:l (next-event ch))))
           (finally (async/close! ch)))))

  (testing "given a log an earlier daemon wrote with secrets this one never read, then it's withheld"
    (logs/mark-sensitive! :old-relay)
    (logs/append! :old-relay "connecting with other-secret")
    (let [ch (async/chan 16)]
      (logstream/stream! ch [:old-relay] {})
      (try (is (= [logstream/withheld-line] (:l (next-event ch))))
           (finally (async/close! ch))))))

(deftest stream-resume-test
  (testing "given a reconnecting client that has the log up to an offset, then
            only what came after is sent - no backlog twice"
    (logs/start-run! :web)
    (logs/append! :web "old")
    (let [offset (:offset (logs/read-since :web 0))
          ch (async/chan 16)]
      (logs/append! :web "new")
      (logstream/stream! ch [:web] {:web offset})
      (try (is (= ["new"] (:l (next-event ch))))
           (finally (async/close! ch))))))

(deftest stream-route-test
  (testing "the route streams the components it's given, as SSE"
    (let [{:keys [status headers body]} (server/app {:request-method :get :uri "/api/logs/stream"
                                                      :query-string "c=api,web&from=web:3"})]
      (is (= 200 status))
      (is (= "text/event-stream" (get headers "Content-Type")))
      (async/close! body)))

  (testing "a name that would leave the logs dir is refused, here and on /api/logs"
    (is (= 400 (:status (server/app {:request-method :get :uri "/api/logs/stream" :query-string "c=..%2Fsecrets"}))))
    (is (= 400 (:status (server/app {:request-method :get :uri "/api/logs/..%2F..%2Fx"}))))))
