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

const POLL_INTERVAL_MS = 5000;

// Selector matching "a sub-panel is currently open" — inline sub-panels are
// inserted as sibling <tr> rows (picker, cast-anomaly alias, recode preview)
// or appended inside the actions cell (slug-override form). If any of these is
// present a background refresh must bail so it doesn't collapse a mid-edit panel.
const OPEN_PANEL_SELECTOR =
  '.er-picker-row, .er-cast-anomaly-row, .er-recode-preview-row, .er-slug-form';

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
let _pollTimer = null;
let _visWired  = false;

function onVisibilityChange() {
  if (document.visibilityState === 'visible') reload({ background: true });
}

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

  // Clear any stale timer from a previous mount so re-mounts don't stack polls.
  if (_pollTimer) clearInterval(_pollTimer);

  await reload();

  // Deep-link: if ?focus=<id> is present, scroll to + highlight that row once
  // on mount. The initial reload uses the default (All) view with no reason
  // filter, so unresolved rows are present in the DOM.
  const focusId = new URLSearchParams(location.search).get('focus');
  if (focusId) focusReviewItem(focusId);

  // Background auto-refresh — silent, and skips ticks while a panel is open.
  _pollTimer = setInterval(() => reload({ background: true }), POLL_INTERVAL_MS);

  // Refresh immediately when the tab regains visibility. Guard against
  // double-registration across re-mounts via the named handler + flag.
  if (!_visWired) {
    document.addEventListener('visibilitychange', onVisibilityChange);
    _visWired = true;
  }
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

async function reload({ background = false } = {}) {
  if (background) {
    // Skip this tick if any inline sub-panel is open (mid-edit) — a silent
    // re-render would collapse the picker/alias/recode/slug-form.
    if (_tableBody && _tableBody.querySelector(OPEN_PANEL_SELECTOR)) return;
  } else {
    if (_headerEl) _headerEl.textContent = 'loading…';
  }
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
    if (!background && _headerEl) _headerEl.textContent = 'failed to load';
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
