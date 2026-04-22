// Utilities → Volumes screen.
// Two-pane target + operations layout. Left: volume picker with health badges.
// Right: volume detail and operations stage (5 modes: empty / detail / visualize
// / running / summary). Backed by /api/utilities/* endpoints; progress streams
// via SSE on /api/utilities/runs/{runId}/events. See spec/UTILITIES_VOLUMES.md.

import { esc } from './utils.js';
import * as taskCenter from './task-center.js';

const SELECTION_KEY = 'utilities.volumes.selection';

// When the user clicks the floating task pill elsewhere in the app, they want
// to come back here and see the run. Register a callback once at module load.
taskCenter.onOpenRequested((state) => {
  if (!state) return;
  const volumesBtn = document.getElementById('tools-volumes-btn');
  if (volumesBtn) volumesBtn.click();
});

// Pick up any server-side active run after a page refresh. The SSE stream will
// replay history, so the pill + run view reconstruct themselves without the
// user needing to be on Volumes at load time.
(async function probeActiveRun() {
  try {
    const res = await fetch('/api/utilities/active');
    if (!res.ok) return;
    const body = await res.json();
    if (!body.active) return;
    // Best-effort: we don't know the originating volumeId for arbitrary tasks,
    // but volume.sync encodes it in the run's first PhaseStarted. For now we
    // adopt the run and let the SSE stream populate phases; if the user opens
    // Volumes, showVolumesView will restore the pane. We just need the pill
    // to show immediately.
    const runId = body.runId;
    taskCenter.start({
      taskId: body.taskId,
      runId,
      label: labelFor(body.taskId),
    });
    // Adopt into the module's activeRun state so subsequent view-switches work.
    activeRun = {
      runId,
      volumeId: null,   // unknown until we see the first phase log / task inputs
      eventSource: null,
      phases: new Map(),
      taskStatus: body.status || 'running',
      taskSummary: '',
    };
    const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
    activeRun.eventSource = es;
    es.addEventListener('task.started',  () => {});
    es.addEventListener('phase.started', e => handlePhaseStarted(JSON.parse(e.data)));
    es.addEventListener('phase.progress',e => handlePhaseProgress(JSON.parse(e.data)));
    es.addEventListener('phase.log',     e => handlePhaseLog(JSON.parse(e.data)));
    es.addEventListener('phase.ended',   e => handlePhaseEnded(JSON.parse(e.data)));
    es.addEventListener('task.ended',    e => handleTaskEnded(JSON.parse(e.data)));
  } catch { /* silently ignore — this is a convenience probe */ }
})();

function labelFor(taskId) {
  if (taskId === 'volume.sync') return 'Syncing volume';
  if (taskId === 'volume.clean_stale_locations') return 'Cleaning stale locations';
  return taskId;
}

// If the detail view is showing and a task finishes (or starts) elsewhere,
// re-render so the Sync button's disabled state is current.
taskCenter.subscribe(() => {
  if (viewEl().style.display !== 'none' && detailEl().style.display !== 'none' && selectedId) {
    showDetail(selectedId);
  }
});

const listEl      = () => document.getElementById('volumes-list');
const emptyEl     = () => document.getElementById('volumes-empty');
const detailEl    = () => document.getElementById('volumes-detail');
const visualizeEl = () => document.getElementById('volumes-visualize');
const runEl       = () => document.getElementById('volumes-run');
const viewEl      = () => document.getElementById('tools-volumes-view');

function hideAllRightPanes() {
  emptyEl().style.display = 'none';
  detailEl().style.display = 'none';
  visualizeEl().style.display = 'none';
  runEl().style.display = 'none';
}

let volumes = [];        // last-known list from /api/utilities/volumes
let selectedId = null;   // currently selected volume id
let activeRun = null;    // { runId, eventSource, phases: Map<id,state>, logBuffer }

export async function showVolumesView() {
  viewEl().style.display = 'flex';
  selectedId = localStorage.getItem(SELECTION_KEY);
  await refreshVolumes();

  // If a task is still running from a previous navigation, return the user to
  // that run view regardless of what's currently selected. They expect
  // continuity.
  if (activeRun && activeRun.taskStatus === 'running') {
    if (activeRun.volumeId) {
      selectedId = activeRun.volumeId;
      localStorage.setItem(SELECTION_KEY, selectedId);
      renderList();
    }
    hideAllRightPanes();
    runEl().style.display = '';
    renderRun();
    return;
  }

  if (selectedId && volumes.some(v => v.id === selectedId)) {
    openVolume(selectedId);
  } else {
    showEmpty();
  }
}

export function hideVolumesView() {
  // Intentionally leave the EventSource and activeRun in place. A sync that
  // kicks off here must keep streaming while the user browses elsewhere;
  // the floating task pill surfaces state during that time.
  viewEl().style.display = 'none';
}

async function refreshVolumes() {
  try {
    const res = await fetch('/api/utilities/volumes');
    volumes = await res.json();
  } catch (err) {
    console.error('Failed to load volumes', err);
    volumes = [];
  }
  renderList();
}

// Palette of distinct volume hues. A stable index per id gives each volume its
// own color identity across re-renders and across sessions.
const VOLUME_HUES = [
  '#60a5fa', '#4ade80', '#fbbf24', '#f472b6', '#a78bfa',
  '#34d399', '#fb923c', '#22d3ee', '#e879f9', '#facc15',
  '#2dd4bf', '#f87171',
];

function hueFor(id) {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) | 0;
  return VOLUME_HUES[Math.abs(h) % VOLUME_HUES.length];
}

function diskIconSVG(color) {
  return `<svg class="vol-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="${color}" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
    <ellipse cx="12" cy="5" rx="9" ry="3"/>
    <path d="M3 5v6c0 1.66 4 3 9 3s9-1.34 9-3V5"/>
    <path d="M3 11v6c0 1.66 4 3 9 3s9-1.34 9-3v-6"/>
  </svg>`;
}

function renderList() {
  const ul = listEl();
  ul.innerHTML = '';
  for (const v of volumes) {
    const li = document.createElement('li');
    if (v.id === selectedId) li.classList.add('selected');
    li.addEventListener('click', () => {
      selectedId = v.id;
      localStorage.setItem(SELECTION_KEY, v.id);
      renderList();
      openVolume(v.id);
    });

    const color = hueFor(v.id);
    li.innerHTML = `
      <div class="vol-row">
        <div class="vol-row-icon">${diskIconSVG(color)}</div>
        <div class="vol-row-body">
          <div class="vol-row-title">
            <span class="vol-row-name" style="color:${color}">${esc(v.id.toUpperCase())}</span>
            ${badgeHTML(v)}
          </div>
          <div class="vol-row-path">${esc(v.smbPath || '')}</div>
          <div class="vol-row-meta">${v.titleCount || 0} titles · ${esc(formatLastSynced(v.lastSyncedAt))}</div>
        </div>
      </div>
    `;

    ul.appendChild(li);
  }
}

function badgeHTML(v) {
  if (v.status === 'offline') {
    return `<span class="vol-badge offline"><span class="vol-badge-dot"></span>offline</span>`;
  }
  const errors = (v.health || []).filter(h => h.level === 'error').length;
  const warns  = (v.health || []).filter(h => h.level === 'warn').length;
  if (errors > 0) return `<span class="vol-badge error"><span class="vol-badge-dot"></span>${errors}</span>`;
  if (warns > 0)  return `<span class="vol-badge warn"><span class="vol-badge-dot"></span>${warns}</span>`;
  return `<span class="vol-badge healthy"><span class="vol-badge-dot"></span>healthy</span>`;
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

function showEmpty() {
  hideAllRightPanes();
  emptyEl().style.display = '';
}

/**
 * Dispatch for opening a volume from the picker. If a run is in flight *for this
 * volume*, show the run pane; otherwise show the static detail pane. This is what
 * the user expects after clicking a volume whose sync they already started.
 */
function openVolume(volumeId) {
  if (activeRun && activeRun.taskStatus === 'running' && activeRun.volumeId === volumeId) {
    hideAllRightPanes();
    runEl().style.display = '';
    renderRun();
    return;
  }
  showDetail(volumeId);
}

function showDetail(volumeId) {
  const v = volumes.find(x => x.id === volumeId);
  if (!v) { showEmpty(); return; }

  hideAllRightPanes();
  const d = detailEl();
  d.style.display = '';

  const color = hueFor(v.id);
  d.innerHTML = `
    <div class="vol-detail-head">
      <div class="vol-detail-name">
        <span class="vol-detail-icon">${diskIconSVG(color)}</span>
        <span>Volume <span style="color:${color}">${esc(v.id.toUpperCase())}</span></span>
      </div>
      <div class="vol-detail-path">${esc(v.smbPath || '')}</div>
    </div>
    <div class="vol-detail-stats">
      <div><span class="vol-stat-label">Titles</span><span class="vol-stat-value">${v.titleCount || 0}</span></div>
      <div><span class="vol-stat-label">Structure</span><span class="vol-stat-value">${esc(v.structureType || '—')}</span></div>
      <div><span class="vol-stat-label">Last synced</span><span class="vol-stat-value">${esc(formatLastSynced(v.lastSyncedAt))}</span></div>
    </div>
    <div class="vol-section">
      <div class="vol-section-heading">Health</div>
      ${renderHealth(v)}
    </div>
    <div class="vol-section">
      <div class="vol-section-heading">Operations</div>
      <button type="button" class="vol-op-primary" id="vol-op-sync"${taskCenter.isRunning() ? ' disabled' : ''}>Sync</button>
      ${taskCenter.isRunning() ? '<div class="vol-op-blocked">Another utility task is running. Wait for it to finish.</div>' : ''}
    </div>
  `;
  document.getElementById('vol-op-sync').addEventListener('click', () => startSync(v.id));

  // Wire up any health-row action buttons. Each button's data-cat drives behavior.
  d.querySelectorAll('.vol-health-action').forEach(btn => {
    btn.addEventListener('click', async () => {
      const cat = btn.getAttribute('data-cat');
      if (cat === 'stale_locations') {
        await showStaleLocationsVisualize(v.id);
      }
    });
  });
}

function renderHealth(v) {
  const issues = v.health || [];
  if (issues.length === 0) {
    return `<div class="vol-health-healthy">
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
      All healthy
    </div>`;
  }
  const rows = issues.map(h => {
    const action = healthAction(h);
    const btn = action
      ? `<button type="button" class="vol-health-action" data-cat="${esc(h.category)}"${taskCenter.isRunning() ? ' disabled' : ''}>${esc(action.label)}</button>`
      : '';
    const tip = healthTooltip(h.category);
    const info = tip
      ? `<span class="vol-health-info" tabindex="0" title="${esc(tip)}">
          <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
         </span>`
      : '';
    return `<li><span class="vol-health-desc">${esc(h.description)}${info}</span>${btn}</li>`;
  }).join('');
  return `<ul class="vol-health-list">${rows}</ul>`;
}

/** Returns the action metadata for a health category, or null if there's no action yet. */
function healthAction(h) {
  if (h.category === 'stale_locations') return { label: 'Clean up', kind: 'visualize-task', taskId: 'volume.clean_stale_locations' };
  return null;
}

/** Short plain-text tooltip keyed by health category. Shown on hover of the (i) icon. */
function healthTooltip(category) {
  switch (category) {
    case 'stale_locations':
      return 'A stale location is a DB row saying "this file is at path X on this volume," but '
           + 'the file wasn\'t found during the last sync — it was moved, renamed, or deleted. '
           + 'Cleaning up removes the index row only; nothing on disk is touched.';
    default:
      return null;
  }
}

// ── Visualize-then-confirm: Clean stale locations ──────────────────────────
async function showStaleLocationsVisualize(volumeId) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running. Wait for it to finish.');
    return;
  }
  const pane = visualizeEl();
  hideAllRightPanes();
  pane.style.display = '';
  pane.innerHTML = `
    <div class="vol-visualize-head">
      <div class="vol-visualize-title">Clean stale locations</div>
      <div class="vol-visualize-sub">Fetching preview…</div>
    </div>
  `;

  let preview;
  try {
    const res = await fetch(`/api/utilities/tasks/volume.clean_stale_locations/preview`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    preview = await res.json();
  } catch (err) {
    pane.innerHTML = `
      <div class="vol-visualize-head">
        <div class="vol-visualize-title">Clean stale locations</div>
        <div class="vol-visualize-sub">Failed to fetch preview: ${esc(err.message)}</div>
      </div>
      <div class="vol-visualize-actions">
        <button type="button" class="vol-visualize-cancel">Back</button>
      </div>
    `;
    pane.querySelector('.vol-visualize-cancel').addEventListener('click', () => showDetail(volumeId));
    return;
  }

  const rows = preview.rows || [];
  if (rows.length === 0) {
    pane.innerHTML = `
      <div class="vol-visualize-head">
        <div class="vol-visualize-title">Clean stale locations</div>
        <div class="vol-visualize-sub">No stale locations found. Nothing to clean up.</div>
      </div>
      <div class="vol-visualize-actions">
        <button type="button" class="vol-visualize-cancel">Back</button>
      </div>
    `;
    pane.querySelector('.vol-visualize-cancel').addEventListener('click', () => showDetail(volumeId));
    return;
  }

  const rowsHTML = rows.map(r => `
    <tr>
      <td class="vol-visualize-cell-code">${esc(r.titleCode || '')}</td>
      <td class="vol-visualize-cell-path">${esc(r.path || '')}</td>
      <td class="vol-visualize-cell-date">${esc(shortDate(r.lastSeenAt))}</td>
    </tr>
  `).join('');

  pane.innerHTML = `
    <div class="vol-visualize-head">
      <div class="vol-visualize-title">Clean stale locations</div>
      <div class="vol-visualize-sub">
        The following <b>${rows.length}</b> location record${rows.length === 1 ? '' : 's'} will be removed from the database.
        Files on disk are not touched — only the index entries pointing at files no longer observed on the last sync.
      </div>
    </div>
    <div class="vol-visualize-table-wrap">
      <table class="vol-visualize-table">
        <thead><tr><th>Title</th><th>Path</th><th>Last seen</th></tr></thead>
        <tbody>${rowsHTML}</tbody>
      </table>
    </div>
    <div class="vol-visualize-actions">
      <button type="button" class="vol-visualize-cancel">Cancel</button>
      <button type="button" class="vol-visualize-proceed">Proceed — remove ${rows.length}</button>
    </div>
  `;
  pane.querySelector('.vol-visualize-cancel').addEventListener('click', () => showDetail(volumeId));
  pane.querySelector('.vol-visualize-proceed').addEventListener('click', () =>
      startCleanStaleLocations(volumeId));
}

async function startCleanStaleLocations(volumeId) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  try {
    const res = await fetch(`/api/utilities/tasks/volume.clean_stale_locations/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    taskCenter.start({
      taskId: 'volume.clean_stale_locations',
      runId,
      label: `Cleaning stale locations on Volume ${volumeId.toUpperCase()}`,
    });
    beginRunView(volumeId, runId, 'volume.clean_stale_locations');
  } catch (err) {
    alert('Failed to start cleanup: ' + err.message);
  }
}

function shortDate(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return ts;
  return d.toISOString().slice(0, 10);
}

async function startSync(volumeId) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running. Wait for it to finish before starting a new sync.');
    return;
  }
  try {
    const res = await fetch(`/api/utilities/tasks/volume.sync/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    taskCenter.start({
      taskId: 'volume.sync',
      runId,
      label: `Syncing Volume ${volumeId.toUpperCase()}`,
    });
    beginRunView(volumeId, runId, 'volume.sync');
  } catch (err) {
    alert('Failed to start sync: ' + err.message);
  }
}

function beginRunView(volumeId, runId, taskId) {
  // Close any previous SSE.
  if (activeRun?.eventSource) activeRun.eventSource.close();

  activeRun = {
    runId,
    volumeId,
    taskId: taskId || 'volume.sync',
    eventSource: null,
    phases: new Map(),   // phaseId → { label, status, detail, durationMs }
    taskStatus: 'running',
    taskSummary: '',
  };

  hideAllRightPanes();
  const r = runEl();
  r.style.display = '';
  renderRun();

  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  activeRun.eventSource = es;

  es.addEventListener('task.started',  e => { renderRun(); });
  es.addEventListener('phase.started', e => handlePhaseStarted(JSON.parse(e.data)));
  es.addEventListener('phase.progress',e => handlePhaseProgress(JSON.parse(e.data)));
  es.addEventListener('phase.log',     e => handlePhaseLog(JSON.parse(e.data)));
  es.addEventListener('phase.ended',   e => handlePhaseEnded(JSON.parse(e.data)));
  es.addEventListener('task.ended',    e => handleTaskEnded(JSON.parse(e.data)));
  es.onerror = () => { /* server closes on terminal event; browser reconnect attempts are harmless */ };
}

function handlePhaseStarted(ev) {
  if (!activeRun) return;
  activeRun.phases.set(ev.phaseId, {
    label: ev.label,
    status: 'running',
    detail: '',
    durationMs: null,
    current: 0,
    total: -1,
    lastTick: Date.now(),
  });
  taskCenter.updateProgress({
    phaseLabel: ev.label,
    overallPct: computeOverallPct(),
  });
  renderRun();
}

function handlePhaseProgress(ev) {
  if (!activeRun) return;
  const p = activeRun.phases.get(ev.phaseId);
  if (!p) return;
  p.current = ev.current;
  p.total = ev.total;
  if (ev.total > 0) {
    p.detail = `${ev.current} / ${ev.total}${ev.detail ? ' — ' + ev.detail : ''}`;
  } else if (ev.detail) {
    p.detail = ev.detail;
  }
  p.lastTick = Date.now();
  taskCenter.updateProgress({
    phaseLabel: p.label,
    overallPct: computeOverallPct(),
    detail: p.detail,
  });
  renderRun();
}

function handlePhaseLog(ev) {
  // Raw log lines no longer surfaced in the UI — users care about "scanned 120/1500",
  // not per-file log noise. The task layer already emits structured progress events;
  // raw log output lives in logs/organizer3.log for debugging.
}

function handlePhaseEnded(ev) {
  if (!activeRun) return;
  const p = activeRun.phases.get(ev.phaseId);
  if (!p) return;
  p.status = ev.status;
  p.durationMs = ev.durationMs;
  if (ev.summary) p.detail = ev.summary;
  taskCenter.updateProgress({ overallPct: computeOverallPct() });
  renderRun();
}

function handleTaskEnded(ev) {
  if (!activeRun) return;
  activeRun.taskStatus = ev.status;
  activeRun.taskSummary = ev.summary;
  if (activeRun.eventSource) { activeRun.eventSource.close(); activeRun.eventSource = null; }
  taskCenter.finish({ status: ev.status, summary: ev.summary });
  renderRun();
  // Refresh volumes list so last-synced updates.
  refreshVolumes();
}

// Overall % across all known phases of the current run. Phases that have not
// started yet count as 0, completed phases as 100, running as their current
// ratio (or 50 if indeterminate — just so the pill moves).
// Uses a fixed denominator equal to the expected phase count (4 for
// SyncVolumeTask). Overestimates if a task has fewer actual phases; good
// enough for a progress pill, not a billing system.
function computeOverallPct() {
  if (!activeRun) return 0;
  const EXPECTED_PHASES = 4;
  let sum = 0;
  for (const [, p] of activeRun.phases) {
    if (p.status === 'ok')      sum += 100;
    else if (p.status === 'failed') sum += 100;
    else if (p.total > 0)       sum += Math.min(100, (100 * p.current / p.total));
    else                        sum += 50;
  }
  return Math.min(100, sum / EXPECTED_PHASES);
}

function renderRun() {
  if (!activeRun) return;
  const r = runEl();
  const v = volumes.find(x => x.id === activeRun.volumeId) || { id: activeRun.volumeId };
  const statusLabel = activeRun.taskStatus === 'running' ? 'running'
      : activeRun.taskStatus === 'ok'      ? 'complete'
      : activeRun.taskStatus === 'partial' ? 'partial'
      : 'failed';

  const phasesHTML = Array.from(activeRun.phases.entries()).map(([id, p]) => {
    const cls = p.status;
    const icon = p.status === 'running' ? '<span class="vol-run-spinner"></span>'
              : p.status === 'ok'      ? '✓'
              : p.status === 'failed'  ? '✗'
              : '○';
    const dur = p.durationMs != null ? formatMs(p.durationMs) : '';
    return `
      <div class="vol-run-phase ${cls}">
        <div class="vol-run-phase-icon">${icon}</div>
        <div>
          <div class="vol-run-phase-label">${esc(p.label)}</div>
          ${p.detail ? `<div class="vol-run-phase-detail">${esc(p.detail)}</div>` : ''}
          ${renderPhaseBar(p)}
        </div>
        <div class="vol-run-phase-duration">${esc(dur)}</div>
      </div>
    `;
  }).join('');

  const actions = activeRun.taskStatus === 'running'
      ? ''
      : `<div class="vol-run-actions"><button type="button" id="vol-run-done">Done</button></div>`;

  const heading = activeRun.taskId === 'volume.clean_stale_locations'
      ? `Cleaning stale locations · Volume ${esc((v.id || '').toUpperCase())}`
      : `Syncing Volume ${esc((v.id || '').toUpperCase())}`;

  r.innerHTML = `
    <div class="vol-run-head">
      <span>${heading}</span>
      <span class="vol-run-status ${activeRun.taskStatus}">${esc(statusLabel)}</span>
    </div>
    <div class="vol-run-phases">${phasesHTML}</div>
    ${actions}
  `;

  if (activeRun.taskStatus !== 'running') {
    document.getElementById('vol-run-done').addEventListener('click', () => {
      activeRun = null;
      showDetail(v.id);
    });
  }
}

function renderPhaseBar(p) {
  if (p.status !== 'running') return '';
  if (p.total > 0 && p.current >= 0) {
    const pct = Math.min(100, Math.max(0, Math.floor(100 * p.current / p.total)));
    return `<div class="vol-phase-bar"><div class="vol-phase-bar-fill" style="width:${pct}%"></div></div>`;
  }
  return `<div class="vol-phase-bar"><div class="vol-phase-bar-indet"></div></div>`;
}

function formatMs(ms) {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  const s = Math.floor(ms / 1000);
  return Math.floor(s / 60) + 'm ' + (s % 60) + 's';
}
