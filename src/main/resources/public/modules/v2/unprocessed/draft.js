/* ─────────────────────────────────────────────────────────────────────
   unprocessed/draft.js — draft state helpers + Validate/Promote/Discard.

   Wave 3 scope:
     - mountDraft(paneEl, state, callbacks) → { destroy }
     - Renders metadata block (read-only — matches legacy + spec; PATCH
       has no metadata field), upstream-changed banner, scratch cover
       (via mountDraftCoverPane), cast scaffolding (via mountCastPane),
       intrinsic tag panel (via renderTagPanel), action row.
     - Wires Validate, Promote (validate + promote), Discard (confirm),
       Skip. Fires editor-session-open telemetry on mount.
     - Reload-on-409 + reload after Unlink PATCH.

   NOT in scope (Wave 4): cast picker, alias-capture, near-miss, polling.
   NOT in scope (Wave 5): bulk enrich.

   Cleanup contract: destroy() is structured so Wave 4 can clear polling
   timers here without touching the editor-level handle wiring.
   ───────────────────────────────────────────────────────────────────── */

import { mountDraftCoverPane } from './cover-pane.js';
import { mountCastPane }       from './cast-pane.js';
import { renderTagPanel }      from './tags-pane.js';
import { displayPath }         from '../../path-utils.js';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function formatReleaseDate(dateStr) {
  if (!dateStr) return '';
  try {
    return new Date(dateStr + 'T00:00:00').toLocaleDateString('en-US', {
      year: 'numeric', month: 'long', day: 'numeric',
    });
  } catch (_) { return dateStr; }
}

/**
 * Build the draft-mode editorState shim used by the (shared) tag panel.
 * The tag panel reads directTags / labelImpliedTags / enrichmentImpliedTags
 * as Sets — we mirror the no-draft shape so renderTagPanel works unchanged.
 */
function buildDraftTagState(detail, draft) {
  const enrFromDraft = draft?.enrichment?.resolvedTags;
  return {
    directTags:            new Set(detail?.directTags || []),
    labelImpliedTags:      new Set(detail?.labelImpliedTags || []),
    enrichmentImpliedTags: new Set(enrFromDraft || detail?.enrichmentImpliedTags || []),
  };
}

/**
 * Mount the draft-mode editor pane.
 *
 * @param {HTMLElement} paneEl
 * @param {object}      state                — shared state (reads draft, detail, currentId)
 * @param {object}      callbacks
 * @param {Function}    callbacks.queueReload      — () → Promise — reload sidebar
 * @param {Function}    callbacks.loadDetail       — (titleId) → Promise — re-route this title
 * @param {Function}    callbacks.onPromoted       — (titleId) → void — after successful promote
 * @param {Function}    callbacks.onDiscarded      — (titleId) → void — after successful discard
 * @param {Function}    callbacks.onSkip           — () → void — Skip pressed
 * @returns {{ destroy:Function }}
 */
export function mountDraft(paneEl, state, {
  queueReload, loadDetail, onPromoted, onDiscarded, onSkip,
}) {
  let _coverHandle = null;
  let _castHandle  = null;
  let _upstreamDismissed = false;

  // Tags shim for renderTagPanel (Set-bearing object).
  // Source enrichmentImpliedTags from the DRAFT's resolved tags (not the canonical
  // detail), because canonical title_enrichment_tags are empty until promotion.
  const _tagState = buildDraftTagState(state.detail, state.draft);

  // Telemetry: fire-and-forget editor-session-open.
  if (state.currentId != null) {
    fetch('/api/curation/editor-session-open', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ titleId: state.currentId }),
    }).catch(() => {});
  }

  _renderShell();
  _renderAll();

  // ── Shell render (HTML scaffolding only) ─────────────────────────────
  function _renderShell() {
    const draft        = state.draft  || {};
    const detail       = state.detail?.detail || {};
    const code         = draft.code   || detail.code   || '';
    const folder       = detail.folderName || '';
    const folderNasPath = state.detail?.folderNasPath || '';

    paneEl.innerHTML = `
      <div class="un-editor-shell un-draft-shell">

        <div class="un-editor-header">
          <div class="un-editor-code-row">
            <span class="un-editor-code" id="un-ed-code" title="Click to copy">${esc(code)}</span>
            <span class="un-draft-pill">DRAFT</span>
          </div>
          <div class="un-editor-folder">
            <span class="un-editor-folder-key">Folder</span>
            ${folderNasPath
              ? `<span class="un-editor-folder-path un-editor-folder-copy" id="un-ed-folder-path" data-path="${esc(folderNasPath)}" title="Click to copy full path"><span class="un-editor-folder-text">${esc(displayPath(folderNasPath))}</span><svg class="un-editor-folder-copy-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg></span>`
              : `<span class="un-editor-folder-path">${esc(folder)}</span>`}
          </div>
        </div>

        <div class="un-upstream-banner" id="un-upstream-banner" style="display:none">
          <span class="un-upstream-icon">⚠</span>
          <div class="un-upstream-body">
            <strong>Upstream changed</strong> — the underlying title was modified after this
            draft was created. Reviewing fields may overwrite that change.
          </div>
          <div class="un-upstream-actions">
            <button class="btn btn-secondary btn-sm" id="un-upstream-discard"  type="button">Discard draft</button>
            <button class="btn btn-secondary btn-sm" id="un-upstream-continue" type="button">Continue anyway</button>
          </div>
        </div>

        <div class="un-editor-body">

          <div class="un-actions-row">
            <div class="un-actions-left">
              <button class="btn btn-secondary"  id="un-draft-validate" type="button">Validate</button>
              <button class="btn btn-primary"    id="un-draft-promote"  type="button">Promote</button>
              <button class="btn btn-danger"     id="un-draft-discard"  type="button">Discard</button>
              <button class="btn btn-secondary"  id="un-draft-skip"     type="button">Skip</button>
            </div>
          </div>

          <div class="un-status-bar" id="un-status-bar"></div>

          <div class="un-meta-section">
            <div class="un-section-label">Title metadata</div>
            <div class="un-meta-grid">
              <div class="un-meta-row"><span class="un-meta-key">Title</span>
                <span class="un-meta-val" id="un-meta-title"></span></div>
              <div class="un-meta-row" id="un-meta-rating-row" style="display:none">
                <span class="un-meta-key">Rating</span>
                <span class="un-meta-val" id="un-meta-rating"></span>
              </div>
              <div class="un-meta-row"><span class="un-meta-key">Release</span>
                <span class="un-meta-val" id="un-meta-release"></span></div>
              <div class="un-meta-row"><span class="un-meta-key">Maker</span>
                <span class="un-meta-val" id="un-meta-maker"></span></div>
              <div class="un-meta-row"><span class="un-meta-key">Series</span>
                <span class="un-meta-val" id="un-meta-series"></span></div>
            </div>
          </div>

          <div class="un-cover-section">
            <div class="un-section-label">Scratch cover</div>
            <div class="un-cover-container" id="un-cover-container"></div>
          </div>

          <div class="un-cast-section">
            <div class="un-section-label">Cast</div>
            <div class="un-cast-container" id="un-cast-container"></div>
          </div>

          <div class="un-tags-section">
            <div class="un-section-label">Tags</div>
            <div class="un-tags-panel" id="un-tags-panel"></div>
          </div>

        </div>
      </div>
    `;

    // ── Code copy ──────────────────────────────────────────────────────
    const codeEl = paneEl.querySelector('#un-ed-code');
    codeEl?.addEventListener('click', () => {
      const c = (codeEl.textContent || '').trim();
      if (!c) return;
      navigator.clipboard?.writeText(c).then(() => {
        codeEl.classList.add('un-code-copied');
        setTimeout(() => codeEl.classList.remove('un-code-copied'), 900);
      }).catch(() => {});
    });

    // ── Folder path copy ───────────────────────────────────────────────
    const folderPathEl = paneEl.querySelector('#un-ed-folder-path');
    folderPathEl?.addEventListener('click', () => {
      const raw = folderPathEl.dataset.path || '';
      if (!raw) return;
      const text = displayPath(raw.startsWith('//') ? 'smb:' + raw : raw);
      navigator.clipboard?.writeText(text).then(() => {
        folderPathEl.classList.add('un-editor-folder-copied');
        setTimeout(() => folderPathEl.classList.remove('un-editor-folder-copied'), 1100);
      }).catch(() => {});
    });

    // ── Upstream banner buttons ────────────────────────────────────────
    paneEl.querySelector('#un-upstream-discard')?.addEventListener('click', () => {
      paneEl.querySelector('#un-draft-discard')?.click();
    });
    paneEl.querySelector('#un-upstream-continue')?.addEventListener('click', () => {
      _upstreamDismissed = true;
      _renderUpstreamBanner();
    });

    // ── Action buttons ─────────────────────────────────────────────────
    paneEl.querySelector('#un-draft-validate')?.addEventListener('click', _onValidate);
    paneEl.querySelector('#un-draft-promote' )?.addEventListener('click', _onPromote);
    paneEl.querySelector('#un-draft-discard' )?.addEventListener('click', _onDiscard);
    paneEl.querySelector('#un-draft-skip'    )?.addEventListener('click', () => onSkip?.());
  }

  function _renderAll() {
    _renderUpstreamBanner();
    _renderMetadata();
    _renderCover();
    _renderCast();
    _renderTags();
    _setStatus('', '');
  }

  function _renderUpstreamBanner() {
    const el = paneEl.querySelector('#un-upstream-banner');
    if (!el) return;
    el.style.display = (state.draft?.upstreamChanged && !_upstreamDismissed) ? 'flex' : 'none';
  }

  function _renderMetadata() {
    const draft = state.draft || {};
    const enr   = draft.enrichment || {};
    const t     = paneEl.querySelector('#un-meta-title');
    const r     = paneEl.querySelector('#un-meta-release');
    const m     = paneEl.querySelector('#un-meta-maker');
    const s     = paneEl.querySelector('#un-meta-series');
    const ratingRow = paneEl.querySelector('#un-meta-rating-row');
    const rating    = paneEl.querySelector('#un-meta-rating');

    if (t) t.textContent = draft.titleOriginal || '';
    if (r) r.textContent = formatReleaseDate(draft.releaseDate);
    if (m) m.textContent = enr.maker  || '';
    if (s) s.textContent = enr.series || '';

    if (ratingRow && rating) {
      if (enr.ratingAvg != null && enr.ratingCount != null) {
        ratingRow.style.display = '';
        const avg   = Number(enr.ratingAvg).toFixed(2);
        const votes = Number(enr.ratingCount).toLocaleString();
        rating.innerHTML = enr.grade
          ? `<span class="grade-badge" data-grade="${esc(enr.grade)}">${esc(enr.grade)}</span> <span class="un-meta-rating-raw">${avg} · ${votes} votes</span>`
          : `<span class="un-meta-rating-raw">${avg} · ${votes} votes</span>`;
      } else {
        ratingRow.style.display = 'none';
      }
    }
  }

  function _renderCover() {
    _coverHandle?.destroy();
    const container = paneEl.querySelector('#un-cover-container');
    if (!container) return;
    _coverHandle = mountDraftCoverPane(container, state, {
      onStatus: (msg, cls) => _setStatus(msg, cls),
    });
  }

  function _renderCast() {
    _castHandle?.destroy();
    const container = paneEl.querySelector('#un-cast-container');
    if (!container) return;
    _castHandle = mountCastPane(container, state, {
      onResolve: async (javdbSlug, resolution, extra, idx, afterSuccess) => {
        await _patchResolution(javdbSlug, resolution, extra, idx, afterSuccess);
      },
      onUnlink: async (javdbSlug, idx) => {
        await _patchResolution(javdbSlug, 'unresolved', {}, idx);
      },
      onReload: () => { _reloadDraft(); },
    });
    _castHandle.renderCast();
  }

  function _renderTags() {
    const panel = paneEl.querySelector('#un-tags-panel');
    if (!panel) return;
    renderTagPanel(panel, _tagState, state.tagsCatalog, false, () => {
      _saveDraftTags();
    });
  }

  // ── Intrinsic tag save in draft mode (per spec §1.4 tags-only body) ──
  let _tagSaveTimer = null;
  function _saveDraftTags() {
    if (state.currentId == null) return;
    clearTimeout(_tagSaveTimer);
    _tagSaveTimer = setTimeout(async () => {
      const tags = [..._tagState.directTags].sort();
      try {
        await fetch(`/api/unsorted/titles/${state.currentId}/actresses`, {
          method:  'PUT',
          headers: { 'Content-Type': 'application/json' },
          body:    JSON.stringify({ tags }),
        });
      } catch (err) {
        console.warn('[draft] saveIntrinsicTags failed', err);
      }
    }, 250);
  }

  // ── Status bar ───────────────────────────────────────────────────────
  function _setStatus(msg, cls) {
    const el = paneEl.querySelector('#un-status-bar');
    if (!el) return;
    el.textContent = msg || '';
    el.className = 'un-status-bar' + (cls ? ' un-status-' + cls : '');
  }

  // ── Reload draft from server (after 409 / Unlink) ───────────────────
  async function _reloadDraft() {
    if (state.currentId == null) return;
    try {
      const res = await fetch(`/api/drafts/${state.currentId}`);
      if (res.ok) {
        state.draft = await res.json();
        _renderUpstreamBanner();
        _renderMetadata();
        _renderCover();
        _renderCast();
      } else if (res.status === 404) {
        // Draft vanished — fall back to detail reload
        await loadDetail?.(state.currentId);
      }
    } catch (err) {
      console.error('[draft] reload failed', err);
    }
  }

  // ── PATCH cast resolution (pick / create_new / skip / sentinel:N / unresolved) ──
  async function _patchResolution(javdbSlug, resolution, extra, idx, afterSuccess) {
    if (state.currentId == null || !state.draft) return;
    const isUnlink = resolution === 'unresolved';
    _setStatus(isUnlink ? 'Unlinking…' : 'Saving…', '');

    const payload = {
      expectedUpdatedAt: state.draft.updatedAt,
      castResolutions: [{
        javdbSlug,
        resolution,
        linkToExistingId: extra?.linkToExistingId ?? null,
        englishLastName:  extra?.englishLastName  ?? null,
        englishFirstName: extra?.englishFirstName ?? null,
      }],
      newActresses: [],
    };

    try {
      const res = await fetch(`/api/drafts/${state.currentId}`, {
        method:  'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify(payload),
      });
      if (res.status === 409) {
        _setStatus('Conflict — draft was updated elsewhere. Reloading…', 'warn');
        await _reloadDraft();
        return;
      }
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        const msg = body.errors?.join(', ') || body.error || ('HTTP ' + res.status);
        _setStatus((isUnlink ? 'Unlink' : 'Save') + ' failed: ' + msg, 'error');
        return;
      }
      const data = await res.json();
      state.draft.updatedAt = data.updatedAt;

      // Optimistic local update so we don't pay a round-trip.
      const slot = state.draft.cast?.[idx];
      if (slot) {
        if (isUnlink) {
          slot.resolution = 'unresolved';
          slot.linkToExistingId = null;
          slot.linkedActressName = null;
          slot.linkedActressAvatarUrl = null;
          slot.englishLastName = null;
          slot.englishFirstName = null;
        } else {
          slot.resolution = resolution;
          if (extra?.linkToExistingId != null) slot.linkToExistingId = extra.linkToExistingId;
          if (extra?.englishLastName  != null) slot.englishLastName  = extra.englishLastName;
          if (extra?.englishFirstName != null) slot.englishFirstName = extra.englishFirstName;
        }
      }
      _renderCast();
      _setStatus('', '');
      if (typeof afterSuccess === 'function') afterSuccess();
    } catch (err) {
      _setStatus((isUnlink ? 'Unlink' : 'Save') + ' error: ' + (err.message || err), 'error');
    }
  }

  // ── Validate ─────────────────────────────────────────────────────────
  async function _onValidate() {
    if (state.currentId == null || !state.draft) return;
    const btn = paneEl.querySelector('#un-draft-validate');
    if (btn) btn.disabled = true;
    _setStatus('Validating…', '');
    try {
      const res = await fetch(`/api/drafts/${state.currentId}/validate`, {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ expectedUpdatedAt: state.draft.updatedAt }),
      });
      if (res.status === 404) {
        _setStatus('Draft not found — may have been discarded.', 'error');
        return;
      }
      const data = await res.json();
      if (data.ok) {
        _setStatus('Ready to promote.', 'success');
      } else {
        _setStatus('Validation failed: ' + (data.errors || []).join(', '), 'error');
      }
    } catch (err) {
      _setStatus('Validate error: ' + (err.message || err), 'error');
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  // ── Promote (validate + promote) ────────────────────────────────────
  async function _onPromote() {
    if (state.currentId == null || !state.draft) return;
    const promoteBtn  = paneEl.querySelector('#un-draft-promote');
    const validateBtn = paneEl.querySelector('#un-draft-validate');
    const discardBtn  = paneEl.querySelector('#un-draft-discard');
    const skipBtn     = paneEl.querySelector('#un-draft-skip');

    // Helper: re-enable the two action buttons after a recoverable failure.
    function _reenableOnFailure() {
      if (promoteBtn)  promoteBtn.disabled  = false;
      if (validateBtn) validateBtn.disabled = false;
    }

    if (promoteBtn)  promoteBtn.disabled  = true;
    if (validateBtn) validateBtn.disabled = true;
    _setStatus('Promoting…', '');

    try {
      // Pre-flight validate.
      const vRes = await fetch(`/api/drafts/${state.currentId}/validate`, {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ expectedUpdatedAt: state.draft.updatedAt }),
      });
      const vData = await vRes.json();
      if (!vData.ok) {
        _setStatus('Promotion blocked: ' + (vData.errors || []).join(', '), 'error');
        _reenableOnFailure();
        return;
      }

      // Promote.
      const pRes = await fetch(`/api/drafts/${state.currentId}/promote`, {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ expectedUpdatedAt: state.draft.updatedAt }),
      });
      if (pRes.status === 409) {
        _setStatus('Conflict — draft was updated elsewhere. Reloading…', 'warn');
        _reenableOnFailure();
        await _reloadDraft();
        return;
      }
      if (pRes.status === 422) {
        const d = await pRes.json().catch(() => ({}));
        _setStatus('Promotion failed (pre-flight): ' + (d.errors || []).join(', '), 'error');
        _reenableOnFailure();
        return;
      }
      if (!pRes.ok) {
        const d = await pRes.json().catch(() => ({}));
        _setStatus('Promotion failed: ' + (d.error || d.detail || pRes.status), 'error');
        _reenableOnFailure();
        return;
      }

      // Success — parse response to get folderRenamed advisory.
      const pData = await pRes.json().catch(() => ({}));
      const bannerMsg = pData.folderRenamed === true
        ? 'Promoted — folder renamed'
        : 'Promoted — folder rename pending';

      // Terminal state: permanently disable all four action buttons.
      if (promoteBtn)  promoteBtn.disabled  = true;
      if (validateBtn) validateBtn.disabled = true;
      if (discardBtn)  discardBtn.disabled  = true;
      if (skipBtn)     skipBtn.disabled     = true;

      _setStatus(bannerMsg, 'success');
      const promotedId = state.currentId;
      onPromoted?.(promotedId);
    } catch (err) {
      _setStatus('Promote error: ' + (err.message || err), 'error');
      _reenableOnFailure();
    }
    // NOTE: no finally — buttons are only re-enabled on failure paths above.
  }

  // ── Discard ──────────────────────────────────────────────────────────
  async function _onDiscard() {
    if (state.currentId == null || !state.draft) return;
    if (!confirm('Discard this draft? This will drop all draft work; the title returns to the queue.')) return;

    const btn = paneEl.querySelector('#un-draft-discard');
    if (btn) btn.disabled = true;
    _setStatus('Discarding…', '');
    try {
      const res = await fetch(`/api/drafts/${state.currentId}`, { method: 'DELETE' });
      if (res.ok || res.status === 204 || res.status === 404) {
        _setStatus('Discarded.', 'success');
        onDiscarded?.(state.currentId);
      } else {
        _setStatus('Discard failed: HTTP ' + res.status, 'error');
        if (btn) btn.disabled = false;
      }
    } catch (err) {
      _setStatus('Discard error: ' + (err.message || err), 'error');
      if (btn) btn.disabled = false;
    }
  }

  // ── Cleanup ──────────────────────────────────────────────────────────
  function destroy() {
    _coverHandle?.destroy();
    _castHandle?.destroy();
    _coverHandle = null;
    _castHandle  = null;
    clearTimeout(_tagSaveTimer);
    // Stage-name polling timers + dirty/suppress sets are cleared inside
    // _castHandle.destroy() above (state.pollTimers, state.dirtySlots,
    // state.suppressInput live on shared state; see cast-pane.js).
  }

  return { destroy };
}
