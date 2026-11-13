(ns zent.diff-test
  (:require [clojure.test :refer [deftest is testing]]
            [zent.diff :as diff]))

(deftest segments-test
  (testing "given a padded id with a suffix, then refined by character: the id stays, the padding and suffix show"
    (is (= [[:same "000"] [:add "00000000"] [:same "764"] [:add "05"]] (diff/segments "000764" "0000000000076405"))))
  (testing "given two decimals, then each stays whole"
    (is (= [[:del "17.6"] [:add "20.65"]] (diff/segments "17.6" "20.65"))))
  (testing "given words unlike each other, then the run of words goes as one, the rest stays"
    (is (= [[:del "arrêt moteur"] [:add "roulage"] [:same " (état "] [:del "4"] [:add "2"] [:same ")"]]
           (diff/segments "arrêt moteur (état 4)" "roulage (état 2)")))))

(deftest of-test
  (testing "given two maps, EDN or JSON, an empty side an empty map, then key by key"
    (is (= {:keys [{:key ":litres" :op :changed :segments [[:del "17.6"] [:add "20.65"]]} {:key ":v" :op :same :value "184"}]}
           (diff/of ["{:litres 17.6 :v 184}" "{:litres 20.65 :v 184}"])))
    (is (= {:keys [{:key "ndid" :op :add :value "0005"}]} (diff/of ["" "{\"ndid\": \"0005\"}"]))))
  (testing "given text, then segments; given sides too long, nil"
    (is (= {:segments [[:del "a"] [:add "b"]]} (diff/of ["a" "b"])))
    (is (nil? (diff/of [(apply str (repeat 600 "a ")) (apply str (repeat 600 "b "))])))))

(deftest lines-test
  (is (= ["expected: 000764" "  actual: 000{+00000000+}764{+05+}"] (diff/lines (diff/of ["000764" "0000000000076405"]))))
  (is (= ["~ :litres [-17.6-] -> {+20.65+}"] (diff/lines (diff/of ["{:litres 17.6 :v 184}" "{:litres 20.65 :v 184}"])))))
