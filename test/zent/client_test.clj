(ns zent.client-test
  "zent.client against zent.ui.server/app in-process: its HTTP transport is
  swapped for a direct call, so the real request/response contract between
  the two is what's exercised."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [zent.client :as client]
            [zent.ui.server :as server]
            [zent.ui.state :as state]))

(def ^:private d {:port 0 :token "test-token"})

(defn- in-process [method url headers body]
  ;; split as the adapter does: the query string isn't part of :uri
  (let [[uri query] (str/split (str/replace url #"^http://[^/]+" "") #"\?" 2)]
    (server/app (cond-> {:request-method method
                         :uri uri
                         :headers (update-keys headers str/lower-case)}
                  query (assoc :query-string query)
                  body (assoc :body (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8")))))))

(use-fixtures :each (fn [t]
                      (reset! state/view-state (assoc-in state/empty-state [:components :api] {:status :up}))
                      (reset! state/control [])
                      (reset! server/csrf-token "test-token")
                      (with-redefs [client/send! in-process] (t))))

(deftest request-test
  (testing "a write goes through with the card's token and returns the command id"
    (let [{:keys [ok id]} (client/request d :post "/api/stop/api")]
      (is ok)
      (is (= [{:action :stop :name :api :id id}] @state/control))))

  (testing "a rejected request throws with the server's error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"X-Zent-Token"
                          (client/request (assoc d :token "wrong") :post "/api/stop-all")))))

(deftest await-test
  (testing "given a queued command, await! returns once it's handled, reporting
            every status transition in the order it happened - even two that
            land back to back"
    (let [queued (client/request d :post "/api/trigger/api")
          changes (atom [])
          engine (future (Thread/sleep 50)
                         (state/observe-deploy-start! :api)
                         (state/observe-deploy-ok! :api {:mode :on :kind :process} nil)
                         (state/observe-handled! [{:id (:id queued)}]))]
      (is (= "up" (get-in (client/await! d queued #(swap! changes conj %&)) [:components :api :status])))
      @engine
      (is (= [[:api "up" "deploying"] [:api "deploying" "up"]] @changes)))))
