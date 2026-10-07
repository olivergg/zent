# AGENTS.md

zent runs local dev stacks (Docker Compose stacks, processes, Quarkus apps, k8s workloads)
from a catalog of components. This repo is the engine (`src/zent/`); a catalog is plain data
in a repo of its own (`catalog.edn` + `presets/`, loaded by `zent.catalog`). Details in
`README.md`. The sections down to "Design principles" are binding; when in doubt, prefer the
smaller change.

## Invariants (don't break these without a very good reason)

1. **The engine never knows a catalog component.** `zent.*` is mechanism (ordering,
   deploy, readiness, teardown, watch); what runs is catalog data. A gap both could close
   is closed in the engine, as one generic guard, not one method per component.
2. **Jolt only.** Deps stay Jolt-native. Look in jolt-lang (`~/.jolt/gitlibs/`) before
   calling a JVM API missing; a new dep needs a need nothing there serves.
3. **Values, not objects.** Catalog, preset, resolved cfg and handle are plain EDN. A preset
   is an overlay map, never an expression. A handle is persisted as is
   (`zent.session/describe`), so a later daemon adopts it and `zent down` still stops it.
4. **A kind is multimethods on `:kind`:** `deploy-kind!`, plus `build-kind!`, `already-running?`,
   `follow-logs!` and `zent.lifecycle/stop!` when it has something to build, check, follow or
   tear down, plus its schema
   entries. Generic keys (`:mode`, `:deps`, `:readiness`, `:watch`) are handled once, in
   the engine; nothing there lists kinds by name.
5. **Never touch what zent can't prove it owns.** A PID is re-checked by `ps` identity
   before a kill; `:permanent` survives every implicit teardown; the main clone is only
   read (`:branch` runs from a worktree); no auto-clone.
6. **Security defaults on.** Every kubectl pins `--context`, which must be in the catalog's
   allowlist (the kubeconfig may hold prod). Runtime presets (CLI, MCP) pick components
   and branch/image/port/context, never what code runs (`:cmd`, `:env`, `:scripts`), and
   those values are format-checked before reaching argv or a manifest (no leading `-`, no
   newline).
   Secrets are read with the user's own rights, never logged, redacted from served output.
   The daemon only answers requests addressed to 127.0.0.1/localhost, writes need the
   token from its 0600 card.
7. **The resident loop never dies, nor waits on a deploy.** New code in `serve!`'s loop gets
   its own try/catch: an escaping exception freezes the dashboard while the HTTP server keeps
   answering. Anything long (a build, a rollout) is a job on its deploy worker. Keep `false`
   (checked: down) distinct from `nil` (can't tell).

## Scope boundary

zent orchestrates; the tools that already do the work stay in charge of it. Compose runs
containers (`up --wait`), kubectl rolls out, mvn builds, git manages worktrees: zent
sequences them, tracks what they started and tears it down.

Reuse what upstream projects already offer (a CLI flag, a library, a format) before
writing it: zent's own code is for what nothing upstream does, never a rewrite for the
sake of it.

Signs you've crossed the line: parsing a compose file instead of asking compose
(`config --services`); reimplementing a build or a rollout; a catalog's component
name in `zent.*`; a preset needing `:cmd` to do its job.

## Design principles

- **Mechanism before usage.** A new capability is proven generic in the engine first, then
  used by the catalog, never the other way round.
- **Data first.** A feature is a new key in a cfg or kind schema before a new function,
  and a new function before a namespace. Malli validates at resolve time, so a bad preset
  fails before anything starts.
- **Calculations apart from actions.** `zent.compose`, `zent.topo`, `zent.commands` and
  `zent.ui.render` are pure (resolve, order, build commands, render); `zent.shell`,
  `zent.kinds` and `zent.lifecycle` do the IO. `preview` is everything before the first
  action.
- **Mutation at named edges.** Atoms live in `serve!`, `zent.ui.state`/`zent.ui.server`,
  `zent.secrets` (what to redact) and `zent.branch` (one git lock per repo), as `defonce`
  with a name saying what they hold; resolution stays pure.
- **Deploys run in parallel.** A component starts once its `:deps` are up,
  up to the catalog's `:max-parallel` (default 3). Local builds and scripts still run one
  at a time (`zent.kinds/run-local!`) unless the component sets `:allow-parallel`; any
  other step a kind adds must be safe next to another component's.
- **The session is a claim, not a fact.** Verify (`ps` identity, `already-running?`)
  before adopting or stopping anything it lists.
- **One failure, not all.** A failed component skips its dependents only; one bad session
  entry, watch target or teardown never aborts the rest of the batch.
- **Generalize from pain.** A new kind, probe type or key needs a preset that hurts today
  without it. An abstraction with one call site is a guess.
- **Measure before fixing.** A perf or robustness fix starts from a number taken on the
  real stack (a timing, a count, a CPU sample): most suspected costs here proved negligible.
- **The test is part of the change.** `test/zent/<ns>_test.clj`, same turn, compact
  given/when/then; no docker, kubectl or network (stub `zent.shell`, `spawn!` included),
  never the real `~/.cache` (the runner points `zent.logs` at a temp dir).
- **Cleanup is part of the change.** Drop leftovers, factorize duplicates, keep comments
  and docs concise, in English, saying what isn't obvious from the code.

## Prerequisites

| Tool | Why | Install |
|---|---|---|
| Jolt ≥ 0.8.16 | the only runtime (not `clj`) | [jolt-lang/jolt](https://github.com/jolt-lang/jolt#install) - `brew install jolt-lang/jolt/jolt` |
| Docker + Compose | `:docker-compose` / `:compose-services` components | [colima](https://github.com/abiosoft/colima#installation), [Compose](https://docs.docker.com/compose/install/) |
| JDK + Maven | `:quarkus-app` components | [SDKMAN](https://sdkman.io/install), [maven.apache.org](https://maven.apache.org/install.html) |
| kubectl | `:secret-env`, `:k8s` kind | [kubernetes.io](https://kubernetes.io/docs/tasks/tools/) |

What a catalog's components themselves need is listed in that catalog's repo.

## Working on the engine

- Symlink this clone's `bin/zent` onto PATH (README "Quickstart"), then `zent reload-code` from
  a catalog repo picks an edit up.
- `zent mcp` is a process its client spawned and keeps: an engine change reaches it only
  once the client restarts it (`/mcp` reconnect in Claude Code), whatever `reload-code` did
  to the daemon. Its stdout is the protocol - code it runs must not print outside a tool
  call (zent.mcp captures per call, the rest goes to stderr).
- Kinds and their keys: `src/zent/schema.clj`, `docs/resource-kinds.md`. A catalog's format:
  `README.md` ("A catalog repo", "For Java/Python developers").

## Useful to know

- Tests: `jolt test/runner.clj`, never `clj`: the HTTP deps are Jolt-only, so the JVM
  can't load the project. `ZENT_TEST_SECRET=<context>/<ns>/<secret>/<key>` also runs the
  real-Secret tests (`zent.secrets-test`), skipped otherwise.
- Jolt lacks some JVM APIs (`Semaphore`, `FileChannel.lock`, `RandomAccessFile`), and its
  `Files/move` isn't atomic (`.renameTo` is). Try an API in a scratch `jolt` script before
  building on it.
- `reload-code` swaps namespaces, not what a running `serve!` already holds (its loop, deploy
  worker, push thread): a change there needs `zent shutdown` then `zent apply <preset>`. A
  running daemon re-reads its catalog on `reload-code`; CLI verbs read it on every call.
- Checking the dashboard without a browser: Chrome headless (`--headless --timeout=5000
  --screenshot=out.png http://127.0.0.1:8765/`) shows the layout, but may shoot before
  streamed content arrives (the Logs tab looks empty). For that, start Chrome with
  `--remote-debugging-port` and read the page's state over the DevTools protocol
  (`Runtime.evaluate`, e.g. `document.querySelectorAll('.ln').length`).
- `:branch` runs from a worktree in `~/.cache/zent/worktrees/`, reset to `origin/<branch>`
  on each deploy, so the branch must be pushed - unless the main clone has it checked out:
  then it runs from the main clone, as it is. The main clone is never touched.
- State: `~/.cache/zent/` (session, logs, `serve.log`, worktrees), user presets in
  `~/.config/zent/presets/`.
- Never run a bare `zent down` to clean up: the session also tracks stacks you started.
- Use `127.0.0.1`, not `localhost`, for the dashboard (IPv6-first resolution hangs the websocket).
- Commit on `main`, and only when asked.
