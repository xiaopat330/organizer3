// actresses/index.js — Entry point for the v2 Actresses browse page.
//
// Top-level tabs: Browse | Admin
//   Browse — all existing mode-based browsing (dashboard, favorites, etc.)
//   Admin  — actress YAML management + alias editor (ported from legacy
//             Utilities → Actress Data screen)
//
// Browse modes (all ported from legacy):
//   dashboard       — KPI dashboard (spotlight, stats, strips)
//   favorites       — grid of favorited actresses
//   bookmarks       — grid of bookmarked actresses
//   exhibition-volumes — grid filtered to exhibition volume set + company dropdown
//   archive-volumes    — grid filtered to archive volume set + company dropdown
//   studio          — catalog: group chips + two-column company/label panel
//   studio-group:<slug> — filtered grid for one studio group + company dropdown
//   tier-GODDESS / SUPERSTAR / POPULAR / MINOR / LIBRARY
//                   — tier-filtered grid + company dropdown
//
// Grid controls:
//   - Scope chips (All / Favorites / Bookmarks) drive mode selection
//   - Tier chips (Goddess … Library) drive tier-X mode
//   - Exhibition / Archives / Studio buttons in sub-nav
//   - Company dropdowns per mode (tier / exhibition / archives)
//   - Column-count slider (4/5/6/8/10/12) per grid mode
//
// Single mount point: mountActresses(rootEl).

import { mountAdminTab, unmountAdminTab } from './admin.js';
import {
  renderActressCardWithNotes,
  resetNotesState,
  buildNotesFilterRow,
} from './notes.js';
import { renderActressDashboard } from './dashboard.js';
import {
  renderStudioGroupRow,
  selectStudioGroup,
  renderActressGridHeader,
} from './studio.js';
import {
  buildTierChips,
  updateTierChips,
  buildColsSlider,
  populateExhibitionCompanyDropdown,
  populateArchivesCompanyDropdown,
  populateTierCompanyDropdown,
} from './chips.js';
import { ensureStudioGroups } from '../../studio-data.js';
import { effectiveCols } from '../../grid-cols.js';

// ── Constants ─────────────────────────────────────────────────────────────

const PAGE_LIMIT       = 48;
const COLS_STORAGE_KEY = 'actress-browse-v2.cols';
const PAGE_TAB_KEY     = 'actress-browse-v2.page-tab';  // 'browse' | 'admin'

// ── Utils ─────────────────────────────────────────────────────────────────

async function fetchJson(url, fallback = null) {
  try {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) return fallback;
    return await r.json();
  } catch (e) {
    console.warn('[actresses] fetch failed:', url, e);
    return fallback;
  }
}

// ── State factory ─────────────────────────────────────────────────────────

function createState() {
  return {
    mode:              'dashboard',
    tierPanelOpen:     false,
    lastTier:          'GODDESS',
    studioSlug:        null,
    studioGroupName:   null,
    studioGroupCompany: null,
    exhibitionCompany: null,
    archivesCompany:   null,
    tierCompany:       null,
    // Scroll grid state
    offset:    0,
    loading:   false,
    exhausted: false,
    items:     [],
    // Config (loaded from /api/config)
    exhibitionVolumes: '',
    archiveVolumes:    '',
    // Post-It Notes filter: null | 'has_note' | 'no_note'
    notesFilter: null,
  };
}

// ── URL builder for grid modes ────────────────────────────────────────────

function appendNotesParam(url, state) {
  if (state.notesFilter) url += `&notes=${encodeURIComponent(state.notesFilter)}`;
  return url;
}

function buildGridUrl(state, offset, limit) {
  if (state.mode === 'favorites')
    return appendNotesParam(`/api/actresses?favorites=true&offset=${offset}&limit=${limit}`, state);
  if (state.mode === 'bookmarks')
    return appendNotesParam(`/api/actresses?bookmarks=true&offset=${offset}&limit=${limit}`, state);
  if (state.mode === 'exhibition-volumes') {
    let url = `/api/actresses?volumes=${encodeURIComponent(state.exhibitionVolumes)}&offset=${offset}&limit=${limit}`;
    if (state.exhibitionCompany) url += `&company=${encodeURIComponent(state.exhibitionCompany)}`;
    return appendNotesParam(url, state);
  }
  if (state.mode === 'archive-volumes') {
    let url = `/api/actresses?volumes=${encodeURIComponent(state.archiveVolumes)}&offset=${offset}&limit=${limit}`;
    if (state.archivesCompany) url += `&company=${encodeURIComponent(state.archivesCompany)}`;
    return appendNotesParam(url, state);
  }
  if (state.mode && state.mode.startsWith('tier-')) {
    const tier = state.mode.slice(5);
    let url = `/api/actresses?tier=${encodeURIComponent(tier)}&offset=${offset}&limit=${limit}`;
    if (state.tierCompany) url += `&company=${encodeURIComponent(state.tierCompany)}`;
    return appendNotesParam(url, state);
  }
  if (state.mode && state.mode.startsWith('studio-group:')) {
    const slug = state.mode.slice('studio-group:'.length);
    let url = `/api/actresses?studioGroup=${encodeURIComponent(slug)}&offset=${offset}&limit=${limit}`;
    if (state.studioGroupCompany) url += `&company=${encodeURIComponent(state.studioGroupCompany)}`;
    return appendNotesParam(url, state);
  }
  return null; // dashboard / studio — no grid
}

// ── Mount ─────────────────────────────────────────────────────────────────

export function mountActresses(rootEl) {
  // ── Render shell HTML ──────────────────────────────────────────────────
  rootEl.innerHTML = `
    <div class="aca-page-header"><h1 class="lib-page-title">Actresses</h1></div>
    <div class="aca-page-tabs tabs" id="aca-page-tabs">
      <button class="tab aca-page-tab" data-page-tab="browse">Browse</button>
      <button class="tab aca-page-tab" data-page-tab="admin">Admin</button>
    </div>

    <!-- Browse panel -->
    <div class="tab-panel aca-browse-panel" id="aca-browse-panel">
    <div class="act-page">

      <!-- Sub-nav: mode buttons -->
      <div class="act-subnav" id="act-subnav">
        <div class="act-subnav-group">
          <button type="button" class="act-subnav-btn on" data-mode="dashboard" id="act-btn-dashboard">Dashboard</button>
          <button type="button" class="act-subnav-btn" data-mode="favorites" id="act-btn-favorites">Favorites</button>
          <button type="button" class="act-subnav-btn" data-mode="bookmarks" id="act-btn-bookmarks">Bookmarks</button>
        </div>
        <div class="act-subnav-divider"></div>
        <div class="act-subnav-group">
          <button type="button" class="act-subnav-btn" data-mode="exhibition-volumes" id="act-btn-exhibition">Exhibition</button>
          <button type="button" class="act-subnav-btn" data-mode="archive-volumes" id="act-btn-archives">Archives</button>
          <button type="button" class="act-subnav-btn" data-mode="studio" id="act-btn-studio">Studio</button>
        </div>
        <div class="act-subnav-divider"></div>
        <div class="act-subnav-group">
          <button type="button" class="act-subnav-btn" id="act-btn-tier">Tier</button>
        </div>
      </div>

      <!-- Tier chip row (hidden by default) -->
      <div class="act-tier-row" id="act-tier-row" style="display:none">
        <!-- tier chips injected by buildTierChips() -->
        <div class="act-subnav-divider" id="act-tier-company-divider" style="display:none"></div>
        <select class="act-company-select" id="act-tier-company-select" style="display:none">
          <option value="">All Companies</option>
        </select>
        <!-- cols slider injected here for tier mode -->
      </div>

      <!-- Exhibition company row (hidden by default) -->
      <div class="act-company-row" id="act-exhibition-row" style="display:none">
        <span class="act-company-row-label">Company:</span>
        <select class="act-company-select" id="act-exhibition-company-select">
          <option value="">All Companies</option>
        </select>
        <!-- cols slider injected here for exhibition mode -->
      </div>

      <!-- Archives company row (hidden by default) -->
      <div class="act-company-row" id="act-archives-row" style="display:none">
        <span class="act-company-row-label">Company:</span>
        <select class="act-company-select" id="act-archives-company-select">
          <option value="">All Companies</option>
        </select>
        <!-- cols slider injected here for archives mode -->
      </div>

      <!-- Studio group row (hidden by default) -->
      <div class="act-studio-divider" id="act-studio-divider" style="display:none"></div>
      <div class="act-studio-group-row" id="act-studio-group-row" style="display:none">
        <!-- group buttons rendered by renderStudioGroupRow() -->
      </div>
      <div class="act-studio-group-header" id="act-studio-group-header" style="display:none"></div>
      <div class="act-studio-labels" id="act-studio-labels" style="display:none"></div>

      <!-- Grid header (studio-group mode) -->
      <div class="act-grid-header" id="act-grid-header" style="display:none"></div>

      <!-- Dashboard panel -->
      <div id="act-dashboard" style="display:none"></div>

      <!-- Actress grid -->
      <div class="act-grid" id="act-grid"></div>

      <!-- Grid status + sentinel -->
      <div class="act-grid-status" id="act-grid-status" style="display:none"></div>
      <div id="act-sentinel" style="height:1px"></div>

    </div>
    </div><!-- /aca-browse-panel -->

    <!-- Admin panel -->
    <div class="tab-panel aca-admin-panel" id="aca-admin-panel"></div>
  `;

  // ── DOM refs ─────────────────────────────────────────────────────────────
  const state = createState();

  const btnDashboard  = rootEl.querySelector('#act-btn-dashboard');
  const btnFavorites  = rootEl.querySelector('#act-btn-favorites');
  const btnBookmarks  = rootEl.querySelector('#act-btn-bookmarks');
  const btnExhibition = rootEl.querySelector('#act-btn-exhibition');
  const btnArchives   = rootEl.querySelector('#act-btn-archives');
  const btnStudio     = rootEl.querySelector('#act-btn-studio');
  const btnTier       = rootEl.querySelector('#act-btn-tier');

  const tierRow             = rootEl.querySelector('#act-tier-row');
  const tierCompanyDivider  = rootEl.querySelector('#act-tier-company-divider');
  const tierCompanySelect   = rootEl.querySelector('#act-tier-company-select');

  const exhibitionRow    = rootEl.querySelector('#act-exhibition-row');
  const exhibitionSelect = rootEl.querySelector('#act-exhibition-company-select');

  const archivesRow    = rootEl.querySelector('#act-archives-row');
  const archivesSelect = rootEl.querySelector('#act-archives-company-select');

  const studioDivider   = rootEl.querySelector('#act-studio-divider');
  const studioGroupRow  = rootEl.querySelector('#act-studio-group-row');
  const studioGroupHeader = rootEl.querySelector('#act-studio-group-header');
  const studioLabels    = rootEl.querySelector('#act-studio-labels');

  const gridHeaderEl  = rootEl.querySelector('#act-grid-header');
  const dashboardEl   = rootEl.querySelector('#act-dashboard');
  const gridEl        = rootEl.querySelector('#act-grid');
  const gridStatusEl  = rootEl.querySelector('#act-grid-status');
  const sentinelEl    = rootEl.querySelector('#act-sentinel');

  // ── Notes filter chip row ────────────────────────────────────────────────
  // Injected before the grid element; shown only when a scrollable grid mode
  // is active (dashboard and studio modes hide it).

  const notesFilter = buildNotesFilterRow(
    gridEl.parentNode,
    gridEl,
    state,
    () => {
      resetGrid();
      loadMore();
    }
  );

  // ── Config fetch ──────────────────────────────────────────────────────────
  fetchJson('/api/config').then(cfg => {
    if (!cfg) return;
    if (cfg.exhibitionVolumes) state.exhibitionVolumes = cfg.exhibitionVolumes.join(',');
    if (cfg.archiveVolumes)    state.archiveVolumes    = cfg.archiveVolumes.join(',');
  });

  // ── Grid: reset / loadMore ────────────────────────────────────────────────

  function applyGridCols(cols) {
    gridEl.style.gridTemplateColumns = `repeat(${cols}, 1fr)`;
  }

  function resetGrid() {
    state.offset    = 0;
    state.loading   = false;
    state.exhausted = false;
    state.items     = [];
    gridEl.innerHTML = '';
    gridStatusEl.style.display = 'none';
    gridStatusEl.innerHTML = '';
    resetNotesState();
  }

  function ensureSentinel() {
    // sentinel is always at the bottom of the page DOM, re-use it
  }

  async function loadMore() {
    const url = buildGridUrl(state, state.offset, PAGE_LIMIT);
    if (!url || state.loading || state.exhausted) return;
    state.loading = true;

    gridStatusEl.style.display = '';
    gridStatusEl.innerHTML = '<div class="act-grid-loading">Loading…</div>';

    const data = await fetchJson(url, []);
    const list  = Array.isArray(data) ? data : (data?.items ?? []);

    if (list.length === 0 && state.items.length === 0) {
      gridStatusEl.innerHTML = `
        <div class="act-grid-empty">
          <div class="act-grid-empty-title">No actresses match these filters.</div>
          <div class="act-grid-empty-body">Try a different scope or filter.</div>
        </div>`;
      state.exhausted = true;
      state.loading   = false;
      return;
    }

    state.items.push(...list);
    list.forEach(a => gridEl.appendChild(renderActressCardWithNotes(a)));
    state.offset += list.length;

    if (list.length < PAGE_LIMIT) {
      state.exhausted = true;
      gridStatusEl.innerHTML = `<div class="act-grid-end">End · ${state.items.length} actresses</div>`;
    } else {
      gridStatusEl.innerHTML = `<div class="act-grid-count">${state.items.length} loaded</div>`;
    }
    state.loading = false;
  }

  // ── Infinite scroll observer ──────────────────────────────────────────────

  const io = new IntersectionObserver((entries) => {
    if (entries.some(e => e.isIntersecting)) loadMore();
  }, { rootMargin: '400px' });
  io.observe(sentinelEl);

  // ── Sub-nav selection helper ──────────────────────────────────────────────

  function updateSubnavSelection() {
    const mode = state.mode;
    btnDashboard.classList.toggle('on',  mode === 'dashboard');
    btnFavorites.classList.toggle('on',  mode === 'favorites');
    btnBookmarks.classList.toggle('on',  mode === 'bookmarks');
    btnExhibition.classList.toggle('on', mode === 'exhibition-volumes');
    btnArchives.classList.toggle('on',   mode === 'archive-volumes');
    btnStudio.classList.toggle('on',
      mode === 'studio' || (mode || '').startsWith('studio-group:'));
    btnTier.classList.toggle('on', state.tierPanelOpen);
    updateTierChips(tierRow, state);
  }

  // ── Row show/hide helpers ─────────────────────────────────────────────────

  function hideTierRow() {
    state.tierPanelOpen = false;
    tierRow.style.display = 'none';
    tierCompanyDivider.style.display = 'none';
    tierCompanySelect.style.display  = 'none';
    state.tierCompany = null;
  }

  function showTierRow() {
    state.tierPanelOpen = true;
    tierRow.style.display = '';
    tierCompanyDivider.style.display = '';
    tierCompanySelect.style.display  = '';
  }

  function hideExhibition() {
    exhibitionRow.style.display  = 'none';
    state.exhibitionCompany = null;
  }

  function showExhibition() {
    exhibitionRow.style.display = '';
  }

  function hideArchives() {
    archivesRow.style.display  = 'none';
    state.archivesCompany = null;
  }

  function showArchives() {
    archivesRow.style.display = '';
  }

  function hideStudio() {
    studioDivider.style.display     = 'none';
    studioGroupRow.style.display    = 'none';
    studioGroupHeader.style.display = 'none';
    studioGroupHeader.innerHTML     = '';
    studioLabels.style.display      = 'none';
    state.studioSlug                = null;
  }

  function showStudio() {
    studioDivider.style.display  = '';
    studioGroupRow.style.display = '';
    studioLabels.style.display   = 'none'; // shown after group selection
  }

  function hideAllSubRows() {
    hideTierRow();
    hideExhibition();
    hideArchives();
  }

  function hideGridHeader() {
    gridHeaderEl.style.display = 'none';
    gridHeaderEl.innerHTML = '';
  }

  // ── Column slider helpers ─────────────────────────────────────────────────

  function injectColsSlider(rowEl) {
    buildColsSlider(rowEl, COLS_STORAGE_KEY, applyGridCols);
  }

  function attachColsSliderForMode() {
    // Inject slider into the row that's visible for the current mode
    const mode = state.mode;
    if (mode === 'exhibition-volumes') {
      injectColsSlider(exhibitionRow);
    } else if (mode === 'archive-volumes') {
      injectColsSlider(archivesRow);
    } else if (mode && mode.startsWith('tier-')) {
      injectColsSlider(tierRow);
    } else if (mode && mode.startsWith('studio-group:')) {
      const right = gridHeaderEl.querySelector('.act-grid-header-right');
      if (right) injectColsSlider(right);
    } else {
      // favorites / bookmarks — inject a standalone bar above the grid
      let bar = rootEl.querySelector('#act-cols-bar');
      if (!bar) {
        bar = document.createElement('div');
        bar.id = 'act-cols-bar';
        bar.className = 'act-cols-bar';
        gridEl.parentNode.insertBefore(bar, gridEl);
      }
      injectColsSlider(bar);
    }
    applyGridCols(effectiveCols(COLS_STORAGE_KEY));
  }

  // ── Mode dispatcher ───────────────────────────────────────────────────────

  async function selectMode(modeKey) {
    state.mode = modeKey;

    // Remove standalone cols bar if present
    rootEl.querySelector('#act-cols-bar')?.remove();

    // Dashboard mode
    if (modeKey === 'dashboard') {
      hideAllSubRows();
      hideStudio();
      hideGridHeader();
      notesFilter.hide();
      state.studioGroupName    = null;
      state.studioGroupCompany = null;
      gridEl.style.display     = 'none';
      gridEl.innerHTML         = '';
      gridStatusEl.style.display = 'none';
      dashboardEl.style.display  = 'block';
      updateSubnavSelection();
      await renderActressDashboard(dashboardEl, (slug) => selectMode(`studio-group:${slug}`));
      return;
    }

    dashboardEl.style.display = 'none';

    // Studio catalog mode
    if (modeKey === 'studio') {
      hideAllSubRows();
      hideGridHeader();
      notesFilter.hide();
      state.studioGroupName    = null;
      state.studioGroupCompany = null;
      gridEl.style.display     = 'none';
      gridEl.innerHTML         = '';
      gridStatusEl.style.display = 'none';
      showStudio();
      updateSubnavSelection();

      const groups = await renderStudioGroupRow(studioGroupRow, state, (slug) => {
        selectStudioGroup(
          studioGroupRow, studioGroupHeader, studioLabels, gridEl,
          state, slug,
          (s) => selectMode(`studio-group:${s}`)
        ).then(() => {
          studioLabels.style.display = 'flex';
        });
      });

      if (groups.length > 0) {
        const slug = state.studioSlug || groups[0].slug;
        await selectStudioGroup(
          studioGroupRow, studioGroupHeader, studioLabels, gridEl,
          state, slug,
          (s) => selectMode(`studio-group:${s}`)
        );
        studioLabels.style.display = 'flex';
      }
      return;
    }

    // Hide studio panel for non-studio modes
    hideStudio();

    // Exhibition / Archives
    if (modeKey === 'exhibition-volumes') {
      state.exhibitionCompany  = null;
      state.studioGroupName    = null;
      state.studioGroupCompany = null;
      hideTierRow();
      hideArchives();
      hideGridHeader();
      showExhibition();
      await populateExhibitionCompanyDropdown(state, exhibitionSelect);
    } else if (modeKey === 'archive-volumes') {
      state.archivesCompany    = null;
      state.studioGroupName    = null;
      state.studioGroupCompany = null;
      hideTierRow();
      hideExhibition();
      hideGridHeader();
      showArchives();
      await populateArchivesCompanyDropdown(state, archivesSelect);
    } else {
      hideExhibition();
      hideArchives();
    }

    // Studio-group grid
    if (modeKey.startsWith('studio-group:')) {
      const slug = modeKey.slice('studio-group:'.length);
      state.studioSlug         = slug;
      state.studioGroupCompany = null;
      const groups = await ensureStudioGroups();
      const group  = groups.find(g => g.slug === slug);
      state.studioGroupName = group ? group.name : slug;

      let companyCounts = null;
      try {
        const resp = await fetch(`/api/studio-groups/${encodeURIComponent(slug)}/companies`);
        companyCounts = resp.ok ? await resp.json() : null;
      } catch (_) {}

      renderActressGridHeader(
        gridHeaderEl, state, group, slug, companyCounts,
        { reset: resetGrid, loadMore, resetAndLoad: () => { resetGrid(); loadMore(); } },
        selectMode,
        ensureSentinel
      );
      hideTierRow();
    } else {
      state.studioGroupName    = null;
      state.studioGroupCompany = null;
      hideGridHeader();

      if (modeKey.startsWith('tier-')) {
        state.lastTier    = modeKey.slice(5);
        state.tierCompany = null;
        showTierRow();
        await populateTierCompanyDropdown(state, tierCompanySelect);
      } else {
        hideTierRow();
      }
    }

    updateSubnavSelection();
    gridEl.style.display = '';
    gridStatusEl.style.display = '';
    notesFilter.show();
    attachColsSliderForMode();
    resetGrid();
    loadMore();
  }

  // ── Company select change handlers ────────────────────────────────────────

  exhibitionSelect.addEventListener('change', () => {
    state.exhibitionCompany = exhibitionSelect.value || null;
    resetGrid();
    loadMore();
  });

  archivesSelect.addEventListener('change', () => {
    state.archivesCompany = archivesSelect.value || null;
    resetGrid();
    loadMore();
  });

  tierCompanySelect.addEventListener('change', () => {
    state.tierCompany = tierCompanySelect.value || null;
    resetGrid();
    loadMore();
  });

  // ── Button wiring ─────────────────────────────────────────────────────────

  btnDashboard.addEventListener('click',  () => selectMode('dashboard'));
  btnFavorites.addEventListener('click',  () => selectMode('favorites'));
  btnBookmarks.addEventListener('click',  () => selectMode('bookmarks'));
  btnExhibition.addEventListener('click', () => selectMode('exhibition-volumes'));
  btnArchives.addEventListener('click',   () => selectMode('archive-volumes'));
  btnStudio.addEventListener('click',     () => selectMode('studio'));
  btnTier.addEventListener('click', () => {
    selectMode(`tier-${state.lastTier}`);
  });

  // ── Tier chips ────────────────────────────────────────────────────────────

  buildTierChips(tierRow, tierCompanyDivider, (modeKey) => selectMode(modeKey));

  // ── Grid header's scrollGrid interface ───────────────────────────────────
  // renderActressGridHeader expects a scrollGrid-like object with .reset() + .loadMore().
  // We pass { reset: resetGrid, loadMore } — the caller also needs ensureSentinel.
  // This is already handled above inside selectMode.

  // ── Page-level Browse | Admin tab strip ───────────────────────────────────

  const browsePanel = rootEl.querySelector('#aca-browse-panel');
  const adminPanel  = rootEl.querySelector('#aca-admin-panel');

  let adminMounted = false;

  function selectPageTab(tab) {
    localStorage.setItem(PAGE_TAB_KEY, tab);
    rootEl.querySelectorAll('.aca-page-tab').forEach(btn =>
      btn.classList.toggle('active', btn.dataset.pageTab === tab));

    if (tab === 'admin') {
      browsePanel.classList.remove('active');
      adminPanel.classList.add('active');
      if (!adminMounted) {
        adminMounted = true;
        mountAdminTab(adminPanel);
      }
    } else {
      adminPanel.classList.remove('active');
      browsePanel.classList.add('active');
    }
  }

  rootEl.querySelectorAll('.aca-page-tab').forEach(btn =>
    btn.addEventListener('click', () => selectPageTab(btn.dataset.pageTab)));

  // ── Initial load ──────────────────────────────────────────────────────────

  const initialTab = localStorage.getItem(PAGE_TAB_KEY) || 'browse';

  // Activate browse panel content before the tab decision so dashboard renders
  // even if the initial tab ends up being Admin.
  dashboardEl.style.display = 'block';
  gridEl.style.display      = 'none';
  renderActressDashboard(dashboardEl, (slug) => selectMode(`studio-group:${slug}`));

  // Apply initial tab (activates panel via .active class; browse has no
  // special async setup beyond the dashboard render above).
  selectPageTab(initialTab);
}
