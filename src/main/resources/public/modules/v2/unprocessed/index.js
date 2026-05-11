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
import * as taskCenter      from '../../task-center.js';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;',
  }[c]));
}

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

  // ── Bulk Enrich button → confirm modal → task dispatch ────────────────
  let _bulkPlan = null;       // last preview result; cached for confirm step
  let _bulkBtnLabel = '';     // remember label to restore on errors

  function visibleEligibleIds() {
    const visible = state.showComplete ? state.queueRows : state.queueRows.filter(r => !r.complete);
    return visible.filter(r => !r.complete).map(r => r.titleId);
  }

  function closeBulkModal() {
    document.querySelector('.un-modal-overlay[data-un-modal="bulk-enrich"]')?.remove();
    _bulkPlan = null;
  }

  function showBulkModal({ titleEl, bodyHtml, confirmLabel, onConfirm }) {
    closeBulkModal();
    const overlay = document.createElement('div');
    overlay.className = 'un-modal-overlay';
    overlay.dataset.unModal = 'bulk-enrich';
    overlay.innerHTML = `
      <div class="un-modal" role="dialog" aria-modal="true" aria-label="Bulk Enrich">
        <div class="un-modal-header">
          <h2 class="un-modal-title">${esc(titleEl)}</h2>
          <button type="button" class="un-modal-close" aria-label="Close">✕</button>
        </div>
        <div class="un-modal-body">${bodyHtml}</div>
        <div class="un-modal-actions">
          <button type="button" class="btn sm" data-act="cancel">Cancel</button>
          ${confirmLabel
            ? `<button type="button" class="btn sm primary" data-act="confirm">${esc(confirmLabel)}</button>`
            : ''}
        </div>
      </div>
    `;
    document.body.appendChild(overlay);

    const ac = new AbortController();
    const close = () => { ac.abort(); overlay.remove(); _bulkPlan = null; };
    overlay.querySelector('.un-modal-close')?.addEventListener('click', close, { signal: ac.signal });
    overlay.querySelector('[data-act="cancel"]')?.addEventListener('click', close, { signal: ac.signal });
    overlay.addEventListener('click', e => { if (e.target === overlay) close(); }, { signal: ac.signal });
    document.addEventListener('keydown', e => { if (e.key === 'Escape') close(); }, { signal: ac.signal });

    const confirmBtn = overlay.querySelector('[data-act="confirm"]');
    if (confirmBtn && onConfirm) {
      confirmBtn.addEventListener('click', async () => {
        confirmBtn.disabled = true;
        const cancelBtn = overlay.querySelector('[data-act="cancel"]');
        if (cancelBtn) cancelBtn.disabled = true;
        try {
          await onConfirm({ close });
        } catch (err) {
          confirmBtn.disabled = false;
          if (cancelBtn) cancelBtn.disabled = false;
          throw err;
        }
      }, { signal: ac.signal });
    }
    return { close };
  }

  bulkEnrichBtn.addEventListener('click', async () => {
    const titleIds = visibleEligibleIds();
    if (titleIds.length === 0) return;

    if (taskCenter.isRunning?.()) {
      alert('Another task is already running. Wait for it to finish before starting Bulk Enrich.');
      return;
    }

    _bulkBtnLabel = bulkEnrichBtn.textContent;
    bulkEnrichBtn.disabled = true;
    bulkEnrichBtn.textContent = 'Checking…';
    try {
      const res = await fetch('/api/drafts/bulk-enrich/preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ titleIds }),
      });
      if (!res.ok) throw new Error('Preview failed: HTTP ' + res.status);
      _bulkPlan = await res.json();

      const { eligibleCount, alreadyDrafted = 0, alreadyCurated = 0 } = _bulkPlan;
      const exclusions = [];
      if (alreadyDrafted > 0) exclusions.push(`${alreadyDrafted} already have drafts`);
      if (alreadyCurated > 0) exclusions.push(`${alreadyCurated} already curated`);
      const exclusionLine = exclusions.length
        ? `<div class="un-modal-note">Excluded: ${esc(exclusions.join(', '))}.</div>`
        : '';

      if (eligibleCount === 0) {
        showBulkModal({
          titleEl: 'Bulk Enrich',
          bodyHtml: `<div>No eligible titles to enrich.</div>${exclusionLine}`,
          confirmLabel: null,
          onConfirm: null,
        });
        return;
      }

      const bodyHtml = `
        <div><strong>${eligibleCount}</strong> title${eligibleCount !== 1 ? 's' : ''}
          will be enriched to draft.</div>
        ${exclusionLine}
        <div class="un-modal-note">The background enrichment runner will be paused while
          the task runs. Progress shows in the global task pill.</div>
      `;

      showBulkModal({
        titleEl: 'Bulk Enrich',
        bodyHtml,
        confirmLabel: `Enrich ${eligibleCount}`,
        onConfirm: async ({ close }) => {
          if (!_bulkPlan || _bulkPlan.eligibleCount === 0) return;
          const eligibleIds = _bulkPlan.eligibleIds;
          const titleIdsJson = JSON.stringify(eligibleIds);
          const startRes = await fetch('/api/utilities/tasks/enrichment.bulk_enrich_to_draft/run', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ titleIds: titleIdsJson }),
          });
          const data = await startRes.json().catch(() => ({}));
          if (!startRes.ok || !data.runId) {
            alert('Failed to start task: ' + (data.error || data.message || startRes.statusText));
            return;
          }
          close();
          taskCenter.start({
            taskId: 'enrichment.bulk_enrich_to_draft',
            runId: data.runId,
            label: 'Bulk Enrich to Draft',
          });
          const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(data.runId)}/events`);
          es.addEventListener('phase.started',  e => {
            const ev = JSON.parse(e.data);
            taskCenter.updateProgress({ phaseLabel: ev.label });
          });
          es.addEventListener('phase.progress', e => {
            const ev = JSON.parse(e.data);
            taskCenter.updateProgress({ overallPct: Math.round((ev.current / ev.total) * 100) });
          });
          es.addEventListener('phase.ended',    e => {
            const ev = JSON.parse(e.data);
            if (ev.status === 'failed') taskCenter.finish({ status: 'failed', summary: ev.summary });
          });
          es.addEventListener('run.ended',      e => {
            const ev = JSON.parse(e.data);
            taskCenter.finish({ status: ev.status, summary: ev.summary });
            es.close();
            // Reload the queue to surface fresh DRAFT pills.
            queue.reload();
          });
          es.addEventListener('error', () => {
            taskCenter.finish({ status: 'failed', summary: 'Connection lost' });
            es.close();
          });
        },
      });
    } catch (err) {
      console.error('[unprocessed] BulkEnrich preview failed', err);
      alert('Could not load preview: ' + err.message);
    } finally {
      bulkEnrichBtn.disabled = false;
      // Restore label (will be re-synced by next updateBulkEnrichBtn() too).
      if (_bulkBtnLabel) bulkEnrichBtn.textContent = _bulkBtnLabel;
      updateBulkEnrichBtn();
    }
  });
}
