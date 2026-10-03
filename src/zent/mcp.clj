(ns zent.mcp
  "`zent mcp`: an MCP server on stdio (newline-delimited JSON-RPC 2.0), so an
  agent can read the catalog, compose presets and drive the daemon. Each tool
  is the matching zent.cli verb, answering with the text that verb prints -
  one behaviour, three front ends (CLI, dashboard, MCP).

  stdout is the protocol channel: while serving, anything else printed goes
  to stderr, and tool output is captured per call."
  (:require [clojure.data.json :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [zent.cli :as cli]
            [zent.schema :as schema]))

(def ^:private protocol-version
  "Answered when the client asks for none; otherwise its own is echoed back."
  "2025-06-18")

(def ^:private instructions
  "zent runs local dev stacks from a catalog of components. Typical flow:
describe_catalog to see components and their fields, save_preset to compose
one, preview to check what it would run, apply to start it (it waits until
everything settled), then status/logs/reload/stop. Applying a preset stops
what it doesn't name, except :permanent components and :keep-warm ones
(left running as idle for the next preset); one already running is
redeployed if the preset configures it differently. One-shots (seeding
scripts) end as done; status shows not run/queued/running for them. A
preset is EDN: a vector of component names, each optionally followed by a
map of overrides, e.g. [:db :api {:branch \"feature/x\"}]. A saved
preset may only choose components and set :branch/:image/:port/:context/
:namespace-style keys (and :permanent/:keep-warm), never what code runs
(:cmd, :env, ...); contexts outside the catalog's allowlists
are refused. :use-secret-env true switches a component to the real
credentials its catalog entry points at, if it declares any.")

(defn- catalog-description [ctx]
  (let [{:keys [defaults components]} (cli/catalog ctx)]
    (with-out-str
      (pp/pprint {:defaults defaults
                  :components components
                  :kind-fields (schema/describe-kinds)}))))

(defn- prop [type description] {:type type :description description})

(defn- arg
  "`args`' `k`, checked: an agent gets a usable message instead of whatever
  a nil or a wrong type would throw deeper down. Absent and optional is nil."
  [args k & {:keys [type optional?] :or {type :string}}]
  (let [v (get args k)
        ok? (case type
              :string (and (string? v) (seq v))
              ;; non-empty: `stop` with [] meaning "nothing" must not read as
              ;; omitted, which means everything
              :strings (and (sequential? v) (seq v) (every? string? v))
              :int (integer? v))]
    (cond
      ok? v
      (and optional? (nil? v)) nil
      :else (throw (ex-info (format "argument %s: expected %s, got %s" (name k)
                                    ({:string "a non-empty string" :strings "a non-empty array of strings" :int "an integer"} type)
                                    (pr-str v))
                            {:argument k})))))

(defn- tools [ctx]
  [{:name "status" :description "What the daemon is running, per component."
    :call (fn [_] (cli/status!))}
   {:name "list_presets" :description "Preset names: * = active, + = user-saved (deletable)."
    :call (fn [_] (cli/presets! ctx))}
   {:name "describe_catalog"
    :description "Every component (kind, deps, repo, defaults) and each kind's fields - what a preset can name and override."
    :call (fn [_] (print (catalog-description ctx)))}
   {:name "preview" :description "What a preset would run (resolved and validated) and, with a daemon up, what applying it would stop, start or redeploy and why - without doing it."
    :props {:preset (prop "string" "preset name")} :required ["preset"]
    :call (fn [args] (cli/preview! ctx (keyword (arg args :preset))))}
   {:name "save_preset"
    :description "Create or replace a user preset from an EDN spec, validated against the catalog; a running daemon learns it immediately."
    :props {:name (prop "string" "preset name (not one of the catalog's own)")
            :spec (prop "string" "EDN vector, e.g. [:db :api {:branch \"x\"}]")}
    :required ["name" "spec"]
    :call (fn [args] (cli/save-preset! ctx (keyword (arg args :name)) (arg args :spec)))}
   {:name "delete_preset" :description "Delete a user preset."
    :props {:name (prop "string" "preset name")} :required ["name"]
    :call (fn [args] (cli/delete-preset! ctx (keyword (arg args :name))))}
   {:name "apply"
    :description "Switch the running stack to a preset (starting the daemon if needed); blocks until it settled and reports each component. Components not in it are stopped, except :permanent ones and :keep-warm ones (kept running, idle); a running one the preset configures differently is redeployed."
    :props {:preset (prop "string" "preset name")} :required ["preset"]
    :call (fn [args] (cli/apply! ctx (keyword (arg args :preset))))}
   {:name "stop"
    :description "Stop the given components (even :permanent ones), or with none, everything but :permanent ones."
    :props {:components {:type "array" :items {:type "string"} :description "component names; omit for all"}}
    :call (fn [args] (cli/stop! (arg args :components :type :strings :optional? true)))}
   {:name "reload" :description "Redeploy one running component now."
    :props {:component (prop "string" "component name")} :required ["component"]
    :call (fn [args] (cli/reload! (arg args :component)))}
   {:name "reload_code"
    :description "Reload the daemon's own code (after editing zent), keeping everything it runs."
    :call (fn [_] (cli/reload-code!))}
   {:name "logs" :description "The tail of a component's output (secret values zent read for it are masked)."
    :props {:component (prop "string" "component name")
            :lines (prop "integer" "how many lines (default 200, at most 500)")}
    :required ["component"]
    :call (fn [args] (cli/redacted-logs! ctx (arg args :component) (or (arg args :lines :type :int :optional? true) cli/log-tail-lines)))}])

(defn- tool-list [ctx]
  (mapv (fn [{:keys [name description props required]}]
          {:name name :description description
           :inputSchema (cond-> {:type "object" :properties (or props {})}
                          required (assoc :required required))})
        (tools ctx)))

(defn- call-tool
  "Runs the tool, capturing what it prints. A failure is a tool error the
  agent can act on, with any output before it."
  [ctx tool-name args]
  (let [out (java.io.StringWriter.)
        tool (first (filter #(= tool-name (:name %)) (tools ctx)))
        [text error?] (if-not tool
                        [(str "unknown tool " tool-name) true]
                        (try (binding [*out* out] ((:call tool) (or args {})))
                             [(str out) false]
                             ;; any failure (a refused preset, a daemon gone
                             ;; mid-request) is the agent's to read, not a crash
                             (catch Exception e
                               [(str out (or (ex-message e) (str e))) true])))]
    {:content [{:type "text" :text (if (empty? text) "done" text)}] :isError error?}))

(defn handle
  "The response to one JSON-RPC message, or nil for a notification."
  [ctx {:keys [id method params]}]
  (let [reply #(assoc % :jsonrpc "2.0" :id id)]
    (when id
      (case method
        "initialize" (reply {:result {:protocolVersion (or (:protocolVersion params) protocol-version)
                                      :capabilities {:tools {}}
                                      :serverInfo {:name "zent" :version "0.1.0"}
                                      :instructions instructions}})
        "ping" (reply {:result {}})
        "tools/list" (reply {:result {:tools (tool-list ctx)}})
        "tools/call" (reply {:result (call-tool ctx (:name params) (:arguments params))})
        (reply {:error {:code -32601 :message (str "method not found: " method)}})))))

(defn serve!
  "Serves MCP on stdin/stdout until stdin closes."
  [ctx]
  (let [protocol *out*]
    (binding [*out* *err*]
      (loop []
        (when-let [line (read-line)]
          (when-not (str/blank? line)
            (let [response (try (handle ctx (json/read-str line :key-fn keyword))
                                (catch Exception e
                                  {:jsonrpc "2.0" :id nil
                                   :error {:code -32700 :message (str "bad request: " (ex-message e))}}))]
              (when response
                (binding [*out* protocol]
                  (println (json/write-str response))
                  (flush)))))
          (recur))))))
