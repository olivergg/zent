# Glossary

One word per thing, the same in the CLI, the MCP server, the dashboard, the docs and the code. A name says what
you get, not how zent does it. Words marked *under review* keep their current meaning until their rename lands
(the renaming plan's steps 4 and 5).

## Where code runs from

| Word | Means | Not |
|---|---|---|
| **main worktree** | your clone of a repo, `~/workspace/<repo>` by default (`:workspace-dir`), as it is: uncommitted changes included - git's own name for it (`git worktree list`) | "the clone", "main clone", "working copy", "workdir", "folder", "your local clone" |
| **zent's worktree** | a worktree linked to it, which zent makes and owns, `~/.cache/zent/worktrees/<repo>-<branch>`: never edited by hand | "checkout" (that's the git verb, and the dashboard's button) |
| `:branch` | the branch a preset wants for a component ("the preset wants …") | - |
| `:from` | which checkout runs that `:branch` - only with a `:branch` | "mode" (that's `:mode`) |
| `:from :origin-worktree` (default) | zent's worktree at `origin/<branch>`: what's pushed, fetched and reset on each sync | - |
| `:from :local-worktree` | the same worktree at your local `<branch>`: what's committed, pushed or not, while your main worktree stays free for another branch | - |
| `:from :main-worktree` | your main worktree as it is, checked to be on `:branch` (refused otherwise; the dashboard offers a checkout) | - |
| no `:branch` | your main worktree as it is, on whatever branch it is: nothing checked | - |
| **sync** *(under review)* | bring zent's worktree back to its ref (fetch + reset for `:origin-worktree`), then redeploy: `zent pull <c>`, an apply that redeploys it | a `git pull`; the dashboard's "Sync" (a fast-forward of the main worktree's branch) |
| **trial rebase** *(under review)* | `zent rebase` on an `:origin-worktree`: its detached HEAD rebased, no branch moved, kept under `refs/zent/test/` until the next sync | "local test", "Test on <base>" |

## Components

| Word | Means |
|---|---|
| **component** | one entry of a catalog's `:components`: what zent runs, stops and watches (a card in the dashboard) |
| `:kind` | how zent provides it: `:docker-compose`, `:compose-services`, `:process`, `:quarkus-app`, `:one-shot`, `:external` |
| **one-shot** | a component that runs its `:scripts` once and ends: `done` or `failed` | 
| `:on-demand` | a one-shot an apply never runs: only a reload (the card's ▶) |
| `:readiness` | what "ready" means for a component: the probe an apply waits on |
| `:permanent` | never stopped implicitly (switching presets, `zent stop` without names, shutdown) |
| `:keep-warm` | left running, `warm`, when a preset doesn't name it; stopped by a stop-all or a shutdown |
| **deploy** | start a component, whatever its kind: up a compose file, launch a process, run a one-shot's scripts |

## Presets

| Word | Means |
|---|---|
| **preset** | a scenario: which components run, with their overrides; `presets/<name>.edn` in the catalog |
| **your preset** | one saved with `zent preset save` (or the MCP server) in `~/.config/zent/presets/<catalog>/`: limited to picking components and their branch, ports, images… never what code runs | 
| **apply** | switch the running stack to a preset: start what it names, stop what it doesn't (but `:permanent` and `:keep-warm`) |

## Statuses

A component's, the keyword in the code and the API; shown as its label, a one-shot's where it differs.

| Status | Label (one-shot) | Means | Not |
|---|---|---|---|
| `waiting` | | in an apply, waiting for its dependencies (or a free slot, `:max-parallel`) | "pending", "queued" |
| `deploying` | starting (running) | being deployed | |
| `up` | | deployed, and its readiness passing if it has one | "ready" |
| `done` | | a one-shot whose scripts all succeeded | |
| `on-demand` | on demand | an `:on-demand` one-shot an apply left for you to run | "ready" |
| `warm` | | running, kept by `:keep-warm` though the preset doesn't name it | "idle" |
| `stopping` | | being stopped | |
| `stopped` | (not run) | stopped by a stop | "down" |
| `off` | (not run) | not part of this run (`:mode :off`) | |
| `failed` | | its deploy failed, or it died | |
| `unready` | | running, its readiness failing | |
| `skipped` | | left out by an apply: a dependency failed | |
| `external` | up | an `:external` one, its readiness passing | |

A run as a whole (the dashboard's LED): `failed` if one is failed or unready, else `starting` or `stopping` while one
is on its way, else `up`.

"idle" means nothing is happening (a neutral LED, no preset applied), never a status.

## Verbs *(under review)*

`apply` · `status` · `logs` · `reload` (redeploy one component) · `pull` (sync a worktree) · `rebase` ·
`worktrees` · `stop` · `preview` · `serve` · `watch` · `attach` · `shutdown` (the daemon) · `reload-code` ·
`check` (validate the catalog) · `verify` (run a preset's checks) · `preset save|rm` · `mcp` · `down`
(tear down what the session lists, no daemon).

## Checks

| Word | Means |
|---|---|
| **check** | a namespace of tests about a preset's running stack, `checks/<preset_munged>/<check>.clj` |
| **test** | one of its `deftest`s |
| **verify** | run a preset's checks: `zent verify`, the MCP's `verify`, the dashboard's Checks tab |
| `:zent/kind` *(under review)* | the oracle a test relies on: `:invariant`, `:pseudo-oracle`, `:metamorphic`, `:business-rule`, `:heuristic` |
