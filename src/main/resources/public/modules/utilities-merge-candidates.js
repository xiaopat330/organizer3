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
let taskCenterUnsub = null;

// ── Public API ────────────────────────────────────────────────────────────────

export async function showMergeCandidatesView() {
  viewEl().style.display = 'flex';
  updateBreadcrumb([{ label: 'Tools' }, { label: 'Merge Candidates' }]);
  await reload();
  taskCenterUnsub?.();
  taskCenterUnsub = taskCenter.subscribe(onTaskUpdate);
}

export function hideMergeCandidatesView() {
  viewEl().style.display = 'none';
  taskCenterUnsub?.();
  taskCenterUnsub = null;
}

export function wireMergeCandidatesEvents() {
  detectBtn().addEventListener('click', () => runTask('duplicates.detect_merge_candidates'));
  executeBtn().addEventListener('click', () => runTask('duplicates.execute_merge'));
}

// ── Task trigger ──────────────────────────────────────────────────────────────

async function runTask(id) {
  try {
    const res = await fetch(`/api/utilities/tasks/${id}/run`, { method: 'POST',
      headers: { 'Content-Type': 'application/json' }, body: '{}' });
    if (!res.ok) throw new Error(await res.text());
    taskCenter.open();
  } catch (err) {
    console.error('Failed to start task', id, err);
    alert('Failed to start task: ' + err.message);
  }
}

function onTaskUpdate() {
  // Reload candidates after an execute-merge task finishes
  reload();
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
