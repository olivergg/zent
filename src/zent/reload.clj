(ns zent.reload
  "Hot-reloads a running daemon's own code: every loaded namespace under the
  given prefixes, dependencies first. defonce state survives; a fn captured
  as a value rather than a var keeps its old version - which is why
  zent.ui.bridge hands the engine vars."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- source-url [ns-sym]
  (io/resource (str (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/")) ".clj")))

(defn- requires-of
  "The namespaces `ns-sym`'s source file requires, read from its ns form -
  not ns-aliases, which misses a bare (:require [zent.kinds])."
  [ns-sym]
  (when-let [url (source-url ns-sym)]
    (let [[_ _ & clauses] (read-string (slurp url))]
      (for [clause clauses
            :when (and (seq? clause) (= :require (first clause)))
            spec (rest clause)]
        (if (sequential? spec) (first spec) spec)))))

(defn reload-order
  "`targets` sorted so each comes after the targets it requires."
  [targets requires-fn]
  (let [targets (set targets)
        visit (fn visit [[order seen :as acc] n]
                (if (seen n)
                  acc
                  (let [[order seen] (reduce visit [order (conj seen n)]
                                             (filter targets (requires-fn n)))]
                    [(conj order n) seen])))]
    (first (reduce visit [[] #{}] (sort targets)))))

(defn reload!
  "Reloads the loaded namespaces whose name starts with one of `prefixes`.
  Stops at the first that fails to load: those before it are already live.
  Returns {:reloaded [ns...]} plus :failed/:error on failure."
  [prefixes]
  (let [loaded (filter (fn [n] (some #(str/starts-with? (str n) %) prefixes))
                       (map ns-name (all-ns)))]
    (reduce (fn [acc n]
              (try
                (require n :reload)
                (update acc :reloaded conj n)
                (catch Throwable e
                  (reduced (assoc acc :failed n :error (or (ex-message e) (str e)))))))
            {:reloaded []}
            (reload-order loaded requires-of))))
