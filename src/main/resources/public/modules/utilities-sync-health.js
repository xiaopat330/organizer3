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

function renderVolumeActions() {
  const listEl = document.getElementById('sh-vol-list');
  if (!listEl) return;
  if (volumes.length === 0) {
    listEl.innerHTML = '<div class="sh-vol-empty">No volumes configured.</div>';
    return;
  }
  const isBlocked = taskCenter.isRunning();
  const disAttr = isBlocked ? ' disabled' : '';
  // Sync button always available — SyncVolumeTask handles mount/unmount itself as phases.
  // Mount status dot is purely informational. Mount/unmount and queue-only sync are not
  // exposed here: there are no /mount or /unmount HTTP endpoints, and SyncVolumeTask does
  // not honor a queueOnly input flag — it always runs `sync all`. For queue-partition-only
  // sync use the `sync queue` shell command.
  listEl.innerHTML = volumes.map(v => {
    const mounted  = v.status !== 'offline';
    const statusDot = mounted
      ? '<span class="sh-vol-dot sh-vol-dot--online"  title="online"></span>'
      : '<span class="sh-vol-dot sh-vol-dot--offline" title="offline"></span>';
    return `<div class="sh-vol-row">
      <div class="sh-vol-id">${statusDot}${esc(v.id.toUpperCase())}</div>
      <div class="sh-vol-meta">${esc(formatLastSynced(v.lastSyncedAt))}</div>
      <div class="sh-vol-btns">
        <button type="button" class="sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}"${disAttr}>Sync</button>
      </div>
    </div>`;
  }).join('');

  // Wire per-volume buttons
  listEl.querySelectorAll('.sh-vol-btn').forEach(btn => {
    btn.addEventListener('click', () => handleVolAction(btn.dataset.action, btn.dataset.vol));
  });
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
  taskCenter.subscribe(() => {
    const isBlocked = taskCenter.isRunning();
    const c = document.getElementById('sh-coherent-btn');
    const r = document.getElementById('sh-rc-run');
    const s = document.getElementById('sh-rc-sweep');
    if (c) c.disabled = isBlocked;
    if (r && r.textContent !== 'Running…' && r.textContent !== 'Sweeping…') r.disabled = isBlocked;
    if (s && s.textContent !== 'Running…' && s.textContent !== 'Sweeping…') s.disabled = isBlocked;
    // Re-render volume action buttons to update disabled state
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
    taskCenter.start({
      taskId: 'volume.sync_coherent',
      runId,
      label: 'Coherent sync (all volumes)',
    });
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
    taskCenter.start({ taskId: 'volume.sync', runId, label: `Syncing Volume ${volumeId.toUpperCase()}` });
  } catch (err) {
    alert('Sync failed: ' + err.message);
  }
}
