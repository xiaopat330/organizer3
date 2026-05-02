// title-editor-draft.js
// Draft-mode editor pane: header + upstream-changed banner + cast-slot picker +
// cover preview + read-only metadata + enrichment tags + validate/promote/discard.
// See spec/PROPOSAL_DRAFT_MODE.md §11 (editor states, cast picker, actions).

import { esc } from './utils.js';

// ── DOM refs ──────────────────────────��──────────────────────────��────────
const draftPane         = document.getElementById('queue-draft-pane');
const draftCodeEl       = document.getElementById('queue-draft-code');
const draftFolderEl     = document.getElementById('queue-draft-folder');
const upstreamBanner    = document.getElementById('queue-upstream-changed-banner');
const upstreamDiscardBtn  = document.getElementById('queue-upstream-discard-btn');
const upstreamContinueBtn = document.getElementById('queue-upstream-continue-btn');
const draftStatusLine   = document.getElementById('queue-draft-status-line');

const validateBtn  = document.getElementById('queue-draft-validate-btn');
const promoteBtn   = document.getElementById('queue-draft-promote-btn');
const discardBtn   = document.getElementById('queue-draft-discard-btn');
const draftSkipBtn = document.getElementById('queue-draft-skip-btn');

const draftCoverImg        = document.getElementById('queue-draft-cover-img');
const draftCoverPlaceholder= document.getElementById('queue-draft-cover-placeholder');
const coverRefetchBtn      = document.getElementById('queue-draft-cover-refetch-btn');
const coverClearBtn        = document.getElementById('queue-draft-cover-clear-btn');

const metaTitle      = document.getElementById('queue-draft-meta-title');
const metaRatingRow  = document.getElementById('queue-draft-meta-rating-row');
const metaRating     = document.getElementById('queue-draft-meta-rating');
const metaRelease    = document.getElementById('queue-draft-meta-release');
const metaMaker      = document.getElementById('queue-draft-meta-maker');
const metaSeries     = document.getElementById('queue-draft-meta-series');

const castList     = document.getElementById('queue-draft-cast-list');
const draftTagsPanel = document.getElementById('queue-draft-tags-panel');
const promoteStatus  = document.getElementById('queue-draft-promote-status');

// ── State ────────────────────────────────────────────────────────────────
let _draft      = null;  // last fetched GET /api/drafts/:titleId response
let _titleId    = null;
let _folderName = null;
let _tagsCatalog = null;
let _directTags = null;   // Set<string> — mutable copy for tag toggles
let _upstreamBannerDismissed = false;

// Callbacks wired by mountDraftView.
let _onDiscard       = null;   // (titleId) → void — called after successful discard
let _onPromote       = null;   // (titleId) → void — called after successful promote
let _onSkip          = null;   // () → void
let _setParentStatus = null;   // (msg, cls) → void

// ── Public API ─────────────────────────────────���──────────────────────────

/**
 * Mount and render the draft pane for the given title + draft data.
 *
 * @param {number}   titleId    canonical title id
 * @param {string}   folderName display name for the header
 * @param {object}   draft      GET /api/drafts/:titleId response
 * @param {Array}    tagsCatalog from GET /api/tags
 * @param {Array}    directTags current intrinsic tags for the title
 * @param {Function} onDiscard  called after discard completes
 * @param {Function} onPromote  called after promote completes
 * @param {Function} onSkip     called when Skip is clicked
 * @param {Function} setStatus  (msg, cls) → void for parent status
 */
export function mountDraftView(titleId, folderName, draft, tagsCatalog, directTags,
                               onDiscard, onPromote, onSkip, setStatus) {
  _titleId    = titleId;
  _folderName = folderName;
  _draft      = draft;
  _tagsCatalog = tagsCatalog;
  _directTags = new Set(directTags || []);
  _onDiscard  = onDiscard;
  _onPromote  = onPromote;
  _onSkip     = onSkip;
  _setParentStatus = setStatus;
  _upstreamBannerDismissed = false;

  renderDraftPane();
}

/**
 * Unmount: wipe state and hide the pane. Called when navigating away.
 */
export function unmountDraftView() {
  _draft   = null;
  _titleId = null;
  if (draftPane) draftPane.style.display = 'none';
}

// ── Render ───────────────────────────────────────────────────��────────────

function renderDraftPane() {
  if (!_draft) return;

  draftPane.style.display = 'flex';
  draftCodeEl.textContent = _draft.code   || '';
  draftFolderEl.textContent = _folderName || '';

  // Reset action button states — they may be stale from a previous title's operation.
  if (discardBtn)  discardBtn.disabled  = false;
  if (validateBtn) validateBtn.disabled = false;
  if (promoteBtn)  promoteBtn.disabled  = false;

  renderUpstreamBanner();
  renderCoverPreview();
  renderMetadata();
  renderCastSlots();
  renderTags();
  showDraftStatus('', '');
}

function renderUpstreamBanner() {
  if (_draft.upstreamChanged && !_upstreamBannerDismissed) {
    upstreamBanner.style.display = '';
  } else {
    upstreamBanner.style.display = 'none';
  }
}

function renderCoverPreview(cacheBuster) {
  const bust = cacheBuster || Date.now();
  if (_draft.coverScratchPresent) {
    draftCoverImg.src = `/api/drafts/${_titleId}/cover?t=${bust}`;
    draftCoverImg.style.display = 'block';
    draftCoverPlaceholder.style.display = 'none';
  } else {
    draftCoverImg.style.display = 'none';
    draftCoverPlaceholder.style.display = 'block';
  }
}

function renderMetadata() {
  const enr = _draft.enrichment || {};
  metaTitle.textContent   = _draft.titleOriginal || '';
  metaRelease.textContent = _draft.releaseDate
    ? new Date(_draft.releaseDate + 'T00:00:00').toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' })
    : '';
  metaMaker.textContent   = enr.maker            || '';
  metaSeries.textContent  = enr.series           || '';

  // Rating + grade row (hidden when no rating data).
  if (metaRatingRow && metaRating && enr.ratingAvg != null && enr.ratingCount != null) {
    metaRatingRow.style.display = '';
    const avg   = Number(enr.ratingAvg).toFixed(2);
    const votes = Number(enr.ratingCount).toLocaleString();
    metaRating.innerHTML = enr.grade
      ? `<span class="grade-badge" data-grade="${esc(enr.grade)}">${esc(enr.grade)}</span> <span class="queue-draft-rating-raw">${avg} · ${votes} votes</span>`
      : `<span class="queue-draft-rating-raw">${avg} · ${votes} votes</span>`;
  } else if (metaRatingRow) {
    metaRatingRow.style.display = 'none';
  }
}

function renderCastSlots() {
  if (!castList) return;
  const cast = _draft.cast || [];
  castList.innerHTML = '';
  if (cast.length === 0) {
    const li = document.createElement('li');
    li.className = 'queue-cast-slot';
    li.style.color = '#64748b';
    li.style.fontSize = '0.85rem';
    li.textContent = 'No cast slots — javdb returned 0 stage names.';
    castList.appendChild(li);
    return;
  }
  for (let i = 0; i < cast.length; i++) {
    const slot = cast[i];
    const li = document.createElement('li');
    li.className = 'queue-cast-slot';
    li.dataset.idx = i;
    li.appendChild(buildSlotContent(slot, i));
    castList.appendChild(li);
  }
}

function buildSlotContent(slot, idx) {
  const frag = document.createDocumentFragment();

  // Header row.
  const header = document.createElement('div');
  header.className = 'queue-cast-slot-header';

  const stage = document.createElement('span');
  stage.className = 'queue-cast-slot-stage';
  stage.textContent = slot.stageName || '(no stage name)';
  header.appendChild(stage);

  const slug = document.createElement('span');
  slug.className = 'queue-cast-slot-slug';
  slug.textContent = slot.javdbSlug;
  header.appendChild(slug);

  const resBadge = document.createElement('span');
  resBadge.className = 'queue-cast-slot-resolution ' + resolutionCls(slot.resolution);
  resBadge.textContent = resolutionLabel(slot.resolution);
  header.appendChild(resBadge);

  frag.appendChild(header);

  // For resolved (non-unresolved) slots: show summary + unlink button.
  if (slot.resolution && slot.resolution !== 'unresolved') {
    // For linked slots: show avatar + canonical name side-by-side.
    if (slot.resolution === 'pick' && (slot.linkedActressName || slot.linkedActressAvatarUrl)) {
      const linked = document.createElement('div');
      linked.className = 'queue-cast-linked-actress';
      if (slot.linkedActressAvatarUrl) {
        const img = document.createElement('img');
        img.className = 'queue-cast-linked-avatar';
        img.src = slot.linkedActressAvatarUrl;
        img.alt = slot.linkedActressName || '';
        linked.appendChild(img);
      }
      if (slot.linkedActressName) {
        const name = document.createElement('span');
        name.className = 'queue-cast-linked-name';
        name.textContent = slot.linkedActressName;
        linked.appendChild(name);
      }
      frag.appendChild(linked);
    } else {
      const summary = document.createElement('div');
      summary.className = 'queue-cast-slot-name';
      summary.textContent = resolvedSummary(slot);
      frag.appendChild(summary);
    }

    const unlinkBtn = document.createElement('button');
    unlinkBtn.type = 'button';
    unlinkBtn.className = 'queue-btn queue-btn-sm queue-btn-secondary';
    unlinkBtn.textContent = 'Unlink and pick different';
    unlinkBtn.addEventListener('click', () => patchResolution(slot.javdbSlug, 'unresolved', {}, idx));
    frag.appendChild(unlinkBtn);

  } else {
    // Unresolved: render picker.
    frag.appendChild(buildPicker(slot, idx));
  }

  return frag;
}

function resolvedSummary(slot) {
  const res = slot.resolution;
  if (res === 'pick') {
    const name = slot.stageName || '';
    const link = slot.linkToExistingId ? `id:${slot.linkToExistingId}` : '';
    return `Linked to existing actress${link ? ' (' + link + ')' : ''}`;
  }
  if (res === 'create_new') {
    const last  = slot.englishLastName  || '';
    const first = slot.englishFirstName || '';
    const full  = first ? `${first} ${last}` : last;
    return `Create new: ${full || '(name pending)'}`;
  }
  if (res === 'skip') return 'Skipped — will not be linked';
  if (res && res.startsWith('sentinel:')) return 'Replaced with sentinel actress';
  return res || '';
}

function resolutionLabel(res) {
  if (!res || res === 'unresolved') return 'unresolved';
  if (res === 'pick')       return 'linked';
  if (res === 'create_new') return 'create new';
  if (res === 'skip')       return 'skip';
  if (res.startsWith('sentinel:')) return 'sentinel';
  return res;
}

function resolutionCls(res) {
  if (!res || res === 'unresolved') return 'queue-cast-res-unresolved';
  if (res === 'pick')       return 'queue-cast-res-pick';
  if (res === 'create_new') return 'queue-cast-res-create';
  if (res === 'skip')       return 'queue-cast-res-skip';
  if (res.startsWith('sentinel:')) return 'queue-cast-res-sentinel';
  return 'queue-cast-res-unresolved';
}

// ── Cast-slot picker ────────────────────────────────���─────────────────────

function buildPicker(slot, idx) {
  const container = document.createElement('div');
  container.className = 'queue-cast-picker';

  const actionsRow = document.createElement('div');
  actionsRow.className = 'queue-cast-picker-actions';

  // Search existing actress.
  const searchWrap = document.createElement('div');
  searchWrap.className = 'queue-cast-picker-search';

  const searchInput = document.createElement('input');
  searchInput.type = 'text';
  searchInput.className = 'queue-cast-picker-input';
  searchInput.placeholder = 'Search existing actress…';
  searchInput.autocomplete = 'off';

  const suggestBox = document.createElement('div');
  suggestBox.className = 'queue-cast-picker-suggest';

  let searchSeq = 0;
  let debounce  = null;
  searchInput.addEventListener('input', () => {
    clearTimeout(debounce);
    debounce = setTimeout(async () => {
      const q = searchInput.value.trim();
      if (q.length < 1) { suggestBox.style.display = 'none'; return; }
      const seq = ++searchSeq;
      try {
        const res = await fetch(`/api/unsorted/actresses/search?q=${encodeURIComponent(q)}&limit=8`);
        if (seq !== searchSeq) return;
        const hits = await res.json();
        suggestBox.innerHTML = '';
        hits.forEach(h => {
          const item = document.createElement('div');
          item.className = 'queue-cast-picker-suggest-item';
          item.textContent = h.canonicalName + (h.stageName ? ` (${h.stageName})` : '');
          item.addEventListener('click', () => {
            suggestBox.style.display = 'none';
            searchInput.value = '';
            patchResolution(slot.javdbSlug, 'pick', { linkToExistingId: h.id }, idx);
          });
          suggestBox.appendChild(item);
        });
        suggestBox.style.display = hits.length ? 'block' : 'none';
      } catch (err) {
        console.error('picker search failed', err);
      }
    }, 180);
  });

  document.addEventListener('click', e => {
    if (!searchWrap.contains(e.target)) suggestBox.style.display = 'none';
  }, { capture: false });

  searchWrap.appendChild(searchInput);
  searchWrap.appendChild(suggestBox);
  actionsRow.appendChild(searchWrap);

  // Create new button.
  const createBtn = document.createElement('button');
  createBtn.type = 'button';
  createBtn.className = 'queue-btn queue-btn-sm';
  createBtn.textContent = 'Create new…';
  actionsRow.appendChild(createBtn);

  // SKIP button (multi-actress mode only: ≥2 cast slots).
  const totalSlots = _draft.cast ? _draft.cast.length : 0;
  if (totalSlots >= 2) {
    const skipBtn2 = document.createElement('button');
    skipBtn2.type = 'button';
    skipBtn2.className = 'queue-btn queue-btn-sm queue-btn-secondary';
    skipBtn2.textContent = 'Skip';
    skipBtn2.addEventListener('click', () =>
      patchResolution(slot.javdbSlug, 'skip', {}, idx));
    actionsRow.appendChild(skipBtn2);
  }

  // Sentinel dropdown (0 stage_names or multi-actress mode).
  if (totalSlots === 0 || totalSlots >= 2) {
    const sentinelSelect = document.createElement('select');
    sentinelSelect.className = 'queue-cast-picker-input';
    sentinelSelect.style.width = '130px';
    const sentinelDefault = document.createElement('option');
    sentinelDefault.value = '';
    sentinelDefault.textContent = 'Sentinel…';
    sentinelSelect.appendChild(sentinelDefault);

    // Load sentinels lazily.
    fetchSentinels().then(sentinels => {
      sentinels.forEach(s => {
        const opt = document.createElement('option');
        opt.value = 'sentinel:' + s.id;
        opt.textContent = s.canonicalName;
        sentinelSelect.appendChild(opt);
      });
    });

    sentinelSelect.addEventListener('change', () => {
      const val = sentinelSelect.value;
      if (!val) return;
      patchResolution(slot.javdbSlug, val, {}, idx);
      sentinelSelect.value = '';
    });
    actionsRow.appendChild(sentinelSelect);
  }

  container.appendChild(actionsRow);

  // Create-new inline form (hidden by default).
  const createForm = document.createElement('div');
  createForm.className = 'queue-cast-picker-create-form';

  const lastRow = document.createElement('div');
  lastRow.className = 'queue-cast-picker-create-row';
  const lastLabel = document.createElement('label');
  lastLabel.textContent = 'Last';
  const lastInput = document.createElement('input');
  lastInput.type = 'text';
  lastInput.className = 'queue-cast-picker-name-input';
  lastInput.placeholder = 'Last name (required)';
  lastRow.appendChild(lastLabel);
  lastRow.appendChild(lastInput);

  const firstRow = document.createElement('div');
  firstRow.className = 'queue-cast-picker-create-row';
  const firstLabel = document.createElement('label');
  firstLabel.textContent = 'First';
  const firstInput = document.createElement('input');
  firstInput.type = 'text';
  firstInput.className = 'queue-cast-picker-name-input';
  firstInput.placeholder = 'First name (optional)';
  firstRow.appendChild(firstLabel);
  firstRow.appendChild(firstInput);

  const submitRow = document.createElement('div');
  submitRow.className = 'queue-cast-picker-create-row';
  const submitBtn = document.createElement('button');
  submitBtn.type = 'button';
  submitBtn.className = 'queue-btn queue-btn-sm queue-btn-primary';
  submitBtn.textContent = 'Save';
  const cancelCreate = document.createElement('button');
  cancelCreate.type = 'button';
  cancelCreate.className = 'queue-btn queue-btn-sm queue-btn-secondary';
  cancelCreate.textContent = 'Cancel';
  submitRow.appendChild(submitBtn);
  submitRow.appendChild(cancelCreate);

  createForm.appendChild(lastRow);
  createForm.appendChild(firstRow);
  createForm.appendChild(submitRow);
  container.appendChild(createForm);

  // Wire create button toggle.
  createBtn.addEventListener('click', () => {
    const showing = createForm.style.display === 'flex';
    createForm.style.display = showing ? 'none' : 'flex';
  });
  cancelCreate.addEventListener('click', () => {
    createForm.style.display = 'none';
  });
  submitBtn.addEventListener('click', () => {
    const lastName  = lastInput.value.trim();
    const firstName = firstInput.value.trim();
    if (!lastName) {
      lastInput.focus();
      return;
    }
    patchResolution(slot.javdbSlug, 'create_new',
      { englishLastName: lastName, englishFirstName: firstName || null }, idx);
  });

  return container;
}

let _sentinelsCache = null;
async function fetchSentinels() {
  if (_sentinelsCache) return _sentinelsCache;
  try {
    const res = await fetch('/api/actresses?sentinel=true&limit=20');
    if (!res.ok) return [];
    const data = await res.json();
    // data may be an object with items or a plain array depending on endpoint.
    const arr = Array.isArray(data) ? data : (data.items || []);
    _sentinelsCache = arr.filter(a => a.isSentinel || a.is_sentinel);
    if (!_sentinelsCache.length) {
      // Fallback: search by name.
      const r2 = await fetch('/api/unsorted/actresses/search?q=Various&limit=5');
      const hits = await r2.json();
      _sentinelsCache = hits.filter(h => h.isSentinel || h.is_sentinel);
    }
    return _sentinelsCache;
  } catch (err) {
    console.warn('fetchSentinels failed', err);
    return [];
  }
}

// ── PATCH cast resolution ────────────────────────────────���────────────────

async function patchResolution(javdbSlug, resolution, extra, idx) {
  if (!_draft || !_titleId) return;

  const payload = {
    expectedUpdatedAt: _draft.updatedAt,
    castResolutions: [{
      javdbSlug,
      resolution,
      linkToExistingId:  extra.linkToExistingId  || null,
      englishLastName:   extra.englishLastName   || null,
      englishFirstName:  extra.englishFirstName  || null,
    }],
    newActresses: [],
  };

  showDraftStatus('Saving…', '');
  try {
    const res = await fetch(`/api/drafts/${_titleId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });

    if (res.status === 409) {
      showDraftStatus('Conflict — draft was updated elsewhere. Reloading…', 'error');
      await reloadDraft();
      return;
    }

    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      const errs = body.errors || [body.error || 'Unknown error'];
      showDraftStatus('Save failed: ' + errs.join(', '), 'error');
      return;
    }

    const data = await res.json();
    // Update the local draft's updated_at token.
    _draft = { ..._draft, updatedAt: data.updatedAt };
    // Update the local cast slot optimistically.
    if (_draft.cast && _draft.cast[idx]) {
      const slot = _draft.cast[idx];
      _draft.cast[idx] = {
        ...slot,
        resolution,
        linkToExistingId: extra.linkToExistingId || slot.linkToExistingId,
        englishLastName:  extra.englishLastName  || slot.englishLastName,
        englishFirstName: extra.englishFirstName || slot.englishFirstName,
      };
    }
    showDraftStatus('', '');
    renderCastSlots();
  } catch (err) {
    console.error('patchResolution failed', err);
    showDraftStatus('Error: ' + (err.message || err), 'error');
  }
}

async function reloadDraft() {
  if (!_titleId) return;
  try {
    const res = await fetch(`/api/drafts/${_titleId}`);
    if (res.ok) {
      _draft = await res.json();
      renderDraftPane();
    }
  } catch (err) {
    console.error('reloadDraft failed', err);
  }
}

// ── Tags ──────────────────────────────────────────────────────────────────

function renderTags() {
  if (!draftTagsPanel || !_tagsCatalog) return;

  const direct  = _directTags;
  const enrTags = buildEnrTagSet();

  draftTagsPanel.innerHTML = _tagsCatalog.map(group => `
    <div class="queue-tag-group tag-cat-${esc(group.category)}">
      <div class="queue-tag-group-label">${esc(group.label)}</div>
      <div class="queue-tag-row">
        ${group.tags.map(t => {
          const isEnr    = enrTags.has(t.name);
          const isActive = direct.has(t.name) || isEnr;
          const cls = 'queue-tag-toggle'
                    + (isActive ? ' active'   : '')
                    + (isEnr   ? ' implicit'  : '');
          const title = isEnr
              ? `Enrichment tag (read-only) — ${esc(t.description || '')}`
              : esc(t.description || '');
          return `<button type="button" class="${cls}" data-tag="${esc(t.name)}"
                    ${isEnr ? 'disabled title="' + title + '"' : 'title="' + title + '"'}>${esc(t.name)}</button>`;
        }).join('')}
      </div>
    </div>
  `).join('');

  draftTagsPanel.querySelectorAll('.queue-tag-toggle:not(.implicit)').forEach(btn => {
    btn.addEventListener('click', () => {
      const tag = btn.getAttribute('data-tag');
      if (_directTags.has(tag)) _directTags.delete(tag);
      else _directTags.add(tag);
      btn.classList.toggle('active');
      saveIntrinsicTags();
    });
  });
}

function buildEnrTagSet() {
  const set = new Set();
  const enr = _draft.enrichment;
  if (!enr) return set;
  // Prefer pre-resolved canonical names from the backend (via curated_alias).
  if (Array.isArray(enr.resolvedTags)) {
    enr.resolvedTags.forEach(t => { if (t) set.add(t); });
    return set;
  }
  // Fall back to raw tagsJson (only matches if tags happen to use canonical names).
  if (!enr.tagsJson) return set;
  try {
    const raw = JSON.parse(enr.tagsJson);
    (raw || []).forEach(t => {
      const name = typeof t === 'string' ? t : (t.name || t.tag || '');
      if (name) set.add(name);
    });
  } catch (_) {}
  return set;
}

async function saveIntrinsicTags() {
  if (!_titleId) return;
  const tags = [..._directTags].sort();
  try {
    // Intrinsic tags use the canonical UnsortedEditor endpoint directly,
    // NOT the draft tables — per spec §11.1.
    await fetch(`/api/unsorted/titles/${_titleId}/actresses`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tags }),
    });
  } catch (err) {
    console.warn('saveIntrinsicTags failed', err);
  }
}

// ── Validate + Promote + Discard ───────────────────────────────��──────────

function showDraftStatus(msg, cls) {
  if (!draftStatusLine) return;
  if (!msg) {
    draftStatusLine.style.display = 'none';
    draftStatusLine.textContent = '';
    draftStatusLine.className = 'queue-draft-status-line';
    return;
  }
  draftStatusLine.textContent = msg;
  draftStatusLine.className = 'queue-draft-status-line' + (cls ? ' ' + cls : '');
  draftStatusLine.style.display = '';
}

if (validateBtn) {
  validateBtn.addEventListener('click', async () => {
    if (!_titleId || !_draft) return;
    validateBtn.disabled = true;
    showDraftStatus('Validating…', '');
    try {
      const res = await fetch(`/api/drafts/${_titleId}/validate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ expectedUpdatedAt: _draft.updatedAt }),
      });
      if (res.status === 404) {
        showDraftStatus('Draft not found — may have been discarded.', 'error');
        return;
      }
      const data = await res.json();
      if (data.ok) {
        showDraftStatus('Ready to promote.', 'ok');
      } else {
        const msgs = (data.errors || []).join(', ');
        showDraftStatus('Validation failed: ' + msgs, 'error');
      }
    } catch (err) {
      showDraftStatus('Validate error: ' + (err.message || err), 'error');
    } finally {
      validateBtn.disabled = false;
    }
  });
}

if (promoteBtn) {
  promoteBtn.addEventListener('click', async () => {
    if (!_titleId || !_draft) return;
    promoteBtn.disabled = true;
    validateBtn.disabled = true;
    showDraftStatus('Promoting…', '');

    try {
      // Step 1: validate.
      const vRes = await fetch(`/api/drafts/${_titleId}/validate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ expectedUpdatedAt: _draft.updatedAt }),
      });
      const vData = await vRes.json();
      if (!vData.ok) {
        const msgs = (vData.errors || []).join(', ');
        showDraftStatus('Promotion blocked: ' + msgs, 'error');
        return;
      }

      // Step 2: promote.
      const pRes = await fetch(`/api/drafts/${_titleId}/promote`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ expectedUpdatedAt: _draft.updatedAt }),
      });

      if (pRes.status === 409) {
        showDraftStatus('Conflict — draft was updated elsewhere. Reloading…', 'error');
        await reloadDraft();
        return;
      }
      if (pRes.status === 422) {
        const d = await pRes.json().catch(() => ({}));
        const msgs = (d.errors || []).join(', ');
        showDraftStatus('Promotion failed (pre-flight): ' + msgs, 'error');
        return;
      }
      if (!pRes.ok) {
        const d = await pRes.json().catch(() => ({}));
        showDraftStatus('Promotion failed: ' + (d.error || d.detail || pRes.status), 'error');
        return;
      }

      // Success!
      showDraftStatus('Promoted!', 'ok');
      if (_onPromote) _onPromote(_titleId);

    } catch (err) {
      showDraftStatus('Promote error: ' + (err.message || err), 'error');
    } finally {
      promoteBtn.disabled  = false;
      validateBtn.disabled = false;
    }
  });
}

if (discardBtn) {
  discardBtn.addEventListener('click', async () => {
    if (!_titleId || !_draft) return;
    if (!confirm('Discard this draft? This will drop all draft work; the title returns to Queue.')) return;

    discardBtn.disabled = true;
    showDraftStatus('Discarding…', '');
    try {
      const res = await fetch(`/api/drafts/${_titleId}`, { method: 'DELETE' });
      if (res.status === 404 || res.status === 204) {
        if (_onDiscard) _onDiscard(_titleId);
      } else {
        showDraftStatus('Discard failed: HTTP ' + res.status, 'error');
        discardBtn.disabled = false;
      }
    } catch (err) {
      showDraftStatus('Discard error: ' + (err.message || err), 'error');
      discardBtn.disabled = false;
    }
  });
}

if (draftSkipBtn) {
  draftSkipBtn.addEventListener('click', () => {
    if (_onSkip) _onSkip();
  });
}

// ── Cover management ──────────────────────────────────────────────────���───

if (coverRefetchBtn) {
  coverRefetchBtn.addEventListener('click', async () => {
    if (!_titleId) return;
    coverRefetchBtn.disabled = true;
    coverRefetchBtn.textContent = 'Fetching…';
    showDraftStatus('Fetching cover…', '');
    try {
      const res = await fetch(`/api/drafts/${_titleId}/cover/refetch`, { method: 'POST' });
      if (res.ok) {
        _draft = { ..._draft, coverScratchPresent: true };
        renderCoverPreview(Date.now());
        showDraftStatus('Cover updated.', 'ok');
      } else if (res.status === 422) {
        showDraftStatus('No cover URL on file — populate first.', 'error');
      } else {
        showDraftStatus('Refetch failed: HTTP ' + res.status, 'error');
      }
    } catch (err) {
      showDraftStatus('Refetch error: ' + (err.message || err), 'error');
    } finally {
      coverRefetchBtn.disabled = false;
      coverRefetchBtn.textContent = 'Refetch cover';
    }
  });
}

if (coverClearBtn) {
  coverClearBtn.addEventListener('click', async () => {
    if (!_titleId) return;
    coverClearBtn.disabled = true;
    try {
      await fetch(`/api/drafts/${_titleId}/cover`, { method: 'DELETE' });
      _draft = { ..._draft, coverScratchPresent: false };
      renderCoverPreview();
      showDraftStatus('Cover cleared.', 'ok');
    } catch (err) {
      showDraftStatus('Clear error: ' + (err.message || err), 'error');
    } finally {
      coverClearBtn.disabled = false;
    }
  });
}

// ── Upstream-changed banner buttons ──────────────────────────────��────────

if (upstreamDiscardBtn) {
  upstreamDiscardBtn.addEventListener('click', () => {
    // Delegate to discard button logic.
    if (discardBtn) discardBtn.click();
  });
}

if (upstreamContinueBtn) {
  upstreamContinueBtn.addEventListener('click', () => {
    _upstreamBannerDismissed = true;
    upstreamBanner.style.display = 'none';
  });
}
