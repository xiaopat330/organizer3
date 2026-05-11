/* ─────────────────────────────────────────────────────────────────────
   v2/enrichment/index.js — Enrichment Review Queue workbench.
   Entry point; call mountEnrichmentReview(rootEl) from the HTML page.

   What's here vs legacy:
     PORTED  — Full queue list with per-reason pill filters, cover lightbox
               (self-contained — no external DOM dependency), all 8 row-reason
               variants (cast_anomaly, ambiguous, no_match, fetch_failed,
               orphan_enriched, recode_candidate, actress_rename_candidate,
               slug_conflict), all action buttons and inline sub-panels
               (picker panel with candidate cards + reference card,
               cast-anomaly alias panel, recode-title hint row, slug-override
               form), doPickCandidate, doRefreshCandidates, doForceEnrich,
               confirmOrphanDelete, doAddAlias, resolveRow, row-highlight
               focusReviewItem() export, resolver source label mapping.
     DEFERRED — None. All legacy features ported.
   ───────────────────────────────────────────────────────────────────── */

import { renderPills, renderTable } from './queue.js';

// Module-level state — one instance per page mount.
const state = {
  activeReason: null,   // null = All
  counts: {},
  rows: [],
};

let _tableBody = null;
let _emptyEl   = null;
let _headerEl  = null;
let _pillsEl   = null;

/**
 * Mount the Enrichment Review workbench into rootEl.
 * Called once from v2-enrichment.html.
 *
 * @param {HTMLElement} rootEl
 */
export async function mountEnrichmentReview(rootEl) {
  rootEl.innerHTML = `
    <div class="er-wb wb-page">
      <div class="er-wb-head">
        <div class="er-title-group">
          <span class="er-page-title">Enrichment Review</span>
          <span class="er-kpi-strip" id="er-kpi-strip">loading…</span>
        </div>
        <div class="er-pills" id="er-pills"></div>
      </div>

      <div class="er-table-wrap wb-table-wrap">
        <table class="er-table wb-table">
          <thead>
            <tr>
              <th class="er-col-code">Code</th>
              <th class="er-col-slug">Slug</th>
              <th class="er-col-reason">Reason</th>
              <th class="er-col-source">Source</th>
              <th class="er-col-created">Age</th>
              <th class="er-col-actions">Actions</th>
            </tr>
          </thead>
          <tbody id="er-table-body"></tbody>
        </table>
        <div class="er-empty" id="er-empty" style="display:none">◌<br>Nothing to review.</div>
      </div>
    </div>
  `;

  _headerEl  = rootEl.querySelector('#er-kpi-strip');
  _pillsEl   = rootEl.querySelector('#er-pills');
  _tableBody = rootEl.querySelector('#er-table-body');
  _emptyEl   = rootEl.querySelector('#er-empty');

  await reload();
}

/**
 * Exported: scroll to and highlight a queue row by ID (used for deep-linking).
 * @param {number|string} id
 */
export function focusReviewItem(id) {
  if (!_tableBody) return;
  const row = _tableBody.querySelector(`tr[data-id="${id}"]`);
  if (!row) return;
  row.scrollIntoView({ block: 'center', behavior: 'smooth' });
  row.classList.add('er-row-highlight');
  setTimeout(() => row.classList.remove('er-row-highlight'), 2000);
}

// ── Data loading ──────────────────────────────────────────────────────────────

async function reload() {
  if (_headerEl) _headerEl.textContent = 'loading…';
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
    if (_headerEl) _headerEl.textContent = 'failed to load';
    console.error('EnrichmentReview: load failed', err);
  }
}

function totalOpen() {
  return Object.values(state.counts).reduce((a, b) => a + b, 0);
}

function oldestDays() {
  let max = 0;
  state.rows.forEach(r => {
    if (r.createdAt) {
      const d = Math.floor((Date.now() - new Date(r.createdAt).getTime()) / 86400000);
      if (d > max) max = d;
    }
  });
  return max;
}

function render() {
  const total = totalOpen();
  if (_headerEl) {
    if (total === 0) {
      _headerEl.textContent = '0 open';
    } else {
      const oldest = oldestDays();
      const parts = [`${total} open`];
      if (oldest > 0) parts.push(`oldest: ${oldest}d ago`);
      _headerEl.textContent = parts.join(' · ');
    }
  }
  if (_pillsEl)  renderPills(state, _pillsEl, reload);
  if (_tableBody && _emptyEl) renderTable(state, _tableBody, _emptyEl, reload);
}
