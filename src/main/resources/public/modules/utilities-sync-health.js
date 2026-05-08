// Utilities → Sync Health screen.
// Consolidates all sync-related UI: coherent sync CTA, reconcile signals,
// reconcile report history, and per-volume actions (demoted to secondary).
// Phase B will remove the duplicated reconcile chip from Library Health and
// the coherent-sync button from Volumes — leave them in place for Phase A.

import { esc } from './utils.js';
import * as taskCenter from './task-center.js';

const viewEl = () => document.getElementById('tools-sync-health-view');

// ── State ─────────────────────────────────────────────────────────────────────
let volumes         = [];     // [{id, smbPath, status, lastSyncedAt, structureType}]
let lastReconcile   = null;   // most recent PersistedReport from /api/reconcile/recent?limit=1
let lastCoherentRun = null;   // PersistedReport or null from /api/reconcile/last?trigger=coherent_sync
let buttonsWired    = false;

// ── Run-tracking state ────────────────────────────────────────────────────────
// volStates: Map<volumeId, { state: 'idle'|'queued'|'scanning'|'done'|'failed'|'skipped',
//                            startedAt: null|number, endedAt: null|number,
//                            detail: string, errorMsg: string }>
let volStates       = new Map();
let activeRunId     = null;   // runId of the currently tracked run (null when idle)
let activeES        = null;   // EventSource for the active run
let runIsCoherent   = false;  // true when task is volume.sync_coherent
let runStartedAt    = null;   // Date.now() when the run began (for cumulative elapsed)
let coherentTotal   = 0;      // total volumes expected in a coherent run
let coherentDone    = 0;      // volumes completed (ok) so far
let currentVolId    = null;   // id of volume currently scanning
let tickInterval    = null;   // setInterval handle for ticking durations

// ── Show / hide ───────────────────────────────────────────────────────────────
export async function showSyncHealthView() {
  viewEl().style.display = 'flex';
  await Promise.all([
    loadVolumes(),
    loadLatestReconcile(),
    loadLastCoherentRun(),
  ]);
  renderCoherentSyncAge();
  renderReconcile();
  renderReportsTable();
  renderVolumeActions();
  if (!buttonsWired) {
    wireButtons();
    buttonsWired = true;
  }

  // If a coherent sync started before the user navigated here, reattach.
  const active = taskCenter.getActive();
  if (
    active &&
    active.status === 'running' &&
    active.taskId === 'volume.sync_coherent' &&
    active.runId !== activeRunId
  ) {
    // Seed all volumes as queued, then subscribe — replay from server will catch us up.
    initVolStatesQueued();
    runIsCoherent = true;
    runStartedAt  = runStartedAt || Date.now();
    coherentTotal = volumes.length;
    coherentDone  = 0;
    openEventSource(active.runId);
  }
}

export function hideSyncHealthView() {
  viewEl().style.display = 'none';
}

// ── Data loading ──────────────────────────────────────────────────────────────
async function loadVolumes() {
  try {
    const res = await fetch('/api/utilities/volumes');
    volumes = await res.json();
  } catch (e) {
    console.error('sync-health: failed to load volumes', e);
    volumes = [];
  }
}

async function loadLatestReconcile() {
  try {
    const res = await fetch('/api/reconcile/recent?limit=1');
    if (!res.ok) { lastReconcile = null; return; }
    const list = await res.json();
    lastReconcile = Array.isArray(list) && list.length > 0 ? list[0] : null;
  } catch (e) {
    console.error('sync-health: failed to load latest reconcile', e);
    lastReconcile = null;
  }
}

async function loadLastCoherentRun() {
  try {
    const res = await fetch('/api/reconcile/last?trigger=coherent_sync');
    lastCoherentRun = res.ok ? await res.json() : null;
  } catch (e) {
    console.error('sync-health: failed to load last coherent run', e);
    lastCoherentRun = null;
  }
}

// ── Render helpers ─────────────────────────────────────────────────────────────
function formatAge(date) {
  const secs = Math.floor((Date.now() - date.getTime()) / 1000);
  if (secs < 60)  return 'just now';
  if (secs < 3600) return Math.floor(secs / 60) + 'm ago';
  if (secs < 86400) return Math.floor(secs / 3600) + 'h ago';
  const days = Math.floor(secs / 86400);
  if (days === 1)  return 'yesterday';
  if (days < 30)   return days + ' days ago';
  return date.toLocaleDateString();
}

function formatDuration(ms) {
  const totalSecs = Math.floor(ms / 1000);
  const mins = Math.floor(totalSecs / 60);
  const secs = totalSecs % 60;
  if (mins === 0) return `${secs}s`;
  return `${mins}m ${String(secs).padStart(2, '0')}s`;
}

function renderCoherentSyncAge() {
  const el = document.getElementById('sh-coherent-age');
  if (!el) return;
  if (!lastCoherentRun || !lastCoherentRun.generatedAt) {
    el.textContent = 'Last coherent run: never';
    el.removeAttribute('title');
    return;
  }
  const t = new Date(lastCoherentRun.generatedAt);
  el.textContent = 'Last coherent run: ' + formatAge(t);
  el.title = t.toLocaleString();
}

function setReconcileNum(el, value, warnAt) {
  if (!el) return;
  if (value == null) { el.textContent = '—'; el.className = 'sh-n'; return; }
  el.textContent = String(value);
  el.className = 'sh-n' + (value >= warnAt ? ' warn' : '');
}

function renderReconcile() {
  const r = lastReconcile;
  setReconcileNum(document.getElementById('sh-rc-dup'),      r?.duplicateLiveLocations,  1);
  setReconcileNum(document.getElementById('sh-rc-pending'),  r?.pendingGrace,             1);
  setReconcileNum(document.getElementById('sh-rc-past'),     r?.pastGraceStragglers,      1);
  setReconcileNum(document.getElementById('sh-rc-mismatch'), r?.actressFolderMismatches,  1);

  const ageEl = document.getElementById('sh-reconcile-age');
  if (!ageEl) return;
  if (!r || !r.generatedAt) {
    ageEl.textContent = 'Last reconcile: never run';
    ageEl.removeAttribute('title');
    return;
  }
  const t = new Date(r.generatedAt);
  ageEl.textContent = 'Last reconcile: ' + formatAge(t);
  ageEl.title = t.toLocaleString();
}

function renderReportsTable() {
  const tbody = document.getElementById('sh-reports-tbody');
  if (!tbody) return;
  // Load full 30-row list asynchronously, then render
  fetch('/api/reconcile/recent?limit=30')
    .then(r => r.ok ? r.json() : [])
    .then(rows => {
      if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="sh-reports-empty">No reconcile reports yet.</td></tr>';
        return;
      }
      tbody.innerHTML = rows.map(r => {
        const t = r.generatedAt ? new Date(r.generatedAt) : null;
        const timeCell = t
          ? `<span class="sh-report-time" title="${esc(t.toLocaleString())}">${esc(formatAge(t))}</span>`
          : '<span class="sh-report-time">—</span>';
        const trigger = r.triggeredBy === 'coherent_sync' ? 'coherent_sync' : (r.triggeredBy || '—');
        const trigClass = r.triggeredBy === 'coherent_sync' ? 'sh-trigger-coherent' : 'sh-trigger-manual';
        return `<tr>
          <td>${timeCell}</td>
          <td><span class="${trigClass}">${esc(trigger)}</span></td>
          <td class="sh-report-num${(r.duplicateLiveLocations  >= 1) ? ' warn' : ''}">${r.duplicateLiveLocations  ?? '—'}</td>
          <td class="sh-report-num${(r.pendingGrace            >= 1) ? ' warn' : ''}">${r.pendingGrace            ?? '—'}</td>
          <td class="sh-report-num${(r.pastGraceStragglers     >= 1) ? ' warn' : ''}">${r.pastGraceStragglers     ?? '—'}</td>
          <td class="sh-report-num${(r.actressFolderMismatches >= 1) ? ' warn' : ''}">${r.actressFolderMismatches ?? '—'}</td>
        </tr>`;
      }).join('');
    })
    .catch(() => {
      tbody.innerHTML = '<tr><td colspan="6" class="sh-reports-empty">Could not load reports.</td></tr>';
    });
}

function formatLastSynced(ts) {
  if (!ts) return 'Never synced';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return 'Never synced';
  const diff = Date.now() - d.getTime();
  const days = Math.floor(diff / 86400000);
  if (days >= 2) return `${days} days ago`;
  if (days === 1) return 'Yesterday';
  const hours = Math.floor(diff / 3600000);
  if (hours >= 1) return `${hours}h ago`;
  const mins = Math.floor(diff / 60000);
  if (mins >= 1) return `${mins}m ago`;
  return 'Just now';
}

// ── Card subtitle ──────────────────────────────────────────────────────────────
function renderCardSubtitle() {
  const el = document.getElementById('sh-vol-card-subtitle');
  if (!el) return;
  if (!runIsCoherent || !activeRunId) {
    el.textContent = '';
    return;
  }
  const elapsed = runStartedAt ? formatDuration(Date.now() - runStartedAt) : '—';
  const volLabel = currentVolId ? `vol-${currentVolId}` : '—';
  el.textContent = `Coherent sync running — ${volLabel} (${coherentDone}/${coherentTotal} done) — ${elapsed} elapsed`;
}

// ── Per-volume row rendering ────────────────────────────────────────────────────
function renderVolumeActions() {
  const listEl = document.getElementById('sh-vol-list');
  if (!listEl) return;
  if (volumes.length === 0) {
    listEl.innerHTML = '<div class="sh-vol-empty">No volumes configured.</div>';
    return;
  }

  const anyRunning = taskCenter.isRunning();

  listEl.innerHTML = volumes.map(v => {
    const vs = volStates.get(v.id) || { state: 'idle' };
    return renderVolRow(v, vs, anyRunning);
  }).join('');

  // Wire per-volume buttons
  listEl.querySelectorAll('.sh-vol-btn').forEach(btn => {
    btn.addEventListener('click', () => handleVolAction(btn.dataset.action, btn.dataset.vol));
  });

  renderCardSubtitle();
}

function renderVolRow(v, vs, anyRunning) {
  const state = vs.state || 'idle';

  let dotHtml, metaHtml, rightHtml, rowStyle = '', rowCls = 'sh-vol-row';

  const mounted = v.status !== 'offline';

  if (state === 'idle') {
    dotHtml = mounted
      ? '<span class="sh-vol-dot sh-vol-dot--online"  title="online"></span>'
      : '<span class="sh-vol-dot sh-vol-dot--offline" title="offline"></span>';
    metaHtml = esc(formatLastSynced(v.lastSyncedAt));
    rightHtml = `<button type="button" class="sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}"${anyRunning ? ' disabled' : ''}>Sync</button>`;

  } else if (state === 'queued') {
    dotHtml = mounted
      ? '<span class="sh-vol-dot sh-vol-dot--online"  title="online"></span>'
      : '<span class="sh-vol-dot sh-vol-dot--offline" title="offline"></span>';
    metaHtml = esc(formatLastSynced(v.lastSyncedAt));
    rightHtml = `<span class="sh-vol-state-label sh-vol-queued">queued</span>
      <button type="button" class="sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;
    rowCls += ' sh-vol-row--muted';

  } else if (state === 'scanning') {
    dotHtml = '<span class="sh-vol-spinner" title="scanning"></span>';
    const elapsed = vs.startedAt ? formatDuration(Date.now() - vs.startedAt) : '…';
    const progressText = vs.detail ? esc(vs.detail) : `Scanning… (${elapsed})`;
    metaHtml = `<span class="sh-vol-scanning-detail">${progressText}</span>`;
    rightHtml = `<span class="sh-vol-duration sh-vol-duration--ticking">(${elapsed})</span>
      <button type="button" class="sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;
    rowStyle = 'background: rgba(59, 130, 246, 0.08);';

  } else if (state === 'cancelling') {
    dotHtml = '<span class="sh-vol-spinner sh-vol-spinner--cancelling" title="cancelling"></span>';
    const elapsed = vs.startedAt ? formatDuration(Date.now() - vs.startedAt) : '…';
    metaHtml = `<span class="sh-vol-scanning-detail">⏸ Cancelling…</span>`;
    rightHtml = `<span class="sh-vol-duration sh-vol-duration--ticking">(${elapsed})</span>
      <button type="button" class="sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;
    rowStyle = 'background: rgba(59, 130, 246, 0.08);';

  } else if (state === 'done') {
    dotHtml = '<span class="sh-vol-dot sh-vol-dot--done" title="done">✓</span>';
    metaHtml = 'Just now';
    const dur = vs.startedAt && vs.endedAt ? formatDuration(vs.endedAt - vs.startedAt) : '';
    rightHtml = `<span class="sh-vol-duration sh-vol-duration--final">${esc(dur)}</span>
      <button type="button" class="sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;

  } else if (state === 'failed') {
    dotHtml = '<span class="sh-vol-dot sh-vol-dot--failed" title="failed">✗</span>';
    metaHtml = esc(formatLastSynced(v.lastSyncedAt));
    const dur = vs.startedAt && vs.endedAt ? formatDuration(vs.endedAt - vs.startedAt) : '';
    const errTitle = vs.errorMsg ? ` title="${esc(vs.errorMsg)}"` : '';
    rightHtml = `<span class="sh-vol-duration sh-vol-duration--failed"${errTitle}>Failed (${esc(dur)})</span>
      <button type="button" class="sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;

  } else if (state === 'skipped') {
    dotHtml = mounted
      ? '<span class="sh-vol-dot sh-vol-dot--online"  title="online"></span>'
      : '<span class="sh-vol-dot sh-vol-dot--offline" title="offline"></span>';
    metaHtml = esc(formatLastSynced(v.lastSyncedAt));
    rightHtml = `<span class="sh-vol-state-label sh-vol-skipped">skipped</span>
      <button type="button" class="sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;
    rowCls += ' sh-vol-row--muted';

  } else {
    // fallback: treat as idle
    dotHtml = mounted
      ? '<span class="sh-vol-dot sh-vol-dot--online"  title="online"></span>'
      : '<span class="sh-vol-dot sh-vol-dot--offline" title="offline"></span>';
    metaHtml = esc(formatLastSynced(v.lastSyncedAt));
    rightHtml = `<button type="button" class="sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}"${anyRunning ? ' disabled' : ''}>Sync</button>`;
  }

  const styleAttr = rowStyle ? ` style="${rowStyle}"` : '';
  return `<div class="${rowCls}"${styleAttr}>
    <div class="sh-vol-id">${dotHtml}${esc(v.id.toUpperCase())}</div>
    <div class="sh-vol-meta">${metaHtml}</div>
    <div class="sh-vol-btns">${rightHtml}</div>
  </div>`;
}

// ── Wire buttons ──────────────────────────────────────────────────────────────
function wireButtons() {
  // Coherent sync
  const coherentBtn = document.getElementById('sh-coherent-btn');
  if (coherentBtn) coherentBtn.addEventListener('click', confirmAndStartCoherentSync);

  // Reconcile
  const runBtn   = document.getElementById('sh-rc-run');
  const sweepBtn = document.getElementById('sh-rc-sweep');
  if (runBtn)   runBtn.addEventListener('click',   () => runReconcile(false));
  if (sweepBtn) sweepBtn.addEventListener('click', () => {
    const past = lastReconcile?.pastGraceStragglers ?? 0;
    const msg = past > 0
      ? `Run reconcile and sweep ${past} past-grace stale row(s)? This deletes location rows older than the grace window.`
      : 'Run reconcile and sweep past-grace stale rows? (None currently — sweep will be a no-op.)';
    if (!confirm(msg)) return;
    runReconcile(true);
  });

  // Keep buttons in sync with taskCenter
  taskCenter.subscribe((active) => {
    const isBlocked = taskCenter.isRunning();
    const c = document.getElementById('sh-coherent-btn');
    const r = document.getElementById('sh-rc-run');
    const s = document.getElementById('sh-rc-sweep');
    if (c) c.disabled = isBlocked;
    if (r && r.textContent !== 'Running…' && r.textContent !== 'Sweeping…') r.disabled = isBlocked;
    if (s && s.textContent !== 'Running…' && s.textContent !== 'Sweeping…') s.disabled = isBlocked;

    // Detect cancel request flip — mark current scanning row as cancelling,
    // queued rows as skipped.
    if (active && active.cancelRequested && active.status === 'running' && runIsCoherent) {
      for (const [vid, vs] of volStates) {
        if (vs.state === 'queued') vs.state = 'skipped';
        else if (vs.state === 'scanning') vs.state = 'cancelling';
      }
      // Update pill label to give immediate feedback
      if (currentVolId) {
        taskCenter.updateLabel(`Cancelling… vol-${currentVolId} must finish first`);
        taskCenter.updateProgress({ phaseLabel: '' });
      }
    }

    renderVolumeActions();
  });
}

// ── Actions ───────────────────────────────────────────────────────────────────
async function confirmAndStartCoherentSync() {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running. Wait for it to finish before starting a coherent sync.');
    return;
  }
  const confirmed = confirm(
    'Coherent multi-volume sync scans every configured volume in turn and only evaluates orphans '
    + 'after all volumes are observed.\n\n'
    + 'This holds the task lock for the duration and may run for hours. '
    + 'Recommended for overnight runs after manual cross-volume movement.\n\n'
    + 'Continue?'
  );
  if (!confirmed) return;

  try {
    const res = await fetch('/api/utilities/tasks/volume.sync_coherent/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();

    // Init per-volume states
    initVolStatesQueued();
    runIsCoherent  = true;
    runStartedAt   = Date.now();
    coherentTotal  = volumes.length;
    coherentDone   = 0;
    currentVolId   = null;

    taskCenter.start({
      taskId: 'volume.sync_coherent',
      runId,
      label: 'Coherent sync (all volumes)',
    });
    openEventSource(runId);
    renderVolumeActions();
  } catch (err) {
    alert('Failed to start coherent sync: ' + err.message);
  }
}

async function runReconcile(sweep) {
  const runBtn   = document.getElementById('sh-rc-run');
  const sweepBtn = document.getElementById('sh-rc-sweep');
  const origRun   = runBtn?.textContent;
  const origSweep = sweepBtn?.textContent;
  if (runBtn)   { runBtn.disabled   = true; runBtn.textContent   = sweep ? 'Sweeping…' : 'Running…'; }
  if (sweepBtn) { sweepBtn.disabled = true; sweepBtn.textContent = sweep ? 'Sweeping…' : 'Running…'; }
  try {
    const res = await fetch('/api/reconcile/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ verbose: false, sweep: !!sweep }),
    });
    if (!res.ok) { alert(`Reconcile failed (${res.status})`); return; }
    const body = await res.json();
    lastReconcile = body;
    renderReconcile();
    renderReportsTable();
    if (sweep) {
      const result = body.sweepResult || `Swept ${body.sweptCount ?? 0} row(s)`;
      alert(result);
    }
  } catch (e) {
    console.error('sync-health: reconcile failed', e);
    alert('Reconcile failed: ' + e.message);
  } finally {
    if (runBtn)   { runBtn.textContent   = origRun;   runBtn.disabled   = false; }
    if (sweepBtn) { sweepBtn.textContent = origSweep; sweepBtn.disabled = false; }
  }
}

async function handleVolAction(action, volumeId) {
  if (action !== 'sync') return;
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running. Wait for it to finish.');
    return;
  }
  try {
    const res = await fetch('/api/utilities/tasks/volume.sync/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (res.status === 409) {
      const b = await res.json().catch(() => ({}));
      alert(b.error || 'Task already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();

    // Single-volume sync: just track the one volume
    runIsCoherent = false;
    runStartedAt  = Date.now();
    volStates.clear();
    volStates.set(volumeId, { state: 'scanning', startedAt: Date.now(), endedAt: null, detail: '', errorMsg: '' });

    taskCenter.start({ taskId: 'volume.sync', runId, label: `Syncing Volume ${volumeId.toUpperCase()}` });
    openEventSource(runId);
    renderVolumeActions();
  } catch (err) {
    alert('Sync failed: ' + err.message);
  }
}

// ── EventSource management ─────────────────────────────────────────────────────
function initVolStatesQueued() {
  volStates.clear();
  for (const v of volumes) {
    volStates.set(v.id, { state: 'queued', startedAt: null, endedAt: null, detail: '', errorMsg: '' });
  }
}

function openEventSource(runId) {
  // Avoid opening a second EventSource for the same run
  if (activeES && activeRunId === runId) return;
  if (activeES) { activeES.close(); activeES = null; }

  activeRunId = runId;
  startTickInterval();

  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  activeES = es;

  es.addEventListener('task.started',  () => { /* replay may deliver this; ignore — we handle phases */ });
  es.addEventListener('phase.started', e => handlePhaseStarted(JSON.parse(e.data)));
  es.addEventListener('phase.progress', e => handlePhaseProgress(JSON.parse(e.data)));
  es.addEventListener('phase.ended',   e => handlePhaseEnded(JSON.parse(e.data)));
  es.addEventListener('task.ended',    e => handleTaskEnded(JSON.parse(e.data)));
  es.onerror = () => { /* server closes on terminal event; reconnect attempts are harmless */ };
}

function startTickInterval() {
  stopTickInterval();
  tickInterval = setInterval(() => {
    renderVolumeActions();
  }, 1000);
}

function stopTickInterval() {
  if (tickInterval != null) {
    clearInterval(tickInterval);
    tickInterval = null;
  }
}

// ── Phase event handlers ───────────────────────────────────────────────────────
function handlePhaseStarted(ev) {
  const phaseId = ev.phaseId;

  if (phaseId && phaseId.startsWith('vol.')) {
    const volId = phaseId.slice(4);
    currentVolId = volId;
    volStates.set(volId, { state: 'scanning', startedAt: Date.now(), endedAt: null, detail: '', errorMsg: '' });

    // Update pill label for coherent runs
    if (runIsCoherent) {
      const mins = runStartedAt ? Math.floor((Date.now() - runStartedAt) / 60000) : 0;
      taskCenter.updateLabel(`Coherent sync — vol-${volId} (${coherentDone}/${coherentTotal}) — ${mins}m`);
      taskCenter.updateProgress({ phaseLabel: `Scanning ${volId.toUpperCase()}` });
    }
  }
  // prune / reconcile phases: no special row state; just let them run
  renderVolumeActions();
}

function handlePhaseProgress(ev) {
  const phaseId = ev.phaseId;
  if (!phaseId || !phaseId.startsWith('vol.')) return;
  const volId = phaseId.slice(4);
  const vs = volStates.get(volId);
  if (!vs) return;

  if (ev.total > 0) {
    vs.detail = `${ev.detail || 'Saving'} ${ev.current}/${ev.total}`;
  } else if (ev.detail) {
    vs.detail = ev.detail;
  }
  // renderVolumeActions is called by the tick interval — no need to call here every event
}

function handlePhaseEnded(ev) {
  const phaseId = ev.phaseId;

  if (phaseId && phaseId.startsWith('vol.')) {
    const volId = phaseId.slice(4);
    const vs = volStates.get(volId);
    if (vs) {
      vs.state    = ev.status === 'ok' ? 'done' : 'failed';
      vs.endedAt  = Date.now();
      vs.detail   = ev.summary || '';
      vs.errorMsg = ev.status !== 'ok' ? (ev.summary || 'Unknown error') : '';
    }

    if (ev.status === 'ok') coherentDone++;
    if (volId === currentVolId) currentVolId = null;

    // Update pill label
    if (runIsCoherent) {
      const mins = runStartedAt ? Math.floor((Date.now() - runStartedAt) / 60000) : 0;
      taskCenter.updateLabel(`Coherent sync — (${coherentDone}/${coherentTotal}) — ${mins}m`);
    }
  }
  renderVolumeActions();
}

function handleTaskEnded(ev) {
  stopTickInterval();

  if (activeES) { activeES.close(); activeES = null; }

  // For single-volume sync: mark the scanning volume done/failed
  if (!runIsCoherent) {
    for (const [vid, vs] of volStates) {
      if (vs.state === 'scanning' || vs.state === 'cancelling') {
        vs.state   = ev.status === 'ok' ? 'done' : 'failed';
        vs.endedAt = Date.now();
        vs.errorMsg = ev.status !== 'ok' ? (ev.summary || 'Unknown error') : '';
      }
    }
  }

  // Any still-queued rows become skipped (cancelled run)
  for (const [, vs] of volStates) {
    if (vs.state === 'queued') vs.state = 'skipped';
  }

  taskCenter.finish({ status: ev.status, summary: ev.summary });
  activeRunId   = null;
  runIsCoherent = false;
  currentVolId  = null;

  renderVolumeActions();

  // Refresh page state — counts may have changed if reconcile auto-ran
  // at the end of a coherent sync.
  refreshAfterRun();
}

async function refreshAfterRun() {
  try {
    await Promise.all([loadVolumes(), loadLatestReconcile(), loadLastCoherentRun()]);
    // Clear run states now that we've refreshed
    volStates.clear();
    runStartedAt  = null;
    coherentTotal = 0;
    coherentDone  = 0;
    renderVolumeActions();
    renderReconcile();
    renderCoherentSyncAge();
    renderReportsTable();
  } catch (e) {
    console.error('sync-health: refresh after run failed', e);
  }
}
