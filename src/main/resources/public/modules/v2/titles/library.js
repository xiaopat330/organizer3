/* ─────────────────────────────────────────────────────────────────────
   Titles v2 — Library mode filter panel
   Controls: code input (with label autocomplete), company select,
   sort select (addedDate / productCode / actressName), order toggle,
   tags toggle (curated groups + enrichment), active-chip bar,
   cols slider.
   ───────────────────────────────────────────────────────────────────── */

import { ageRangeHtml, wireAgeRange, AGE_MIN, AGE_MAX } from '../widgets/age-range.js';

const LIBRARY_DEBOUNCE_MS = 350;
const AUTOCOMPLETE_DEBOUNCE_MS = 200;

// ── Chip colour palette (hash-based, mirrors legacy library.js) ───────────
const TAG_CHIP_PALETTE = [
  { border: '#50c878', bg: '#081a10', text: '#70e898' },
  { border: '#e07050', bg: '#2a0e08', text: '#e89070' },
  { border: '#e0a030', bg: '#251800', text: '#e8c060' },
  { border: '#60c0e0', bg: '#081820', text: '#80d8f0' },
  { border: '#9060e0', bg: '#180a28', text: '#b080f0' },
  { border: '#e060a0', bg: '#280810', text: '#f080c0' },
  { border: '#60e0a0', bg: '#082018', text: '#80f0b8' },
  { border: '#e0d060', bg: '#201c00', text: '#f0e880' },
];

function tagChipStyle(tag) {
  let h = 5381;
  for (let i = 0; i < tag.length; i++) h = ((h << 5) + h) ^ tag.charCodeAt(i);
  const c = TAG_CHIP_PALETTE[Math.abs(h) % TAG_CHIP_PALETTE.length];
  return `background:${c.bg};border-color:${c.border};color:${c.text}`;
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

// ── Data fetching ─────────────────────────────────────────────────────────
async function ensureTagData(state) {
  const [catalog, countData, healthData] = await Promise.all([
    state.tagsCatalog      ? Promise.resolve(state.tagsCatalog)        : fetch('/api/tags').then(r => r.ok ? r.json() : []),
    state.tagCounts        ? Promise.resolve(state.tagCounts)          : fetch('/api/titles/tag-counts').then(r => r.ok ? r.json() : { totalTitles: 0, counts: {} }),
    state.enrichmentTagFilters ? Promise.resolve(state.enrichmentTagFilters) : fetch('/api/javdb/discovery/tag-health').then(r => r.ok ? r.json() : { definitions: [] }),
  ]);
  if (!state.tagsCatalog)          state.tagsCatalog          = catalog;
  if (!state.tagCounts)            state.tagCounts            = countData;
  if (!state.enrichmentTagFilters) state.enrichmentTagFilters = healthData;
  return { catalog, countData, healthData };
}

// ── Autocomplete ──────────────────────────────────────────────────────────
function closeAutocomplete(panelEl) {
  const drop = panelEl.querySelector('#tit-lib-code-dropdown');
  const wrap = panelEl.querySelector('#tit-lib-code-wrap');
  if (drop) drop.innerHTML = '';
  if (wrap) wrap.classList.remove('tit-lib-code-open');
}

function openAutocomplete(panelEl, items, inputEl, state) {
  const drop = panelEl.querySelector('#tit-lib-code-dropdown');
  const wrap = panelEl.querySelector('#tit-lib-code-wrap');
  if (!drop || items.length === 0) { closeAutocomplete(panelEl); return; }
  drop.innerHTML = '';
  items.forEach((code, i) => {
    const item = document.createElement('div');
    item.className = 'tit-lib-autocomplete-item';
    item.textContent = code;
    item.dataset.idx = i;
    item.addEventListener('mousedown', e => {
      e.preventDefault();
      state.libraryCode = code;
      if (inputEl) { inputEl.value = code; inputEl.focus(); }
      closeAutocomplete(panelEl);
      state._scheduleQuery();
    });
    drop.appendChild(item);
  });
  wrap?.classList.add('tit-lib-code-open');
}

async function fetchAutocomplete(panelEl, state) {
  const prefix = state.libraryCode.trim().toUpperCase().replace(/\s+/g, '').replace(/-+$/, '');
  if (!prefix || prefix.length < 1) { closeAutocomplete(panelEl); return; }
  try {
    const res = await fetch(`/api/labels/autocomplete?prefix=${encodeURIComponent(prefix)}`);
    if (!res.ok) return;
    const codes = await res.json();
    const inputEl = panelEl.querySelector('#tit-library-code');
    if (state.mode === 'library' && inputEl) openAutocomplete(panelEl, codes, inputEl, state);
  } catch (_) { /* ignore */ }
}

function moveAutocompleteSelection(panelEl, dir) {
  const drop = panelEl.querySelector('#tit-lib-code-dropdown');
  if (!drop) return;
  const items = drop.querySelectorAll('.tit-lib-autocomplete-item');
  if (items.length === 0) return;
  const current = drop.querySelector('.tit-lib-autocomplete-item.focused');
  let idx = current ? parseInt(current.dataset.idx) + dir : (dir > 0 ? 0 : items.length - 1);
  idx = Math.max(0, Math.min(items.length - 1, idx));
  items.forEach(el => el.classList.remove('focused'));
  items[idx]?.classList.add('focused');
}

// ── Tag chip bar ──────────────────────────────────────────────────────────
function renderTagChips(panelEl, state) {
  const container = panelEl.querySelector('#tit-lib-tag-chips');
  if (!container) return;

  const hasAny = state.activeTags.size > 0 || state.activeEnrichmentTagIds.size > 0;
  const bar    = panelEl.querySelector('#tit-lib-chips-bar');

  if (!hasAny) {
    container.innerHTML = '';
    if (bar) {
      if (state.chipsHideTimer) clearTimeout(state.chipsHideTimer);
      state.chipsHideTimer = setTimeout(() => {
        state.chipsHideTimer = null;
        if (bar) { bar.classList.add('tit-lib-chips-rollup'); setTimeout(() => { bar.style.display = 'none'; bar.classList.remove('tit-lib-chips-rollup'); }, 350); }
      }, 1800);
    }
    return;
  }

  if (bar) {
    if (state.chipsHideTimer) { clearTimeout(state.chipsHideTimer); state.chipsHideTimer = null; }
    bar.classList.remove('tit-lib-chips-rollup');
    bar.style.display = '';
  }

  const enrichDefs = state.enrichmentTagFilters?.definitions || [];
  const curatedChips = [...state.activeTags].map(tag =>
    `<span class="tit-lib-chip" data-tag="${esc(tag)}" style="${tagChipStyle(tag)}"><button type="button" class="tit-lib-chip-remove" data-tag="${esc(tag)}" title="Remove">&#x2296;</button>${esc(tag)}</span>`
  );
  const enrichChips = [...state.activeEnrichmentTagIds].map(id => {
    const def = enrichDefs.find(d => d.id === id);
    const label = def ? (def.curatedAlias || def.name) : String(id);
    return `<span class="tit-lib-chip tit-lib-chip--enrichment" data-enrichment-id="${id}" style="${tagChipStyle(label)}"><button type="button" class="tit-lib-chip-remove" data-enrichment-id="${id}" title="Remove">&#x2296;</button>${esc(label)}</span>`;
  });
  container.innerHTML = [...curatedChips, ...enrichChips].join('');

  container.querySelectorAll('.tit-lib-chip-remove').forEach(btn => {
    btn.addEventListener('click', e => {
      e.stopPropagation();
      if (btn.dataset.tag) {
        const tag = btn.dataset.tag;
        state.activeTags.delete(tag);
        const toggleEl = panelEl.querySelector(`.tit-tag-toggle[data-tag="${CSS.escape(tag)}"]`);
        if (toggleEl) toggleEl.classList.remove('on');
      } else if (btn.dataset.enrichmentId) {
        const id = parseInt(btn.dataset.enrichmentId, 10);
        state.activeEnrichmentTagIds.delete(id);
        const toggleEl = panelEl.querySelector(`.tit-tag-toggle[data-enrichment-id="${id}"]`);
        if (toggleEl) toggleEl.classList.remove('on');
      }
      renderTagChips(panelEl, state);
      state._scheduleQuery();
    });
  });
}

// ── Tags section ──────────────────────────────────────────────────────────
function toggleTagsSection(panelEl, state) {
  const section = panelEl.querySelector('#tit-lib-tags-section');
  const btn = panelEl.querySelector('#tit-lib-tags-btn');
  if (!section) return;
  if (state.tagsBarOpen) {
    state.tagsBarOpen = false;
    section.style.display = 'none';
    btn?.classList.remove('on');
    if (state.tagsPendingChanged) {
      state.tagsPendingChanged = false;
      state._scheduleQuery();
    }
  } else {
    state.tagsBarOpen = true;
    section.style.display = '';
    btn?.classList.add('on');
  }
}

// ── Cols slider HTML ──────────────────────────────────────────────────────
const COLS_DEFAULT = 5;
function colsSliderHtml(current) {
  return `<span class="tit-cols-ctrl">
    <span class="tit-cols-caption">Cols</span>
    <input type="range" class="tit-cols-slider" id="tit-cols-slider" min="2" max="10" step="1" value="${current}">
    <span class="tit-cols-label" id="tit-cols-label">${current}</span>
  </span>`;
}

// ── Notes chip HTML ───────────────────────────────────────────────────────
function notesChipHtmlLib(currentFilter) {
  const val   = currentFilter || '';
  const label = val === 'has_note' ? 'Notes: Has'
               : val === 'no_note'  ? 'Notes: None'
               :                      'Notes: Any';
  const activeClass = val ? ' tcv2-notes-chip--active' : '';
  return `<button type="button" class="tcv2-notes-chip${activeClass}" id="tcv2-notes-chip" data-notes-value="${val}" title="Filter by note">${label}</button>`;
}

// ── Main render ───────────────────────────────────────────────────────────
export async function renderLibraryPanel(panelEl, state, onColsChange) {
  let companies = [];
  try {
    if (!state.allCompanies) {
      const res = await fetch('/api/companies');
      state.allCompanies = res.ok ? await res.json() : [];
    }
    companies = state.allCompanies;
  } catch (_) { /* ignore */ }

  let groups = [];
  let tagCounts = {};
  let enrichmentDefs = [];
  try {
    const { catalog, countData, healthData } = await ensureTagData(state);
    groups = catalog || [];
    tagCounts = countData?.counts || {};
    enrichmentDefs = (healthData?.definitions || [])
      .filter(d => d.surface && !d.curatedAlias && d.libraryPct >= 0.01 && d.libraryPct <= 0.50)
      .sort((a, b) => a.name.localeCompare(b.name));
  } catch (_) { /* ignore */ }

  const sortOptions = [
    { value: 'addedDate',   label: 'Added Date' },
    { value: 'productCode', label: 'Product Code' },
    { value: 'actressName', label: 'Actress Name' },
  ];

  function curatedTagHtml(t) {
    const count = tagCounts[t.name] || 0;
    if (count === 0) return '';
    const on = state.activeTags.has(t.name) ? ' on' : '';
    return `<button type="button" class="chip tit-tag-toggle${on}" data-tag="${esc(t.name)}" title="${esc(t.description || '')}">${esc(t.name)}<span class="tit-tag-count">${count}</span></button>`;
  }

  function enrichTagHtml(d) {
    const on = state.activeEnrichmentTagIds.has(d.id) ? ' on' : '';
    const label = d.curatedAlias ? esc(d.curatedAlias) : esc(d.name);
    const title = d.curatedAlias ? `${d.name} → ${d.curatedAlias}` : d.name;
    return `<button type="button" class="chip tit-tag-toggle${on}" data-enrichment-id="${d.id}" title="${esc(title)}">${label}<span class="tit-tag-count">${d.titleCount}</span></button>`;
  }

  const curatedGroupsHtml = groups.map(g => {
    const tagsHtml = g.tags.map(curatedTagHtml).join('');
    if (!tagsHtml) return '';
    return `<div class="tit-tags-group">
      <div class="tit-tags-group-label">${esc(g.label)}</div>
      <div class="tit-tags-row">${tagsHtml}</div>
    </div>`;
  }).join('');

  const enrichGroupHtml = enrichmentDefs.length === 0 ? '' : `
    <div class="tit-tags-group">
      <div class="tit-tags-group-label">Enrichment <span class="tit-tags-group-sublabel">% of enriched titles</span></div>
      <div class="tit-tags-row">${enrichmentDefs.map(enrichTagHtml).join('')}</div>
    </div>`;

  const colsNow = state._cols || COLS_DEFAULT;

  panelEl.innerHTML = `
    <div class="tit-lib-controls">
      <div class="tit-lib-code-wrap" id="tit-lib-code-wrap">
        <input type="text" id="tit-library-code" class="tit-lib-code-input"
               placeholder="code (e.g. ONED, ONED-42)"
               value="${esc(state.libraryCode)}"
               autocomplete="off" spellcheck="false">
        <div class="tit-lib-autocomplete-dropdown" id="tit-lib-code-dropdown"></div>
      </div>
      <select id="tit-lib-company" class="tit-lib-select">
        <option value="">All Companies</option>
        ${companies.map(c => `<option value="${esc(c)}"${state.libraryCompany === c ? ' selected' : ''}>${esc(c)}</option>`).join('')}
      </select>
      ${ageRangeHtml(state.libraryAgeMin, state.libraryAgeMax, { idPrefix: 'tit-lib-age' })}
      <select id="tit-lib-sort" class="tit-lib-select">
        ${sortOptions.map(o => `<option value="${o.value}"${state.librarySort === o.value ? ' selected' : ''}>${o.label}</option>`).join('')}
      </select>
      <button type="button" id="tit-lib-order-btn" class="btn sm tit-lib-order-btn">${state.libraryOrder === 'asc' ? 'A–Z' : 'Z–A'}</button>
      <button type="button" id="tit-lib-tags-btn" class="btn sm${state.tagsBarOpen ? ' on' : ''}">Tags</button>
      ${notesChipHtmlLib(state.notesFilter)}
      ${colsSliderHtml(colsNow)}
    </div>
    <div class="tit-lib-chips-bar" id="tit-lib-chips-bar" style="display:none">
      <div class="tit-lib-chip-row" id="tit-lib-tag-chips"></div>
    </div>
    <div class="tit-lib-tags-section" id="tit-lib-tags-section" style="${state.tagsBarOpen ? '' : 'display:none'}">
      ${curatedGroupsHtml}
      ${enrichGroupHtml}
    </div>`;

  // ── Wire code input ──
  const codeInput = panelEl.querySelector('#tit-library-code');
  let autoTimer = null;
  if (codeInput) {
    codeInput.addEventListener('input', () => {
      state.libraryCode = codeInput.value;
      const upper = state.libraryCode.trim().toUpperCase().replace(/\s+/g, '');
      const isLabelPrefix = upper.length > 0 && /^[A-Z][A-Z0-9]*-?$/.test(upper);
      if (isLabelPrefix) {
        if (autoTimer) clearTimeout(autoTimer);
        autoTimer = setTimeout(() => { autoTimer = null; fetchAutocomplete(panelEl, state); }, AUTOCOMPLETE_DEBOUNCE_MS);
      } else {
        closeAutocomplete(panelEl);
      }
      state._scheduleQuery();
    });
    codeInput.addEventListener('keydown', e => {
      if (e.key === 'ArrowDown') { e.preventDefault(); moveAutocompleteSelection(panelEl, 1); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); moveAutocompleteSelection(panelEl, -1); }
      else if (e.key === 'Enter') {
        const focused = panelEl.querySelector('#tit-lib-code-dropdown .tit-lib-autocomplete-item.focused');
        if (focused) { e.preventDefault(); state.libraryCode = focused.textContent; codeInput.value = state.libraryCode; closeAutocomplete(panelEl); state._scheduleQuery(); return; }
        closeAutocomplete(panelEl);
      }
      else if (e.key === 'Escape') { closeAutocomplete(panelEl); }
    });
    codeInput.addEventListener('blur', () => setTimeout(() => closeAutocomplete(panelEl), 150));
  }

  // ── Wire company select ──
  panelEl.querySelector('#tit-lib-company')?.addEventListener('change', e => {
    state.libraryCompany = e.target.value || null;
    state._scheduleQuery();
  });

  // ── Wire age-range slider ──
  wireAgeRange(panelEl, {
    idPrefix: 'tit-lib-age',
    getLo: () => state.libraryAgeMin,
    getHi: () => state.libraryAgeMax,
    setLo: v => { state.libraryAgeMin = v; },
    setHi: v => { state.libraryAgeMax = v; },
    onChange: () => state._scheduleQuery(),
  });

  // ── Wire sort select ──
  panelEl.querySelector('#tit-lib-sort')?.addEventListener('change', e => {
    state.librarySort = e.target.value;
    state._scheduleQuery();
  });

  // ── Wire order button ──
  const orderBtn = panelEl.querySelector('#tit-lib-order-btn');
  if (orderBtn) {
    orderBtn.addEventListener('click', () => {
      state.libraryOrder = state.libraryOrder === 'desc' ? 'asc' : 'desc';
      orderBtn.textContent = state.libraryOrder === 'asc' ? 'A–Z' : 'Z–A';
      state._scheduleQuery();
    });
  }

  // ── Wire tags toggle ──
  panelEl.querySelector('#tit-lib-tags-btn')?.addEventListener('click', () => toggleTagsSection(panelEl, state));

  // ── Wire notes chip ──
  const notesChipEl = panelEl.querySelector('#tcv2-notes-chip');
  if (notesChipEl) {
    notesChipEl.addEventListener('click', () => {
      const cycle = [null, 'has_note', 'no_note'];
      const idx = cycle.indexOf(state.notesFilter);
      state.notesFilter = cycle[(idx + 1) % cycle.length];
      // Update chip appearance in place without full re-render
      const val = state.notesFilter || '';
      notesChipEl.dataset.notesValue = val;
      notesChipEl.textContent = val === 'has_note' ? 'Notes: Has'
                               : val === 'no_note'  ? 'Notes: None'
                               :                      'Notes: Any';
      notesChipEl.classList.toggle('tcv2-notes-chip--active', !!val);
      state._scheduleQuery();
    });
  }

  // ── Wire cols slider ──
  const slider = panelEl.querySelector('#tit-cols-slider');
  const colsLabel = panelEl.querySelector('#tit-cols-label');
  if (slider && colsLabel) {
    slider.addEventListener('input', () => {
      const cols = parseInt(slider.value, 10);
      colsLabel.textContent = String(cols);
      state._cols = cols;
      onColsChange(cols);
    });
  }

  // ── Wire curated tag toggles ──
  panelEl.querySelectorAll('.tit-tag-toggle[data-tag]').forEach(btn => {
    btn.addEventListener('click', () => {
      const tag = btn.dataset.tag;
      if (state.activeTags.has(tag)) { state.activeTags.delete(tag); btn.classList.remove('on'); }
      else                           { state.activeTags.add(tag);    btn.classList.add('on'); }
      state.tagsPendingChanged = true;
      renderTagChips(panelEl, state);
    });
  });

  // ── Wire enrichment tag toggles ──
  panelEl.querySelectorAll('.tit-tag-toggle[data-enrichment-id]').forEach(btn => {
    btn.addEventListener('click', () => {
      const id = parseInt(btn.dataset.enrichmentId, 10);
      if (state.activeEnrichmentTagIds.has(id)) { state.activeEnrichmentTagIds.delete(id); btn.classList.remove('on'); }
      else                                       { state.activeEnrichmentTagIds.add(id);    btn.classList.add('on'); }
      state.tagsPendingChanged = true;
      renderTagChips(panelEl, state);
    });
  });

  // ── Render existing chips ──
  renderTagChips(panelEl, state);
}

// ── Schedule debounced library query ──────────────────────────────────────
export function scheduleLibraryQuery(state) {
  if (state.tagsDebounceTimer) clearTimeout(state.tagsDebounceTimer);
  state.tagsDebounceTimer = setTimeout(() => {
    state.tagsDebounceTimer = null;
    state._updateBreadcrumb?.();
    state._runQuery();
  }, LIBRARY_DEBOUNCE_MS);
}

// ── Hide library panel ────────────────────────────────────────────────────
export function hideLibraryPanel(panelEl, state) {
  if (!panelEl) return;
  state.tagsBarOpen = false;
  state.tagsPendingChanged = false;
  if (state.chipsHideTimer) { clearTimeout(state.chipsHideTimer); state.chipsHideTimer = null; }
  panelEl.style.display = 'none';
}
