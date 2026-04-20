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
const coverLock     = document.getElementById('queue-cover-lock');

const descriptorInput   = document.getElementById('queue-descriptor-input');
const descriptorPreview = document.getElementById('queue-descriptor-preview');
const duplicateBadge    = document.getElementById('queue-duplicate-badge');
const duplicateBanner   = document.getElementById('queue-duplicate-banner');
const duplicateLocations= document.getElementById('queue-duplicate-locations');
const tagsPanel         = document.getElementById('queue-tags-panel');
const actressList   = document.getElementById('queue-actress-list');
const actressInput  = document.getElementById('queue-actress-input');
const actressSuggest= document.getElementById('queue-actress-suggest');
const actressHint   = document.getElementById('queue-actress-hint');

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
let tagsCatalog = null; // [{category, label, tags: [{name, description}]}]

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
    const [rowsRes] = await Promise.all([fetch('/api/unsorted/titles'), ensureTagsCatalog()]);
    queueRows = await rowsRes.json();
    renderSidebar();
  } catch (err) {
    console.error('loadQueue failed', err);
    sidebarCount.textContent = 'Error loading queue';
  }
}

async function ensureTagsCatalog() {
  if (tagsCatalog) return tagsCatalog;
  const res = await fetch('/api/tags');
  if (!res.ok) throw new Error(`tags catalog HTTP ${res.status}`);
  tagsCatalog = await res.json();
  return tagsCatalog;
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
  const directTags = (detail.directTags || []).slice().sort();
  return {
    actresses,
    descriptor,
    directTags: new Set(directTags),
    labelImpliedTags: new Set(detail.labelImpliedTags || []),
    coverStaged: null,
    coverDirty: false,
    hasExistingCover: !!detail.hasCover,
    initialActresses: JSON.stringify(actresses),
    initialDescriptor: descriptor,
    initialTags: JSON.stringify(directTags)
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
  renderDuplicateState();
  renderTags();

  // Cover preview
  const dup = isDuplicate();
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
    // Hide the "Drop image here" prompt in duplicate mode — the cover isn't editable.
    coverPlaceholder.style.display = dup ? 'none' : 'block';
  }
  coverLock.style.display = dup ? 'flex' : 'none';

  renderActresses();
  updateSaveEnabled();
}

function renderTags() {
  if (!editorState || !tagsCatalog) { tagsPanel.innerHTML = ''; return; }
  const dup = isDuplicate();
  const direct  = editorState.directTags;
  const implied = editorState.labelImpliedTags;

  tagsPanel.innerHTML = tagsCatalog.map(group => `
    <div class="queue-tag-group tag-cat-${esc(group.category)}">
      <div class="queue-tag-group-label">${esc(group.label)}</div>
      <div class="queue-tag-row">
        ${group.tags.map(t => {
          const isImplied = implied.has(t.name);
          const isActive  = direct.has(t.name) || isImplied;
          const cls = 'queue-tag-toggle'
                    + (isActive  ? ' active'    : '')
                    + (isImplied ? ' implicit'  : '')
                    + (dup       ? ' disabled'  : '');
          const title = isImplied
              ? `Implied by label (${esc(t.description || '')})`
              : esc(t.description || '');
          return `<button type="button" class="${cls}" data-tag="${esc(t.name)}" ${isImplied || dup ? 'disabled' : ''} title="${title}">${esc(t.name)}</button>`;
        }).join('')}
      </div>
    </div>
  `).join('');

  tagsPanel.querySelectorAll('.queue-tag-toggle:not(.implicit):not(.disabled)').forEach(btn => {
    btn.addEventListener('click', () => {
      const tag = btn.getAttribute('data-tag');
      if (editorState.directTags.has(tag)) editorState.directTags.delete(tag);
      else editorState.directTags.add(tag);
      btn.classList.toggle('active');
      updateSaveEnabled();
    });
  });
}

function isDuplicate() {
  return !!(currentDetail && currentDetail.duplicate);
}

function renderDuplicateState() {
  const dup = isDuplicate();
  duplicateBadge.style.display  = dup ? 'inline-flex' : 'none';
  duplicateBanner.style.display = dup ? 'block' : 'none';
  if (dup) {
    const locs = currentDetail.otherLocations || [];
    duplicateLocations.innerHTML = locs
        .map(l => `<li><span class="queue-duplicate-vol">${esc(l.volumeId)}</span><span class="queue-duplicate-path">${esc(l.path)}</span></li>`)
        .join('');
  }
  // Disable actress add in duplicate mode
  actressInput.disabled = dup;
  actressInput.placeholder = dup
      ? 'Actress edits are disabled for duplicates'
      : 'Type a name to search or create a new actress…';
  document.querySelector('.queue-actress-add')?.classList.toggle('disabled', dup);
  // Disable cover panel in duplicate mode
  coverPanel.classList.toggle('readonly', dup);
  coverPanel.setAttribute('aria-disabled', dup ? 'true' : 'false');
}

function renderActressHint() {
  if (!editorState || isDuplicate()) { actressHint.style.display = 'none'; return; }
  const count = editorState.actresses.length;
  const hasPrimary = editorState.actresses.some(a => a.primary);
  let msg = '';
  if (count === 0) msg = 'At least one actress is required before you can save.';
  else if (!hasPrimary) msg = 'Pick a primary actress (★). The folder will be renamed after her.';
  if (msg) {
    actressHint.textContent = msg;
    actressHint.style.display = 'block';
  } else {
    actressHint.style.display = 'none';
  }
}

function renderActresses() {
  actressList.innerHTML = '';
  const count = editorState.actresses.length;
  const dup = isDuplicate();
  editorState.actresses.forEach((a, idx) => {
    const li = document.createElement('li');
    li.className = 'queue-actress-item';
    const primaryBtn = document.createElement('button');
    primaryBtn.type = 'button';
    primaryBtn.className = 'queue-actress-primary-btn' + (a.primary ? ' active' : '');
    primaryBtn.textContent = a.primary ? '★' : '☆';
    primaryBtn.disabled = dup;
    primaryBtn.title = dup ? 'Locked (duplicate)'
                           : (a.primary ? 'Primary actress' : 'Mark as primary');
    primaryBtn.addEventListener('click', () => { if (!dup) setPrimary(idx); });

    const name = document.createElement('span');
    name.className = 'queue-actress-name';
    name.textContent = a.canonicalName;

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'queue-actress-remove-btn';
    remove.textContent = '×';
    remove.disabled = dup || count <= 1;
    remove.title = dup ? 'Locked (duplicate)'
                       : (count <= 1 ? 'At least one actress required' : 'Remove');
    remove.addEventListener('click', () => { if (!dup && count > 1) removeActress(idx); });

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
  renderActressHint();
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
  const items = Array.from(actressSuggest.querySelectorAll('.queue-actress-suggest-item:not(.disabled)'));
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
  // Clear highlight across all items (including disabled, which wouldn't be in the arrow list)
  actressSuggest.querySelectorAll('.queue-actress-suggest-item').forEach(el => el.classList.remove('highlight'));
  if (items[suggestHighlight]) items[suggestHighlight].classList.add('highlight');
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

// Bold-red highlight of the matched substring in a name. Case-insensitive.
function highlight(text, query) {
  if (!text) return '';
  const t = String(text);
  if (!query) return esc(t);
  const idx = t.toLowerCase().indexOf(query.toLowerCase());
  if (idx < 0) return esc(t);
  return esc(t.slice(0, idx))
       + '<strong class="queue-actress-match">' + esc(t.slice(idx, idx + query.length)) + '</strong>'
       + esc(t.slice(idx + query.length));
}

function renderSuggest(hits, query) {
  actressSuggest.innerHTML = '';
  suggestHighlight = -1;
  const takenIds   = new Set(editorState.actresses.filter(a => a.id != null).map(a => a.id));
  const takenNames = new Set(editorState.actresses.map(a => a.canonicalName.toLowerCase()));

  hits.forEach((h) => {
    const alreadyAdded = takenIds.has(h.id) || takenNames.has(h.canonicalName.toLowerCase());
    const item = document.createElement('div');
    item.className = 'queue-actress-suggest-item' + (alreadyAdded ? ' disabled' : '');

    const thumb = h.coverUrl
        ? `<div class="queue-actress-suggest-thumb" style="background-image:url(${esc(h.coverUrl)})"></div>`
        : `<div class="queue-actress-suggest-thumb queue-actress-suggest-thumb-empty"></div>`;

    const stage = h.stageName
        ? `<span class="queue-actress-suggest-stage">${highlight(h.stageName, query)}</span>` : '';
    const alias = h.matchedAlias
        ? `<span class="queue-actress-suggest-alias">a.k.a. ${highlight(h.matchedAlias, query)}</span>` : '';
    const tier  = h.tier
        ? `<span class="queue-actress-suggest-tier tier-${(h.tier || '').toLowerCase()}">${esc(h.tier)}</span>` : '';
    const count = h.titleCount > 0
        ? `<span class="queue-actress-suggest-count">${h.titleCount}</span>` : '';
    const addedBadge = alreadyAdded
        ? `<span class="queue-actress-suggest-added">already added</span>` : '';

    item.innerHTML = thumb
        + '<div class="queue-actress-suggest-body">'
        +   `<div class="queue-actress-suggest-row1">`
        +     `<span class="queue-actress-suggest-name">${highlight(h.canonicalName, query)}</span>`
        +     stage
        +     addedBadge
        +   `</div>`
        +   (alias || tier || count
              ? `<div class="queue-actress-suggest-row2">${alias}${tier}${count}</div>`
              : '')
        + '</div>';
    if (!alreadyAdded) item.addEventListener('click', () => addExisting(h));
    actressSuggest.appendChild(item);
  });

  const liveHits = hits.filter(h => !takenIds.has(h.id) && !takenNames.has(h.canonicalName.toLowerCase()));
  const exactMatch = liveHits.some(h => h.canonicalName.toLowerCase() === query.toLowerCase());
  if (!exactMatch && !takenNames.has(query.toLowerCase())) {
    const create = document.createElement('div');
    create.className = 'queue-actress-suggest-item queue-actress-suggest-create';
    create.innerHTML = `<div class="queue-actress-suggest-thumb queue-actress-suggest-thumb-empty"></div>`
                     + `<div class="queue-actress-suggest-body"><span class="queue-actress-suggest-name">+ Create "${esc(query)}"</span></div>`;
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
  if (isDuplicate()) return;
  e.preventDefault();
  coverPanel.classList.add('dragover');
});
coverPanel.addEventListener('dragleave', () => coverPanel.classList.remove('dragover'));
coverPanel.addEventListener('drop', async e => {
  if (isDuplicate()) { e.preventDefault(); return; }
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
  if (isDuplicate()) { e.preventDefault(); return; }
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
    updateSaveEnabled();
  };
  reader.readAsDataURL(file);
}

function stageUrl(url) {
  editorState.coverStaged = { kind: 'url', url, previewUrl: url };
  editorState.coverDirty = true;
  renderEditor();
  updateSaveEnabled();
}

// ── Save flow ────────────────────────────────────────────────────────────
function isDirty() {
  if (!editorState) return false;
  if (editorState.coverDirty) return true;
  if ((editorState.descriptor || '') !== (editorState.initialDescriptor || '')) return true;
  const currentTags = JSON.stringify([...editorState.directTags].sort());
  if (currentTags !== editorState.initialTags) return true;
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
  if (!descriptorIsValid()) return false;
  if (isDuplicate()) {
    return (editorState.descriptor || '') !== (editorState.initialDescriptor || '');
  }
  if (editorState.actresses.length === 0) return false;
  if (!editorState.actresses.some(a => a.primary)) return false;
  // Non-duplicate: enable when anything editable is dirty so user can resave tag-only changes.
  return isDirty();
}

saveBtn.addEventListener('click', async () => {
  if (!canSave()) return;
  setStatus('Saving…', '');
  saveBtn.disabled = true;

  try {
    // 1. Actress save (transactional with inline create)
    const dup = isDuplicate();
    const actressPayload = dup
      ? { descriptor: (editorState.descriptor || '').trim() || null }
      : {
          actresses: editorState.actresses.map(a => a.isNew
            ? { newName: a.newName }
            : { id: a.id }),
          primary: (() => {
            const p = editorState.actresses.find(a => a.primary);
            return p.isNew ? { newName: p.newName } : { id: p.id };
          })(),
          descriptor: (editorState.descriptor || '').trim() || null,
          tags: [...editorState.directTags].sort()
        };
    const actRes = await fetch(`/api/unsorted/titles/${currentId}/actresses`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(actressPayload)
    });
    if (!actRes.ok) throw new Error('Actresses: ' + await actRes.text());
    const actData = await actRes.json();

    // 2. Cover save (if staged) — skipped entirely for duplicates
    if (!dup && editorState.coverStaged) {
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
