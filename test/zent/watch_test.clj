(ns zent.watch-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [zent.watch :as watch])
  (:import [java.io File]))

(defn- p
  "Path join that normalizes the way scan's .getPath does - java.io.tmpdir
  ends in a slash on macOS, so plain str would build a double-slash path that
  never matches."
  [root & segs]
  (.getPath (apply io/file root segs)))

(defn- tmp-tree
  "A throwaway dir with src/a.txt, src/b.md and target/ignored.txt."
  []
  (let [root (p (System/getProperty "java.io.tmpdir") (str "zent-watch-test-" (System/nanoTime)))]
    (.mkdirs (File. (p root "src")))
    (.mkdirs (File. (p root "target")))
    (spit (p root "src/a.txt") "a")
    (spit (p root "src/b.md") "b")
    (spit (p root "target/ignored.txt") "nope")
    root))

(deftest scan-scopes-to-declared-paths-test
  (let [root (tmp-tree)]
    (testing ":paths limits the scan - the whole repo is never walked"
      (is (= #{(p root "src/a.txt") (p root "src/b.md")}
             (set (keys (watch/scan root {:paths ["src"]}))))))

    (testing ":exts filters by extension"
      (is (= [(p root "src/a.txt")]
             (keys (watch/scan root {:paths ["src"] :exts [".txt"]})))))

    (testing "target/ is ignored by default, so build output can't trigger reloads"
      (is (not-any? #(re-find #"/target/" %)
                    (keys (watch/scan root {:paths ["."]})))))

    (testing "a single file as a path works, not just directories"
      (is (= [(p root "src/a.txt")]
             (keys (watch/scan root {:paths ["src/a.txt"]})))))

    (testing "a path that doesn't exist yet is not an error"
      (is (= {} (watch/scan root {:paths ["nope"]}))))))

(deftest change-detection-test
  (let [root (tmp-tree)
        spec {:paths ["src"]}
        before (watch/scan root spec)]

    (testing "an unchanged tree scans equal - that's what suppresses reloads"
      (is (= before (watch/scan root spec))))

    (testing "a modification is detected, and reported by path"
      ;; mtime granularity can be 1s, so set it explicitly rather than sleeping
      (.setLastModified (File. (p root "src/a.txt")) (+ 60000 (System/currentTimeMillis)))
      (let [after (watch/scan root spec)]
        (is (not= before after))
        (is (= #{(p root "src/a.txt")} (:changed (watch/changes before after))))))

    (testing "a new file is detected"
      (let [before (watch/scan root spec)]
        (spit (p root "src/c.txt") "c")
        (let [after (watch/scan root spec)]
          (is (= #{(p root "src/c.txt")} (:changed (watch/changes before after)))))))

    (testing "a deletion is detected - a count+newest digest would miss this"
      (let [before (watch/scan root spec)]
        (.delete (File. (p root "src/c.txt")))
        (let [after (watch/scan root spec)]
          (is (not= before after))
          (is (= #{(p root "src/c.txt")} (:removed (watch/changes before after)))))))))
