// Utilities → Actress Data screen.
// Two-pane target + operations layout. Left: list of actress YAMLs on the
// classpath with DB-loaded indicator. Right: per-actress detail + operations.
// Atomic task lock, task pill, SSE streaming all reused from task-center.
// See spec/UTILITIES_ACTRESS_DATA.md.

import { esc } from './utils.js';
import * as taskCenter from './task-center.js';

const SELECTION_KEY = 'utilities.actress-data.selection';

const listEl    = () => document.getElementById('ad-list');
const emptyEl   = () => document.getElementById('ad-empty');
const detailEl  = () => document.getElementById('ad-detail');
const runEl     = () => document.getElementById('ad-run');
const viewEl    = () => document.getElementById('tools-actress-data-view');
const loadAllEl = () => document.getElementById('ad-load-all');

function hideAllRightPanes() {
  emptyEl().style.display = 'none';
  detailEl().style.display = 'none';
  runEl().style.display = 'none';
}

let entries = [];
let selectedSlug = null;
let activeRun = null;

export async function showActressDataView() {
  viewEl().style.display = 'flex';
  selectedSlug = localStorage.getItem(SELECTION_KEY);
  await refreshEntries();

  // Resume the run pane if an actress-data task is still running.
  if (activeRun && activeRun.taskStatus === 'running') {
    hideAllRightPanes();
    runEl().style.display = '';
    renderRun();
    return;
  }

  if (selectedSlug && entries.some(e => e.slug === selectedSlug)) {
    showDetail(selectedSlug);
  } else {
    showEmpty();
  }
}

export function hideActressDataView() {
  // Keep EventSource + activeRun alive so the task pill stays accurate while
  // the user is elsewhere in the app.
  viewEl().style.display = 'none';
}

async function refreshEntries() {
  try {
    const res = await fetch('/api/utilities/actress-yamls');
    entries = await res.json();
  } catch (err) {
    console.error('Failed to load actress catalog', err);
    entries = [];
  }
  renderList();
}

function renderList() {
  const ul = listEl();
  ul.innerHTML = '';
  for (const e of entries) {
    const li = document.createElement('li');
    if (e.slug === selectedSlug) li.classList.add('selected');
    li.addEventListener('click', () => {
      selectedSlug = e.slug;
      localStorage.setItem(SELECTION_KEY, e.slug);
      renderList();
      showDetail(e.slug);
    });

    li.innerHTML = `
      <div class="ad-row">
        <div class="ad-row-body">
          <div class="ad-row-title">
            <span class="ad-row-name">${esc(e.canonicalName || e.slug)}</span>
            ${e.loaded ? '<span class="ad-row-check" title="Loaded in database">✓</span>' : ''}
          </div>
          <div class="ad-row-slug">${esc(e.slug)}</div>
          <div class="ad-row-meta">${e.portfolioSize || 0} portfolio entries</div>
        </div>
      </div>
    `;
    ul.appendChild(li);
  }

  // Load All button disables while any task is running.
  loadAllEl().disabled = !!taskCenter.isRunning();
}

function showEmpty() {
  hideAllRightPanes();
  emptyEl().style.display = '';
}

function showDetail(slug) {
  const e = entries.find(x => x.slug === slug);
  if (!e) { showEmpty(); return; }

  hideAllRightPanes();
  const d = detailEl();
  d.style.display = '';

  const p = e.profile || {};
  const studios = (p.primaryStudios || []).map(s => `<span class="ad-chip">${esc(s)}</span>`).join('');

  d.innerHTML = `
    <div class="ad-detail-head">
      <div class="ad-detail-name">${esc(e.canonicalName || e.slug)}</div>
      <div class="ad-detail-slug">${esc(e.slug)}</div>
    </div>
    <div class="ad-detail-status ${e.loaded ? 'loaded' : 'unloaded'}">
      ${e.loaded
        ? '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg> Loaded in database'
        : 'Not yet loaded in database'}
    </div>
    <div class="ad-detail-grid">
      ${p.dateOfBirth ? `<div><span class="ad-stat-label">Born</span><span class="ad-stat-value">${esc(p.dateOfBirth)}</span></div>` : ''}
      ${p.heightCm ? `<div><span class="ad-stat-label">Height</span><span class="ad-stat-value">${p.heightCm} cm</span></div>` : ''}
      ${p.activeYears ? `<div><span class="ad-stat-label">Active</span><span class="ad-stat-value">${esc(p.activeYears)}</span></div>` : ''}
      <div><span class="ad-stat-label">Portfolio</span><span class="ad-stat-value">${e.portfolioSize || 0} entries</span></div>
    </div>
    ${studios ? `<div class="ad-detail-studios"><span class="ad-stat-label">Primary studios</span><div class="ad-chip-row">${studios}</div></div>` : ''}
    <div class="ad-section">
      <div class="ad-section-heading">Operations</div>
      <button type="button" class="ad-op-primary" id="ad-op-load"${taskCenter.isRunning() ? ' disabled' : ''}>Load</button>
      ${taskCenter.isRunning() ? '<div class="ad-op-blocked">Another utility task is running. Wait for it to finish.</div>' : ''}
    </div>
  `;
  document.getElementById('ad-op-load').addEventListener('click', () => startLoadOne(e.slug));
}

async function startLoadOne(slug) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  try {
    const res = await fetch('/api/utilities/tasks/actress.load_one/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    taskCenter.start({
      taskId: 'actress.load_one',
      runId,
      label: `Loading ${slug}`,
    });
    beginRunView('actress.load_one', slug, runId);
  } catch (err) {
    alert('Failed to start load: ' + err.message);
  }
}

async function startLoadAll() {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  try {
    const res = await fetch('/api/utilities/tasks/actress.load_all/run', {
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
      taskId: 'actress.load_all',
      runId,
      label: 'Loading all actress YAMLs',
    });
    beginRunView('actress.load_all', null, runId);
  } catch (err) {
    alert('Failed to start load-all: ' + err.message);
  }
}

function beginRunView(taskId, slug, runId) {
  if (activeRun?.eventSource) activeRun.eventSource.close();
  activeRun = {
    runId,
    taskId,
    slug,
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
    label: ev.label,
    status: 'running',
    detail: '',
    durationMs: null,
    current: 0,
    total: -1,
  });
  taskCenter.updateProgress({ phaseLabel: ev.label, overallPct: computeOverallPct() });
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
  taskCenter.updateProgress({
    phaseLabel: p.label, overallPct: computeOverallPct(), detail: p.detail,
  });
  renderRun();
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
  // Refresh list so the loaded ✓ flips for this actress.
  refreshEntries();
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
  const heading = activeRun.taskId === 'actress.load_all'
      ? 'Loading all actress YAMLs'
      : `Loading ${activeRun.slug || 'actress'}`;

  const statusLabel = activeRun.taskStatus === 'running' ? 'running'
      : activeRun.taskStatus === 'ok' ? 'complete'
      : activeRun.taskStatus;

  const phasesHTML = Array.from(activeRun.phases.entries()).map(([, p]) => {
    const icon = p.status === 'running' ? '<span class="ad-run-spinner"></span>'
              : p.status === 'ok'      ? '✓'
              : p.status === 'failed'  ? '✗' : '○';
    const dur = p.durationMs != null ? formatMs(p.durationMs) : '';
    const bar = p.status === 'running'
        ? (p.total > 0
            ? `<div class="ad-phase-bar"><div class="ad-phase-bar-fill" style="width:${Math.floor(100 * p.current / p.total)}%"></div></div>`
            : `<div class="ad-phase-bar"><div class="ad-phase-bar-indet"></div></div>`)
        : '';
    return `
      <div class="ad-run-phase ${p.status}">
        <div class="ad-run-phase-icon">${icon}</div>
        <div>
          <div class="ad-run-phase-label">${esc(p.label)}</div>
          ${p.detail ? `<div class="ad-run-phase-detail">${esc(p.detail)}</div>` : ''}
          ${bar}
        </div>
        <div class="ad-run-phase-duration">${esc(dur)}</div>
      </div>
    `;
  }).join('');

  const actions = activeRun.taskStatus === 'running'
      ? ''
      : `<div class="ad-run-actions"><button type="button" id="ad-run-done">Done</button></div>`;

  runEl().innerHTML = `
    <div class="ad-run-head">
      <span>${esc(heading)}</span>
      <span class="ad-run-status ${activeRun.taskStatus}">${esc(statusLabel)}</span>
    </div>
    <div class="ad-run-phases">${phasesHTML}</div>
    ${activeRun.taskSummary ? `<div class="ad-run-summary">${esc(activeRun.taskSummary)}</div>` : ''}
    ${actions}
  `;

  if (activeRun.taskStatus !== 'running') {
    document.getElementById('ad-run-done').addEventListener('click', () => {
      activeRun = null;
      if (selectedSlug) showDetail(selectedSlug); else showEmpty();
    });
  }
}

function formatMs(ms) {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  const s = Math.floor(ms / 1000);
  return Math.floor(s / 60) + 'm ' + (s % 60) + 's';
}

// Load All button is always reachable in the header.
loadAllEl().addEventListener('click', startLoadAll);

// Re-render detail when task-center state flips, so the Load button's disabled
// state stays current even when the state change originates elsewhere.
taskCenter.subscribe(() => {
  if (viewEl().style.display !== 'none') {
    loadAllEl().disabled = !!taskCenter.isRunning();
    if (detailEl().style.display !== 'none' && selectedSlug) {
      showDetail(selectedSlug);
    }
  }
});
