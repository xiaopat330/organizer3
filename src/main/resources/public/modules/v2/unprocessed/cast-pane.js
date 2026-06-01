/* ─────────────────────────────────────────────────────────────────────
   unprocessed/cast-pane.js — cast slot list with picker + translation
   polling (Wave 4).

   Responsibilities:
     - Render slots: stage name, javdb slug, resolution badge.
     - Resolved slot: linked-actress avatar / summary + Unlink button.
     - Unresolved slot: full picker — search typeahead, create-new
       inline form (last/first), Skip (≥2 slots), Sentinel dropdown
       (0 or ≥2 slots).
     - Stage-name translation polling loop (30 × 5s) for slots with
       Japanese stage names but no english_first/last yet.
     - Auto-fill dirty-slot guard; auto-fill cue.
     - Near-Miss "?" badge → mountNearMissModal.
     - Alias-capture check after pick → openAliasCaptureModal.

   Pure UI: PATCH semantics live in draft.js via callbacks
   (onResolve, onUnlink). State for timers/dirty/suppress/sentinels
   lives on shared `state` (per spec §4.2) — not module-local — so
   they survive re-renders and are torn down cleanly on destroy().

   Exported:
     mountCastPane(containerEl, state, { onResolve, onUnlink, onReload })
       → { renderCast, destroy }

   onResolve(javdbSlug, resolution, extra, idx, afterSuccess?)
   onUnlink(javdbSlug, idx)
   onReload()  — invoked by the near-miss-resolved window event so the
                 owning draft.js can re-fetch /api/drafts/:id.
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
  if (res && res.startsWith('sentinel:')) return 'Replaced with sentinel actress';
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
 * @param {Function}    callbacks.onResolve  — (javdbSlug, resolution, extra, idx, afterSuccess?) → void
 * @param {Function}    callbacks.onUnlink   — (javdbSlug, idx) → void
 * @param {Function}    callbacks.onReload   — () → void  (near-miss-resolved → reload draft)
 * @returns {{ renderCast:Function, destroy:Function }}
 */
export function mountCastPane(containerEl, state, { onResolve, onUnlink, onReload }) {

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
      containerEl.innerHTML = `
        <div class="un-cast-empty">No cast slots — javdb returned 0 stage names.</div>
      `;
      return;
    }

    containerEl.innerHTML = `<ul class="un-cast-list" id="un-cast-list"></ul>`;
    const listEl = containerEl.querySelector('#un-cast-list');

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
        summary.textContent = resolvedSummary(slot);
        frag.appendChild(summary);
      }

      const unlinkBtn = document.createElement('button');
      unlinkBtn.type = 'button';
      unlinkBtn.className = 'btn btn-secondary btn-sm';
      unlinkBtn.textContent = 'Unlink and pick different';
      unlinkBtn.addEventListener('click', () => onUnlink?.(slot.javdbSlug, idx));
      frag.appendChild(unlinkBtn);

    } else {
      // ── Unresolved: real picker ────────────────────────────────
      frag.appendChild(_buildPicker(slot, idx, header));
    }

    return frag;
  }

  function _buildPicker(slot, idx, headerEl) {
    const container = document.createElement('div');
    container.className = 'un-cast-picker';

    const actionsRow = document.createElement('div');
    actionsRow.className = 'un-cast-picker-actions';

    // ── Search existing ────────────────────────────────────────────────
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
              // Capture name inputs before re-render destroys them.
              const lastEl  = container.querySelector('.un-cast-picker-name-input[data-name-field="last"]');
              const firstEl = container.querySelector('.un-cast-picker-name-input[data-name-field="first"]');
              const capturedLast  = lastEl  ? lastEl.value.trim()  : '';
              const capturedFirst = firstEl ? firstEl.value.trim() : '';
              onResolve?.(slot.javdbSlug, 'pick', { linkToExistingId: h.id }, idx, () => {
                checkAndOpenAliasModal(h.id, slot.stageName, capturedFirst, capturedLast);
              });
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
    actionsRow.appendChild(searchWrap);

    // ── Create-new toggle button ───────────────────────────────────────
    const createBtn = document.createElement('button');
    createBtn.type = 'button';
    createBtn.className = 'btn btn-secondary btn-sm';
    createBtn.textContent = 'Create new…';
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

    // ── Sentinel dropdown (0 or ≥2 slots) ──────────────────────────────
    if (totalSlots === 0 || totalSlots >= 2) {
      const sel = document.createElement('select');
      sel.className = 'un-cast-picker-input un-cast-picker-sentinel';
      const def = document.createElement('option');
      def.value = '';
      def.textContent = 'Sentinel…';
      sel.appendChild(def);

      fetchSentinels(state).then(sentinels => {
        sentinels.forEach(s => {
          const opt = document.createElement('option');
          opt.value = 'sentinel:' + s.id;
          opt.textContent = s.canonicalName;
          sel.appendChild(opt);
        });
      });

      sel.addEventListener('change', () => {
        const val = sel.value;
        if (!val) return;
        onResolve?.(slot.javdbSlug, val, {}, idx);
        sel.value = '';
      });
      actionsRow.appendChild(sel);
    }

    container.appendChild(actionsRow);

    // ── Create-new inline form (hidden by default) ─────────────────────
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

    function markDirtyAndRemoveCue() {
      if (state.suppressInput.has(slot.javdbSlug)) return;
      state.dirtySlots.add(slot.javdbSlug);
      if (headerEl) removeCue(headerEl, slot.javdbSlug);
    }
    lastInput.addEventListener('input', markDirtyAndRemoveCue);
    firstInput.addEventListener('input', markDirtyAndRemoveCue);

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
    container.appendChild(createForm);

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
      onResolve?.(slot.javdbSlug, 'create_new',
        { englishLastName: lastName, englishFirstName: firstName || null }, idx);
    });

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
