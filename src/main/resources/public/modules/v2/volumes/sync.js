// volumes/sync.js — Sync subsystem: single-volume sync, coherent sync, and
// the SSE-based run view with phase-by-phase progress.
// Mirrors utilities-volumes.js run-view logic verbatim; endpoints unchanged.

import * as taskCenter from '../../task-center.js';
import { esc } from './cards.js';

// ── Active run state ──────────────────────────────────────────────────────────
// Exported so index.js can inspect / share it.
export let activeRun = null; // { runId, volumeId, taskId, eventSource, phases, taskStatus, taskSummary }

export function setActiveRun(run) { activeRun = run; }

// ── Label helpers ─────────────────────────────────────────────────────────────
export function labelFor(taskId) {
  if (taskId === 'volume.sync') return 'Syncing volume';
  if (taskId === 'volume.clean_stale_locations') return 'Cleaning stale locations';
  if (taskId === 'volume.sync_coherent') return 'Coherent sync (all volumes)';
  return taskId;
}

// ── Sync start actions ────────────────────────────────────────────────────────

export async function startSync(volumeId, onBeginRun) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running. Wait for it to finish before starting a new sync.');
    return;
  }
  try {
    const res = await fetch('/api/utilities/tasks/volume.sync/run', {
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
    taskCenter.start({ taskId: 'volume.sync', runId, label: `Syncing Volume ${volumeId.toUpperCase()}` });
    beginRunView(volumeId, runId, 'volume.sync', onBeginRun);
  } catch (err) {
    alert('Failed to start sync: ' + err.message);
  }
}

export async function confirmAndStartCoherentSync(onBeginRun) {
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
    taskCenter.start({ taskId: 'volume.sync_coherent', runId, label: 'Coherent sync (all volumes)' });
    beginRunView(null, runId, 'volume.sync_coherent', onBeginRun);
  } catch (err) {
    alert('Failed to start coherent sync: ' + err.message);
  }
}

// ── Run view bootstrapping ────────────────────────────────────────────────────

export function beginRunView(volumeId, runId, taskId, onBeginRun) {
  if (activeRun?.eventSource) activeRun.eventSource.close();

  activeRun = {
    runId,
    volumeId,
    taskId: taskId || 'volume.sync',
    eventSource: null,
    phases: new Map(),
    taskStatus: 'running',
    taskSummary: '',
  };

  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  activeRun.eventSource = es;

  es.addEventListener('task.started',   () => { if (onBeginRun) onBeginRun(); });
  es.addEventListener('phase.started',  e => handlePhaseStarted(JSON.parse(e.data), onBeginRun));
  es.addEventListener('phase.progress', e => handlePhaseProgress(JSON.parse(e.data), onBeginRun));
  es.addEventListener('phase.log',      () => { /* raw logs go to organizer3.log */ });
  es.addEventListener('phase.ended',    e => handlePhaseEnded(JSON.parse(e.data), onBeginRun));
  es.addEventListener('task.ended',     e => handleTaskEnded(JSON.parse(e.data), onBeginRun));
  es.onerror = () => {};

  if (onBeginRun) onBeginRun();
}

// ── Phase event handlers ──────────────────────────────────────────────────────

function handlePhaseStarted(ev, onBeginRun) {
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
  if (onBeginRun) onBeginRun();
}

function handlePhaseProgress(ev, onBeginRun) {
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
  taskCenter.updateProgress({ phaseLabel: p.label, overallPct: computeOverallPct(), detail: p.detail });
  if (onBeginRun) onBeginRun();
}

function handlePhaseEnded(ev, onBeginRun) {
  if (!activeRun) return;
  const p = activeRun.phases.get(ev.phaseId);
  if (!p) return;
  p.status = ev.status;
  p.durationMs = ev.durationMs;
  if (ev.summary) p.detail = ev.summary;
  taskCenter.updateProgress({ overallPct: computeOverallPct() });
  if (onBeginRun) onBeginRun();
}

function handleTaskEnded(ev, onBeginRun) {
  if (!activeRun) return;
  activeRun.taskStatus = ev.status;
  activeRun.taskSummary = ev.summary;
  if (activeRun.eventSource) { activeRun.eventSource.close(); activeRun.eventSource = null; }
  taskCenter.finish({ status: ev.status, summary: ev.summary });
  if (onBeginRun) onBeginRun();
}

function computeOverallPct() {
  if (!activeRun) return 0;
  const EXPECTED_PHASES = 4;
  let sum = 0;
  for (const [, p] of activeRun.phases) {
    if (p.status === 'ok' || p.status === 'failed') sum += 100;
    else if (p.total > 0) sum += Math.min(100, (100 * p.current / p.total));
    else sum += 50;
  }
  return Math.min(100, sum / EXPECTED_PHASES);
}

// ── Run view rendering ────────────────────────────────────────────────────────

export function renderRunView(volumes, onDone) {
  if (!activeRun) return '';
  const v = volumes.find(x => x.id === activeRun.volumeId) || { id: activeRun.volumeId };
  const statusLabel = activeRun.taskStatus === 'running'   ? 'running'
                    : activeRun.taskStatus === 'ok'        ? 'complete'
                    : activeRun.taskStatus === 'partial'   ? 'partial'
                    : activeRun.taskStatus === 'cancelled' ? 'cancelled'
                    : 'failed';

  const phasesHTML = Array.from(activeRun.phases.entries()).map(([, p]) => {
    const icon = p.status === 'running' ? '<span class="vol-run-spinner"></span>'
               : p.status === 'ok'     ? '✓'
               : p.status === 'failed' ? '✗'
               : '○';
    const dur = p.durationMs != null ? formatMs(p.durationMs) : '';
    return `
      <div class="vol-run-phase ${esc(p.status)}">
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

  const actions = activeRun.taskStatus !== 'running'
    ? `<div class="vol-run-actions"><button type="button" class="btn" id="vol-run-done">Done</button></div>`
    : '';

  const heading = activeRun.taskId === 'volume.sync_coherent'
    ? 'Coherent sync (all volumes)'
    : activeRun.taskId === 'volume.clean_stale_locations'
    ? `Cleaning stale locations · Volume ${esc((v.id || '').toUpperCase())}`
    : `Syncing Volume ${esc((v.id || '').toUpperCase())}`;

  return `
    <div class="vol-run-head">
      <span>${heading}</span>
      <span class="vol-run-status ${esc(activeRun.taskStatus)}">${esc(statusLabel)}</span>
    </div>
    <div class="vol-run-phases">${phasesHTML}</div>
    ${actions}
  `;
}

export function wireRunDone(runEl, onDone) {
  const btn = runEl.querySelector('#vol-run-done');
  if (!btn) return;
  btn.addEventListener('click', () => {
    const vid = activeRun?.volumeId;
    activeRun = null;
    if (onDone) onDone(vid);
  });
}

function renderPhaseBar(p) {
  if (p.status !== 'running') return '';
  if (p.total > 0 && p.current >= 0) {
    const pct = Math.min(100, Math.max(0, Math.floor(100 * p.current / p.total)));
    return `<div class="vol-phase-bar"><div class="vol-phase-bar-fill" style="width:${pct}%"></div></div>`;
  }
  return `<div class="vol-phase-bar"><div class="vol-phase-bar-indet"></div></div>`;
}

export function formatMs(ms) {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  const s = Math.floor(ms / 1000);
  return Math.floor(s / 60) + 'm ' + (s % 60) + 's';
}
