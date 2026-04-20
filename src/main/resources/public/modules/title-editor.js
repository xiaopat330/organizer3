// Title Editor (Queue) module. See spec/PROPOSAL_TITLE_EDITOR.md.
// Split layout: sidebar queue + editor pane. Single route, swap editor pane in place.

import { esc } from './utils.js';

// ── DOM refs ──────────────────────────────────────────────────────────────
const view          = document.getElementById('tools-queue-view');
const sidebarList   = document.getElementById('queue-list');
const sidebarCount  = document.getElementById('queue-count-label');
const showCompleteCb= document.getElementById('queue-show-complete');

const emptyState    = document.getElementById('queue-editor-empty');
const pane          = document.getElementById('queue-editor-pane');
const codeEl        = document.getElementById('queue-editor-code');
const folderEl      = document.getElementById('queue-editor-folder');

const coverPanel    = document.getElementById('queue-cover-panel');
const coverImg      = document.getElementById('queue-cover-img');
const coverPlaceholder = document.getElementById('queue-cover-placeholder');

const descriptorInput   = document.getElementById('queue-descriptor-input');
const descriptorPreview = document.getElementById('queue-descriptor-preview');
const actressList   = document.getElementById('queue-actress-list');
const actressInput  = document.getElementById('queue-actress-input');
const actressSuggest= document.getElementById('queue-actress-suggest');

const saveBtn       = document.getElementById('queue-save-btn');
const skipBtn       = document.getElementById('queue-skip-btn');
const statusEl      = document.getElementById('queue-editor-status');

// ── Module state ──────────────────────────────────────────────────────────
let queueRows = [];          // [{titleId, code, folderName, actressCount, hasCover, complete}]
let currentId = null;
let currentDetail = null;    // loaded detail for currentId
let editorState = null;      // { actresses: [{id?|newName, canonicalName, primary, isNew}], coverStaged: { kind: 'url'|'bytes', ...} | null, coverDirty: boolean }
let suggestHighlight = -1;
let searchSeq = 0;

// ── Public API ────────────────────────────────────────────────────────────
export function showTitleEditor() {
  view.style.display = 'flex';
  loadQueue();
}

export function hideTitleEditorView() {
  view.style.display = 'none';
  hideSuggest();
}

// ── Sidebar / queue list ──────────────────────────────────────────────────
async function loadQueue() {
  try {
    const res = await fetch('/api/unsorted/titles');
    queueRows = await res.json();
    renderSidebar();
  } catch (err) {
    console.error('loadQueue failed', err);
    sidebarCount.textContent = 'Error loading queue';
  }
}

function visibleRows() {
  return showCompleteCb.checked ? queueRows : queueRows.filter(r => !r.complete);
}

function renderSidebar() {
  const complete = queueRows.filter(r => r.complete).length;
  sidebarCount.textContent = `${queueRows.length} eligible · ${complete} complete`;

  sidebarList.innerHTML = '';
  const rows = visibleRows();
  for (const r of rows) {
    const li = document.createElement('li');
    li.className = 'queue-list-item';
    if (r.titleId === currentId) li.classList.add('selected');
    const marker = statusMarker(r);
    li.innerHTML = `
      <span class="queue-status-marker ${marker.cls}" title="${marker.title}">${marker.glyph}</span>
      <span class="queue-code">${esc(r.code)}</span>
      <span class="queue-folder-excerpt">${esc(r.folderName)}</span>
    `;
    li.addEventListener('click', () => navigateTo(r.titleId));
    sidebarList.appendChild(li);
  }
  if (!rows.length) {
    sidebarList.innerHTML = '<li class="queue-list-item" style="color:#555;cursor:default">No titles</li>';
  }
}

function statusMarker(r) {
  if (r.complete)                        return { cls: 'queue-status-complete', glyph: '●', title: 'Complete' };
  if (r.actressCount > 0 || r.hasCover)  return { cls: 'queue-status-partial',  glyph: '◐', title: 'Partial' };
  return                                         { cls: 'queue-status-empty',    glyph: '○', title: 'Empty' };
}

showCompleteCb.addEventListener('change', renderSidebar);

// ── Navigation (with unsaved-changes guard) ───────────────────────────────
async function navigateTo(titleId) {
  if (isDirty() && !confirm('Discard unsaved changes?')) return;
  currentId = titleId;
  await loadDetail(titleId);
  renderSidebar();
}

skipBtn.addEventListener('click', () => {
  if (isDirty() && !confirm('Discard unsaved changes?')) return;
  const rows = visibleRows();
  if (!rows.length) { showEmpty(); return; }
  const idx = rows.findIndex(r => r.titleId === currentId);
  const next = idx < 0 ? rows[0] : rows[(idx + 1) % rows.length];
  currentId = next.titleId;
  loadDetail(next.titleId).then(renderSidebar);
});

// ── Load + render editor pane ─────────────────────────────────────────────
async function loadDetail(titleId) {
  setStatus('', '');
  try {
    const res = await fetch(`/api/unsorted/titles/${titleId}`);
    if (!res.ok) { showEmpty(); return; }
    currentDetail = await res.json();
    editorState = buildInitialState(currentDetail);
    renderEditor();
  } catch (err) {
    console.error('loadDetail failed', err);
    showEmpty();
  }
}

function showEmpty() {
  currentDetail = null;
  editorState = null;
  pane.style.display = 'none';
  emptyState.style.display = 'block';
  emptyState.textContent = visibleRows().length === 0
      ? 'No titles left in this view — toggle "Show complete" to revisit finished titles.'
      : 'Select a title from the queue to start editing.';
}

function buildInitialState(detail) {
  const actresses = (detail.detail?.actresses || []).map(a => ({
    id: a.actressId,
    canonicalName: a.canonicalName,
    stageName: a.stageName,
    primary: a.primary,
    isNew: false
  }));
  const descriptor = detail.descriptor || '';
  return {
    actresses,
    descriptor,
    coverStaged: null,
    coverDirty: false,
    hasExistingCover: !!detail.hasCover,
    initialActresses: JSON.stringify(actresses),
    initialDescriptor: descriptor
  };
}

function renderEditor() {
  if (!currentDetail) return;
  emptyState.style.display = 'none';
  pane.style.display = 'flex';
  const d = currentDetail.detail;
  codeEl.textContent = d.code;
  folderEl.textContent = d.folderName;
  descriptorInput.value = editorState.descriptor || '';
  updateDescriptorPreview();

  // Cover preview
  if (currentDetail.hasCover && currentDetail.coverFilename && !editorState.coverDirty) {
    coverImg.src = `/covers/${encodeURIComponent(d.label)}/${encodeURIComponent(currentDetail.coverFilename)}?t=${Date.now()}`;
    coverImg.style.display = 'block';
    coverPlaceholder.style.display = 'none';
  } else if (editorState.coverStaged && editorState.coverStaged.previewUrl) {
    coverImg.src = editorState.coverStaged.previewUrl;
    coverImg.style.display = 'block';
    coverPlaceholder.style.display = 'none';
  } else {
    coverImg.style.display = 'none';
    coverPlaceholder.style.display = 'block';
  }

  renderActresses();
  updateSaveEnabled();
}

function renderActresses() {
  actressList.innerHTML = '';
  const count = editorState.actresses.length;
  editorState.actresses.forEach((a, idx) => {
    const li = document.createElement('li');
    li.className = 'queue-actress-item';
    const primaryBtn = document.createElement('button');
    primaryBtn.type = 'button';
    primaryBtn.className = 'queue-actress-primary-btn' + (a.primary ? ' active' : '');
    primaryBtn.textContent = a.primary ? '★' : '☆';
    primaryBtn.title = a.primary ? 'Primary actress' : 'Mark as primary';
    primaryBtn.addEventListener('click', () => setPrimary(idx));

    const name = document.createElement('span');
    name.className = 'queue-actress-name';
    name.textContent = a.canonicalName;

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'queue-actress-remove-btn';
    remove.textContent = '×';
    remove.disabled = count <= 1;
    remove.title = count <= 1 ? 'At least one actress required' : 'Remove';
    remove.addEventListener('click', () => removeActress(idx));

    li.appendChild(primaryBtn);
    li.appendChild(name);
    if (a.isNew) {
      const badge = document.createElement('span');
      badge.className = 'queue-actress-new-badge';
      badge.textContent = 'new';
      li.appendChild(badge);
    }
    li.appendChild(remove);
    actressList.appendChild(li);
  });
}

function setPrimary(idx) {
  editorState.actresses.forEach((a, i) => a.primary = (i === idx));
  renderActresses();
  updateDescriptorPreview();
  updateSaveEnabled();
}

function removeActress(idx) {
  const removed = editorState.actresses[idx];
  editorState.actresses.splice(idx, 1);
  if (removed.primary && editorState.actresses.length > 0) {
    // Force re-pick: no primary set until user marks one
    editorState.actresses.forEach(a => a.primary = false);
  }
  renderActresses();
  updateSaveEnabled();
}

// ── Actress typeahead ─────────────────────────────────────────────────────
let debounceTimer = null;
actressInput.addEventListener('input', () => {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(runSearch, 150);
});

actressInput.addEventListener('keydown', e => {
  const items = Array.from(actressSuggest.querySelectorAll('.queue-actress-suggest-item'));
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    if (!items.length) return;
    suggestHighlight = (suggestHighlight + 1) % items.length;
    updateHighlight(items);
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    if (!items.length) return;
    suggestHighlight = (suggestHighlight - 1 + items.length) % items.length;
    updateHighlight(items);
  } else if (e.key === 'Enter') {
    e.preventDefault();
    if (suggestHighlight >= 0 && items[suggestHighlight]) items[suggestHighlight].click();
  } else if (e.key === 'Escape') {
    hideSuggest();
  }
});

function updateHighlight(items) {
  items.forEach((el, i) => el.classList.toggle('highlight', i === suggestHighlight));
}

async function runSearch() {
  const q = actressInput.value.trim();
  if (q.length < 1) { hideSuggest(); return; }
  const seq = ++searchSeq;
  try {
    const res = await fetch(`/api/unsorted/actresses/search?q=${encodeURIComponent(q)}&limit=10`);
    if (seq !== searchSeq) return;
    const hits = await res.json();
    renderSuggest(hits, q);
  } catch (err) {
    console.error('actress search failed', err);
  }
}

function renderSuggest(hits, query) {
  actressSuggest.innerHTML = '';
  suggestHighlight = -1;
  const taken = new Set(editorState.actresses.filter(a => a.id != null).map(a => a.id));
  const takenNames = new Set(editorState.actresses.map(a => a.canonicalName.toLowerCase()));

  const filtered = hits.filter(h => !taken.has(h.id));
  filtered.forEach((h, i) => {
    const item = document.createElement('div');
    item.className = 'queue-actress-suggest-item';
    let html = `<span>${esc(h.canonicalName)}</span>`;
    if (h.matchedAlias) {
      html += `<span class="queue-actress-suggest-alias">(matched: ${esc(h.matchedAlias)})</span>`;
    }
    item.innerHTML = html;
    item.addEventListener('click', () => addExisting(h));
    actressSuggest.appendChild(item);
  });

  const exactMatch = filtered.some(h => h.canonicalName.toLowerCase() === query.toLowerCase());
  if (!exactMatch && !takenNames.has(query.toLowerCase())) {
    const create = document.createElement('div');
    create.className = 'queue-actress-suggest-item queue-actress-suggest-create';
    create.textContent = `+ Create "${query}"`;
    create.addEventListener('click', () => addDraft(query));
    actressSuggest.appendChild(create);
  }
  if (actressSuggest.children.length > 0) actressSuggest.style.display = 'block';
  else hideSuggest();
}

function hideSuggest() {
  actressSuggest.style.display = 'none';
  actressSuggest.innerHTML = '';
  suggestHighlight = -1;
}

function addExisting(h) {
  const hadPrimary = editorState.actresses.some(a => a.primary);
  editorState.actresses.push({
    id: h.id, canonicalName: h.canonicalName, stageName: h.stageName,
    primary: !hadPrimary, isNew: false
  });
  actressInput.value = '';
  hideSuggest();
  renderActresses();
  updateSaveEnabled();
}

function addDraft(name) {
  const trimmed = name.trim();
  if (!trimmed) return;
  const hadPrimary = editorState.actresses.some(a => a.primary);
  editorState.actresses.push({
    id: null, newName: trimmed, canonicalName: trimmed,
    primary: !hadPrimary, isNew: true
  });
  actressInput.value = '';
  hideSuggest();
  renderActresses();
  updateSaveEnabled();
}

document.addEventListener('click', e => {
  if (!actressSuggest.contains(e.target) && e.target !== actressInput) hideSuggest();
});

// ── Cover panel: URL drop, file drop, clipboard paste ─────────────────────
coverPanel.addEventListener('dragover', e => {
  e.preventDefault();
  coverPanel.classList.add('dragover');
});
coverPanel.addEventListener('dragleave', () => coverPanel.classList.remove('dragover'));
coverPanel.addEventListener('drop', async e => {
  e.preventDefault();
  coverPanel.classList.remove('dragover');
  if (!confirmCoverReplace()) return;

  const dt = e.dataTransfer;
  if (dt.files && dt.files.length > 0) {
    stageFile(dt.files[0]);
  } else {
    const url = dt.getData('text/uri-list') || dt.getData('text/plain');
    if (url) stageUrl(url.split('\n')[0].trim());
  }
});

coverPanel.addEventListener('paste', async e => {
  if (!confirmCoverReplace()) { e.preventDefault(); return; }
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
    if (/^https?:\/\//i.test(trimmed)) { stageUrl(trimmed); e.preventDefault(); }
  }
});

function confirmCoverReplace() {
  if (currentDetail && currentDetail.hasCover && !editorState.coverDirty) {
    return confirm('Replace existing cover?');
  }
  return true;
}

function stageFile(file) {
  const reader = new FileReader();
  reader.onload = () => {
    editorState.coverStaged = { kind: 'bytes', file, previewUrl: reader.result };
    editorState.coverDirty = true;
    renderEditor();
  };
  reader.readAsDataURL(file);
}

function stageUrl(url) {
  editorState.coverStaged = { kind: 'url', url, previewUrl: url };
  editorState.coverDirty = true;
  renderEditor();
}

// ── Save flow ────────────────────────────────────────────────────────────
function isDirty() {
  if (!editorState) return false;
  if (editorState.coverDirty) return true;
  if ((editorState.descriptor || '') !== (editorState.initialDescriptor || '')) return true;
  return JSON.stringify(editorState.actresses) !== editorState.initialActresses;
}

const DESCRIPTOR_ALLOWED = /^[A-Za-z0-9 _@#=+,;]*$/;

descriptorInput.addEventListener('input', () => {
  if (!editorState) return;
  editorState.descriptor = descriptorInput.value;
  updateDescriptorPreview();
  updateSaveEnabled();
});

function descriptorIsValid() {
  const v = (editorState?.descriptor || '').trim();
  return DESCRIPTOR_ALLOWED.test(v);
}

function updateDescriptorPreview() {
  if (!currentDetail || !editorState) {
    descriptorPreview.textContent = '';
    descriptorInput.classList.remove('invalid');
    return;
  }
  const primary = editorState.actresses.find(a => a.primary);
  const primaryName = primary ? primary.canonicalName : '(primary)';
  const code = currentDetail.detail.code;
  const desc = (editorState.descriptor || '').trim();
  const valid = descriptorIsValid();
  descriptorInput.classList.toggle('invalid', !valid);
  if (!valid) {
    descriptorPreview.textContent = 'invalid — allowed: letters, digits, space, _ @ # = + , ;';
    return;
  }
  descriptorPreview.textContent = desc
      ? `${primaryName} - ${desc} (${code})`
      : `${primaryName} (${code})`;
}

function updateSaveEnabled() {
  saveBtn.disabled = !canSave();
}

function canSave() {
  if (!editorState) return false;
  if (editorState.actresses.length === 0) return false;
  if (!editorState.actresses.some(a => a.primary)) return false;
  if (!descriptorIsValid()) return false;
  return true;
}

saveBtn.addEventListener('click', async () => {
  if (!canSave()) return;
  setStatus('Saving…', '');
  saveBtn.disabled = true;

  try {
    // 1. Actress save (transactional with inline create)
    const actressPayload = {
      actresses: editorState.actresses.map(a => a.isNew
        ? { newName: a.newName }
        : { id: a.id }),
      primary: (() => {
        const p = editorState.actresses.find(a => a.primary);
        return p.isNew ? { newName: p.newName } : { id: p.id };
      })(),
      descriptor: (editorState.descriptor || '').trim() || null
    };
    const actRes = await fetch(`/api/unsorted/titles/${currentId}/actresses`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(actressPayload)
    });
    if (!actRes.ok) throw new Error('Actresses: ' + await actRes.text());
    const actData = await actRes.json();

    // 2. Cover save (if staged)
    if (editorState.coverStaged) {
      const cs = editorState.coverStaged;
      if (cs.kind === 'bytes') {
        const fd = new FormData();
        fd.append('file', cs.file);
        const r = await fetch(`/api/unsorted/titles/${currentId}/cover`, { method: 'POST', body: fd });
        if (!r.ok) throw new Error('Cover: ' + await r.text());
      } else {
        const r = await fetch(`/api/unsorted/titles/${currentId}/cover`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: cs.url })
        });
        if (!r.ok) throw new Error('Cover: ' + await r.text());
      }
    }

    // 3. Refresh sidebar and current detail
    await loadQueue();
    await loadDetail(currentId);
    setStatus(actData.folderRenamed ? 'Saved · folder renamed' : 'Saved', 'success');
  } catch (err) {
    console.error('Save failed', err);
    setStatus('Save failed: ' + (err.message || err), 'error');
  } finally {
    updateSaveEnabled();
  }
});

function setStatus(msg, cls) {
  statusEl.textContent = msg;
  statusEl.className = 'queue-editor-status' + (cls ? ' ' + cls : '');
}
