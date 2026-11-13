(ns zent.checks
  "A preset's checks: clojure.test namespaces under <catalog>/checks/<preset>/
  that judge a running stack - one file per check, its deftests the tests. The
  ns attr-map carries what zent needs:

    (ns shop.order-totals
      {:zent/observes [:orders-api :postgres] :zent/description \"...\"}
      (:require [clojure.test :refer [deftest is]]))

  Read-only by default; :zent/writes true marks one that writes (a scenario):
  it runs only when named, never with the preset's other checks. A deftest's
  :zent/kind (:invariant, :pseudo-oracle, :metamorphic, :heuristic...) says
  what kind of oracle it is.

  A test may say what it judged, passing or not - shown under it:
  (clojure.test/do-report {:type :zent/note :note \"63 plug-ins, largest gap 1 min\"}).

  All optional, all plain clojure.test - for a reader who isn't a developer:
    ^{:zent/title \"Orders keep their total\"}   on the deftest: shown for its name
    {:type :zent/verdict :verdict \"3 orders of 120 lost their total\"}   its outcome, one sentence
    {:type :zent/fact :label \"orders compared\" :value 120 :bad false}   a figure it judged
    {:type :zent/accepted :reason \"known, ticket 42\"}   a gap decided acceptable: a test
                                                         with no failure is :accepted
    {:type :fail :expected 12.5 :actual 12 :message \"...\"
     :zent/group \"Total\" :zent/explain \"rounded down\" :zent/context {:order 7}}
      a failure on values, not forms: grouped by :zent/group (explained once), its
      context named; any (is (= a b)) failing is diffed too (zent.diff).

  A check runs in a JVM `clojure -M` from <catalog>/checks (its deps.edn, with
  :paths [\".\" \"lib\"]): it is the catalog's code, not the engine's - Jolt never
  loads it. clojure.test's own `report` hook prints one EDN event per line, read back
  into a result whose :status is :pass, :fail or :unknown (could not judge:
  an error, nothing run), merged the way Jepsen merges :valid?."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [zent.diff :as diff]
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
                                      (let [[ns-sym {:zent/keys [observes description writes]}] (read-ns-form (slurp f))]
                                        (cond-> (assoc check :observes (vec (filter keyword? observes)))
                                          description (assoc :description (str description))
                                          (true? writes) (assoc :writes true)
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
                          :else (some-> x pr-str)))
      running #(some-> clojure.test/*testing-vars* first meta :name str)
      side #(if (string? %%) %% (pr-str %%))
      ;; (is (= a b)) failing reports (not (= a b)): its two sides, for zent.diff
      sides (fn [a] (when (and (seq? a) (= 'not (first a)) (seq? (second a)) (= '= (first (second a)))
                               (= 3 (count (second a))))
                      (mapv side (rest (second a)))))
      ;; a failure on values (:zent/group or :zent/context): its own :expected and :actual are the sides
      on-values (fn [{g :zent/group x :zent/explain c :zent/context :as m}]
                  (when (or g c)
                    (cond-> {:sides [(side (:expected m)) (side (:actual m))]}
                      g (assoc :group (shown g))
                      x (assoc :explain (shown x))
                      (map? c) (assoc :where (mapv (fn [[k v]] [(if (keyword? k) (name k) (str k)) (shown v)]) c)))))]
  (binding [clojure.test/report
            (fn [{t :type :as m}]
              (case t
                :begin-test-var
                (let [{test :name :keys [doc] kind :zent/kind title :zent/title} (-> m :var meta)]
                  (emit (cond-> {:event :begin :test (str test)} doc (assoc :doc doc) kind (assoc :kind kind)
                          title (assoc :title title))))
                :end-test-var
                (emit {:event :end :test (-> m :var meta :name str)})
                (:pass :fail :error)
                (emit (cond-> {:event t :test (running)}
                        (not= :pass t)
                        (merge {:message (shown (:message m)) :expected (pr-str (:expected m))
                                :actual (shown (:actual m)) :file (:file m) :line (:line m)
                                :context (not-empty (clojure.test/testing-contexts-str))}
                               (or (on-values m) (when-let [s (sides (:actual m))] {:sides s})))))
                :zent/note
                (emit {:event :note :test (running) :note (shown (:note m))})
                :zent/verdict
                (emit {:event :verdict :test (running) :verdict (shown (:verdict m))})
                :zent/fact
                (emit {:event :fact :test (running) :fact [(shown (:label m)) (shown (:value m)) (boolean (:bad m))]})
                :zent/accepted
                (emit {:event :accepted :test (running) :reason (shown (:reason m))})
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
  "The argv running `check`'s deftests, in file order - only those of `tests`
  (names) it has, when given: a preset's checks needn't all have them."
  [{:keys [ns]} tests]
  (let [only (when (seq tests)
               (format "(filter (comp %s str :name meta))" (pr-str (set (map (comp str test-name!) tests)))))
        vars (format "(->> (ns-interns '%s) vals (filter (comp :test meta)) %s (sort-by (comp :line meta)))" ns (or only ""))]
    ["clojure" "-M" "-e" (format runner ns vars (pr-str event-prefix))]))

(defn- verdict
  "Jepsen's merge of :valid?: something proven wrong beats can't-tell."
  [statuses]
  (cond (some #{:fail} statuses) :fail
        (or (empty? statuses) (some #{:error} statuses)) :unknown
        :else :pass))

;; a test's failures kept in its result; :groups count them all
(def ^:private max-failures 50)

(defn- shape
  "What failures of one kind share: their :group and their diff's shape - its ops, or a map's keys
  and ops - else their message with numbers and quoted strings masked."
  [{:keys [group diff message]}]
  [group (cond (:keys diff) (mapv (juxt :key :op) (:keys diff))
               (:segments diff) (mapv first (:segments diff))
               :else (some-> message (str/replace #"\"[^\"]*\"" "\"…\"") (str/replace #"\d+" "#")))])

(defn- groups
  "`failures` by shape, the largest first: a group's count, :group and :explain, a few examples."
  [failures]
  (->> (vals (group-by shape failures))
       (map (fn [fs] (let [{:keys [group explain]} (first fs)]
                       (cond-> {:count (count fs) :examples (mapv #(dissoc % :group :explain) (take 5 fs))}
                         group (assoc :group group)
                         explain (assoc :explain explain)))))
       (sort-by (comp - :count))
       vec))

(defn parse
  "A run's result from its stdout `lines` and exit code: tests in run order,
  each :pass/:accepted/:fail/:error with its assertion count, failures (the first
  max-failures) and their :groups."
  [lines exit]
  (let [events (keep #(when (str/starts-with? % event-prefix) (edn/read-string (subs % (count event-prefix)))) lines)
        done? (some #(= :done (:event %)) events)
        events (remove #(= :done (:event %)) events)
        tests (reduce (fn [acc {:keys [event test] :as e}]
                        (let [i (or (first (keep-indexed #(when (= test (:name %2)) %1) acc))
                                    (count acc))
                              acc (if (= i (count acc)) (conj acc {:name test :status :pass :assertions 0 :failures []}) acc)
                              diff (some-> (:sides e) diff/of)
                              detail (cond-> (dissoc e :event :test :sides) diff (assoc :diff diff))]
                          (case event
                            :begin (update acc i merge (select-keys e [:doc :kind :title]))
                            :end (assoc-in acc [i :done?] true)
                            :note (update-in acc [i :notes] (fnil conj []) (:note e))
                            :verdict (assoc-in acc [i :verdict] (:verdict e))
                            :fact (update-in acc [i :facts] (fnil conj []) (:fact e))
                            :accepted (update-in acc [i :accepted] (fnil conj []) (:reason e))
                            :pass (update-in acc [i :assertions] inc)
                            :fail (-> acc (update-in [i :assertions] inc) (update-in [i :failures] conj detail)
                                      (update-in [i :status] #(if (= :error %) % :fail)))
                            :error (-> acc (update-in [i :failures] conj detail) (assoc-in [i :status] :error)))))
                      [] events)
        ;; begun, never ended: the run died inside it - a failure already seen stays one;
        ;; a passing one with an accepted gap is :accepted
        tests (mapv #(cond-> (dissoc % :done?)
                       (and (not (:done? %)) (= :pass (:status %))) (assoc :status :error)
                       (and (:done? %) (= :pass (:status %)) (:accepted %)) (assoc :status :accepted)
                       (seq (:failures %)) (assoc :groups (groups (:failures %))
                                                  :failures (vec (take max-failures (:failures %)))))
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

(def ^:private glyph {:pass "✓" :accepted "≈" :fail "✗" :error "!" :unknown "?"})

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
   (for [{:keys [name title status assertions failures doc kind notes verdict facts accepted]} tests
         line (concat [(format "  %s %s%s%s  %d" (glyph status) name (if title (str " - " title) "")
                               (if kind (str " [" (clojure.core/name kind) "]") "") assertions)]
                      (when verdict [(str "      => " verdict)])
                      (when (seq facts) [(str "      " (str/join " · " (for [[l v] facts] (str l ": " v))))])
                      (map #(str "      accepted: " %) accepted)
                      ;; what the test judged, passing or not
                      (map #(str "      · " %) (mapcat str/split-lines notes))
                      ;; the why of a test, where it matters: when it doesn't pass
                      (when (and doc (not (#{:pass :accepted} status)))
                        (map #(str "      # " (str/trim %)) (str/split-lines doc)))
                      (for [{:keys [message context expected actual file line diff group where]} failures
                            l (into [(str "      " (str/join " " (remove nil? [(some->> group (format "[%s]")) context
                                                                         (some->> (seq where) (map (fn [[k v]] (str k " " v)))
                                                                                  (str/join " · "))
                                                                         message]))
                                          (when file (format "  (%s:%s)" file line)))]
                                    (if diff
                                      (map #(str "      " %) (diff/lines diff))
                                      [(str "      expected: " expected)
                                       (str "        actual: " actual)]))]
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
  *dir* and returns it. :none-matched when it has none of `tests`: nothing
  judged, nothing kept."
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
    (if (and (seq tests) (empty? (:tests parsed)) (not (:error result)))
      (assoc result :none-matched true)
      (do (io/make-parents f)
          (spit f (pr-str result))
          result))))
