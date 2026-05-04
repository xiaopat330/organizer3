// Pool-mode + browse-filter-bar helpers.
//
// Covers the modes that share browse-filter-bar UI: collections, unsorted, archive-pool.
//
// Exports:
//   ensureQueuesVolumes(state)
//   enterUnsortedMode(state)
//   enterArchiveMode(state)
//   showBrowseFilterBar(state, allTitlesGrid)
//   hideBrowseFilterBar()
//   resetBrowseFilters(state)
//   scheduleBrowseFilteredQuery(state, allTitlesGrid)

import { esc } from '../utils.js';
import { effectiveCols, colsSliderHtml, wireColsSlider } from '../grid-cols.js';
import { ensureSentinel } from '../grid.js';
import { updateCompanyMarquee } from '../studio-data.js';

const FILTERABLE_MODES = new Set(['collections', 'unsorted', 'archive-pool']);
const BROWSE_FILTER_DEBOUNCE_MS = 350;

// ── Queue/volume data ─────────────────────────────────────────────────────

export async function ensureQueuesVolumes(state) {
  if (state.queuesVolumeData) return state.queuesVolumeData;
  const res = await fetch('/api/queues/volumes');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  state.queuesVolumeData = await res.json();
  return state.queuesVolumeData;
}

// ── Pool entry ────────────────────────────────────────────────────────────

export async function enterUnsortedMode(state, selectTitleBrowseMode) {
  if (!state.poolVolumeId) {
    try {
      const data = await ensureQueuesVolumes(state);
      if (!data.sortPool) { console.warn('No sort pool available'); return; }
      state.poolVolumeId = data.sortPool.id;
      state.poolSmbPath  = data.sortPool.smbPath || null;
    } catch (err) { console.error('Failed to load pool info', err); return; }
  }
  selectTitleBrowseMode('unsorted');
}

export async function enterArchiveMode(state, selectTitleBrowseMode) {
  if (!state.archivePoolVolumeId) {
    try {
      const data = await ensureQueuesVolumes(state);
      if (!data.classicPool) { console.warn('No classic pool available'); return; }
      state.archivePoolVolumeId = data.classicPool.id;
      state.archivePoolSmbPath  = data.classicPool.smbPath || null;
    } catch (err) { console.error('Failed to load archive pool info', err); return; }
  }
  selectTitleBrowseMode('archive-pool');
}

// ── Browse filter bar ─────────────────────────────────────────────────────

export function resetBrowseFilters(state) {
  state.resetBrowse();
}

export function hideBrowseFilterBar() {
  const bar   = document.getElementById('title-browse-filter-bar');
  const panel = document.getElementById('title-browse-tags-panel');
  if (bar)   { bar.innerHTML = '';   bar.style.display   = 'none'; }
  if (panel) { panel.innerHTML = ''; panel.style.display = 'none'; }
}

function updateBrowseTagsBtn(state) {
  const countEl = document.getElementById('browse-tags-count');
  if (!countEl) return;
  if (state.browseActiveTags.size > 0) {
    countEl.textContent = state.browseActiveTags.size;
    countEl.style.display = '';
  } else {
    countEl.style.display = 'none';
  }
  const btn = document.getElementById('browse-tags-btn');
  if (btn) btn.classList.toggle('has-active', state.browseActiveTags.size > 0);
}

export function scheduleBrowseFilteredQuery(state, allTitlesGrid) {
  updateBrowseTagsBtn(state);
  if (state.browseFilterTimer) clearTimeout(state.browseFilterTimer);
  state.browseFilterTimer = setTimeout(() => {
    state.browseFilterTimer = null;
    document.getElementById('sentinel')?.remove();
    allTitlesGrid.reset();
    ensureSentinel();
    allTitlesGrid.loadMore();
  }, BROWSE_FILTER_DEBOUNCE_MS);
}

function renderBrowseTagsPanel(state, panel, allTitlesGrid) {
  const tags = state.browseCatalogTags || [];
  if (tags.length === 0) {
    panel.innerHTML = '<div class="detail-tags-loading">No tags available</div>';
    return;
  }
  panel.innerHTML = `
    <div class="detail-tags-inner">
      ${tags.map(t => `<button type="button" class="tag-toggle${state.browseActiveTags.has(t) ? ' active' : ''}" data-tag="${esc(t)}">${esc(t)}</button>`).join('')}
    </div>`;
  panel.querySelectorAll('.tag-toggle').forEach(btn => {
    btn.addEventListener('click', () => {
      const tag = btn.dataset.tag;
      if (state.browseActiveTags.has(tag)) { state.browseActiveTags.delete(tag); btn.classList.remove('active'); }
      else                           { state.browseActiveTags.add(tag);    btn.classList.add('active'); }
      scheduleBrowseFilteredQuery(state, allTitlesGrid);
    });
  });
}

async function toggleBrowseTagsPanel(state, allTitlesGrid) {
  const panel = document.getElementById('title-browse-tags-panel');
  if (!panel) return;

  if (panel.style.display !== 'none') {
    panel.style.display = 'none';
    return;
  }

  if (!state.browseCatalogTags || state.browseTagsForMode !== state.mode) {
    panel.innerHTML = '<div class="detail-tags-loading">Loading tags…</div>';
    panel.style.display = '';
    const tagsUrl = state.mode === 'collections'
      ? '/api/collections/tags'
      : `/api/pool/${encodeURIComponent(state.mode === 'unsorted' ? state.poolVolumeId : state.archivePoolVolumeId)}/tags`;
    try {
      const res = await fetch(tagsUrl);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      state.browseCatalogTags = await res.json();
      state.browseTagsForMode = state.mode;
    } catch (err) {
      panel.innerHTML = '<div class="detail-tags-loading">Could not load tags</div>';
      return;
    }
  }

  renderBrowseTagsPanel(state, panel, allTitlesGrid);
  panel.style.display = '';
}

export async function showBrowseFilterBar(state, allTitlesGrid, applyTitleGridCols) {
  if (!state.allCompanies) {
    try {
      const res = await fetch('/api/companies');
      state.allCompanies = res.ok ? await res.json() : [];
    } catch { state.allCompanies = []; }
  }

  if (!FILTERABLE_MODES.has(state.mode)) return;

  const bar = document.getElementById('title-browse-filter-bar');
  if (!bar) return;

  bar.innerHTML = `
    <select class="detail-company-select" id="browse-company-select">
      <option value="">All Companies</option>
      ${state.allCompanies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('')}
    </select>
    <div class="company-marquee company-marquee-browse" id="browse-company-marquee" style="display:none"><span class="company-marquee-inner"></span></div>
    <button type="button" class="detail-tags-btn" id="browse-tags-btn">
      Tags<span class="detail-tags-count" id="browse-tags-count" style="display:none"></span>
    </button>
    ${colsSliderHtml(effectiveCols(), 'title-cols-control', 'title-cols-slider', 'title-cols-label')}`;
  bar.style.display = '';

  const sel = document.getElementById('browse-company-select');
  if (sel && state.browseCompanyFilter) {
    sel.value = state.browseCompanyFilter;
    updateCompanyMarquee(document.getElementById('browse-company-marquee'), state.browseCompanyFilter);
  }
  updateBrowseTagsBtn(state);
  wireColsSlider('title-cols-slider', 'title-cols-label', applyTitleGridCols);

  sel.addEventListener('change', e => {
    state.browseCompanyFilter = e.target.value || null;
    updateCompanyMarquee(document.getElementById('browse-company-marquee'), state.browseCompanyFilter);
    scheduleBrowseFilteredQuery(state, allTitlesGrid);
  });
  document.getElementById('browse-tags-btn').addEventListener('click', () => toggleBrowseTagsPanel(state, allTitlesGrid));
}
