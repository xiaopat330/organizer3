// Shared utilities used by 2+ subtabs of the JavDB Discovery tool.
// Sibling subtab modules import this; they never import each other.
//
// Diverges from spec/PROPOSAL_HOUSEKEEPING_2026_05.md §3 Phase 3 (which lists 6
// files). The pager + filter + cover-modal + peek-modal helpers are used by
// both titles.js and collections.js, so duplicating them would mean fixing
// bugs twice. See PR-C body for rationale.

import { esc } from '../utils.js';

export function formatRelative(isoStr) {
  if (!isoStr) return '—';
  try {
    const diff = Date.now() - new Date(isoStr).getTime();
    const days = Math.floor(diff / 86400000);
    if (days === 0) return 'Today';
    if (days === 1) return 'Yesterday';
    if (days < 30)  return `${days}d ago`;
    if (days < 365) return `${Math.floor(days / 30)}mo ago`;
    return `${Math.floor(days / 365)}y ago`;
  } catch { return isoStr; }
}

export function fmtDate(iso) {
  if (!iso) return '—';
  const [y, m, d] = iso.split('-');
  const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
  return `${months[+m - 1]} ${+d}, ${y}`;
}

export function parseCast(castJson) {
  if (!castJson) return [];
  try { return JSON.parse(castJson); } catch (_) { return []; }
}

export function showJdCoverModal(coverUrl, code) {
  document.querySelector('.jd-cover-overlay')?.remove();

  const overlay = document.createElement('div');
  overlay.className = 'jd-cover-overlay';

  const img = document.createElement('img');
  img.className = 'jd-cover-modal-img';
  img.src = coverUrl;
  img.alt = code;

  const label = document.createElement('div');
  label.className = 'jd-cover-modal-label';
  label.textContent = code;

  const box = document.createElement('div');
  box.className = 'jd-cover-modal-box';
  box.appendChild(img);
  box.appendChild(label);
  overlay.appendChild(box);
  document.body.appendChild(overlay);

  const ac = new AbortController();
  const close = () => { overlay.remove(); ac.abort(); };
  overlay.addEventListener('click', e => { if (e.target === overlay) close(); }, { signal: ac.signal });
  document.addEventListener('keydown', e => { if (e.key === 'Escape') close(); }, { signal: ac.signal });
}

// ── Title peek modal ───────────────────────────────────────────────────────

let _peekKeyHandlerInstalled = false;

function _peekKeydown(e) {
  if (e.key === 'Escape') closeTitlePeekModal();
}

export async function openTitlePeekModal(code) {
  closeTitlePeekModal();
  let t = null;
  try {
    const res = await fetch(`/api/titles/by-code/${encodeURIComponent(code)}`);
    if (res.ok) t = await res.json();
  } catch (_) { /* render with what we have */ }
  if (!t) t = { code };

  const backdrop = document.createElement('div');
  backdrop.className = 'jd-peek-backdrop';
  backdrop.id = 'jd-peek-backdrop';

  const cast = (t.actresses && t.actresses.length > 0)
    ? t.actresses
    : (t.actressName ? [{ id: t.actressId, name: t.actressName, tier: t.actressTier }] : []);
  const castHtml = cast.length === 0
    ? '<span style="color:#475569">—</span>'
    : cast.map(a => `<span class="jd-peek-cast-chip">${esc(a.name || '')}</span>`).join('');

  const labelText = [t.companyName, t.labelName].filter(Boolean).join(' · ');
  const dateLabel = t.releaseDate ? 'Released' : (t.addedDate ? 'Added' : null);
  const dateValue = t.releaseDate || t.addedDate;

  let gradeHtml = '';
  if (t.grade) {
    gradeHtml = `<span class="jd-peek-grade tier-${esc(String(t.grade))}">${esc(String(t.grade))}</span>`;
    if (t.ratingAvg != null && t.ratingCount != null) {
      gradeHtml += `<span class="jd-peek-rating">${t.ratingAvg.toFixed(2)} · ${t.ratingCount.toLocaleString()} votes</span>`;
    }
  }

  const tags = (t.tags || []).filter(Boolean);
  const tagsHtml = tags.length === 0
    ? '<span style="color:#475569">—</span>'
    : tags.map(tag => `<span class="jd-peek-tag">${esc(tag)}</span>`).join('');

  const nas = t.nasPaths || [];
  const nasHtml = nas.length === 0
    ? '<span style="color:#475569">—</span>'
    : nas.map(p => `<span class="jd-peek-nas">${esc(p)}</span>`).join('');

  const coverHtml = t.coverUrl
    ? `<div class="jd-peek-cover-wrap"><img class="jd-peek-cover" src="${esc(t.coverUrl)}" alt="${esc(t.code)}"></div>`
    : '';

  const titleJaHtml = t.titleOriginal ? `<div class="jd-peek-title-ja">${esc(t.titleOriginal)}</div>` : '';
  const titleEnHtml = t.titleEnglish  ? `<div class="jd-peek-title-en">${esc(t.titleEnglish)}</div>`  : '';

  backdrop.innerHTML = `
    <div class="jd-peek-modal" role="dialog" aria-label="Title preview" tabindex="-1">
      <button type="button" class="jd-peek-close" id="jd-peek-close" title="Close (Esc)">×</button>
      ${coverHtml}
      <div class="jd-peek-code">${esc(t.code)}</div>
      ${titleJaHtml}
      ${titleEnHtml}
      <div class="jd-peek-rows">
        <div class="jd-peek-row"><span class="jd-peek-row-label">Cast</span><span class="jd-peek-row-value">${castHtml}</span></div>
        ${labelText ? `<div class="jd-peek-row"><span class="jd-peek-row-label">Label</span><span class="jd-peek-row-value">${esc(labelText)}</span></div>` : ''}
        ${dateLabel ? `<div class="jd-peek-row"><span class="jd-peek-row-label">${dateLabel}</span><span class="jd-peek-row-value">${esc(fmtDate(dateValue))}</span></div>` : ''}
        ${gradeHtml ? `<div class="jd-peek-row"><span class="jd-peek-row-label">Grade</span><span class="jd-peek-row-value">${gradeHtml}</span></div>` : ''}
        <div class="jd-peek-row"><span class="jd-peek-row-label">Tags</span><span class="jd-peek-row-value">${tagsHtml}</span></div>
        <div class="jd-peek-row"><span class="jd-peek-row-label">Location</span><span class="jd-peek-row-value">${nasHtml}</span></div>
      </div>
    </div>
  `;
  document.body.appendChild(backdrop);

  backdrop.addEventListener('click', e => { if (e.target === backdrop) closeTitlePeekModal(); });
  document.getElementById('jd-peek-close').addEventListener('click', closeTitlePeekModal);
  if (!_peekKeyHandlerInstalled) {
    document.addEventListener('keydown', _peekKeydown);
    _peekKeyHandlerInstalled = true;
  }
}

export function closeTitlePeekModal() {
  const el = document.getElementById('jd-peek-backdrop');
  if (el) el.remove();
  if (_peekKeyHandlerInstalled) {
    document.removeEventListener('keydown', _peekKeydown);
    _peekKeyHandlerInstalled = false;
  }
}

// ── Filter input + autocomplete (Titles / Collections) ─────────────────────

const FILTER_DEBOUNCE_MS = 300;
const FILTER_AUTOCOMPLETE_DEBOUNCE_MS = 200;

/**
 * Wires a search input + clear button + autocomplete dropdown to a tab's state.
 * Mimics the library code-input on the Titles browse screen: prefix-only matches
 * (e.g. 'AB', 'ABP', 'ABP-') trigger an /api/labels/autocomplete fetch and open
 * a dropdown of suggested label codes. Once digits start to appear (e.g. 'ABP-001')
 * the dropdown closes — the user is past the label-prefix phase.
 */
export function attachFilterHandlers(input, clearBtn, dropEl, getState, onChange) {
  let autoTimer = null;
  let autoVisible = false;

  function closeAutocomplete() {
    autoVisible = false;
    if (autoTimer) { clearTimeout(autoTimer); autoTimer = null; }
    if (dropEl) { dropEl.innerHTML = ''; dropEl.classList.remove('open'); }
  }
  function openAutocomplete(items) {
    if (!dropEl || items.length === 0) { closeAutocomplete(); return; }
    autoVisible = true;
    dropEl.innerHTML = '';
    items.forEach((code, i) => {
      const el = document.createElement('div');
      el.className = 'jd-filter-autocomplete-item';
      el.textContent = code;
      el.dataset.idx = String(i);
      el.addEventListener('mousedown', e => {
        e.preventDefault();
        selectAutocompleteItem(code);
      });
      dropEl.appendChild(el);
    });
    dropEl.classList.add('open');
  }
  function moveAutocompleteSelection(dir) {
    if (!dropEl || !autoVisible) return;
    const items = dropEl.querySelectorAll('.jd-filter-autocomplete-item');
    if (items.length === 0) return;
    const cur = dropEl.querySelector('.jd-filter-autocomplete-item.focused');
    let idx = cur ? parseInt(cur.dataset.idx, 10) + dir : (dir > 0 ? 0 : items.length - 1);
    idx = Math.max(0, Math.min(items.length - 1, idx));
    items.forEach(el => el.classList.remove('focused'));
    items[idx]?.classList.add('focused');
  }
  function selectAutocompleteItem(code) {
    input.value = code;
    clearBtn.style.display = code.length > 0 ? '' : 'none';
    closeAutocomplete();
    const st = getState();
    if (st.filterDebounce) { clearTimeout(st.filterDebounce); st.filterDebounce = null; }
    st.filter = code;
    st.page = 0;
    st.selected.clear();
    onChange();
  }
  async function fetchAutocomplete(prefix) {
    if (!prefix || prefix.length < 1) { closeAutocomplete(); return; }
    try {
      const res = await fetch(`/api/labels/autocomplete?prefix=${encodeURIComponent(prefix)}`);
      if (!res.ok) return;
      const items = await res.json();
      if (document.activeElement !== input) return;
      openAutocomplete(items);
    } catch { /* ignore */ }
  }

  input.addEventListener('input', () => {
    const st = getState();
    const v = input.value;
    clearBtn.style.display = v.length > 0 ? '' : 'none';

    const upper = v.trim().toUpperCase().replace(/\s+/g, '');
    const isLabelPrefixOnly = upper.length > 0 && /^[A-Z][A-Z0-9]*-?$/.test(upper);
    if (isLabelPrefixOnly) {
      if (autoTimer) clearTimeout(autoTimer);
      autoTimer = setTimeout(() => {
        autoTimer = null;
        fetchAutocomplete(upper.replace(/-+$/, ''));
      }, FILTER_AUTOCOMPLETE_DEBOUNCE_MS);
    } else {
      closeAutocomplete();
    }

    if (st.filterDebounce) clearTimeout(st.filterDebounce);
    st.filterDebounce = setTimeout(() => {
      st.filterDebounce = null;
      st.filter = v;
      st.page = 0;
      st.selected.clear();
      onChange();
    }, FILTER_DEBOUNCE_MS);
  });

  input.addEventListener('keydown', e => {
    if (e.key === 'ArrowDown') { e.preventDefault(); moveAutocompleteSelection(1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); moveAutocompleteSelection(-1); }
    else if (e.key === 'Enter') {
      if (autoVisible) {
        const focused = dropEl.querySelector('.jd-filter-autocomplete-item.focused');
        if (focused) { e.preventDefault(); selectAutocompleteItem(focused.textContent); return; }
      }
      closeAutocomplete();
    }
    else if (e.key === 'Escape') {
      if (autoVisible) { closeAutocomplete(); return; }
      input.value = '';
      clearBtn.style.display = 'none';
      const st = getState();
      if (st.filterDebounce) { clearTimeout(st.filterDebounce); st.filterDebounce = null; }
      if (st.filter) {
        st.filter = '';
        st.page = 0;
        st.selected.clear();
        onChange();
      }
    }
  });

  input.addEventListener('blur', () => { setTimeout(closeAutocomplete, 150); });

  clearBtn.addEventListener('click', () => {
    input.value = '';
    clearBtn.style.display = 'none';
    closeAutocomplete();
    const st = getState();
    if (st.filterDebounce) { clearTimeout(st.filterDebounce); st.filterDebounce = null; }
    if (st.filter) {
      st.filter = '';
      st.page = 0;
      st.selected.clear();
      onChange();
    }
    input.focus();
  });
}

// ── Pager (Titles / Collections) ───────────────────────────────────────────

/**
 * Shared pager renderer used by both Titles and Collections tabs.
 * Layout: «  -10  ←  [page input] of N  →  +10  »
 */
export function renderPagerInto(el, st, kind) {
  const total = Math.max(st.totalPages || 0, 1);
  const cur = (st.page || 0) + 1;
  const atStart = cur <= 1;
  const atEnd   = cur >= total;
  const dis = (b) => b ? 'disabled' : '';
  el.innerHTML =
    `<button type="button" class="jd-titles-pager-btn" data-${kind}-pager="first" ${dis(atStart)} title="First page">«</button>` +
    `<button type="button" class="jd-titles-pager-btn" data-${kind}-pager="back10" ${dis(atStart)} title="Back 10 pages">−10</button>` +
    `<button type="button" class="jd-titles-pager-btn" data-${kind}-pager="prev" ${dis(atStart)}>←</button>` +
    `<span class="jd-pager-input-wrap">` +
      `<input type="text" class="jd-pager-input" data-${kind}-pager-input value="${cur}" ` +
        `inputmode="numeric" pattern="[0-9]*" maxlength="6" ` +
        `aria-label="Page number">` +
      `<span class="jd-pager-of">of ${total}</span>` +
    `</span>` +
    `<button type="button" class="jd-titles-pager-btn" data-${kind}-pager="next" ${dis(atEnd)}>→</button>` +
    `<button type="button" class="jd-titles-pager-btn" data-${kind}-pager="forward10" ${dis(atEnd)} title="Forward 10 pages">+10</button>` +
    `<button type="button" class="jd-titles-pager-btn" data-${kind}-pager="last" ${dis(atEnd)} title="Last page">»</button>`;
}

export function attachPagerHandlers(container, kind, getState, onJump) {
  container.addEventListener('click', e => {
    const btn = e.target.closest(`[data-${kind}-pager]`);
    if (!btn || btn.disabled) return;
    const action = btn.dataset[`${kind}Pager`];
    const st = getState();
    const total = Math.max(st.totalPages || 0, 1);
    let target = st.page;
    switch (action) {
      case 'first':     target = 0; break;
      case 'back10':    target = Math.max(0, st.page - 10); break;
      case 'prev':      target = Math.max(0, st.page - 1); break;
      case 'next':      target = Math.min(total - 1, st.page + 1); break;
      case 'forward10': target = Math.min(total - 1, st.page + 10); break;
      case 'last':      target = total - 1; break;
    }
    if (target !== st.page) onJump(target);
  });

  let debounce = null;
  container.addEventListener('input', e => {
    const input = e.target.closest(`[data-${kind}-pager-input]`);
    if (!input) return;
    const cleaned = input.value.replace(/[^0-9]/g, '');
    if (cleaned !== input.value) input.value = cleaned;
    if (debounce) clearTimeout(debounce);
    debounce = setTimeout(() => attemptInputJump(input, kind, getState, onJump), 500);
  });
  container.addEventListener('keydown', e => {
    const input = e.target.closest(`[data-${kind}-pager-input]`);
    if (!input) return;
    if (e.key === 'Enter') {
      if (debounce) { clearTimeout(debounce); debounce = null; }
      attemptInputJump(input, kind, getState, onJump);
      input.blur();
    }
  });
  container.addEventListener('focusout', e => {
    const input = e.target.closest(`[data-${kind}-pager-input]`);
    if (!input) return;
    const st = getState();
    const total = Math.max(st.totalPages || 0, 1);
    const v = parseInt(input.value, 10);
    if (!Number.isFinite(v) || v < 1 || v > total) {
      input.value = String(st.page + 1);
      input.classList.remove('jd-pager-input-invalid');
    }
  });
}

function attemptInputJump(input, kind, getState, onJump) {
  const st = getState();
  const total = Math.max(st.totalPages || 0, 1);
  const raw = input.value;
  const valid = /^[0-9]+$/.test(raw);
  const v = valid ? parseInt(raw, 10) : NaN;
  if (!valid || !Number.isFinite(v) || v < 1 || v > total) {
    input.classList.add('jd-pager-input-invalid');
    return;
  }
  input.classList.remove('jd-pager-input-invalid');
  const target = v - 1;
  if (target !== st.page) onJump(target);
}
