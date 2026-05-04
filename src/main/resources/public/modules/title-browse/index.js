import { esc } from '../utils.js';
import { pushNav } from '../nav.js';
import { showView, setActiveGrid, ensureSentinel, updateBreadcrumb, ScrollingGrid } from '../grid.js';
import { makeTitleCard } from '../cards.js';
import { ensureStudioGroups } from '../studio-data.js';
import { resetActressState, actressesBtn } from '../actress-browse.js';
import { MAX_TOTAL } from '../config.js';
import { effectiveCols, colsSliderHtml, wireColsSlider } from '../grid-cols.js';

import { renderTitleDashboard, titleSpotlightRotator } from './dashboard.js';
import { renderLibraryFilterPanel, hideTagsPanel, scheduleLibraryQuery } from './library.js';
import { enterUnsortedMode as enterUnsortedModeImpl, enterArchiveMode as enterArchiveModeImpl, showBrowseFilterBar, hideBrowseFilterBar, resetBrowseFilters } from './pool.js';
import { loadAndRenderStudioGroupRow, selectStudioGroup, showStudioGroupRow, hideStudioGroupRow } from './studio.js';

// ── DOM refs ──────────────────────────────────────────────────────────────
export const titlesBrowseBtn    = document.getElementById('titles-browse-btn');
const titleLandingEl            = document.getElementById('title-landing');
const titleDashboardBtn         = document.getElementById('title-dashboard-btn');
const titleDashboardEl          = document.getElementById('title-dashboard');
const titleFavoritesBtn         = document.getElementById('title-favorites-btn');
const titleBookmarksBtn         = document.getElementById('title-bookmarks-btn');
const titleStudioBtn            = document.getElementById('title-studio-btn');
const titleStudioDivider        = document.getElementById('title-studio-divider');
const titleStudioGroupRow       = document.getElementById('title-studio-group-row');
const titleStudioLabelsEl       = document.getElementById('title-studio-labels');
export const collectionsBtn     = document.getElementById('title-collections-btn');
export const titleUnsortedBtn   = document.getElementById('title-unsorted-btn');
export const titleArchivesBtn   = document.getElementById('title-archives-btn');
const titleTagsBtn              = document.getElementById('title-tags-btn');
const titleTagsPanel            = document.getElementById('title-tags-panel');

// ── Column count control ──────────────────────────────────────────────────
function applyTitleGridCols(cols) {
  const grid = document.getElementById('titles-browse-grid');
  if (grid) grid.style.gridTemplateColumns = `repeat(${cols}, 1fr)`;
}

function showColsOnlyFilterBar() {
  const bar = document.getElementById('title-browse-filter-bar');
  if (!bar) return;
  bar.innerHTML = colsSliderHtml(effectiveCols(), 'title-cols-control', 'title-cols-slider', 'title-cols-label');
  bar.style.display = '';
  wireColsSlider('title-cols-slider', 'title-cols-label', applyTitleGridCols);
}

// ── State ─────────────────────────────────────────────────────────────────
function createTitleBrowseState() {
  return {
    mode: null,
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
    libraryAutoTimer: null,
    libraryAutoVisible: false,
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
    selectedStudioSlug: null,
    /** Clears collections/unsorted/archive filter state only. */
    resetBrowse() {
      this.browseCompanyFilter = null;
      this.browseActiveTags    = new Set();
      this.browseCatalogTags   = null;
      this.browseTagsForMode   = null;
      if (this.browseFilterTimer) { clearTimeout(this.browseFilterTimer); this.browseFilterTimer = null; }
    },
  };
}
const state = createTitleBrowseState();

// Inject callback references so sibling modules can schedule queries without
// importing index.js (which would create cycles).
state._scheduleQuery    = () => scheduleLibraryQuery(state);
state._runQuery         = () => runTitleBrowseQuery();
state._updateBreadcrumb = () => updateTitleBreadcrumb();

/** Getter for external consumers (replaces the previous live-binding export). */
export function getTitleBrowseMode() { return state.mode; }
/** Getter for external consumers that need the library-mode tag set. */
export function getActiveTags() { return state.activeTags; }

// ── Browse-mode constants ─────────────────────────────────────────────────
const FILTERABLE_MODES = new Set(['collections', 'unsorted', 'archive-pool']);

// ── Scrolling grid ────────────────────────────────────────────────────────
export const allTitlesGrid = new ScrollingGrid(
  document.getElementById('titles-browse-grid'),
  (o, l) => {
    if (state.mode === 'favorites')
      return `/api/titles?favorites=true&offset=${o}&limit=${l}`;
    if (state.mode === 'bookmarks')
      return `/api/titles?bookmarks=true&offset=${o}&limit=${l}`;
    if (state.mode === 'collections') {
      let url = `/api/collections/titles?offset=${o}&limit=${l}`;
      if (state.browseCompanyFilter) url += `&company=${encodeURIComponent(state.browseCompanyFilter)}`;
      if (state.browseActiveTags.size > 0) url += `&tags=${encodeURIComponent([...state.browseActiveTags].join(','))}`;
      return url;
    }
    if (state.mode === 'unsorted') {
      let url = `/api/pool/${encodeURIComponent(state.poolVolumeId)}/titles?offset=${o}&limit=${l}`;
      if (state.browseCompanyFilter) url += `&company=${encodeURIComponent(state.browseCompanyFilter)}`;
      if (state.browseActiveTags.size > 0) url += `&tags=${encodeURIComponent([...state.browseActiveTags].join(','))}`;
      return url;
    }
    if (state.mode === 'archive-pool') {
      let url = `/api/pool/${encodeURIComponent(state.archivePoolVolumeId)}/titles?offset=${o}&limit=${l}`;
      if (state.browseCompanyFilter) url += `&company=${encodeURIComponent(state.browseCompanyFilter)}`;
      if (state.browseActiveTags.size > 0) url += `&tags=${encodeURIComponent([...state.browseActiveTags].join(','))}`;
      return url;
    }
    if (state.mode === 'library') {
      const params = new URLSearchParams({ offset: o, limit: l });
      if (state.libraryCode.trim())                 params.set('code',             state.libraryCode.trim());
      if (state.libraryCompany)                     params.set('company',          state.libraryCompany);
      if (state.activeTags.size > 0)                params.set('tags',             [...state.activeTags].join(','));
      if (state.activeEnrichmentTagIds.size > 0)    params.set('enrichmentTagIds', [...state.activeEnrichmentTagIds].join(','));
      if (state.librarySort !== 'addedDate')         params.set('sort',             state.librarySort);
      if (state.libraryOrder !== 'desc')             params.set('order',            state.libraryOrder);
      return `/api/titles?${params}`;
    }
    return `/api/titles?offset=${o}&limit=${l}`;
  },
  t => {
    if (state.mode === 'unsorted' && state.poolSmbPath) {
      if (t.location)  t.location  = state.poolSmbPath + t.location;
      if (t.locations) t.locations = t.locations.map(p => state.poolSmbPath + p);
    } else if (state.mode === 'archive-pool' && state.archivePoolSmbPath) {
      if (t.location)  t.location  = state.archivePoolSmbPath + t.location;
      if (t.locations) t.locations = t.locations.map(p => state.archivePoolSmbPath + p);
    }
    return makeTitleCard(t);
  },
  'no titles',
  { getMax: () => MAX_TOTAL }
);

// ── Query runner ──────────────────────────────────────────────────────────
export function runTitleBrowseQuery() {
  titleDashboardEl.style.display = 'none';
  document.getElementById('titles-browse-grid').style.display = 'grid';
  setActiveGrid(allTitlesGrid);
  if (!FILTERABLE_MODES.has(state.mode) && state.mode !== 'library') showColsOnlyFilterBar();
  applyTitleGridCols(effectiveCols());
  allTitlesGrid.reset();
  ensureSentinel();
  allTitlesGrid.loadMore();
}

// ── Landing UI state ──────────────────────────────────────────────────────
function updateTitleLandingSelection() {
  titleDashboardBtn.classList.toggle('selected', state.mode === 'dashboard');
  titleFavoritesBtn.classList.toggle('selected', state.mode === 'favorites');
  titleBookmarksBtn.classList.toggle('selected', state.mode === 'bookmarks');
  titleStudioBtn.classList.toggle('selected',    state.mode === 'studio');
  collectionsBtn.classList.toggle('selected',    state.mode === 'collections');
  titleUnsortedBtn.classList.toggle('selected',  state.mode === 'unsorted');
  titleArchivesBtn.classList.toggle('selected',  state.mode === 'archive-pool');
  titleTagsBtn.classList.toggle('selected',      state.mode === 'library');
}

function updateTitleBreadcrumb() {
  const crumbs = [{ label: 'Titles', action: () => showTitlesBrowse() }];
  if (state.mode === 'dashboard')     { /* no sub-item — dashboard IS the home */ }
  else if (state.mode === 'favorites')     crumbs.push({ label: 'Favorites' });
  else if (state.mode === 'bookmarks')  crumbs.push({ label: 'Bookmarks' });
  else if (state.mode === 'studio')     crumbs.push({ label: 'Studio' });
  else if (state.mode === 'collections') crumbs.push({ label: 'Collections' });
  else if (state.mode === 'unsorted')   crumbs.push({ label: 'Unsorted' });
  else if (state.mode === 'archive-pool') crumbs.push({ label: 'Archives' });
  else if (state.mode === 'library') {
    const parts = [];
    if (state.libraryCode.trim())  parts.push(state.libraryCode.trim().toUpperCase());
    if (state.libraryCompany)      parts.push(state.libraryCompany);
    const totalTagSel = state.activeTags.size + state.activeEnrichmentTagIds.size;
    if (totalTagSel > 0) parts.push(`${totalTagSel} tag${totalTagSel > 1 ? 's' : ''}`);
    crumbs.push({ label: parts.length > 0 ? `Library (${parts.join(', ')})` : 'Library' });
  }
  updateBreadcrumb(crumbs);
}

// ── Browse mode selection ─────────────────────────────────────────────────
export function selectTitleBrowseMode(modeKey) {
  pushNav({ view: 'titles-browse', mode: modeKey }, 'browse/' + modeKey);
  if (modeKey !== state.mode) {
    resetBrowseFilters(state);
  }
  state.mode = modeKey;
  updateTitleLandingSelection();
  updateTitleBreadcrumb();
  titlesBrowseBtn.classList.add('active');
  showView('titles-browse');
  if (modeKey === 'dashboard') {
    showView('titles-browse');
    document.getElementById('titles-browse-grid').style.display = 'none';
    hideStudioGroupRow(state, titleStudioDivider, titleStudioGroupRow, titleStudioLabelsEl);
    hideTagsPanel(state, titleTagsPanel);
    hideBrowseFilterBar();
    titleDashboardEl.style.display = 'block';
    renderTitleDashboard(titleDashboardEl);
    return;
  }
  titleSpotlightRotator.stop();
  titleDashboardEl.style.display = 'none';
  if (modeKey === 'studio') {
    document.getElementById('titles-browse-grid').style.display = 'none';
    titleStudioLabelsEl.style.display = 'none';
    hideTagsPanel(state, titleTagsPanel);
    hideBrowseFilterBar();
    loadAndRenderStudioGroupRow(state, titleStudioGroupRow, titleStudioLabelsEl).then(groups => {
      showStudioGroupRow(titleStudioDivider, titleStudioGroupRow);
      if (groups.length > 0 && !state.selectedStudioSlug) {
        selectStudioGroup(state, groups[0].slug, titleStudioGroupRow, titleStudioLabelsEl);
      }
    });
    return;
  }
  if (modeKey === 'library') {
    hideStudioGroupRow(state, titleStudioDivider, titleStudioGroupRow, titleStudioLabelsEl);
    hideBrowseFilterBar();
    titleTagsPanel.style.display = 'block';
    requestAnimationFrame(() => {
      const header  = document.querySelector('header');
      const subNav  = document.getElementById('sub-nav-search-bar');
      const landing = document.getElementById('title-landing');
      const h = (header  ? header.offsetHeight  : 0)
              + (subNav  && subNav.style.display  !== 'none' ? subNav.offsetHeight  : 0)
              + (landing ? landing.offsetHeight : 0);
      titleTagsPanel.style.top = h + 'px';
    });
    runTitleBrowseQuery();
    return;
  }
  hideStudioGroupRow(state, titleStudioDivider, titleStudioGroupRow, titleStudioLabelsEl);
  hideTagsPanel(state, titleTagsPanel);
  if (FILTERABLE_MODES.has(modeKey)) {
    showBrowseFilterBar(state, allTitlesGrid, applyTitleGridCols);
  }
  runTitleBrowseQuery();
}

// ── showTitlesBrowse ──────────────────────────────────────────────────────
export function showTitlesBrowse() {
  titlesBrowseBtn.classList.add('active');
  actressesBtn.classList.remove('active');
  collectionsBtn.classList.remove('active');
  document.getElementById('av-btn')?.classList.remove('active');
  document.getElementById('action-btn')?.classList.remove('active');
  resetActressState();
  if (state.tagsDebounceTimer)   { clearTimeout(state.tagsDebounceTimer);  state.tagsDebounceTimer  = null; }
  if (state.libraryAutoTimer)    { clearTimeout(state.libraryAutoTimer);   state.libraryAutoTimer   = null; }
  state.mode = null;
  state.activeTags.clear();
  state.activeEnrichmentTagIds.clear();
  state.libraryCode    = '';
  state.libraryCompany = null;
  state.librarySort    = 'addedDate';
  state.libraryOrder   = 'desc';
  resetBrowseFilters(state);
  hideStudioGroupRow(state, titleStudioDivider, titleStudioGroupRow, titleStudioLabelsEl);
  hideTagsPanel(state, titleTagsPanel);
  hideBrowseFilterBar();
  updateTitleLandingSelection();
  showView('titles-browse');
  selectTitleBrowseMode('dashboard');
}

// ── Pool entry points (exported for external callers) ─────────────────────
export async function enterUnsortedMode() {
  return enterUnsortedModeImpl(state, selectTitleBrowseMode);
}

export async function enterArchiveMode() {
  return enterArchiveModeImpl(state, selectTitleBrowseMode);
}

// ── Button wiring ─────────────────────────────────────────────────────────
titlesBrowseBtn.addEventListener('click', showTitlesBrowse);
titleDashboardBtn.addEventListener('click', () => selectTitleBrowseMode('dashboard'));
titleFavoritesBtn.addEventListener('click', () => selectTitleBrowseMode('favorites'));
titleBookmarksBtn.addEventListener('click', () => selectTitleBrowseMode('bookmarks'));
titleStudioBtn.addEventListener('click',    () => selectTitleBrowseMode('studio'));
collectionsBtn.addEventListener('click',    () => selectTitleBrowseMode('collections'));
titleUnsortedBtn.addEventListener('click',  () => enterUnsortedMode());
titleArchivesBtn.addEventListener('click',  () => enterArchiveMode());

titleTagsBtn.addEventListener('click', async () => {
  if (state.mode === 'library') return;
  try {
    selectTitleBrowseMode('library');
    await renderLibraryFilterPanel(state, titleTagsPanel, applyTitleGridCols);
  } catch (err) {
    console.error('Failed to render library panel', err);
  }
});
