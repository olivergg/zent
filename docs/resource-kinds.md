# Resource kinds

What a component can *be*. Written when two things in the model didn't hold up -
`:webapp`/`:front` existed as **kinds** when they were one generic
mechanism used twice, and `:external` was a **mode** when it belongs in the same
list as compose/image/k8s. Both are fixed now; §2 and §3 record what was wrong
and why, since the reasoning outlives the fix.

## 1. The gradation

Every entry here answers one question - *how is this component provided?* - and
they should be interchangeable for the same component: the same `:webapp` runs
as a local process today, tracked-externally tomorrow, from a registry image
next month, without the catalog entry becoming a different thing.

| # | mechanism | today | generic? |
|---|---|---|---|
| 1 | long-lived command in a checked-out repo | `:process` | yes (was a bespoke kind each for webapp/front - §2) |
| 2 | build, then run the artifact | `:quarkus-app` | partly - `mvn package` and `target/quarkus-app/quarkus-run.jar` are baked in |
| 3 | one-off scripts, nothing stays up | `:one-shot` | yes |
| 4 | an existing compose file, whole | `:docker-compose` | yes |
| 5 | some services of an existing compose file | `:compose-services` | yes (+ discovery and `:exclude`) |
| 6 | a registry image, run directly | via `:k8s` (`:image`) | no plain `docker run` kind - a compose file covers that |
| 7 | a k8s workload from a repo's manifests | `:k8s` | yes - §4 |
| 8 | started by someone else, zent only probes it | `:external` | yes (was a *mode* - §3) |
| 9 | not part of this run | `:mode :off` | a preset overlay (`:a {:mode :off}`); listing a component sets `:mode :on`, so a catalog never writes it. Still resolved: another component's `:source-env` reads its checkout (its `:branch` included) |

All sections record done work.

## 2. Fixed: `:webapp` and `:front` weren't kinds

They were the *same* mechanism written twice:

```clojure
;; catalog.webapp  spawn "jbang start.java [-Denv.profile=local]" in <repo>
;; catalog.front   spawn "npm run start:nobrowser" in <repo>, with VITE_BACKEND_URL
```

Strip the literals and nothing component-specific is left - it's "spawn a
long-lived command in a checked-out repo, with env". `:quarkus-app` is that plus
a build step. One generic kind covers all three:

```clojure
:process [[:repo :string]
          [:cmd :string]                    ; the long-lived command
          [:build-cmd {:optional true} :string]
          [:env {:optional true} [:map-of :string :string]]
          [:port {:optional true} pos-int?]]
```

Then `webapp` is a component, not a kind:

```clojure
:webapp {:kind :process :repo "webapp" :cmd "jbang start.java" ...}
```

A variant (another command, another checkout via `:workspace-dir`) is then a preset
overriding a key, never a new kind.

## 3. Fixed: `:external` was in the wrong axis

`:mode` was answering two unrelated questions at once:

- is this component part of the run at all? (`:off` vs the rest)
- who brings it up? (`:local` = zent, `:external` = something else)

The second is not a mode, it's mechanism #8 in the table above - "provided by a
Tomcat you started from your IDE" sits next to "provided by a compose file" and
"provided by a registry image", and reads that way in the gradation. Making it a
kind is what makes the whole list interchangeable:

```clojure
;; catalog
:webapp {:kind :process :repo "webapp" :cmd "jbang start.java"}
;; a preset: same component, different mechanism
:webapp {:kind :external :readiness {:type :http-poll :url "..."}}
```

`:mode` is `:on`/`:off` now - it was `:local`, which reads as a contradiction
next to `:kind :external`.

This works because the per-kind malli maps are open - the catalog's `:cmd`
rides along harmlessly when a preset flips `:kind` to `:external`.

## 4. Done: k8s (#7), and images through it (#6)

`:k8s` runs a prebuilt image in a cluster - never a build: the repo's manifests,
rendered with kustomize (namespace injected, `:image-placeholder` swapped for
`:image`, affinity optionally stripped), applied, waited on with `rollout
status`, followed with `kubectl logs -f`, deleted on stop. `--context` must be
in the catalog's `:allow-k8s-contexts`; a `:pull-secret` is created from its
`:password-cmd`'s output (e.g. a registry login token). Port-forwarding is a separate `:process` component for now.

## 5. Granularity: one box per compose service?

`:compose-services` is one component for N services: one dependency target, one
handle, one status - while `docker compose ps` knows each service's own state,
health and published ports. Three options were on the table:

| | | |
|---|---|---|
| **one box per service** | truthful, enables per-service reload/stop | needs a sub-resource concept in the engine, view-state and UI; and what does an arrow point at - the group? |
| **one box, sub-items inside it** | real per-service health (`docker compose ps --format json` already gives State/Health/Publishers), no new engine concept, arrows keep pointing at one thing | no per-service *control* |
| **status quo** | - | the card lists service *names* and says nothing about whether each is actually healthy |

Chose the middle one: each service is a box inside the component's box, with its
own health and published ports, but they stay one component - one dependency
target, one handle. Promote services to real components only if per-service
*control* is ever wanted, at which point they're just `:compose-services`
components with a one-item `:services` and no new concept is needed.
