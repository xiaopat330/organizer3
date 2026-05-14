/* ─────────────────────────────────────────────────────────────────────
   Titles v2 — Orchestrator
   Modes: Dashboard · Favorites · Bookmarks · Studio · Collections ·
          Unsorted · Archives · Library (Tags)
   Entry: mountTitles(rootEl)
   ───────────────────────────────────────────────────────────────────── */

import { renderDashboard, spotlightRotator } from './dashboard.js';
import { renderTitleCard } from '../cards/title-card.js';
import { renderLibraryPanel, scheduleLibraryQuery, hideLibraryPanel } from './library.js';
import {
  enterUnsortedMode,
  enterArchiveMode,
  showBrowseFilterBar,
  hideBrowseFilterBar,
  resetBrowseFilters,
} from './pool.js';
import { mountStudio, unmountStudio } from './studio.js';

const PAGE_LIMIT = 36;
const COLS_DEFAULT = 5;
const FILTERABLE_MODES = new Set(['collections', 'unsorted', 'archive-pool']);

// ── Utils ─────────────────────────────────────────────────────────────────
async function fetchJson(url, fallback = null) {
  try {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) return fallback;
    return await r.json();
  } catch (e) {
    console.warn('[titles]', url, e);
    return fallback;
  }
}

// ── State factory ─────────────────────────────────────────────────────────
function createState() {
  return {
    mode: null,
    // Library filters
    activeTags: new Set(),
    activeEnrichmentTagIds: new Set(),
    tagsDebounceTimer: null,
    tagsCatalog: null,
    tagCounts: null,
    enrichmentTagFilters: null,
    tagsBarOpen: false,
    tagsPendingChanged: false,
    chipsHideTimer: null,
    libraryCode: '',
    libraryCompany: null,
    librarySort: 'addedDate',
    libraryOrder: 'desc',
    // Browse (pool) filters
    poolVolumeId: null,
    poolSmbPath: null,
    archivePoolVolumeId: null,
    archivePoolSmbPath: null,
    queuesVolumeData: null,
    browseCompanyFilter: null,
    browseActiveTags: new Set(),
    browseFilterTimer: null,
    browseCatalogTags: null,
    browseTagsForMode: null,
    allCompanies: null,
    // Studio
    selectedStudioSlug: null,
    // Grid columns
    _cols: COLS_DEFAULT,
    // Caches
    _labelCache: null,
  };
}

// ── URL builder ───────────────────────────────────────────────────────────
function buildUrl(state, offset, limit) {
  if (state.mode === 'favorites')
    return `/api/titles?favorites=true&offset=${offset}&limit=${limit}`;
  if (state.mode === 'bookmarks')
    return `/api/titles?bookmarks=true&offset=${offset}&limit=${limit}`;
  if (state.mode === 'collections') {
    let url = `/api/collections/titles?offset=${offset}&limit=${limit}`;
    if (state.browseCompanyFilter) url += `&company=${encodeURIComponent(state.browseCompanyFilter)}`;
    if (state.browseActiveTags.size > 0) url += `&tags=${encodeURIComponent([...state.browseActiveTags].join(','))}`;
    return url;
  }
  if (state.mode === 'unsorted') {
    let url = `/api/pool/${encodeURIComponent(state.poolVolumeId)}/titles?offset=${offset}&limit=${limit}`;
    if (state.browseCompanyFilter) url += `&company=${encodeURIComponent(state.browseCompanyFilter)}`;
    if (state.browseActiveTags.size > 0) url += `&tags=${encodeURIComponent([...state.browseActiveTags].join(','))}`;
    return url;
  }
  if (state.mode === 'archive-pool') {
    let url = `/api/pool/${encodeURIComponent(state.archivePoolVolumeId)}/titles?offset=${offset}&limit=${limit}`;
    if (state.browseCompanyFilter) url += `&company=${encodeURIComponent(state.browseCompanyFilter)}`;
    if (state.browseActiveTags.size > 0) url += `&tags=${encodeURIComponent([...state.browseActiveTags].join(','))}`;
    return url;
  }
  if (state.mode === 'library') {
    const params = new URLSearchParams({ offset, limit });
    if (state.libraryCode.trim())              params.set('code',             state.libraryCode.trim());
    if (state.libraryCompany)                  params.set('company',          state.libraryCompany);
    if (state.activeTags.size > 0)             params.set('tags',             [...state.activeTags].join(','));
    if (state.activeEnrichmentTagIds.size > 0) params.set('enrichmentTagIds', [...state.activeEnrichmentTagIds].join(','));
    if (state.librarySort !== 'addedDate')      params.set('sort',             state.librarySort);
    if (state.libraryOrder !== 'desc')          params.set('order',            state.libraryOrder);
    return `/api/titles?${params}`;
  }
  return `/api/titles?offset=${offset}&limit=${limit}`;
}

// ── Grid column setter ────────────────────────────────────────────────────
function applyGridCols(gridEl, cols) {
  if (gridEl) gridEl.style.gridTemplateColumns = `repeat(${cols}, 1fr)`;
}

// ── Mount ─────────────────────────────────────────────────────────────────
export function mountTitles(rootEl) {
  rootEl.innerHTML = `
    <div class="lib-page tit-page">
      <!-- Mode tab strip -->
      <div class="tit-mode-bar" id="tit-mode-bar">
        <button class="tit-mode-btn on" data-mode="dashboard">Dashboard</button>
        <button class="tit-mode-btn" data-mode="favorites">Favorites</button>
        <button class="tit-mode-btn" data-mode="bookmarks">Bookmarks</button>
        <button class="tit-mode-btn" data-mode="studio">Studio</button>
        <button class="tit-mode-btn" data-mode="collections">Collections</button>
        <button class="tit-mode-btn" data-mode="unsorted">Unsorted</button>
        <button class="tit-mode-btn" data-mode="archive-pool">Archives</button>
        <button class="tit-mode-btn" data-mode="library">Library</button>
      </div>

      <!-- Studio group strip (shown only in Studio mode) -->
      <div class="tit-studio-group-row" id="tit-studio-group-row" style="display:none"></div>

      <!-- Library filter panel (shown in Library mode) -->
      <div class="tit-lib-panel" id="tit-lib-panel" style="display:none"></div>

      <!-- Browse filter bar (shown in collections/unsorted/archive-pool) -->
      <div class="tit-browse-filter-bar" id="tit-browse-filter-bar" style="display:none"></div>
      <div class="tit-browse-tags-panel" id="tit-browse-tags-panel" style="display:none"></div>

      <!-- Simple cols-only filter bar (favorites/bookmarks) -->
      <div class="tit-simple-filter-bar" id="tit-simple-filter-bar" style="display:none"></div>

      <!-- Dashboard panel -->
      <div class="tit-dashboard" id="tit-dashboard" style="display:none"></div>

      <!-- Studio panel (two-column company view) -->
      <div class="tit-studio-panel" id="tit-studio-panel" style="display:none"></div>

      <!-- Grid + infinite scroll -->
      <div class="shelf-grid shelf-grid-titles tit-grid" id="tit-grid" style="display:none"></div>
      <div class="grid-status" id="tit-grid-status"></div>
      <div class="filter-meta" id="tit-grid-meta" style="margin-top:4px;padding-bottom:4px"></div>
      <div id="tit-sentinel" style="height:1px"></div>
    </div>`;

  const state = createState();

  // ── DOM refs ──
  const modeBar       = rootEl.querySelector('#tit-mode-bar');
  const studioGroupRow = rootEl.querySelector('#tit-studio-group-row');
  const libPanel      = rootEl.querySelector('#tit-lib-panel');
  const filterBar     = rootEl.querySelector('#tit-browse-filter-bar');
  const tagsPanel     = rootEl.querySelector('#tit-browse-tags-panel');
  const simpleFiltBar = rootEl.querySelector('#tit-simple-filter-bar');
  const dashPanel     = rootEl.querySelector('#tit-dashboard');
  const studioPanel   = rootEl.querySelector('#tit-studio-panel');
  const grid          = rootEl.querySelector('#tit-grid');
  const statusEl      = rootEl.querySelector('#tit-grid-status');
  const metaEl        = rootEl.querySelector('#tit-grid-meta');
  const sentinel      = rootEl.querySelector('#tit-sentinel');

  // ── Grid scroll state ──
  let gridState = { offset: 0, loading: false, exhausted: false, count: 0 };

  function resetGrid() {
    gridState = { offset: 0, loading: false, exhausted: false, count: 0 };
    grid.innerHTML = '';
    metaEl.textContent = '';
    statusEl.innerHTML = '';
  }

  async function loadMore() {
    if (gridState.loading || gridState.exhausted) return;
    if (!state.mode || state.mode === 'dashboard' || state.mode === 'studio') return;
    gridState.loading = true;
    statusEl.innerHTML = '<div class="shelf-loading">Loading…</div>';

    const smbPathPrefix = state.mode === 'unsorted'     ? (state.poolSmbPath || null)
                        : state.mode === 'archive-pool' ? (state.archivePoolSmbPath || null)
                        : null;

    const url  = buildUrl(state, gridState.offset, PAGE_LIMIT);
    let data   = await fetchJson(url, []);
    let list   = Array.isArray(data) ? data : (data?.items ?? data?.titles ?? []);

    // Prepend smbPath prefix to locations for unsorted/archive-pool
    if (smbPathPrefix) {
      list = list.map(t => {
        if (t.location)   t = { ...t, location:  smbPathPrefix + t.location };
        if (t.locations)  t = { ...t, locations: t.locations.map(p => smbPathPrefix + p) };
        return t;
      });
    }

    if (list.length === 0 && gridState.count === 0) {
      statusEl.innerHTML = `
        <div class="empty-state">
          <div class="empty-state-title">No titles found</div>
          <div class="empty-state-body">Try different filters.</div>
        </div>`;
      gridState.exhausted = true;
      gridState.loading = false;
      return;
    }

    list.forEach(t => grid.appendChild(renderTitleCard(t, { watched: true })));
    gridState.offset += list.length;
    gridState.count  += list.length;
    metaEl.textContent = `${gridState.count} loaded`;

    if (list.length < PAGE_LIMIT) {
      gridState.exhausted = true;
      statusEl.innerHTML = '<div class="shelf-loading">End of results.</div>';
    } else {
      statusEl.innerHTML = '';
    }
    gridState.loading = false;
  }

  function runQuery() {
    dashPanel.style.display   = 'none';
    studioPanel.style.display = 'none';
    grid.style.display        = '';
    applyGridCols(grid, state._cols);
    resetGrid();
    loadMore();
  }

  // ── Cols-only filter bar for Favorites / Bookmarks ──
  function showSimpleFilterBar() {
    simpleFiltBar.innerHTML = `<span class="tit-cols-ctrl">
      <span class="tit-cols-caption">Cols</span>
      <input type="range" class="tit-cols-slider" id="tit-simple-cols-slider" min="2" max="10" step="1" value="${state._cols}">
      <span class="tit-cols-label" id="tit-simple-cols-label">${state._cols}</span>
    </span>`;
    simpleFiltBar.style.display = '';
    const sl = simpleFiltBar.querySelector('#tit-simple-cols-slider');
    const lb = simpleFiltBar.querySelector('#tit-simple-cols-label');
    if (sl && lb) {
      sl.addEventListener('input', () => {
        const c = parseInt(sl.value, 10);
        lb.textContent = String(c);
        state._cols = c;
        applyGridCols(grid, c);
      });
    }
  }

  // ── Inject callback refs for library.js ──
  state._runQuery        = runQuery;
  state._scheduleQuery   = () => scheduleLibraryQuery(state);
  state._updateBreadcrumb = () => { /* breadcrumb lives in legacy app; noop in v2 */ };

  // ── Mode selection ────────────────────────────────────────────────────
  async function selectMode(modeKey) {
    if (modeKey !== state.mode) {
      resetBrowseFilters(state);
    }
    state.mode = modeKey;

    // Update mode buttons
    modeBar.querySelectorAll('.tit-mode-btn').forEach(btn => {
      btn.classList.toggle('on', btn.dataset.mode === modeKey);
    });

    // Tear down all panels before activating new one
    spotlightRotator.stop();
    unmountStudio(studioGroupRow, studioPanel);
    hideLibraryPanel(libPanel, state);
    hideBrowseFilterBar(filterBar, tagsPanel);
    simpleFiltBar.innerHTML = '';
    simpleFiltBar.style.display = 'none';
    dashPanel.style.display = 'none';
    grid.style.display = 'none';

    if (modeKey === 'dashboard') {
      dashPanel.style.display = '';
      renderDashboard(dashPanel, rootEl);
      return;
    }

    if (modeKey === 'studio') {
      studioGroupRow.style.display = '';
      studioPanel.style.display = '';
      mountStudio(state, studioGroupRow, studioPanel);
      return;
    }

    if (modeKey === 'library') {
      libPanel.style.display = '';
      await renderLibraryPanel(libPanel, state, cols => {
        state._cols = cols;
        applyGridCols(grid, cols);
      });
      runQuery();
      return;
    }

    if (FILTERABLE_MODES.has(modeKey)) {
      showBrowseFilterBar(filterBar, tagsPanel, state, cols => {
        state._cols = cols;
        applyGridCols(grid, cols);
      }, () => {
        resetGrid();
        loadMore();
      });
    } else {
      // Favorites / Bookmarks
      showSimpleFilterBar();
    }

    runQuery();
  }

  // ── Pool mode entry points ──
  async function handleUnsorted() {
    await enterUnsortedMode(state, selectMode);
  }
  async function handleArchive() {
    await enterArchiveMode(state, selectMode);
  }

  // ── Mode button clicks ──
  modeBar.addEventListener('click', e => {
    const btn = e.target.closest('.tit-mode-btn');
    if (!btn) return;
    const mode = btn.dataset.mode;
    if (mode === 'unsorted')     { handleUnsorted(); return; }
    if (mode === 'archive-pool') { handleArchive();  return; }
    selectMode(mode);
  });

  // ── Infinite scroll ──
  const io = new IntersectionObserver(entries => {
    if (entries.some(e => e.isIntersecting)) loadMore();
  }, { rootMargin: '400px' });
  io.observe(sentinel);

  // ── Initial mode ──
  // Honor URL params from cross-page links (e.g. ⌘K palette label/company hits).
  // Defaults to dashboard when no recognized params are present.
  const urlParams = new URLSearchParams(location.search);
  const urlCode    = urlParams.get('code');
  const urlCompany = urlParams.get('company');
  const urlTags    = urlParams.get('tags');
  if (urlCode || urlCompany || urlTags) {
    if (urlCode)    state.libraryCode    = urlCode;
    if (urlCompany) state.libraryCompany = urlCompany;
    if (urlTags)    state.activeTags     = new Set(urlTags.split(',').map(s => s.trim()).filter(Boolean));
    selectMode('library');
  } else {
    selectMode('dashboard');
  }
}
