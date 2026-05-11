// volumes/reconcile.js — Reconcile dashboard: last-reconcile age, last-coherent-run
// age, four signal tiles, 30-row history table, Run / Run+Sweep buttons, and
// per-volume row state machine for coherent sync tracking.
// Mirrors utilities-sync-health.js reconcile + volume-actions logic verbatim; endpoints unchanged.

import * as taskCenter from '../../task-center.js';
import { esc, formatLastSynced } from './cards.js';

// ── Module-level state ────────────────────────────────────────────────────────
let lastReconcile   = null;  // most recent PersistedReport from /api/reconcile/recent?limit=1
let lastCoherentRun = null;  // PersistedReport or null from /api/reconcile/last?trigger=coherent_sync

// Per-volume run-state during a coherent sync.
// Map<volumeId, { state: 'idle'|'queued'|'scanning'|'cancelling'|'done'|'failed'|'skipped',
//                 startedAt, endedAt, detail, errorMsg, current, total }>
export let volStates = new Map();
export let activeRunId   = null;
export let activeES      = null;
export let runIsCoherent = false;
export let runStartedAt  = null;
export let coherentTotal = 0;
export let coherentDone  = 0;
export let currentVolId  = null;
let tickInterval = null;

// ── Data loading ──────────────────────────────────────────────────────────────
export async function loadReconcileData() {
  await Promise.all([loadLatestReconcile(), loadLastCoherentRun()]);
}

async function loadLatestReconcile() {
  try {
    const res = await fetch('/api/reconcile/recent?limit=1');
    if (!res.ok) { lastReconcile = null; return; }
    const list = await res.json();
    lastReconcile = Array.isArray(list) && list.length > 0 ? list[0] : null;
  } catch { lastReconcile = null; }
}

async function loadLastCoherentRun() {
  try {
    const res = await fetch('/api/reconcile/last?trigger=coherent_sync');
    lastCoherentRun = res.ok ? await res.json() : null;
  } catch { lastCoherentRun = null; }
}

// ── Format helpers ────────────────────────────────────────────────────────────
function formatAge(date) {
  const secs = Math.floor((Date.now() - date.getTime()) / 1000);
  if (secs < 60)   return 'just now';
  if (secs < 3600) return Math.floor(secs / 60) + 'm ago';
  if (secs < 86400) return Math.floor(secs / 3600) + 'h ago';
  const days = Math.floor(secs / 86400);
  if (days === 1) return 'yesterday';
  if (days < 30)  return days + ' days ago';
  return date.toLocaleDateString();
}

function formatDuration(ms) {
  const totalSecs = Math.floor(ms / 1000);
  const mins = Math.floor(totalSecs / 60);
  const secs = totalSecs % 60;
  if (mins === 0) return `${secs}s`;
  return `${mins}m ${String(secs).padStart(2, '0')}s`;
}

// ── Reconcile panel HTML ──────────────────────────────────────────────────────

export function renderReconcilePanel() {
  const r = lastReconcile;
  const rcAge = r?.generatedAt
    ? `<span title="${esc(new Date(r.generatedAt).toLocaleString())}">Last reconcile: ${formatAge(new Date(r.generatedAt))}</span>`
    : 'Last reconcile: never run';

  const chAge = lastCoherentRun?.generatedAt
    ? `<span title="${esc(new Date(lastCoherentRun.generatedAt).toLocaleString())}">Last coherent run: ${formatAge(new Date(lastCoherentRun.generatedAt))}</span>`
    : 'Last coherent run: never';

  const numCell = (val, warnAt) => {
    if (val == null) return `<span class="vol-rc-n">—</span>`;
    const cls = val >= warnAt ? ' warn' : '';
    return `<span class="vol-rc-n${cls}">${val}</span>`;
  };

  return `
    <div class="vol-reconcile-panel">
      <div class="vol-reconcile-age-bar">
        <span class="vol-reconcile-age">${rcAge}</span>
        <span class="vol-reconcile-age">${chAge}</span>
      </div>

      <div class="vol-rc-tiles">
        <div class="vol-rc-tile">
          <div class="vol-rc-tile-label">Duplicate live locations</div>
          <div class="vol-rc-tile-val">${numCell(r?.duplicateLiveLocations, 1)}</div>
        </div>
        <div class="vol-rc-tile">
          <div class="vol-rc-tile-label">Pending grace</div>
          <div class="vol-rc-tile-val">${numCell(r?.pendingGrace, 1)}</div>
        </div>
        <div class="vol-rc-tile">
          <div class="vol-rc-tile-label">Past-grace stragglers</div>
          <div class="vol-rc-tile-val">${numCell(r?.pastGraceStragglers, 1)}</div>
        </div>
        <div class="vol-rc-tile">
          <div class="vol-rc-tile-label">Actress folder mismatches</div>
          <div class="vol-rc-tile-val">${numCell(r?.actressFolderMismatches, 1)}</div>
        </div>
      </div>

      <div class="vol-rc-actions">
        <button type="button" class="btn sm" id="vol-rc-run"${taskCenter.isRunning() ? ' disabled' : ''}>Run reconcile</button>
        <button type="button" class="btn sm" id="vol-rc-sweep"${taskCenter.isRunning() ? ' disabled' : ''}>Run + sweep past-grace</button>
      </div>

      <div class="vol-rc-history-head">Reconcile history (last 30)</div>
      <div class="vol-rc-history-wrap">
        <table class="vol-rc-history-table">
          <thead>
            <tr>
              <th>Time</th>
              <th>Trigger</th>
              <th>Dup locs</th>
              <th>Pending</th>
              <th>Past-grace</th>
              <th>Folder mismatch</th>
            </tr>
          </thead>
          <tbody id="vol-rc-history-tbody"><tr><td colspan="6" class="vol-rc-history-empty">Loading…</td></tr></tbody>
        </table>
      </div>
    </div>
  `;
}

export function wireReconcilePanel(el, onRefresh) {
  const runBtn   = el.querySelector('#vol-rc-run');
  const sweepBtn = el.querySelector('#vol-rc-sweep');

  if (runBtn)   runBtn.addEventListener('click', () => runReconcile(false, el, onRefresh));
  if (sweepBtn) sweepBtn.addEventListener('click', () => {
    const past = lastReconcile?.pastGraceStragglers ?? 0;
    const msg = past > 0
      ? `Run reconcile and sweep ${past} past-grace stale row(s)? This deletes location rows older than the grace window.`
      : 'Run reconcile and sweep past-grace stale rows? (None currently — sweep will be a no-op.)';
    if (!confirm(msg)) return;
    runReconcile(true, el, onRefresh);
  });

  loadReportsTable();
}

async function runReconcile(sweep, el, onRefresh) {
  const runBtn   = el?.querySelector('#vol-rc-run');
  const sweepBtn = el?.querySelector('#vol-rc-sweep');
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
    if (onRefresh) onRefresh();
    loadReportsTable();
    if (sweep) {
      const result = body.sweepResult || `Swept ${body.sweptCount ?? 0} row(s)`;
      alert(result);
    }
  } catch (e) {
    alert('Reconcile failed: ' + e.message);
  } finally {
    if (runBtn)   { runBtn.textContent   = origRun;   runBtn.disabled   = false; }
    if (sweepBtn) { sweepBtn.textContent = origSweep; sweepBtn.disabled = false; }
  }
}

function loadReportsTable() {
  const tbody = document.getElementById('vol-rc-history-tbody');
  if (!tbody) return;
  fetch('/api/reconcile/recent?limit=30')
    .then(r => r.ok ? r.json() : [])
    .then(rows => {
      if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="vol-rc-history-empty">No reconcile reports yet.</td></tr>';
        return;
      }
      tbody.innerHTML = rows.map(r => {
        const t = r.generatedAt ? new Date(r.generatedAt) : null;
        const timeCell = t
          ? `<span class="vol-rc-time" title="${esc(t.toLocaleString())}">${esc(formatAge(t))}</span>`
          : '<span class="vol-rc-time">—</span>';
        const trigger = r.triggeredBy === 'coherent_sync' ? 'coherent_sync' : (r.triggeredBy || '—');
        const trigCls = r.triggeredBy === 'coherent_sync' ? 'vol-rc-trigger-coherent' : 'vol-rc-trigger-manual';
        const wc = (n) => n >= 1 ? ' class="vol-rc-num warn"' : ' class="vol-rc-num"';
        return `<tr>
          <td>${timeCell}</td>
          <td><span class="${trigCls}">${esc(trigger)}</span></td>
          <td${wc(r.duplicateLiveLocations)}>${r.duplicateLiveLocations ?? '—'}</td>
          <td${wc(r.pendingGrace)}>${r.pendingGrace ?? '—'}</td>
          <td${wc(r.pastGraceStragglers)}>${r.pastGraceStragglers ?? '—'}</td>
          <td${wc(r.actressFolderMismatches)}>${r.actressFolderMismatches ?? '—'}</td>
        </tr>`;
      }).join('');
    })
    .catch(() => {
      const tbody2 = document.getElementById('vol-rc-history-tbody');
      if (tbody2) tbody2.innerHTML = '<tr><td colspan="6" class="vol-rc-history-empty">Could not load reports.</td></tr>';
    });
}

// ── Per-volume row state for coherent sync ────────────────────────────────────

export function renderVolRows(volumes, onSync) {
  if (!volumes.length) {
    return '<div class="vol-sh-empty">No volumes configured.</div>';
  }
  const anyRunning = taskCenter.isRunning();
  return volumes.map(v => renderVolRow(v, volStates.get(v.id) || { state: 'idle' }, anyRunning)).join('');
}

function renderVolRow(v, vs, anyRunning) {
  const state = vs.state || 'idle';
  const mounted = v.status !== 'offline';

  let dotHtml, metaHtml, rightHtml, rowStyle = '', rowCls = 'vol-sh-vol-row';

  if (state === 'idle') {
    dotHtml  = mounted ? '<span class="vol-sh-dot vol-sh-dot--online"  title="online"></span>'
                       : '<span class="vol-sh-dot vol-sh-dot--offline" title="offline"></span>';
    metaHtml = esc(formatLastSynced(v.lastSyncedAt));
    rightHtml = `<button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}"${anyRunning ? ' disabled' : ''}>Sync</button>`;

  } else if (state === 'queued') {
    dotHtml   = mounted ? '<span class="vol-sh-dot vol-sh-dot--online"  title="online"></span>'
                        : '<span class="vol-sh-dot vol-sh-dot--offline" title="offline"></span>';
    metaHtml  = esc(formatLastSynced(v.lastSyncedAt));
    rightHtml = `<span class="vol-sh-state-label vol-sh-queued">queued</span>
      <button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;
    rowCls   += ' vol-sh-vol-row--muted';

  } else if (state === 'scanning') {
    dotHtml  = '<span class="vol-sh-spinner" title="scanning"></span>';
    const elapsed = vs.startedAt ? formatDuration(Date.now() - vs.startedAt) : '…';
    const progressText = vs.detail ? esc(vs.detail) : `Scanning… (${elapsed})`;
    metaHtml = `<span class="vol-sh-scanning-detail">${progressText}</span>`;
    rightHtml = `<span class="vol-sh-duration vol-sh-duration--ticking">(${elapsed})</span>
      <button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;
    rowStyle  = 'background: rgba(59, 130, 246, 0.08);';
    const bar = renderRowProgressBar(vs);
    return `<div class="${rowCls}" style="${rowStyle}">
      <div class="vol-sh-vol-id">${dotHtml}${esc(v.id.toUpperCase())}</div>
      <div class="vol-sh-vol-meta">${metaHtml}</div>
      <div class="vol-sh-vol-btns">${rightHtml}</div>
      ${bar}
    </div>`;

  } else if (state === 'cancelling') {
    dotHtml  = '<span class="vol-sh-spinner vol-sh-spinner--cancelling" title="cancelling"></span>';
    const elapsed = vs.startedAt ? formatDuration(Date.now() - vs.startedAt) : '…';
    metaHtml = `<span class="vol-sh-scanning-detail">⏸ Cancelling…</span>`;
    rightHtml = `<span class="vol-sh-duration vol-sh-duration--ticking">(${elapsed})</span>
      <button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;
    rowStyle  = 'background: rgba(59, 130, 246, 0.08);';
    const bar = renderRowProgressBar(vs);
    return `<div class="${rowCls}" style="${rowStyle}">
      <div class="vol-sh-vol-id">${dotHtml}${esc(v.id.toUpperCase())}</div>
      <div class="vol-sh-vol-meta">${metaHtml}</div>
      <div class="vol-sh-vol-btns">${rightHtml}</div>
      ${bar}
    </div>`;

  } else if (state === 'done') {
    dotHtml  = '<span class="vol-sh-dot vol-sh-dot--done" title="done">✓</span>';
    metaHtml = 'Just now';
    const dur = vs.startedAt && vs.endedAt ? formatDuration(vs.endedAt - vs.startedAt) : '';
    rightHtml = `<span class="vol-sh-duration vol-sh-duration--final">${esc(dur)}</span>
      <button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;

  } else if (state === 'failed') {
    dotHtml  = '<span class="vol-sh-dot vol-sh-dot--failed" title="failed">✗</span>';
    metaHtml = esc(formatLastSynced(v.lastSyncedAt));
    const dur = vs.startedAt && vs.endedAt ? formatDuration(vs.endedAt - vs.startedAt) : '';
    const errTitle = vs.errorMsg ? ` title="${esc(vs.errorMsg)}"` : '';
    rightHtml = `<span class="vol-sh-duration vol-sh-duration--failed"${errTitle}>Failed (${esc(dur)})</span>
      <button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;

  } else if (state === 'skipped') {
    dotHtml   = mounted ? '<span class="vol-sh-dot vol-sh-dot--online"  title="online"></span>'
                        : '<span class="vol-sh-dot vol-sh-dot--offline" title="offline"></span>';
    metaHtml  = esc(formatLastSynced(v.lastSyncedAt));
    rightHtml = `<span class="vol-sh-state-label vol-sh-skipped">skipped</span>
      <button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;
    rowCls   += ' vol-sh-vol-row--muted';

  } else {
    dotHtml  = mounted ? '<span class="vol-sh-dot vol-sh-dot--online"  title="online"></span>'
                       : '<span class="vol-sh-dot vol-sh-dot--offline" title="offline"></span>';
    metaHtml = esc(formatLastSynced(v.lastSyncedAt));
    rightHtml = `<button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}"${anyRunning ? ' disabled' : ''}>Sync</button>`;
  }

  const styleAttr = rowStyle ? ` style="${rowStyle}"` : '';
  return `<div class="${rowCls}"${styleAttr}>
    <div class="vol-sh-vol-id">${dotHtml}${esc(v.id.toUpperCase())}</div>
    <div class="vol-sh-vol-meta">${metaHtml}</div>
    <div class="vol-sh-vol-btns">${rightHtml}</div>
  </div>`;
}

function renderRowProgressBar(vs) {
  const hasProgress = (vs.total || 0) > 0;
  const pct = hasProgress ? Math.max(0, Math.min(100, (vs.current / vs.total) * 100)) : 0;
  const cls = hasProgress ? 'vol-sh-progress-fill' : 'vol-sh-progress-fill vol-sh-progress-fill--indeterminate';
  return `<div class="vol-sh-progress"><div class="${cls}" style="width: ${pct}%;"></div></div>`;
}

// ── Coherent-sync progress subtitle ─────────────────────────────────────────

export function renderCoherentSubtitle() {
  if (!runIsCoherent || !activeRunId) return '';
  const elapsed  = runStartedAt ? formatDuration(Date.now() - runStartedAt) : '—';
  const volLabel = currentVolId ? `vol-${currentVolId}` : '—';
  const pct      = coherentTotal > 0 ? Math.min(100, (coherentDone / coherentTotal) * 100) : 0;
  return `
    <div class="vol-sh-coherent-progress-text">Coherent sync running — ${esc(volLabel)} (${coherentDone}/${coherentTotal} done) — ${esc(elapsed)} elapsed</div>
    <div class="vol-sh-coherent-progress-bar"><div class="vol-sh-coherent-progress-fill" style="width: ${pct}%;"></div></div>
  `;
}

// ── Coherent sync start ───────────────────────────────────────────────────────

export async function startCoherentSyncFromSyncHealth(volumes, onRender) {
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
    initVolStatesQueued(volumes);
    runIsCoherent  = true;
    runStartedAt   = Date.now();
    coherentTotal  = volumes.length;
    coherentDone   = 0;
    currentVolId   = null;
    taskCenter.start({ taskId: 'volume.sync_coherent', runId, label: 'Coherent sync (all volumes)' });
    openEventSource(runId, volumes, onRender);
    if (onRender) onRender();
  } catch (err) {
    alert('Failed to start coherent sync: ' + err.message);
  }
}

export async function startSingleVolumeSyncFromSyncHealth(volumeId, volumes, onRender) {
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
    runIsCoherent = false;
    runStartedAt  = Date.now();
    volStates.clear();
    volStates.set(volumeId, { state: 'scanning', startedAt: Date.now(), endedAt: null, detail: '', errorMsg: '', current: 0, total: 0 });
    taskCenter.start({ taskId: 'volume.sync', runId, label: `Syncing Volume ${volumeId.toUpperCase()}` });
    openEventSource(runId, volumes, onRender);
    if (onRender) onRender();
  } catch (err) {
    alert('Sync failed: ' + err.message);
  }
}

// ── EventSource management ────────────────────────────────────────────────────

function initVolStatesQueued(volumes) {
  volStates.clear();
  for (const v of volumes) {
    volStates.set(v.id, { state: 'queued', startedAt: null, endedAt: null, detail: '', errorMsg: '', current: 0, total: 0 });
  }
}

export function openEventSource(runId, volumes, onRender) {
  if (activeES && activeRunId === runId) return;
  if (activeES) { activeES.close(); activeES = null; }
  activeRunId = runId;
  startTickInterval(onRender);
  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  activeES = es;
  es.addEventListener('task.started',   () => {});
  es.addEventListener('phase.started',  e => handlePhaseStarted(JSON.parse(e.data), onRender));
  es.addEventListener('phase.progress', e => handlePhaseProgress(JSON.parse(e.data)));
  es.addEventListener('phase.ended',    e => handlePhaseEnded(JSON.parse(e.data), onRender));
  es.addEventListener('task.ended',     e => handleTaskEnded(JSON.parse(e.data), volumes, onRender));
  es.onerror = () => {};
}

function startTickInterval(onRender) {
  stopTickInterval();
  tickInterval = setInterval(() => { if (onRender) onRender(); }, 1000);
}

function stopTickInterval() {
  if (tickInterval != null) { clearInterval(tickInterval); tickInterval = null; }
}

// ── Phase handlers ────────────────────────────────────────────────────────────

function handlePhaseStarted(ev, onRender) {
  const phaseId = ev.phaseId;
  if (phaseId && phaseId.startsWith('vol.')) {
    const volId = phaseId.slice(4);
    currentVolId = volId;
    volStates.set(volId, { state: 'scanning', startedAt: Date.now(), endedAt: null, detail: '', errorMsg: '', current: 0, total: 0 });
    if (runIsCoherent) {
      const mins = runStartedAt ? Math.floor((Date.now() - runStartedAt) / 60000) : 0;
      taskCenter.updateLabel(`Coherent sync — vol-${volId} (${coherentDone}/${coherentTotal}) — ${mins}m`);
      taskCenter.updateProgress({ phaseLabel: `Scanning ${volId.toUpperCase()}` });
    }
  }
  if (onRender) onRender();
}

function handlePhaseProgress(ev) {
  const phaseId = ev.phaseId;
  if (!phaseId || !phaseId.startsWith('vol.')) return;
  const volId = phaseId.slice(4);
  const vs = volStates.get(volId);
  if (!vs) return;
  if (ev.total > 0) {
    vs.detail  = `${ev.detail || 'Saving'} ${ev.current}/${ev.total}`;
    vs.current = ev.current;
    vs.total   = ev.total;
  } else if (ev.detail) {
    vs.detail = ev.detail;
  }
  // onRender called by tick interval
}

function handlePhaseEnded(ev, onRender) {
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
    if (runIsCoherent) {
      const mins = runStartedAt ? Math.floor((Date.now() - runStartedAt) / 60000) : 0;
      taskCenter.updateLabel(`Coherent sync — (${coherentDone}/${coherentTotal}) — ${mins}m`);
    }
  }
  if (onRender) onRender();
}

async function handleTaskEnded(ev, volumes, onRender) {
  stopTickInterval();
  if (activeES) { activeES.close(); activeES = null; }

  if (!runIsCoherent) {
    for (const [, vs] of volStates) {
      if (vs.state === 'scanning' || vs.state === 'cancelling') {
        vs.state   = ev.status === 'ok' ? 'done' : 'failed';
        vs.endedAt = Date.now();
        vs.errorMsg = ev.status !== 'ok' ? (ev.summary || 'Unknown error') : '';
      }
    }
  }
  for (const [, vs] of volStates) {
    if (vs.state === 'queued') vs.state = 'skipped';
  }

  taskCenter.finish({ status: ev.status, summary: ev.summary });
  activeRunId   = null;
  runIsCoherent = false;
  currentVolId  = null;

  if (onRender) onRender();

  // Refresh reconcile data and clear run states after a short pause.
  try {
    await Promise.all([loadLatestReconcile(), loadLastCoherentRun()]);
    volStates.clear();
    runStartedAt  = null;
    coherentTotal = 0;
    coherentDone  = 0;
    if (onRender) onRender();
  } catch {}
}

// ── taskCenter subscription helper (cancel-detection) ────────────────────────

export function handleTaskCenterUpdate(active) {
  if (active && active.cancelRequested && active.status === 'running' && runIsCoherent) {
    for (const [, vs] of volStates) {
      if (vs.state === 'queued') vs.state = 'skipped';
      else if (vs.state === 'scanning') vs.state = 'cancelling';
    }
    if (currentVolId) {
      taskCenter.updateLabel(`Cancelling… vol-${currentVolId} must finish first`);
      taskCenter.updateProgress({ phaseLabel: '' });
    }
  }
}
