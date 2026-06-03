/* ─────────────────────────────────────────────────────────────────────
   unprocessed/cast-pane.js — cast slot list with picker + translation
   polling (Wave 4).

   Responsibilities:
     - Render slots: stage name, javdb slug, resolution badge.
     - Resolved slot: linked-actress avatar / summary + Unlink button.
     - Unresolved slot: full picker — search typeahead, create-new
       inline form (last/first), Skip (≥2 slots).
     - Empty-cast state: Add-cast block with search existing, create new,
       and placeholder (sentinel) buttons. Mutually exclusive with slots.
     - Per-slot Remove button; Clear all cast button when list is non-empty.
     - Stage-name translation polling loop (30 × 5s) for slots with
       Japanese stage names but no english_first/last yet.
     - Auto-fill dirty-slot guard; auto-fill cue.
     - Near-Miss "?" badge → mountNearMissModal.
     - Alias-capture check after pick → openAliasCaptureModal.

   Pure UI: PATCH semantics live in draft.js via callbacks
   (onResolve, onUnlink, onAddManual, onRemove, onClearAll). State for
   timers/dirty/suppress/sentinels lives on shared `state` (per spec §4.2)
   — not module-local — so they survive re-renders and are torn down
   cleanly on destroy().

   Exported:
     mountCastPane(containerEl, state, {
       onResolve, onUnlink, onReload,
       onAddManual, onRemove, onClearAll
     }) → { renderCast, destroy }

   onResolve(javdbSlug, resolution, extra, idx, afterSuccess?)
   onUnlink(javdbSlug, idx)
   onReload()  — invoked by the near-miss-resolved window event so the
                 owning draft.js can re-fetch /api/drafts/:id.
   onAddManual(resolution, extra) — add a manual cast slot (empty-state)
   onRemove(javdbSlug)            — remove a cast slot by javdbSlug
   onClearAll()                   — remove all cast slots
   ───────────────────────────────────────────────────────────────────── */

import { mount as mountNearMissModal } from '../../near-miss-modal.js';
import { openAliasCaptureModal }       from '../../alias-capture-modal.js';

const POLL_MAX = 30;

// ── Module-level singletons (legacy parity) ────────────────────────────
// Single window listener for near-miss-resolved. Legacy uses the same
// register-once pattern (never removed); we mirror it. The latest mount
// wins via the activeReloader pointer.
let _activeReloader = null;
let _nmListenerRegistered = false;
function ensureNearMissListener() {
  if (_nmListenerRegistered) return;
  _nmListenerRegistered = true;
  window.addEventListener('near-miss-resolved', () => {
    if (typeof _activeReloader === 'function') _activeReloader();
  });
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function hasJpChar(s) {
  return s && /[ぁ-ゖァ-ヺ一-鿿]/.test(s);
}

/**
 * Splits LLM-returned romaji into {first, last} parts.
 * Mirrors ActressFuzzyMatcher.splitRomaji exactly.
 */
function splitRomaji(s) {
  if (!s || !s.trim()) return { first: null, last: null };
  const tokens = s.trim().split(/\s+/);
  if (tokens.length === 1) return { first: null, last: tokens[0] };
  if (tokens.length === 2) return { first: tokens[0], last: tokens[1] };
  return { first: tokens[0], last: tokens.slice(1).join(' ') };
}

function resolutionLabel(res) {
  if (!res || res === 'unresolved') return 'unresolved';
  if (res === 'pick')       return 'linked';
  if (res === 'create_new') return 'create new';
  if (res === 'skip')       return 'skip';
  if (res.startsWith?.('sentinel:')) return 'sentinel';
  return res;
}

function resolutionCls(res) {
  if (!res || res === 'unresolved') return 'un-cast-res-unresolved';
  if (res === 'pick')       return 'un-cast-res-pick';
  if (res === 'create_new') return 'un-cast-res-create';
  if (res === 'skip')       return 'un-cast-res-skip';
  if (res.startsWith?.('sentinel:')) return 'un-cast-res-sentinel';
  return 'un-cast-res-unresolved';
}

function resolvedSummary(slot, sentinelsCache) {
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
    const cache = sentinelsCache || [];
    const found = cache.find(s => String(s.id) === sentinelId);
    return found ? `Placeholder: ${found.canonicalName}` : 'Placeholder';
  }
  return res || '';
}

async function fetchSentinels(state) {
  if (state.sentinelsCache) return state.sentinelsCache;
  try {
    const res = await fetch('/api/actresses?sentinel=true&limit=20');
    if (!res.ok) { state.sentinelsCache = []; return []; }
    const data = await res.json();
    const arr = Array.isArray(data) ? data : (data.items || []);
    let cache = arr.filter(a => a.isSentinel || a.is_sentinel);
    if (!cache.length) {
      const r2 = await fetch('/api/unsorted/actresses/search?q=Various&limit=5');
      const hits = await r2.json();
      cache = hits.filter(h => h.isSentinel || h.is_sentinel);
    }
    state.sentinelsCache = cache;
    return cache;
  } catch (err) {
    console.warn('[cast-pane] fetchSentinels failed', err);
    return [];
  }
}

/**
 * Alias-capture check: after a successful 'pick', see whether the
 * linked actress's canonical/aliases cover both the kanji stage name
 * and the romaji typed at click-time. If not, open the modal.
 */
async function checkAndOpenAliasModal(actressId, stageName, capturedFirst, capturedLast) {
  if (!actressId) return;
  try {
    const res = await fetch(`/api/actresses/${actressId}`);
    if (!res.ok) return;
    const data = await res.json();
    const canonicalName = data.canonicalName || '';
    const aliasNames = (data.aliases || [])
      .map(a => (typeof a === 'string' ? a : a.name)).filter(Boolean);

    const kanjiText  = (stageName || '').trim();
    const romajiText = capturedFirst
      ? `${capturedFirst} ${capturedLast}`.trim()
      : (capturedLast || '').trim();

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
    console.warn('[cast-pane] checkAndOpenAliasModal failed', err);
  }
}

/**
 * @param {HTMLElement} containerEl
 * @param {object}      state
 * @param {object}      callbacks
 * @param {Function}    callbacks.onResolve   — (javdbSlug, resolution, extra, idx, afterSuccess?) → void
 * @param {Function}    callbacks.onUnlink    — (javdbSlug, idx) → void
 * @param {Function}    callbacks.onReload    — () → void  (near-miss-resolved → reload draft)
 * @param {Function}    [callbacks.onAddManual] — (resolution, extra) → void  (empty-state add)
 * @param {Function}    [callbacks.onRemove]    — (javdbSlug) → void  (per-slot remove)
 * @param {Function}    [callbacks.onClearAll]  — () → void  (clear all cast)
 * @returns {{ renderCast:Function, destroy:Function }}
 */
export function mountCastPane(containerEl, state, {
  onResolve, onUnlink, onReload,
  onAddManual, onRemove, onClearAll,
}) {

  // Make our reloader the active one for the singleton listener.
  ensureNearMissListener();
  _activeReloader = onReload || null;

  // One document-level click handler dismisses any open suggest box.
  function onDocClick(e) {
    containerEl.querySelectorAll('.un-cast-picker-suggest').forEach(box => {
      const wrap = box.closest('.un-cast-picker-search');
      if (!wrap || !wrap.contains(e.target)) box.style.display = 'none';
    });
  }
  document.addEventListener('click', onDocClick);

  // ── Polling helpers ─────────────────────────────────────────────────
  function stopAllPolling() {
    for (const id of state.pollTimers.values()) clearTimeout(id);
    state.pollTimers.clear();
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
  function removeBadge(headerEl) {
    headerEl.querySelector('.sn-translating-badge')?.remove();
  }
  function removeCue(headerEl, slug) {
    headerEl.querySelector(`.sn-autofill-cue[data-slug="${slug}"]`)?.remove();
  }

  /**
   * Shared DOM helper: populates the create-new Last/First inputs in pickerEl,
   * opens the create-new form, and appends the autofill cue to headerEl.
   * Called both from applyAutoFill (poll path) and renderCast (prefilled path).
   * @param {HTMLElement} headerEl
   * @param {HTMLElement} pickerEl
   * @param {object}      slot
   * @param {string|null} first
   * @param {string|null} last
   */
  function _fillCreateNew(headerEl, pickerEl, slot, first, last) {
    if (!pickerEl) return;
    const lastInput  = pickerEl.querySelector('.un-cast-picker-name-input[data-name-field="last"]');
    const firstInput = pickerEl.querySelector('.un-cast-picker-name-input[data-name-field="first"]');
    if (!lastInput && !firstInput) return;
    if ((lastInput && lastInput.value) || (firstInput && firstInput.value)) return;

    state.suppressInput.add(slot.javdbSlug);
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
      state.suppressInput.delete(slot.javdbSlug);
    }

    const createForm = pickerEl.querySelector('.un-cast-picker-create-form');
    if (createForm) createForm.style.display = 'flex';

    if (!headerEl.querySelector(`.sn-autofill-cue[data-slug="${slot.javdbSlug}"]`)) {
      const cue = document.createElement('span');
      cue.className = 'sn-autofill-cue';
      cue.dataset.slug = slot.javdbSlug;
      cue.textContent = 'filled by translation — accept or edit';
      headerEl.appendChild(cue);
    }
  }

  function applyAutoFill(headerEl, pickerEl, slot, romaji) {
    const { first, last } = splitRomaji(romaji);
    _fillCreateNew(headerEl, pickerEl, slot, first, last);
  }

  function startPollForSlot(slot, headerEl, pickerEl) {
    const kanji = slot.stageName;
    let count = 0;

    function doPoll() {
      // If the cast-pane has been replaced/destroyed, our timers map is empty.
      if (!state.draft || state.currentId == null) return;
      count++;
      fetch(`/api/translation/stage-name-status?kanji=${encodeURIComponent(kanji)}`)
        .then(r => r.json())
        .then(data => {
          if (!state.draft || state.currentId == null) return;
          if (data.status === 'ready') {
            removeBadge(headerEl);
            state.pollTimers.delete(slot.javdbSlug);
            if (!state.dirtySlots.has(slot.javdbSlug)) {
              applyAutoFill(headerEl, pickerEl, slot, data.romaji);
            }
          } else if (data.status === 'queued') {
            ensureBadge(headerEl, slot.javdbSlug);
            if (count < POLL_MAX) {
              const ms = typeof window.__phase6dPollMs === 'number' ? window.__phase6dPollMs : 5000;
              const tid = setTimeout(doPoll, ms);
              state.pollTimers.set(slot.javdbSlug, tid);
            } else {
              removeBadge(headerEl);
              state.pollTimers.delete(slot.javdbSlug);
            }
          } else {
            // missing
            removeBadge(headerEl);
            state.pollTimers.delete(slot.javdbSlug);
          }
        })
        .catch(() => {
          state.pollTimers.delete(slot.javdbSlug);
        });
    }
    doPoll();
  }

  // ── Render ──────────────────────────────────────────────────────────
  function renderCast() {
    // Always tear down previous-render polling before rebuilding the DOM.
    stopAllPolling();

    const cast = state.draft?.cast || [];
    if (cast.length === 0) {
      // Empty-state: render Add-cast block with search, create-new, and
      // placeholder sentinel buttons. Warm the sentinel cache if needed.
      containerEl.innerHTML = '';
      const addBlock = _buildAddCastBlock((resolution, extra) => {
        onAddManual?.(resolution, extra);
      });
      containerEl.appendChild(addBlock);
      return;
    }

    // Warm sentinel cache if any slot uses a sentinel resolution, so that
    // resolvedSummary() can show the canonical name synchronously after re-render.
    const hasSentinelSlot = cast.some(s => s.resolution?.startsWith?.('sentinel:'));
    if (hasSentinelSlot && !state.sentinelsCache) {
      fetchSentinels(state).then(() => {
        // Re-render once cache is populated so resolved summaries show names.
        renderCast();
      });
    }

    containerEl.innerHTML = '';

    // ── Clear-all button (non-empty list) ──────────────────────────────
    const clearAllRow = document.createElement('div');
    clearAllRow.className = 'un-cast-clear-all-row';
    const clearAllBtn = document.createElement('button');
    clearAllBtn.type = 'button';
    clearAllBtn.className = 'btn btn-secondary btn-sm';
    clearAllBtn.textContent = 'Clear all cast';
    clearAllBtn.addEventListener('click', () => onClearAll?.());
    clearAllRow.appendChild(clearAllBtn);
    containerEl.appendChild(clearAllRow);

    const listEl = document.createElement('ul');
    listEl.className = 'un-cast-list';
    listEl.id = 'un-cast-list';
    containerEl.appendChild(listEl);

    cast.forEach((slot, idx) => {
      const li = document.createElement('li');
      li.className = 'un-cast-slot';
      li.dataset.idx = String(idx);
      li.dataset.slug = slot.javdbSlug || '';
      li.appendChild(_buildSlotContent(slot, idx));
      listEl.appendChild(li);

      // Start polling if Japanese stage name and no english parts yet.
      const needsPoll = !slot.englishFirstName && !slot.englishLastName
                        && slot.stageName && hasJpChar(slot.stageName);
      const isUnresolved = !slot.resolution || slot.resolution === 'unresolved';
      if (needsPoll) {
        const headerEl = li.querySelector('.un-cast-slot-header');
        const pickerEl = li.querySelector('.un-cast-picker');
        if (headerEl) startPollForSlot(slot, headerEl, pickerEl);
      } else if (isUnresolved && slot.englishLastName != null
                 && !state.dirtySlots.has(slot.javdbSlug)) {
        // Romaji already pre-filled by backend — surface it immediately using the
        // same DOM path as the poll's applyAutoFill, without splitRomaji (names
        // are already split into first/last by the server).
        const headerEl = li.querySelector('.un-cast-slot-header');
        const pickerEl = li.querySelector('.un-cast-picker');
        if (headerEl) {
          _fillCreateNew(headerEl, pickerEl, slot,
                         slot.englishFirstName, slot.englishLastName);
        }
      }
    });
  }

  function _buildSlotContent(slot, idx) {
    const frag = document.createDocumentFragment();

    // ── Header ────────────────────────────────────────────────────
    const header = document.createElement('div');
    header.className = 'un-cast-slot-header';

    const stage = document.createElement('span');
    stage.className = 'un-cast-slot-stage';
    stage.textContent = slot.stageName || '(no stage name)';
    header.appendChild(stage);

    if (slot.javdbSlug) {
      const slug = document.createElement('span');
      slug.className = 'un-cast-slot-slug';
      slug.textContent = slot.javdbSlug;
      header.appendChild(slug);
    }

    const resBadge = document.createElement('span');
    resBadge.className = 'badge un-cast-res ' + resolutionCls(slot.resolution);
    resBadge.textContent = resolutionLabel(slot.resolution);
    header.appendChild(resBadge);

    // Near-Miss "?" badge — only on truly-unresolved slots with a stage name.
    const isUnresolved = !slot.resolution || slot.resolution === 'unresolved';
    const isFilled = slot.linkToExistingId != null
                  || slot.linkToDraftSlug  != null
                  || slot.englishLastName  != null;
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

    let unlinkBtn = null;
    if (!isUnresolved) {
      // ── Resolved body ──────────────────────────────────────────
      if (slot.resolution === 'pick' && (slot.linkedActressName || slot.linkedActressAvatarUrl)) {
        const linked = document.createElement('div');
        linked.className = 'un-cast-linked';
        if (slot.linkedActressAvatarUrl) {
          const img = document.createElement('img');
          img.className = 'un-cast-linked-avatar';
          img.src = slot.linkedActressAvatarUrl;
          img.alt = slot.linkedActressName || '';
          linked.appendChild(img);
        }
        if (slot.linkedActressName) {
          const name = document.createElement('span');
          name.className = 'un-cast-linked-name';
          name.textContent = slot.linkedActressName;
          linked.appendChild(name);
        }
        frag.appendChild(linked);
      } else {
        const summary = document.createElement('div');
        summary.className = 'un-cast-slot-summary';
        summary.textContent = resolvedSummary(slot, state.sentinelsCache);
        frag.appendChild(summary);
      }

      unlinkBtn = document.createElement('button');
      unlinkBtn.type = 'button';
      unlinkBtn.className = 'btn btn-secondary btn-sm';
      unlinkBtn.textContent = 'Unlink and pick different';
      unlinkBtn.addEventListener('click', () => onUnlink?.(slot.javdbSlug, idx));

    } else {
      // ── Unresolved: real picker ────────────────────────────────
      frag.appendChild(_buildPicker(slot, idx, header));
    }

    // ── Slot actions: Unlink (resolved) + Remove, side by side ──
    const actions = document.createElement('div');
    actions.className = 'un-cast-slot-actions';
    if (unlinkBtn) actions.appendChild(unlinkBtn);
    const removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'btn btn-secondary btn-sm un-cast-remove-btn';
    removeBtn.textContent = 'Remove';
    removeBtn.addEventListener('click', () => onRemove?.(slot.javdbSlug));
    actions.appendChild(removeBtn);
    frag.appendChild(actions);

    return frag;
  }

  /**
   * Build the search-existing sub-component.
   * submit(resolution, extra, afterSuccess?) — called when the user picks a hit.
   * If slot/headerEl/pickerContainerRef are provided, alias-capture is enabled;
   * otherwise (empty-state) they should be null/undefined.
   *
   * @param {Function} submit
   * @param {object|null}   slot             — current draft cast slot (or null for empty-state)
   * @param {HTMLElement|null} headerEl      — slot header for alias modal (or null)
   * @param {object}    pickerContainerRef   — { el: HTMLElement } reference to picker container
   *                                           (needed to read captured name inputs pre-render)
   * @returns {HTMLElement} searchWrap
   */
  function _buildSearchExisting(submit, slot, headerEl, pickerContainerRef) {
    const searchWrap = document.createElement('div');
    searchWrap.className = 'un-cast-picker-search';

    const searchInput = document.createElement('input');
    searchInput.type = 'text';
    searchInput.className = 'un-cast-picker-input';
    searchInput.placeholder = 'Search existing actress…';
    searchInput.autocomplete = 'off';

    const suggestBox = document.createElement('div');
    suggestBox.className = 'un-cast-picker-suggest';
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
          const res  = await fetch(`/api/unsorted/actresses/search?q=${encodeURIComponent(q)}&limit=8`);
          if (seq !== searchSeq) return;
          const hits = await res.json();
          suggestBox.innerHTML = '';
          hits.forEach(h => {
            const item = document.createElement('div');
            item.className = 'un-cast-picker-suggest-item';
            const stageBit = h.stageName ? ` (${h.stageName})` : '';
            item.textContent = (h.canonicalName || '') + stageBit;
            item.addEventListener('click', () => {
              suggestBox.style.display = 'none';
              searchInput.value = '';
              if (slot && pickerContainerRef?.el) {
                // Capture name inputs before re-render destroys them (per-slot only).
                const lastEl  = pickerContainerRef.el.querySelector('.un-cast-picker-name-input[data-name-field="last"]');
                const firstEl = pickerContainerRef.el.querySelector('.un-cast-picker-name-input[data-name-field="first"]');
                const capturedLast  = lastEl  ? lastEl.value.trim()  : '';
                const capturedFirst = firstEl ? firstEl.value.trim() : '';
                submit('pick', { linkToExistingId: h.id }, () => {
                  checkAndOpenAliasModal(h.id, slot.stageName, capturedFirst, capturedLast);
                });
              } else {
                submit('pick', { linkToExistingId: h.id });
              }
            });
            suggestBox.appendChild(item);
          });
          suggestBox.style.display = hits.length ? 'block' : 'none';
        } catch (err) {
          console.error('[cast-pane] picker search failed', err);
        }
      }, 180);
    });

    searchWrap.appendChild(searchInput);
    searchWrap.appendChild(suggestBox);
    return searchWrap;
  }

  /**
   * Build the create-new sub-component (toggle button + inline form).
   * submit(resolution, extra) — called when the user submits the form.
   * If slot/headerEl are provided, dirty-tracking + autofill cue are enabled.
   *
   * @param {Function}      submit
   * @param {object|null}   slot
   * @param {HTMLElement|null} headerEl
   * @param {object}        pickerContainerRef — { el: HTMLElement } set once the container is live
   * @returns {{ createBtn: HTMLElement, createForm: HTMLElement }}
   */
  function _buildCreateNew(submit, slot, headerEl, pickerContainerRef) {
    const createBtn = document.createElement('button');
    createBtn.type = 'button';
    createBtn.className = 'btn btn-secondary btn-sm';
    createBtn.textContent = 'Create new…';

    const createForm = document.createElement('div');
    createForm.className = 'un-cast-picker-create-form';
    createForm.style.display = 'none';

    const lastRow = document.createElement('div');
    lastRow.className = 'un-cast-picker-create-row';
    const lastLabel = document.createElement('label'); lastLabel.textContent = 'Last';
    const lastInput = document.createElement('input');
    lastInput.type = 'text';
    lastInput.className = 'un-cast-picker-name-input';
    lastInput.dataset.nameField = 'last';
    lastInput.placeholder = 'Last name (required)';
    lastRow.appendChild(lastLabel); lastRow.appendChild(lastInput);

    const firstRow = document.createElement('div');
    firstRow.className = 'un-cast-picker-create-row';
    const firstLabel = document.createElement('label'); firstLabel.textContent = 'First';
    const firstInput = document.createElement('input');
    firstInput.type = 'text';
    firstInput.className = 'un-cast-picker-name-input';
    firstInput.dataset.nameField = 'first';
    firstInput.placeholder = 'First name (optional)';
    firstRow.appendChild(firstLabel); firstRow.appendChild(firstInput);

    // Per-slot dirty-tracking (no-op when slot is null / empty-state).
    if (slot) {
      function markDirtyAndRemoveCue() {
        if (state.suppressInput.has(slot.javdbSlug)) return;
        state.dirtySlots.add(slot.javdbSlug);
        if (headerEl) removeCue(headerEl, slot.javdbSlug);
      }
      lastInput.addEventListener('input', markDirtyAndRemoveCue);
      firstInput.addEventListener('input', markDirtyAndRemoveCue);
    }

    const submitRow = document.createElement('div');
    submitRow.className = 'un-cast-picker-create-row';
    const submitBtn = document.createElement('button');
    submitBtn.type = 'button';
    submitBtn.className = 'btn btn-primary btn-sm';
    submitBtn.textContent = 'Save';
    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'btn btn-secondary btn-sm';
    cancelBtn.textContent = 'Cancel';
    submitRow.appendChild(submitBtn); submitRow.appendChild(cancelBtn);

    createForm.appendChild(lastRow);
    createForm.appendChild(firstRow);
    createForm.appendChild(submitRow);

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
      submit('create_new', { englishLastName: lastName, englishFirstName: firstName || null });
    });

    return { createBtn, createForm };
  }

  /**
   * Build the Add-cast block shown when cast list is empty.
   * Contains: search-existing, create-new, and placeholder (sentinel) buttons.
   * @param {Function} submit — (resolution, extra) → void
   * @returns {HTMLElement}
   */
  function _buildAddCastBlock(submit) {
    const block = document.createElement('div');
    block.className = 'un-cast-add-block';

    const label = document.createElement('div');
    label.className = 'un-cast-add-label';
    label.textContent = 'Add cast';
    block.appendChild(label);

    const actionsRow = document.createElement('div');
    actionsRow.className = 'un-cast-picker-actions';

    // Ref used only if needed; empty-state has no slot container to reference.
    const pickerContainerRef = { el: block };

    const searchWrap = _buildSearchExisting(submit, null, null, null);
    actionsRow.appendChild(searchWrap);

    const { createBtn, createForm } = _buildCreateNew(submit, null, null, null);
    actionsRow.appendChild(createBtn);

    block.appendChild(actionsRow);
    block.appendChild(createForm);

    // ── Sentinel placeholder buttons ───────────────────────────────────
    const sentinelRow = document.createElement('div');
    sentinelRow.className = 'un-cast-sentinel-row';
    const sentinelLabel = document.createElement('span');
    sentinelLabel.className = 'un-cast-sentinel-label';
    sentinelLabel.textContent = 'Placeholders:';
    sentinelRow.appendChild(sentinelLabel);

    fetchSentinels(state).then(sentinels => {
      sentinels.forEach(s => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn btn-secondary btn-sm';
        btn.textContent = s.canonicalName;
        btn.addEventListener('click', () => submit('sentinel:' + s.id, {}));
        sentinelRow.appendChild(btn);
      });
    });

    block.appendChild(sentinelRow);
    return block;
  }

  function _buildPicker(slot, idx, headerEl) {
    const container = document.createElement('div');
    container.className = 'un-cast-picker';

    // Mutable ref so search builder can read name inputs from this container.
    const pickerContainerRef = { el: container };

    const actionsRow = document.createElement('div');
    actionsRow.className = 'un-cast-picker-actions';

    // Per-slot submit: routes through onResolve with slot context + alias-capture.
    const perSlotSubmit = (resolution, extra, afterSuccess) =>
      onResolve?.(slot.javdbSlug, resolution, extra, idx, afterSuccess);

    // ── Search existing ────────────────────────────────────────────────
    const searchWrap = _buildSearchExisting(perSlotSubmit, slot, headerEl, pickerContainerRef);
    actionsRow.appendChild(searchWrap);

    // ── Create-new toggle button + form ───────────────────────────────
    const { createBtn, createForm } = _buildCreateNew(perSlotSubmit, slot, headerEl, pickerContainerRef);
    actionsRow.appendChild(createBtn);

    const totalSlots = state.draft?.cast?.length || 0;

    // ── Skip (≥2 slots) ────────────────────────────────────────────────
    if (totalSlots >= 2) {
      const skipBtn = document.createElement('button');
      skipBtn.type = 'button';
      skipBtn.className = 'btn btn-secondary btn-sm';
      skipBtn.textContent = 'Skip';
      skipBtn.addEventListener('click', () =>
        onResolve?.(slot.javdbSlug, 'skip', {}, idx));
      actionsRow.appendChild(skipBtn);
    }

    // NOTE: Sentinel dropdown removed. Sentinels are only available via the
    // empty-state Add-cast block (mutual exclusivity rule).

    container.appendChild(actionsRow);
    container.appendChild(createForm);

    return container;
  }

  function destroy() {
    stopAllPolling();
    // Full-unmount semantics: clear dirty/suppress (mirrors legacy
    // unmountDraftView). Re-renders go through renderCast() which only
    // stops timers — dirty tracking persists across renders within a
    // single mount.
    state.dirtySlots.clear();
    state.suppressInput.clear();
    document.removeEventListener('click', onDocClick);
    if (_activeReloader === onReload) _activeReloader = null;
  }

  return { renderCast, destroy };
}
