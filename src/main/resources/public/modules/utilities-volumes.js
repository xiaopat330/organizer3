// Utilities → Volumes screen.
// Two-pane target + operations layout. Left: volume picker with health badges.
// Right: volume detail and operations stage (5 modes: empty / detail / visualize
// / running / summary). Backed by /api/utilities/* endpoints; progress streams
// via SSE on /api/utilities/runs/{runId}/events. See spec/UTILITIES_VOLUMES.md.

import { esc } from './utils.js';

const SELECTION_KEY = 'utilities.volumes.selection';

const listEl    = () => document.getElementById('volumes-list');
const emptyEl   = () => document.getElementById('volumes-empty');
const detailEl  = () => document.getElementById('volumes-detail');
const runEl     = () => document.getElementById('volumes-run');
const viewEl    = () => document.getElementById('tools-volumes-view');

let volumes = [];        // last-known list from /api/utilities/volumes
let selectedId = null;   // currently selected volume id
let activeRun = null;    // { runId, eventSource, phases: Map<id,state>, logBuffer }

export async function showVolumesView() {
  viewEl().style.display = 'flex';
  selectedId = localStorage.getItem(SELECTION_KEY);
  await refreshVolumes();
  if (selectedId && volumes.some(v => v.id === selectedId)) {
    showDetail(selectedId);
  } else {
    showEmpty();
  }
}

export function hideVolumesView() {
  viewEl().style.display = 'none';
  if (activeRun?.eventSource) {
    activeRun.eventSource.close();
    activeRun = null;
  }
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
      showDetail(v.id);
    });

    const row = document.createElement('div');
    row.className = 'vol-row-title';
    row.innerHTML = `<span>Volume ${esc(v.id.toUpperCase())}</span>${badgeHTML(v)}`;
    li.appendChild(row);

    const path = document.createElement('div');
    path.className = 'vol-row-path';
    path.textContent = v.smbPath || '';
    li.appendChild(path);

    const meta = document.createElement('div');
    meta.className = 'vol-row-meta';
    meta.textContent = `${v.titleCount || 0} titles · ${formatLastSynced(v.lastSyncedAt)}`;
    li.appendChild(meta);

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
  emptyEl().style.display = '';
  detailEl().style.display = 'none';
  runEl().style.display = 'none';
}

function showDetail(volumeId) {
  const v = volumes.find(x => x.id === volumeId);
  if (!v) { showEmpty(); return; }

  emptyEl().style.display = 'none';
  runEl().style.display = 'none';
  const d = detailEl();
  d.style.display = '';

  d.innerHTML = `
    <div class="vol-detail-head">
      <div class="vol-detail-name">Volume ${esc(v.id.toUpperCase())}</div>
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
      <button type="button" class="vol-op-primary" id="vol-op-sync">Sync</button>
    </div>
  `;
  document.getElementById('vol-op-sync').addEventListener('click', () => startSync(v.id));
}

function renderHealth(v) {
  const issues = v.health || [];
  if (issues.length === 0) {
    return `<div class="vol-health-healthy">
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
      All healthy
    </div>`;
  }
  const rows = issues.map(h => `
    <li>
      <span>${esc(h.description)}</span>
    </li>
  `).join('');
  return `<ul class="vol-health-list">${rows}</ul>`;
}

async function startSync(volumeId) {
  try {
    const res = await fetch(`/api/utilities/tasks/volume.sync/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    beginRunView(volumeId, runId);
  } catch (err) {
    alert('Failed to start sync: ' + err.message);
  }
}

function beginRunView(volumeId, runId) {
  // Close any previous SSE.
  if (activeRun?.eventSource) activeRun.eventSource.close();

  activeRun = {
    runId,
    volumeId,
    eventSource: null,
    phases: new Map(),   // phaseId → { label, status, detail, durationMs, logs: [] }
    taskStatus: 'running',
    taskSummary: '',
  };

  detailEl().style.display = 'none';
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
    logs: [],
  });
  renderRun();
}

function handlePhaseProgress(ev) {
  if (!activeRun) return;
  const p = activeRun.phases.get(ev.phaseId);
  if (!p) return;
  if (ev.total > 0) {
    p.detail = `${ev.current} / ${ev.total}${ev.detail ? ' — ' + ev.detail : ''}`;
  } else if (ev.detail) {
    p.detail = ev.detail;
  }
  renderRun();
}

function handlePhaseLog(ev) {
  if (!activeRun) return;
  const p = activeRun.phases.get(ev.phaseId);
  if (!p) return;
  p.logs.push(ev.line);
  renderRunLog();
}

function handlePhaseEnded(ev) {
  if (!activeRun) return;
  const p = activeRun.phases.get(ev.phaseId);
  if (!p) return;
  p.status = ev.status;
  p.durationMs = ev.durationMs;
  if (ev.summary) p.detail = ev.summary;
  renderRun();
}

function handleTaskEnded(ev) {
  if (!activeRun) return;
  activeRun.taskStatus = ev.status;
  activeRun.taskSummary = ev.summary;
  if (activeRun.eventSource) { activeRun.eventSource.close(); activeRun.eventSource = null; }
  renderRun();
  // Refresh volumes list so last-synced updates.
  refreshVolumes();
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
    const icon = p.status === 'running' ? '⏳'
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
        </div>
        <div class="vol-run-phase-duration">${esc(dur)}</div>
      </div>
    `;
  }).join('');

  const actions = activeRun.taskStatus === 'running'
      ? ''
      : `<div class="vol-run-actions"><button type="button" id="vol-run-done">Done</button></div>`;

  r.innerHTML = `
    <div class="vol-run-head">
      <span>Syncing Volume ${esc(v.id.toUpperCase())}</span>
      <span class="vol-run-status ${activeRun.taskStatus}">${esc(statusLabel)}</span>
    </div>
    <div class="vol-run-phases">${phasesHTML}</div>
    <button type="button" class="vol-run-log-toggle" id="vol-run-log-toggle">Show log output</button>
    <div class="vol-run-log" id="vol-run-log" style="display:none"></div>
    ${actions}
  `;

  document.getElementById('vol-run-log-toggle').addEventListener('click', toggleLog);
  if (activeRun.taskStatus !== 'running') {
    document.getElementById('vol-run-done').addEventListener('click', () => {
      activeRun = null;
      showDetail(v.id);
    });
  }
  renderRunLog();
}

function renderRunLog() {
  const el = document.getElementById('vol-run-log');
  if (!el || !activeRun) return;
  const all = [];
  for (const [, p] of activeRun.phases) {
    for (const line of p.logs) all.push(line);
  }
  el.textContent = all.join('\n');
  el.scrollTop = el.scrollHeight;
}

function toggleLog() {
  const el = document.getElementById('vol-run-log');
  const btn = document.getElementById('vol-run-log-toggle');
  if (!el) return;
  if (el.style.display === 'none') {
    el.style.display = '';
    btn.textContent = 'Hide log output';
  } else {
    el.style.display = 'none';
    btn.textContent = 'Show log output';
  }
}

function formatMs(ms) {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  const s = Math.floor(ms / 1000);
  return Math.floor(s / 60) + 'm ' + (s % 60) + 's';
}
