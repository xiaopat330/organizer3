// Library-mode filter panel: tag chips, autocomplete, company/sort/order controls.
// Exports: initLibrary(state, hooks)
// hooks.scheduleQuery()       — debounced re-run
// hooks.runQuery()            — immediate re-run
// hooks.updateBreadcrumb()    — after filter state changes
// hooks.titleTagsPanel        — DOM ref owned by index.js

import { esc } from '../utils.js';
import { effectiveCols, colsSliderHtml, wireColsSlider } from '../grid-cols.js';

// ── Constants ─────────────────────────────────────────────────────────────

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

const LIBRARY_DEBOUNCE_MS = 350;

// ── Helpers ───────────────────────────────────────────────────────────────

function tagChipStyle(tag) {
  let h = 5381;
  for (let i = 0; i < tag.length; i++) h = ((h << 5) + h) ^ tag.charCodeAt(i);
  const c = TAG_CHIP_PALETTE[Math.abs(h) % TAG_CHIP_PALETTE.length];
  return `background:${c.bg};border-color:${c.border};color:${c.text}`;
}

// ── Data fetching ─────────────────────────────────────────────────────────

async function ensureTagPanelData(state) {
  const [catalog, countData, healthData] = await Promise.all([
    state.tagsCatalog      ? Promise.resolve(state.tagsCatalog)        : fetch('/api/tags').then(r => r.ok ? r.json() : []),
    state.tagCounts        ? Promise.resolve(state.tagCounts)          : fetch('/api/titles/tag-counts').then(r => r.ok ? r.json() : { totalTitles: 0, counts: {} }),
    state.enrichmentTagFilters ? Promise.resolve(state.enrichmentTagFilters) : fetch('/api/javdb/discovery/tag-health').then(r => r.ok ? r.json() : { definitions: [] }),
  ]);
  if (!state.tagsCatalog)          state.tagsCatalog          = catalog;
  if (!state.tagCounts)            state.tagCounts            = countData;
  if (!state.enrichmentTagFilters) state.enrichmentTagFilters = healthData;
  return { catalog: state.tagsCatalog, countData: state.tagCounts, healthData: state.enrichmentTagFilters };
}

// ── Autocomplete ──────────────────────────────────────────────────────────

function closeLibraryAutocomplete(state) {
  state.libraryAutoVisible = false;
  if (state.libraryAutoTimer) { clearTimeout(state.libraryAutoTimer); state.libraryAutoTimer = null; }
  const drop = document.getElementById('library-code-dropdown');
  if (drop) drop.innerHTML = '';
  const wrap = document.getElementById('library-code-wrap');
  if (wrap) wrap.classList.remove('autocomplete-open');
}

function openLibraryAutocomplete(state, items, inputEl) {
  const drop = document.getElementById('library-code-dropdown');
  if (!drop || items.length === 0) { closeLibraryAutocomplete(state); return; }
  state.libraryAutoVisible = true;
  drop.innerHTML = '';
  items.forEach((code, i) => {
    const item = document.createElement('div');
    item.className = 'library-autocomplete-item';
    item.textContent = code;
    item.dataset.idx = i;
    item.addEventListener('mousedown', e => {
      e.preventDefault();
      selectAutocompleteItem(state, code, inputEl);
    });
    drop.appendChild(item);
  });
  document.getElementById('library-code-wrap')?.classList.add('autocomplete-open');
}

function selectAutocompleteItem(state, code, inputEl) {
  state.libraryCode = code;
  if (inputEl) { inputEl.value = code; inputEl.focus(); }
  closeLibraryAutocomplete(state);
  state._scheduleQuery();
}

function moveAutocompleteSelection(state, dir) {
  const drop = document.getElementById('library-code-dropdown');
  if (!drop || !state.libraryAutoVisible) return;
  const items = drop.querySelectorAll('.library-autocomplete-item');
  if (items.length === 0) return;
  const current = drop.querySelector('.library-autocomplete-item.focused');
  let idx = current ? parseInt(current.dataset.idx) + dir : (dir > 0 ? 0 : items.length - 1);
  idx = Math.max(0, Math.min(items.length - 1, idx));
  items.forEach(el => el.classList.remove('focused'));
  items[idx]?.classList.add('focused');
}

async function fetchAutocomplete(state) {
  const prefix = state.libraryCode.trim().toUpperCase().replace(/\s+/g, '').replace(/-+$/, '');
  if (!prefix || prefix.length < 1) { closeLibraryAutocomplete(state); return; }
  try {
    const res = await fetch(`/api/labels/autocomplete?prefix=${encodeURIComponent(prefix)}`);
    if (!res.ok) return;
    const codes = await res.json();
    const inputEl = document.getElementById('library-code-input');
    if (state.mode === 'library' && inputEl) openLibraryAutocomplete(state, codes, inputEl);
  } catch { /* ignore */ }
}

// ── Tag chip bar ──────────────────────────────────────────────────────────

function showChipsBar(state) {
  const bar = document.getElementById('library-tags-bar');
  if (!bar) return;
  if (state.chipsHideTimer) { clearTimeout(state.chipsHideTimer); state.chipsHideTimer = null; }
  bar.classList.remove('rolling-up');
  bar.style.display = '';
}

function scheduleChipsBarHide(state) {
  const bar = document.getElementById('library-tags-bar');
  if (!bar || bar.style.display === 'none') return;
  if (state.chipsHideTimer) clearTimeout(state.chipsHideTimer);
  state.chipsHideTimer = setTimeout(() => {
    state.chipsHideTimer = null;
    bar.classList.add('rolling-up');
    setTimeout(() => {
      bar.style.display = 'none';
      bar.classList.remove('rolling-up');
    }, 350);
  }, 1800);
}

function renderTagChips(state, titleTagsPanel) {
  const container = document.getElementById('library-tag-chips');
  if (!container) return;
  const hasAny = state.activeTags.size > 0 || state.activeEnrichmentTagIds.size > 0;
  if (!hasAny) {
    container.innerHTML = '';
    scheduleChipsBarHide(state);
    return;
  }
  showChipsBar(state);
  const curatedChips = [...state.activeTags].map(tag =>
    `<span class="library-tag-chip" data-tag="${esc(tag)}" style="${tagChipStyle(tag)}"><button type="button" class="library-tag-chip-remove" data-tag="${esc(tag)}" title="Remove tag">&#x2296;</button>${esc(tag)}</span>`
  );
  const enrichDefs = state.enrichmentTagFilters?.definitions || [];
  const enrichChips = [...state.activeEnrichmentTagIds].map(id => {
    const def = enrichDefs.find(d => d.id === id);
    const label = def ? (def.curatedAlias || def.name) : String(id);
    return `<span class="library-tag-chip library-tag-chip--enrichment" data-enrichment-id="${id}" style="${tagChipStyle(label)}"><button type="button" class="library-tag-chip-remove" data-enrichment-id="${id}" title="Remove tag">&#x2296;</button>${esc(label)}</span>`;
  });
  container.innerHTML = [...curatedChips, ...enrichChips].join('');
  container.querySelectorAll('.library-tag-chip-remove').forEach(btn => {
    btn.addEventListener('click', e => {
      e.stopPropagation();
      if (btn.dataset.tag) {
        const tag = btn.dataset.tag;
        state.activeTags.delete(tag);
        const toggleEl = titleTagsPanel.querySelector(`.tag-toggle[data-tag="${CSS.escape(tag)}"]`);
        if (toggleEl) toggleEl.classList.remove('active');
      } else if (btn.dataset.enrichmentId) {
        const id = parseInt(btn.dataset.enrichmentId, 10);
        state.activeEnrichmentTagIds.delete(id);
        const toggleEl = titleTagsPanel.querySelector(`.tag-toggle[data-enrichment-id="${id}"]`);
        if (toggleEl) toggleEl.classList.remove('active');
      }
      renderTagChips(state, titleTagsPanel);
      state._scheduleQuery();
    });
  });
}

function toggleTagsSection(state) {
  const section = document.getElementById('library-tags-section');
  const btn = document.getElementById('library-tags-toggle-btn');
  if (!section) return;
  if (state.tagsBarOpen) {
    state.tagsBarOpen = false;
    section.style.display = 'none';
    btn?.classList.remove('open');
    if (state.tagsPendingChanged) {
      state.tagsPendingChanged = false;
      state._scheduleQuery();
    }
  } else {
    state.tagsBarOpen = true;
    section.style.display = '';
    btn?.classList.add('open');
  }
}

// ── Hide the entire library panel ─────────────────────────────────────────

export function hideTagsPanel(state, titleTagsPanel) {
  state.tagsBarOpen = false;
  state.tagsPendingChanged = false;
  if (state.chipsHideTimer) { clearTimeout(state.chipsHideTimer); state.chipsHideTimer = null; }
  const section = document.getElementById('library-tags-section');
  if (section) section.style.display = 'none';
  const tagsToggleBtn = document.getElementById('library-tags-toggle-btn');
  if (tagsToggleBtn) tagsToggleBtn.classList.remove('open');
  const bar = document.getElementById('library-tags-bar');
  if (bar) { bar.classList.remove('rolling-up'); bar.style.display = 'none'; }
  titleTagsPanel.style.display = 'none';
  closeLibraryAutocomplete(state);
}

// ── Schedule debounced library query ──────────────────────────────────────

export function scheduleLibraryQuery(state) {
  if (state.tagsDebounceTimer) clearTimeout(state.tagsDebounceTimer);
  state.tagsDebounceTimer = setTimeout(() => {
    state.tagsDebounceTimer = null;
    state._updateBreadcrumb();
    state._runQuery();
  }, LIBRARY_DEBOUNCE_MS);
}

// ── Main panel render ─────────────────────────────────────────────────────

export async function renderLibraryFilterPanel(state, titleTagsPanel, applyTitleGridCols) {
  let companies = [];
  try {
    if (!state.allCompanies) {
      const res = await fetch('/api/companies');
      state.allCompanies = res.ok ? await res.json() : [];
    }
    companies = state.allCompanies;
  } catch { /* ignore */ }

  let groups = [];
  let tagCounts = {};
  let enrichmentDefs = [];
  try {
    const { catalog, countData, healthData } = await ensureTagPanelData(state);
    groups = catalog || [];
    tagCounts = countData?.counts || {};
    enrichmentDefs = (healthData?.definitions || [])
      .filter(d => d.surface && !d.curatedAlias && d.libraryPct >= 0.01 && d.libraryPct <= 0.50)
      .sort((a, b) => a.name.localeCompare(b.name));
  } catch { /* ignore */ }

  function curatedTagHtml(t) {
    const count = tagCounts[t.name] || 0;
    if (count === 0) return '';
    const active = state.activeTags.has(t.name) ? ' active' : '';
    const countBadge = `<span class="tag-toggle-count">${count}</span>`;
    return `<button type="button" class="tag-toggle${active}" data-tag="${esc(t.name)}" title="${esc(t.description || '')}">${esc(t.name)}${countBadge}</button>`;
  }

  function enrichmentTagHtml(d) {
    const active = state.activeEnrichmentTagIds.has(d.id) ? ' active' : '';
    const label = d.curatedAlias ? esc(d.curatedAlias) : esc(d.name);
    const title = d.curatedAlias ? `${d.name} → ${d.curatedAlias}` : d.name;
    const countBadge = `<span class="tag-toggle-count">${d.titleCount}</span>`;
    return `<button type="button" class="tag-toggle tag-toggle--enrichment${active}" data-enrichment-id="${d.id}" title="${esc(title)}">${label}${countBadge}</button>`;
  }

  const curatedGroupsHtml = groups.map(g => {
    const tagsHtml = g.tags.map(curatedTagHtml).join('');
    if (!tagsHtml) return '';
    return `<div class="tags-group">
      <div class="tags-group-label">${esc(g.label)}</div>
      <div class="tags-row">${tagsHtml}</div>
    </div>`;
  }).join('');

  const enrichmentGroupHtml = enrichmentDefs.length === 0 ? '' : `
    <div class="tags-group tags-group--enrichment">
      <div class="tags-group-label">Enrichment <span class="tags-group-sublabel">% of enriched titles</span></div>
      <div class="tags-row">${enrichmentDefs.map(enrichmentTagHtml).join('')}</div>
    </div>`;

  const sortOptions = [
    { value: 'addedDate',   label: 'Added Date' },
    { value: 'productCode', label: 'Product Code' },
    { value: 'actressName', label: 'Actress Name' },
  ];

  titleTagsPanel.innerHTML = `
    <div class="library-controls-row">
      <div class="library-code-wrap" id="library-code-wrap">
        <input type="text" id="library-code-input" class="library-code-input"
               placeholder="code (e.g. ONED, ONED-42)"
               value="${esc(state.libraryCode)}"
               autocomplete="off" spellcheck="false">
        <div class="library-autocomplete-dropdown" id="library-code-dropdown"></div>
      </div>
      <select id="library-company-select" class="library-company-select">
        <option value="">All Companies</option>
        ${companies.map(c => `<option value="${esc(c)}"${state.libraryCompany === c ? ' selected' : ''}>${esc(c)}</option>`).join('')}
      </select>
      <select id="library-sort-select" class="library-sort-select">
        ${sortOptions.map(o => `<option value="${o.value}"${state.librarySort === o.value ? ' selected' : ''}>${o.label}</option>`).join('')}
      </select>
      <button type="button" id="library-tags-toggle-btn" class="library-tags-toggle-btn">Tags</button>
      <button type="button" id="library-order-btn" class="library-order-btn">${state.libraryOrder === 'asc' ? 'A–Z' : 'Z–A'}</button>
      ${colsSliderHtml(effectiveCols(), 'title-cols-control', 'title-cols-slider', 'title-cols-label')}
    </div>
    <div class="library-tags-bar" id="library-tags-bar" style="display:none">
      <div class="library-tag-chips" id="library-tag-chips"></div>
    </div>
    <div class="library-tags-section" id="library-tags-section" style="display:none">
      ${curatedGroupsHtml}
      ${enrichmentGroupHtml}
    </div>
  `;

  const codeInput = document.getElementById('library-code-input');
  if (codeInput) {
    codeInput.addEventListener('input', () => {
      state.libraryCode = codeInput.value;
      const upper = state.libraryCode.trim().toUpperCase().replace(/\s+/g, '');
      const isLabelPrefixOnly = upper.length > 0 && /^[A-Z][A-Z0-9]*-?$/.test(upper);
      if (isLabelPrefixOnly) {
        if (state.libraryAutoTimer) clearTimeout(state.libraryAutoTimer);
        state.libraryAutoTimer = setTimeout(() => {
          state.libraryAutoTimer = null;
          fetchAutocomplete(state);
        }, 200);
      } else {
        closeLibraryAutocomplete(state);
      }
      state._scheduleQuery();
    });

    codeInput.addEventListener('keydown', e => {
      if (e.key === 'ArrowDown') { e.preventDefault(); moveAutocompleteSelection(state, 1); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); moveAutocompleteSelection(state, -1); }
      else if (e.key === 'Enter') {
        if (state.libraryAutoVisible) {
          const focused = document.querySelector('#library-code-dropdown .library-autocomplete-item.focused');
          if (focused) { e.preventDefault(); selectAutocompleteItem(state, focused.textContent, codeInput); return; }
        }
        closeLibraryAutocomplete(state);
      }
      else if (e.key === 'Escape') { closeLibraryAutocomplete(state); }
    });

    codeInput.addEventListener('blur', () => {
      setTimeout(() => closeLibraryAutocomplete(state), 150);
    });
  }

  const compSel = document.getElementById('library-company-select');
  if (compSel) {
    compSel.addEventListener('change', () => {
      state.libraryCompany = compSel.value || null;
      state._scheduleQuery();
    });
  }

  const sortSel = document.getElementById('library-sort-select');
  if (sortSel) {
    sortSel.addEventListener('change', () => {
      state.librarySort = sortSel.value;
      state._scheduleQuery();
    });
  }

  const orderBtn = document.getElementById('library-order-btn');
  if (orderBtn) {
    orderBtn.addEventListener('click', () => {
      state.libraryOrder = state.libraryOrder === 'desc' ? 'asc' : 'desc';
      orderBtn.textContent = state.libraryOrder === 'asc' ? 'A–Z' : 'Z–A';
      state._scheduleQuery();
    });
  }

  wireColsSlider('title-cols-slider', 'title-cols-label', applyTitleGridCols);

  document.getElementById('library-tags-toggle-btn')?.addEventListener('click', () => toggleTagsSection(state));

  renderTagChips(state, titleTagsPanel);

  titleTagsPanel.querySelectorAll('.tag-toggle[data-tag]').forEach(btn => {
    btn.addEventListener('click', () => {
      const tag = btn.dataset.tag;
      if (state.activeTags.has(tag)) { state.activeTags.delete(tag); btn.classList.remove('active'); }
      else                           { state.activeTags.add(tag);    btn.classList.add('active'); }
      state.tagsPendingChanged = true;
      renderTagChips(state, titleTagsPanel);
    });
  });

  titleTagsPanel.querySelectorAll('.tag-toggle[data-enrichment-id]').forEach(btn => {
    btn.addEventListener('click', () => {
      const id = parseInt(btn.dataset.enrichmentId, 10);
      if (state.activeEnrichmentTagIds.has(id)) { state.activeEnrichmentTagIds.delete(id); btn.classList.remove('active'); }
      else                                       { state.activeEnrichmentTagIds.add(id);    btn.classList.add('active'); }
      state.tagsPendingChanged = true;
      renderTagChips(state, titleTagsPanel);
    });
  });
}
