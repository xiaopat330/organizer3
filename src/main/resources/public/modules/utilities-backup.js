// Utilities → Backup screen.
// Two-pane: snapshots on the left, per-snapshot detail / visualize / run on the right.
// Back up now is a header action. Restore uses visualize-then-confirm.
// See spec/UTILITIES_BACKUP_RESTORE.md.

import { esc } from './utils.js';
import * as taskCenter from './task-center.js';

const SELECTION_KEY = 'utilities.backup.selection';

const viewEl       = () => document.getElementById('tools-backup-view');
const listEl       = () => document.getElementById('bk-list');
const emptyEl      = () => document.getElementById('bk-empty');
const detailEl     = () => document.getElementById('bk-detail');
const visualizeEl  = () => document.getElementById('bk-visualize');
const runEl        = () => document.getElementById('bk-run');
const backupNowBtn = () => document.getElementById('bk-backup-now');

function hideAllRightPanes() {
  emptyEl().style.display = 'none';
  detailEl().style.display = 'none';
  visualizeEl().style.display = 'none';
  runEl().style.display = 'none';
}

let snapshots = [];
let selectedName = null;
let activeRun = null;

export async function showBackupView() {
  viewEl().style.display = 'flex';
  selectedName = localStorage.getItem(SELECTION_KEY);
  await refreshSnapshots();

  if (activeRun && activeRun.taskStatus === 'running') {
    hideAllRightPanes();
    runEl().style.display = '';
    renderRun();
    return;
  }

  if (selectedName && snapshots.some(s => s.name === selectedName)) {
    showDetail(selectedName);
  } else {
    showEmpty();
  }
}

export function hideBackupView() {
  viewEl().style.display = 'none';
}

async function refreshSnapshots() {
  try {
    const res = await fetch('/api/utilities/backup/snapshots');
    snapshots = await res.json();
  } catch (err) {
    console.error('Failed to load snapshots', err);
    snapshots = [];
  }
  renderList();
}

function renderList() {
  const ul = listEl();
  ul.innerHTML = '';
  if (snapshots.length === 0) {
    const li = document.createElement('li');
    li.className = 'bk-empty-list';
    li.textContent = 'No snapshots yet.';
    ul.appendChild(li);
  } else {
    for (const s of snapshots) {
      const li = document.createElement('li');
      if (s.name === selectedName) li.classList.add('selected');
      li.addEventListener('click', () => {
        selectedName = s.name;
        localStorage.setItem(SELECTION_KEY, s.name);
        renderList();
        showDetail(s.name);
      });

      const ts = s.timestamp ? new Date(s.timestamp) : null;
      const rel = ts ? formatRelative(ts) : s.name;
      const abs = ts ? ts.toISOString().replace('T', ' ').slice(0, 19) : '';
      li.innerHTML = `
        <div class="bk-row">
          <div class="bk-row-body">
            <div class="bk-row-title">
              <span class="bk-row-when">${esc(rel)}</span>
              ${s.latest ? '<span class="bk-row-latest">latest</span>' : ''}
            </div>
            <div class="bk-row-abs">${esc(abs)}</div>
            <div class="bk-row-meta">${formatBytes(s.sizeBytes)}</div>
          </div>
        </div>
      `;
      ul.appendChild(li);
    }
  }
  // Disable "Back up now" while any task is running.
  backupNowBtn().disabled = !!taskCenter.isRunning();
}

function formatRelative(date) {
  const diff = Date.now() - date.getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return mins + 'm ago';
  const hours = Math.floor(mins / 60);
  if (hours < 24) return hours + 'h ago';
  const days = Math.floor(hours / 24);
  if (days === 1) return 'Yesterday';
  return days + ' days ago';
}

function formatBytes(bytes) {
  if (bytes == null) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' kB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function showEmpty() {
  hideAllRightPanes();
  emptyEl().style.display = '';
}

async function showDetail(name) {
  hideAllRightPanes();
  detailEl().style.display = '';
  detailEl().innerHTML = '<div class="bk-detail-loading">Loading snapshot…</div>';

  try {
    const res = await fetch(`/api/utilities/backup/snapshots/${encodeURIComponent(name)}`);
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || `HTTP ${res.status}`);
    }
    const d = await res.json();
    renderDetail(d);
  } catch (err) {
    detailEl().innerHTML = `<div class="bk-detail-error">Failed to read snapshot: ${esc(err.message)}</div>`;
  }
}

function renderDetail(d) {
  const created = d.createdAt
      ? new Date(d.createdAt).toISOString().replace('T', ' ').slice(0, 19)
      : '';
  detailEl().innerHTML = `
    <div class="bk-detail-head">
      <div class="bk-detail-title">${esc(d.name)}</div>
      <div class="bk-detail-meta">
        Version ${d.version} · ${esc(created)} · ${formatBytes(d.sizeBytes)}
      </div>
    </div>
    <div class="bk-detail-grid">
      <div><span class="bk-stat-label">Actresses</span><span class="bk-stat-value">${d.actresses || 0}</span></div>
      <div><span class="bk-stat-label">Titles</span><span class="bk-stat-value">${d.titles || 0}</span></div>
      <div><span class="bk-stat-label">Watch history</span><span class="bk-stat-value">${d.watchHistory || 0}</span></div>
      <div><span class="bk-stat-label">AV actresses</span><span class="bk-stat-value">${d.avActresses || 0}</span></div>
      <div><span class="bk-stat-label">AV videos</span><span class="bk-stat-value">${d.avVideos || 0}</span></div>
    </div>
    <div class="bk-section">
      <div class="bk-section-heading">Operations</div>
      <button type="button" id="bk-restore-btn" class="bk-op-primary"${taskCenter.isRunning() ? ' disabled' : ''}>Restore this snapshot</button>
      ${taskCenter.isRunning() ? '<div class="bk-op-blocked">Another utility task is running. Wait for it to finish.</div>' : ''}
    </div>
  `;
  document.getElementById('bk-restore-btn').addEventListener('click', () => showRestorePreview(d.name));
}

// ── Visualize: restore preview ────────────────────────────────────────────

async function showRestorePreview(name) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  hideAllRightPanes();
  const pane = visualizeEl();
  pane.style.display = '';
  pane.innerHTML = `
    <div class="bk-visualize-head">
      <div class="bk-visualize-title">Restore preview</div>
      <div class="bk-visualize-sub">Reading snapshot and diffing against the database…</div>
    </div>
  `;

  let preview;
  try {
    const res = await fetch('/api/utilities/tasks/backup.restore/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ snapshotName: name }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || `HTTP ${res.status}`);
    }
    preview = await res.json();
  } catch (err) {
    pane.innerHTML = `
      <div class="bk-visualize-head">
        <div class="bk-visualize-title">Restore preview</div>
        <div class="bk-visualize-sub">Failed: ${esc(err.message)}</div>
      </div>
      <div class="bk-visualize-actions">
        <button type="button" id="bk-cancel-btn" class="bk-cancel-btn">Back</button>
      </div>
    `;
    document.getElementById('bk-cancel-btn').addEventListener('click', () => showDetail(name));
    return;
  }

  renderPreview(pane, name, preview);
}

function renderPreview(pane, name, preview) {
  const totalChanges = (preview.actresses?.existing || 0)
                     + (preview.titles?.existing || 0)
                     + (preview.watchHistory?.wouldInsert || 0)
                     + (preview.avActresses?.existing || 0)
                     + (preview.avVideos?.existing || 0);
  const totalSkip    = (preview.actresses?.missing || 0)
                     + (preview.titles?.missing || 0)
                     + (preview.avActresses?.missing || 0)
                     + (preview.avVideos?.missing || 0);
  const nothingToDo = totalChanges === 0 && totalSkip === 0;

  const when = preview.backupExportedAt
      ? new Date(preview.backupExportedAt).toISOString().replace('T', ' ').slice(0, 19)
      : '';

  const cats = [
    preview.actresses,
    preview.titles,
    preview.watchHistory,
    preview.avActresses,
    preview.avVideos,
  ].filter(c => c && (c.existing || c.missing || c.wouldInsert));

  pane.innerHTML = `
    <div class="bk-visualize-head">
      <div class="bk-visualize-title">Restore preview · ${esc(name)}</div>
      <div class="bk-visualize-sub">Snapshot exported ${esc(when)}.</div>
      <div class="bk-vz-badges">
        <span class="bk-vz-badge update">${totalChanges} will change</span>
        ${totalSkip > 0 ? `<span class="bk-vz-badge skip">${totalSkip} skipped (no DB row)</span>` : ''}
      </div>
      <div class="bk-visualize-note">
        Restore overlays user-altered fields (favorites, grades, bookmarks, watch history, notes) onto existing DB rows.
        Rows with no matching entry are left untouched. Non-destructive to rows not in the snapshot.
      </div>
    </div>
    <div class="bk-vz-cats">${cats.map(renderCategory).join('')}</div>
    <div class="bk-visualize-actions">
      <button type="button" id="bk-proceed-btn" class="bk-proceed-btn"${nothingToDo ? ' disabled' : ''}>
        ${nothingToDo ? 'Nothing to restore' : 'Proceed — restore'}
      </button>
      <button type="button" id="bk-cancel-btn" class="bk-cancel-btn">Cancel</button>
    </div>
  `;
  document.getElementById('bk-cancel-btn').addEventListener('click', () => showDetail(name));
  if (!nothingToDo) {
    document.getElementById('bk-proceed-btn').addEventListener('click', () => startRestore(name));
  }
}

function renderCategory(c) {
  const existing = c.existing || 0;
  const missing  = c.missing || 0;
  const insert   = c.wouldInsert || 0;
  const changed  = existing + insert;

  const samples = (c.sampleIds || []).map(id =>
      `<span class="bk-vz-sample">${esc(id)}</span>`).join('');
  const moreCount = changed - (c.sampleIds || []).length;
  const moreBadge = moreCount > 0 ? `<span class="bk-vz-more">+${moreCount} more</span>` : '';

  return `
    <div class="bk-vz-cat">
      <div class="bk-vz-cat-head">
        <span class="bk-vz-cat-name">${esc(c.name)}</span>
        <span class="bk-vz-cat-counts">
          ${c.wouldInsert ? `<span class="bk-vz-chip add">+${insert} insert</span>` : ''}
          ${existing ? `<span class="bk-vz-chip update">${existing} update</span>` : ''}
          ${missing  ? `<span class="bk-vz-chip skip">${missing} skip</span>` : ''}
        </span>
      </div>
      ${(samples || moreBadge) ? `<div class="bk-vz-samples">${samples}${moreBadge}</div>` : ''}
    </div>
  `;
}

// ── Run: back up / restore ────────────────────────────────────────────────

async function startBackupNow() {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  try {
    const res = await fetch('/api/utilities/tasks/backup.run_now/run', {
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
    taskCenter.start({ taskId: 'backup.run_now', runId, label: 'Backing up user data' });
    beginRunView('backup.run_now', null, runId);
  } catch (err) {
    alert('Failed to start backup: ' + err.message);
  }
}

async function startRestore(snapshotName) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  try {
    const res = await fetch('/api/utilities/tasks/backup.restore/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ snapshotName }),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    taskCenter.start({ taskId: 'backup.restore', runId, label: `Restoring ${snapshotName}` });
    beginRunView('backup.restore', snapshotName, runId);
  } catch (err) {
    alert('Failed to start restore: ' + err.message);
  }
}

function beginRunView(taskId, snapshotName, runId) {
  if (activeRun?.eventSource) activeRun.eventSource.close();
  activeRun = {
    runId,
    taskId,
    snapshotName,
    eventSource: null,
    phases: new Map(),
    taskStatus: 'running',
    taskSummary: '',
  };

  hideAllRightPanes();
  runEl().style.display = '';
  renderRun();

  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  activeRun.eventSource = es;
  es.addEventListener('phase.started', e => handlePhaseStarted(JSON.parse(e.data)));
  es.addEventListener('phase.progress',e => handlePhaseProgress(JSON.parse(e.data)));
  es.addEventListener('phase.ended',   e => handlePhaseEnded(JSON.parse(e.data)));
  es.addEventListener('task.ended',    e => handleTaskEnded(JSON.parse(e.data)));
}

function handlePhaseStarted(ev) {
  if (!activeRun) return;
  activeRun.phases.set(ev.phaseId, {
    label: ev.label, status: 'running', detail: '', durationMs: null, current: 0, total: -1,
  });
  taskCenter.updateProgress({ phaseLabel: ev.label, overallPct: computeOverallPct() });
  renderRun();
}
function handlePhaseProgress(ev) {
  if (!activeRun) return;
  const p = activeRun.phases.get(ev.phaseId);
  if (!p) return;
  p.current = ev.current; p.total = ev.total;
  if (ev.total > 0) p.detail = `${ev.current} / ${ev.total}${ev.detail ? ' — ' + ev.detail : ''}`;
  else if (ev.detail) p.detail = ev.detail;
  taskCenter.updateProgress({ phaseLabel: p.label, overallPct: computeOverallPct(), detail: p.detail });
  renderRun();
}
function handlePhaseEnded(ev) {
  if (!activeRun) return;
  const p = activeRun.phases.get(ev.phaseId);
  if (!p) return;
  p.status = ev.status; p.durationMs = ev.durationMs;
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
  // Refresh snapshot list so a new backup shows up immediately.
  refreshSnapshots();
}

function computeOverallPct() {
  if (!activeRun) return 0;
  const total = activeRun.phases.size || 1;
  let sum = 0;
  for (const [, p] of activeRun.phases) {
    if (p.status === 'ok' || p.status === 'failed') sum += 100;
    else if (p.total > 0) sum += Math.min(100, 100 * p.current / p.total);
    else sum += 50;
  }
  return Math.min(100, sum / total);
}

function renderRun() {
  if (!activeRun) return;
  const heading = activeRun.taskId === 'backup.run_now'
      ? 'Backing up user data'
      : `Restoring ${activeRun.snapshotName || 'snapshot'}`;
  const statusLabel = activeRun.taskStatus === 'running' ? 'running'
      : activeRun.taskStatus === 'ok' ? 'complete'
      : activeRun.taskStatus;

  const phasesHTML = Array.from(activeRun.phases.entries()).map(([, p]) => {
    const icon = p.status === 'running' ? '<span class="bk-run-spinner"></span>'
              : p.status === 'ok'      ? '✓'
              : p.status === 'failed'  ? '✗' : '○';
    const dur = p.durationMs != null ? formatMs(p.durationMs) : '';
    const bar = p.status === 'running'
        ? (p.total > 0
            ? `<div class="bk-phase-bar"><div class="bk-phase-bar-fill" style="width:${Math.floor(100 * p.current / p.total)}%"></div></div>`
            : `<div class="bk-phase-bar"><div class="bk-phase-bar-indet"></div></div>`)
        : '';
    return `
      <div class="bk-run-phase ${p.status}">
        <div class="bk-run-phase-icon">${icon}</div>
        <div>
          <div class="bk-run-phase-label">${esc(p.label)}</div>
          ${p.detail ? `<div class="bk-run-phase-detail">${esc(p.detail)}</div>` : ''}
          ${bar}
        </div>
        <div class="bk-run-phase-duration">${esc(dur)}</div>
      </div>
    `;
  }).join('');

  const actions = activeRun.taskStatus === 'running'
      ? ''
      : `<div class="bk-run-actions"><button type="button" id="bk-run-done">Done</button></div>`;

  runEl().innerHTML = `
    <div class="bk-run-head">
      <span>${esc(heading)}</span>
      <span class="bk-run-status ${activeRun.taskStatus}">${esc(statusLabel)}</span>
    </div>
    <div class="bk-run-phases">${phasesHTML}</div>
    ${activeRun.taskSummary ? `<div class="bk-run-summary">${esc(activeRun.taskSummary)}</div>` : ''}
    ${actions}
  `;

  if (activeRun.taskStatus !== 'running') {
    document.getElementById('bk-run-done').addEventListener('click', () => {
      const wasRestore = activeRun.taskId === 'backup.restore';
      activeRun = null;
      if (wasRestore && selectedName) showDetail(selectedName);
      else if (selectedName) showDetail(selectedName);
      else showEmpty();
    });
  }
}

function formatMs(ms) {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  const s = Math.floor(ms / 1000);
  return Math.floor(s / 60) + 'm ' + (s % 60) + 's';
}

// ── Wiring ────────────────────────────────────────────────────────────────

backupNowBtn().addEventListener('click', startBackupNow);

taskCenter.subscribe(() => {
  if (viewEl().style.display !== 'none') {
    backupNowBtn().disabled = !!taskCenter.isRunning();
    if (detailEl().style.display !== 'none' && selectedName) showDetail(selectedName);
  }
});
