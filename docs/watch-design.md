# Live reload: design

Status: built (scope A): `zent.watch` scans, `zent.engine/serve!`'s loop re-applies.

## The constraint that shapes everything: no native watcher

| option | verdict |
|---|---|
| `java.nio.file.WatchService` under Jolt | **unavailable** - Jolt doesn't expose `FileSystem.newWatchService` |
| `WatchService` on the JVM / macOS | **unusable** - measured **9.7 s** detection latency (Apple's polling implementation) |
| `fswatch` / `watchexec` / `entr` | native performance, but none installed - an external dependency to assume |
| **scan mtimes ourselves** | **chosen** - portable, no external dependency, latency = poll interval |

Measured cost of one full scan pass (Jolt, warm):

| repo | watched files | ms/scan |
|---|---|---|
| `notifier` (`.java`/`.xml`) | 11 | 3 |
| `front` (`.ts`/`.tsx`/`.json`) | 16 227 | 352 |
| `webapp` (`.java`/`.xml`) | 6 931 | 528 |

So scanning is only affordable if the watched set is **narrow and explicitly declared per
component** (watched paths derive from the component's declared sources), not from the repo root.

## Resident mode

Watching needs a resident process that owns its children: the daemon (`zent serve`,
`zent.engine/serve!`). `zent watch <preset>` is serve with that preset applied at start;
every other verb is a client of it. The session file (`~/.cache/zent/session.edn`) stays
current across stops and reloads, so a later daemon adopts what it lists and `zent down`
can still stop it.

## The ordering invariant (this is the load-bearing part)

```
file change -> debounce -> re-run build -> ONLY on success: stop, then start
```

- the old process stays up while the build runs
- stop strictly before start, because both bind the same port
- a failed build leaves the running process alone
- debounce: 200 ms of quiet, re-armed on each event, hard ceiling 10 s
- one serialized worker, so two events can't race the same component
- **no cascade to dependents** - `:deps` orders the initial deploy, it doesn't propagate
  reloads.

Stop-then-start is for a spawned process only: compose stacks, one-shots and k8s workloads
converge in place, never stopped first (`zent.engine/reload!`).

## Watch spec: catalog data, per component

```clojure
:broker-seed
{:kind :one-shot :repo "broker-stack" ...
 :watch {:paths ["src/main/resources/schemas"]   ; relative to the resolved source dir
         :exts  [".avsc"]                        ; optional extension filter
         :ignore ["target"]}}                    ; directory names to skip
```

Paths resolve against the component's checkout dir (its branch worktree when `:branch` is
set; `zent.engine/watch-targets`). Omitted `:paths` means `"."`; omitted `:ignore` means
`target`, `node_modules`, `.git`. Validated by `zent.schema` like any other key. A component without `:watch` is
simply never watched.

## Scope A vs B

Most of our components already have a **better** watcher than zent could be, so scope is
deliberately narrow:

| component | today | zent watch would add |
|---|---|---|
| `front` | vite HMR | nothing - a full restart is *worse* than HMR |
| quarkus apps | `java -jar` | `mvn package` + restart (~30 s), where `mvn quarkus:dev` does it natively in ~1 s |
| `broker-seed` | manual one-shot | **real gain** - re-seed when an `.avsc` changes |
| docker-compose | manual | **real gain** - re-converge when the compose file changes |
| `webapp` | `jbang start.java` | rebuild + restart: slow, but there's no native alternative |

- **A**: engine mechanism + watching one-shots and docker-compose.
  Useful immediately, cannot do worse than the status quo.
- **B (catalog side)**: run a Quarkus app as a `:process` with `mvn quarkus:dev`, and leave
  vite's HMR alone - their native reload beats a zent restart.

## Out of scope

An in-memory log store (zent uses files, see `zent.logs`) and image/k8s rebuild semantics.
A failed watch-loop iteration is logged and the loop carries on; no backoff.
