"use strict";

let view = { components: {}, graph: [], preset: null };
let selected = null;
// "load a preset without starting it" - GET /api/preview/:preset (pure,
// zent.compose/resolve-preset - nothing deployed). Kept separate from
// `view` rather than replacing it, so a live push in the background keeps
// `view` current while previewing, and exiting preview shows up-to-date
// state instead of whatever was live when preview started.
let previewing = null;
let previewData = null;
function activeData() { return previewing ? previewData : view; }
// A stopped component of the preset is part of the picture: shown unless turned
// off, persisted like the rail width/zoom below.
let showStopped = localStorage.getItem("zent.showStopped") !== "false";

// the wrapper's own data-status (CSS/layout only now - the label text
// itself is server-rendered, see zent.ui.render's state-label).
const STATE_CLASS = {
  waiting: "waiting", deploying: "starting", up: "up", done: "done", warm: "warm", stopping: "stopping",
  failed: "failed", external: "external", off: "off", stopped: "stopped", "on-demand": "on-demand",
  unready: "unready", skipped: "skipped"
};
const WOVEN = new Set(["up", "done", "external"]);
// what isn't settled yet: down (red) or on its way (amber) - the colour of a wire leaving it
const UNSETTLED = { failed: "bad", unready: "bad", skipped: "bad", starting: "wait", waiting: "wait" };

/* Motion follows what the stack does, live view only: a card that joins
   rises in, row by row; one that changes status is washed in its new
   colour. Every push rebuilds the board, so each change keeps its time and a
   rebuilt card resumes its animation where it was (a negative delay) rather
   than restarting it. */
const MOTION_MS = 1600;
const seenStatus = {}, changedAt = {}, arrivedAt = {};
let wasBusy = false, selectedAt = -Infinity, unlit = null, unlitAt = -Infinity;
const since = (t) => `${Math.round(t - performance.now())}ms`;
const recent = (t) => performance.now() - (t ?? -Infinity) < MOTION_MS;

function noteChanges(components) {
  const now = performance.now(), primed = Object.keys(seenStatus).length > 0;
  for (const [name, c] of Object.entries(components)) {
    const s = STATE_CLASS[c.status] || "waiting";
    if (!(name in seenStatus)) { if (primed) arrivedAt[name] = now; }
    else if (seenStatus[name] !== s) {
      changedAt[name] = now;
      if (s === "failed") toast("failed", name + " failed", c.error, name);
    }
    seenStatus[name] = s;
  }
  // a run settling: from starting or stopping to up
  const health = healthOf(components);
  if (wasBusy && health === "up" && view.preset) {
    const n = Object.values(components).filter((c) => c.status === "up").length;
    toast("up", view.preset + " is up", n + (n === 1 ? " component" : " components") + " running");
  }
  wasBusy = health === "starting" || health === "stopping";
}

/* A run as a whole: failed while one is failed or unready, else starting or
   stopping while one is on its way, else up (up, done, external, on
   demand, warm). */
function healthOf(components) {
  const s = new Set(Object.values(components).map((c) => c.status));
  return s.has("failed") || s.has("unready") ? "failed"
    : s.has("waiting") || s.has("deploying") ? "starting"
    : s.has("stopping") ? "stopping" : "up";
}

/* A toast: what just happened, for a few seconds - a failure longer, and a
   click on it selects the component. */
function toast(kind, title, detail, name, action) {
  const el = document.createElement("div");
  el.className = "toast";
  el.dataset.kind = kind;
  el.innerHTML = "<b>" + escHtml(title) + "</b>" +
    (detail ? "<span>" + escHtml(String(detail).split("\n")[0]) + "</span>" : "");
  const leave = () => { el.classList.add("leaving"); setTimeout(() => el.remove(), 240); };
  // an action (an undo): a button of its own, which leaves the toast once pressed
  if (action) {
    const b = Object.assign(document.createElement("button"), { type: "button", className: "toast-act", textContent: action.label });
    b.onclick = (e) => { e.stopPropagation(); action.run(); leave(); };
    el.appendChild(b);
  }
  el.onclick = () => { if (name && view.components[name]) { location.hash = "#stack"; setSelected(name); } leave(); };
  document.getElementById("toasts").appendChild(el);
  setTimeout(leave, kind === "failed" ? 9000 : action ? 8000 : 5000);
}

/* Layered DAG: a component sits one level right of its deepest dependency.
   That level IS the warp position, so the layout carries the dependency
   structure rather than decorating it. Row order within a level starts
   alphabetical, then a few barycenter sweeps (median-heuristic crossing
   reduction, the standard trick behind Sugiyama-style layered graph
   drawing) nudge each row toward the average row of the nodes it's
   threaded to - a fan-out/fan-in (e.g. a seed script feeding three different
   downstream apps) stops crossing the threads of unrelated components. */
function levelsOf(components) {
  const depth = {}, visiting = new Set();
  const of = (name) => {
    if (depth[name] !== undefined) return depth[name];
    if (visiting.has(name)) return 0;          // a cycle is the engine's error to report
    visiting.add(name);
    const deps = (components[name]?.deps || []).filter((d) => components[d]);
    depth[name] = deps.length ? Math.max(...deps.map(of)) + 1 : 0;
    visiting.delete(name);
    return depth[name];
  };
  Object.keys(components).forEach(of);
  const levels = [];
  for (const [name, d] of Object.entries(depth)) (levels[d] ||= []).push(name);
  levels.forEach((names) => names.sort());

  const predsOf = (name) => (components[name]?.deps || []).filter((d) => components[d]);
  const succsOf = {};
  for (const name of Object.keys(components)) {
    for (const p of predsOf(name)) (succsOf[p] ||= []).push(name);
  }
  const rowIndex = () => {
    const idx = {};
    levels.forEach((lvl) => lvl.forEach((n, i) => { idx[n] = i; }));
    return idx;
  };
  const sweep = (forward) => {
    const idx = rowIndex();
    const order = forward ? levels.map((_, i) => i) : levels.map((_, i) => i).reverse();
    const neighborsOf = forward ? predsOf : (n) => succsOf[n] || [];
    for (const d of order) {
      const keyed = levels[d].map((n) => {
        const rows = neighborsOf(n).map((m) => idx[m]).filter((v) => v !== undefined);
        return [n, rows.length ? rows.reduce((a, b) => a + b, 0) / rows.length : idx[n]];
      });
      keyed.sort((a, b) => a[1] - b[1] || a[0].localeCompare(b[0]));
      levels[d] = keyed.map(([n]) => n);
      levels[d].forEach((n, i) => { idx[n] = i; });
    }
  };
  for (let pass = 0; pass < 4; pass++) { sweep(true); sweep(false); }

  return levels;
}

/* renderSummary/renderPresets/renderDetail's string-building moved server-
   side (zent.ui.render) - the websocket push already carries the rendered
   fragments (view.summaryHtml/presetsHtml), and the detail panel is a
   fetch on selection (loadDetail below), since it depends on `selected`,
   which the server has no reason to track. */
let lastDetailKey = null;

function render() {
  renderRun();
  renderRunToggle();

  if (view.summaryHtml) document.getElementById("summary").outerHTML = view.summaryHtml;
  if (view.presetsHtml) {
    document.getElementById("preset-picker").outerHTML = view.presetsHtml;
    markPreviewing(previewing);
    htmx.process(document.getElementById("preset-picker"));
  }
  // only on change: a refetch replaces the panel
  const detailKey = selected && selected + JSON.stringify(activeData().components[selected] ?? null);
  if (selected && detailKey !== lastDetailKey) loadDetail(selected);
  lastDetailKey = detailKey;
  renderLogs();
  checksOnView();
  // Preview <-> Running: another preset's repos
  if (tabOf() === "branches" && branches.data && branches.of !== shownPreset()) loadBranches();
  markBusy();
}

/* Fetches and shows a preset's would-be components/graph without starting
   anything (zent.ui.render/component-card with :preview? true drops the
   reload/stop buttons server-side - nothing here is deployed). Toggles off
   on a second click of the same name, same as a normal expand/collapse. */
async function previewPreset(name) {
  if (previewing === name) return exitPreview();
  // reading a preview takes seconds (git, per checkout): said at once - body[data-switching] - and the
  // Branches tab starts reading that preset's repos alongside
  switchingTo = name; document.body.dataset.switching = name;
  if (tabOf() === "branches") loadBranches();
  try {
    const res = await fetch("/api/preview/" + encodeURIComponent(name));
    if (!res.ok) return;
    setPreview(name, await res.json());
  } catch {
    /* the click just does nothing - no partial/broken preview state to undo */
  } finally {
    switchingTo = undefined; delete document.body.dataset.switching;
  }
}
// the preset a preview is being read for, undefined when none (previewPreset)
let switchingTo;
// the preset the tabs show: the one being switched to, else the previewed one (null: what runs)
const shownPreset = () => switchingTo === undefined ? previewing : switchingTo;

function exitPreview() { setPreview(null, null); }

// the previewed preset read again in place (a rebase or a checkout from it moved a clone)
async function refreshPreview() {
  if (!previewing) return;
  const res = await fetch("/api/preview/" + encodeURIComponent(previewing));
  if (res.ok) { previewData = await res.json(); renderRun(); }
}

// live <-> preview, or one preview to another: the loom blurs out, the next comes into focus
function setPreview(name, data) {
  if (name) lastPreviewed = name;
  transit("morph", () => { previewing = name; previewData = data; renderRun(); renderRunToggle(); markPreviewing(name); });
}
// the toolbar's Preview goes back to the last preset looked at, else the one running
let lastPreviewed = null;

/* `update` as a View Transition of `kind` (html's data-vt while it runs, so
   CSS names and animates only what this kind moves - a name left on, the
   element would be pulled out of every other transition's snapshot).
   Composited on the GPU; immediate when motion is reduced or unsupported. */
function transit(kind, update) {
  if (!document.startViewTransition || matchMedia("(prefers-reduced-motion: reduce)").matches) return update();
  const root = document.documentElement;
  root.dataset.vt = kind;
  document.startViewTransition(update).finished.finally(() => delete root.dataset.vt);
}

// flags the picker's row of the preset being previewed (null: none)
function markPreviewing(name) {
  document.querySelectorAll("#preset-picker .preset-row")
    .forEach((row) => { row.dataset.previewing = String(row.querySelector(".preset-name")?.dataset.preset === name); });
}

/* The hover explanations' texts (zent.ui.render/tips, sent with the page): `tip(key, {x})` fills its {x}. */
// a daemon older than the page leaves {{TIPS}} unfilled: no explanations then, the page still works
const TIPS = (() => { try { return JSON.parse(document.getElementById("tips")?.textContent || "{}"); } catch { return {}; } })();
const tip = (key, vars = {}) => (TIPS[key] || "").replace(/\{(\w+)\}/g, (_, k) => vars[k] ?? "");
const escHtml = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

/* What applying the previewed preset would do from what runs now (the engine's plan-summary), one
   line per verb, a redeploy with its reason: what its Start says on hover. */
function planText(plan) {
  if (!plan) return "";
  const part = (verb, names) => names.length ? verb + " " + names.join(", ") : null;
  const redeploys = Object.entries(plan.redeploy || {}).map(([n, why]) => `${n} (${why})`);
  return [part("stops", plan.stop), part("keeps warm", plan["keep-warm"]), part("redeploys", redeploys),
          part("starts", plan.start)].filter(Boolean).join("\n") || "nothing to change";
}

/* The one big, hard-to-miss control next to the masthead's preset badge -
   Stop for whatever's actually live, or Start for a preset currently being
   previewed - what that would do on hover. */
function renderRunToggle() {
  // outerHTML, not attribute mutation - #run-toggle otherwise persists
  // across every push, and re-htmx.process()ing the same node repeatedly
  // isn't a pattern used anywhere else here; replacing it (same as
  // #summary/#preset-picker/#detail) keeps every processed node fresh.
  // the label beside it says which preset - the button only says what it does
  const state = previewing ? "preview" : view.preset ? "live" : "idle";
  // live: the run's health on the label's LED (CSS) and in its caption
  const health = state === "live" ? healthOf(view.components) : null;
  const run = document.getElementById("run");
  run.dataset.state = state;
  if (health) run.dataset.health = health; else delete run.dataset.health;
  document.getElementById("run-caption").textContent = health || { preview: "preview", idle: "no run" }[state];
  document.getElementById("preset").textContent = previewing || view.preset || "presets";
  const [preview, running] = document.querySelectorAll("#mode button");
  preview.setAttribute("aria-pressed", String(!!previewing));
  running.setAttribute("aria-pressed", String(!previewing));
  preview.disabled = !previewing && !lastPreviewed && !view.preset;
  const old = document.getElementById("run-toggle");
  if (previewing) {
    old.outerHTML = '<button type="button" class="run-toggle play" id="run-toggle" ' +
      'title="' + escHtml(tip("run/start", { preset: previewing, plan: planText(previewData.plan) })) + '" ' +
      'hx-post="/api/apply/' + previewing + '" hx-swap="none" hx-disabled-elt="this">' +
      '▶ Start</button>';
    document.getElementById("run-toggle").addEventListener("click", () => setTimeout(exitPreview));
  } else if (view.preset) {
    old.outerHTML = '<button type="button" class="run-toggle stop" id="run-toggle" ' +
      'hx-post="/api/stop-all" hx-swap="none" hx-disabled-elt="this">&#9632; Stop</button>';
  } else {
    old.outerHTML = '<button type="button" class="run-toggle" id="run-toggle" hidden></button>';
  }
  htmx.process(document.getElementById("run-toggle"));
  markBusy();
  // the live run in three figures: what's down, what's up, how far its steps are
  const cs = (view.members || []).map((m) => view.components[m]).filter(Boolean), n = (pred) => cs.filter(pred).length;
  const failed = n((c) => UNSETTLED[STATE_CLASS[c.status]] === "bad"), up = n((c) => c.status === "up" || c.status === "external");
  const steps = cs.filter((c) => c.kind === "one-shot" && !c["on-demand"]);
  document.getElementById("chips").innerHTML = !view.preset ? "" :
    (failed ? `<span class="chip bad" data-tip-key="chip/failed">` +
              `<b>${failed}</b> failed</span>` : "") +
    `<span class="chip" data-tip-key="chip/up"><b>${up}</b> up</span>` +
    (steps.length ? `<span class="chip" data-tip-key="chip/steps">` +
                    `<b>${steps.filter((c) => c.status === "done").length}</b> of ${steps.length} steps</span>` : "");
}

/* A preview's verdict, over the board where the side panel was: what applying it does (the engine's
   plan), what stops it - each with the click that unblocks it - and what's worth a look first. */
function renderVerdict() {
  const box = document.getElementById("verdict");
  if (!previewing || !previewData) { box.hidden = true; return; }
  const comps = previewData.components, plan = previewData.plan || {}, q = "?preset=" + encodeURIComponent(previewing);
  const act = (verb, n, label, confirm) => `<button type="button" class="act-btn" hx-post="/api/${verb}/${escHtml(n)}${q}" hx-swap="none"` +
    ` hx-disabled-elt="this"${confirm ? ` hx-confirm="${escHtml(confirm)}"` : ""}>${label}</button>`;
  const blocks = [], looks = [];
  for (const [n, c] of Object.entries(comps)) {
    const g = c.git || {}, want = c["wanted-branch"], from = c.from, at = `<b>${escHtml(n)}</b>`;
    if (from === "main-worktree" && want && g.branch && g.branch !== want) {
      blocks.push(`${at} runs your main worktree, on <code>${escHtml(g.branch)}</code> - the preset wants <code>${escHtml(want)}</code>` +
        (g.dirty ? ` <span class="why">commit or stash its ${g.dirty} uncommitted file(s) first</span>`
                 : act("checkout", n, "Check out", `Check out ${want} in your main worktree? Every component running it moves with it.`)));
    }
    if ((from === "origin-worktree" || from === "local-worktree") && (g.dirty || g.unreachable)) {
      blocks.push(`${at}'s worktree holds ${g.dirty ? g.dirty + " uncommitted file(s)" : ""}${g.dirty && g.unreachable ? " and " : ""}` +
        `${g.unreachable ? g.unreachable + " commit(s) on no branch" : ""}: the apply refuses to sync it <span class="why">keep or drop them first</span>`);
    }
    if (want && g.behind > 0) {
      looks.push(`${at} is ${g.behind} commit${g.behind > 1 ? "s" : ""} behind <code>${escHtml(g.base)}</code>` +
        ((g.dirty || want === g.base) && from !== "main-worktree" ? "" : act("rebase", n, from === "origin-worktree" ? "Trial rebase onto " + escHtml(g.base) : "Rebase")));
    }
    if (g.unsynced > 0) looks.push(`${at}: origin has ${g.unsynced} new commit(s) - the apply resets its worktree to them`);
    if (!want && g.dirty) looks.push(`${at} runs your main worktree as it is, with ${g.dirty} uncommitted file(s)`);
  }
  const steps = Object.entries(comps).filter(([, c]) => c.kind === "one-shot");
  const demand = steps.filter(([, c]) => c["on-demand"]).map(([n]) => n);
  const restarts = Object.entries(plan.redeploy || {});
  const count = (xs, verb) => xs.length ? `<span class="n"><b>${xs.length}</b> ${verb}</span>` : "";
  const services = (plan.start || []).filter((n) => comps[n]?.kind !== "one-shot");
  box.hidden = false;
  box.innerHTML = `<div class="v-head ${blocks.length ? "bad" : "ok"}">
      <h2>${blocks.length ? `${blocks.length} thing${blocks.length > 1 ? "s" : ""} stop${blocks.length > 1 ? "" : "s"} this apply` : "Nothing stops this apply"}</h2>
      <span class="does">${count(services, "start")}${count(restarts, "restart")}${count(plan.stop || [], "stop")}${count(plan["keep-warm"] || [], "kept warm")}` +
      `${count((plan.unchanged || []), "stay as they are")}${count(steps.filter(([, c]) => !c["on-demand"]), "steps run")}</span></div>
    ${restarts.length ? `<p class="v-line">Restarts: ${restarts.map(([n, why]) => `<b>${escHtml(n)}</b> <span class="why">${escHtml(why)}</span>`).join(", ")}</p>` : ""}
    ${(plan.stop || []).length ? `<p class="v-line">Stops: ${plan.stop.map((n) => `<b>${escHtml(n)}</b>`).join(", ")}</p>` : ""}
    ${blocks.length ? `<ul class="v-list bad">${blocks.map((b) => `<li>${b}</li>`).join("")}</ul>` : ""}
    ${looks.length ? `<details class="v-more"${blocks.length ? "" : " open"}><summary>${looks.length} thing${looks.length > 1 ? "s" : ""} worth a look</summary>` +
      `<ul class="v-list">${looks.map((l) => `<li>${l}</li>`).join("")}</ul></details>` : ""}
    ${demand.length ? `<p class="v-line quiet">Only when you ask: ${demand.map((n) => `<b>${escHtml(n)}</b>`).join(", ")}</p>` : ""}`;
  htmx.process(box);
}

function renderRun() {
  const host = document.getElementById("levels");
  document.getElementById("loom").dataset.preview = String(!!previewing);
  // a preview has its verdict over the board: the side panel steps aside
  document.getElementById("shell").dataset.preview = String(!!previewing);
  renderVerdict();
  if (!previewing) noteChanges(view.components);

  // a preview shows every one of the preset's own components, never
  // filtered by showStopped - nothing in it is "stopped", it just hasn't
  // run yet, so that toggle has no meaning here.
  const hiddenCount = weave(host, activeData(), showStopped || previewing);

  if (hiddenCount !== null) {
    host.innerHTML = hiddenCount
      ? '<div class="empty-loom"><h2>Nothing shown</h2>' +
        "<p>" + hiddenCount + " stopped component(s) hidden - " +
        '<button type="button" class="show-stopped-link">show them</button>.</p></div>'
      : '<div class="empty-loom"><h2>The loom is empty</h2>' +
        "<p>Pick a preset from the panel on the right, or bring one up from a terminal with " +
        "<code>zent apply &lt;preset&gt;</code>. " +
        "Components appear here in dependency order as they start.</p></div>";
    const showLink = host.querySelector(".show-stopped-link");
    if (showLink) showLink.onclick = () => setShowStopped(true);
  }
  tickClocks();
  drawWarp();
}

/* Lays `data`'s components out in `host`, level by level - the preset's stopped
   ones too when `all`. Returns how many it hid when it laid out none (the
   caller says why the loom is empty), else null. */
function weave(host, data, all) {
  host.replaceChildren();
  const allNames = Object.keys(data.components);
  // an earlier preset's components stay in the view: stopped, they're off the picture
  // (:members, the summary's rule too: zent.ui.state/shown-components; a preview has none - all its own)
  const members = new Set(data.members?.length ? data.members : allNames);
  const visible = Object.fromEntries(Object.entries(data.components)
    .filter(([name, c]) => (c.status !== "stopped" && c.status !== "off") || (all && members.has(name))));
  const hiddenCount = allNames.length - Object.keys(visible).length;

  // :attached-to one-shots ride under their host's card when it's shown, so
  // they leave the layout; a dependency on one counts as one on its host
  const layout = Object.fromEntries(Object.entries(visible)
    .filter(([name]) => !hostOf(name, visible))
    .map(([name, c]) => [name, { ...c, deps: (c.deps || []).map((d) => hostOf(d, visible) || d)
                                                           .filter((d) => d !== name) }]));
  const levels = levelsOf(layout);
  if (!levels.length) return hiddenCount;

  levels.forEach((names, lvl) => {
    const col = document.createElement("div");
    col.className = "level";
    const label = document.createElement("span");
    label.className = "lane";
    label.textContent = lvl === 0 ? "Starts first" : lvl === levels.length - 1 ? "Last" : "Then";
    label.title = tip("lane", { row: label.textContent });
    col.appendChild(label);

    for (const name of names) {
      const stack = document.createElement("div");
      stack.className = "stack";
      stack.style.setProperty("--lvl", lvl);
      // its :attached-to one-shots are buttons on its own plate (zent.ui.render/shot-buttons)
      stack.appendChild(nodeEl(name, data.components[name]));
      col.appendChild(stack);
    }
    host.appendChild(col);
  });

  // activates the hx-post/hx-disabled-elt attributes component-card just
  // rendered - htmx only auto-wires content it swapped in itself, not
  // innerHTML set by other code (see htmx's docs on manually processing
  // new content).
  htmx.process(host);
  return null;
}

/* The clocks of what's moving (zent.ui.render/clock), ticked between pushes - same format as its duration. */
function duration(ms) {
  const s = Math.floor(ms / 1000), pad = (n) => String(n).padStart(2, "0");
  return ms < 1000 ? ms + "ms" : s < 60 ? s + "s" : s < 3600 ? `${Math.floor(s / 60)}m${pad(s % 60)}s`
    : `${Math.floor(s / 3600)}h${pad(Math.floor(s / 60) % 60)}m`;
}
function tickClocks() {
  for (const el of document.querySelectorAll(".clock[data-since]")) {
    el.textContent = duration(Math.max(0, Date.now() - Number(el.dataset.since)));
  }
}
setInterval(tickClocks, 1000);


/* The one-shot `name` is drawn under, or null: its :attached-to, when that
   component is itself among `components`. */
function hostOf(name, components) {
  const host = components[name]?.["attached-to"];
  // :lone (zent.ui.server/with-cards): it waits for several others, so it's drawn on its own
  return host && host !== name && components[host] && !components[name].lone ? host : null;
}

function nodeEl(name, c) {
  const status = STATE_CLASS[c.status] || "waiting";
  const el = document.createElement("div");
  el.className = "node";
  el.setAttribute("role", "button");
  el.tabIndex = 0;
  el.dataset.status = status;
  el.dataset.kind = c.kind;
  el.dataset.name = name;
  el.dataset.selected = String(selected === name);
  for (const [attr, at] of [["arrived", arrivedAt[name]], ["changed", changedAt[name]]]) {
    if (!previewing && recent(at)) { el.dataset[attr] = "true"; el.style.setProperty(`--${attr}-at`, since(at)); }
  }
  if (selected === name && recent(selectedAt)) { el.dataset.lighting = "true"; el.style.setProperty("--lit-at", since(selectedAt)); }
  else if (unlit === name && recent(unlitAt)) { el.dataset.dimming = "true"; el.style.setProperty("--unlit-at", since(unlitAt)); }
  // the card's own inner markup - name/kind/state/repo/services/ports/
  // source/links/error/reload-stop buttons - is server-rendered
  // (zent.ui.render/component-card, see its docstring on the split);
  // this wrapper only owns what's genuinely client/layout-side: the
  // status/selection dataset attributes and the click/keydown handling.
  el.innerHTML = c.card;
  // Anything interactive inside the card handles its own click - a real
  // link has to stay a real link (cmd-click, middle-click, "open in new
  // tab", the status bar showing the target on hover), which it can't
  // if the card swallows the event. closest() rather than per-element
  // stopPropagation: it can't be forgotten when new controls are added.
  // a second click on the selected card deselects it
  const select = () => setSelected(selected === name ? null : name);
  el.onclick = (e) => {
    if (e.target.closest("a, button")) return;
    // a one-shot's button selects that one-shot
    const shot = e.target.closest("[data-shot]")?.dataset.shot;
    if (shot) setSelected(selected === shot ? null : shot); else select();
  };
  el.onkeydown = (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); select(); } };
  el.onmouseenter = el.onmouseleave = () => drawWarp();
  // its own checkout and, for a :source-env one, every dir it reads
  for (const b of el.querySelectorAll(".workdir-open")) b.onclick = () => copyCd(b.dataset.dir, b);
  return el;
}

/* An orthogonal path through `pts`, its corners rounded by `r` (less where a leg is short). */
function route(pts, r = 8) {
  let d = `M${pts[0][0]},${pts[0][1]}`;
  for (let i = 1; i < pts.length - 1; i++) {
    const [x0, y0] = pts[i - 1], [x1, y1] = pts[i], [x2, y2] = pts[i + 1];
    const a = Math.min(r, Math.hypot(x1 - x0, y1 - y0) / 2, Math.hypot(x2 - x1, y2 - y1) / 2);
    d += ` L${x1 - Math.sign(x1 - x0) * a},${y1 - Math.sign(y1 - y0) * a}` +
         ` Q${x1},${y1} ${x1 + Math.sign(x2 - x1) * a},${y1 + Math.sign(y2 - y1) * a}`;
  }
  return d + ` L${pts.at(-1)[0]},${pts.at(-1)[1]}`;
}

/* A plug at (`x`, `mouth`), pushed into a socket's mouth and leaving it in direction `d` (1 down, -1
   up): its metal tip in the socket, a moulded body, a band in its `end`'s colour (in, out), a ribbed
   strain relief the wire leaves by, PLUG px from the mouth. */
const PLUG = 16;
function plug(x, mouth, d, end) {
  const top = (a, b) => Math.min(mouth + d * a, mouth + d * b);
  // pushed home: its metal tip is inside the jack, out of sight; a metal collar seats on the jack's
  // mouth, a soft shadow under it where it meets the jack
  return `<g class="plug"><ellipse class="seat" cx="${x}" cy="${mouth}" rx="6.5" ry="1.4"/>` +
    `<rect class="tip" x="${x - 5.5}" y="${top(-.6, 2.2)}" width="11" height="2.8" rx=".8"/>` +
    `<rect class="body" x="${x - 4.5}" y="${top(2, 12)}" width="9" height="10" rx="2"/>` +
    `<rect class="band ${end}" x="${x - 4.5}" y="${top(4, 6)}" width="9" height="2"/>` +
    `<line class="relief" x1="${x}" y1="${mouth + d * 12}" x2="${x}" y2="${mouth + d * PLUG}"/></g>`;
}
// the plugs' finishes: brushed metal, and a dark moulding with a gloss down its length
// (the collar: gunmetal, so it reads as the plug's own, not as a gap against a jack's dark ring)
const PLUG_DEFS = '<defs><linearGradient id="plug-metal"><stop offset="0" stop-color="#3e424a"/><stop offset=".45" stop-color="#9aa0aa"/>' +
  '<stop offset="1" stop-color="#363a42"/></linearGradient><linearGradient id="plug-body"><stop offset="0" stop-color="#5d616c"/>' +
  '<stop offset=".3" stop-color="#2a2c33"/><stop offset=".7" stop-color="#101114"/><stop offset="1" stop-color="#3d4049"/></linearGradient></defs>';

/* The wiring: one bus per source. It drops from the source's plate - under its button when the
   source is a one-shot riding there - into the gap below its row, runs along it, and rises into an
   input socket of each plate waiting for it. Its colour is the source's state: ready, still waited
   for, down. Sockets and wires are positioned from the same measured boxes, so one pass draws both. */
function drawWarp() {
  const data = activeData(), comps = data.components;
  const loom = document.getElementById("loom");
  const svg = document.getElementById("warp"), flow = document.getElementById("warp-flow");
  const fabric = document.getElementById("levels");
  const rows = [...fabric.querySelectorAll(".level")];
  // the widest row fits the board: its plates draw closer, then the whole graph scales down a little
  const most = Math.max(1, ...rows.map((row) => row.querySelectorAll(".stack").length));
  // (the gap: a box's depth to its right, then its ports' numbers)
  const room = loom.clientWidth - 80, gap = Math.max(54, Math.min(64, (room - most * 186 - 40) / Math.max(1, most - 1)));
  fabric.style.setProperty("--gap", gap + "px");
  fabric.style.zoom = zoom * Math.max(.7, Math.min(1, room / (most * 186 + (most - 1) * gap + 40)));
  const rowOf = {};
  rows.forEach((row, i) => row.querySelectorAll(".node").forEach((n) => { rowOf[n.dataset.name] = i; }));
  // the plate a component is drawn on: its own, or its host's when it's a button there
  const plateOf = (n) => rowOf[n] !== undefined ? n : comps[n]?.["attached-to"];
  const buses = new Map(), inputs = {};
  for (const [to, from] of data.graph || []) {
    const src = plateOf(from), dst = plateOf(to);
    if (rowOf[src] === undefined || rowOf[dst] === undefined || src === dst) continue;
    if (!buses.has(from)) buses.set(from, new Set());
    if (!buses.get(from).has(dst)) { buses.get(from).add(dst); (inputs[dst] ||= []).push(from); }
  }
  // each row leaves room under it for the buses that run there, one track each
  const tracks = rows.map(() => []);
  for (const from of buses.keys()) tracks[rowOf[plateOf(from)]].push(from);
  // (78: a plug's length under this row and over the next, and the next one's top face)
  rows.forEach((row, i) => { row.style.marginBottom = (i < rows.length - 1 ? 78 + tracks[i].length * 11 : 0) + "px"; });

  // sockets, where a wire's plug goes in: one input per source on the top edge, its link light on once
  // that source is ready; an output under the plate - under its button, for a one-shot riding there
  const ready = (n) => WOVEN.has(comps[n]?.status);
  const socket = {};
  fabric.querySelectorAll(".node").forEach((n) => {
    n.querySelectorAll(":scope > .pin").forEach((p) => p.remove());
    const name = n.dataset.name, pin = (key, cls, style, title) => {
      const p = document.createElement("span");
      p.className = "pin " + cls; p.style.cssText = style; p.title = title; n.appendChild(p);
      socket[key] = p;
    };
    (inputs[name] || []).forEach((from, k) => pin(from + ">" + name, "in" + (ready(from) && !previewing ? " ok" : ""), `left:${23 + k * 17}px`,
      tip("jack/in", { from, to: name, ready: comps[from]?.kind === "one-shot" ? "done" : "ready" })));
    if (buses.has(name)) pin(name, "out", "", tip("jack/out", { name }));
    for (const b of n.querySelectorAll("[data-shot]")) {
      if (!buses.has(b.dataset.shot)) continue;
      const x = (b.getBoundingClientRect().left + b.getBoundingClientRect().width / 2 - n.getBoundingClientRect().left) / (n.getBoundingClientRect().width / n.offsetWidth);
      pin(b.dataset.shot, "out", `left:${Math.round(x - 7)}px`, tip("jack/out-step", { name: b.dataset.shot }));
    }
  });

  const box = loom.getBoundingClientRect();
  const w = loom.scrollWidth, h = Math.max(loom.scrollHeight, box.height);
  const plugLayer = document.getElementById("warp-plugs");
  for (const s of [svg, flow, plugLayer]) { s.setAttribute("width", w); s.setAttribute("height", h); s.setAttribute("viewBox", `0 0 ${w} ${h}`); }
  const place = (el) => {
    const r = el.getBoundingClientRect();
    return {
      l: r.left - box.left + loom.scrollLeft,
      cx: r.left + r.width / 2 - box.left + loom.scrollLeft,
      top: r.top - box.top + loom.scrollTop,
      y: r.top - box.top + loom.scrollTop + r.height / 2,
      bottom: r.bottom - box.top + loom.scrollTop
    };
  };
  const at = {};
  fabric.querySelectorAll(".node").forEach((n) => { at[n.dataset.name] = place(n); });
  const rowBottom = rows.map((row) => place(row).bottom);

  const hovered = fabric.querySelector(".node:hover")?.dataset.name;
  // a hovered, else selected, plate's wires stand out and every other one fades back
  const focus = at[hovered] ? hovered : at[selected] ? selected : null;
  // hovering also steps back every plate but it and its neighbours
  const near = new Set(hovered ? [hovered, ...[...buses].flatMap(([from, to]) =>
    plateOf(from) === hovered ? [...to] : to.has(hovered) ? [plateOf(from)] : [])] : []);
  loom.dataset.hovering = String(!!hovered);
  fabric.querySelectorAll(".node").forEach((n) => { n.dataset.near = String(near.has(n.dataset.name)); });

  const parts = [], plugParts = [], waiting = [], labels = [];
  for (const [from, to] of buses) {
    const src = plateOf(from), row = rowOf[src];
    const state = previewing ? "idle" : ready(from) ? "ok" : UNSETTLED[STATE_CLASS[comps[from]?.status]] || "idle";
    // each end is a plug pushed into its socket's mouth (6px past the nut): the wire runs between their tails
    // k: the board's scale - the jack's 6px of sleeve and ring are CSS px, scaled with it
    const k = socket[from].getBoundingClientRect().width / socket[from].offsetWidth || 1;
    const out = place(socket[from]), sx = Math.round(out.cx), sy = out.bottom + 6 * k;
    const y = rowBottom[row] + 16 + tracks[row].indexOf(from) * 11 + PLUG;
    const lit = focus && (src === focus || to.has(focus));
    const plugs = [plug(sx, sy, 1, "out")];
    const paths = [...to].map((dst) => {
      const pin = place(socket[from + ">" + dst]), tx = Math.round(pin.cx), ty = pin.top - 6 * k;  // its mouth: over the nut, the sleeve (4) and the ring (2), as an output's
      plugs.push(plug(tx, ty, -1, "in"));
      return route(tx === sx ? [[sx, sy + PLUG], [tx, ty - PLUG]] : [[sx, sy + PLUG], [sx, y], [tx, y], [tx, ty - PLUG]]);
    });
    if (state === "wait") waiting.push(...paths);
    const label = from + (to.size > 1 ? " ×" + to.size : ""), width = label.length * 6 + 16;
    // beside its junction - past any label already there
    let lx = sx + 8;
    for (let hit; (hit = labels.find((l) => Math.abs(l.y - y) < 15 && lx < l.x + l.width + 4 && lx + width > l.x - 4));) lx = hit.x + hit.width + 6;
    if (lx + width > w - 8) lx = sx - 8 - width;        // at the board's edge: on the junction's other side
    labels.push({ x: lx, y, width });
    parts.push(
      `<g class="bus ${state}${lit ? " lit" : ""}">` + paths.map((d) => `<path d="${d}"/>`).join("") +
      `<circle cx="${sx}" cy="${y}" r="3.5"/>` +
      `<g class="tag" data-tip="${escHtml(tip("bus", { from, to: [...to].join(", ") }))}">` +
      `<rect x="${lx}" y="${y - 17}" width="${width}" height="14" rx="7"/>` +
      `<text x="${lx + 7}" y="${y - 7}">${escHtml(label)}</text></g></g>`
    );
    plugParts.push(`<g class="bus ${state}${lit ? " lit" : ""}">${plugs.join("")}</g>`);
  }
  svg.dataset.focus = plugLayer.dataset.focus = String(!!focus);
  plugLayer.innerHTML = PLUG_DEFS + plugParts.join("");
  // what's still waited for breathes while something starts: opacity only, on a layer of its own
  flow.innerHTML = Object.values(comps).some((c) => c.status === "deploying") ? waiting.map((d) => `<path d="${d}"/>`).join("") : "";

  /* :related (zent.schema): a declared coupling zent never orchestrates, so never a bus - a dotted
     line bowing out by the plates' left edges, where no wire runs. */
  for (const [to, from] of data.informational || []) {
    const a = at[from], b = at[to];
    if (!a || !b) continue;
    const bow = Math.min(a.l, b.l) - 26;
    parts.push(`<path class="related${focus && to !== focus && from !== focus ? " faded" : ""}" ` +
               `d="M${a.l} ${a.y} C${bow} ${a.y} ${bow} ${b.y} ${b.l} ${b.y}"/>`);
  }
  svg.innerHTML = parts.join("");
}

/* Server-rendered (zent.ui.render/detail-panel, GET /api/detail/:name) -
   fetched on selection rather than pushed with every snapshot, since it
   depends on `selected`, which is client-only state the server never
   tracks. Called again on every push too (render()), so a component torn
   down while selected doesn't leave stale facts on screen. */
/* Deselecting (same card again, Escape, a click on the empty fabric) puts the
   detail panel back to its placeholder - render() only refreshes a selection. */
const detailPlaceholder = document.getElementById("detail").outerHTML;

function setSelected(name) {
  // the one let go dims from now, the new one lights up from now (nodeEl)
  if (selected && selected !== name) { unlit = selected; unlitAt = performance.now(); }
  selected = name;
  selectedAt = performance.now();
  if (!name) { document.getElementById("detail").outerHTML = detailPlaceholder; detailShown = null; }
  render();
}

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && selected) setSelected(null);
});

let detailShown = null;                             // whose detail last came in, to animate a new one only
async function loadDetail(name) {
  try {
    const res = await fetch("/api/detail/" + name + previewQuery());
    if (selected !== name) return;                  // selection moved mid-fetch
    document.getElementById("detail").outerHTML = await res.text();
    htmx.process(document.getElementById("detail"));
    if (detailShown !== name) document.getElementById("detail").classList.add("enter");
    detailShown = name;
  } catch {
    /* transient - the next push retries */
  }
}

/* ------------------------------------------------------------------------ */
/* Logs. Files on disk (zent.logs), streamed as Server-Sent Events by
   zent.ui.logstream: one stream while the Logs tab is open, only the new
   lines of the components it shows.                                        */
/* ------------------------------------------------------------------------ */

// a component's colour comes from its name, so it's the same in every preset;
// tones readable on the dark terminal and told apart without full colour vision
const LOG_COLORS = ["#e69f00", "#56b4e9", "#3fbf8f", "#f0e442", "#6b9bff",
                    "#ff7b54", "#d986c0", "#a5a5ff", "#7fd1c3", "#e8a0a0"];
function colorOf(name) {
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return LOG_COLORS[h % LOG_COLORS.length];
}

const LOG_MAX_CHARS = 2_000_000, LOG_TRIM_TO = 1_000_000;

const ansi = new AnsiUp();

// a line's level, by pattern - logs carry no structured one. A stack trace's
// frames don't count: one exception would otherwise be thirty errors.
const LEVEL_RE = { error: /\b(ERROR|FATAL|SEVERE)\b|^\S*(Exception|Error)\b/, warn: /\b(WARN|WARNING)\b/ };
function levelOf(text) {
  return LEVEL_RE.error.test(text) ? "error" : LEVEL_RE.warn.test(text) ? "warn" : "";
}
// shown before each warning/error when filtering by level: what led to it
const LEVEL_CONTEXT = 5;

class LogPane {
  // `newLines`: the "N new lines" button
  constructor(term, newLines) {
    this.term = term;
    this.newLines = newLines;
    this.names = [];
    this.offsets = {};            // name -> byte offset already shown
    this.es = null;
    this.chars = 0;
    this.unseen = 0;
    this.stick = true;
    this.filter = { re: null, level: "" };
    this.context = {};            // name -> the hidden lines just before, for a level filter
    this.counts = { warn: 0, error: 0 };
    this.onCounts = null;
    this.cursor = document.createElement("div");
    this.cursor.className = "cursor";
    this.cursor.textContent = "█";
    this.term.appendChild(this.cursor);
    this.term.addEventListener("scroll", () => {
      this.stick = this.term.scrollTop + this.term.clientHeight >= this.term.scrollHeight - 24;
      if (this.stick) this.setUnseen(0);
    });
    newLines.onclick = () => { this.term.scrollTop = this.term.scrollHeight; };
  }

  watch(names) {
    if (this.es && names.join() === this.names.join()) return;
    const kept = new Set(names);
    // a component dropped from the view leaves it, and comes back with its backlog
    for (const n of this.names) if (!kept.has(n)) delete this.offsets[n];
    this.term.querySelectorAll(".ln").forEach((ln) => { if (!kept.has(ln.dataset.c)) this.remove(ln); });
    this.names = names;
    this.close();
    if (names.length) this.open();
    this.placeholder();
  }

  open() {
    const q = new URLSearchParams({ c: this.names.join(",") });
    const from = this.names.filter((n) => this.offsets[n] !== undefined).map((n) => n + ":" + this.offsets[n]);
    if (from.length) q.set("from", from.join(","));
    const es = new EventSource("/api/logs/stream?" + q);
    es.onmessage = (ev) => this.add(JSON.parse(ev.data));
    // reopened by hand, not by EventSource's own retry: that one would ask
    // for the same URL, backlog included, and show it twice
    es.onerror = () => {
      es.close();
      if (this.es === es) { this.es = null; setTimeout(() => { if (!this.es && this.names.length) this.open(); }, 1000); }
    };
    this.es = es;
  }

  close() {
    if (this.es) this.es.close();
    this.es = null;
  }

  add({ c, l, o, t, b, r }) {
    if (!this.names.includes(c)) return;
    if (r) this.line(c, "--- new run ---", t, "sep");
    for (const text of l) this.line(c, text, t, text.startsWith("--- reload") ? "reload" : "");
    if (o !== undefined) this.offsets[c] = o;
    this.trim();
    this.placeholder();
    if (this.stick) this.term.scrollTop = this.term.scrollHeight;
    else if (!b) this.setUnseen(this.unseen + l.length);
  }

  line(c, text, t, cls) {
    const prev = this.cursor.previousElementSibling;
    const same = prev?.dataset?.c === c;
    const ln = document.createElement("div");
    // the name stands out where the component changes, dimmed while it goes on
    ln.className = "ln" + (cls ? " " + cls : "") + (prev?.dataset?.c && !same ? " ctx" : "") + (same ? " cont" : "");
    ln.dataset.c = c;
    ln.style.setProperty("--c", colorOf(c));
    const g = document.createElement("span"); g.className = "g"; g.textContent = c;
    const ts = document.createElement("span"); ts.className = "ts";
    ts.textContent = t ? new Date(t).toTimeString().slice(0, 8) : "        ";
    const tx = document.createElement("span"); tx.className = "tx";
    // ansi_up escapes the text before turning colour codes into spans, so a
    // log line can't inject markup; filters and copy then read the plain text
    const coloured = text.includes("\x1b[");
    if (coloured) tx.innerHTML = ansi.ansi_to_html(text); else tx.textContent = text;
    ln.append(g, ts, tx);
    const plain = tx.textContent;
    const lvl = cls ? "" : levelOf(plain);
    if (lvl) {
      ln.classList.add(lvl);
      ln.dataset.lvl = lvl;
      // a line with no colours of its own gets its level's
      if (!coloured) ln.classList.add("by-level");
      this.counts[lvl]++;
      this.onCounts?.(this.counts);
    }
    this.apply(ln);
    this.term.insertBefore(ln, this.cursor);
    this.chars += plain.length;
  }

  remove(ln) {
    this.chars -= ln.querySelector(".tx")?.textContent.length || 0;
    if (ln.dataset.lvl) { this.counts[ln.dataset.lvl]--; this.onCounts?.(this.counts); }
    ln.remove();
  }

  // shown or not, for the current filter: the text must match, and with a
  // level, only that level's lines - each with the lines just before it
  apply(ln) {
    const { re, level } = this.filter;
    const textOk = !re || re.test(ln.lastChild.textContent);
    if (!level) { ln.hidden = !textOk; return; }
    const ctx = (this.context[ln.dataset.c] ||= []);
    const hit = textOk && ln.dataset.lvl === level;
    if (hit) { ctx.forEach((p) => { p.hidden = false; }); ctx.length = 0; }
    else { ctx.push(ln); if (ctx.length > LEVEL_CONTEXT) ctx.shift(); }
    ln.hidden = !hit;
  }

  setFilter(filter) {
    this.filter = filter;
    this.context = {};
    this.term.querySelectorAll(".ln").forEach((ln) => this.apply(ln));
    this.term.scrollTop = this.term.scrollHeight;
  }

  // what's shown, as text - prefixed by component when there's more than one
  visibleText() {
    const lines = [...this.term.querySelectorAll(".ln:not([hidden])")];
    const many = new Set(lines.map((ln) => ln.dataset.c)).size > 1;
    return lines.map((ln) => (many ? ln.dataset.c + " | " : "") + ln.lastChild.textContent);
  }

  clear() {
    this.term.querySelectorAll(".ln").forEach((ln) => this.remove(ln));
    this.context = {};
    this.setUnseen(0);
    this.placeholder();
  }

  // past LOG_MAX_CHARS the oldest lines go, down to LOG_TRIM_TO in one cut -
  // rare big cuts, not a reflow on every line
  trim() {
    if (this.chars <= LOG_MAX_CHARS) return;
    for (let ln = this.term.querySelector(".ln"); ln && this.chars > LOG_TRIM_TO; ln = this.term.querySelector(".ln")) this.remove(ln);
  }

  placeholder() {
    let q = this.term.querySelector(".quiet");
    const empty = !this.term.querySelector(".ln");
    if (empty && !q) {
      q = document.createElement("div");
      q.className = "quiet";
      this.term.insertBefore(q, this.cursor);
    }
    if (q) q.textContent = this.names.length ? "No output yet." : "No component shown - pick one above.";
    if (!empty && q) q.remove();
  }

  setUnseen(n) {
    this.unseen = n;
    this.newLines.hidden = n === 0;
    this.newLines.textContent = n + " new line" + (n === 1 ? "" : "s") + " ↓";
  }
}

const logsPane = new LogPane(document.getElementById("logs-term"), document.getElementById("new-lines"));

/* Which components the Logs tab shows: per preset, in localStorage - a view
   preference, not orchestration state. Every component, until changed. */
function logKey() { return "zent.logs." + (view.preset || "none"); }

function logSelection() {
  const all = Object.keys(view.components).sort();
  try {
    const saved = JSON.parse(localStorage.getItem(logKey()));
    if (Array.isArray(saved)) return saved.filter((n) => view.components[n]).sort();
  } catch { /* unreadable: start over from every component */ }
  return all;
}

function setLogSelection(names) {
  localStorage.setItem(logKey(), JSON.stringify([...new Set(names)].sort()));
  render();
}

// a legend, as in Grafana: a click shows only `name` - clicked again, every
// component; ctrl/cmd-click adds it to what's shown, or takes it out
function pickLog(name, e) {
  const sel = logSelection();
  if (e?.ctrlKey || e?.metaKey) {
    return setLogSelection(sel.includes(name) ? sel.filter((n) => n !== name) : [...sel, name]);
  }
  setLogSelection(sel.length === 1 && sel[0] === name ? Object.keys(view.components) : [name]);
}

function renderLogs() {
  const sel = logSelection();
  const pills = document.getElementById("log-pills");
  pills.innerHTML = "";
  for (const name of Object.keys(view.components).sort()) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "pill";
    b.style.setProperty("--c", colorOf(name));
    b.setAttribute("aria-pressed", String(sel.includes(name)));
    b.title = "click: only this one (again: all) · ctrl/⌘-click: add or remove";
    b.textContent = name;
    b.onclick = (e) => pickLog(name, e);
    pills.appendChild(b);
  }
  if (document.body.dataset.tab === "logs") logsPane.watch(sel);
  else logsPane.close();
}

/* The Logs tab's filter lives in the URL (#logs?term=...&level=warn), so a
   filtered view can be bookmarked or reopened. "/.../" is a regexp, anything
   else plain text; both case-insensitive. */
function logParams() { return new URLSearchParams(location.hash.split("?")[1] || ""); }

function readLogFilter() {
  const p = logParams();
  const raw = p.get("term") || "";
  const input = document.getElementById("log-term");
  if (document.activeElement !== input) input.value = raw;
  let re = null;
  input.removeAttribute("aria-invalid");
  input.title = "";
  if (raw) {
    const m = raw.match(/^\/(.+)\/$/);
    try { re = new RegExp(m ? m[1] : raw.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i"); }
    catch (e) { input.setAttribute("aria-invalid", "true"); input.title = e.message; }
  }
  const level = ["warn", "error"].includes(p.get("level")) ? p.get("level") : "";
  document.querySelectorAll(".levels-filter button")
    .forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.level === level)));
  logsPane.setFilter({ re, level });
}

function writeLogFilter(changes) {
  const p = logParams();
  for (const [k, v] of Object.entries(changes)) { if (v) p.set(k, v); else p.delete(k); }
  const q = p.toString();
  // replaceState: no hashchange, no history entry per keystroke
  history.replaceState(null, "", "#logs" + (q ? "?" + q : ""));
  readLogFilter();
}

const TABS = ["stack", "logs", "checks", "branches"];
// #graph: the stack tab's former name, kept for links and bookmarks made before
const tabOf = () => TABS.find((t) => location.hash.startsWith("#" + t)) || "stack";

// a tab change slides the old tab out the way you went, the new one in
function switchTab() {
  const from = TABS.indexOf(document.body.dataset.tab), to = TABS.indexOf(tabOf());
  if (from === to) return showTab();
  transit(to > from ? "next" : "prev", showTab);
}

function showTab() {
  const tab = tabOf();
  document.body.dataset.tab = tab;
  document.querySelectorAll("#tabs a").forEach((a) => {
    if (a.dataset.tab === tab) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
  });
  renderLogs();
  if (tab === "logs") readLogFilter();
  else if (tab === "checks") loadChecks();
  else if (tab === "branches") loadBranches();
  else drawWarp();                                // the loom was hidden: positions are stale
}

/* ------------------------------------------------------------------------ */
/* Checks: the active preset's clojure.test checks (zent.checks), shown like
   an IDE's test runner. GET /api/checks gives each check's results; a run
   (POST /api/checks/run) streams its output like a log (/api/logs/stream),
   whose "#zent/check {...}" lines move the tree live - the failures' detail
   comes with the result, fetched once the run is over.                     */
/* ------------------------------------------------------------------------ */

const CHECK_GLYPH = { pass: "✓", accepted: "≈", fail: "✗", error: "!", unknown: "?", run: "…" };
const checks = { data: null, preset: undefined, sel: {}, runIdx: {}, live: {}, streams: {} };

// a docstring's lines as written, without the indent of its continuation lines
const docLines = (d) => String(d).split("\n").map((l) => l.trim()).join("\n");
const hms = (ms) => new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });

async function loadChecks() {
  try {
    const res = await fetch("/api/checks");
    if (!res.ok) return;
    checks.data = await res.json();
  } catch { return; }
  for (const c of checks.data.checks) {
    if (c.running && !checks.streams[c.name]) followCheck(c);
    // over, and nothing left to read: its result is in the data now
    if (!c.running && !checks.streams[c.name]) delete checks.live[c.name];
  }
  renderChecks();
  // a run whose stream ended before its result was written: ask again shortly
  if (checks.data.checks.some((c) => c.running || checks.live[c.name])) setTimeout(loadChecks, 1000);
}

// the preset changed (a push): its checks are another set
function checksOnView() {
  if (view.preset === checks.preset) return;
  checks.preset = view.preset;
  checks.sel = {}; checks.runIdx = {};
  loadChecks();
}

// What the tree shows: a live run, a run picked in the history, or each test's latest known result
function shownCheckRun(c) {
  if (checks.live[c.name]) return checks.live[c.name];
  const i = checks.runIdx[c.name];
  return i === undefined ? c.current || c.runs.at(-1) : c.runs[i];
}

function followCheck(c) {
  checks.live[c.name] = { tests: [], live: true };
  const es = new EventSource("/api/logs/stream?" + new URLSearchParams({ c: c.log }));
  checks.streams[c.name] = es;
  const end = () => { es.close(); delete checks.streams[c.name]; };
  es.onmessage = (ev) => {
    const { l, r } = JSON.parse(ev.data);
    const live = checks.live[c.name];
    if (!live) return end();
    if (r) live.tests = [];
    for (const text of l) {
      // EDN, read just enough: the event, the test, a :begin's docstring (EDN string escapes are JSON's)
      const m = /^#zent\/check \{:event :(\w+)(?:, :test "([^"]*)")?(?:, :doc ("(?:[^"\\]|\\.)*"))?/.exec(text);
      if (!m) continue;
      const [, event, name, doc] = m;
      if (event === "done") { live.done = true; end(); setTimeout(loadChecks, 300); continue; }
      let t = live.tests.find((x) => x.name === name);
      if (!t) { t = { name, status: "run", assertions: 0, failures: [] }; live.tests.push(t); }
      if (doc) { try { t.doc = JSON.parse(doc); } catch { /* shown once the result is fetched */ } }
      if (event === "pass") t.assertions++;
      else if (event === "fail") { t.assertions++; t.status = "fail"; }
      else if (event === "error") t.status = "error";
      else if (event === "end" && t.status === "run") t.status = "pass";
      checks.sel[c.name] = name;
    }
    renderChecks();
  };
  es.onerror = () => { end(); setTimeout(loadChecks, 1000); };
}

/* A failed (= expected actual) (zent.diff): one side of its segments, what differs marked by `tag`
   (none: plain text); side by side; key by key for two maps, an unchanged key greyed. */
const diffSide = (segs, op, tag) => segs.filter(([o]) => o === "same" || o === op)
  .map(([o, t]) => o === "same" || !tag ? escHtml(t) : `<${tag}>${escHtml(t)}</${tag}>`).join("") || `<span class="none">empty</span>`;
function diffHtml({ segments, keys }) {
  if (keys) return `<table class="chk-keys">${keys.map((k) => `<tr data-op="${k.op}"><td class="mark">${
    { add: "+", del: "−", changed: "~", same: "" }[k.op]}</td><td class="k">${escHtml(k.key)}</td><td>${
    k.op === "changed" ? `${diffSide(k.segments, "del", "del")} <span class="none">→</span> ${diffSide(k.segments, "add", "ins")}`
    : k.op === "add" ? `<ins>${escHtml(k.value)}</ins>` : k.op === "del" ? `<del>${escHtml(k.value)}</del>` : escHtml(k.value)}</td></tr>`).join("")}</table>`;
  return `<div class="chk-diff"><div class="exp"><h4>Expected</h4><div class="v">${diffSide(segments, "del", "del")}</div></div>
    <div class="got"><h4>Actual</h4><div class="v">${diffSide(segments, "add", "ins")}</div></div></div>`;
}
// the same, on one line: an example under its group
const diffInline = ({ segments, keys }) => keys
  ? keys.filter((k) => k.op !== "same").map((k) => `${escHtml(k.key)} ${k.op === "changed"
      ? `${diffSide(k.segments, "del")} → ${diffSide(k.segments, "add")}` : escHtml(k.value)}`).join(" · ")
  : `${diffSide(segments, "del")} → ${diffSide(segments, "add")}`;

const num = (v) => /^-?\d+(\.\d+)?$/.test(String(v)) ? Number(v).toLocaleString() : String(v);
const ORACLE_LABEL = { "business-rule": "business rule" };
const capitalized = (s) => s[0].toUpperCase() + s.slice(1);
// a test as a reader sees it: its :zent/title, else its name spelled out
const titleOf = (t) => t.title || capitalized(t.name).replace(/-/g, " ");
const failingCount = (t) => (t.groups || []).reduce((n, g) => n + g.count, 0) || t.failures.length;
const bad = (t) => t.status === "fail" || t.status === "error";
// its outcome in a few words, under its title in the list
function testLine(t) {
  const facts = t.facts || [], worst = facts.find(([, , b]) => b);
  if (t.status === "run") return "running…";
  if (t.status === "fail") return worst ? `${num(worst[1])} ${worst[0]}` : `${num(failingCount(t))} failing`;
  if (t.status === "error") return "couldn't finish";
  if (t.status === "accepted") return "accepted gap";
  return facts[0] ? `${num(facts[0][1])} ${facts[0][0]}` : `${num(t.assertions)} passed`;
}
// its outcome in a sentence: its :zent/verdict, else what the result says
const verdictOf = (t) => t.verdict ? escHtml(t.verdict)
  : { run: "Running…", pass: "Passes", accepted: "Passes, with an accepted gap", error: "Couldn't finish: it threw",
      unknown: "Not judged" }[t.status] ?? `${num(failingCount(t))} failing of ${num(t.assertions)} assertion${t.assertions > 1 ? "s" : ""}`;

const chips = (where) => where.map(([k, v]) => `<span class="chk-chip"><span>${escHtml(k)}</span>${escHtml(v)}</span>`).join(" ");
// a group of failures sharing one shape (zent.checks): what it is, how many, one shown, a few more on demand
function groupHtml(g, err) {
  const ex = g.examples[0];
  const title = g.group || (err ? "Threw" : ex.diff ? "Difference" : ex.message) || "Failure";
  // without a diff, the message says it; an exception's own text is shown, a failing form's isn't
  const raw = err ? ex.actual : ex.message !== title && ex.message;
  const body = ex.diff ? diffHtml(ex.diff) : raw ? `<pre class="raw">${escHtml(String(raw).slice(0, 600))}</pre>` : "";
  const more = g.examples.length > 1 || ex.where?.length;
  return `<div class="chk-group ${err ? "err" : ""}"><div class="h"><b>${escHtml(title)}</b>${g.count > 1 ? `<span class="n">${num(g.count)} ×</span>` : ""}</div>
    ${g.explain ? `<div class="sub">${escHtml(g.explain)}</div>` : ""}${body ? `<div class="body">${body}</div>` : ""}
    ${more ? `<details class="chk-ex"><summary>Show examples (${g.examples.length} of ${num(g.count)})</summary>${g.examples.map((x) =>
      `<div class="ex">${x.where?.length ? chips(x.where) : escHtml(x.message || "")}<span class="v">${x.diff ? diffInline(x.diff) : ""}</span></div>`).join("")}</details>` : ""}</div>`;
}

async function runCheck(c, only) {
  // a check that writes runs only when asked for, like a :confirm one-shot
  if (c.writes && !(await askConfirm({ name: c.name, action: "run",
                                       question: "It writes to the running stack. Run it?" }))) return;
  const q = new URLSearchParams({ check: c.name });
  if (only?.length) q.set("only", only.join(","));
  const res = await fetch("/api/checks/run?" + q, {
    method: "POST", headers: { "X-Zent-Token": csrfToken() } });
  if (!res.ok) { alert(await res.text()); return; }
  delete checks.runIdx[c.name];
  followCheck(c);
  renderChecks();
}

const checkList = () => checks.data?.checks || [];

function renderChecks() {
  const tab = document.getElementById("checks-tab"), badge = document.getElementById("checks-badge");
  const list = checkList();
  tab.hidden = !list.length;
  const tests = list.flatMap((c) => shownCheckRun(c)?.tests || []);
  const failing = tests.filter(bad).length, running = list.some((c) => checks.live[c.name]);
  badge.hidden = !tests.length && !running;
  badge.textContent = running ? "…" : failing || "✓";
  badge.className = "tab-badge" + (failing || running ? "" : " ok");
  if (document.body.dataset.tab !== "checks") return;

  const tree = document.getElementById("checks-tree"), detail = document.getElementById("checks-detail");
  document.getElementById("checks-run-all").disabled = document.getElementById("checks-run-failed").disabled = running;
  // the summary: how the tests went, as a sentence and a bar
  const n = { pass: 0, accepted: 0, fail: 0, unknown: 0 };
  for (const t of tests) n[bad(t) ? "fail" : t.status in n ? t.status : "unknown"]++;
  document.getElementById("checks-headline").innerHTML = running ? "Running…"
    : !tests.length ? "No results yet"
    : (failing ? `<b>${failing} test${failing > 1 ? "s" : ""} failing</b> of ${tests.length}` : `All ${tests.length} tests pass`)
      + (n.accepted ? ` · ${n.accepted} accepted gap${n.accepted > 1 ? "s" : ""}` : "");
  document.getElementById("checks-bar").innerHTML = Object.entries(n).filter(([, k]) => k)
    .map(([s, k]) => `<i class="${s}" style="width:${(100 * k) / tests.length}%"></i>`).join("");
  const LEGEND = { pass: ["passing", "var(--green)"], accepted: ["accepted", "var(--amber)"], fail: ["failing", "var(--red)"], unknown: ["not judged", "#c9cbd1"] };
  document.getElementById("checks-legend").innerHTML = Object.entries(n).filter(([, k]) => k)
    .map(([s, k]) => `<span style="--c:${LEGEND[s][1]}">${k} ${LEGEND[s][0]}</span>`).join("");

  if (!list.length) {
    tree.innerHTML = `<p class="checks-empty">${checks.data?.preset ? "No checks for this preset - checks/&lt;preset&gt;/ in the catalog repo." : "No preset running."}</p>`;
    detail.innerHTML = "";
    return;
  }
  const onlyFailed = document.getElementById("checks-only-failed").checked;
  // the list: checks with a failing test first, a line per test
  const failingFirst = [...list].sort((a, b) => !(shownCheckRun(a)?.tests || []).some(bad) - !(shownCheckRun(b)?.tests || []).some(bad));
  tree.innerHTML = failingFirst.map((c) => {
    const r = shownCheckRun(c), k = (r?.tests || []).filter(bad).length;
    return `<div class="chk" data-check="${escHtml(c.name)}">
      <div class="h" title="${escHtml(c.title || "")}">${escHtml(capitalized(c.title ? c.title.split(":")[0] : c.name))}
        ${c.writes ? `<span class="badge writes" title="a check that writes: runs only when asked for, never with Run all">writes</span>` : ""}
        <small>${r?.live ? "running…" : !r ? "never run" : k ? `<b>${k} failing</b>` : `${r.tests.length} ✓`}</small></div>
      ${c.problem ? `<div class="chk-problem">${escHtml(c.problem)}</div>` : ""}
      ${(r?.tests || []).filter((t) => !onlyFailed || bad(t)).map((t) => `
        <div class="chk-t st-${t.status}" data-test="${escHtml(t.name)}" aria-selected="${checks.selCheck === c.name && checks.sel[c.name] === t.name}">
          <span class="chk-dot"></span><span class="what"><div>${escHtml(titleOf(t))}</div><small>${escHtml(testLine(t))}</small></span>
          ${t.oracle ? `<span class="badge" title="its oracle: ${escHtml(t.oracle)}">${escHtml(ORACLE_LABEL[t.oracle] || t.oracle)}</span>` : ""}</div>`).join("")}</div>`;
  }).join("");

  // the detail: the selected test, else the first failing one
  const c = list.find((x) => x.name === checks.selCheck) || failingFirst[0];
  const r = shownCheckRun(c);
  const t = r && (r.tests.find((x) => x.name === checks.sel[c.name]) || r.tests.find(bad) || r.tests[0]);
  if (!r || !t) {
    detail.innerHTML = r?.error ? `<h3>Not judged: the run failed</h3><div class="chk-error">${escHtml(r.error)}</div>` : "";
    return;
  }
  checks.selCheck = c.name; checks.sel[c.name] = t.name;
  tree.querySelector(`[data-check="${CSS.escape(c.name)}"] [data-test="${CSS.escape(t.name)}"]`)?.setAttribute("aria-selected", "true");
  // what changed since the full run before the one shown
  const prev = r.live ? null : [...c.runs].reverse().find((x) => !x.only && x["started-at"] < (t.at ?? r["started-at"] ?? Infinity));
  const before = prev?.tests.find((x) => x.name === t.name);
  const since = !before ? "" : before.status === t.status ? `Unchanged since ${hms(prev["started-at"])}.`
    : !bad(t) ? `<span class="fixed">Fixed</span> - ${before.status} at ${hms(prev["started-at"])}.`
    : `<span class="new">Newly ${t.status === "fail" ? "failing" : t.status}</span> - ${before.status} at ${hms(prev["started-at"])}.`;
  const [first, ...rest] = t.doc ? docLines(t.doc).split("\n") : [];
  const cmd = `zent verify ${checks.data.preset} ${c.name} --only=${t.name}`;
  const groups = t.groups || t.failures.map((f) => ({ count: 1, examples: [f] }));
  detail.innerHTML = `
    <div class="chk-verdict st-${t.status}"><span class="chk-dot"></span><div><h2>${verdictOf(t)}</h2>
      <p>${escHtml(titleOf(t))}${c.title ? ` · ${escHtml(c.title)}` : ""} · <code>${escHtml(t.name)}</code></p></div></div>
    ${first ? `<dl class="chk-why ${checks.whyOpen ? "" : "closed"}"><dt>Checks</dt><dd>${escHtml(first)}</dd>
      ${rest.length ? `<dt class="more">Details</dt><dd class="more">${escHtml(rest.join("\n"))}</dd>
        <button type="button" id="checks-why">${checks.whyOpen ? "Hide" : "How and why"}</button>` : ""}</dl>` : ""}
    ${t.facts?.length || t.notes?.length ? `<h3>What it judged</h3>` : ""}
    ${t.facts?.length ? `<div class="chk-facts">${t.facts.map(([l, v, b]) =>
      `<div class="chk-fact ${b ? "bad" : ""}"><b>${escHtml(num(v))}</b><span>${escHtml(l)}</span></div>`).join("")}</div>` : ""}
    ${t.notes?.length ? `<ul class="chk-notes">${t.notes.map((x) => `<li>${escHtml(x)}</li>`).join("")}</ul>` : ""}
    ${t.accepted?.length ? `<h3>Accepted</h3>${t.accepted.map((x) => `<div class="chk-accepted">${escHtml(x)}</div>`).join("")}` : ""}
    ${groups.length ? `<h3>${t.status === "error" ? "What went wrong" : "Gaps, grouped"}</h3>${groups.map((g) => groupHtml(g, t.status === "error")).join("")}` : ""}
    ${r.error ? `<div class="chk-error">${escHtml(r.error)}</div>` : ""}
    ${c.runs.length ? `<div class="chk-since">${since || `Last run ${r["started-at"] ? hms(r["started-at"]) : "now"}.`}
      <div class="chk-runs">${c.runs.map((x, i) => `<button type="button" class="${x.status} ${x.only ? "partial" : ""}" data-i="${i}" aria-pressed="${checks.runIdx[c.name] === i}"
        title="${hms(x["started-at"])} · ${x.status}${x.only ? " · only " + x.only.join(", ") : ""}">${CHECK_GLYPH[x.status]}</button>`).join("")}
      ${checks.runIdx[c.name] !== undefined ? `<button type="button" id="checks-latest">Back to the latest</button>` : ""}</div></div>` : ""}
    <div class="chk-cmd"><code>${escHtml(cmd)}</code>
      <button type="button" id="checks-run-one" ${running ? "disabled" : ""}>&#9654; Run this test</button>
      <button type="button" id="checks-copy">Copy</button></div>`;
  document.getElementById("checks-run-one").onclick = () => runCheck(c, [t.name]);
  document.getElementById("checks-copy").onclick = () => navigator.clipboard?.writeText(cmd);
}

function initChecksControls() {
  document.getElementById("checks-view").addEventListener("click", (e) => {
    const row = e.target.closest(".chk-t"), box = e.target.closest(".chk");
    const run = e.target.closest(".chk-runs button[data-i]");
    const current = checks.selCheck;
    if (row && box) { checks.selCheck = box.dataset.check; checks.sel[box.dataset.check] = row.dataset.test; renderChecks(); }
    else if (run && current) { checks.runIdx[current] = +run.dataset.i; renderChecks(); }
    else if (e.target.id === "checks-latest" && current) { delete checks.runIdx[current]; renderChecks(); }
    else if (e.target.id === "checks-why") { checks.whyOpen = !checks.whyOpen; renderChecks(); }
  });
  document.getElementById("checks-only-failed").onchange = renderChecks;
  // every check of the preset, one after the other would queue on the server: one run each, in parallel
  document.getElementById("checks-run-all").onclick = () => checkList().filter((c) => !c.writes).forEach((c) => runCheck(c, null));
  document.getElementById("checks-run-failed").onclick = () => checkList().forEach((c) => {
    const names = (shownCheckRun(c)?.tests || []).filter(bad).map((t) => t.name);
    if (names.length) runCheck(c, names);
  });
}

/* ------------------------------------------------------------------------ */
/* Branches: each repo the shown preset runs (GET /api/branches, zent.graph),
   drawn as git graph tools draw it - newest first, a column per lane, the
   refs on their commit: a local branch and origin's as one label when they
   coincide, apart with ↑↓ when they don't. What needs doing comes first, as
   chips with their action; hovering a chip lights the commits it's about,
   hovering an action says what it would do.                               */
/* ------------------------------------------------------------------------ */

// the network only on Fetch: loads read the repos as they are (git, local), never fetch
const branches = { data: null, loading: false, fetching: false, unfolded: new Set() };
// BR_CALM: the commits an up-to-date repo shows before "N more"
const BR_ROW = 24, BR_LANE = 14, BR_FOLD = 6, BR_CALM = 5, BR_STALE = 3600e3;
const LANE_COLORS = ["#0a84ff", "#8e5bd6", "#1fa38a", "#e8890c", "#d6457a", "#5b6ee1"];
const laneColor = (col) => LANE_COLORS[col % LANE_COLORS.length];
const ico = (i) => `<i class="ico" data-i="${i}"></i>`;
/* A branch (or a dir) as a label wherever one is named - zent.ui.render/branch-chip's twin: its icon
   (local, remote, both, wt, folder), the name without its feature/... prefix, the full one on hover. */
const bn = (kind, full, { shown = bareBranch(full), cls = "", extra = "", tip } = {}) =>
  `<span class="bn ${cls}" data-kind="${kind}" data-tip="${escHtml(tip ?? full)}">` +
  `${(kind === "both" ? ["local", "remote"] : [kind]).map(ico).join("")}${escHtml(shown)}${extra}</span>`;
// a label's text, for what can't hold markup (a confirm's question)
const textOf = (html) => Object.assign(document.createElement("div"), { innerHTML: html }).textContent;
// where a project's code comes from, as its badge says it - the line under it ("Runs", the server's) says the rule
const SOURCE = { "origin-worktree": "origin worktree", "local-worktree": "local worktree", "main-worktree": "main worktree" };
const bareBranch = (b) => String(b ?? "").replace(/^(feature|bugfix|bug|fix|hotfix|chore|release)\//, "");
const shortSha = (s) => (s || "").slice(0, 7);
const plural = (n, w) => n + " " + w + (n === 1 ? "" : "s");
// an age, short (a commit's column: 5 min, 3 h, 2 d) or said (just now, 5 min ago - zent.ui.render/ago's)
const ageText = (ms) => { const m = Math.round(ms / 60e3); return m < 60 ? Math.max(m, 1) + " min" : m < 2880 ? Math.round(m / 60) + " h" : Math.round(m / 1440) + " d"; };
const agoText = (ms) => ms < 60e3 ? "just now" : ageText(ms) + " ago";
const previewQuery = () => shownPreset() ? "?preset=" + encodeURIComponent(shownPreset()) : "";

/* Where a project's code runs: from (origin-worktree|local-worktree|main-worktree), wt (a zent worktree), the commit, its
   uncommitted files, and the commits it was deployed from - by what runs now: a one-shot done long ago doesn't count. */
function brRun(p) {
  const from = p.from || "main-worktree", wt = from !== "main-worktree";
  return { from, wt, sha: wt ? p.worktree?.sha : p.head.sha, dirty: wt ? (p.worktree?.dirty || []) : p.head.dirty,
           deployed: p.components.filter((c) => ["up", "unready", "warm"].includes(c.status)).map((c) => c["deployed-sha"]).filter(Boolean) };
}

async function loadBranches() {
  if (branches.loading) return;
  const asked = shownPreset();
  branches.loading = asked || view.preset || true;
  renderBranches();                               // says it's reading, at once: git takes seconds
  try {
    const res = await fetch("/api/branches" + previewQuery());
    if (res.ok) { branches.data = await res.json(); branches.of = asked; }
  } catch { /* the next load retries */ } finally { branches.loading = false; }
  // switched preset meanwhile: what came back is the other one's
  if (asked !== shownPreset()) return loadBranches();
  renderBranches();
}

async function fetchBranches() {
  if (branches.fetching) return;
  branches.fetching = true; renderBranches();
  try {
    const res = await fetch("/api/branches/fetch" + previewQuery(), { method: "POST", headers: { "X-Zent-Token": csrfToken() } });
    const reply = await res.json().catch(() => ({}));
    for (const [dir, why] of Object.entries(reply.failed || {})) toast("failed", "fetch failed: " + dir.split("/").pop(), why);
  } catch { toast("failed", "fetch failed", "the daemon didn't answer"); }
  branches.fetching = false;
  loadBranches();
}

// commits reachable from `tips` among the drawn ones
function reach(p, tips) {
  const by = new Map(p.commits.map((c) => [c.sha, c])), seen = new Set(), todo = tips.filter(Boolean);
  while (todo.length) {
    const s = todo.pop();
    if (seen.has(s) || !by.has(s)) continue;
    seen.add(s); todo.push(...by.get(s).parents);
  }
  return seen;
}
const minus = (a, b) => new Set([...a].filter((x) => !b.has(x)));

/* What a project needs doing, most pressing first: [html, level (bad|warn), action, shas it's about].
   An action: [verb, label, what it would do (html), needs a confirm?, refused because]. Branch names
   are labels (bn), as on the graph. */
function brStates(p) {
  const out = [], g = p.git || {}, { from, wt, sha: runSha, dirty, deployed: [deployed] } = brRun(p);
  const ref = (n) => p.refs.find((r) => r.name === n);
  const target = p.branch || p.head.branch, L = ref(target), R = ref("origin/" + target);
  const mine = bn("local", target || ""), theirs = bn("remote", "origin/" + target, { shown: "origin/" + bareBranch(target) });
  const base = p.base || g.base, originBase = bn("remote", "origin/" + base, { shown: "origin/" + base });
  if (from === "main-worktree" && p.branch && p.head.branch !== p.branch)
    out.push([`On ${p.head.branch ? bn("local", p.head.branch) : "a detached HEAD"}, the preset wants ${bn("local", p.branch)}`, "bad",
              ["checkout", "Check out", `checks out ${bn("local", p.branch)} in your main worktree`, true,
               p.head.dirty.length ? "uncommitted files: commit or stash them first" : null],
              new Set([p.head.sha])]);
  if (wt && dirty.length) out.push([`${plural(dirty.length, "file")} edited by hand in zent's worktree`, "bad", null, new Set([runSha])]);
  else if (dirty.length) out.push([`${plural(dirty.length, "uncommitted file")}`, "warn", null, new Set(["wip"])]);
  if (wt && g.unreachable > 0) out.push([`${plural(g.unreachable, "commit")} on no branch in zent's worktree`, "bad", null, new Set([runSha])]);
  if (L && R && target !== base) {
    const ahead = minus(reach(p, [L.sha]), reach(p, [R.sha])), behind = minus(reach(p, [R.sha]), reach(p, [L.sha]));
    // :origin-worktree runs what's pushed: your local commits on the same branch don't run until pushed
    const pushNote = (L.behind > 0 ? `replaces ${plural(L.behind, "commit")} on ${theirs} (force-with-lease)` : `sends ${plural(L.ahead, "commit")} to ${theirs}`)
      + (from === "origin-worktree" ? ", then Sync to run them" : "");
    const notRunning = from === "origin-worktree" ? ` - they don't run: zent runs ${theirs}` : "";
    if (L.ahead > 0 && L.behind > 0)
      out.push([`${mine} and ${theirs} diverged: ↑${L.ahead} ↓${L.behind}${notRunning}`, "bad", ["push", "Push", pushNote, true], new Set([...ahead, ...behind])]);
    else if (L.ahead > 0)
      out.push([`${plural(L.ahead, "commit")} on ${mine} not pushed${notRunning}`, from === "origin-worktree" ? "bad" : "warn", ["push", "Push", pushNote, true], ahead]);
    else if (L.behind > 0)
      out.push([`${mine} is ${L.behind} behind ${theirs}`, "bad",
                ["fast-forward", "Fast-forward", `fast-forwards ${mine} to ${theirs}: nothing of yours is lost`, false,
                 p.head.branch === target ? null : `your main worktree has ${p.head.branch || "no branch"} checked out: fast-forward ${target} there`], behind]);
  } else if (L && !R && target !== base && L.ahead !== 0) {
    out.push([`${mine} isn't on origin`, "warn", ["push", "Push", `creates ${theirs}`, true], reach(p, [L.sha])]);
  }
  // the default branch: told, never pushed from here
  const M = ref(base), MR = ref("origin/" + base);
  if (M && MR && M.ahead > 0)
    out.push([`${plural(M.ahead, "commit")} on ${bn("local", base, { shown: base })} not pushed`, "warn", null, minus(reach(p, [M.sha]), reach(p, [MR.sha]))]);
  if (wt && g.unsynced > 0)
    out.push([`zent's worktree is ${g.unsynced} behind ${from === "local-worktree" ? mine : theirs}`, "bad",
              ["sync", "Sync", `moves the worktree ${plural(g.unsynced, "commit")} ahead, then redeploys`, false, dirty.length ? "files edited by hand in zent's worktree" : null],
              minus(reach(p, [from === "local-worktree" ? L?.sha : R?.sha]), reach(p, [runSha]))]);
  if (deployed && runSha && deployed !== runSha)
    out.push([`Changed since it started: runs <code class="sha">${shortSha(deployed)}</code>, the disk has <code class="sha">${shortSha(runSha)}</code>`, "warn",
              ["reload", "Reload", `restarts it on <code class="sha">${shortSha(runSha)}</code>`, false], new Set([deployed, runSha])]);
  if (g.behind > 0) {
    const c = p.conflicts;
    const note = c == null ? "no conflict" : c.length ? `conflicts on ${plural(c.length, "file")}: ${escHtml(c.join(", "))}` : "conflicts unknown";
    const why = g.branch == null && !wt ? "detached HEAD" : wt && target === base ? `on ${base} itself`
      : wt && dirty.length ? "uncommitted files: commit or stash them first"
      : g["held-at"] ? `${target} is checked out in ${g["held-at"]}` : c?.length ? "it conflicts: rebase it by hand" : null;
    out.push([`${originBase} is ${g.behind} ahead`, "warn",
              ["rebase", from === "origin-worktree" ? `Trial rebase onto ${base}` : "Rebase",
               (from === "origin-worktree" ? `runs ${theirs} rebased onto ${originBase}, locally in its worktree` : `rebases ${mine} onto ${originBase}`) + ` - ${note}` +
               (!wt && dirty.length ? `, ${plural(dirty.length, "uncommitted file")} autostashed` : ""), true, why],
              minus(reach(p, [ref("origin/" + base)?.sha]), reach(p, [runSha]))]);
  }
  return out;
}

/* The rows shown: a working-copy row on top when what runs has uncommitted files, then the commits,
   runs of plain ones (no label, one parent, lines straight) folded past BR_FOLD unless unfolded. */
function brRows(p, labels) {
  const { dirty, sha: runSha } = brRun(p), rows = [], cs = p.commits;
  const plain = (c) => !labels.has(c.sha) && c.parents.length <= 1 && c.segs.every(([a, b]) => a === b);
  for (let i = 0; i < cs.length;) {
    let j = i;
    while (j < cs.length && plain(cs[j])) j++;
    if (j - i > BR_FOLD && !branches.unfolded.has(p.repo + cs[i].sha)) {
      rows.push(cs[i], cs[i + 1]);
      const hidden = cs.slice(i + 2, j - 1);
      rows.push({ fold: hidden, key: p.repo + cs[i].sha, col: hidden[0].col, segs: hidden[0].segs, sha: "fold:" + i });
      rows.push(cs[j - 1]);
      i = j;
    } else {
      const end = Math.max(j, i + 1);
      for (; i < end; i++) rows.push(cs[i]);
    }
  }
  const head = cs.find((c) => c.sha === runSha);
  if (dirty.length && head) rows.unshift({ wip: dirty, at: runSha, col: head.col, segs: [], sha: "wip" });
  return rows;
}

// the labels on each commit: branches local + origin merged when they coincide, HEAD, worktrees
function brLabels(p) {
  const labels = new Map(), add = (sha, html) => sha && labels.set(sha, (labels.get(sha) || "") + html), { wt } = brRun(p);
  // your main worktree's HEAD, always, first - then the branch it's on (HEAD -> branch, as git log --decorate)
  add(p.head.sha, `<span class="bn head-tag" data-tip="${escHtml("Your main worktree's HEAD\n" + (!p.head.branch ? "Detached, on no branch"
    : `On ${p.head.branch}` + (wt ? " - free: zent runs its worktree, not your main worktree" : " - what runs")))}">HEAD</span>`);
  // a label's hover: its full name, then where it stands against the other side
  for (const n of new Set(p.refs.map((r) => r.branch))) {
    const L = p.refs.find((r) => r.name === n), R = p.refs.find((r) => r.name === "origin/" + n);
    const cls = n === p.base ? "main" : "", shown = n === p.base ? n : bareBranch(n);
    if (L && R && L.sha === R.sha) add(L.sha, bn("both", n, { shown, cls, tip: `${n}\nYours and origin's: the same commit` }));
    else {
      if (L) {
        const ab = [L.ahead && "↑" + L.ahead, L.behind && "↓" + L.behind].filter(Boolean).join(" ");
        add(L.sha, bn("local", n, { shown, cls, extra: ab && `<b class="ab">${ab}</b>`,
                                    tip: `${n}\nYours: ` + (R ? `${plural(L.ahead || 0, "commit")} not on origin, ${L.behind || 0} only on origin` : "not on origin") }));
      }
      if (R) add(R.sha, bn("remote", "origin/" + n, { shown: "origin/" + shown, cls,
                                                       tip: `origin/${n}\nOrigin's: ` + (L ? "apart from yours" : "you have no local branch") }));
    }
  }
  if (wt && p.worktree)
    add(p.worktree.sha, bn("wt", p.worktree.path, { shown: "worktree", cls: "wt", extra: p.git?.unsynced > 0 ? `<b class="ab">↓${p.git.unsynced}</b>` : "",
                                                    tip: `${p.worktree.path}\nzent's worktree, what runs: detached on this commit` +
                                                         (p.git?.unsynced > 0 ? `, ${plural(p.git.unsynced, "commit")} behind - Sync to run them` : "") }));
  for (const o of p.others || []) add(o.sha, bn("wt", o.path, { shown: "worktree", cls: "wt idle", tip: `${o.path}\nAnother zent worktree of this repo` }));
  return labels;
}

function brGraph(p, rows) {
  const lanes = Math.max(1, ...rows.map((r) => Math.max(r.col + 1, ...r.segs.flat().map((x) => x + 1))));
  const x = (c) => 9 + c * BR_LANE, y = (i) => i * BR_ROW + BR_ROW / 2;
  const deployed = new Set(brRun(p).deployed);
  let s = "";
  rows.forEach((r, i) => {
    if (i === 0) return;
    for (const [a, b] of r.segs) {
      const col = laneColor(r.fold ? a : (b === r.col && a !== b ? a : b));
      s += a === b ? `<line x1="${x(a)}" y1="${y(i - 1)}" x2="${x(b)}" y2="${y(i)}" stroke="${col}"/>`
        : `<path d="M${x(a)},${y(i - 1)} C${x(a)},${y(i) - 4} ${x(b)},${y(i - 1) + 4} ${x(b)},${y(i)}" stroke="${col}" fill="none"/>`;
    }
  });
  rows.forEach((r, i) => {
    const cx = x(r.col), cy = y(i), col = laneColor(r.col);
    if (r.wip) {
      const h = rows.findIndex((q) => q.sha === r.at);
      s += `<line class="wip" x1="${cx}" y1="${cy}" x2="${cx}" y2="${y(h)}"/><circle class="wip" cx="${cx}" cy="${cy}" r="5"/>`;
    } else if (r.fold) {
      s += [-6, 0, 6].map((d) => `<circle cx="${cx}" cy="${cy + d}" r="${d ? 1.6 : 2.6}" fill="${col}"/>`).join("");
    } else {
      s += `<circle cx="${cx}" cy="${cy}" r="4.5" fill="${col}" stroke="#fff" stroke-width="1.5"/>`;
      if (r.sha === p.head.sha) s += `<circle cx="${cx}" cy="${cy}" r="8" fill="none" stroke="#28b463" stroke-width="2"/>`;
      if (deployed.has(r.sha)) s += `<circle class="br-pulse" cx="${cx}" cy="${cy}" r="7"/>`;
    }
  });
  return [`<svg class="br-svg" width="${x(lanes - 1) + 10}" height="${rows.length * BR_ROW}">${s}</svg>`, x(lanes - 1) + 16];
}

function brCell(p) {
  const name = p.repo, comps = p.components.map((c) => c.name);
  if (p.error) return `<article class="br-cell" data-level="bad"><div class="br-head"><span class="br-name">${escHtml(name)}</span></div><p class="br-error">${escHtml(p.error)}</p></article>`;
  const { from } = brRun(p), states = brStates(p), labels = brLabels(p);
  let rows = brRows(p, labels);
  // up to date: its last commits still say what's there - the rest a click away
  const key = p.repo + ":all";
  if (!states.length && rows.length > BR_CALM + 1 && !branches.unfolded.has(key)) {
    const rest = rows.slice(BR_CALM).flatMap((r) => r.fold || (r.sha?.length === 40 ? [r] : []));
    rows = [...rows.slice(0, BR_CALM), { fold: rest, key, col: rows[BR_CALM - 1].col, segs: [], sha: "fold:all" }];
  }
  const [svg, indent] = brGraph(p, rows);
  const fetched = p["fetched-at"], age = fetched ? Date.now() - fetched : Infinity;
  const fresh = branches.fetching ? `<span class="br-fresh busy">fetching…</span>`
    : `<span class="br-fresh${age > BR_STALE ? " stale" : ""}" data-tip="${fetched ? "Last fetch\n" + new Date(fetched).toLocaleString() : "Never fetched"}">${fetched ? "fetched " + agoText(age) : "never fetched"}</span>`;
  const acts = [];
  const seen = new Set();
  states.forEach(([, , a], i) => { if (a && !seen.has(a[0])) { seen.add(a[0]); acts.push([...a, i]); } });
  const [deployed] = brRun(p).deployed;
  const tipOf = (c) => `${c.subject}\n${shortSha(c.sha)} · ${c.author} · ${c.time ? new Date(c.time).toLocaleString() : ""}` +
    (deployed === c.sha ? "\nRunning: deployed from this commit" : "") + (p.head.sha === c.sha ? "\nYour main worktree's HEAD" : "") + "\nClick to copy its hash";
  const pad = ` style="padding-left:${indent}px"`;
  const row = (r) => r.wip
    ? `<div class="br-row wip"${pad} data-sha="wip" data-tip="Uncommitted\n${escHtml(r.wip.join("\n"))}"><span class="br-msg">${plural(r.wip.length, "uncommitted file")}</span></div>`
    : r.fold
      ? `<div class="br-row fold"${pad} role="button" tabindex="0" data-fold="${escHtml(r.key)}" data-tip="${plural(r.fold.length, "commit")}\n${escHtml(r.fold.slice(0, 5).map((c) => c.subject).join("\n"))}${r.fold.length > 5 ? "\n…" : ""}"><span class="br-msg">${plural(r.fold.length, "more commit")}</span><span class="br-unfold">Show</span></div>`
      : `<div class="br-row"${pad} data-sha="${r.sha}" data-tip="${escHtml(tipOf(r))}"><span class="br-refs">${labels.get(r.sha) || ""}</span><span class="br-msg">${escHtml(r.subject)}</span><span class="br-age">${r.time ? ageText(Date.now() - r.time) : ""}</span></div>`;
  // its worst state, as the cell's tint: something red, something to look at, or nothing to do
  const level = states.some(([, l]) => l === "bad") ? "bad" : states.length ? "warn" : "ok";
  return `<article class="br-cell" data-level="${level}" data-repo="${escHtml(name)}">
    <header class="br-head">
      <span class="br-name">${escHtml(name)}</span>
      <span class="br-src" data-from="${from}">${SOURCE[from]}</span>
      <span class="br-comps" data-tip="${escHtml("Run from it\n" + comps.join(", "))}">${escHtml(comps.length > 2 ? comps.slice(0, 2).join(", ") + " +" + (comps.length - 2) : comps.join(", "))}</span>
      ${fresh}
      <nav class="br-links"><a href="#stack" data-select="${escHtml(comps[0])}">Stack</a><a href="#logs" data-logs="${escHtml(comps.join(","))}">Logs</a></nav>
    </header>
    <div class="br-where">${p.where || ""}</div>
    <div class="br-states">${states.length ? states.map(([t, lvl], i) => `<span class="br-state ${lvl}" data-i="${i}">${t}</span>`).join("")
      : `<span class="br-state ok">Up to date</span>`}</div>
    ${acts.length ? `<div class="br-acts">${acts.map(([verb, label, note, , why, i], k) =>
      // one look per role: the most pressing action first and blue, one that replaces origin's commits red
      `<button type="button" class="${/force-with-lease/.test(note) ? "warn" : k === 0 && !why ? "primary" : ""}" data-verb="${verb}" data-i="${i}" data-post="${brPath(verb, comps[0])}"${why ? ` aria-disabled="true" data-why="${escHtml(why)}"` : ""}>${escHtml(label)}</button>`).join("")}
      <span class="br-note" aria-live="polite"></span></div>` : ""}
    ${p.truncated ? `<p class="br-cut">The ${p.commits.length} newest commits only</p>` : ""}
    <div class="br-graph">${svg}${rows.map(row).join("")}</div>
  </article>`;
}

function brPath(verb, comp) {
  return `/api/${verb}/${encodeURIComponent(comp)}` + (["rebase", "checkout", "push", "fast-forward"].includes(verb) ? previewQuery() : "");
}

function renderBranches() {
  if (tabOf() !== "branches") return;
  const ps = (branches.data?.projects || []).slice();
  const score = (p) => p.error ? 99 : brStates(p).reduce((n, [, l]) => n + (l === "bad" ? 10 : 1), 0);
  ps.sort((a, b) => score(b) - score(a) || a.repo.localeCompare(b.repo));
  const needing = ps.filter((p) => score(p) > 0).length;
  // the tab's count: the headline's - red when one of them is, amber else
  const bad = ps.some((p) => p.error || brStates(p).some(([, l]) => l === "bad")), badge = document.getElementById("branches-badge");
  badge.hidden = !needing; badge.textContent = needing; badge.classList.toggle("warn", !bad);
  // reading: said in the headline, what's shown dimmed - it may be another preset's - or ghosts at first
  // - a refresh of what's shown (every minute, on focus) stays quiet: a small spinner by the fetch time
  const grid = document.getElementById("branches-grid");
  const loading = branches.loading && (!branches.data || branches.of !== shownPreset()) ? branches.loading : false;
  grid.classList.toggle("loading", Boolean(loading)); grid.setAttribute("aria-busy", String(Boolean(branches.loading)));
  document.getElementById("branches-view").dataset.refreshing = String(Boolean(branches.loading) && !loading);
  document.getElementById("branches-headline").innerHTML = loading
    ? `<span class="br-spin" aria-hidden="true"></span>Reading the repos${typeof loading === "string" ? " of " + escHtml(loading) : ""}…`
    : !ps.length ? "No repo to show"
    : needing ? `<b class="${bad ? "bad" : "warn"}">${needing} of ${plural(ps.length, "repo")}</b> need${needing === 1 ? "s" : ""} attention` : `All ${plural(ps.length, "repo")} up to date`;
  if (loading && !branches.data) { grid.innerHTML = `<div class="br-ghost"></div>`.repeat(6); return; }
  const newest = Math.max(0, ...ps.map((p) => p["fetched-at"] || 0));
  document.getElementById("branches-fetched").textContent = branches.fetching ? "" : newest ? "Fetched " + agoText(Date.now() - newest) : "Never fetched";
  const fetchBtn = document.getElementById("branches-fetch");
  fetchBtn.disabled = branches.fetching; fetchBtn.dataset.busy = String(branches.fetching);
  fetchBtn.textContent = branches.fetching ? "Fetching…" : "Fetch";
  grid.innerHTML = ps.length ? ps.map(brCell).join("")
    : branches.data ? `<p class="br-empty">${shownPreset() ? "This preset runs nothing from a repo." : "Nothing runs. Apply a preset, or preview one, to see its repos here."}</p>` : "";
  const unused = branches.data?.unused || [], u = document.getElementById("branches-unused");
  u.hidden = !unused.length;
  u.innerHTML = unused.length ? `<span class="br-unused-head"><b>${plural(unused.length, "worktree")}</b> no preset uses</span>` +
    `<span class="br-unused-list">${unused.map((w) => bn("wt", w.path, { shown: w.path.split("/").pop(), extra: `<small>${ageText(Date.now() - w.modified)}</small>` })).join("")}</span>` +
    `<button type="button" id="branches-prune" data-tip="Clean up\nRemoves them; one holding uncommitted files or commits on no branch is kept">Clean up</button>` : "";
  markBusy();
}

function initBranchesControls() {
  const legend = document.getElementById("branches-legend");
  const ring = (stroke, fill = "none", dash = "") =>
    `<svg width="14" height="14" aria-hidden="true"><circle cx="7" cy="7" r="${fill === "none" ? 5 : 3.5}" fill="${fill}" stroke="${stroke}" stroke-width="${stroke === "none" ? 0 : 2}" ${dash}/></svg>`;
  legend.innerHTML = [
    [bn("local", "main", { shown: "main", tip: "" }), "yours"], [bn("remote", "origin/main", { shown: "origin/main", tip: "" }), "origin's"],
    [bn("both", "main", { shown: "main", tip: "" }), "both, one commit"], [bn("local", "x", { extra: `<b class="ab">↑2 ↓1</b>`, tip: "" }), "ahead / behind origin"],
    [bn("wt", "worktree", { shown: "worktree", cls: "wt", tip: "" }), "zent's worktree, what runs"],
    [`<span class="bn head-tag">HEAD</span>${ring("#28b463")}`, "your main worktree's HEAD"], [ring("none", "#28b463"), "running"],
    [ring("#e8a200", "#fff", `stroke-dasharray="2 2"`), "uncommitted"]
  ].map(([m, t]) => `<span>${m}${t}</span>`).join("");
  document.getElementById("branches-fetch").onclick = fetchBranches;
  const view = document.getElementById("branches-view");
  const projectOf = (el) => branches.data?.projects.find((p) => p.repo === el.closest(".br-cell")?.dataset.repo);
  // a state or an action hovered (or focused): the commits it's about stay lit; an action says what it
  // would do - or why it can't - under the buttons
  const light = (e) => {
    const chip = e.target.closest?.(".br-state[data-i], .br-acts button[data-i]"), cell = e.target.closest?.(".br-cell");
    if (!chip || !cell || chip.contains(e.relatedTarget)) return;
    const [, , action, shas] = brStates(projectOf(cell))[+chip.dataset.i];
    cell.querySelectorAll(".br-row").forEach((r) => r.classList.toggle("dim", !shas.has(r.dataset.sha)));
    const note = cell.querySelector(".br-note");
    if (note && chip.tagName === "BUTTON" && action) {
      const why = chip.dataset.why;
      note.innerHTML = why ? `Can't: ${escHtml(why)}` : action[2];
      note.dataset.bad = String(Boolean(why) || /conflicts on|replaces/.test(action[2]));
    }
  };
  const unlight = (e) => {
    const chip = e.target.closest?.(".br-state[data-i], .br-acts button[data-i]"), cell = e.target.closest?.(".br-cell");
    if (!chip || !cell || chip.contains(e.relatedTarget)) return;
    cell.querySelectorAll(".br-row.dim").forEach((r) => r.classList.remove("dim"));
    const note = cell.querySelector(".br-note");
    if (note) note.textContent = "";
  };
  view.addEventListener("mouseover", light); view.addEventListener("focusin", light);
  view.addEventListener("mouseout", unlight); view.addEventListener("focusout", unlight);
  view.addEventListener("keydown", (e) => {
    const fold = e.target.closest(".br-row.fold");
    if (fold && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); fold.click(); }
  });
  view.addEventListener("click", async (e) => {
    const fold = e.target.closest(".br-row.fold"), btn = e.target.closest(".br-acts button"), link = e.target.closest(".br-links a");
    const commit = e.target.closest(".br-row[data-sha]:not(.wip)");
    if (fold) { branches.unfolded.add(fold.dataset.fold); renderBranches(); return; }
    if (commit && !e.target.closest(".bn")) {
      const sha = commit.dataset.sha;
      // said in place, where you look: the row's age reads "copied" for a moment
      try { await navigator.clipboard.writeText(sha); commit.classList.add("copied"); setTimeout(() => commit.classList.remove("copied"), 1200); }
      catch { /* no clipboard (not a secure context): nothing copied, nothing said */ }
      return;
    }
    if (link) {
      e.preventDefault();
      if (link.dataset.select) { location.hash = "#stack"; setSelected(link.dataset.select); }
      else { setLogSelection(link.dataset.logs.split(",")); location.hash = "#logs"; }
      return;
    }
    if (e.target.id === "branches-prune") {
      if (!(await askConfirm({ name: "worktrees", action: "clean up", anchor: e.target,
                               question: "Remove the worktrees no preset uses? Those holding uncommitted files or commits on no branch are kept." }))) return;
      const res = await fetch("/api/worktrees/prune", { method: "POST", headers: { "X-Zent-Token": csrfToken() } });
      const r = await res.json().catch(() => ({}));
      if (!res.ok) toast("failed", "not cleaned up", r.error);
      else toast("up", "removed " + plural(r.removed.length, "worktree"), r.kept.map((k) => k.path.split("/").pop() + ": " + k.why).join("; "));
      loadBranches();
      return;
    }
    if (!btn || btn.getAttribute("aria-disabled") === "true") return;
    const p = projectOf(btn), [, , action] = brStates(p)[+btn.dataset.i], [verb, label, note, confirm] = action;
    const comp = p.components[0].name;
    if (confirm && !(await askConfirm({ name: p.repo, action: label.toLowerCase(), anchor: btn, question: `${label}: ${textOf(note)}?` }))) return;
    gitPost(verb, comp, btn.dataset.post);
  });
}

/* A git write from the Branches tab: queued like the board's (pending, gitJobs), its outcome a toast -
   with an Undo when the job left one (state :command-undo). */
async function gitPost(verb, name, path) {
  pending.set(path, Infinity); markBusy();
  let reply;
  try {
    const res = await fetch(path, { method: "POST", headers: { "X-Zent-Token": csrfToken() } });
    reply = await res.json().catch(() => ({}));
  } catch { reply = { error: "the daemon didn't answer" }; }
  if (Number.isInteger(reply.id)) {
    pending.set(path, reply.id);
    // a reload or a sync isn't a git job: its outcome shows on the board, no message to toast
    if (!["reload", "sync"].includes(verb)) gitJobs.set(reply.id, [gitDone[verb] || verb, name]);
  }
  else { pending.delete(path); toast("failed", "not " + (gitDone[verb] || verb) + ": " + name, reply.error, name); }
  markBusy();
}

/* The token is baked into the page at load (zent.ui.server, a <meta> tag -
   no separate fetch needed) and attached to every htmx-issued write here,
   in one place instead of a per-call header. authorized-write? server-side
   is the actual CSRF defense; this is just how the token gets there. */
function csrfToken() { return document.querySelector('meta[name="csrf-token"]')?.content || ""; }
document.body.addEventListener("htmx:configRequest", (e) => { e.detail.headers["X-Zent-Token"] = csrfToken(); });

/* Asks before a write, in a bubble under `anchor` (the button that asked; none: top centre)
   rather than window.confirm. Resolves true on its action, false on Cancel - the focused one,
   so Enter can't fire it - Esc or a click outside. */
function askConfirm({ name, question, action, anchor }) {
  const d = document.getElementById("confirm");
  document.getElementById("confirm-name").textContent = name;
  document.getElementById("confirm-msg").textContent = question;
  document.getElementById("confirm-go").textContent = action[0].toUpperCase() + action.slice(1);
  const W = 300, r = anchor?.getBoundingClientRect();
  const x = r ? r.left + r.width / 2 : innerWidth / 2;
  const left = Math.min(Math.max(8, x - 24), innerWidth - W - 8);
  d.style.left = left + "px";
  d.style.top = (r ? r.bottom + 10 : 80) + "px";
  d.style.setProperty("--tail", (r ? x - left : W / 2) + "px");
  return new Promise((resolve) => {
    let done = false;
    // closed after its fade-out; a timer, not animationend: reduced motion has no animation
    const finish = (ok) => {
      if (done) return;
      done = true;
      d.classList.add("closing");
      setTimeout(() => { d.classList.remove("closing"); d.close(); }, 160);
      resolve(ok);
    };
    d.oncancel = (e) => { e.preventDefault(); finish(false); };
    d.onclick = (e) => { if (e.target === d) finish(false); };
    d.querySelector("form").onsubmit = (e) => { e.preventDefault(); finish(e.submitter?.value === "ok"); };
    d.showModal();
  });
}

/* Every hx-confirm write (zent.ui.render: a one-shot's :confirm, a git action) asks through askConfirm. */
document.body.addEventListener("htmx:confirm", (e) => {
  if (!e.detail.question) return;
  e.preventDefault();
  const [, , verb, rest = ""] = e.detail.path.split("/");
  const name = decodeURIComponent(rest.split("?")[0]), c = view.components[name] || {};
  const action = { reload: c.kind === "one-shot" ? "run" : "reload", checkout: "check out" }[verb] || verb;
  // the board redraws on every push: by the time it's confirmed, its button may be a new one,
  // and htmx silently drops a request from a detached element
  askConfirm({ name, question: e.detail.question, action, anchor: e.detail.elt })
    .then((ok) => {
      if (!ok) return;
      if (e.detail.elt.isConnected) e.detail.issueRequest(true);
      else htmx.ajax(e.detail.verb, e.detail.path, { source: document.body, target: document.body, swap: "none" });
    });
});

/* Writes are queued: a POST answers {id} at once, the daemon handles the
   command later, and view.done lists that id once it has - in no set order: a
   quick stop can finish before a long apply queued before it. Until then
   every button posting the same path shows a spinner - tracked here by path,
   since each push re-renders the buttons themselves. Infinity while the POST
   is still in flight (no id yet). */
const pending = new Map();
// a git write's past participle, by its /api/<verb>/<component> path
const gitDone = { rebase: "rebased", checkout: "checked out", push: "pushed", "fast-forward": "fast-forwarded", sync: "synced", reload: "reloaded", undo: "undone" };
// queued git writes, id -> [done, component]: once handled, say what happened, the undo command included
const gitJobs = new Map();

function markBusy() {
  const done = new Set(view.done || []);
  for (const [path, id] of pending) if (done.has(id)) pending.delete(path);
  for (const [id, [did, name]] of gitJobs) {
    if (!done.has(id)) continue;
    gitJobs.delete(id);
    const message = view["command-messages"]?.[id];
    if (tabOf() === "branches") loadBranches();
    if (message) {
      refreshPreview();
      // a job that left an undo (state :command-undo): offered on the toast while it shows
      const undo = view["command-undo"]?.[id] && { label: "Undo", run: () => gitPost("undo", name, "/api/undo/" + id) };
      toast("up", did + " " + name, message, name, undo);
      continue;
    }
    // neither means no job ran it: a shutdown took it, or a daemon older than :job
    toast("failed", "not " + did + ": " + name,
          view["command-errors"]?.[id] || "the daemon didn't run it - restart it and try again", name);
  }
  document.querySelectorAll("[hx-post], [data-post]").forEach((b) => {
    b.dataset.busy = String(pending.has(b.getAttribute("hx-post") || b.dataset.post));
  });
}

document.body.addEventListener("htmx:beforeRequest", (e) => {
  const path = e.detail.elt?.getAttribute?.("hx-post");
  if (path) { pending.set(path, Infinity); markBusy(); }
});

document.body.addEventListener("htmx:afterRequest", (e) => {
  // a confirmed write whose button was redrawn meanwhile went from the body (askConfirm's caller)
  const path = e.detail.elt?.getAttribute?.("hx-post") || (e.detail.elt === document.body && e.detail.requestConfig?.path);
  if (!path) return;
  let id, reply;
  try { reply = JSON.parse(e.detail.xhr.responseText); id = reply.id; } catch { /* not a queued write */ }
  const [, verb, name] = path.match(/^\/api\/(rebase|checkout)\/([^?]*)/) || [];
  if (verb && reply) {
    if (Number.isInteger(id)) gitJobs.set(id, [gitDone[verb], name]);
    else toast("failed", "not " + gitDone[verb] + ": " + name, reply.error, name);
  }
  if (e.detail.successful && Number.isInteger(id)) pending.set(path, id);
  else pending.delete(path);
  markBusy();
});

/* A failed write flashes its button red - every write is a plain hx-post
   (component-card/preset-picker, zent.ui.render), so this is the one place. */
document.body.addEventListener("htmx:responseError", (e) => {
  const btn = e.detail.elt;
  if (!(btn instanceof HTMLElement) || !btn.classList.contains("act")) return;
  btn.dataset.failed = "true";
  btn.title = "HTTP " + e.detail.xhr.status;
  setTimeout(() => { btn.dataset.failed = "false"; }, 3000);
});

/* Copies the absolute path, not the ~-shortened one on screen: it's what
   you want to paste into anything, shell or not. localhost counts as a
   secure context, so navigator.clipboard is available here. */
async function copyCd(dir, btn) {
  const shown = btn.textContent;
  try {
    await navigator.clipboard.writeText("cd " + dir);
    btn.textContent = "copied";
  } catch {
    btn.textContent = "couldn't copy";
    btn.dataset.failed = "true";
  }
  setTimeout(() => { btn.textContent = shown; btn.dataset.failed = "false"; }, 1200);
}

/* ------------------------------------------------------------------------ */
/* Resizing the rail, and panning/zooming the fabric. Both persist, because
   a pane width and a zoom level are opinions about your screen, not about
   the run - and re-setting them after every restart would be a chore.      */
/* ------------------------------------------------------------------------ */

const RAIL_MIN = 220, RAIL_MAX = 720, RAIL_DEFAULT = 330;
const ZOOM_MIN = 0.4, ZOOM_MAX = 1.6;
let zoom = 1;

function setRailCollapsed(collapsed) {
  document.getElementById("shell").dataset.railCollapsed = collapsed;
  const toggle = document.getElementById("rail-toggle");
  toggle.setAttribute("aria-pressed", String(!collapsed));
  toggle.title = collapsed ? "Show the side panel" : "Hide the side panel";
  localStorage.setItem("zent.railCollapsed", collapsed);
  drawWarp();                       // the loom changes width - again once the panel has arrived (below)
}
// the light on the metal follows the pointer across the plate under it (index.css .sheen/.rim)
document.getElementById("levels").addEventListener("pointermove", (e) => {
  const plate = e.target.closest(".node");
  if (!plate) return;
  const r = plate.getBoundingClientRect();
  plate.style.setProperty("--mx", ((e.clientX - r.left) / r.width * 100).toFixed(1) + "%");
  plate.style.setProperty("--my", ((e.clientY - r.top) / r.height * 100).toFixed(1) + "%");
});
// the board's width eases with the panel (index.css): the wires follow it once it's settled
document.getElementById("shell").addEventListener("transitionend", (e) => {
  if (e.target.id === "shell" && e.propertyName === "grid-template-columns") drawWarp();
});

function setShowStopped(shown) {
  showStopped = shown;
  document.getElementById("show-stopped").checked = shown;
  localStorage.setItem("zent.showStopped", shown);
  render();
}

function setRail(px) {
  const w = Math.min(RAIL_MAX, Math.max(RAIL_MIN, px));
  document.documentElement.style.setProperty("--rail", w + "px");
  localStorage.setItem("zent.rail", w);
  drawWarp();                       // the loom just changed width
}

function setZoom(z) {
  zoom = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, z));
  localStorage.setItem("zent.zoom", zoom);
  // drawWarp applies it, times what fits the widest row to the board
  drawWarp();
}

function initViewControls() {
  const grip = document.getElementById("grip");
  const loom = document.getElementById("loom");

  document.getElementById("rail-toggle").onclick = () =>
    setRailCollapsed(document.getElementById("shell").dataset.railCollapsed !== "true");

  document.getElementById("show-stopped").checked = showStopped;
  document.getElementById("show-stopped").onchange = (e) => setShowStopped(e.target.checked);

  // delegated: #preset-picker's own content is replaced wholesale on every
  // push (render()'s outerHTML assignment), so a listener on it directly
  // wouldn't survive past the first snapshot.
  document.getElementById("run-menu").addEventListener("click", (e) => {
    const name = e.target.closest(".preset-name")?.dataset.preset;
    if (name) previewPreset(name);
  });

  grip.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    grip.setPointerCapture(e.pointerId);
    grip.dataset.dragging = "true";
    document.body.dataset.resizing = "true";
  });
  grip.addEventListener("pointermove", (e) => {
    if (grip.dataset.dragging === "true") setRail(window.innerWidth - e.clientX);
  });
  const endResize = (e) => {
    if (grip.dataset.dragging !== "true") return;
    grip.releasePointerCapture(e.pointerId);
    grip.dataset.dragging = "false";
    document.body.dataset.resizing = "false";
  };
  grip.addEventListener("pointerup", endResize);
  grip.addEventListener("pointercancel", endResize);
  grip.addEventListener("dblclick", () => setRail(RAIL_DEFAULT));

  // pan: drag anywhere that isn't a card or an interactive control (a
  // button/link - the picker's preset buttons live outside any .node), so
  // dragging never competes with selecting a card or hitting a button
  let from = null;
  loom.addEventListener("pointerdown", (e) => {
    if (e.button !== 0 || e.target.closest(".node, button, a")) return;
    from = { x: e.clientX, y: e.clientY, left: loom.scrollLeft, top: loom.scrollTop };
    loom.setPointerCapture(e.pointerId);
    loom.dataset.panning = "true";
  });
  loom.addEventListener("pointermove", (e) => {
    if (!from) return;
    loom.scrollLeft = from.left - (e.clientX - from.x);
    loom.scrollTop = from.top - (e.clientY - from.y);
  });
  const endPan = (e) => {
    if (!from) return;
    // a press that didn't drag is a click on the empty fabric: deselect
    const click = e.type === "pointerup" && Math.hypot(e.clientX - from.x, e.clientY - from.y) < 4;
    from = null;
    loom.releasePointerCapture(e.pointerId);
    loom.dataset.panning = "false";
    if (click && selected) setSelected(null);
  };
  loom.addEventListener("pointerup", endPan);
  loom.addEventListener("pointercancel", endPan);

  // ctrl/cmd + wheel, i.e. the trackpad pinch - plain wheel stays scrolling
  loom.addEventListener("wheel", (e) => {
    if (!e.ctrlKey && !e.metaKey) return;
    e.preventDefault();
    setZoom(zoom * (e.deltaY < 0 ? 1.08 : 1 / 1.08));
  }, { passive: false });

  setRail(Number(localStorage.getItem("zent.rail")) || RAIL_DEFAULT);
  setRailCollapsed(localStorage.getItem("zent.railCollapsed") === "true");
  setZoom(Number(localStorage.getItem("zent.zoom")) || 1);
}

let reconnecting = false;

function connect() {
  const ws = new WebSocket(`ws://${location.host}/ws/view`);
  const link = document.getElementById("link");
  ws.onopen = async () => {
    link.textContent = "live";
    link.dataset.live = "true";
    // after a reconnect the daemon may be a new process: new write token,
    // command ids from 0 - refresh one, forget pending spinners of the other
    if (reconnecting) {
      pending.clear();
      gitJobs.clear();
      try {
        const { token } = await (await fetch("/api/token")).json();
        document.querySelector('meta[name="csrf-token"]').content = token;
      } catch { /* the next reconnect retries */ }
    }
    reconnecting = true;
  };
  ws.onclose = () => {
    link.textContent = "reconnecting";
    link.dataset.live = "false";
    setTimeout(connect, 1000);
  };
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.type === "snapshot") {
      view = msg.data;
      // rendered server-side (zent.ui.render/run-summary, preset-picker) -
      // pushed alongside the raw component data, not nested in it, since
      // they're not per-component.
      view.summaryHtml = msg["summary-html"];
      view.presetsHtml = msg["presets-html"];
      render();
    }
  };
}

function initLogControls() {
  addEventListener("hashchange", switchTab);
  const times = document.getElementById("log-times");
  times.checked = localStorage.getItem("zent.logTimes") === "true";
  const applyTimes = () => {
    logsPane.term.dataset.times = String(times.checked);
    localStorage.setItem("zent.logTimes", times.checked);
  };
  times.onchange = applyTimes;
  applyTimes();

  let typing = null;
  document.getElementById("log-term").addEventListener("input", (e) => {
    clearTimeout(typing);
    typing = setTimeout(() => writeLogFilter({ term: e.target.value }), 200);
  });
  document.querySelectorAll(".levels-filter button")
    .forEach((b) => { b.onclick = () => writeLogFilter({ level: b.dataset.level }); });
  logsPane.onCounts = ({ warn, error }) => {
    document.getElementById("n-warn").textContent = warn;
    document.getElementById("n-error").textContent = error;
  };
  const copy = document.getElementById("log-copy");
  copy.onclick = async () => {
    const lines = logsPane.visibleText();
    try { await navigator.clipboard.writeText(lines.join("\n")); copy.textContent = "copied " + lines.length; }
    catch { copy.textContent = "couldn't copy"; }
    setTimeout(() => { copy.textContent = "copy"; }, 1500);
  };
  document.getElementById("log-clear").onclick = () => logsPane.clear();
  // delegated: #detail is replaced on every refresh. Its "Logs" opens the
  // tab on that component alone - one click on its pill brings the rest back
  document.querySelector(".rail").addEventListener("click", (e) => {
    const name = e.target.closest(".open-logs")?.dataset.name;
    if (!name) return;
    setLogSelection([name]);
    location.hash = "#logs";
  });
}

// the toolbar: Preview | Running, and the presets under the preset's name
document.getElementById("mode").onclick = (e) => {
  const mode = e.target.closest("button")?.dataset.mode;
  if (mode === "run" && previewing) exitPreview();
  if (mode === "preview" && !previewing) previewPreset(lastPreviewed || view.preset);
};
{
  const label = document.getElementById("run-label"), menu = document.getElementById("run-menu");
  const open = (on) => { menu.hidden = !on; label.setAttribute("aria-expanded", String(on)); };
  label.onclick = () => open(menu.hidden);
  // a pick, a click elsewhere or Escape closes it - the checkbox in it doesn't
  document.addEventListener("click", (e) => {
    if (menu.hidden || label.contains(e.target)) return;
    if (!menu.contains(e.target) || e.target.closest(".preset-name, .act")) open(false);
  });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") open(false); });
}

/* Explanations on hover: in the graph, the legend and the toolbar, a title is shown as one tip that
   follows the pointer, its first line the heading - the browser's own comes late and can't be read
   at a glance. Each push re-renders the titles, so they're taken over as the pointer meets them. */
const tipEl = document.getElementById("tip");
document.addEventListener("mousemove", (e) => {
  const t = e.target.closest?.(":is(.loom, .legend, .masthead, .branches-view) :is([title], [data-tip], [data-tip-key])");
  if (!t) { tipEl.classList.remove("on"); return; }
  if (t.title) { t.dataset.tip = t.title; t.removeAttribute("title"); }
  if (t.dataset.tipKey) t.dataset.tip = tip(t.dataset.tipKey);
  const [head, ...rest] = t.dataset.tip.split("\n");
  tipEl.replaceChildren(Object.assign(document.createElement("b"), { textContent: head }),
                        Object.assign(document.createElement("span"), { textContent: rest.join("\n") }));
  tipEl.style.left = Math.min(e.clientX + 14, innerWidth - 300) + "px";
  tipEl.style.top = Math.min(e.clientY + 18, innerHeight - tipEl.offsetHeight - 8) + "px";
  tipEl.classList.add("on");
});

connect();
initViewControls();
initLogControls();
initChecksControls();
initBranchesControls();
// the repos move under the tab: re-read on coming back to the window, and every minute while it shows
addEventListener("focus", () => { if (tabOf() === "branches") loadBranches(); });
setInterval(() => { if (tabOf() === "branches" && !document.hidden) loadBranches(); }, 60e3);
showTab();
render();
addEventListener("resize", () => render());
