"use strict";
/* Opsflow console. Plain JS, no build step. Talks only to ticket-service's /api. */

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = v => String(v ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const main = () => $("#main");

const state = {
  token: sessionStorage.getItem("opsflow.token"),
  actor: sessionStorage.getItem("opsflow.actor") || "",
  role: sessionStorage.getItem("opsflow.role") || "",
  timer: null,
};
const canWrite = () => state.role === "OPERATOR";

/* ---------- helpers ---------- */
let toastTimer;
function toast(msg, bad = false) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.toggle("bad", bad);
  t.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add("hidden"), 3800);
}

async function api(path, { method = "GET", body } = {}) {
  const headers = { Authorization: "Bearer " + state.token };
  if (state.actor) headers["X-Actor"] = state.actor;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let res;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw Object.assign(new Error("Opsflow is not reachable. Check that the stack is running."), { status: 0 });
  }
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!res.ok) {
    const err = new Error((data && data.message) || `Request failed (${res.status})`);
    err.status = res.status;
    err.code = data && data.error;
    err.fields = (data && data.fields) || {};
    if (res.status === 401 && path !== "/api/session") {
      signOut("Your token is no longer accepted. Sign in again.");
      err.sessionEnded = true;
    }
    throw err;
  }
  return data;
}

function ago(iso) {
  if (!iso) return "";
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 45) return "just now";
  if (s < 3600) return Math.round(s / 60) + " min ago";
  if (s < 86400) return Math.round(s / 3600) + " h ago";
  return Math.round(s / 86400) + " d ago";
}
const when = iso => iso ? new Date(iso).toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "";
function dur(a, b) {
  if (!a) return "";
  const s = Math.max(0, Math.round(((b ? new Date(b) : new Date()) - new Date(a)) / 1000));
  return s < 60 ? s + " s" : s < 3600 ? Math.floor(s / 60) + " min " + (s % 60) + " s" : (s / 3600).toFixed(1) + " h";
}
const label = s => String(s || "").replace(/_/g, " ").toLowerCase();
const badge = (s, text) => s ? `<span class="badge ${esc(s)}">${esc(text || label(s))}</span>` : `<span class="badge">–</span>`;
const prio = p => p ? `<span class="badge ${esc(p)}">${esc(p)}</span>` : `<span class="badge">untriaged</span>`;

function formData(form) {
  const d = {};
  new FormData(form).forEach((v, k) => { d[k] = typeof v === "string" ? v.trim() : v; });
  return d;
}
function clearErrors(form) {
  $$(".err", form).forEach(e => (e.textContent = ""));
  const a = $(".alert", form);
  if (a) { a.textContent = ""; a.classList.add("hidden"); }
}
function showErrors(form, e) {
  let placed = false;
  Object.entries(e.fields || {}).forEach(([k, v]) => {
    const el = $(`.err[data-for="${k}"]`, form);
    if (el) { el.textContent = v; placed = true; }
  });
  const a = $(".alert", form);
  if (a && (!placed || e.status !== 400)) { a.textContent = e.message; a.classList.remove("hidden"); }
  else if (!a && !placed) toast(e.message, true);
}
async function submitting(form, fn) {
  const btn = $("button[type=submit]", form);
  clearErrors(form);
  if (btn) btn.disabled = true;
  try { return await fn(); }
  catch (e) { if (!e.sessionEnded) showErrors(form, e); return undefined; }
  finally { if (btn) btn.disabled = false; }
}

let lastFocus = null;
function openModal(html, { narrow = false } = {}) {
  lastFocus = document.activeElement;
  $("#modal-root").innerHTML = `<div class="backdrop"><div class="modal ${narrow ? "narrow" : ""}" role="dialog" aria-modal="true">${html}</div></div>`;
  const m = $(".modal");
  const h = $("h2", m);
  if (h) { h.id = "modal-title"; m.setAttribute("aria-labelledby", "modal-title"); }
  $(".backdrop").addEventListener("mousedown", e => { if (e.target.classList.contains("backdrop")) closeModal(); });
  $$("[data-close]", m).forEach(b => b.addEventListener("click", closeModal));
  const f = $("input, select, textarea, button", m);
  if (f) f.focus();
  return m;
}
function closeModal() {
  $("#modal-root").innerHTML = "";
  if (lastFocus && lastFocus.focus) lastFocus.focus();
}
document.addEventListener("keydown", e => { if (e.key === "Escape" && $(".modal")) closeModal(); });

function confirmBox(title, text, ok) {
  return new Promise(resolve => {
    const m = openModal(`<h2>${esc(title)}</h2><p class="muted">${esc(text)}</p><div class="actions end"><button class="btn" data-no>Cancel</button><button class="btn primary" data-yes>${esc(ok)}</button></div>`, { narrow: true });
    $("[data-no]", m).addEventListener("click", () => { closeModal(); resolve(false); });
    $("[data-yes]", m).addEventListener("click", () => { closeModal(); resolve(true); });
  });
}

/* ---------- session ---------- */
function signOut(message) {
  sessionStorage.removeItem("opsflow.token");
  sessionStorage.removeItem("opsflow.role");
  state.token = null;
  state.role = "";
  stopPolling();
  closeModal();
  $("#app").classList.add("hidden");
  $("#signin").classList.remove("hidden");
  const a = $("#signin-error");
  a.textContent = message || "";
  a.classList.toggle("hidden", !message);
  $("#actor").value = state.actor;
  $("#token").focus();
}

$("#signin-form").addEventListener("submit", async e => {
  e.preventDefault();
  const token = $("#token").value.trim();
  const actor = $("#actor").value.trim();
  const a = $("#signin-error");
  a.classList.add("hidden");
  if (!token) { a.textContent = "Enter the API token."; a.classList.remove("hidden"); return; }
  state.token = token;
  state.actor = actor;
  $("#signin-go").disabled = true;
  try {
    const s = await api("/api/session");
    state.role = s.role;
    sessionStorage.setItem("opsflow.token", token);
    sessionStorage.setItem("opsflow.actor", actor);
    sessionStorage.setItem("opsflow.role", s.role);
    $("#token").value = "";
    enterApp();
  } catch (err) {
    state.token = null;
    a.textContent = err.status === 401 ? "That token is not valid." : err.message;
    a.classList.remove("hidden");
  } finally {
    $("#signin-go").disabled = false;
  }
});
$("#signout").addEventListener("click", () => signOut());
$("#menu").addEventListener("click", () => {
  const open = $("#nav").classList.toggle("open");
  $("#menu").setAttribute("aria-expanded", String(open));
});

function enterApp() {
  $("#signin").classList.add("hidden");
  $("#app").classList.remove("hidden");
  $("#who").textContent = (state.actor || "Signed in") + " · " + (canWrite() ? "operator" : "read-only");
  if (!location.hash || location.hash === "#") location.hash = "#/tickets";
  else route();
}

/* ---------- routing ---------- */
function stopPolling() {
  clearTimeout(state.timer);
  state.timer = null;
}
function poll(fn, ms) {
  stopPolling();
  const token = Symbol();
  state.pollToken = token;
  state.timer = setTimeout(async () => {
    if (state.pollToken !== token || !state.token) return;
    try { await fn(); } catch (e) { if (!e.sessionEnded) console.warn(e); }
  }, ms);
}
const params = () => new URLSearchParams(location.hash.split("?")[1] || "");

async function route() {
  if (!state.token) return;
  stopPolling();
  closeModal();
  $("#nav").classList.remove("open");
  $("#menu").setAttribute("aria-expanded", "false");
  const path = location.hash.slice(1).split("?")[0] || "/tickets";
  const parts = path.split("/").filter(Boolean);
  $$(".nav a").forEach(a => a.classList.toggle("on", a.dataset.nav === parts[0]));
  try {
    if (parts[0] === "tickets" && parts[1]) await ticketPage(decodeURIComponent(parts[1]));
    else if (parts[0] === "tickets") await ticketsPage();
    else if (parts[0] === "dashboard") await dashboardPage();
    else if (parts[0] === "workflows") await workflowsPage();
    else if (parts[0] === "activity") await activityPage(parts[1] || "notifications");
    else if (parts[0] === "system") await systemPage();
    else main().innerHTML = `<div class="card"><h1>Not found</h1><p class="muted">There is no page here. <a href="#/tickets">Go to tickets</a>.</p></div>`;
  } catch (e) {
    if (e.sessionEnded) return;
    main().innerHTML = `<div class="card"><h1>${e.status === 404 ? "Not found" : "Something went wrong"}</h1><p class="muted">${esc(e.message)}</p><button class="btn" id="again">Try again</button></div>`;
    $("#again").addEventListener("click", route);
  }
  main().focus({ preventScroll: true });
}
window.addEventListener("hashchange", route);

/* ---------- tickets ---------- */
const STATUSES = ["NEW", "TRIAGED", "IN_PROGRESS", "ACTIONED", "FAILED", "RESOLVED"];
const TEAMS = ["db-team", "network-team", "auth-team", "devops-team", "general-support"];

async function ticketsPage() {
  const p = params();
  const q = p.get("q") || "", status = p.get("status") || "", priority = p.get("priority") || "", team = p.get("team") || "";
  const page = Math.max(0, parseInt(p.get("page") || "0", 10) || 0);
  main().innerHTML = `
    <div class="page-head"><div><h1>Tickets</h1><p class="muted" id="count">Loading…</p></div>
      ${canWrite() ? `<button class="btn primary" id="new-ticket">+ New ticket</button>` : ""}</div>
    <div class="card">
      <form class="toolbar" id="filters" role="search">
        <label class="sr" for="q">Search</label>
        <input type="search" id="q" name="q" placeholder="Search key, title, reporter" value="${esc(q)}">
        <select name="status" aria-label="Status"><option value="">Any status</option>${STATUSES.map(s => `<option value="${s}" ${s === status ? "selected" : ""}>${label(s)}</option>`).join("")}</select>
        <select name="priority" aria-label="Priority"><option value="">Any priority</option>${["P1", "P2", "P3"].map(s => `<option ${s === priority ? "selected" : ""}>${s}</option>`).join("")}</select>
        <select name="team" aria-label="Team"><option value="">Any team</option>${TEAMS.map(s => `<option ${s === team ? "selected" : ""}>${s}</option>`).join("")}</select>
        <button class="btn" type="submit">Search</button>
        ${q || status || priority || team ? `<button class="btn ghost" type="button" id="clear">Clear</button>` : ""}
      </form>
      <div class="table-wrap"><table>
        <thead><tr><th>Ticket</th><th>Priority</th><th class="hide-sm">Owner</th><th>Status</th><th class="hide-sm">Workflow</th><th class="hide-sm">Created</th></tr></thead>
        <tbody id="rows"><tr><td colspan="6" class="empty">Loading…</td></tr></tbody>
      </table></div>
      <div class="pager" id="pager"></div>
    </div>`;
  const nt = $("#new-ticket");
  if (nt) nt.addEventListener("click", newTicketForm);
  $("#filters").addEventListener("submit", e => {
    e.preventDefault();
    const d = formData(e.currentTarget);
    const np = new URLSearchParams(Object.entries(d).filter(([, v]) => v));
    location.hash = "#/tickets" + (np.toString() ? "?" + np : "");
  });
  const clear = $("#clear");
  if (clear) clear.addEventListener("click", () => (location.hash = "#/tickets"));
  $$("#filters select").forEach(s => s.addEventListener("change", () => $("#filters").requestSubmit()));

  const load = async () => {
    const qs = new URLSearchParams({ q, status, priority, team, page, size: 20 });
    const data = await api("/api/tickets?" + qs);
    $("#count").textContent = data.total === 1 ? "1 ticket" : data.total + " tickets";
    $("#rows").innerHTML = data.items.length ? data.items.map(t => `
      <tr class="click" data-key="${esc(t.key)}" tabindex="0">
        <td><span class="mono faint">${esc(t.key)}</span><div class="title">${esc(t.title)}</div>${t.reporter ? `<div class="faint small">${esc(t.reporter)}</div>` : ""}</td>
        <td>${prio(t.priority)}</td>
        <td class="hide-sm team">${esc(t.ownerTeam || "–")}</td>
        <td>${badge(t.status)}</td>
        <td class="hide-sm small muted">${esc(t.workflowName || "–")}</td>
        <td class="hide-sm small muted nowrap" title="${esc(when(t.createdAt))}">${esc(ago(t.createdAt))}</td>
      </tr>`).join("")
      : `<tr><td colspan="6" class="empty">${q || status || priority || team ? "No tickets match these filters." : "No tickets yet. Create one and watch it get triaged and worked."}</td></tr>`;
    $$("#rows tr[data-key]").forEach(tr => {
      const go = () => (location.hash = "#/tickets/" + tr.dataset.key);
      tr.addEventListener("click", go);
      tr.addEventListener("keydown", e => { if (e.key === "Enter") go(); });
    });
    const last = Math.max(0, data.totalPages - 1);
    $("#pager").innerHTML = data.totalPages > 1 ? `<span>Page ${data.page + 1} of ${data.totalPages}</span><span class="actions">
      <button class="btn sm" data-page="${page - 1}" ${page <= 0 ? "disabled" : ""}>Previous</button>
      <button class="btn sm" data-page="${page + 1}" ${page >= last ? "disabled" : ""}>Next</button></span>` : "";
    $$("#pager [data-page]").forEach(b => b.addEventListener("click", () => {
      const np = params();
      np.set("page", b.dataset.page);
      location.hash = "#/tickets?" + np;
    }));
    if (data.items.some(t => ["NEW", "TRIAGED", "IN_PROGRESS"].includes(t.status))) poll(load, 3000);
  };
  await load();
}

function newTicketForm() {
  const m = openModal(`<h2>New ticket</h2>
    <form id="tform" novalidate>
      <p class="alert hidden" role="alert"></p>
      <div class="form-grid">
        <label class="field full"><span>Title</span><input name="title" maxlength="200" autocomplete="off" placeholder="Orders database down in Mumbai"><span class="err" data-for="title"></span></label>
        <label class="field full"><span>What is happening?</span><textarea name="description" maxlength="5000" placeholder="Since 10:05 checkout fails with timeouts. About 40% of orders affected."></textarea><span class="err" data-for="description"></span></label>
        <label class="field"><span>Reported by <em>(optional)</em></span><input name="reporter" maxlength="100" autocomplete="off"><span class="err" data-for="reporter"></span></label>
        <label class="field"><span>Priority <em>(optional)</em></span><select name="priority"><option value="">Let triage decide</option><option>P1</option><option>P2</option><option>P3</option></select><span class="err" data-for="priority"></span></label>
        <label class="field full"><span>Workflow <em>(optional)</em></span><select name="workflow" id="wf-pick"><option value="">Pick automatically from priority and team</option></select><span class="err" data-for="workflow"></span></label>
      </div>
      <p class="preview top-gap" id="preview" aria-live="polite">Start typing to see how this ticket will be triaged.</p>
      <div class="actions end mt"><button type="button" class="btn" data-close>Cancel</button><button type="submit" class="btn primary">Create ticket</button></div>
    </form>`);
  api("/api/workflows").then(ws => {
    $("#wf-pick", m).insertAdjacentHTML("beforeend", ws.filter(w => w.valid).map(w => `<option value="${esc(w.id)}">${esc(w.name)}</option>`).join(""));
  }).catch(() => {});
  const form = $("#tform", m);
  let t, seq = 0;
  const preview = () => {
    clearTimeout(t);
    t = setTimeout(async () => {
      const d = formData(form);
      if (!d.title && !d.description) { $("#preview").textContent = "Start typing to see how this ticket will be triaged."; return; }
      const mine = ++seq;
      try {
        const r = await api("/api/triage/preview", { method: "POST", body: { title: d.title, description: d.description } });
        if (mine !== seq || !$("#preview")) return;
        const v = r.triage;
        $("#preview").innerHTML = `Triage would say <b>${esc(d.priority || v.priority)}</b>${d.priority ? " (your choice)" : ""} for <b>${esc(v.ownerTeam)}</b>
          ${r.workflow && !d.workflow ? ` and run <b>${esc(r.workflow.name)}</b>` : ""}.<br><span class="small">${esc(v.reason)}</span>`;
      } catch (e) {
        if ($("#preview")) $("#preview").textContent = e.status === 503 ? "Triage service is unreachable; the ticket will be triaged when it is back." : "";
      }
    }, 350);
  };
  form.addEventListener("input", preview);
  form.addEventListener("submit", async e => {
    e.preventDefault();
    const d = formData(form);
    const body = { title: d.title, description: d.description, reporter: d.reporter, priority: d.priority || null, workflow: d.workflow || null };
    const r = await submitting(form, () => api("/api/tickets", { method: "POST", body }));
    if (!r) return;
    closeModal();
    toast(r.key + " created. Triage is under way.");
    location.hash = "#/tickets/" + r.key;
  });
}

/* ---------- one ticket ---------- */
async function ticketPage(key) {
  main().innerHTML = `<a class="crumb" href="#/tickets">← Tickets</a><div id="ticket"><p class="muted">Loading…</p></div>`;
  let first = true;
  const load = async () => {
    const [d, runs] = await Promise.all([
      api("/api/tickets/" + encodeURIComponent(key)),
      api("/api/tickets/" + encodeURIComponent(key) + "/runs").catch(e => ({ error: e.message, items: [] })),
    ]);
    if (!$("#ticket")) return;
    renderTicket(d, runs, first);
    first = false;
    const busy = d.pendingJobs.length > 0 || ["NEW", "IN_PROGRESS"].includes(d.ticket.status) || (runs.items || []).some(r => r.status === "RUNNING");
    if (busy) poll(load, 1500);
  };
  await load();
}

function renderTicket(d, runs, first) {
  const t = d.ticket;
  const box = $("#ticket");
  const openNote = box.querySelector("#note") ? box.querySelector("#note").value : "";
  box.innerHTML = `
    <div class="ticket-head">
      <div><h1>${esc(t.title)}</h1>
        <div class="meta"><span class="mono">${esc(t.key)}</span>${prio(t.priority)}${badge(t.status)}<span>${esc(t.ownerTeam || "not triaged yet")}</span>
          <span class="faint">opened ${esc(ago(t.createdAt))} by ${esc(t.createdBy)}</span></div></div>
      ${canWrite() ? `<div class="actions">
        ${t.status === "RESOLVED" ? `<button class="btn" id="reopen">Reopen</button>` : `<button class="btn primary" id="resolve">Resolve</button>`}
        <button class="btn" id="edit">Change priority / owner</button>
        <button class="btn" id="retriage" ${d.pendingJobs.length ? "disabled" : ""}>Triage again</button>
        <button class="btn" id="run" ${d.pendingJobs.length || t.status === "RESOLVED" || !t.triagedAt || t.runStatus === "RUNNING" ? "disabled" : ""}>Run a workflow</button></div>` : ""}
    </div>
    ${d.pendingJobs.map(j => `<p class="note">${esc(jobText(j))}${j.lastError ? ` Last try: ${esc(j.lastError)} (attempt ${j.attempts}).` : ""}</p>`).join("")}
    ${d.failedJobs.map(j => `<p class="alert mb">${esc(jobText(j, true))} ${esc(j.lastError || "")}</p>`).join("")}
    <div class="grid cols-2">
      <div>
        <section class="card"><h2>Description</h2>${t.description ? `<p class="desc">${esc(t.description)}</p>` : `<p class="muted">No description.</p>`}
          ${t.reporter ? `<p class="small muted mt-s">Reported by ${esc(t.reporter)}</p>` : ""}</section>
        <section class="card"><div class="run-head"><h2 class="m0">Workflow runs</h2>${t.workflowName ? `<span class="small muted">Current: ${esc(t.workflowName)}</span>` : ""}</div>
          <div id="runs">${runsHtml(runs, t)}</div></section>
      </div>
      <div>
        <section class="card"><h2>Triage</h2>${t.triagedAt ? `
          <dl class="facts">
            <dt>Priority</dt><dd>${prio(t.priority)} ${t.priorityOverridden ? `<span class="small faint">set by hand</span>` : ""}</dd>
            <dt>Owner</dt><dd>${esc(t.ownerTeam)} ${t.teamOverridden ? `<span class="small faint">set by hand</span>` : ""}</dd>
            <dt>Decided by</dt><dd>${t.triageSource === "llm" ? "AI model" : "keyword rules"}${t.triageConfidence ? `, ${esc(t.triageConfidence)} confidence` : ""}</dd>
            <dt>Why</dt><dd>${esc(t.triageReason || "")}</dd>
            ${t.triageMatched.length ? `<dt>Matched</dt><dd class="chips">${t.triageMatched.map(m => `<span class="chip">${esc(m)}</span>`).join("")}</dd>` : ""}
          </dl>${t.triageNote ? `<p class="small muted mt-s">${esc(t.triageNote)}</p>` : ""}`
          : `<p class="muted">Waiting for the triage service…</p>`}</section>
        <section class="card"><h2>Activity</h2>
          ${canWrite() ? `<form id="note-form" class="grid grid note-form" novalidate>
            <p class="alert hidden" role="alert"></p>
            <label class="field"><span class="sr">Add a note</span><textarea id="note" name="text" maxlength="1500" placeholder="Add a note for the team" class="short"></textarea><span class="err" data-for="text"></span></label>
            <div class="actions end"><button class="btn sm" type="submit">Add note</button></div></form>` : ""}
          <ol class="timeline">${d.events.slice().reverse().map(e => `<li class="${esc(e.type)}"><div><div class="msg">${esc(e.message)}</div>
            <div class="when">${esc(when(e.createdAt))} · ${esc(e.actor)}</div></div></li>`).join("")}</ol></section>
      </div>
    </div>`;
  if (openNote && $("#note")) $("#note").value = openNote;
  wireTicket(t);
  if (first) box.scrollIntoView({ block: "start" });
}

function jobText(j, failed) {
  const what = { TRIAGE: "Triage", START_WORKFLOW: "Starting the workflow", CLOSE_ISSUES: "Closing tracker issues" }[j.type] || j.type;
  if (failed) return what + " failed and was not retried:";
  return j.attempts ? `${what} is waiting for its service and will be retried automatically.` : `${what} is queued.`;
}

function runsHtml(runs, t) {
  if (runs.error) return `<p class="note">${esc(runs.error)}</p>`;
  if (!runs.items || !runs.items.length) return `<p class="muted">${t.triagedAt ? "No workflow has run yet." : "A workflow starts as soon as triage is done."}</p>`;
  return runs.items.map(r => `
    <div class="run">
      <div class="run-head"><div><b>${esc(r.workflowName)}</b> <span class="small faint">${esc(r.trigger)} · ${esc(when(r.createdAt))}${r.attempt > 1 ? ` · attempt ${r.attempt}` : ""}</span></div>
        <div class="actions">${badge(r.status)}${r.warnings ? `<span class="badge FAILED">${r.warnings} warning${r.warnings > 1 ? "s" : ""}</span>` : ""}
        ${r.status === "FAILED" && canWrite() && r.id === t.currentRunId ? `<button class="btn sm" data-retry="${esc(r.id)}">Retry from failed step</button>` : ""}</div></div>
      <ol class="steps">${r.steps.map(s => `
        <li class="step"><span class="dot ${esc(s.status)}" aria-label="${esc(label(s.status))}"></span>
          <div><div class="label">${esc(s.label)}</div><div class="detail">${esc(s.detail || label(s.status))}${s.link ? ` · <a href="${esc(s.link)}" target="_blank" rel="noopener noreferrer">open in Jira</a>` : ""}</div></div>
          <div class="side">${s.reference ? `<span class="mono">${esc(s.reference.startsWith("n-") || s.reference.length > 20 ? "" : s.reference)}</span> ` : ""}${s.attempts > 1 ? `${s.attempts} tries · ` : ""}${s.startedAt ? esc(dur(s.startedAt, s.finishedAt)) : ""}</div></li>`).join("")}</ol>
      ${r.error ? `<div class="run-error">${esc(r.error)}</div>` : ""}
    </div>`).join("");
}

function wireTicket(t) {
  const key = t.key;
  const reload = () => route();
  const on = (id, fn) => { const b = $("#" + id); if (b) b.addEventListener("click", fn); };
  on("resolve", async () => {
    if (!(await confirmBox("Resolve " + key + "?", "Open tracker issues for this ticket will be closed.", "Resolve"))) return;
    try { await api("/api/tickets/" + key, { method: "PATCH", body: { status: "RESOLVED" } }); toast(key + " resolved"); reload(); }
    catch (e) { if (!e.sessionEnded) toast(e.message, true); }
  });
  on("reopen", async () => {
    try { await api("/api/tickets/" + key, { method: "PATCH", body: { status: "REOPENED" } }); toast(key + " reopened"); reload(); }
    catch (e) { if (!e.sessionEnded) toast(e.message, true); }
  });
  on("retriage", async () => {
    try { await api("/api/tickets/" + key + "/retriage", { method: "POST", body: { rerun: false } }); toast("Triage requested"); reload(); }
    catch (e) { if (!e.sessionEnded) toast(e.message, true); }
  });
  on("edit", () => {
    const m = openModal(`<h2>Change priority or owner</h2><form id="eform" novalidate><p class="alert hidden" role="alert"></p>
      <div class="form-grid">
        <label class="field"><span>Priority</span><select name="priority">${["P1", "P2", "P3"].map(p => `<option ${p === t.priority ? "selected" : ""}>${p}</option>`).join("")}</select><span class="err" data-for="priority"></span></label>
        <label class="field"><span>Owner team</span><input name="ownerTeam" list="teams" value="${esc(t.ownerTeam || "")}" autocomplete="off"><datalist id="teams">${TEAMS.map(x => `<option value="${x}">`).join("")}</datalist><span class="err" data-for="ownerTeam"></span></label>
      </div><p class="small muted">Your choice is kept if the ticket is triaged again. It does not restart the workflow.</p>
      <div class="actions end"><button type="button" class="btn" data-close>Cancel</button><button type="submit" class="btn primary">Save</button></div></form>`, { narrow: true });
    $("#eform", m).addEventListener("submit", async e => {
      e.preventDefault();
      const d = formData(e.currentTarget);
      const body = {};
      if (d.priority !== t.priority) body.priority = d.priority;
      if (d.ownerTeam !== (t.ownerTeam || "")) body.ownerTeam = d.ownerTeam;
      if (!Object.keys(body).length) { closeModal(); return; }
      const r = await submitting(e.currentTarget, () => api("/api/tickets/" + key, { method: "PATCH", body }));
      if (!r) return;
      closeModal(); toast("Saved"); reload();
    });
  });
  on("run", async () => {
    let ws = [];
    try { ws = (await api("/api/workflows")).filter(w => w.valid); } catch { /* list stays empty: automatic only */ }
    const m = openModal(`<h2>Run a workflow on ${esc(key)}</h2><form id="rform" novalidate><p class="alert hidden" role="alert"></p>
      <label class="field"><span>Workflow</span><select name="workflow"><option value="">Pick automatically (${esc(t.priority)}, ${esc(t.ownerTeam)})</option>
      ${ws.map(w => `<option value="${esc(w.id)}">${esc(w.name)} (${w.steps.length} steps)</option>`).join("")}</select><span class="err" data-for="workflow"></span></label>
      <div class="actions end mt"><button type="button" class="btn" data-close>Cancel</button><button type="submit" class="btn primary">Start</button></div></form>`, { narrow: true });
    $("#rform", m).addEventListener("submit", async e => {
      e.preventDefault();
      const d = formData(e.currentTarget);
      const r = await submitting(e.currentTarget, () => api("/api/tickets/" + key + "/runs", { method: "POST", body: { workflow: d.workflow || null } }));
      if (!r) return;
      closeModal(); toast("Workflow requested"); reload();
    });
  });
  $$("[data-retry]").forEach(b => b.addEventListener("click", async () => {
    b.disabled = true;
    try { await api("/api/runs/" + b.dataset.retry + "/retry", { method: "POST" }); toast("Retrying from the failed step"); reload(); }
    catch (e) { if (!e.sessionEnded) toast(e.message, true); b.disabled = false; }
  }));
  const nf = $("#note-form");
  if (nf) nf.addEventListener("submit", async e => {
    e.preventDefault();
    const d = formData(nf);
    const r = await submitting(nf, () => api("/api/tickets/" + key + "/notes", { method: "POST", body: { text: d.text } }));
    if (!r) return;
    $("#note").value = "";
    toast("Note added");
    reload();
  });
}

/* ---------- dashboard ---------- */
async function dashboardPage() {
  main().innerHTML = `<div class="page-head"><div><h1>Dashboard</h1><p class="muted">Everything open right now, and how fast tickets get worked.</p></div></div><div id="dash"><p class="muted">Loading…</p></div>`;
  const load = async () => {
    const [s, recent] = await Promise.all([api("/api/stats?days=14"), api("/api/events/recent")]);
    if (!$("#dash")) return;
    const p1 = (s.openByPriority || {}).P1 || 0;
    const avg = s.avgSecondsToAction == null ? "–" : s.avgSecondsToAction < 90 ? Math.round(s.avgSecondsToAction) + " s" : (s.avgSecondsToAction / 60).toFixed(1) + " min";
    const runs = s.runs || {};
    $("#dash").innerHTML = `
      <div class="tiles">
        <div class="tile"><div class="label">Open tickets</div><div class="value">${s.open}</div><div class="sub">${s.total} in total</div></div>
        <div class="tile"><div class="label">Open P1</div><div class="value${p1 ? " hot" : ""}">${p1}</div><div class="sub">major incidents</div></div>
        <div class="tile"><div class="label">Being worked</div><div class="value">${s.byStatus.IN_PROGRESS}</div><div class="sub">${s.pendingJobs} job(s) waiting</div></div>
        <div class="tile"><div class="label">Failed workflows</div><div class="value${s.byStatus.FAILED ? " hot" : ""}">${s.byStatus.FAILED}</div><div class="sub">${runs.COMPLETED ?? "–"} runs completed</div></div>
        <div class="tile"><div class="label">Time to action</div><div class="value">${avg}</div><div class="sub">ticket → workflow done</div></div>
      </div>
      <div class="grid cols-3">
        <section class="card"><h2>Open by priority</h2>${bars(s.openByPriority, true)}</section>
        <section class="card"><h2>Open by team</h2>${bars(s.openByTeam)}</section>
        <section class="card"><h2>By status</h2>${bars(s.byStatus)}</section>
      </div>
      <div class="grid cols-2 mt">
        <section class="card"><h2>New tickets, last ${s.days} days</h2>${dayChart(s.createdPerDay)}<p class="small faint mt-s">Days in ${esc(s.timezone)}</p></section>
        <section class="card"><h2>Latest activity</h2><ol class="timeline">${recent.length ? recent.slice(0, 10).map(e => `<li class="${esc(e.type)}"><div>
          <div class="msg"><a href="#/tickets/${esc(e.ticketKey)}">${esc(e.ticketKey)}</a> ${esc(e.message)}</div><div class="when">${esc(ago(e.createdAt))} · ${esc(e.actor)}</div></div></li>`).join("") : `<li><div class="muted">Nothing yet.</div></li>`}</ol></section>
      </div>`;
    $$("[data-w]").forEach(el => (el.style.width = el.dataset.w + "%"));
    poll(load, 5000);
  };
  await load();
}

function bars(map, isPriority) {
  const entries = Object.entries(map || {});
  if (!entries.length || entries.every(([, n]) => !n)) return `<p class="muted">Nothing open.</p>`;
  const max = Math.max(...entries.map(([, n]) => n), 1);
  return `<div class="bars">${entries.map(([k, n]) => `<div class="bar"><span class="${isPriority ? "" : "team"}">${esc(isPriority ? k : label(k))}</span>
    <span class="track"><span class="fill ${isPriority ? esc(k) : ""}" data-w="${(n / max) * 100}"></span></span><span class="n">${n}</span></div>`).join("")}</div>`;
}

function dayChart(perDay) {
  const days = Object.entries(perDay || {});
  const w = 560, h = 160, pad = 22, max = Math.max(1, ...days.map(([, n]) => n));
  const bw = (w - pad * 2) / Math.max(days.length, 1);
  return `<svg class="chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="New tickets per day">
    <line x1="${pad}" y1="${h - pad}" x2="${w - pad}" y2="${h - pad}" stroke="#2b3440"/>
    ${days.map(([d, n], i) => {
      const bh = (n / max) * (h - pad * 2);
      const x = pad + i * bw + 3;
      return `<rect x="${x}" y="${h - pad - bh}" width="${Math.max(bw - 6, 2)}" height="${bh}" rx="3" fill="#f5a524"><title>${esc(d)}: ${n}</title></rect>
        ${n ? `<text x="${x + (bw - 6) / 2}" y="${h - pad - bh - 4}" text-anchor="middle">${n}</text>` : ""}
        ${i % 2 === 0 ? `<text x="${x + (bw - 6) / 2}" y="${h - 6}" text-anchor="middle">${esc(d.slice(8))}</text>` : ""}`;
    }).join("")}</svg>`;
}

/* ---------- workflows ---------- */
async function workflowsPage() {
  main().innerHTML = `<div class="page-head"><div><h1>Workflows</h1><p class="muted">Templates the orchestrator runs. The first one whose rules match a ticket is used; otherwise the default.</p></div></div><div id="wfs"><p class="muted">Loading…</p></div>`;
  const [ws, rules] = await Promise.all([api("/api/workflows"), api("/api/triage/rules").catch(() => null)]);
  const matchText = w => {
    const p = w.priorities.length ? w.priorities.join(", ") : "any priority";
    const t = w.teams.length ? w.teams.join(", ") : "any team";
    return w.isDefault ? "Default: used when nothing else matches" : `${p} · ${t}`;
  };
  const stepText = s => {
    const extra = s.type === "deployment" || s.type === "deploy" ? ` (${esc(s.service || "core-api")})` : s.message && s.type !== "complete" ? `: <span class="muted">${esc(s.message)}</span>` : "";
    return `${esc(s.label)}${extra}${s.retries != null ? ` <span class="faint small">· ${s.retries} retries</span>` : ""}${s.continueOnError ? ` <span class="faint small">· optional</span>` : ""}`;
  };
  $("#wfs").innerHTML = `<div class="grid cols-2">
    <div>${ws.map(w => `<section class="card wf">
      <div class="run-head m0"><h2 class="m0">${esc(w.name)}</h2><span class="actions">${w.isDefault ? `<span class="badge TRIAGED">default</span>` : ""}${w.valid ? "" : `<span class="badge FAILED">broken</span>`}</span></div>
      <div class="small muted"><span class="mono">${esc(w.id)}</span> · ${esc(matchText(w))} · ${esc(w.source)}</div>
      ${w.description ? `<p class="m0">${esc(w.description)}</p>` : ""}
      ${w.valid ? "" : `<p class="alert">${w.errors.map(esc).join("<br>")}</p>`}
      <ol>${w.steps.map(s => `<li>${stepText(s)}</li>`).join("")}</ol></section>`).join("")}</div>
    <div><section class="card"><h2>Triage rules</h2>${rules ? `
      <p class="small muted mt0">${rules.llm && rules.llm.enabled ? `AI triage is on (${esc(rules.llm.model)}); these rules are the fallback.` : "Keyword rules decide. Set OPSFLOW_LLM_API_KEY to let an AI model decide, with these rules as the fallback."}</p>
      <h3>Priority (first match wins)</h3>
      ${rules.priorities.map(p => `<p class="rule">${prio(p.level)} <span class="small muted">${esc(p.reason)}</span><br><span class="chips mt-xs">${p.keywords.map(k => `<span class="chip">${esc(k)}</span>`).join("")}</span></p>`).join("")}
      <p class="small muted">Otherwise ${prio(rules.defaultPriority)}</p>
      <h3 class="mt">Owner (most matches wins)</h3>
      ${rules.teams.map(t => `<p class="rule"><b class="small">${esc(t.team)}</b><br><span class="chips mt-xs">${t.keywords.map(k => `<span class="chip">${esc(k)}</span>`).join("")}</span></p>`).join("")}
      <p class="small muted">Otherwise ${esc(rules.defaultTeam)}</p>` : `<p class="note">The triage service is unreachable.</p>`}</section></div></div>`;
}

/* ---------- activity ---------- */
async function activityPage(tab) {
  const tabs = { notifications: "Notifications", deployments: "Deployments", issues: "Tracker issues" };
  if (!tabs[tab]) tab = "notifications";
  const p = params();
  const page = Math.max(0, parseInt(p.get("page") || "0", 10) || 0);
  main().innerHTML = `<div class="page-head"><div><h1>Activity</h1><p class="muted">What the workflows did in the action services.</p></div></div>
    <nav class="tabs">${Object.entries(tabs).map(([k, v]) => `<a href="#/activity/${k}" class="${k === tab ? "on" : ""}">${v}</a>`).join("")}</nav>
    <div class="card"><div id="act"><p class="muted">Loading…</p></div><div class="pager" id="apager"></div></div>`;
  const load = async () => {
    const data = await api(`/api/activity/${tab}?page=${page}`);
    if (!$("#act")) return;
    let head, rows;
    if (tab === "notifications") {
      head = `<th>When</th><th>Channel</th><th>Message</th><th>Ticket</th><th>Status</th>`;
      rows = data.items.map(n => `<tr><td class="small nowrap muted">${esc(when(n.createdAt))}</td><td class="nowrap">${esc(n.channel)}</td>
        <td>${esc(n.message)}${n.attempts > 1 ? `<div class="faint small">${n.attempts} attempts</div>` : ""}</td><td>${ticketLink(n.ticketKey)}</td><td title="${esc(n.detail || "")}">${badge(n.status)}</td></tr>`);
    } else if (tab === "deployments") {
      head = `<th>Deployment</th><th>Where</th><th>Ticket</th><th>Status</th><th class="hide-sm">Took</th><th></th>`;
      rows = data.items.map(d => `<tr><td><span class="mono">${esc(d.key)}</span><div class="small muted">${esc(d.service)} ${esc(d.version)}</div></td>
        <td>${esc(d.environment)}</td><td>${ticketLink(d.ticketKey)}</td><td>${badge(d.status)}${d.failureReason ? `<div class="small small bad-text">${esc(d.failureReason)}</div>` : ""}</td>
        <td class="hide-sm small muted">${esc(dur(d.startedAt, d.finishedAt))}</td><td><button class="btn sm" data-log="${esc(d.id)}">Log</button></td></tr>`);
    } else {
      head = `<th>Issue</th><th>Summary</th><th>Priority</th><th>Ticket</th><th>Status</th>`;
      rows = data.items.map(i => `<tr><td class="nowrap"><span class="mono">${esc(i.key)}</span>${i.externalKey ? `<div class="small"><a href="${esc(i.externalUrl)}" target="_blank" rel="noopener noreferrer">Jira ${esc(i.externalKey)}</a></div>` : ""}</td>
        <td>${esc(i.summary)}<div class="small faint">${esc(i.team || "")}</div></td><td>${prio(i.priority)}</td><td>${ticketLink(i.ticketKey)}</td><td>${badge(i.status)}</td></tr>`);
    }
    $("#act").innerHTML = `<div class="table-wrap"><table><thead><tr>${head}</tr></thead><tbody>${rows.length ? rows.join("") : `<tr><td colspan="6" class="empty">Nothing yet. Workflows add entries here as they run.</td></tr>`}</tbody></table></div>`;
    $$("[data-log]").forEach(b => b.addEventListener("click", async () => {
      const d = await api("/api/activity/deployments/" + b.dataset.log);
      openModal(`<h2>${esc(d.key)} · ${esc(d.environment)}</h2><p class="small muted">${esc(d.service)} ${esc(d.version)} · ${badge(d.status)} · ${esc(d.reason || "")}</p><pre class="log">${esc(localLog(d.log))}</pre>
        <div class="actions end mt"><button class="btn" data-close>Close</button></div>`);
    }));
    $("#apager").innerHTML = data.totalPages > 1 ? `<span>Page ${data.page + 1} of ${data.totalPages}</span><span class="actions">
      <a class="btn sm" href="#/activity/${tab}?page=${Math.max(0, page - 1)}">Previous</a><a class="btn sm" href="#/activity/${tab}?page=${Math.min(data.totalPages - 1, page + 1)}">Next</a></span>` : "";
    if (tab === "deployments" && data.items.some(d => d.status === "QUEUED" || d.status === "RUNNING")) poll(load, 1500);
  };
  await load();
}
/* Log lines start with a UTC instant; show them in the viewer's time zone. */
const localLog = log => (log || "").replace(/^(\d{4}-\d\d-\d\dT[\d:.]+Z)/gm, t => new Date(t).toLocaleTimeString([], { hour12: false }));
const ticketLink = k => k ? `<a class="mono" href="#/tickets/${esc(k)}">${esc(k)}</a>` : `<span class="faint">–</span>`;

/* ---------- system ---------- */
async function systemPage() {
  main().innerHTML = `<div class="page-head"><div><h1>System</h1><p class="muted">Service health, work waiting on other services, integrations and test controls.</p></div>
    ${canWrite() ? `<button class="btn" id="retry-jobs">Retry waiting jobs now</button>` : ""}</div><div id="sys"><p class="muted">Loading…</p></div>`;
  const rj = $("#retry-jobs");
  if (rj) rj.addEventListener("click", async () => {
    try { const r = await api("/api/system/jobs/retry", { method: "POST" }); toast(r.scheduled + " job(s) retried"); route(); }
    catch (e) { if (!e.sessionEnded) toast(e.message, true); }
  });
  const load = async () => {
    const s = await api("/api/system");
    if (!$("#sys")) return;
    const dep = s.deployments;
    const integ = (name, ok, onText, offText) => `<tr><td>${esc(name)}</td><td>${ok == null ? badge("DOWN", "unreachable") : ok ? badge("SENT", "connected") : badge("NEW", "local only")}</td><td class="small muted">${esc(ok ? onText : offText)}</td></tr>`;
    $("#sys").innerHTML = `<div class="grid cols-2">
      <div>
        <section class="card"><h2>Services</h2><div class="table-wrap"><table><thead><tr><th>Service</th><th>Status</th><th>Detail</th></tr></thead><tbody>
          ${s.services.map(x => `<tr><td>${esc(x.service)}</td><td>${badge(x.status === "UP" ? "UP" : "DOWN", x.status.toLowerCase())}</td><td class="small muted">${x.status === "UP" ? esc(x.latencyMs + " ms") : esc(x.detail || "")}</td></tr>`).join("")}
        </tbody></table></div></section>
        <section class="card"><h2>Waiting jobs</h2>${s.pendingJobs.length ? `<div class="table-wrap"><table><thead><tr><th>Ticket</th><th>Job</th><th>Tries</th><th>Last error</th></tr></thead><tbody>
          ${s.pendingJobs.map(j => `<tr><td>${ticketLink("OPS-" + j.ticketId)}</td><td>${esc(label(j.type))}</td><td>${j.attempts}</td><td class="small muted">${esc(j.lastError || "queued")}</td></tr>`).join("")}</tbody></table></div>`
          : `<p class="muted">None. Every triage and workflow start has gone through.</p>`}
          ${s.failedJobs.length ? `<h3 class="mt">Gave up</h3><ul class="small">${s.failedJobs.map(j => `<li>${ticketLink("OPS-" + j.ticketId)} ${esc(label(j.type))}: ${esc(j.lastError || "")}</li>`).join("")}</ul>` : ""}</section>
      </div>
      <div>
        <section class="card"><h2>Integrations</h2><div class="table-wrap"><table><tbody>
          ${integ("Slack", s.notifications ? s.notifications.slackConfigured : null, "Notifications are posted to your Slack webhook.", "Notifications are recorded here. Set OPSFLOW_SLACK_WEBHOOK_URL to post them.")}
          ${integ("Jira Cloud", s.tracker ? s.tracker.jiraConfigured : null, "Issues are also created in Jira project " + ((s.tracker && s.tracker.jiraProject) || ""), "Issues live in the built-in tracker. Set the OPSFLOW_JIRA_* values to mirror them to Jira.")}
          ${integ("AI triage", s.triage ? s.triage.enabled : null, "Model: " + ((s.triage && s.triage.model) || ""), "Keyword rules triage tickets. Set OPSFLOW_LLM_API_KEY for AI triage.")}
        </tbody></table></div></section>
        <section class="card"><h2>Deployments</h2>${dep ? `
          <p class="small muted mt0">Environments: ${dep.environments.map(e => `<span class="chip">${esc(e)}</span>`).join(" ")}</p>
          ${dep.frozen.length ? dep.frozen.map(f => `<p class="note"><b>${esc(f.environment)}</b> is frozen: ${esc(f.reason)} ${canWrite() ? `<button class="btn sm" data-unfreeze="${esc(f.environment)}">Unfreeze</button>` : ""}</p>`).join("") : `<p class="muted small">No environment is frozen.</p>`}
          ${canWrite() ? `<form id="freeze" class="form-grid" novalidate><p class="alert hidden full" role="alert"></p>
            <label class="field"><span>Freeze</span><select name="environment">${dep.environments.map(e => `<option>${esc(e)}</option>`).join("")}</select></label>
            <label class="field"><span>Because</span><input name="reason" maxlength="300" placeholder="Change freeze during the sale"><span class="err" data-for="reason"></span></label>
            <div class="actions full"><button class="btn sm" type="submit">Freeze environment</button></div></form>
          <h3 class="mt">Fault injection</h3>
          <p class="small muted mt0">Make the next deployments fail their health check, to watch a workflow fail and be retried.${dep.failNext ? ` <b>${dep.failNext} more will fail.</b>` : ""}</p>
          <div class="actions"><button class="btn sm" data-chaos="1">Fail the next one</button><button class="btn sm" data-chaos="3">Fail the next 3</button>${dep.failNext ? `<button class="btn sm ghost" data-chaos="0">Stop</button>` : ""}</div>` : ""}`
          : `<p class="note">The deployment service is unreachable.</p>`}</section>
      </div></div>`;
    $$("[data-chaos]").forEach(b => b.addEventListener("click", async () => {
      try { await api("/api/system/chaos", { method: "POST", body: { failNext: Number(b.dataset.chaos) } }); toast(b.dataset.chaos === "0" ? "Fault injection off" : "The next " + b.dataset.chaos + " deployment(s) will fail"); load(); }
      catch (e) { if (!e.sessionEnded) toast(e.message, true); }
    }));
    $$("[data-unfreeze]").forEach(b => b.addEventListener("click", async () => {
      try { await api("/api/system/freezes/" + b.dataset.unfreeze, { method: "DELETE" }); toast(b.dataset.unfreeze + " unfrozen"); load(); }
      catch (e) { if (!e.sessionEnded) toast(e.message, true); }
    }));
    const fz = $("#freeze");
    if (fz) fz.addEventListener("submit", async e => {
      e.preventDefault();
      const d = formData(fz);
      const r = await submitting(fz, () => api("/api/system/freezes/" + d.environment, { method: "PUT", body: { reason: d.reason } }));
      if (!r) return;
      toast(d.environment + " frozen"); load();
    });
    if (s.pendingJobs.length) poll(load, 3000);
  };
  await load();
}

/* ---------- start ---------- */
if (state.token) {
  api("/api/session").then(s => { state.role = s.role; sessionStorage.setItem("opsflow.role", s.role); enterApp(); })
    .catch(e => { if (!e.sessionEnded) signOut(e.status === 401 ? "Sign in again." : e.message); });
} else {
  signOut();
}
