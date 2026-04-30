// Utilities → Enrichment Review Queue triage page.
// Lists open enrichment_review_queue rows with bucket pills for per-reason filtering.
// Two resolution actions per row: Accept as gap / Mark resolved.

import { esc } from './utils.js';

const view      = document.getElementById('tools-enrichment-review-view');
const headerEl  = document.getElementById('er-header');
const pillsEl   = document.getElementById('er-pills');
const tableBody = document.getElementById('er-table-body');
const emptyEl   = document.getElementById('er-empty');

const ALL_REASONS = ['cast_anomaly', 'ambiguous', 'no_match', 'fetch_failed'];

let state = {
  activeReason: null,   // null = All
  counts: {},
  rows: [],
};

export async function showEnrichmentReviewView() {
  view.style.display = '';
  await reload();
}

export function hideEnrichmentReviewView() {
  view.style.display = 'none';
}

async function reload() {
  headerEl.textContent = 'Enrichment Review Queue — loading…';
  try {
    const params = new URLSearchParams({ limit: 500 });
    if (state.activeReason) params.set('reason', state.activeReason);
    const res = await fetch(`/api/utilities/enrichment-review/queue?${params}`);
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    state.counts = data.counts || {};
    state.rows   = data.rows   || [];
    render();
  } catch (err) {
    headerEl.textContent = 'Enrichment Review Queue — failed to load';
    console.error('EnrichmentReview: load failed', err);
  }
}

function totalOpen() {
  return Object.values(state.counts).reduce((a, b) => a + b, 0);
}

function render() {
  const total = totalOpen();
  headerEl.textContent = `Enrichment Review Queue (${total} open)`;
  renderPills();
  renderTable();
}

function renderPills() {
  pillsEl.innerHTML = '';

  const allBtn = makePill('All', state.activeReason === null, () => {
    state.activeReason = null;
    reload();
  });
  pillsEl.appendChild(allBtn);

  ALL_REASONS.forEach(r => {
    const count = state.counts[r] || 0;
    const btn = makePill(`${r}: ${count}`, state.activeReason === r, () => {
      state.activeReason = r;
      reload();
    });
    pillsEl.appendChild(btn);
  });
}

function makePill(label, selected, onClick) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'er-pill' + (selected ? ' er-pill-selected' : '');
  btn.textContent = label;
  btn.addEventListener('click', onClick);
  return btn;
}

function renderTable() {
  if (state.rows.length === 0) {
    tableBody.innerHTML = '';
    emptyEl.style.display = '';
    return;
  }
  emptyEl.style.display = 'none';
  tableBody.innerHTML = '';
  state.rows.forEach(row => tableBody.appendChild(makeRow(row)));
}

function makeRow(row) {
  const tr = document.createElement('tr');
  tr.className = 'er-row';
  tr.innerHTML = `
    <td class="er-col-code">${esc(row.titleCode || '')}</td>
    <td class="er-col-slug">${esc(row.slug || '—')}</td>
    <td class="er-col-reason"><span class="er-reason er-reason-${esc(row.reason || '')}">${esc(row.reason || '')}</span></td>
    <td class="er-col-source">${esc(row.resolverSource || '—')}</td>
    <td class="er-col-created">${formatRelative(row.createdAt)}</td>
    <td class="er-col-actions">
      <button type="button" class="er-action-btn er-gap-btn" data-id="${row.id}" data-res="accepted_gap">Accept as gap</button>
      <button type="button" class="er-action-btn er-resolve-btn" data-id="${row.id}" data-res="marked_resolved">Mark resolved</button>
    </td>
  `;
  tr.querySelectorAll('.er-action-btn').forEach(btn => {
    btn.addEventListener('click', () => resolveRow(Number(btn.dataset.id), btn.dataset.res, tr));
  });
  return tr;
}

async function resolveRow(id, resolution, tr) {
  tr.querySelectorAll('.er-action-btn').forEach(b => { b.disabled = true; });
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${id}/resolve`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolution }),
    });
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    if (data.ok) {
      tr.remove();
      state.rows = state.rows.filter(r => r.id !== id);
      if (state.counts) {
        // Optimistically decrement the affected reason's count
        const row = state.rows.find ? null : null; // already removed; use full reload for counts
      }
      await reload();
    } else {
      alert(data.message);
      tr.querySelectorAll('.er-action-btn').forEach(b => { b.disabled = false; });
    }
  } catch (err) {
    console.error('EnrichmentReview: resolve failed', err);
    alert('Failed to resolve row: ' + err.message);
    tr.querySelectorAll('.er-action-btn').forEach(b => { b.disabled = false; });
  }
}

function formatRelative(isoStr) {
  if (!isoStr) return '—';
  try {
    const diff = Date.now() - new Date(isoStr).getTime();
    const days = Math.floor(diff / 86400000);
    if (days === 0) return 'Today';
    if (days === 1) return 'Yesterday';
    if (days < 30)  return `${days}d ago`;
    if (days < 365) return `${Math.floor(days / 30)}mo ago`;
    return `${Math.floor(days / 365)}y ago`;
  } catch { return isoStr; }
}
