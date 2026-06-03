/* ─────────────────────────────────────────────────────────────────────
   unprocessed/editor.js — editor pane dispatcher.

   Wave 2: handles no-draft mode + duplicate-mode.
   Wave 3: dispatches to draft.js when isDraftMode is true (metadata
           block, scratch cover, cast scaffolding, intrinsic tags,
           Validate/Promote/Discard/Skip).

   Exported:
     mountEditor(paneEl, state, { onSaveSuccess, loadDetail, queueReload })
       → { renderEditor, destroy, canNavigateAway }
   ───────────────────────────────────────────────────────────────────── */

import { mountActressPane }  from './actress-pane.js';
import { mountCoverPane }    from './cover-pane.js';
import { renderTagPanel }    from './tags-pane.js';
import { mountDraft }        from './draft.js';
import { displayPath }       from '../../path-utils.js';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

const DESCRIPTOR_ALLOWED = /^[A-Za-z0-9 _@#=+,;]*$/;
const ADVANCE_KEY = 'v2-unprocessed-advance-after-save';

function descriptorIsValid(descriptor) {
  return DESCRIPTOR_ALLOWED.test((descriptor || '').trim());
}

/**
 * @param {HTMLElement} paneEl          — .un-editor-pane
 * @param {object}      state           — shared state from createState()
 * @param {object}      callbacks
 * @param {Function}    callbacks.onSaveSuccess  — (savedId, nextId) → void
 * @param {Function}    callbacks.loadDetail     — (titleId) → Promise — called after enrich
 * @param {Function}    callbacks.queueReload    — () → Promise
 * @returns {{ renderEditor:Function, destroy:Function }}
 */
export function mountEditor(paneEl, state, { onSaveSuccess, loadDetail, queueReload }) {
  // Active sub-pane cleanup handles (actress-pane, cover-pane, draft)
  let _actressHandle = null;
  let _coverHandle   = null;
  let _draftHandle   = null;

  // ── isDirty ──────────────────────────────────────────────────────────
  function isDirty() {
    const es = state.editorState;
    if (!es) return false;
    if (es.coverDirty) return true;
    if ((es.descriptor || '') !== (es.initialDescriptor || '')) return true;
    const currentTags = JSON.stringify([...es.directTags].sort());
    if (currentTags !== es.initialTags) return true;
    return JSON.stringify(es.actresses) !== es.initialActresses;
  }

  // Expose for queue.js navigation guard
  state._isDirty = isDirty;

  // ── renderEditor — top-level dispatcher ──────────────────────────────
  function renderEditor() {
    const { detail, isDraftMode, editorState: es } = state;

    if (!detail) {
      paneEl.innerHTML = `
        <div class="dis-empty">
          <span class="un-empty-glyph">◌</span>
          Select a title to begin curation.
        </div>
      `;
      return;
    }

    if (isDraftMode) {
      // Tear down any no-draft sub-panes from a prior render
      _actressHandle?.destroy(); _actressHandle = null;
      _coverHandle?.destroy();   _coverHandle   = null;
      _draftHandle?.destroy();   _draftHandle   = null;

      _draftHandle = mountDraft(paneEl, state, {
        queueReload,
        loadDetail,
        onPromoted: async (titleId) => {
          // Reload queue, then advance like Save (advance=true).
          await queueReload();
          onSaveSuccess(titleId, true);
        },
        onDiscarded: async (titleId) => {
          // Draft → no-draft transition: reload detail (will route to no-draft pane).
          await queueReload();
          await loadDetail(titleId);
        },
        onSkip: () => {
          // Skip = advance without writing.
          onSaveSuccess(state.currentId, true);
        },
      });
      return;
    }

    // No-draft mode
    _draftHandle?.destroy();
    _draftHandle = null;
    _renderNoDraft();
  }

  // ── No-draft render ──────────────────────────────────────────────────
  function _renderNoDraft() {
    const detail        = state.detail;
    const es            = state.editorState;
    const d             = detail?.detail;
    const isDup         = !!(detail?.duplicate);
    const isProcessed   = !!(detail?.processed);
    const folderNasPath = detail?.folderNasPath || '';

    // Teardown existing sub-pane handles
    _actressHandle?.destroy();
    _coverHandle?.destroy();
    _actressHandle = null;
    _coverHandle   = null;

    // Advance-after-save preference (default: true)
    const advanceDefault = localStorage.getItem(ADVANCE_KEY);
    const advanceChecked = advanceDefault === null ? true : advanceDefault === 'true';

    paneEl.innerHTML = `
      <div class="un-editor-shell">

        ${isDup ? `
          <div class="un-dup-banner">
            <span class="un-dup-banner-icon">⚠</span>
            <div class="un-dup-banner-body">
              <strong>Duplicate title</strong> — this folder exists in multiple locations.
              Cast and cover are locked.
              <ul class="un-dup-locations" id="un-dup-locations"></ul>
            </div>
          </div>
        ` : ''}

        <div class="un-editor-header">
          <div class="un-editor-code-row">
            <span class="un-editor-code" id="un-ed-code" title="Click to copy">${esc(d?.code)}</span>
            ${isDup ? '<span class="un-dup-badge">DUPLICATE</span>' : ''}
            ${isProcessed ? '<span class="un-processed-pill un-processed-pill-header" title="Already curated via javdb">Processed via javdb</span>' : ''}
          </div>
          <div class="un-editor-folder" id="un-ed-folder">
            <span class="un-editor-folder-key">Folder</span>
            ${folderNasPath
              ? `<span class="un-editor-folder-path un-editor-folder-copy" id="un-ed-folder-path" data-path="${esc(folderNasPath)}" title="Click to copy full path">${esc(displayPath(folderNasPath))}</span>`
              : `<span class="un-editor-folder-path">${esc(d?.folderName)}</span>`}
          </div>
        </div>

        <div class="un-editor-body">

          <div class="un-actions-row">
            <div class="un-actions-left">
              <button class="btn btn-primary" id="un-save-btn" type="button" disabled>Save</button>
              <button class="btn btn-secondary" id="un-skip-btn" type="button" ${isProcessed ? 'disabled' : ''}>Skip</button>
              ${!isDup ? `
                <button class="btn btn-secondary" id="un-enrich-btn" type="button" ${isProcessed ? 'disabled' : ''}>Enrich via JavDB</button>
                <span class="un-enrich-elapsed" id="un-enrich-elapsed" style="display:none"></span>
              ` : ''}
            </div>
            <div class="un-actions-right">
              <label class="un-advance-label">
                <input type="checkbox" id="un-advance-cb" ${advanceChecked ? 'checked' : ''}>
                Advance after save
              </label>
            </div>
          </div>

          <div class="un-status-bar" id="un-status-bar"></div>

          <div class="un-cover-section">
            <div class="un-section-label">Cover</div>
            <div class="un-cover-container" id="un-cover-container"></div>
          </div>

          <div class="un-actress-section" id="un-actress-section"></div>

          <div class="un-descriptor-section">
            <div class="un-section-label">Descriptor</div>
            <div class="un-descriptor-row">
              <input type="text" class="un-descriptor-input" id="un-descriptor-input"
                     value="${esc(es?.descriptor || '')}"
                     placeholder="Optional — letters, digits, _ @ # = + , ;"
                     ${isDup && false ? 'disabled' : ''}>
            </div>
            <div class="un-descriptor-preview" id="un-descriptor-preview"></div>
          </div>

          <div class="un-tags-section">
            <div class="un-section-label">Tags</div>
            <div class="un-tags-panel" id="un-tags-panel"></div>
          </div>

        </div>
      </div>
    `;

    // ── Duplicate locations ──────────────────────────────────────────
    if (isDup) {
      const locsEl = paneEl.querySelector('#un-dup-locations');
      (detail.otherLocations || []).forEach(loc => {
        const li = document.createElement('li');
        li.className = 'un-dup-location';
        li.innerHTML = `
          <span class="un-dup-vol">${esc(loc.volumeId || '')}</span>
          <span class="un-dup-path">${esc(loc.path || '')}</span>
          <button class="btn btn-sm" data-code="${esc(d?.code)}" type="button">View</button>
        `;
        li.querySelector('button').addEventListener('click', () => {
          // v2-title-detail.html exists — open in new tab with code param
          window.open(`/v2-title-detail.html?code=${encodeURIComponent(d?.code || '')}`, '_blank');
        });
        locsEl?.appendChild(li);
      });
    }

    // ── Code copy ────────────────────────────────────────────────────
    const codeEl = paneEl.querySelector('#un-ed-code');
    codeEl?.addEventListener('click', () => {
      const code = (codeEl.textContent || '').trim();
      if (!code) return;
      navigator.clipboard?.writeText(code).then(() => {
        codeEl.classList.add('un-code-copied');
        setTimeout(() => codeEl.classList.remove('un-code-copied'), 900);
      }).catch(() => {});
    });

    // ── Folder path copy ─────────────────────────────────────────────
    const folderPathEl = paneEl.querySelector('#un-ed-folder-path');
    folderPathEl?.addEventListener('click', () => {
      const raw = folderPathEl.dataset.path || '';
      if (!raw) return;
      const text = displayPath(raw.startsWith('//') ? 'smb:' + raw : raw);
      navigator.clipboard?.writeText(text).then(() => {
        folderPathEl.classList.add('un-code-copied');
        setTimeout(() => folderPathEl.classList.remove('un-code-copied'), 900);
      }).catch(() => {});
    });

    // ── Descriptor ───────────────────────────────────────────────────
    const descriptorInput   = paneEl.querySelector('#un-descriptor-input');
    const descriptorPreview = paneEl.querySelector('#un-descriptor-preview');

    function updateDescriptorPreview() {
      if (!detail || !es) { descriptorPreview.textContent = ''; return; }
      const primary     = es.actresses.find(a => a.primary);
      const primaryName = primary ? primary.canonicalName : '(primary)';
      const code        = d?.code || '';
      const desc        = (es.descriptor || '').trim();
      const valid       = descriptorIsValid(es.descriptor);
      descriptorInput.classList.toggle('un-input-invalid', !valid);
      if (!valid) {
        descriptorPreview.textContent = 'Invalid — allowed: letters, digits, space, _ @ # = + , ;';
        descriptorPreview.classList.add('un-descriptor-invalid');
      } else {
        descriptorPreview.textContent = desc
            ? `${primaryName} - ${desc} (${code})`
            : `${primaryName} (${code})`;
        descriptorPreview.classList.remove('un-descriptor-invalid');
      }
    }

    descriptorInput?.addEventListener('input', () => {
      if (es) es.descriptor = descriptorInput.value;
      updateDescriptorPreview();
      updateSaveEnabled();
    });

    // ── Advance-after-save ───────────────────────────────────────────
    const advanceCb = paneEl.querySelector('#un-advance-cb');
    advanceCb?.addEventListener('change', () => {
      localStorage.setItem(ADVANCE_KEY, advanceCb.checked ? 'true' : 'false');
    });

    // ── Status bar ───────────────────────────────────────────────────
    const statusEl = paneEl.querySelector('#un-status-bar');
    function setStatus(msg, cls) {
      if (!statusEl) return;
      statusEl.textContent = msg;
      statusEl.className = 'un-status-bar' + (cls ? ' un-status-' + cls : '');
    }

    // ── Save enabled ─────────────────────────────────────────────────
    const saveBtn = paneEl.querySelector('#un-save-btn');
    function canSave() {
      if (!es) return false;
      if (!descriptorIsValid(es.descriptor)) return false;
      if (isDup) return true; // always allow save in dup mode
      if (es.actresses.length === 0) return false;
      if (!es.actresses.some(a => a.primary)) return false;
      return isDirty();
    }
    function updateSaveEnabled() {
      if (saveBtn) saveBtn.disabled = !canSave();
    }

    // ── Cover pane ───────────────────────────────────────────────────
    const coverContainer = paneEl.querySelector('#un-cover-container');
    _coverHandle = mountCoverPane(
      coverContainer,
      es,
      isDup,
      state.currentId,
      detail,
      () => { updateSaveEnabled(); }
    );

    // ── Actress pane ─────────────────────────────────────────────────
    const actressSection = paneEl.querySelector('#un-actress-section');
    _actressHandle = mountActressPane(
      actressSection,
      es,
      isDup,
      () => {
        // onChange: update descriptor preview (primary may have changed) + save enabled
        updateDescriptorPreview();
        updateSaveEnabled();
      }
    );

    // ── Tags panel ───────────────────────────────────────────────────
    const tagsPanel = paneEl.querySelector('#un-tags-panel');
    renderTagPanel(tagsPanel, es, state.tagsCatalog, isDup, () => updateSaveEnabled());

    // ── Initial preview + save state ─────────────────────────────────
    updateDescriptorPreview();
    updateSaveEnabled();

    // ── Save ─────────────────────────────────────────────────────────
    saveBtn?.addEventListener('click', async () => {
      if (!canSave()) return;
      setStatus('Saving…', '');
      saveBtn.disabled = true;

      try {
        const dup = isDup;

        // Build actress PUT payload per spec §1.4
        let actressPayload;
        if (dup) {
          actressPayload = {
            descriptor: (es.descriptor || '').trim() || null,
          };
        } else {
          const primary = es.actresses.find(a => a.primary);
          actressPayload = {
            actresses: es.actresses.map(a => a.isNew ? { newName: a.newName } : { id: a.id }),
            primary:   primary
              ? (primary.isNew ? { newName: primary.newName } : { id: primary.id })
              : (es.actresses[0]?.isNew ? { newName: es.actresses[0].newName } : { id: es.actresses[0]?.id }),
            descriptor: (es.descriptor || '').trim() || null,
            tags:       [...es.directTags].sort(),
          };
        }

        const actRes = await fetch(`/api/unsorted/titles/${state.currentId}/actresses`, {
          method:  'PUT',
          headers: { 'Content-Type': 'application/json' },
          body:    JSON.stringify(actressPayload),
        });
        if (!actRes.ok) throw new Error('Actresses: ' + await actRes.text());
        const actData = await actRes.json();

        // Cover save (sequential after PUT, skipped for duplicates)
        if (!dup && es.coverStaged) {
          const cs = es.coverStaged;
          if (cs.kind === 'bytes') {
            const fd = new FormData();
            fd.append('file', cs.file);
            const r = await fetch(`/api/unsorted/titles/${state.currentId}/cover`, { method: 'POST', body: fd });
            if (!r.ok) throw new Error('Cover: ' + await r.text());
          } else {
            const r = await fetch(`/api/unsorted/titles/${state.currentId}/cover`, {
              method:  'POST',
              headers: { 'Content-Type': 'application/json' },
              body:    JSON.stringify({ url: cs.url }),
            });
            if (!r.ok) throw new Error('Cover: ' + await r.text());
          }
        }

        // Reload queue, then advance or reload current
        await queueReload();
        const savedId = state.currentId;
        const advance = advanceCb?.checked ?? true;
        const base = actData.folderRenamed ? 'Saved · folder renamed' : 'Saved';
        setStatus(base, 'success');
        onSaveSuccess(savedId, advance);
      } catch (err) {
        console.error('[editor] save failed', err);
        setStatus('Save failed: ' + (err.message || err), 'error');
        updateSaveEnabled();
      }
    });

    // ── Skip ─────────────────────────────────────────────────────────
    paneEl.querySelector('#un-skip-btn')?.addEventListener('click', () => {
      if (isDirty() && !confirm('Discard unsaved changes?')) return;
      onSaveSuccess(state.currentId, true /* advance only — skip = advance without save */);
    });

    // ── Enrich button ────────────────────────────────────────────────
    if (!isDup) {
      const enrichBtn     = paneEl.querySelector('#un-enrich-btn');
      const enrichElapsed = paneEl.querySelector('#un-enrich-elapsed');
      enrichBtn?.addEventListener('click', async () => {
        const titleId = state.currentId;
        if (!titleId) return;

        enrichBtn.disabled = true;
        enrichBtn.textContent = 'Enriching…';
        enrichElapsed.style.display = 'inline';
        enrichElapsed.textContent = '0s';
        setStatus('Enriching — contacting javdb…', '');

        const start = Date.now();
        const timer = setInterval(() => {
          const s = Math.floor((Date.now() - start) / 1000);
          const mm = String(Math.floor(s / 60)).padStart(2, '0');
          const ss = String(s % 60).padStart(2, '0');
          enrichElapsed.textContent = `${mm}:${ss}`;
          setStatus(`Enriching… ${mm}:${ss}`, '');
        }, 1000);

        const resetBtn = () => {
          clearInterval(timer);
          enrichBtn.disabled = false;
          enrichBtn.textContent = 'Enrich via JavDB';
          enrichElapsed.style.display = 'none';
        };

        try {
          const res = await fetch(`/api/drafts/${titleId}/populate`, { method: 'POST' });
          clearInterval(timer);
          enrichBtn.textContent = 'Enrich via JavDB';
          enrichElapsed.style.display = 'none';

          if (res.status === 201 || res.status === 409) {
            setStatus(res.status === 201 ? 'Draft created — loading…' : 'Draft already exists — reloading…', 'success');
            // Reload detail — will route to draft mode (Wave 3 placeholder)
            await queueReload();
            await loadDetail(titleId);
          } else if (res.status === 422) {
            setStatus('No javdb match found for this title.', 'error');
            resetBtn();
          } else {
            setStatus('Enrich failed: HTTP ' + res.status, 'error');
            resetBtn();
          }
        } catch (err) {
          console.error('[editor] enrich failed', err);
          setStatus('Enrich failed: ' + (err.message || err), 'error');
          resetBtn();
        }
      });
    }
  }

  // ── destroy — clean up all sub-panes ────────────────────────────────
  function destroy() {
    _actressHandle?.destroy();
    _coverHandle?.destroy();
    _draftHandle?.destroy();
    _actressHandle = null;
    _coverHandle   = null;
    _draftHandle   = null;
  }

  // ── canNavigateAway — for unsaved-changes guard ──────────────────────
  function canNavigateAway() {
    if (state.isDraftMode) return true; // draft edits are server-side
    if (!isDirty()) return true;
    return confirm('Discard unsaved changes?');
  }

  return { renderEditor, destroy, canNavigateAway };
}
