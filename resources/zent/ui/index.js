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
  pending: "queued", deploying: "starting", up: "up", done: "done", idle: "idle", stopping: "stopping",
  failed: "failed", external: "external", off: "off", down: "stopped", ready: "ready", unready: "unready",
  skipped: "skipped"
};
const WOVEN = new Set(["up", "done", "external"]);
// what isn't settled yet: down (red) or on its way (amber) - the colour of a wire leaving it
const UNSETTLED = { failed: "bad", unready: "bad", skipped: "bad", starting: "wait", queued: "wait" };

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
    const s = STATE_CLASS[c.status] || "queued";
    if (!(name in seenStatus)) { if (primed) arrivedAt[name] = now; }
    else if (seenStatus[name] !== s) {
      changedAt[name] = now;
      if (s === "failed") toast("failed", name + " failed", c.error, name);
    }
    seenStatus[name] = s;
  }
  // a run settling: from starting or stopping to ready
  const health = healthOf(components);
  if (wasBusy && health === "ready" && view.preset) {
    const n = Object.values(components).filter((c) => c.status === "up").length;
    toast("up", view.preset + " is up", n + (n === 1 ? " component" : " components") + " running");
  }
  wasBusy = health === "starting" || health === "stopping";
}

/* A run as a whole: failed while one is failed or unready, else starting or
   stopping while one is on its way, else ready (up, done, external, an
   on-demand one-shot ready, a kept-warm one idle). */
function healthOf(components) {
  const s = new Set(Object.values(components).map((c) => c.status));
  return s.has("failed") || s.has("unready") ? "failed"
    : s.has("pending") || s.has("deploying") ? "starting"
    : s.has("stopping") ? "stopping" : "ready";
}

/* A toast: what just happened, for a few seconds - a failure longer, and a
   click on it selects the component. */
function toast(kind, title, detail, name) {
  const el = document.createElement("div");
  el.className = "toast";
  el.dataset.kind = kind;
  el.innerHTML = "<b>" + escHtml(title) + "</b>" +
    (detail ? "<span>" + escHtml(String(detail).split("\n")[0]) + "</span>" : "");
  const leave = () => { el.classList.add("leaving"); setTimeout(() => el.remove(), 240); };
  el.onclick = () => { if (name && view.components[name]) { location.hash = "#stack"; setSelected(name); } leave(); };
  document.getElementById("toasts").appendChild(el);
  setTimeout(leave, kind === "failed" ? 9000 : 5000);
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
  markBusy();
}

/* Fetches and shows a preset's would-be components/graph without starting
   anything (zent.ui.render/component-card with :preview? true drops the
   reload/stop buttons server-side - nothing here is deployed). Toggles off
   on a second click of the same name, same as a normal expand/collapse. */
async function previewPreset(name) {
  if (previewing === name) return exitPreview();
  try {
    const res = await fetch("/api/preview/" + encodeURIComponent(name));
    if (!res.ok) return;
    setPreview(name, await res.json());
  } catch {
    /* the click just does nothing - no partial/broken preview state to undo */
  }
}

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
    if (from === "clone" && want && g.branch && g.branch !== want) {
      blocks.push(`${at} runs your clone, on <code>${escHtml(g.branch)}</code> - the preset wants <code>${escHtml(want)}</code>` +
        (g.dirty ? ` <span class="why">commit or stash its ${g.dirty} uncommitted file(s) first</span>`
                 : act("checkout", n, "Check out", `Check out ${want} in your clone? Every component running it moves with it.`)));
    }
    if ((from === "origin" || from === "local") && (g.dirty || g.unreachable)) {
      blocks.push(`${at}'s worktree holds ${g.dirty ? g.dirty + " uncommitted file(s)" : ""}${g.dirty && g.unreachable ? " and " : ""}` +
        `${g.unreachable ? g.unreachable + " commit(s) on no branch" : ""}: the apply refuses to sync it <span class="why">keep or drop them first</span>`);
    }
    if (want && g.behind > 0) {
      looks.push(`${at} is ${g.behind} commit${g.behind > 1 ? "s" : ""} behind <code>${escHtml(g.base)}</code>` +
        (g.dirty ? "" : act("rebase", n, from === "origin" ? "Test on " + escHtml(g.base) : "Rebase")));
    }
    if (g.pull > 0) looks.push(`${at}: origin has ${g.pull} new commit(s) - the apply resets its worktree to them`);
    if (!want && g.dirty) looks.push(`${at} runs your clone as it is, with ${g.dirty} uncommitted file(s)`);
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
        "<code>zent &lt;preset&gt;</code> / <code>zent watch &lt;preset&gt;</code>. " +
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
    .filter(([name, c]) => (c.status !== "down" && c.status !== "off") || (all && members.has(name))));
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
  const status = STATE_CLASS[c.status] || "queued";
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
    const res = await fetch("/api/detail/" + name + (previewing ? "?preset=" + encodeURIComponent(previewing) : ""));
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

const TABS = ["stack", "logs", "checks"];
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
  else drawWarp();                                // the loom was hidden: positions are stale
}

/* ------------------------------------------------------------------------ */
/* Checks: the active preset's clojure.test checks (zent.checks), shown like
   an IDE's test runner. GET /api/checks gives each check's results; a run
   (POST /api/checks/run) streams its output like a log (/api/logs/stream),
   whose "#zent/check {...}" lines move the tree live - the failures' detail
   comes with the result, fetched once the run is over.                     */
/* ------------------------------------------------------------------------ */

const CHECK_GLYPH = { pass: "✓", fail: "✗", error: "!", unknown: "?", run: "…" };
const checks = { data: null, preset: undefined, sel: {}, runIdx: {}, live: {}, streams: {} };

// a docstring's lines as written (indented continuation lines), for the detail; folded to one for the tree
const docLines = (d) => String(d).split("\n").map((l) => l.trim()).join("\n");
const docInline = (d) => docLines(d).replace(/\n/g, " ");
const hms = (ms) => new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
// a test's assertions in the tree, spelled out: "1 of 94 failed", "114 passed"
const testCounts = (t) => t.status === "run" ? ""
  : t.status === "error" ? `threw after ${t.assertions} assertion(s)`
  : t.failures.length ? `${t.failures.length} of ${t.assertions} failed` : `${t.assertions} passed`;

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

async function runCheck(c, only) {
  // a check that writes (a scenario) runs only when asked for, like a :confirm one-shot
  if (c.writes && !confirm(`${c.name} writes to the running stack (a scenario). Run it?`)) return;
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
// the check holding the selection, else the first
const selectedCheck = (list) => list.find((x) => checks.selCheck === x.name) || list[0];

function renderChecks() {
  const tab = document.getElementById("checks-tab"), badge = document.getElementById("checks-badge");
  const list = checkList();
  tab.hidden = !list.length;
  const bad = list.reduce((n, c) => n + ((c.current || c.runs.at(-1))?.tests || []).filter((t) => t.status !== "pass").length, 0);
  const running = list.some((c) => checks.live[c.name]);
  const anyResult = list.some((c) => c.runs.length);
  badge.hidden = !anyResult && !running;
  badge.textContent = running ? "…" : bad || "✓";
  badge.className = "tab-badge" + (bad || running ? "" : " ok");
  if (document.body.dataset.tab !== "checks") return;

  const tree = document.getElementById("checks-tree"), detail = document.getElementById("checks-detail");
  const history = document.getElementById("checks-history");
  if (!list.length) {
    tree.innerHTML = `<p class="checks-empty">${checks.data?.preset ? "No checks for this preset - checks/&lt;preset&gt;/ in the catalog repo." : "No preset running."}</p>`;
    detail.innerHTML = history.innerHTML = "";
    return;
  }
  const onlyFailed = document.getElementById("checks-only-failed").checked;
  document.getElementById("checks-run-all").disabled = running;
  document.getElementById("checks-run-failed").disabled = running;

  tree.innerHTML = list.map((c) => {
    const r = shownCheckRun(c);
    const st = r?.live ? "run" : r?.status || "unknown";
    const meta = r?.live ? `running ${r.tests.filter((t) => t.status !== "run").length} test(s) done`
      : !r ? "never run"
      : `${checks.runIdx[c.name] !== undefined ? "run" : "full run"} ${hms(r["started-at"])}`
        + (r.only ? " · only " + r.only.join(", ") : "")
        + (r.overlaid?.length ? " · then " + r.overlaid.map((o) => `${o.only.join(", ")} at ${hms(o["started-at"])}`).join(", ") : "");
    const sel = checks.sel[c.name];
    return `<div class="chk" data-check="${escHtml(c.name)}">
      <div class="chk-head"><span class="gl st-${st}">${CHECK_GLYPH[st]}</span><span class="name">${escHtml(c.name)}</span>
        <span class="c quiet">${r && !r.live ? `${r.counts.tests} test(s) · ${r.counts.assertions} assertions · ${((r["duration-ms"] || 0) / 1000).toFixed(1)} s` : ""}</span></div>
      <div class="chk-meta">${c.description ? `<span class="desc">${escHtml(c.description)}</span><br>` : ""}${meta}</div>
      ${c.problem ? `<div class="chk-problem">${escHtml(c.problem)}</div>` : ""}
      ${c.observes?.length || c.writes ? `<div class="chk-chips">observes ${(c.observes || []).map((o) => `<span>${escHtml(o)}</span>`).join("")}${c.writes ? `<span class="writes" title="a scenario: runs only when asked for, never with run all">writes</span>` : ""}</div>` : ""}
      <ul class="chk-tests">${(r?.tests || []).filter((t) => !onlyFailed || t.status !== "pass").map((t) => `
        <li data-test="${escHtml(t.name)}" data-status="${t.status}" aria-selected="${t.name === sel}">
          <span class="gl st-${t.status}">${CHECK_GLYPH[t.status]}</span><span class="n">${escHtml(t.name)}${t.kind ? `<span class="kind" data-kind="${escHtml(t.kind)}">${escHtml(t.kind)}</span>` : ""}${t.doc ? `<span class="doc">${escHtml(docInline(t.doc))}</span>` : ""}</span>
          <span class="c">${t.at ? `<span class="quiet">${hms(t.at)}</span> ` : ""}${testCounts(t)}</span></li>`).join("")}
      </ul></div>`;
  }).join("");

  // history and detail
  const c = selectedCheck(list);
  const r = shownCheckRun(c);
  history.innerHTML = c.runs.length ? `<h3>history · ${escHtml(c.name)}</h3><div class="chk-runs">${c.runs.map((x, i) =>
    `<button type="button" class="${x.status} ${x.only ? "partial" : ""}" data-i="${i}" aria-pressed="${checks.runIdx[c.name] === i}"
       title="${hms(x["started-at"])} · ${x.status} · ${x.counts.tests} test(s)${x.only ? " · only " + x.only.join(", ") : ""}">${CHECK_GLYPH[x.status]}</button>`).join("")}</div>
    ${checks.runIdx[c.name] !== undefined ? `<button type="button" class="open-logs" id="checks-latest">back to the latest results</button>` : ""}` : "";

  const t = r && (r.tests.find((x) => x.name === checks.sel[c.name]) || r.tests.find((x) => x.status !== "pass") || r.tests[0]);
  if (!r || !t) {
    detail.innerHTML = r?.error ? `<h3>the run couldn't judge</h3><div class="chk-error">${escHtml(r.error)}</div>` : "";
  } else {
    checks.sel[c.name] = t.name;
    // what changed since the full run before the one shown
    const shownAt = r["started-at"] ?? Infinity;
    const prev = r.live ? null : [...c.runs].reverse().find((x) => !x.only && x["started-at"] < (t.at ?? shownAt));
    const before = prev?.tests.find((x) => x.name === t.name);
    const since = !before ? "" : before.status === t.status ? `unchanged since ${hms(prev["started-at"])} (${CHECK_GLYPH[before.status]} ${before.status})`
      : t.status === "pass" ? `<span class="fixed">fixed</span> - ${before.status} at ${hms(prev["started-at"])}`
      : `<span class="new">newly ${t.status === "fail" ? "failing" : t.status}</span> - ${before.status} at ${hms(prev["started-at"])}`;
    const loc = t.failures[0]?.file ? ` · ${t.failures[0].file}:${t.failures[0].line}` : "";
    const cmd = `zent verify ${checks.data.preset} ${c.name} --only=${t.name}`;
    detail.innerHTML = `
      <div class="chk-detail-head"><span class="gl st-${t.status}">${CHECK_GLYPH[t.status]}</span>${escHtml(t.name)}</div>
      ${t.doc ? `<p class="chk-doc" data-status="${t.status}">${escHtml(docLines(t.doc))}</p>` : ""}
      <div class="chk-where">${t.status === "run" ? "running…" : `${t.failures.length ? t.failures.length + " failing of " : ""}${t.assertions} assertion(s)${escHtml(loc)}`}</div>
      ${t.notes?.length ? `<div class="chk-notes"><h3>what it judged</h3><ul>${t.notes.map((n) => `<li>${escHtml(n)}</li>`).join("")}</ul></div>` : ""}
      ${t.failures.map((f) => `<div class="chk-failure ${t.status === "error" ? "err" : ""}">
          <div class="msg">${f.context ? `<span class="quiet">${escHtml(f.context)}</span> ` : ""}${escHtml(f.message)}</div>
          <dl><dt>expected</dt><dd>${escHtml(f.expected)}</dd><dt>actual</dt><dd>${escHtml(f.actual)}</dd></dl></div>`).join("")}
      ${r.error ? `<div class="chk-error">${escHtml(r.error)}</div>` : ""}
      ${since ? `<div class="chk-since"><h3>since the previous full run</h3>${since}</div>` : ""}
      <div class="chk-cmd"><code>${escHtml(cmd)}</code>
        <button type="button" id="checks-run-one" ${running ? "disabled" : ""}>&#9654; this test</button>
        <button type="button" id="checks-copy">copy</button></div>`;
    document.getElementById("checks-run-one").onclick = () => runCheck(c, [t.name]);
    document.getElementById("checks-copy").onclick = () => navigator.clipboard?.writeText(cmd);
  }
}

function initChecksControls() {
  document.getElementById("checks-view").addEventListener("click", (e) => {
    const li = e.target.closest("li[data-test]"), box = e.target.closest(".chk");
    const run = e.target.closest(".chk-runs button");
    const current = selectedCheck(checkList());
    if (li && box) { checks.selCheck = box.dataset.check; checks.sel[box.dataset.check] = li.dataset.test; renderChecks(); }
    else if (run && current) { checks.runIdx[current.name] = +run.dataset.i; renderChecks(); }
    else if (e.target.id === "checks-latest" && current) { delete checks.runIdx[current.name]; renderChecks(); }
  });
  document.getElementById("checks-only-failed").onchange = renderChecks;
  // every check of the preset, one after the other would queue on the server: one run each, in parallel
  document.getElementById("checks-run-all").onclick = () => checkList().filter((c) => !c.writes).forEach((c) => runCheck(c, null));
  document.getElementById("checks-run-failed").onclick = () => checkList().forEach((c) => {
    const failing = (shownCheckRun(c)?.tests || []).filter((t) => t.status !== "pass").map((t) => t.name);
    if (failing.length) runCheck(c, failing);
  });
}

/* The token is baked into the page at load (zent.ui.server, a <meta> tag -
   no separate fetch needed) and attached to every htmx-issued write here,
   in one place instead of a per-call header. authorized-write? server-side
   is the actual CSRF defense; this is just how the token gets there. */
function csrfToken() { return document.querySelector('meta[name="csrf-token"]')?.content || ""; }
document.body.addEventListener("htmx:configRequest", (e) => { e.detail.headers["X-Zent-Token"] = csrfToken(); });

/* Writes are queued: a POST answers {id} at once, the daemon handles the
   command later, and view.done lists that id once it has - in no set order: a
   quick stop can finish before a long apply queued before it. Until then
   every button posting the same path shows a spinner - tracked here by path,
   since each push re-renders the buttons themselves. Infinity while the POST
   is still in flight (no id yet). */
const pending = new Map();
// a git write's past participle, by its /api/<verb>/<component> path
const gitDone = { rebase: "rebased", checkout: "checked out" };
// queued git writes, id -> [done, component]: once handled, say what happened, the undo command included
const gitJobs = new Map();

function markBusy() {
  const done = new Set(view.done || []);
  for (const [path, id] of pending) if (done.has(id)) pending.delete(path);
  for (const [id, [did, name]] of gitJobs) {
    if (!done.has(id)) continue;
    gitJobs.delete(id);
    const message = view["command-messages"]?.[id];
    if (message) { refreshPreview(); toast("up", did + " " + name, message, name); continue; }
    // neither means no job ran it: a shutdown took it, or a daemon older than :job
    toast("failed", "not " + did + ": " + name,
          view["command-errors"]?.[id] || "the daemon didn't run it - restart it and try again", name);
  }
  document.querySelectorAll("[hx-post]").forEach((b) => {
    b.dataset.busy = String(pending.has(b.getAttribute("hx-post")));
  });
}

document.body.addEventListener("htmx:beforeRequest", (e) => {
  const path = e.detail.elt?.getAttribute?.("hx-post");
  if (path) { pending.set(path, Infinity); markBusy(); }
});

document.body.addEventListener("htmx:afterRequest", (e) => {
  const path = e.detail.elt?.getAttribute?.("hx-post");
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
  const t = e.target.closest?.(":is(.loom, .legend, .masthead) :is([title], [data-tip], [data-tip-key])");
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
showTab();
render();
addEventListener("resize", () => render());
