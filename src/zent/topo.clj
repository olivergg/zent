(ns zent.topo
  "Dependency ordering (:deps), enforced rather than left to
  map order. Kahn-style: repeatedly peel off what has no pending deps; what
  can't be peeled *is* the cycle."
  (:require [clojure.set :as set]))

(defn- deps-in-scope
  "cfg's :deps present in `components` - a dep outside the preset is
  ignored."
  [components cfg]
  (filterv (set (keys components)) (:deps cfg)))

(defn topo-sort
  "[kw cfg] pairs of `components` ({kw {:deps [...]}}), each after its
  in-scope :deps, ties in input order. Throws ex-info (:type ::cycle) naming
  what's left when a cycle blocks the peeling."
  [components]
  (let [ks (keys components)
        deps (into {} (map (fn [k] [k (set (deps-in-scope components (get components k)))])) ks)
        emit-ready? (fn [emitted k] (set/subset? (deps k) emitted))]
    (loop [remaining (vec ks)
           emitted #{}
           out []]
      (if (empty? remaining)
        out
        (let [ready (filterv (partial emit-ready? emitted) remaining)]
          (if (empty? ready)
            (throw (ex-info (str "zent.topo/topo-sort: dependency cycle among " (vec remaining))
                             {:type ::cycle :cycle (vec remaining)}))
            (recur (vec (remove (set ready) remaining))
                   (into emitted ready)
                   (into out (map (fn [k] [k (get components k)])) ready))))))))
