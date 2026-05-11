/* ─────────────────────────────────────────────────────────────────────
   unprocessed/cover-pane.js — drag-drop / paste / URL cover staging
   (no-draft mode), and scratch-cover preview + Refetch/Clear (draft mode).

   Exported:
     mountCoverPane(containerEl, editorState, isDuplicate, titleId, onChange) → { destroy, renderCover }
     mountDraftCoverPane(containerEl, state, { onStatus }) → { destroy, renderCover }

   Responsibilities (no-draft):
     - Show existing cover (if hasCover) or placeholder drop target
     - Drag-drop: file drop or URL drop
     - Clipboard paste: image bytes or URL string
     - URL staging (text that looks like http/https)
     - Replace-confirm guard when a cover already exists
     - Duplicate-mode lock: read-only display, no interaction

   Responsibilities (draft):
     - Show scratch cover GET /api/drafts/:id/cover (cache-busted)
     - Refetch button → POST /api/drafts/:id/cover/refetch
     - Clear button   → DELETE /api/drafts/:id/cover
   ───────────────────────────────────────────────────────────────────── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

/**
 * @param {HTMLElement} containerEl
 * @param {object}      editorState
 * @param {boolean}     isDuplicate
 * @param {number}      titleId        — for cover URL path
 * @param {object}      detail         — full detail response for label + coverFilename
 * @param {Function}    onChange
 * @returns {{ destroy:Function, renderCover:Function }}
 */
export function mountCoverPane(containerEl, editorState, isDuplicate, titleId, detail, onChange) {
  containerEl.innerHTML = `
    <div class="un-cover-pane${isDuplicate ? ' un-cover-locked' : ''}" id="un-cp-panel"
         ${!isDuplicate ? 'tabindex="0"' : ''}>
      <img class="un-cover-img" id="un-cp-img" style="display:none" alt="Cover">
      <div class="un-cover-placeholder" id="un-cp-placeholder" style="display:none">
        <span class="un-cover-drop-hint">Drop image or URL here<br><span class="un-cover-drop-hint-sub">or paste from clipboard</span></span>
      </div>
      ${isDuplicate ? '<div class="un-cover-lock-overlay">🔒 Cover locked (duplicate)</div>' : ''}
    </div>
  `;

  const panelEl      = containerEl.querySelector('#un-cp-panel');
  const imgEl        = containerEl.querySelector('#un-cp-img');
  const placeholderEl= containerEl.querySelector('#un-cp-placeholder');

  // ── Initial render ───────────────────────────────────────────────────
  function renderCover() {
    const hasStagedPreview = editorState.coverStaged?.previewUrl;
    const hasCover = editorState.hasExistingCover;
    const d = detail?.detail;

    if (hasStagedPreview) {
      imgEl.src = editorState.coverStaged.previewUrl;
      imgEl.style.display = 'block';
      placeholderEl.style.display = 'none';
    } else if (hasCover && d?.label && detail?.coverFilename && !editorState.coverDirty) {
      imgEl.src = `/covers/${encodeURIComponent(d.label)}/${encodeURIComponent(detail.coverFilename)}?t=${Date.now()}`;
      imgEl.style.display = 'block';
      placeholderEl.style.display = 'none';
    } else {
      imgEl.style.display = 'none';
      placeholderEl.style.display = isDuplicate ? 'none' : 'flex';
    }
  }

  renderCover();

  if (isDuplicate) {
    // No interactions in dup mode
    return { destroy: () => {}, renderCover };
  }

  // ── Replace guard ────────────────────────────────────────────────────
  function confirmReplace() {
    if (editorState.hasExistingCover && !editorState.coverDirty) {
      return confirm('Replace existing cover?');
    }
    return true;
  }

  // ── File staging ─────────────────────────────────────────────────────
  function stageFile(file) {
    const reader = new FileReader();
    reader.onload = () => {
      editorState.coverStaged = { kind: 'bytes', file, previewUrl: reader.result };
      editorState.coverDirty = true;
      renderCover();
      onChange?.();
    };
    reader.readAsDataURL(file);
  }

  // ── URL staging ──────────────────────────────────────────────────────
  function stageUrl(url) {
    editorState.coverStaged = { kind: 'url', url, previewUrl: url };
    editorState.coverDirty = true;
    renderCover();
    onChange?.();
  }

  // ── Drag-drop ────────────────────────────────────────────────────────
  function onDragOver(e) {
    e.preventDefault();
    panelEl.classList.add('un-cover-dragover');
  }
  function onDragLeave() {
    panelEl.classList.remove('un-cover-dragover');
  }
  function onDrop(e) {
    e.preventDefault();
    panelEl.classList.remove('un-cover-dragover');
    if (!confirmReplace()) return;
    const dt = e.dataTransfer;
    if (dt.files && dt.files.length > 0) {
      stageFile(dt.files[0]);
    } else {
      const url = dt.getData('text/uri-list') || dt.getData('text/plain');
      if (url) stageUrl(url.split('\n')[0].trim());
    }
  }

  // ── Clipboard paste ──────────────────────────────────────────────────
  function onPaste(e) {
    if (!confirmReplace()) { e.preventDefault(); return; }
    const items = e.clipboardData?.items || [];
    for (const it of items) {
      if (it.type && it.type.startsWith('image/')) {
        const file = it.getAsFile();
        if (file) { stageFile(file); e.preventDefault(); return; }
      }
    }
    const text = e.clipboardData?.getData('text/plain');
    if (text) {
      const trimmed = text.trim();
      if (/^https?:\/\//i.test(trimmed)) {
        stageUrl(trimmed);
        e.preventDefault();
      }
    }
  }

  panelEl.addEventListener('dragover',  onDragOver);
  panelEl.addEventListener('dragleave', onDragLeave);
  panelEl.addEventListener('drop',      onDrop);
  panelEl.addEventListener('paste',     onPaste);

  function destroy() {
    panelEl.removeEventListener('dragover',  onDragOver);
    panelEl.removeEventListener('dragleave', onDragLeave);
    panelEl.removeEventListener('drop',      onDrop);
    panelEl.removeEventListener('paste',     onPaste);
  }

  return { destroy, renderCover };
}

/**
 * Draft-mode scratch cover pane: preview + Refetch/Clear actions.
 *
 * @param {HTMLElement} containerEl
 * @param {object}      state         — shared state (reads state.draft, state.currentId)
 * @param {object}      callbacks
 * @param {Function}    callbacks.onStatus  — (msg, cls) → void  (status banner)
 * @returns {{ destroy:Function, renderCover:Function }}
 */
export function mountDraftCoverPane(containerEl, state, { onStatus }) {
  containerEl.innerHTML = `
    <div class="un-cover-pane un-cover-draft" id="un-dcp-panel">
      <img class="un-cover-img" id="un-dcp-img" style="display:none" alt="Scratch cover">
      <div class="un-cover-placeholder" id="un-dcp-placeholder" style="display:none">
        <span class="un-cover-drop-hint">No scratch cover yet<br>
          <span class="un-cover-drop-hint-sub">use Refetch to pull from javdb</span>
        </span>
      </div>
    </div>
    <div class="un-draft-cover-actions">
      <button class="btn btn-secondary btn-sm" id="un-dcp-refetch" type="button">Refetch cover</button>
      <button class="btn btn-secondary btn-sm" id="un-dcp-clear"   type="button">Clear cover</button>
    </div>
  `;

  const imgEl         = containerEl.querySelector('#un-dcp-img');
  const placeholderEl = containerEl.querySelector('#un-dcp-placeholder');
  const refetchBtn    = containerEl.querySelector('#un-dcp-refetch');
  const clearBtn      = containerEl.querySelector('#un-dcp-clear');

  function renderCover(cacheBuster) {
    const titleId = state.currentId;
    const present = !!state.draft?.coverScratchPresent;
    if (present && titleId != null) {
      const bust = cacheBuster || Date.now();
      imgEl.src = `/api/drafts/${titleId}/cover?t=${bust}`;
      imgEl.style.display = 'block';
      placeholderEl.style.display = 'none';
    } else {
      imgEl.removeAttribute('src');
      imgEl.style.display = 'none';
      placeholderEl.style.display = 'flex';
    }
  }

  renderCover();

  refetchBtn.addEventListener('click', async () => {
    const titleId = state.currentId;
    if (titleId == null) return;
    refetchBtn.disabled = true;
    const original = refetchBtn.textContent;
    refetchBtn.textContent = 'Fetching…';
    onStatus?.('Fetching cover…', '');
    try {
      const res = await fetch(`/api/drafts/${titleId}/cover/refetch`, { method: 'POST' });
      if (res.ok) {
        if (state.draft) state.draft.coverScratchPresent = true;
        renderCover(Date.now());
        onStatus?.('Cover updated.', 'success');
      } else if (res.status === 422) {
        onStatus?.('No cover URL on file — populate first.', 'error');
      } else {
        onStatus?.('Refetch failed: HTTP ' + res.status, 'error');
      }
    } catch (err) {
      onStatus?.('Refetch error: ' + (err.message || err), 'error');
    } finally {
      refetchBtn.disabled = false;
      refetchBtn.textContent = original;
    }
  });

  clearBtn.addEventListener('click', async () => {
    const titleId = state.currentId;
    if (titleId == null) return;
    clearBtn.disabled = true;
    try {
      const res = await fetch(`/api/drafts/${titleId}/cover`, { method: 'DELETE' });
      if (res.ok || res.status === 404) {
        if (state.draft) state.draft.coverScratchPresent = false;
        renderCover();
        onStatus?.('Cover cleared.', 'success');
      } else {
        onStatus?.('Clear failed: HTTP ' + res.status, 'error');
      }
    } catch (err) {
      onStatus?.('Clear error: ' + (err.message || err), 'error');
    } finally {
      clearBtn.disabled = false;
    }
  });

  function destroy() {
    // Listeners are bound to elements that go away with innerHTML rewrite; nothing to clear.
  }

  return { destroy, renderCover };
}
