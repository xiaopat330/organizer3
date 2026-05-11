/* ─────────────────────────────────────────────────────────────────────
   unprocessed/index.js — mount entry point.

   Wave 1  — Page chrome, KPI strip, Bulk Enrich button (count only),
             sidebar queue fully functional.
   Wave 2  — No-draft editor (actress typeahead, descriptor, cover,
             tag panel, Save flow, duplicate-mode, enrich button).
   Wave 3  — Draft metadata block, scratch cover, Validate/Promote/Discard.
   Wave 4  — Cast picker, stage-name translation polling.
   Wave 5  — Bulk Enrich modal + task-center SSE wiring.
   ───────────────────────────────────────────────────────────────────── */

import { mountQueue }       from './queue.js';
import { createState, buildEditorState } from './state.js';
import { mountEditor }      from './editor.js';

/**
 * Mount the Unprocessed workbench into rootEl.
 * @param {HTMLElement} rootEl
 */
export async function mountUnprocessed(rootEl) {
  // ── Shared state ─────────────────────────────────────────────────────
  const state = createState();

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

  // ── Rail badge ────────────────────────────────────────────────────────
  const railBadge = document.getElementById('rail-un-count');

  // ── KPI + bulk-enrich counts ──────────────────────────────────────────
  let _pending        = 0;
  let _bulkCandidates = 0;

  function updateKpi() {
    kpiEl.textContent = `${_pending} pending`;
  }

  function updateBulkEnrichBtn() {
    if (_bulkCandidates === 0) {
      bulkEnrichBtn.style.display = 'none';
    } else {
      bulkEnrichBtn.style.display = '';
      bulkEnrichBtn.textContent = `Enrich ${_bulkCandidates} visible`;
    }
    if (railBadge) {
      if (_pending > 0) {
        railBadge.textContent = String(_pending);
        railBadge.style.display = '';
      } else {
        railBadge.style.display = 'none';
      }
    }
  }

  // ── Tags catalog (fetched once; shared by editor pane) ────────────────
  async function ensureTagsCatalog() {
    if (state.tagsCatalog) return;
    try {
      const res = await fetch('/api/tags');
      if (res.ok) state.tagsCatalog = await res.json();
    } catch (err) {
      console.warn('[unprocessed] tags catalog fetch failed', err);
    }
  }

  // ── Editor handle ─────────────────────────────────────────────────────
  let _editorHandle = null;

  function destroyEditor() {
    _editorHandle?.destroy();
    _editorHandle = null;
  }

  // ── loadDetail — fetch detail + draft in parallel ─────────────────────
  async function loadDetail(titleId) {
    await ensureTagsCatalog();
    try {
      const [detailRes, draftRes] = await Promise.all([
        fetch(`/api/unsorted/titles/${titleId}`),
        fetch(`/api/drafts/${titleId}`),
      ]);

      if (!detailRes.ok) {
        console.error('[unprocessed] detail fetch failed', detailRes.status);
        showEmpty();
        return;
      }

      state.detail = await detailRes.json();

      const hasDraft = draftRes.status === 200;
      if (hasDraft) {
        state.draft       = await draftRes.json();
        state.isDraftMode = true;
        state.editorState = null;
      } else {
        state.draft       = null;
        state.isDraftMode = false;
        state.editorState = buildEditorState(state.detail);
      }

      // Tear down old editor, build fresh
      destroyEditor();
      _editorHandle = mountEditor(editorPaneEl, state, {
        onSaveSuccess,
        loadDetail,
        queueReload: () => queue.reload(),
      });
      _editorHandle.renderEditor();

    } catch (err) {
      console.error('[unprocessed] loadDetail error', err);
      showEmpty();
    }
  }

  function showEmpty() {
    destroyEditor();
    state.detail      = null;
    state.draft       = null;
    state.isDraftMode = false;
    state.editorState = null;
    editorPaneEl.innerHTML = `
      <div class="dis-empty">
        <span class="un-empty-glyph">◌</span>
        Select a title to begin curation.
      </div>
    `;
  }

  // ── nextIdAfter — advance-after-save ──────────────────────────────────
  function nextIdAfter(savedId) {
    // Queue visible rows are owned by the queue module; use the state snapshot.
    const visible = state.showComplete ? state.queueRows : state.queueRows.filter(r => !r.complete);
    if (!visible.length) return null;
    const idx = visible.findIndex(r => r.titleId === savedId);
    if (idx < 0) return visible[0].titleId;
    return idx + 1 < visible.length ? visible[idx + 1].titleId : null;
  }

  // ── onSaveSuccess — advance logic ─────────────────────────────────────
  async function onSaveSuccess(savedId, advance) {
    if (advance) {
      const nextId = nextIdAfter(savedId);
      if (nextId != null) {
        state.currentId = nextId;
        queue.setSelectedId(nextId);
        await loadDetail(nextId);
      } else {
        showEmpty();
      }
    } else {
      await loadDetail(savedId);
    }
  }

  // ── Queue mount ──────────────────────────────────────────────────────
  const queue = mountQueue(sidebarEl, {
    onSelect(titleId) {
      // Navigation guard: check dirty state before leaving current title
      if (state.currentId != null && titleId !== state.currentId) {
        if (_editorHandle && !_editorHandle.canNavigateAway()) {
          // Veto: restore selection to current
          queue.setSelectedId(state.currentId);
          return;
        }
      }
      state.currentId = titleId;
      loadDetail(titleId);
    },
    onCountsChange({ pending, bulkCandidates, queueRows, showComplete }) {
      _pending        = pending;
      _bulkCandidates = bulkCandidates;
      // Keep state in sync for nextIdAfter
      if (queueRows)  state.queueRows  = queueRows;
      if (showComplete !== undefined) state.showComplete = showComplete;
      updateKpi();
      updateBulkEnrichBtn();
    },
  });

  // ── Bulk Enrich button ────────────────────────────────────────────────
  // Wave 1: render + count only. Modal wired in Wave 5.
  bulkEnrichBtn.addEventListener('click', () => {
    console.info('[unprocessed] Bulk Enrich modal — wired in Wave 5');
  });
}
