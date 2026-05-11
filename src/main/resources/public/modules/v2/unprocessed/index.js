/* ─────────────────────────────────────────────────────────────────────
   unprocessed/index.js — Wave 1 mount entry point.

   What's here vs deferred:
     WAVE 1  — Page chrome, KPI strip, Bulk Enrich button (render + count
               only; modal deferred to Wave 5), sidebar queue fully
               functional (load, status markers, DRAFT pills, "Show
               complete" toggle, empty state).
     WAVE 2  — No-draft editor pane (actress typeahead, descriptor, cover,
               tag panel, Save flow).
     WAVE 3  — Draft metadata block, scratch cover, Validate/Promote/Discard.
     WAVE 4  — Cast picker, stage-name translation polling.
     WAVE 5  — Bulk Enrich modal + task-center SSE wiring.

   State is kept inline here; Wave 2 will extract a state.js factory.
   ───────────────────────────────────────────────────────────────────── */

import { mountQueue } from './queue.js';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

/**
 * Mount the Unprocessed workbench into rootEl.
 * @param {HTMLElement} rootEl
 */
export async function mountUnprocessed(rootEl) {
  // ── Page shell ───────────────────────────────────────────────────────
  rootEl.innerHTML = `
    <div class="un-wb">
      <div class="un-wb-head">
        <h1 class="wb-page-title">Unprocessed</h1>
        <div class="wb-page-subtitle">Titles that need curation — assign actresses, cover, and descriptor, or enrich via JavDB.</div>
      </div>

      <div class="un-toolbar">
        <div class="un-toolbar-left">
          <div class="dis-kpi-strip" id="un-kpi"></div>
        </div>
        <div class="un-toolbar-right">
          <button class="btn sm" id="un-bulk-enrich-btn" type="button" style="display:none">
            Enrich 0 visible
          </button>
        </div>
      </div>

      <div class="un-layout">
        <aside class="un-sidebar" id="un-sidebar">
          <div class="un-sidebar-inner">Loading…</div>
        </aside>
        <div class="un-editor-pane" id="un-editor-pane">
          <div class="dis-empty">
            <span class="un-empty-glyph">◌</span>
            Select a title to begin curation.
          </div>
        </div>
      </div>
    </div>
  `;

  const kpiEl         = rootEl.querySelector('#un-kpi');
  const bulkEnrichBtn = rootEl.querySelector('#un-bulk-enrich-btn');
  const sidebarEl     = rootEl.querySelector('#un-sidebar');
  const editorPaneEl  = rootEl.querySelector('#un-editor-pane');

  // ── Rail badge (current page only — cross-page is a future chrome enhancement) ──
  const railBadge = document.getElementById('rail-un-count');

  // ── Counts state ──────────────────────────────────────────────────────
  let _pending        = 0;
  let _bulkCandidates = 0;

  function updateKpi() {
    // KPI strip — updated when queue reports counts
    // Note: "with drafts" count is not yet tracked at index level (Wave 1);
    // surfaced as pending count for now.
    kpiEl.textContent = `${_pending} pending`;
  }

  function updateBulkEnrichBtn() {
    if (_bulkCandidates === 0) {
      bulkEnrichBtn.style.display = 'none';
    } else {
      bulkEnrichBtn.style.display = '';
      bulkEnrichBtn.textContent = `Enrich ${_bulkCandidates} visible`;
    }

    // Update rail badge with pending count
    if (railBadge) {
      if (_pending > 0) {
        railBadge.textContent = String(_pending);
        railBadge.style.display = '';
      } else {
        railBadge.style.display = 'none';
      }
    }
  }

  // ── Queue mount ──────────────────────────────────────────────────────
  const queue = mountQueue(sidebarEl, {
    onSelect(titleId) {
      // Wave 2 will wire the editor pane; for Wave 1 the empty state remains.
      // (No action needed — empty state message stays.)
    },
    onCountsChange({ pending, bulkCandidates }) {
      _pending        = pending;
      _bulkCandidates = bulkCandidates;
      updateKpi();
      updateBulkEnrichBtn();
    },
  });

  // ── Bulk Enrich button ────────────────────────────────────────────────
  // Wave 1: render + count only. Modal wired in Wave 5.
  bulkEnrichBtn.addEventListener('click', () => {
    // Wave 5 will open the preview modal here.
    // For now: no-op stub so the button is clickable without errors.
    console.info('[unprocessed] Bulk Enrich modal — wired in Wave 5');
  });
}
