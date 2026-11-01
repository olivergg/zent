(ns zent.usage
  "Each running component's CPU and memory, for the dashboard: one `ps` for every local process
  tree, one `docker ps` + one `docker stats` for every container - whatever the component count.
  Read off the handles' shape (a Process or :pid, a compose file), never off a kind: a handle with neither
  (:external, a one-shot, k8s) has no usage. Pure but for `sample!`.

  CPU is a % of one core (100 = one core busy), like `top` and `docker stats`. A process tree's is
  its cumulative CPU time over the wall time since the last sample: `ps`'s own %cpu is a decaying
  average on macOS and a lifetime one on Linux, used only for the first sample."
  (:require [clojure.string :as str]))

(defn- lines [s] (remove str/blank? (str/split-lines (str s))))

;; ---------------------------------------------------------------------------
;; ps
;; ---------------------------------------------------------------------------

(def ps-cmd
  "Every process: pid, parent, cumulative CPU time, ps's %cpu, RSS in KiB. Run under LC_ALL=C: a
  French locale writes 31,1."
  ["ps" "-axo" "pid=,ppid=,time=,%cpu=,rss="])

(defn cputime
  "`ps`'s TIME column in seconds: macOS `77:45.66` (minutes:seconds), Linux `[dd-]hh:mm:ss`."
  [s]
  (let [[days hms] (if (str/includes? s "-") (str/split s #"-" 2) [nil s])
        parts (map parse-double (str/split hms #":"))
        [h m sec] (case (count parts) 3 parts 2 (cons 0 parts) [0 0 (first parts)])]
    (when (and h m sec)
      (+ (* 86400 (or (some-> days parse-long) 0)) (* 3600 h) (* 60 m) sec))))

(defn parse-ps
  "ps-cmd's output as {pid {:ppid :cpu-s :pcpu :rss}}, :rss in bytes; unreadable lines skipped."
  [out]
  (into {}
        (keep (fn [line]
                (let [[pid ppid time pcpu rss] (str/split (str/trim line) #"\s+")
                      pid (some-> pid parse-long)
                      cpu-s (some-> time cputime)]
                  (when (and pid cpu-s rss)
                    [pid {:ppid (parse-long ppid) :cpu-s cpu-s
                          :pcpu (or (parse-double pcpu) 0.0) :rss (* 1024 (or (parse-long rss) 0))}]))))
        (lines out)))

(defn tree
  "`root` and its descendants in `procs` (parse-ps): a quarkus-app or an npm script is a few
  processes deep. Empty when `root` is gone."
  [procs root]
  (if-not (contains? procs root)
    #{}
    (let [children (reduce-kv (fn [acc pid {:keys [ppid]}] (update acc ppid (fnil conj []) pid)) {} procs)]
      (loop [seen #{root} todo [root]]
        (if-let [[p & more] (seq todo)]
          (let [kids (remove seen (children p))]
            (recur (into seen kids) (into (vec more) kids)))
          seen)))))

(defn tree-usage
  "{:cpu :rss :procs} of `pids`. CPU from each one's CPU time since `prev` ({pid cpu-s}, taken
  `elapsed-s` ago) - a pid absent there was born since, all its time counts; without a previous
  sample, ps's %cpu."
  [procs pids prev elapsed-s]
  (let [ps (keep procs pids)
        cpu (if (and (seq prev) (pos? (or elapsed-s 0)))
              (* 100 (/ (reduce + (map (fn [pid] (max 0 (- (get-in procs [pid :cpu-s]) (get prev pid 0)))) pids))
                        elapsed-s))
              (reduce + (map :pcpu ps)))]
    {:cpu cpu :rss (reduce + (map :rss ps)) :procs (count ps)}))

;; ---------------------------------------------------------------------------
;; docker
;; ---------------------------------------------------------------------------

(def docker-ps-cmd
  "Running containers with the compose labels that tie each to a file and a service."
  ["docker" "ps" "--no-trunc" "--format"
   "{{.ID}}|{{.Label \"com.docker.compose.project.config_files\"}}|{{.Label \"com.docker.compose.service\"}}"])

(defn docker-stats-cmd
  "One sample of `ids` (about 2s: docker measures over an interval)."
  [ids]
  (into ["docker" "stats" "--no-stream" "--no-trunc" "--format" "{{.ID}}|{{.CPUPerc}}|{{.MemUsage}}"] ids))

(defn parse-docker-ps
  "docker-ps-cmd's output as [{:id :file :service}], :file the first config file (compose
  joins several with a comma); containers compose didn't start skipped."
  [out]
  (vec (keep (fn [line]
               (let [[id files service] (str/split line #"\|" -1)]
                 (when (and (seq id) (seq files) (seq service))
                   {:id id :file (first (str/split files #",")) :service (keyword service)})))
             (lines out))))

(def ^:private units
  {"B" 1 "kB" 1e3 "KB" 1e3 "MB" 1e6 "GB" 1e9 "TB" 1e12
   "KiB" 1024 "MiB" 1048576 "GiB" 1073741824 "TiB" 1099511627776})

(defn size
  "`1.796GiB` / `648.4MiB` / `0B` in bytes; nil when unreadable."
  [s]
  (when-let [[_ n unit] (re-matches #"\s*([\d.]+)\s*([A-Za-z]+)\s*" (str s))]
    (when-let [k (units unit)] (long (* k (parse-double n))))))

(defn parse-docker-stats
  "docker-stats-cmd's output as {id {:cpu :rss}} - `1.22%`, `1.634GiB / 11.65GiB` (used / limit)."
  [out]
  (into {}
        (keep (fn [line]
                (let [[id cpu mem] (str/split line #"\|" -1)
                      cpu (some->> cpu (re-find #"[\d.]+") parse-double)
                      rss (some-> mem (str/split #"/") first size)]
                  (when (and (seq id) cpu rss) [id {:cpu cpu :rss rss}]))))
        (lines out)))

;; ---------------------------------------------------------------------------
;; handles -> usage
;; ---------------------------------------------------------------------------

(defn compose-file
  "The compose file a handle's containers carry in their config_files label, or nil."
  [{:keys [repo-dir compose-opts] :as handle}]
  (when (and (map? handle) repo-dir compose-opts)
    (str repo-dir "/" (or (:file compose-opts) "docker-compose.yaml"))))

(defn pid-of
  "A local process handle's pid: the live Process a deploy returns, or the {:kind :pid} the session
  persisted (an adopted one); nil for any other handle."
  [handle]
  (cond (instance? Process handle) (.pid ^Process handle)
        (and (map? handle) (= :pid (:kind handle))) (:pid handle)))

(defn containers-of
  "`containers` (parse-docker-ps) a handle owns: its file's, and only its :services when it lists
  them - a :compose-services takes a few of a file that may hold somebody else's."
  [handle containers]
  (when-let [file (compose-file handle)]
    (let [mine (set (:services handle))]
      (filter #(and (= file (:file %)) (or (empty? mine) (mine (:service %)))) containers))))

(defn usage
  "{name {:cpu :rss ...}} from a sample: `procs` (parse-ps) and `prev` ({pid cpu-s}) `elapsed-s`
  apart, `containers` (parse-docker-ps) and their `stats` (parse-docker-stats). A pid handle gets
  :procs, a compose one :parts (per service, busiest first). A handle measuring nothing - its
  process gone, docker silent - is left out: unknown, not zero."
  [handles {:keys [procs prev elapsed-s containers stats]}]
  (into {}
        (keep (fn [[name handle]]
                (cond
                  (pid-of handle)
                  (let [pids (tree procs (pid-of handle))]
                    (when (seq pids) [name (tree-usage procs pids prev elapsed-s)]))

                  (compose-file handle)
                  (let [parts (->> (containers-of handle containers)
                                   (keep (fn [{:keys [id service]}] (some-> (stats id) (assoc :name service))))
                                   (sort-by (comp - :cpu))
                                   vec)]
                    (when (seq parts)
                      [name {:cpu (reduce + (map :cpu parts)) :rss (reduce + (map :rss parts)) :parts parts}])))))
        handles))

;; ---------------------------------------------------------------------------
;; what's shown: rounded (so a push means a visible change) and judged
;; ---------------------------------------------------------------------------

(def thresholds
  "Where a component turns busy, then stressed: CPU in % of a core, RSS in bytes. Absolute, not
  a share of the machine: a dev JVM past 2.5 GiB is heavy on any laptop."
  {:cpu {:busy 60 :stressed 150}
   :rss {:busy (* 1024 1048576) :stressed (* 2560 1048576)}})

(defn level
  "`v` against `metric`'s thresholds: :calm, :busy or :stressed."
  [metric v]
  (let [{:keys [busy stressed]} (thresholds metric)]
    (cond (>= v stressed) :stressed (>= v busy) :busy :else :calm)))

(defn- round-cpu [cpu] (* 5 (Math/round (/ (double cpu) 5))))

(defn- round-rss
  "RSS to two significant digits in MiB: 1234 MiB reads 1200, 87 stays 87."
  [rss]
  (let [mib (/ (double rss) 1048576)
        step (if (< mib 100) 1 (Math/pow 10 (- (count (str (long mib))) 2)))]
    (long (* 1048576 step (Math/round (/ mib step))))))

(defn- rounded [{:keys [cpu rss] :as u}]
  (assoc u :cpu (round-cpu cpu) :rss (round-rss rss)))

(defn shown
  "`u` (usage) as the dashboard keeps it: CPU to 5%, RSS to two digits, each part likewise, and
  :level, the worse of the two - so two samples differing only in noise compare equal and push
  nothing."
  [u]
  (update-vals u (fn [c]
                   (let [c (cond-> (rounded c) (:parts c) (update :parts #(mapv rounded %)))]
                     (assoc c :level (max-key {:calm 0 :busy 1 :stressed 2}
                                              (level :cpu (:cpu c)) (level :rss (:rss c))))))))

;; ---------------------------------------------------------------------------
;; the one action
;; ---------------------------------------------------------------------------

(defn out-of
  "`cmd`'s stdout under LC_ALL=C, nil when it failed, couldn't run (docker down: no container usage)
  or outlived `timeout-ms` (default 5s: a frozen docker - colima - mustn't pile up hung `docker
  stats`). Through a file: a full pipe nobody reads yet would block it."
  [cmd & [timeout-ms]]
  (let [f (java.io.File/createTempFile "zent-usage" ".out")]
    (try (let [pb (doto (ProcessBuilder. ^java.util.List (mapv str cmd))
                    (.redirectOutput f)
                    (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))
               _ (.put (.environment pb) "LC_ALL" "C")
               p (.start pb)]
           (if (.waitFor p (long (or timeout-ms 5000)) java.util.concurrent.TimeUnit/MILLISECONDS)
             (when (zero? (.exitValue p)) (slurp f))
             (do (.destroyForcibly p) nil)))
         (catch Exception _ nil)
         (finally (.delete f)))))

(defn sample!
  "One sample of `handles` ({name handle}) after `last` (the previous sample!'s result, or nil):
  {:usage {name ...} :prev {pid cpu-s} :at ms}. docker only runs when a handle has containers
  (stats blocks ~2s), so call it off any loop that mustn't wait."
  [handles last]
  (let [at (System/currentTimeMillis)
        pid? (some pid-of (vals handles))
        procs (if pid? (parse-ps (out-of ps-cmd)) {})
        containers (if (some compose-file (vals handles)) (parse-docker-ps (out-of docker-ps-cmd)) [])
        ids (distinct (map :id (mapcat #(containers-of % containers) (vals handles))))
        stats (if (seq ids) (parse-docker-stats (out-of (docker-stats-cmd ids))) {})]
    {:usage (usage handles {:procs procs :prev (:prev last) :containers containers :stats stats
                            :elapsed-s (when (:at last) (/ (- at (:at last)) 1000.0))})
     :prev (update-vals procs :cpu-s)
     :at at}))
