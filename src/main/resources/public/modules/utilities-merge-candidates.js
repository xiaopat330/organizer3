// Utilities → Merge Candidates
// Lists title-code pairs that share a base_code. User picks MERGE (with a winner) or DISMISS.
// "Detect" and "Execute Merges" are background tasks triggered via the task center API.

import { esc } from './utils.js';
import { updateBreadcrumb } from './grid.js';
import * as taskCenter from './task-center.js';

// ── DOM ───────────────────────────────────────────────────────────────────────
const viewEl     = () => document.getElementById('tools-merge-candidates-view');
const headlineEl = () => document.getElementById('mc-headline');
const listEl     = () => document.getElementById('mc-list');
const detectBtn  = () => document.getElementById('mc-detect-btn');
const executeBtn = () => document.getElementById('mc-execute-btn');

// ── State ─────────────────────────────────────────────────────────────────────
let candidates = [];

// ── Public API ────────────────────────────────────────────────────────────────

export async function showMergeCandidatesView() {
  viewEl().style.display = 'flex';
  updateBreadcrumb([{ label: 'Tools' }, { label: 'Merge Candidates' }]);
  await reload();
}

export function hideMergeCandidatesView() {
  viewEl().style.display = 'none';
}

export function wireMergeCandidatesEvents() {
  detectBtn().addEventListener('click', () => runTask('duplicates.detect_merge_candidates', 'Detect merge candidates'));
  executeBtn().addEventListener('click', () => runTask('duplicates.execute_merge', 'Execute merges'));
}

// ── Task trigger ──────────────────────────────────────────────────────────────

async function runTask(taskId, label) {
  if (taskCenter.isRunning()) return;
  let res;
  try {
    res = await fetch(`/api/utilities/tasks/${taskId}/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    });
  } catch (err) {
    alert('Failed to start task: ' + err.message);
    return;
  }
  const data = await res.json();
  if (!res.ok) {
    alert(data.error || 'Could not start task — another task may be running');
    return;
  }
  const runId = data.runId;
  taskCenter.start({ taskId, runId, label });
  subscribeToRun(runId);
}

function subscribeToRun(runId) {
  const src = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);

  src.onmessage = e => {
    let ev;
    try { ev = JSON.parse(e.data); } catch { return; }

    if (ev.type === 'PhaseStarted') {
      taskCenter.updateProgress({ phaseLabel: ev.label });
    } else if (ev.type === 'PhaseProgress' && ev.total > 0) {
      taskCenter.updateProgress({ overallPct: Math.round((ev.current / ev.total) * 100) });
    } else if (ev.type === 'TaskEnded') {
      taskCenter.finish({ status: ev.status, summary: ev.summary });
      src.close();
      reload();
    }
  };

  src.onerror = () => {
    src.close();
    taskCenter.finish({ status: 'failed', summary: 'Connection lost' });
  };
}

// ── Data ──────────────────────────────────────────────────────────────────────

async function reload() {
  headlineEl().textContent = 'Loading…';
  listEl().innerHTML = '<div class="mc-loading">Fetching candidates…</div>';
  try {
    const res = await fetch('/api/tools/merge-candidates');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    candidates = await res.json();
    render();
  } catch (err) {
    headlineEl().textContent = 'Failed to load candidates.';
    listEl().innerHTML = '';
    console.error('Merge candidates load error', err);
  }
}

// ── Render ────────────────────────────────────────────────────────────────────

function render() {
  const n = candidates.length;
  headlineEl().textContent = n === 0 ? 'No pending merge candidates'
    : n === 1 ? '1 pending merge candidate'
    : `${n} pending merge candidates`;

  if (n === 0) {
    listEl().innerHTML = '<div class="mc-empty">All caught up — no pending decisions.</div>';
    return;
  }

  listEl().innerHTML = candidates.map(renderCard).join('');

  // Wire button events
  listEl().querySelectorAll('.mc-card').forEach(card => {
    const id = Number(card.dataset.id);
    const codeA = card.dataset.codeA;
    const codeB = card.dataset.codeB;

    card.querySelector('.mc-dismiss-btn').addEventListener('click', () => decide(id, 'DISMISS', null));
    card.querySelector('.mc-merge-a-btn').addEventListener('click', () => decide(id, 'MERGE', codeA));
    card.querySelector('.mc-merge-b-btn').addEventListener('click', () => decide(id, 'MERGE', codeB));
  });
}

function renderCard(c) {
  const badgeClass = c.confidence === 'code-normalization' ? 'mc-badge--norm' : 'mc-badge--variant';
  const badgeLabel = c.confidence === 'code-normalization' ? 'code normalization' : 'variant suffix';

  return `
  <div class="mc-card" data-id="${c.id}" data-code-a="${esc(c.titleCodeA)}" data-code-b="${esc(c.titleCodeB)}">
    <div class="mc-pair">
      <span class="mc-code">${esc(c.titleCodeA)}</span>
      <span class="mc-pair-sep">↔</span>
      <span class="mc-code">${esc(c.titleCodeB)}</span>
    </div>
    <span class="mc-badge ${badgeClass}">${esc(badgeLabel)}</span>
    <div class="mc-btns">
      <button class="mc-merge-a-btn mc-btn-merge" title="Keep ${esc(c.titleCodeA)}, delete ${esc(c.titleCodeB)}">
        Merge → ${esc(c.titleCodeA)}
      </button>
      <button class="mc-merge-b-btn mc-btn-merge" title="Keep ${esc(c.titleCodeB)}, delete ${esc(c.titleCodeA)}">
        Merge → ${esc(c.titleCodeB)}
      </button>
      <button class="mc-dismiss-btn mc-btn-dismiss">Dismiss</button>
    </div>
  </div>`;
}

// ── Decision ──────────────────────────────────────────────────────────────────

async function decide(id, decision, winnerCode) {
  try {
    const body = decision === 'MERGE' ? { decision, winnerCode } : { decision };
    const res = await fetch(`/api/tools/merge-candidates/${id}/decision`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error(await res.text());
    // Remove from local list and re-render
    candidates = candidates.filter(c => c.id !== id);
    render();
  } catch (err) {
    console.error('Failed to save decision', id, err);
    alert('Failed to save decision: ' + err.message);
  }
}
