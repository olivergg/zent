(ns demo.http
  "Shared check code (checks/lib/): not a preset's checks.")

(defn status
  "The HTTP status of GET http://127.0.0.1:<port><path>."
  [port path]
  (let [c (doto (.openConnection (java.net.URL. (str "http://127.0.0.1:" port path))) (.setConnectTimeout 2000))]
    (.getResponseCode ^java.net.HttpURLConnection c)))
