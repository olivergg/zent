(ns zent.reload-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [zent.reload :as reload]))

(deftest reload-order-test
  (testing "dependencies first, non-targets ignored, cycles don't loop"
    (let [requires {'a '[b clojure.string] 'b '[c] 'c '[a] 'd '[b]}]
      (is (= '[c b a d] (reload/reload-order '[a b c d] requires))))))

;; a real namespace on the test classpath, rewritten between reloads
(def ^:private fixture-ns 'zent.reload-fixture-tmp)
(def ^:private fixture-file "test/zent/reload_fixture_tmp.clj")

(defn- write-fixture! [body]
  (spit fixture-file (str "(ns zent.reload-fixture-tmp)\n" body "\n")))

(deftest reload!-test
  (try
    (write-fixture! "(defonce state (atom :kept)) (defn f [] :v1)")
    (require fixture-ns)
    (reset! @(resolve 'zent.reload-fixture-tmp/state) :changed)

    (testing "given an edited namespace, when reloaded, then its fns are new and defonce state survives"
      (write-fixture! "(defonce state (atom :kept)) (defn f [] :v2)")
      (is (= {:reloaded [fixture-ns]} (reload/reload! ["zent.reload-fixture"])))
      (is (= :v2 ((resolve 'zent.reload-fixture-tmp/f))))
      (is (= :changed @@(resolve 'zent.reload-fixture-tmp/state))))

    (testing "given a namespace that no longer compiles, then the failure is reported, not thrown"
      (write-fixture! "(defn f [] (")
      (let [{:keys [reloaded failed error]} (reload/reload! ["zent.reload-fixture"])]
        (is (= [] reloaded))
        (is (= fixture-ns failed))
        (is (string? error))))
    (finally
      (io/delete-file fixture-file true))))
