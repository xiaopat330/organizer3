// v2/workflow/index.js — Enrichment Workflow surface: mount, polling, render glue.
// Entry point: call mountWorkflow(rootEl) from the HTML page.

import { makeRow }        from './row.js';
import { handleBulkAssist } from './actions.js';

const POLL_INTERVAL_MS = 3000;

let _tableBody = null;
let _emptyEl   = null;
let _kpiEl     = null;
let _bulkBtn   = null;
let _pollTimer = null;
let _rows      = [];

export async function mountWorkflow(rootEl) {
  rootEl.innerHTML = `
    <div class="wf-page wb-page">
      <div class="wf-header">
        <span class="wf-header-title wb-section-title">Enrichment Workflow</span>
        <div class="wf-header-kpis" id="wf-kpis"></div>
        <button type="button" class="btn sm" id="wf-bulk-btn" disabled>Queue all ambiguous</button>
      </div>
      <div class="wf-table-wrap wb-table-wrap">
        <table class="wf-table wb-table">
          <thead>
            <tr>
              <th>Code</th>
              <th>Actresses</th>
              <th class="wf-col-state">State</th>
              <th>Cover</th>
              <th class="wf-col-candidates">Candidates</th>
              <th class="wf-col-judges">AI Vote</th>
              <th></th>
            </tr>
          </thead>
          <tbody id="wf-tbody"></tbody>
        </table>
        <div class="wf-empty" id="wf-empty" style="display:none">Nothing in the workflow queue.</div>
      </div>
    </div>
  `;

  _tableBody = rootEl.querySelector('#wf-tbody');
  _emptyEl   = rootEl.querySelector('#wf-empty');
  _kpiEl     = rootEl.querySelector('#wf-kpis');
  _bulkBtn   = rootEl.querySelector('#wf-bulk-btn');

  _bulkBtn.addEventListener('click', () => handleBulkAssist(_bulkBtn, reload));

  await reload();
  _pollTimer = setInterval(reload, POLL_INTERVAL_MS);
}

// ── Data loading ──────────────────────────────────────────────────────────────

async function reload() {
  try {
    const res = await fetch('/api/enrichment/workflow/rows?limit=200');
    if (!res.ok) throw new Error(await res.text());
    _rows = await res.json();
    render();
  } catch (err) {
    console.error('[workflow] load failed', err);
  }
}

// ── Rendering ─────────────────────────────────────────────────────────────────

function render() {
  renderKpis();
  renderTable();
}

function renderKpis() {
  if (!_kpiEl) return;
  const total        = _rows.length;
  const ambiguous    = _rows.filter(r => r.state === 'ambiguous').length;
  const inconclusive = _rows.filter(r =>
    r.state === 'split_decision' || r.state === 'partial_vote' || r.state === 'no_verdict').length;
  const judging      = _rows.filter(r => r.state === 'judging').length;

  const parts = [`<strong>${total}</strong> open`];
  if (ambiguous    > 0) parts.push(`<strong>${ambiguous}</strong> ambiguous`);
  if (inconclusive > 0) parts.push(`<strong>${inconclusive}</strong> inconclusive`);
  if (judging      > 0) parts.push(`<strong>${judging}</strong> judging`);
  _kpiEl.innerHTML = parts.join(' · ');

  if (_bulkBtn) {
    _bulkBtn.disabled = !_rows.some(r => r.state === 'ambiguous');
  }
}

function renderTable() {
  if (!_tableBody || !_emptyEl) return;
  if (_rows.length === 0) {
    _tableBody.innerHTML = '';
    _emptyEl.style.display = '';
    return;
  }
  _emptyEl.style.display = 'none';
  _tableBody.innerHTML = '';
  for (const row of _rows) {
    _tableBody.appendChild(makeRow(row, reload));
  }
}
