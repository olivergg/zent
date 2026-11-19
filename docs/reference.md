# Reference

Generated from `src/zent/schema.clj` - don't edit: `jolt run -m zent.reference > docs/reference.md`. The words: `docs/glossary.md`.

## A catalog (`catalog.edn`)

| Key | Type | Required | What |
|---|---|---|---|
| `:name` | keyword |  | The catalog's name, required: it keys your saved presets (~/.config/zent/presets/\<name>/). |
| `:defaults` | map |  | Keys merged under every component (below). |
| `:components` | map | yes | The components, {name {:kind ... & its keys}}. |
| `:presets` | map | yes | Not written in catalog.edn: read from presets/\<name>.edn. |
| `:checks` | map |  | Not written in catalog.edn: found under checks/\<preset>/. |
| `:dir` | string |  | Not written in catalog.edn: the dir it was loaded from. |
| `:max-parallel` | int |  | Components deployed at once (3 by default). |

## `:defaults`

Any component key below, merged under every component, and these, catalog-wide:

| Key | Type | Required | What |
|---|---|---|---|
| `:repo-url-template` | string |  | A repo's web URL, with {org} and {repo} replaced by a component's :org and :repo: the dashboard's link to it. |
| `:allow-secret-contexts` | list of string |  | The k8s contexts a :secret-env may read from: the catalog's only. |

## Every component

Whatever its `:kind`:

| Key | Type | Required | What |
|---|---|---|---|
| `:deps` | list of keyword |  | Components it starts after, each once ready (its :readiness). |
| `:mode` | :on, :off |  | Whether it takes part in the run. Naming it in a preset sets :on. |
| `:readiness` | map |  | What "ready" means: the probe an apply waits on after deploying it (:type :http-poll, :url, :timeout-s - 60 by default). |
| `:watch` | map |  | Files whose change redeploys it: :paths (relative to its checkout), :exts, :ignore. Not for a component running from zent's worktree. |
| `:branch` | string |  | The branch the preset wants it on; :from says which checkout runs it. Without one, its main worktree runs as it is. |
| `:from` | :origin-worktree, :local-worktree, :main-worktree |  | With :branch, which checkout runs it (docs/glossary.md): :origin-worktree (the default - zent's worktree at origin/\<branch>, what's pushed), :local-worktree (that worktree at your local \<branch>, what's committed) or :main-worktree (your clone as it is, refused unless on \<branch>). |
| `:workspace-dir` | string |  | Where its main worktree lives: \<workspace-dir>/\<repo>, ~/workspace by default. |
| `:org` | string |  | The GitHub organisation of its repo, for the git clone command zent prints when the repo is missing. |
| `:related` | list of keyword |  | Components it's coupled to, shown in the dashboard - no start order, no readiness wait (that's :deps). |
| `:permanent` | boolean |  | Never stopped implicitly: a preset switch, a stop-everything or zent exiting leave it up; only a stop naming it does. For a shared dependency meant to outlive any one run. |
| `:keep-warm` | boolean |  | Left running, idle, by a preset switch that doesn't need it, for the next one that does; a stop-everything or zent exiting still stop it. |
| `:links` | list of map |  | URLs worth opening, each {:url :label}: the card's links. |

## `:kind :compose-services`

Some services of a compose file, not the whole stack - for a file that carries both a webapp and the infra another component wants alone; torn down by service, not by project.

| Key | Type | Required | What |
|---|---|---|---|
| `:repo` | string | yes | Its repo's dir name under :workspace-dir: what runs from its main worktree, or zent's worktree of it. |
| `:services` | list of keyword |  | The services to run, an allow-list: all the file declares when absent. |
| `:exclude` | list of keyword |  | Services to drop from the file's. |
| `:compose-file` | string |  | The compose file, relative to its checkout (compose's default file when absent). |
| `:project-name` | string |  | The compose project name (compose's default when absent). |
| `:follow-logs` | boolean |  | false keeps its containers' output out of its log (a chatty one's). |

## `:kind :docker-compose`

A whole compose file: up --wait, down.

| Key | Type | Required | What |
|---|---|---|---|
| `:repo` | string | yes | Its repo's dir name under :workspace-dir: what runs from its main worktree, or zent's worktree of it. |
| `:build-cmd` | string |  | A command run in its checkout before it starts (a build). |
| `:allow-parallel` | boolean |  | Its local build or scripts run alongside other components' instead of one at a time. |
| `:compose-file` | string |  | The compose file, relative to its checkout (compose's default file when absent). |
| `:project-name` | string |  | The compose project name (compose's default when absent). |
| `:profiles` | list of string |  | Compose profiles to enable. |
| `:follow-logs` | boolean |  | false keeps its containers' output out of its log (a chatty one's). |

## `:kind :external`

Provided by something zent didn't start (an IDE-run service): nothing is launched, the :readiness probe is its deploy - which lets dependents still start after it.

No keys of its own.

## `:kind :k8s`

Pre-built manifests applied to a k8s cluster - not implemented yet. Reaching it locally is a separate :process running kubectl port-forward.

| Key | Type | Required | What |
|---|---|---|---|
| `:repo` | string | yes | Its repo's dir name under :workspace-dir: what runs from its main worktree, or zent's worktree of it. |
| `:manifests` | list of string | yes | Manifest files, relative to its checkout. |
| `:workload` | string | yes | A kubectl ref (deployment/x, statefulset/y): its rollout, readiness and logs. |
| `:context` | string | yes | The kubectl context, always explicit - one :allow-k8s-contexts lists. |
| `:namespace` | string | yes | The namespace it's applied to. |
| `:allow-k8s-contexts` | list of string |  | The contexts a :context may name: the catalog's only. |
| `:strip-affinity` | boolean |  | Drop affinity and tolerations blocks: a one-node cluster lacks their node labels. |
| `:image-placeholder` | string |  | Text in the manifests replaced by the image. |
| `:image` | string |  | The image the placeholder becomes. |
| `:image-registry` | string |  | The registry the image is pulled from. |
| `:pull-secret` | map |  | The image pull secret it creates, {:name :server :username :password-cmd}. |
| `:rollout-timeout` | int |  | Seconds to wait for its rollout. |
| `:follow-logs` | boolean |  | false keeps its containers' output out of its log (a chatty one's). |

## `:kind :one-shot`

Scripts run once, ending done or failed (a seed, a replay, a purge).

| Key | Type | Required | What |
|---|---|---|---|
| `:repo` | string | yes | Its repo's dir name under :workspace-dir: what runs from its main worktree, or zent's worktree of it. |
| `:scripts` | list of string | yes | Commands run one after the other in its checkout; the first failing one fails it. |
| `:attached-to` | keyword |  | Display only: drawn as a strip under that component's card (the one it prepares). |
| `:description` | string |  | Display only: one line on what its scripts do. |
| `:pre-check` | map |  | A probe waited on before its scripts run, as :readiness is after. |
| `:on-demand` | boolean |  | Never run by an apply, only by a reload (the card's Run): listed in a preset, it waits "on demand" - for a destructive one. |
| `:confirm` | string |  | Display only: the question the dashboard's Run button asks first. |
| `:env` | {string string} |  | Env vars for its build and run, {"VAR" "value"}. |
| `:source-env` | map |  | Env vars set to a checkout dir, {VAR {:component \| :repo, :path}}: a :component's, as the preset runs it (its worktree, else its main worktree), or a :repo's main worktree, as it is on disk. |
| `:allow-parallel` | boolean |  | Its local build or scripts run alongside other components' instead of one at a time. |
| `:secret-env` | map |  | Env vars read from a k8s Secret at deploy time, {VAR {:context :namespace :secret :key}}: where to read, the catalog's only. Off until :use-secret-env. |
| `:use-secret-env` | boolean |  | Read its :secret-env (false by default) - from a context the catalog's :allow-secret-contexts lists. |

## `:kind :process`

A long-lived command, in a checked-out repo and built first, or none (a kubectl port-forward); tracked by its pid.

| Key | Type | Required | What |
|---|---|---|---|
| `:repo` | string |  | Its repo's dir name under :workspace-dir: what runs from its main worktree, or zent's worktree of it. |
| `:cmd` | string | yes | The command it runs, in its checkout (or zent's dir without :repo), until stopped. |
| `:build-cmd` | string |  | A command run in its checkout before it starts (a build). |
| `:env` | {string string} |  | Env vars for its build and run, {"VAR" "value"}. |
| `:source-env` | map |  | Env vars set to a checkout dir, {VAR {:component \| :repo, :path}}: a :component's, as the preset runs it (its worktree, else its main worktree), or a :repo's main worktree, as it is on disk. |
| `:port` | int |  | The port it listens on: shown on its card and in its log. |
| `:allow-parallel` | boolean |  | Its local build or scripts run alongside other components' instead of one at a time. |
| `:secret-env` | map |  | Env vars read from a k8s Secret at deploy time, {VAR {:context :namespace :secret :key}}: where to read, the catalog's only. Off until :use-secret-env. |
| `:use-secret-env` | boolean |  | Read its :secret-env (false by default) - from a context the catalog's :allow-secret-contexts lists. |

## `:kind :quarkus-app`

A Quarkus app: mvn package, then target/quarkus-app/quarkus-run.jar.

| Key | Type | Required | What |
|---|---|---|---|
| `:repo` | string | yes | Its repo's dir name under :workspace-dir: what runs from its main worktree, or zent's worktree of it. |
| `:port` | int | yes | Its HTTP port: QUARKUS_HTTP_PORT. |
| `:env` | {string string} |  | Env vars for its build and run, {"VAR" "value"}. |
| `:source-env` | map |  | Env vars set to a checkout dir, {VAR {:component \| :repo, :path}}: a :component's, as the preset runs it (its worktree, else its main worktree), or a :repo's main worktree, as it is on disk. |
| `:jdk-home` | string |  | The JDK it's built (mvn package) and run with: JAVA_HOME and PATH. |
| `:allow-parallel` | boolean |  | Its local build or scripts run alongside other components' instead of one at a time. |
| `:secret-env` | map |  | Env vars read from a k8s Secret at deploy time, {VAR {:context :namespace :secret :key}}: where to read, the catalog's only. Off until :use-secret-env. |
| `:use-secret-env` | boolean |  | Read its :secret-env (false by default) - from a context the catalog's :allow-secret-contexts lists. |

## In a preset

A preset names components, each with keys overriding its catalog entry:

- a repo preset (`presets/<name>.edn`, reviewed): any key but `:allow-k8s-contexts` `:allow-secret-contexts` `:secret-env`;
- your preset (`zent preset save`, the MCP's `save_preset`): only `:branch` `:context` `:deps` `:exclude` `:from` `:image` `:keep-warm` `:kind` `:mode` `:namespace` `:permanent` `:port` `:profiles` `:services` `:use-secret-env`;
- on a component with a `:secret-env`, either one: only `:branch` `:deps` `:from` `:kind` `:mode` `:use-secret-env`.
