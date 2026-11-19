(ns zent.reference-test
  (:require [clojure.test :refer [deftest is]]
            [zent.reference :as reference]
            [zent.schema :as schema]))

(deftest every-key-described-test
  (is (empty? (for [[kind fields] (schema/describe-kinds)
                    {:keys [key description]} fields
                    :when (not description)]
                [kind key]))))

(deftest reference-is-current-test
  ;; true? - a plain = would print both whole files
  (is (true? (= (str (reference/markdown) "\n") (slurp "docs/reference.md")))
      "docs/reference.md is stale: jolt run -m zent.reference > docs/reference.md"))
