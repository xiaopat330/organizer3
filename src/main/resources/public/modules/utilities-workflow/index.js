// utilities-workflow/index.js — v1 Enrichment hub → Workflow subtab.
//
// Lifecycle (v1 convention, mirrors utilities-ai-assist.js):
//   showWorkflowView({ focusId }) → lazy-mount table into #ehub-workflow-subview,
//     load rows, start 3s polling. focusId (optional) scrolls a row into view and
//     flashes a highlight once after the first render that contains it.
//   hideWorkflowView()            → stop polling, mark inactive (guards late fetches).
//
// Forked from modules/v2/workflow/index.js. Differences from v2:
//   - no URL ?focus param — focusId is passed in by the hub.
//   - dropped v2 .wb-* workbench wrapper classes; uses .wf1-* + v1 primitives.
//   - dropped the in-mount section title (the hub section header supplies it).
//   - polling teardown + active-guard for the embedded-tab lifecycle.

import { makeRow } from './row.js';
import { handleBulkAssist, handleApplyAgreed } from './actions.js';

const POLL_INTERVAL_MS = 3000;

let _root      = null;
let _tableBody = null;
let _emptyEl   = null;
let _kpiEl     = null;
let _bulkBtn   = null;
let _applyBtn  = null;
let _pollTimer = null;
let _rows      = [];
let _mounted   = false;
let _active    = false;   // guards late fetches/renders resolving after hide()

// Deep-link focus (handed in by the hub from AI Assist's "pending apply"). Consumed
// once after the first render that contains the target row; never re-triggered.
let _focusId       = null;
let _focusConsumed = true;

function isActive() { return _active; }

function buildShell() {
  _root.innerHTML = `
    <div class="wf1-page">
      <div class="wf1-header">
        <div class="wf1-header-kpis" id="wf1-kpis"></div>
        <button type="button" class="btn sm" id="wf1-bulk-btn" disabled>Queue all ambiguous</button>
        <button type="button" class="btn sm" id="wf1-apply-agreed-btn" disabled>Apply all agreed</button>
      </div>
      <div class="wf1-table-wrap">
        <table class="wf1-table">
          <thead>
            <tr>
              <th>Code</th>
              <th>Actresses</th>
              <th class="wf1-col-state">State</th>
              <th>Cover</th>
              <th class="wf1-col-candidates">Candidates</th>
              <th class="wf1-col-judges">AI Vote</th>
              <th></th>
            </tr>
          </thead>
          <tbody id="wf1-tbody"></tbody>
        </table>
        <div class="wf1-empty" id="wf1-empty" style="display:none">Nothing in the workflow queue.</div>
      </div>
    </div>
  `;

  _tableBody = _root.querySelector('#wf1-tbody');
  _emptyEl   = _root.querySelector('#wf1-empty');
  _kpiEl     = _root.querySelector('#wf1-kpis');
  _bulkBtn   = _root.querySelector('#wf1-bulk-btn');
  _applyBtn  = _root.querySelector('#wf1-apply-agreed-btn');

  _bulkBtn.addEventListener('click', () => handleBulkAssist(_bulkBtn, reload));
  _applyBtn.addEventListener('click', () => handleApplyAgreed(_applyBtn, reload, isActive));
}

// ── Lifecycle ───────────────────────────────────────────────────────────────

export async function showWorkflowView({ focusId = null } = {}) {
  _root = _root || document.getElementById('ehub-workflow-subview');
  if (!_root) return;
  _active = true;
  _root.style.display = '';

  if (!_mounted) {
    buildShell();
    _mounted = true;
  }

  // Set up deep-link focus for this show.
  _focusId       = focusId || null;
  _focusConsumed = !_focusId;

  await reload();
  if (!_active) return;   // hidden while the first load was in flight
  if (_pollTimer) clearInterval(_pollTimer);
  _pollTimer = setInterval(reload, POLL_INTERVAL_MS);
}

export function hideWorkflowView() {
  _active = false;
  if (_pollTimer) { clearInterval(_pollTimer); _pollTimer = null; }
  if (_root) _root.style.display = 'none';
}

// ── Data loading ──────────────────────────────────────────────────────────────

async function reload() {
  if (!_active) return;
  // Visibility self-guard: the v1 SPA hides top-level views with display:none
  // WITHOUT calling hideWorkflowView (e.g. a code-link click that opens
  // title-detail). offsetParent === null ⇒ an ancestor is display:none (reliable:
  // the subview is a normal block element, not position:fixed). Stop the poll loop
  // and null the timer before issuing any fetch; showWorkflowView restarts a single
  // timer on re-entry (it clears _pollTimer first), so this never double-registers.
  if (!_root || _root.offsetParent === null) { hideWorkflowView(); return; }
  try {
    const res = await fetch('/api/enrichment/workflow/rows?limit=200');
    if (!res.ok) throw new Error(await res.text());
    const rows = await res.json();
    if (!_active) return;   // hidden while the fetch was in flight
    _rows = rows;
    render();
  } catch (err) {
    console.error('[workflow] load failed', err);
  }
  if (_active) refreshApplyAgreedBtn();
}

// Refresh the "Apply all agreed" button label/disabled state from the shared
// AI-assist endpoints. handleApplyAgreed owns the label during a run it started
// (dataset.busy), so we skip relabeling then.
async function refreshApplyAgreedBtn() {
  if (!_applyBtn) return;
  if (_applyBtn.dataset.busy === '1') return;
  try {
    const [dash, status] = await Promise.all([
      fetch('/api/enrichment/assist/dashboard').then(r => r.ok ? r.json() : null).catch(() => null),
      fetch('/api/enrichment/assist/apply-agreed/status').then(r => r.ok ? r.json() : null).catch(() => null),
    ]);
    if (!_active) return;
    const n = dash && dash.agreedPending != null ? Number(dash.agreedPending) : 0;
    const running = !!(status && status.running);
    if (running) {
      const applied = Number(status.applied || 0) + Number(status.failed || 0);
      _applyBtn.textContent = `Applying… ${applied}/${Number(status.total || 0)}`;
      _applyBtn.disabled = true;
    } else {
      _applyBtn.textContent = `Apply all agreed (${n})`;
      _applyBtn.disabled = n === 0;
    }
  } catch (err) {
    console.warn('[workflow] apply-agreed refresh failed', err);
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
  maybeApplyFocus();
}

// Deep-link focus: scroll the targeted row into view and flash a highlight, once.
// If the row isn't present (already resolved / filtered out), consume the intent
// silently so we don't keep scanning on every poll.
function maybeApplyFocus() {
  if (_focusConsumed || !_focusId || !_tableBody) return;
  _focusConsumed = true;
  const tr = _tableBody.querySelector(`tr[data-id="${CSS.escape(String(_focusId))}"]`);
  if (!tr) return; // row gone; nothing to do
  tr.scrollIntoView({ block: 'center', behavior: 'smooth' });
  tr.classList.add('wf1-row-focused');
  setTimeout(() => tr.classList.remove('wf1-row-focused'), 2500);
}
