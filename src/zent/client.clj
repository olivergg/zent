(ns zent.client
  "A running serve's HTTP API (zent.ui.server) from another process - what
  the CLI verbs, and the MCP tools through them, are built on. `d` below is
  a zent.daemon card: {:port :token ...}."
  (:require [clojure.data.json :as json]
            [jolt.http-client :as http]))

(defn- send! [method url headers body]
  ((case method :get http/get :post http/post) url
   (cond-> {:headers headers :throw-exceptions false} body (assoc :body body))))

(defn request
  "The JSON reply to `method` `path` (with a text `body`, if any),
  keywordized. Throws on a non-200, with the server's :error when it gave one."
  [{:keys [port token]} method path & [body]]
  (let [{:keys [status body]} (send! method (str "http://127.0.0.1:" port path) {"X-Zent-Token" token} body)
        data (try (json/read-str body :key-fn keyword) (catch Exception _ {:error body}))]
    (if (= 200 status)
      data
      (throw (ex-info (str path ": " (or (:error data) (str "HTTP " status))) {:status status})))))

(defn await!
  "Long-polls /api/events until the daemon has processed the command a POST
  queued (`queued`, its {:id :seq} reply), calling (on-change name old-status
  new-status) on every status transition, in the order they happened.
  Returns the final view."
  [d {:keys [id] since :seq} on-change]
  (loop [since since]
    (let [{:keys [events done seq]} (request d :get (str "/api/events?since=" since "&id=" id))]
      (doseq [{:keys [component from to]} events]
        (on-change (keyword component) from to))
      (if done
        (request d :get "/api/view")
        (recur seq)))))
