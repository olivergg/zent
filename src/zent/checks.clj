(ns zent.checks
  "A preset's checks: clojure.test namespaces under <catalog>/checks/<preset>/
  that judge a running stack (read-only by contract) - one file per check, its
  deftests the tests. The ns attr-map carries what zent needs:

    (ns supportn3o_6504.charge-history
      {:zent/observes [:oceanv3 :renault-ingestor] :zent/description \"...\"}
      (:require [clojure.test :refer [deftest is]]))

  A check runs in a JVM `clojure -M` from <catalog>/checks (its deps.edn, with
  :paths [\".\"]): it is the catalog's code, not the engine's - Jolt never loads
  it. clojure.test's own `report` hook prints one EDN event per line, read back
  into a result whose :status is :pass, :fail or :unknown (could not judge:
  an error, nothing run), merged the way Jepsen merges :valid?."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [zent.logs :as logs]
            [zent.shell :as shell]))

(def ^:dynamic *dir*
  "Where results are kept, one EDN file per run. Dynamic so tests can redirect it."
  (str (System/getenv "HOME") "/.cache/zent/checks"))

(def ^:private event-prefix "#zent/check")

(defn- demunge [s] (str/replace s "_" "-"))

(defn dir-name
  "checks/<this>/ for `preset`: its name munged like a namespace segment."
  [preset]
  (str/replace (name preset) "-" "_"))

(defn- read-ns-form
  "[ns-symbol attr-map] of a check file's leading (ns ...) form."
  [text]
  (let [[op ns-sym & more] (edn/read-string text)]
    (when-not (and (= 'ns op) (symbol? ns-sym))
      (throw (ex-info "a check file starts with (ns ...)" {})))
    [ns-sym (or (first (filter map? (take 2 more))) {})]))

(defn discover
  "{preset [check ...]} for <dir>/checks/<preset>/*.clj (a missing dir is just
  no checks). checks/lib/ is the checks' shared code, not a preset's. A check
  whose ns form doesn't read, or doesn't match its path, carries a :problem
  instead of failing the whole catalog."
  [dir]
  (let [root (io/file dir "checks")]
    (into {}
          (for [^java.io.File pdir (some-> root .listFiles sort)
                :when (and (.isDirectory pdir) (not= "lib" (.getName pdir)))
                :let [checks (vec (for [^java.io.File f (sort (.listFiles pdir))
                                        :when (str/ends-with? (.getName f) ".clj")
                                        :let [base (str/replace (.getName f) #"\.clj$" "")
                                              expected (symbol (str (.getName pdir) "." (demunge base)))
                                              check {:name (keyword (demunge base)) :root (.getPath root) :ns expected}]]
                                    (try
                                      (let [[ns-sym {:zent/keys [observes description]}] (read-ns-form (slurp f))]
                                        (cond-> (assoc check :observes (vec (filter keyword? observes)))
                                          description (assoc :description (str description))
                                          (not (every? keyword? observes))
                                          (assoc :problem ":zent/observes is a vector of component keywords")
                                          (not= ns-sym expected)
                                          (assoc :problem (format "its ns is %s, its path wants %s" ns-sym expected))))
                                      (catch Exception e
                                        (assoc check :problem (or (ex-message e) (str e)))))))]
                :when (seq checks)]
            [(keyword (demunge (.getName pdir))) checks]))))

(defn problems
  "What `zent check` reports about `catalog`'s checks: a dir no preset owns,
  an unreadable check, an observed component the preset doesn't run."
  [{:keys [checks presets]}]
  (for [[preset cs] (sort-by key checks)
        :let [components (get presets preset)]
        msg (if-not components
              [(format "checks/%s: no preset %s" (dir-name preset) (name preset))]
              (for [{cname :name :keys [problem observes]} cs
                    msg (cons problem
                              (for [o observes :when (not (contains? components o))]
                                (format "observes %s, which the preset doesn't run" (name o))))
                    :when msg]
                (format "%s/%s: %s" (name preset) (name cname) msg)))]
    msg))

(defn- test-name! [t]
  (when-not (re-matches #"[A-Za-z*+!_?<>=][A-Za-z0-9*+!_?<>=-]*" (str t))
    (throw (ex-info (format "invalid test name %s" (pr-str t)) {:test t})))
  t)

;; Evaluated by the check's JVM: clojure.test's `report` hook, one EDN event per
;; line; printed values go as strings (one need not read back, e.g. #object).
;; %1$s the ns, %2$s the vars to run.
(def ^:private runner
  "(require 'clojure.test '%1$s)
(let [emit (fn [m] (println %3$s (pr-str m)) (flush))
      shown (fn [x] (cond (string? x) x
                          (instance? Throwable x) (str (.getName (class x)) \": \" (ex-message x)
                                                       (some->> (ex-data x) not-empty pr-str (str \" \")))
                          :else (some-> x pr-str)))]
  (binding [clojure.test/report
            (fn [{t :type :as m}]
              (case t
                :begin-test-var
                (emit (cond-> {:event :begin :test (-> m :var meta :name str)}
                        (-> m :var meta :doc) (assoc :doc (-> m :var meta :doc))))
                :end-test-var
                (emit {:event :end :test (-> m :var meta :name str)})
                (:pass :fail :error)
                (emit (cond-> {:event t :test (some-> clojure.test/*testing-vars* first meta :name str)}
                        (not= :pass t)
                        (merge {:message (shown (:message m)) :expected (pr-str (:expected m))
                                :actual (shown (:actual m)) :file (:file m) :line (:line m)
                                :context (not-empty (clojure.test/testing-contexts-str))})))
                nil))]
    (clojure.test/test-vars %2$s))
  (emit {:event :done})
  (shutdown-agents)
  (System/exit 0))")

(defn only
  "The test names a comma-separated `s` lists (--only=, ?only=), nil for none."
  [s]
  (some-> s (str/split #",") (->> (remove str/blank?)) seq vec))

(defn run-cmd
  "The argv running `check`'s deftests, in file order - only `tests` (names)
  when given."
  [{:keys [ns]} tests]
  (let [vars (if (seq tests)
               (str "[" (str/join " " (map #(str "#'" ns "/" (test-name! %)) tests)) "]")
               (format "(->> (ns-interns '%s) vals (filter (comp :test meta)) (sort-by (comp :line meta)))" ns))]
    ["clojure" "-M" "-e" (format runner ns vars (pr-str event-prefix))]))

(defn- verdict
  "Jepsen's merge of :valid?: something proven wrong beats can't-tell."
  [statuses]
  (cond (some #{:fail} statuses) :fail
        (or (empty? statuses) (some #{:error} statuses)) :unknown
        :else :pass))

(defn parse
  "A run's result from its stdout `lines` and exit code: tests in run order,
  each :pass/:fail/:error with its assertion count and failures."
  [lines exit]
  (let [events (keep #(when (str/starts-with? % event-prefix) (edn/read-string (subs % (count event-prefix)))) lines)
        done? (some #(= :done (:event %)) events)
        events (remove #(= :done (:event %)) events)
        tests (reduce (fn [acc {:keys [event test] :as e}]
                        (let [i (or (first (keep-indexed #(when (= test (:name %2)) %1) acc))
                                    (count acc))
                              acc (if (= i (count acc)) (conj acc {:name test :status :pass :assertions 0 :failures []}) acc)
                              detail (dissoc e :event :test)]
                          (case event
                            :begin (cond-> acc (:doc e) (assoc-in [i :doc] (:doc e)))
                            :end (assoc-in acc [i :done?] true)
                            :pass (update-in acc [i :assertions] inc)
                            :fail (-> acc (update-in [i :assertions] inc) (update-in [i :failures] conj detail)
                                      (update-in [i :status] #(if (= :error %) % :fail)))
                            :error (-> acc (update-in [i :failures] conj detail) (assoc-in [i :status] :error)))))
                      [] events)
        ;; begun, never ended: the run died inside it - a failure already seen stays one
        tests (mapv #(cond-> (dissoc % :done?) (and (not (:done? %)) (= :pass (:status %))) (assoc :status :error))
                    tests)
        status (verdict (map :status tests))]
    ;; no :done, the run ended early - whatever exit it gave, it judged only part
    (cond-> {:status (if (and (or (not done?) (not (zero? exit))) (not= :fail status)) :unknown status)
             :tests tests
             :counts (let [n (frequencies (map :status tests))]
                       {:tests (count tests)
                        :assertions (reduce + (map :assertions tests))
                        :failed (n :fail 0)
                        :errors (n :error 0)})}
      (not done?) (assoc :incomplete true)
      (not (zero? exit)) (assoc :exit exit))))

(def ^:private glyph {:pass "✓" :fail "✗" :error "!" :unknown "?"})

(defn report
  "`result` as text lines: a line per check and per test; under a test not
  passing, its docstring, then each failure's message, location and
  expected/actual."
  [{:keys [preset check status tests counts duration-ms only error]}]
  (concat
   [(format "%s %s/%s  %s - %d test(s)%s, %d assertion(s), %.1f s" (glyph status) (name preset) (name check)
            (name status) (:tests counts)
            (let [bad (+ (:failed counts) (:errors counts))] (if (pos? bad) (format ", %d not passing" bad) ""))
            (:assertions counts) (/ (or duration-ms 0) 1000.0))]
   (when only [(str "  only: " (str/join ", " only))])
   (when error (map #(str "  | " %) (str/split-lines error)))
   (for [{:keys [name status assertions failures doc]} tests
         line (concat [(format "  %s %s  %d" (glyph status) name assertions)]
                      ;; the why of a test, where it matters: when it doesn't pass
                      (when (and doc (not= :pass status))
                        (map #(str "      # " (str/trim %)) (str/split-lines doc)))
                      (for [{:keys [message context expected actual file line]} failures
                            l [(str "      " (str/join " " (remove nil? [context message]))
                                    (when file (format "  (%s:%s)" file line)))
                               (str "      expected: " expected)
                               (str "        actual: " actual)]]
                        l))]
     line)))

(defn log-name
  "The zent.logs name a check's run prints to - streamed live like a component's."
  [preset check-name]
  (keyword (str "check-" (name preset) "-" (name check-name))))

(defn history
  "`preset`'s `check-name` last `n` results, oldest first."
  [preset check-name n]
  (->> (.listFiles (io/file *dir* (name preset) (name check-name)))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
       (sort-by #(parse-long (str/replace (.getName ^java.io.File %) #"\.edn$" "")))
       (take-last n)
       (mapv #(edn/read-string (slurp %)))))

(defn current
  "Each test's latest known result from `runs` (oldest first): the last full
  run, overlaid by the partial (:only) runs after it - a test overlaid
  carries :at, when it ran. nil without a full run."
  [runs]
  (when-let [i (last (keep-indexed #(when-not (:only %2) %1) runs))]
    (let [base (nth runs i)
          after (subvec runs (inc i))
          tests (reduce (fn [tests {:keys [started-at] :as r}]
                          (reduce (fn [ts t] (mapv #(if (= (:name %) (:name t)) (assoc t :at started-at) %) ts))
                                  tests (:tests r)))
                        (:tests base) after)]
      (assoc base :tests tests :status (verdict (map :status tests))
                  :overlaid (mapv #(select-keys % [:started-at :only]) after)))))

(defn run!
  "Runs `preset`'s `check` (only `tests` when given), keeps its result under
  *dir* and returns it."
  [preset {:keys [root] :as check} tests]
  ;; through a log file, not a pipe (zent.logs): what the run printed stays readable
  (let [log (logs/log-file (log-name preset (:name check)))
        _ (spit log "")
        started (System/currentTimeMillis)
        {:keys [exit]} (shell/run-logged! (run-cmd check tests) :dir root :log-file log)
        lines (str/split-lines (slurp log))
        {:keys [incomplete] :as parsed} (parse lines exit)
        result (cond-> (assoc parsed :preset preset :check (:name check)
                              :started-at started :duration-ms (- (System/currentTimeMillis) started))
                 (seq tests) (assoc :only (vec tests))
                 (or incomplete (not (zero? exit)))
                 (assoc :error (str/join "\n" (cons (format (if incomplete
                                                              "the run ended before it finished (exit %d)"
                                                              "the run finished, then exited %d")
                                                            exit)
                                                    (take-last 20 (remove #(str/starts-with? % event-prefix) lines))))))
        f (io/file *dir* (name preset) (name (:name check)) (str started ".edn"))]
    (io/make-parents f)
    (spit f (pr-str result))
    result))
