// title-editor-draft.js
// Draft-mode editor pane: header + upstream-changed banner + cast-slot picker +
// cover preview + read-only metadata + enrichment tags + validate/promote/discard.
// See spec/PROPOSAL_DRAFT_MODE.md §11 (editor states, cast picker, actions).

import { esc } from './utils.js';
import { displayPath } from './path-utils.js';
import { mount as mountNearMissModal } from './near-miss-modal.js';
import { openAliasCaptureModal } from './alias-capture-modal.js';

// ── DOM refs ──────────────────────────��──────────────────────────��────────
const draftPane         = document.getElementById('queue-draft-pane');
const draftCodeEl       = document.getElementById('queue-draft-code');
const draftFolderEl     = document.getElementById('queue-draft-folder');

// ── Code copy ──────────────────────────────────────────────────────────────
// Click the code chip to copy it to the clipboard (mirrors the v2 editor).
// Class + listener attached once at init; the handler reads the current code
// from textContent at click time so it stays correct across re-renders.
draftCodeEl?.classList.add('queue-code-copyable');
draftCodeEl?.setAttribute('title', 'Click to copy');
draftCodeEl?.addEventListener('click', () => {
  const code = (draftCodeEl.textContent || '').trim();
  if (!code) return;
  navigator.clipboard?.writeText(code).then(() => {
    draftCodeEl.classList.add('queue-code-copied');
    setTimeout(() => draftCodeEl.classList.remove('queue-code-copied'), 1100);
  }).catch(() => {});
});
const upstreamBanner    = document.getElementById('queue-upstream-changed-banner');
const upstreamDiscardBtn  = document.getElementById('queue-upstream-discard-btn');
const upstreamContinueBtn = document.getElementById('queue-upstream-continue-btn');
const draftStatusLine   = document.getElementById('queue-draft-status-line');

const validateBtn  = document.getElementById('queue-draft-validate-btn');
const promoteBtn   = document.getElementById('queue-draft-promote-btn');
const discardBtn   = document.getElementById('queue-draft-discard-btn');
const draftSkipBtn = document.getElementById('queue-draft-skip-btn');
const bookmarkToggle = document.getElementById('queue-draft-bookmark-toggle');

const draftCoverImg        = document.getElementById('queue-draft-cover-img');
const draftCoverPlaceholder= document.getElementById('queue-draft-cover-placeholder');
const coverRefetchBtn      = document.getElementById('queue-draft-cover-refetch-btn');
const coverClearBtn        = document.getElementById('queue-draft-cover-clear-btn');
const draftCoverPanel      = document.getElementById('queue-draft-cover-panel');

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
let _nearMissListenerRegistered = false;

let _draft      = null;  // last fetched GET /api/drafts/:titleId response
let _titleId    = null;
let _folderName = null;
let _folderNasPath = null;
let _tagsCatalog = null;
let _directTags = null;   // Set<string> — mutable copy for tag toggles
let _upstreamBannerDismissed = false;

// Callbacks wired by mountDraftView.
let _onDiscard       = null;   // (titleId) → void — called after successful discard
let _onPromote       = null;   // (titleId) → void — called after successful promote
let _onSkip          = null;   // () → void
let _setParentStatus = null;   // (msg, cls) → void

// ── Stage-name polling ────────────────────────────────────────────────────
// Map from javdbSlug → timeoutId. Cleared on unmount and on each renderCastSlots call.
let _pollTimers = new Map();

// Set of javdbSlugs where the user has typed into a name input. Cleared on unmount,
// NOT on re-render — persists within a single mountDraftView session.
let _dirtySlots = new Set();

// Set of javdbSlugs currently being auto-filled; input events from these are suppressed.
let _suppressInput = new Set();

const POLL_MAX = 30;

function hasJpChar(s) {
  return s && /[ぁ-ゖァ-ヺ一-鿿]/.test(s);
}

/**
 * Splits LLM-returned romaji into {first, last} name parts.
 * Mirrors ActressFuzzyMatcher.splitRomaji exactly:
 *   null/blank → {first: null, last: null}
 *   1 token    → {first: null, last: token}
 *   2 tokens   → {first: tokens[0], last: tokens[1]}
 *   3+ tokens  → {first: tokens[0], last: rest joined by spaces}
 */
function splitRomaji(s) {
  if (!s || !s.trim()) return { first: null, last: null };
  const tokens = s.trim().split(/\s+/);
  if (tokens.length === 1) return { first: null, last: tokens[0] };
  if (tokens.length === 2) return { first: tokens[0], last: tokens[1] };
  return { first: tokens[0], last: tokens.slice(1).join(' ') };
}

// Stops all active polling timers. Called on unmount and before re-render.
function stopAllPolling() {
  for (const id of _pollTimers.values()) clearTimeout(id);
  _pollTimers.clear();
}

function removeCue(containerEl, slug) {
  const c = containerEl.querySelector(`.sn-autofill-cue[data-slug="${slug}"]`);
  if (c) c.remove();
}

function startPollForSlot(slot, headerEl, pickerEl) {
  const kanji = slot.stageName;
  let count = 0;

  function doPoll() {
    if (!_titleId) return;
    count++;
    fetch(`/api/translation/stage-name-status?kanji=${encodeURIComponent(kanji)}`)
      .then(r => r.json())
      .then(data => {
        if (!_titleId) return;
        if (data.status === 'ready') {
          removeBadge(headerEl, slot.javdbSlug);
          _pollTimers.delete(slot.javdbSlug);
          if (!_dirtySlots.has(slot.javdbSlug)) {
            applyAutoFill(headerEl, pickerEl, slot, data.romaji);
          }
          // dirty slot: silently stop — user has control
        } else if (data.status === 'queued') {
          ensureBadge(headerEl, slot.javdbSlug);
          if (count < POLL_MAX) {
            const ms = typeof window.__phase6dPollMs === 'number' ? window.__phase6dPollMs : 5000;
            const tid = setTimeout(doPoll, ms);
            _pollTimers.set(slot.javdbSlug, tid);
          } else {
            removeBadge(headerEl, slot.javdbSlug);
            _pollTimers.delete(slot.javdbSlug);
          }
        } else {
          // missing
          removeBadge(headerEl, slot.javdbSlug);
          _pollTimers.delete(slot.javdbSlug);
        }
      })
      .catch(() => {
        _pollTimers.delete(slot.javdbSlug);
      });
  }

  doPoll();
}

/**
 * Shared DOM helper: populates the create-new Last/First inputs in pickerEl,
 * opens the create-new form, and appends the autofill cue to headerEl.
 * Called both from applyAutoFill (poll path) and renderCastSlots (prefilled path).
 */
function _fillCreateNew(headerEl, pickerEl, slot, first, last) {
  if (!pickerEl) return;
  const lastInput  = pickerEl.querySelector('.queue-cast-picker-name-input[data-name-field="last"]');
  const firstInput = pickerEl.querySelector('.queue-cast-picker-name-input[data-name-field="first"]');
  if (!lastInput && !firstInput) return;

  // Treat pre-populated inputs as dirty — don't overwrite
  if ((lastInput && lastInput.value) || (firstInput && firstInput.value)) return;

  _suppressInput.add(slot.javdbSlug);
  try {
    if (last != null && lastInput && !lastInput.value) {
      lastInput.value = last;
      lastInput.dispatchEvent(new Event('input', { bubbles: true }));
    }
    if (first != null && firstInput && !firstInput.value) {
      firstInput.value = first;
      firstInput.dispatchEvent(new Event('input', { bubbles: true }));
    }
  } finally {
    _suppressInput.delete(slot.javdbSlug);
  }

  // Expand the create form so the cue and filled values are visible
  const createForm = pickerEl.querySelector('.queue-cast-picker-create-form');
  if (createForm) createForm.style.display = 'flex';

  // Show cue in the header (similar position to the B1 badge)
  if (!headerEl.querySelector(`.sn-autofill-cue[data-slug="${slot.javdbSlug}"]`)) {
    const cue = document.createElement('span');
    cue.className = 'sn-autofill-cue';
    cue.dataset.slug = slot.javdbSlug;
    cue.textContent = 'filled by translation — accept or edit';
    headerEl.appendChild(cue);
  }
}

/**
 * Auto-fills first/last name inputs in the Create-new form and shows an
 * "accepted or edit" cue. Skips inputs that already have a value.
 */
function applyAutoFill(headerEl, pickerEl, slot, romaji) {
  const { first, last } = splitRomaji(romaji);
  _fillCreateNew(headerEl, pickerEl, slot, first, last);
}

function ensureBadge(headerEl, slug) {
  if (!headerEl.querySelector('.sn-translating-badge')) {
    const badge = document.createElement('span');
    badge.className = 'sn-translating-badge';
    badge.dataset.slug = slug;
    badge.textContent = 'translating…';
    headerEl.appendChild(badge);
  }
}

function removeBadge(headerEl, slug) {
  const b = headerEl.querySelector('.sn-translating-badge');
  if (b) b.remove();
  const r = headerEl.querySelector('.sn-suggested-reveal');
  if (r) r.remove();
  const c = headerEl.querySelector('.sn-autofill-cue');
  if (c) c.remove();
}

// ── Public API ─────────────────────────────────���──────────────────────────

/**
 * Mount and render the draft pane for the given title + draft data.
 *
 * @param {number}   titleId        canonical title id
 * @param {string}   folderName     display name for the header
 * @param {string}   folderNasPath  full canonical NAS path (copy-clickable)
 * @param {object}   draft          GET /api/drafts/:titleId response
 * @param {Array}    tagsCatalog from GET /api/tags
 * @param {Array}    directTags current intrinsic tags for the title
 * @param {Function} onDiscard  called after discard completes
 * @param {Function} onPromote  called after promote completes
 * @param {Function} onSkip     called when Skip is clicked
 * @param {Function} setStatus  (msg, cls) → void for parent status
 */
export function mountDraftView(titleId, folderName, folderNasPath, draft, tagsCatalog, directTags,
                               onDiscard, onPromote, onSkip, setStatus) {
  _titleId    = titleId;
  _folderName = folderName;
  _folderNasPath = folderNasPath;
  _draft      = draft;
  _tagsCatalog = tagsCatalog;
  _directTags = new Set(directTags || []);
  _onDiscard  = onDiscard;
  _onPromote  = onPromote;
  _onSkip     = onSkip;
  _setParentStatus = setStatus;
  _upstreamBannerDismissed = false;

  if (!_nearMissListenerRegistered) {
    _nearMissListenerRegistered = true;
    window.addEventListener('near-miss-resolved', () => { if (_titleId) reloadDraft(); });
  }

  // Log editor session open to server for §5.4 measurement.
  fetch('/api/curation/editor-session-open', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ titleId }),
  }).catch(() => {});

  renderDraftPane();
}

/**
 * Unmount: wipe state and hide the pane. Called when navigating away.
 */
export function unmountDraftView() {
  stopAllPolling();
  _dirtySlots.clear();
  _draft   = null;
  _titleId = null;
  if (draftPane) draftPane.style.display = 'none';
}

// ── Render ───────────────────────────────────────────────────��────────────

/**
 * Render the header folder field: a "Folder" label + the full canonical NAS
 * path (copy-clickable, OS-adjusted) when folderNasPath is present; otherwise
 * the plain folder name with no copy affordance. Mirrors the no-draft pane.
 */
function renderFolderField(containerEl, folderNasPath, folderName) {
  if (!containerEl) return;
  if (folderNasPath) {
    containerEl.innerHTML =
      `<span class="queue-editor-folder-key">Folder</span>` +
      `<span class="queue-folder-path queue-code-copyable" data-path="${esc(folderNasPath)}" title="Click to copy full path">${esc(displayPath(folderNasPath))}</span>`;
    const pathEl = containerEl.querySelector('.queue-folder-path');
    pathEl?.addEventListener('click', () => {
      const raw = pathEl.dataset.path || '';
      if (!raw) return;
      const text = displayPath(raw.startsWith('//') ? 'smb:' + raw : raw);
      navigator.clipboard?.writeText(text).then(() => {
        pathEl.classList.add('queue-code-copied');
        setTimeout(() => pathEl.classList.remove('queue-code-copied'), 1100);
      }).catch(() => {});
    });
  } else {
    containerEl.innerHTML = `<span class="queue-editor-folder-key">Folder</span><span class="queue-folder-name">${esc(folderName || '')}</span>`;
  }
}

function renderDraftPane() {
  if (!_draft) return;

  draftPane.style.display = 'flex';
  draftCodeEl.textContent = _draft.code   || '';
  renderFolderField(draftFolderEl, _folderNasPath, _folderName);

  // Reset action button states — they may be stale from a previous title's operation.
  if (discardBtn)  discardBtn.disabled  = false;
  if (validateBtn) validateBtn.disabled = false;
  if (promoteBtn)  promoteBtn.disabled  = false;
  if (bookmarkToggle) bookmarkToggle.checked = !!_draft.bookmarkOnPromote;

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
  stopAllPolling();
  const cast = _draft.cast || [];
  castList.innerHTML = '';
  if (cast.length === 0) {
    // Empty-state: render Add-cast block with search, create-new, and
    // placeholder sentinel buttons. Warm the sentinel cache if needed.
    const li = document.createElement('li');
    li.className = 'queue-cast-slot';
    li.appendChild(buildAddCastBlock());
    castList.appendChild(li);
    return;
  }

  // Warm sentinel cache if any slot uses a sentinel resolution, so that
  // resolvedSummary() can show the canonical name synchronously after re-render.
  const hasSentinelSlot = cast.some(s => s.resolution?.startsWith?.('sentinel:'));
  if (hasSentinelSlot && !_sentinelsCache) {
    fetchSentinels().then(() => renderCastSlots());
  }

  // Clear-all button (non-empty list): render as first item inside castList.
  const clearLi = document.createElement('li');
  clearLi.className = 'queue-cast-clear-all-row';
  const clearAllBtn = document.createElement('button');
  clearAllBtn.type = 'button';
  clearAllBtn.className = 'queue-btn queue-btn-sm queue-btn-secondary';
  clearAllBtn.textContent = 'Clear all cast';
  clearAllBtn.addEventListener('click', () => clearAllCast());
  clearLi.appendChild(clearAllBtn);
  castList.appendChild(clearLi);

  for (let i = 0; i < cast.length; i++) {
    const slot = cast[i];
    const li = document.createElement('li');
    li.className = 'queue-cast-slot';
    li.dataset.idx = i;
    const content = buildSlotContent(slot, i);
    li.appendChild(content);
    castList.appendChild(li);

    const isUnresolved = !slot.resolution || slot.resolution === 'unresolved';
    const needsPoll = !slot.englishFirstName && !slot.englishLastName
      && slot.stageName && hasJpChar(slot.stageName);
    if (needsPoll) {
      const headerEl = li.querySelector('.queue-cast-slot-header');
      const pickerEl = li.querySelector('.queue-cast-picker');
      if (headerEl) startPollForSlot(slot, headerEl, pickerEl);
    } else if (isUnresolved && slot.englishLastName != null && !_dirtySlots.has(slot.javdbSlug)) {
      // Romaji already pre-filled by backend — surface it immediately using the
      // same DOM path as the poll's applyAutoFill, without splitRomaji (names
      // are already split into first/last by the server).
      const headerEl = li.querySelector('.queue-cast-slot-header');
      const pickerEl = li.querySelector('.queue-cast-picker');
      if (headerEl) _fillCreateNew(headerEl, pickerEl, slot, slot.englishFirstName, slot.englishLastName);
    }
  }

  // ── Add another actress (non-empty list) ───────────────────────────────
  // Reuses the proven manual-append path (addManualCast → manual:N slot).
  // No sentinels here — placeholders are empty-state only.
  const addLi = document.createElement('li');
  addLi.className = 'queue-cast-slot';
  addLi.appendChild(buildAddCastBlock({ includeSentinels: false, label: 'Add another actress' }));
  castList.appendChild(addLi);
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

  // "?" badge: shown on unresolved slots (link_to_existing_id IS NULL AND
  // link_to_draft_slug IS NULL AND english_last_name IS NULL per spec §3.1).
  // Sentinel slots are excluded by the resolution check.
  const isUnresolved = !slot.resolution || slot.resolution === 'unresolved';
  const isFilled = slot.linkToExistingId != null
    || slot.linkToDraftSlug != null
    || slot.englishLastName != null;
  if (isUnresolved && !isFilled && slot.stageName) {
    const nmBadge = document.createElement('button');
    nmBadge.type = 'button';
    nmBadge.className = 'nm-row-badge';
    nmBadge.title = 'Resolve identity';
    nmBadge.textContent = '?';
    nmBadge.addEventListener('click', () => {
      let mount = document.getElementById('near-miss-modal-mount');
      if (!mount) {
        mount = document.createElement('div');
        mount.id = 'near-miss-modal-mount';
        document.body.appendChild(mount);
      }
      mountNearMissModal(mount, { kanji: slot.stageName, primarySlug: slot.javdbSlug });
    });
    header.appendChild(nmBadge);
  }

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

  // Per-slot Remove button (both resolved and unresolved).
  const removeBtn = document.createElement('button');
  removeBtn.type = 'button';
  removeBtn.className = 'queue-btn queue-btn-sm queue-btn-secondary queue-cast-remove-btn';
  removeBtn.textContent = 'Remove';
  removeBtn.addEventListener('click', () => removeCast(slot.javdbSlug));
  frag.appendChild(removeBtn);

  return frag;
}

function resolvedSummary(slot) {
  const res = slot.resolution;
  if (res === 'pick') {
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
  if (res && res.startsWith('sentinel:')) {
    const sentinelId = String(res.slice('sentinel:'.length));
    const cache = _sentinelsCache || [];
    const found = cache.find(s => String(s.id) === sentinelId);
    return found ? `Placeholder: ${found.canonicalName}` : 'Placeholder';
  }
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
            // Capture current name inputs before DOM rebuild (advisor note: DOM is gone after renderCastSlots).
            const pickerEl = container;
            const lastEl  = pickerEl.querySelector('.queue-cast-picker-name-input[data-name-field="last"]');
            const firstEl = pickerEl.querySelector('.queue-cast-picker-name-input[data-name-field="first"]');
            const capturedLast  = lastEl  ? lastEl.value.trim()  : '';
            const capturedFirst = firstEl ? firstEl.value.trim() : '';
            patchResolution(slot.javdbSlug, 'pick', { linkToExistingId: h.id }, idx, () => {
              checkAndOpenAliasModal(h.id, slot.stageName, capturedFirst, capturedLast);
            });
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

  // NOTE: Sentinel dropdown removed. Sentinels are only available via the
  // empty-state Add-cast block (mutual exclusivity rule).

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
  lastInput.dataset.nameField = 'last';
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
  firstInput.dataset.nameField = 'first';
  firstInput.placeholder = 'First name (optional)';
  firstRow.appendChild(firstLabel);
  firstRow.appendChild(firstInput);

  // Dirty-flag tracking: any real (non-synthetic) input event marks this slot dirty.
  // Once dirty, auto-fill will not overwrite even if status flips ready later.
  function markDirtyAndRemoveCue() {
    if (_suppressInput.has(slot.javdbSlug)) return;
    _dirtySlots.add(slot.javdbSlug);
    // Remove the autofill cue since the user has taken ownership
    const headerEl = container.closest('.queue-cast-slot')
      ? container.closest('.queue-cast-slot').querySelector('.queue-cast-slot-header')
      : null;
    if (headerEl) removeCue(headerEl, slot.javdbSlug);
  }
  lastInput.addEventListener('input', markDirtyAndRemoveCue);
  firstInput.addEventListener('input', markDirtyAndRemoveCue);

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
    if (!res.ok) { _sentinelsCache = []; return []; }
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
    _sentinelsCache = [];
    return [];
  }
}

// ── Alias-capture check (Phase 6d Slice C) ────────────────────────────────

/**
 * After a successful 'pick' link, fetch the linked actress's canonical_name
 * and aliases, compute mismatch flags, and open the alias-capture modal if
 * either flag is true. Silent if the actress's names already cover both.
 *
 * @param {number}  actressId     - id of the linked actress
 * @param {string}  stageName     - slot's kanji stage name (may be blank)
 * @param {string}  capturedFirst - value of first-name input at click-time
 * @param {string}  capturedLast  - value of last-name input at click-time
 */
async function checkAndOpenAliasModal(actressId, stageName, capturedFirst, capturedLast) {
  if (!actressId) return;
  try {
    const res = await fetch(`/api/actresses/${actressId}`);
    if (!res.ok) return;
    const data = await res.json();
    const canonicalName = data.canonicalName || '';
    const aliasNames = (data.aliases || []).map(a => (typeof a === 'string' ? a : a.name)).filter(Boolean);

    const kanjiText  = (stageName || '').trim();
    const romajiText = capturedFirst
      ? `${capturedFirst} ${capturedLast}`.trim()
      : capturedLast.trim();

    const kanjiNeedsAlias  = kanjiText  && kanjiText  !== canonicalName && !aliasNames.includes(kanjiText);
    const romajiNeedsAlias = romajiText && romajiText !== canonicalName && !aliasNames.includes(romajiText);

    if (!kanjiNeedsAlias && !romajiNeedsAlias) return;

    openAliasCaptureModal({
      actressId,
      canonicalName,
      kanjiAlias:  kanjiNeedsAlias  ? kanjiText  : null,
      romajiAlias: romajiNeedsAlias ? romajiText : null,
    });
  } catch (err) {
    console.warn('checkAndOpenAliasModal failed', err);
  }
}

// ── Add-cast block (empty-state) ─────────────────────────────────────────

/**
 * Build the Add-cast block. Contains: search-existing, create-new, and
 * (empty-state only) placeholder (sentinel) buttons.
 * @param {{includeSentinels?: boolean, label?: string}} [opts] — sentinels are
 *        empty-state only (mutual-exclusivity rule); the non-empty "Add another
 *        actress" block passes includeSentinels:false.
 * @returns {HTMLElement}
 */
function buildAddCastBlock({ includeSentinels = true, label: labelText = 'Add cast' } = {}) {
  const block = document.createElement('div');
  block.className = 'queue-cast-add-block';

  const label = document.createElement('div');
  label.className = 'queue-cast-add-label';
  label.style.marginBottom = '6px';
  label.style.color = '#64748b';
  label.style.fontSize = '0.85rem';
  label.textContent = labelText;
  block.appendChild(label);

  const actionsRow = document.createElement('div');
  actionsRow.className = 'queue-cast-picker-actions';

  // ── Search existing actress ────────────────────────────────────────────
  const searchWrap = document.createElement('div');
  searchWrap.className = 'queue-cast-picker-search';
  const searchInput = document.createElement('input');
  searchInput.type = 'text';
  searchInput.className = 'queue-cast-picker-input';
  searchInput.placeholder = 'Search existing actress…';
  searchInput.autocomplete = 'off';
  const suggestBox = document.createElement('div');
  suggestBox.className = 'queue-cast-picker-suggest';
  suggestBox.style.display = 'none';

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
          item.textContent = (h.canonicalName || '') + (h.stageName ? ` (${h.stageName})` : '');
          item.addEventListener('click', () => {
            suggestBox.style.display = 'none';
            searchInput.value = '';
            addManualCast('pick', { linkToExistingId: h.id });
          });
          suggestBox.appendChild(item);
        });
        suggestBox.style.display = hits.length ? 'block' : 'none';
      } catch (err) {
        console.error('[draft] add-cast search failed', err);
      }
    }, 180);
  });
  document.addEventListener('click', e => {
    if (!searchWrap.contains(e.target)) suggestBox.style.display = 'none';
  }, { capture: false });
  searchWrap.appendChild(searchInput);
  searchWrap.appendChild(suggestBox);
  actionsRow.appendChild(searchWrap);

  // ── Create-new toggle button ───────────────────────────────────────────
  const createBtn = document.createElement('button');
  createBtn.type = 'button';
  createBtn.className = 'queue-btn queue-btn-sm';
  createBtn.textContent = 'Create new…';
  actionsRow.appendChild(createBtn);

  block.appendChild(actionsRow);

  // ── Create-new inline form (hidden by default) ─────────────────────────
  const createForm = document.createElement('div');
  createForm.className = 'queue-cast-picker-create-form';
  createForm.style.display = 'none';

  const lastRow = document.createElement('div');
  lastRow.className = 'queue-cast-picker-create-row';
  const lastLabel = document.createElement('label'); lastLabel.textContent = 'Last';
  const lastInput = document.createElement('input');
  lastInput.type = 'text';
  lastInput.className = 'queue-cast-picker-name-input';
  lastInput.dataset.nameField = 'last';
  lastInput.placeholder = 'Last name (required)';
  lastRow.appendChild(lastLabel); lastRow.appendChild(lastInput);

  const firstRow = document.createElement('div');
  firstRow.className = 'queue-cast-picker-create-row';
  const firstLabel = document.createElement('label'); firstLabel.textContent = 'First';
  const firstInput = document.createElement('input');
  firstInput.type = 'text';
  firstInput.className = 'queue-cast-picker-name-input';
  firstInput.dataset.nameField = 'first';
  firstInput.placeholder = 'First name (optional)';
  firstRow.appendChild(firstLabel); firstRow.appendChild(firstInput);

  const submitRow = document.createElement('div');
  submitRow.className = 'queue-cast-picker-create-row';
  const submitBtn = document.createElement('button');
  submitBtn.type = 'button';
  submitBtn.className = 'queue-btn queue-btn-sm queue-btn-primary';
  submitBtn.textContent = 'Save';
  const cancelBtn = document.createElement('button');
  cancelBtn.type = 'button';
  cancelBtn.className = 'queue-btn queue-btn-sm queue-btn-secondary';
  cancelBtn.textContent = 'Cancel';
  submitRow.appendChild(submitBtn); submitRow.appendChild(cancelBtn);

  createForm.appendChild(lastRow);
  createForm.appendChild(firstRow);
  createForm.appendChild(submitRow);
  block.appendChild(createForm);

  createBtn.addEventListener('click', () => {
    const showing = createForm.style.display === 'flex';
    createForm.style.display = showing ? 'none' : 'flex';
  });
  cancelBtn.addEventListener('click', () => {
    createForm.style.display = 'none';
  });
  submitBtn.addEventListener('click', () => {
    const lastName  = lastInput.value.trim();
    const firstName = firstInput.value.trim();
    if (!lastName) { lastInput.focus(); return; }
    addManualCast('create_new', { englishLastName: lastName, englishFirstName: firstName || null });
  });

  // ── Sentinel placeholder buttons (empty-state only) ─────────────────────
  // Mutual-exclusivity rule: sentinels (Amateur/Various/Unknown) are offered
  // only when the cast is empty, never on the "Add another actress" append.
  if (includeSentinels) {
    const sentinelRow = document.createElement('div');
    sentinelRow.className = 'queue-cast-sentinel-row';
    sentinelRow.style.marginTop = '6px';
    const sentinelLabel = document.createElement('span');
    sentinelLabel.style.fontSize = '0.8rem';
    sentinelLabel.style.color = '#64748b';
    sentinelLabel.style.marginRight = '6px';
    sentinelLabel.textContent = 'Placeholders:';
    sentinelRow.appendChild(sentinelLabel);

    fetchSentinels().then(sentinels => {
      sentinels.forEach(s => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'queue-btn queue-btn-sm queue-btn-secondary';
        btn.textContent = s.canonicalName;
        btn.addEventListener('click', () => addManualCast('sentinel:' + s.id, {}));
        sentinelRow.appendChild(btn);
      });
    });

    block.appendChild(sentinelRow);
  }
  return block;
}

// ── Low-level PATCH helper: send castResolutions array ───────────────────

/**
 * Post a castResolutions array to PATCH /api/drafts/:id.
 * Returns the updated `updatedAt` token on success, null on failure.
 * @param {Array} castResolutions
 * @returns {Promise<string|null>}
 */
async function sendPatch(castResolutions) {
  if (!_draft || !_titleId) return null;
  const payload = {
    expectedUpdatedAt: _draft.updatedAt,
    castResolutions,
    newActresses: [],
  };
  try {
    const res = await fetch(`/api/drafts/${_titleId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (res.status === 409) {
      showDraftStatus('Conflict — draft was updated elsewhere. Reloading…', 'error');
      await reloadDraft();
      return null;
    }
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      const errs = body.errors || [body.error || 'Unknown error'];
      showDraftStatus('Save failed: ' + errs.join(', '), 'error');
      return null;
    }
    const data = await res.json();
    _draft.updatedAt = data.updatedAt;
    return data.updatedAt;
  } catch (err) {
    showDraftStatus('Error: ' + (err.message || err), 'error');
    return null;
  }
}

// ── Add / Remove / Clear-all ────────────────────────────────────────────

/**
 * Add a manual cast slot (empty-state). Generates a unique synthetic slug
 * `manual:<n>` (smallest n not colliding with existing cast slugs), then
 * PATCHes the resolution and reloads.
 * @param {string} resolution  — 'pick' | 'create_new' | 'sentinel:<id>'
 * @param {object} extra       — { linkToExistingId?, englishLastName?, englishFirstName? }
 */
async function addManualCast(resolution, extra) {
  if (!_draft || !_titleId) return;
  const existingSlugs = new Set((_draft.cast || []).map(s => s.javdbSlug));
  let n = 1;
  while (existingSlugs.has(`manual:${n}`)) n++;
  const javdbSlug = `manual:${n}`;
  showDraftStatus('Adding…', '');
  const token = await sendPatch([{
    javdbSlug,
    resolution,
    linkToExistingId: extra?.linkToExistingId ?? null,
    englishLastName:  extra?.englishLastName  ?? null,
    englishFirstName: extra?.englishFirstName ?? null,
  }]);
  if (token != null) await reloadDraft();
}

/**
 * Remove one cast slot by javdbSlug, then reload.
 * @param {string} javdbSlug
 */
async function removeCast(javdbSlug) {
  if (!_draft || !_titleId) return;
  showDraftStatus('Removing…', '');
  const token = await sendPatch([{ javdbSlug, resolution: 'remove' }]);
  if (token != null) await reloadDraft();
}

/**
 * Remove all cast slots in one PATCH, then reload.
 */
async function clearAllCast() {
  if (!_draft || !_titleId) return;
  const slugs = (_draft.cast || []).map(s => s.javdbSlug).filter(Boolean);
  if (slugs.length === 0) return;
  showDraftStatus('Clearing cast…', '');
  const token = await sendPatch(slugs.map(javdbSlug => ({ javdbSlug, resolution: 'remove' })));
  if (token != null) await reloadDraft();
}

// ── PATCH cast resolution ─────────────────────────────────────────────────

async function patchResolution(javdbSlug, resolution, extra, idx, afterSuccess) {
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
    if (resolution === 'pick') {
      // 'pick' shows the linked actress's canonical name + avatar from the
      // server-side join; the optimistic slot only has the id, which renders the
      // unhelpful "Linked to existing actress (id:N)" fallback. Reload to fetch
      // the display data (reloadDraft re-fetches + re-renders the cast).
      await reloadDraft();
    } else {
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
      renderCastSlots();
    }
    showDraftStatus('', '');
    if (typeof afterSuccess === 'function') afterSuccess();
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

if (bookmarkToggle) {
  bookmarkToggle.addEventListener('change', async () => {
    if (!_titleId) return;
    const value = bookmarkToggle.checked;
    try {
      await fetch(`/api/drafts/${_titleId}/bookmark-on-promote`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value }),
      });
      if (_draft) _draft.bookmarkOnPromote = value;
    } catch (err) {
      console.error('set bookmark-on-promote failed', err);
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

// Cover drag-drop / paste staging (mirrors v2 mountDraftCoverPane)
if (draftCoverPanel) {
  const confirmCoverReplace = () =>
    (_draft && _draft.coverScratchPresent)
      ? confirm('Replace existing scratch cover?')
      : true;

  async function uploadCoverFile(file) {
    if (!_titleId || !file) return;
    try {
      const fd = new FormData();
      fd.append('file', file);
      const res = await fetch(`/api/drafts/${_titleId}/cover`, { method: 'POST', body: fd });
      if (res.ok) {
        _draft = { ..._draft, coverScratchPresent: true };
        renderCoverPreview(Date.now());
        showDraftStatus('Cover updated.', 'ok');
      } else {
        showDraftStatus('Upload failed: ' + (await res.text().catch(() => res.status)), 'error');
      }
    } catch (err) {
      showDraftStatus('Upload error: ' + (err.message || err), 'error');
    }
  }

  async function uploadCoverUrl(url) {
    if (!_titleId || !url) return;
    try {
      const res = await fetch(`/api/drafts/${_titleId}/cover`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url }),
      });
      if (res.ok) {
        _draft = { ..._draft, coverScratchPresent: true };
        renderCoverPreview(Date.now());
        showDraftStatus('Cover updated.', 'ok');
      } else {
        showDraftStatus('Upload failed: ' + (await res.text().catch(() => res.status)), 'error');
      }
    } catch (err) {
      showDraftStatus('Upload error: ' + (err.message || err), 'error');
    }
  }

  draftCoverPanel.addEventListener('dragover', e => {
    e.preventDefault();
    draftCoverPanel.classList.add('dragover');
  });
  draftCoverPanel.addEventListener('dragleave', () => draftCoverPanel.classList.remove('dragover'));
  draftCoverPanel.addEventListener('drop', e => {
    e.preventDefault();
    draftCoverPanel.classList.remove('dragover');
    if (!confirmCoverReplace()) return;
    const dt = e.dataTransfer;
    if (dt?.files && dt.files.length > 0) {
      uploadCoverFile(dt.files[0]);
    } else if (dt) {
      const url = (dt.getData('text/uri-list') || dt.getData('text/plain') || '').split('\n')[0].trim();
      if (url) uploadCoverUrl(url);
    }
  });
  draftCoverPanel.addEventListener('paste', e => {
    if (!confirmCoverReplace()) { e.preventDefault(); return; }
    const items = e.clipboardData?.items || [];
    for (const it of items) {
      if (it.type && it.type.startsWith('image/')) {
        const file = it.getAsFile();
        if (file) { uploadCoverFile(file); e.preventDefault(); return; }
      }
    }
    const text = e.clipboardData?.getData('text/plain');
    if (text) {
      const trimmed = text.trim();
      if (/^https?:\/\//i.test(trimmed)) { uploadCoverUrl(trimmed); e.preventDefault(); }
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
