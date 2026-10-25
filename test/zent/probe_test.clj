(ns zent.probe-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [zent.probe :as probe]
            [zent.schema :as schema]))

(deftest wait-ready-timeout-test
  (let [waited (fn [spec] (with-redefs [probe/wait-http-ready! (fn [_url & {:keys [timeout-ms]}] timeout-ms)]
                            (probe/wait-ready! :app spec)))]
    (testing "given no :timeout-s, the wait gives up after 60 s"
      (is (= 60000 (waited {:type :http-poll :url "http://x"}))))
    (testing "given :timeout-s, an app slow to start gets that long"
      (is (= 600000 (waited {:type :http-poll :url "http://x" :timeout-s 600}))))
    (testing "given no spec, nothing to wait for"
      (is (true? (probe/wait-ready! :app nil))))))

(deftest readiness-schema-test
  (testing "given :timeout-s, a positive integer of seconds"
    (is (m/validate schema/Readiness {:type :http-poll :url "http://x" :timeout-s 600}))
    (is (not (m/validate schema/Readiness {:type :http-poll :url "http://x" :timeout-s 0})))))
