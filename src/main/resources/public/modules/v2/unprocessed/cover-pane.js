/* ─────────────────────────────────────────────────────────────────────
   unprocessed/cover-pane.js — drag-drop / paste / URL cover staging.

   Exported:
     mountCoverPane(containerEl, editorState, isDuplicate, titleId, onChange) → { destroy, renderCover }

   Responsibilities:
     - Show existing cover (if hasCover) or placeholder drop target
     - Drag-drop: file drop or URL drop
     - Clipboard paste: image bytes or URL string
     - URL staging (text that looks like http/https)
     - Replace-confirm guard when a cover already exists
     - Duplicate-mode lock: read-only display, no interaction
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
