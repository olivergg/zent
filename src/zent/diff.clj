(ns zent.diff
  "A failed (= expected actual)'s two sides, compared for display: word by word, a replaced word
  refined by character when it mostly lies in its pair (a padded id, an added suffix) - a decimal
  stays whole: 17.6 -> 20.65 isn't \"17 became 20\" - and key by key when both sides are maps (EDN
  or JSON; an empty side is an empty map). Segments are [op text], op :same, :del (expected only) or
  :add (actual only)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; beyond this, an edit script costs more than it shows
(def ^:private max-cells 250000)

(defn- script
  "The edit script turning `a` into `b` (sequences): [op x], longest common subsequence first."
  [a b]
  (let [a (vec a) b (vec b) n (count a) m (count b)
        at #(get-in %1 [%2 %3] 0)
        t (reduce (fn [t i]
                    (assoc t i (reduce (fn [row j]
                                         (assoc row j (if (= (a i) (b j)) (inc (at t (inc i) (inc j)))
                                                          (max (at t (inc i) j) (get row (inc j) 0)))))
                                       (vec (repeat (inc m) 0)) (range (dec m) -1 -1))))
                  (vec (repeat (inc n) [])) (range (dec n) -1 -1))]
    (loop [i 0 j 0 out []]
      (cond (and (< i n) (< j m) (= (a i) (b j))) (recur (inc i) (inc j) (conj out [:same (a i)]))
            (and (< i n) (or (= j m) (>= (at t (inc i) j) (at t i (inc j))))) (recur (inc i) j (conj out [:del (a i)]))
            (< j m) (recur i (inc j) (conj out [:add (b j)]))
            :else out))))

(defn- tokens [s] (re-seq #"[A-Za-z0-9À-ÿ_.:-]+|\s+|." s))

(defn- similar? [x y]
  (and (not-any? #(re-matches #"-?\d+\.\d+" %) [x y])
       (>= (count (filter (comp #{:same} first) (script x y))) (* 0.8 (min (count x) (count y))))))

(defn segments
  "`a` against `b`, both strings, adjacent segments of one kind merged."
  [a b]
  (->> (loop [[[o x :as op] & more] (script (tokens a) (tokens b)) out []]
         (let [[o2 y] (first more)]
           (cond (nil? op) out
                 (and (= :del o) (= :add o2) (similar? x y))
                 (recur (rest more) (into out (map (fn [[c ch]] [c (str ch)])) (script x y)))
                 :else (recur more (conj out op)))))
       (reduce (fn [acc [o t]]
                 (let [[po pt] (peek acc)]
                   (if (= o po) (conj (pop acc) [o (str pt t)]) (conj acc [o t]))))
               [])))

(defn- as-map [s]
  (cond (str/blank? s) {}
        (str/starts-with? (str/trim s) "{")
        (let [m (try (if (re-find #"^\{\s*\"" (str/trim s)) (json/read-str s) (edn/read-string s))
                     (catch Exception _ nil))]
          (when (map? m) m))))

(defn- shown [v] (if (string? v) v (pr-str v)))

(defn- keyed [a b]
  (vec (for [k (distinct (concat (keys a) (keys b)))
             :let [x (get a k) y (get b k) row {:key (shown k)}]]
         (cond (not (contains? a k)) (assoc row :op :add :value (shown y))
               (not (contains? b k)) (assoc row :op :del :value (shown x))
               (= x y) (assoc row :op :same :value (shown x))
               :else (assoc row :op :changed :segments (segments (shown x) (shown y)))))))

(defn of
  "The diff of a failure's :sides, [expected actual] as strings: {:keys rows} for two maps, else
  {:segments ...}; nil when too long to be worth it."
  [[a b]]
  (when (<= (* (count (tokens a)) (count (tokens b))) max-cells)
    (let [ma (as-map a) mb (as-map b)]
      (if (and ma mb (or (seq ma) (seq mb)))
        {:keys (keyed ma mb)}
        {:segments (segments a b)}))))

(defn- marked
  "One side of `segs`, its changes in git --word-diff markers: [-…-] expected, {+…+} actual."
  [segs side]
  (let [[open close] (if (= :del side) ["[-" "-]"] ["{+" "+}"])]
    (apply str (for [[o t] segs :when (#{:same side} o)] (if (= :same o) t (str open t close))))))

(defn inline
  "A diff (of) on one line, its sides plain: \"a -> b\", or a map's changed keys."
  [{:keys [segments keys]}]
  (let [side (fn [segs op] (apply str (for [[o t] segs :when (#{:same op} o)] t)))]
    (if keys
      (str/join " · " (for [{:keys [key op value] segs :segments} keys :when (not= :same op)]
                        (str key " " (if segs (str (side segs :del) " -> " (side segs :add)) value))))
      (str (side segments :del) " -> " (side segments :add)))))

(defn lines
  "A diff (of) as text lines, labelled like clojure.test's report."
  [{:keys [segments keys]}]
  (if keys
    (for [{:keys [key op value] segs :segments} keys :when (not= :same op)]
      (case op
        :add (str "+ " key " " value)
        :del (str "- " key " " value)
        :changed (str "~ " key " " (marked segs :del) " -> " (marked segs :add))))
    [(str "expected: " (marked segments :del))
     (str "  actual: " (marked segments :add))]))
