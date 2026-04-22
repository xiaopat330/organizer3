// Global task state + floating pill. One active Utilities task at a time
// (single-user app). The pill lives outside any screen so navigating away
// from Volumes doesn't lose track of a running sync; clicking it navigates
// back to the run view.
//
// Other modules interact via:
//   start({ taskId, runId, label })           — register an active run
//   subscribe(listener)                       — receive state updates
//   getActive()                               — read current state
//   onOpenRequested(callback)                 — called when user clicks pill
//   updateProgress({ phaseId, phaseLabel,
//                    overallPct, detail })    — update pill content
//   finish({ status, summary })               — mark the run terminal

import { esc } from './utils.js';

let active = null;            // { taskId, runId, label, phaseLabel, overallPct, detail, status }
const listeners = new Set();
let openHandler = null;
let pillEl = null;

function ensurePill() {
  if (pillEl) return pillEl;
  pillEl = document.createElement('div');
  pillEl.id = 'task-pill';
  pillEl.className = 'task-pill';
  pillEl.style.display = 'none';
  pillEl.addEventListener('click', () => { if (openHandler) openHandler(active); });
  document.body.appendChild(pillEl);
  return pillEl;
}

function renderPill() {
  const el = ensurePill();
  if (!active) { el.style.display = 'none'; el.innerHTML = ''; return; }
  const pct = typeof active.overallPct === 'number' ? Math.floor(active.overallPct) : null;
  const statusCls = active.status || 'running';
  el.className = 'task-pill ' + statusCls;
  el.style.display = '';
  el.innerHTML = `
    <span class="task-pill-spinner"></span>
    <span class="task-pill-label">${esc(active.label || 'Task')}</span>
    ${active.phaseLabel ? `<span class="task-pill-phase">· ${esc(active.phaseLabel)}</span>` : ''}
    ${pct != null ? `<span class="task-pill-pct">${pct}%</span>` : ''}
    <div class="task-pill-bar"><div class="task-pill-bar-fill" style="width:${pct != null ? pct : 0}%"></div></div>
  `;
}

function notify() { for (const l of listeners) l(active); renderPill(); }

export function start({ taskId, runId, label }) {
  active = { taskId, runId, label, phaseLabel: '', overallPct: 0, detail: '', status: 'running' };
  notify();
}

export function updateProgress(partial) {
  if (!active) return;
  Object.assign(active, partial);
  notify();
}

export function finish({ status, summary } = {}) {
  if (!active) return;
  active.status = status || 'ok';
  active.summary = summary || '';
  renderPill();
  // Keep the pill visible briefly on success/failure so user sees the final state,
  // then clear it. Cancellation (active set to null) is allowed to short-circuit.
  setTimeout(() => {
    if (active && active.status !== 'running') {
      active = null;
      notify();
    }
  }, 4000);
}

export function getActive() { return active; }
export function isRunning() { return active != null && active.status === 'running'; }
export function subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); }
export function onOpenRequested(cb) { openHandler = cb; }
