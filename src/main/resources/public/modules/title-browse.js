import { esc } from './utils.js';
import { pushNav } from './nav.js';
import { showView, setActiveGrid, ensureSentinel, updateBreadcrumb, ScrollingGrid, mode } from './grid.js';
import { makeTitleCard, makeCompactTitleCard, makeActressCard, agingLabel } from './cards.js';
import { openActressDetail } from './actress-detail.js';
import { tagBadgeHtml } from './icons.js';
import { ensureStudioGroups, ensureTitleLabels, renderTwoColumnStudioPanel, updateCompanyMarquee } from './studio-data.js';
import { resetActressState, actressesBtn } from './actress-browse.js';
import { MAX_TOTAL } from './config.js';
import { effectiveCols, colsSliderHtml, wireColsSlider } from './grid-cols.js';
import {
  renderDashboardStrip,
  renderDashboardSection,
  renderSideBySidePanel,
  createSpotlightRotator,
} from './dashboard-panels.js';
import {
  renderTopLabelsLeaderboard,
  renderTitleLibraryStats,
} from './dashboard-renderers.js';

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
/**
 * All mutable title-browse state lives in one object. Kept flat (not grouped
 * into library/browse/pool sub-objects) — bundling is a follow-up refactor.
 *
 * Fields:
 *   mode               — null | 'dashboard' | 'favorites' | 'bookmarks'
 *                        | 'collections' | 'unsorted' | 'archive-pool'
 *                        | 'library' | 'studio'
 *   state.activeTags         — Set of library-mode tag filters (live reference used in `.add()/.delete()`)
 *   state.tagsDebounceTimer  — library-mode query debounce handle
 *   state.tagsCatalog        — cached /api/tags response
 *   state.tagsBarOpen        — library-mode tags panel visibility
 *   state.tagsPendingChanged — library-mode queued change flag
 *   state.chipsHideTimer     — chip bar auto-hide handle
 *   state.libraryCode        — library-mode product-code input
 *   state.libraryCompany     — library-mode company filter
 *   state.librarySort        — 'addedDate' | 'productCode' | 'actressName'
 *   state.libraryOrder       — 'asc' | 'desc'
 *   state.libraryAutoTimer   — autocomplete fetch debounce
 *   state.libraryAutoVisible — autocomplete dropdown visible
 *   state.poolVolumeId       — lazy-loaded unsorted pool volume id
 *   state.poolSmbPath        — lazy-loaded unsorted pool SMB path prefix
 *   state.archivePoolVolumeId — lazy-loaded archive pool volume id
 *   state.archivePoolSmbPath  — lazy-loaded archive pool SMB path prefix
 *   state.queuesVolumeData   — cached /api/queues/volumes response
 *   state.browseCompanyFilter — filter in collections/unsorted/archive modes
 *   state.browseActiveTags   — tag filters in collections/unsorted/archive modes
 *   state.browseFilterTimer  — browse-mode query debounce
 *   state.browseCatalogTags  — cached tag list for current browse mode
 *   state.browseTagsForMode  — which mode state.browseCatalogTags belongs to
 *   state.allCompanies       — cached full company list
 *   state.selectedStudioSlug — studio-group slug currently active
 */
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

/** Getter for external consumers (replaces the previous live-binding export). */
export function getTitleBrowseMode() { return state.mode; }
/** Getter for external consumers that need the library-mode tag set. */
export function getActiveTags() { return state.activeTags; }

// Spotlight rotation (title-specific instance of the shared rotator)
const SPOTLIGHT_INTERVAL_MS = 30_000;
const titleSpotlightRotator = createSpotlightRotator({
  endpoint: '/api/titles/spotlight',
  excludeAttr: 'code',
  makeCard: t => {
    const card = makeTitleCard(t);
    card.classList.add('card-spotlight');
    return card;
  },
  intervalMs: SPOTLIGHT_INTERVAL_MS,
});

// ── Browse-mode constants ────────────────────────────────────────────────
const FILTERABLE_MODES = new Set(['collections', 'unsorted', 'archive-pool']);
const BROWSE_FILTER_DEBOUNCE_MS = 350;

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

// ── Dashboard render ──────────────────────────────────────────────────────
function makeTitleCardWithAging(t) {
  const card = makeTitleCard(t);
  const label = agingLabel(t.addedDate);
  if (label) {
    const badge = document.createElement('div');
    badge.className = 'title-card-aging';
    badge.textContent = label;
    const coverWrap = card.querySelector('.cover-wrap');
    (coverWrap || card).appendChild(badge);
  }
  return card;
}

function renderTopInfoPanel(spotlight, topLabels, libraryStats, onThisDay) {
  const panel = document.createElement('div');
  panel.className = 'dashboard-top-panel';

  // Left column: Spotlight card
  if (spotlight) {
    const left = document.createElement('div');
    left.className = 'dashboard-top-panel-left';
    const header = document.createElement('div');
    header.className = 'dashboard-section-title';
    header.textContent = 'Spotlight';
    left.appendChild(header);
    const card = makeTitleCard(spotlight);
    card.classList.add('card-spotlight');
    left.appendChild(card);
    panel.appendChild(left);
    // Store ref and start rotation after this render cycle
    setTimeout(() => titleSpotlightRotator.start(left), SPOTLIGHT_INTERVAL_MS);
  }

  // Right column: upper row (Top Labels + Library side-by-side) + lower (On This Day)
  if (topLabels.length > 0 || libraryStats || onThisDay.length > 0) {
    const right = document.createElement('div');
    right.className = 'dashboard-top-panel-right';

    // Upper: Top Labels (left) + Library (right) in a 2-column sub-row
    if (topLabels.length > 0 || libraryStats) {
      const upper = document.createElement('div');
      upper.className = 'dashboard-top-right-upper';
      if (topLabels.length > 0) upper.appendChild(renderTopLabelsSection(topLabels));
      if (libraryStats)         upper.appendChild(renderTitleLibraryStats(libraryStats));
      right.appendChild(upper);
    }

    // Lower: On This Day — compact cards, max 3 shown
    if (onThisDay.length > 0) {
      const shown = onThisDay.slice(0, 3);
      const strip = renderDashboardStrip(shown, { id: 'dash-on-this-day', cardFactory: makeCompactTitleCard });
      right.appendChild(renderDashboardSection({
        title: 'On This Day',
        badge: `${onThisDay.length} memor${onThisDay.length === 1 ? 'y' : 'ies'}`,
        body: strip,
      }));
    }

    panel.appendChild(right);
  }

  return panel;
}

function renderTopLabelsSection(topLabels) {
  return renderTopLabelsLeaderboard(topLabels, {
    onRowClick: (lbl) => {
      const searchInput = document.getElementById('search-input');
      if (searchInput) {
        searchInput.value = lbl.code + '-';
        searchInput.dispatchEvent(new Event('input'));
        searchInput.focus();
      }
    },
  });
}

async function renderTitleDashboard() {
  titleDashboardEl.innerHTML = '<div class="dashboard-loading">loading…</div>';
  try {
    const res = await fetch('/api/titles/dashboard');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();

    const onDeck             = data.onDeck             || [];
    const justAdded          = data.justAdded          || [];
    const fromFavoriteLabels = data.fromFavoriteLabels || [];
    const recentlyViewed     = data.recentlyViewed     || [];
    const spotlight          = data.spotlight          || null;
    const forgottenAttic     = data.forgottenAttic     || [];
    const forgottenFavorites = data.forgottenFavorites || [];
    const onThisDay          = data.onThisDay          || [];
    const topLabels          = data.topLabels          || [];
    const libraryStats       = data.libraryStats       || null;

    const hasAny = onDeck.length || justAdded.length || fromFavoriteLabels.length
                || recentlyViewed.length || spotlight || forgottenAttic.length
                || forgottenFavorites.length || onThisDay.length || topLabels.length;
    if (!hasAny) {
      titleDashboardEl.innerHTML = '<div class="dashboard-empty">No titles yet — sync a volume to get started.</div>';
      return;
    }

    titleDashboardEl.innerHTML = '';

    // 0. Top info panel: Spotlight (left) + Top Labels/Library/On This Day (right).
    if (spotlight || topLabels.length > 0 || libraryStats || onThisDay.length > 0) {
      titleDashboardEl.appendChild(renderTopInfoPanel(spotlight, topLabels, libraryStats, onThisDay));
    }

    // 0b. Second panel: Recently Viewed (left) + Just Added (right).
    if (recentlyViewed.length > 0 || justAdded.length > 0) {
      const rvSection = recentlyViewed.length > 0
        ? renderDashboardSection({
            title: 'Recently Viewed',
            body: (() => {
              const strip = renderDashboardStrip(recentlyViewed, { id: 'dash-recently-viewed', cardFactory: makeCompactTitleCard });
              strip.classList.add('dashboard-card-grid-compact');
              return strip;
            })(),
          })
        : null;
      const jaSection = justAdded.length > 0
        ? renderDashboardSection({
            title: 'Just Added',
            body: renderDashboardStrip(justAdded, { id: 'dash-just-added', cardFactory: makeTitleCardWithAging }),
          })
        : null;
      titleDashboardEl.appendChild(renderSideBySidePanel('dashboard-panel-2', rvSection, jaSection));
    }

    // 1. On Deck hero strip.
    if (onDeck.length > 0) {
      titleDashboardEl.appendChild(renderDashboardSection({
        title: 'Bookmarked Selections',
        accent: true,
        bordered: true,
        body: renderDashboardStrip(onDeck, { id: 'dash-on-deck', cardFactory: makeCompactTitleCard }),
      }));
    }

    // 2. (Just Added now in second panel.)

    // 3. From Favorite Labels.
    if (fromFavoriteLabels.length > 0) {
      titleDashboardEl.appendChild(renderDashboardSection({
        title: 'From Favorite Labels',
        bordered: true,
        body: renderDashboardStrip(fromFavoriteLabels, { id: 'dash-fav-labels', cardFactory: makeTitleCardWithAging }),
      }));
    }

    // 4. (Recently Viewed now in second panel.)

    // 5. (Spotlight now in top panel.)

    // 6. Forgotten Attic.
    if (forgottenAttic.length > 0) {
      titleDashboardEl.appendChild(renderDashboardSection({
        title: 'Forgotten Attic',
        bordered: true,
        body: renderDashboardStrip(forgottenAttic, { id: 'dash-forgotten-attic', cardFactory: makeTitleCard }),
      }));
    }

    // 7. Forgotten Favorites.
    if (forgottenFavorites.length > 0) {
      titleDashboardEl.appendChild(renderDashboardSection({
        title: 'Forgotten Favorites',
        body: renderDashboardStrip(forgottenFavorites, { id: 'dash-forgotten-favs', cardFactory: makeTitleCard }),
      }));
    }

    // 8. (On This Day now in top panel right column.)

    // 9. (Top Labels + Library Stats now in top panel.)
  } catch (err) {
    titleDashboardEl.innerHTML = '<div class="dashboard-empty">Error loading dashboard.</div>';
    console.error(err);
  }
}

// ── Browse mode selection ─────────────────────────────────────────────────
export function selectTitleBrowseMode(modeKey) {
  pushNav({ view: 'titles-browse', mode: modeKey }, 'browse/' + modeKey);
  // Reset browse filters when entering a different filterable mode, or leaving filterable modes entirely
  if (modeKey !== state.mode) {
    resetBrowseFilters();
  }
  state.mode = modeKey;
  updateTitleLandingSelection();
  updateTitleBreadcrumb();
  titlesBrowseBtn.classList.add('active');
  showView('titles-browse');
  if (modeKey === 'dashboard') {
    showView('titles-browse');
    document.getElementById('titles-browse-grid').style.display = 'none';
    hideStudioGroupRow();
    hideTagsPanel();
    hideBrowseFilterBar();
    titleDashboardEl.style.display = 'block';
    renderTitleDashboard();
    return;
  }
  titleSpotlightRotator.stop();
  titleDashboardEl.style.display = 'none';
  if (modeKey === 'studio') {
    document.getElementById('titles-browse-grid').style.display = 'none';
    titleStudioLabelsEl.style.display = 'none';
    hideTagsPanel();
    hideBrowseFilterBar();
    ensureStudioGroups().then(groups => {
      renderStudioGroupRow(groups);
      showStudioGroupRow();
      if (groups.length > 0 && !state.selectedStudioSlug) {
        selectStudioGroup(groups[0].slug);
      }
    });
    return;
  }
  if (modeKey === 'library') {
    hideStudioGroupRow();
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
  hideStudioGroupRow();
  hideTagsPanel();
  if (FILTERABLE_MODES.has(modeKey)) {
    showBrowseFilterBar(); // async, fire-and-forget
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
  resetBrowseFilters();
  hideStudioGroupRow();
  hideTagsPanel();
  hideBrowseFilterBar();
  updateTitleLandingSelection();
  showView('titles-browse');
  selectTitleBrowseMode('dashboard');
}

titlesBrowseBtn.addEventListener('click', showTitlesBrowse);
titleDashboardBtn.addEventListener('click', () => selectTitleBrowseMode('dashboard'));

titleFavoritesBtn.addEventListener('click', () => selectTitleBrowseMode('favorites'));
titleBookmarksBtn.addEventListener('click', () => selectTitleBrowseMode('bookmarks'));
titleStudioBtn.addEventListener('click',    () => selectTitleBrowseMode('studio'));
collectionsBtn.addEventListener('click',    () => selectTitleBrowseMode('collections'));

// ── Unsorted / Archives pool browse ───────────────────────────────────────
async function ensureQueuesVolumes() {
  if (state.queuesVolumeData) return state.queuesVolumeData;
  const res = await fetch('/api/queues/volumes');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  state.queuesVolumeData = await res.json();
  return state.queuesVolumeData;
}

export async function enterUnsortedMode() {
  if (!state.poolVolumeId) {
    try {
      const data = await ensureQueuesVolumes();
      if (!data.sortPool) { console.warn('No sort pool available'); return; }
      state.poolVolumeId = data.sortPool.id;
      state.poolSmbPath  = data.sortPool.smbPath || null;
    } catch (err) { console.error('Failed to load pool info', err); return; }
  }
  selectTitleBrowseMode('unsorted');
}

export async function enterArchiveMode() {
  if (!state.archivePoolVolumeId) {
    try {
      const data = await ensureQueuesVolumes();
      if (!data.classicPool) { console.warn('No classic pool available'); return; }
      state.archivePoolVolumeId = data.classicPool.id;
      state.archivePoolSmbPath  = data.classicPool.smbPath || null;
    } catch (err) { console.error('Failed to load archive pool info', err); return; }
  }
  selectTitleBrowseMode('archive-pool');
}

titleUnsortedBtn.addEventListener('click', () => enterUnsortedMode());
titleArchivesBtn.addEventListener('click', () => enterArchiveMode());

// ── Library browse ────────────────────────────────────────────────────────
async function ensureTagsCatalog() {
  if (state.tagsCatalog) return state.tagsCatalog;
  const res = await fetch('/api/tags');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  state.tagsCatalog = await res.json();
  return state.tagsCatalog;
}

async function ensureTagPanelData() {
  // Fetch catalog, counts, and enrichment filters in parallel if not cached.
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

const LIBRARY_DEBOUNCE_MS = 350;
function scheduleLibraryQuery() {
  if (state.tagsDebounceTimer) clearTimeout(state.tagsDebounceTimer);
  state.tagsDebounceTimer = setTimeout(() => {
    state.tagsDebounceTimer = null;
    updateTitleBreadcrumb();
    runTitleBrowseQuery();
  }, LIBRARY_DEBOUNCE_MS);
}

function hideTagsPanel() {
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
  closeLibraryAutocomplete();
}

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

function showChipsBar() {
  const bar = document.getElementById('library-tags-bar');
  if (!bar) return;
  if (state.chipsHideTimer) { clearTimeout(state.chipsHideTimer); state.chipsHideTimer = null; }
  bar.classList.remove('rolling-up');
  bar.style.display = '';
}

function scheduleChipsBarHide() {
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

function renderTagChips() {
  const container = document.getElementById('library-tag-chips');
  if (!container) return;
  const hasAny = state.activeTags.size > 0 || state.activeEnrichmentTagIds.size > 0;
  if (!hasAny) {
    container.innerHTML = '';
    scheduleChipsBarHide();
    return;
  }
  showChipsBar();
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
      renderTagChips();
      scheduleLibraryQuery();
    });
  });
}

function toggleTagsSection() {
  const section = document.getElementById('library-tags-section');
  const btn = document.getElementById('library-tags-toggle-btn');
  if (!section) return;
  if (state.tagsBarOpen) {
    state.tagsBarOpen = false;
    section.style.display = 'none';
    btn?.classList.remove('open');
    if (state.tagsPendingChanged) {
      state.tagsPendingChanged = false;
      scheduleLibraryQuery();
    }
  } else {
    state.tagsBarOpen = true;
    section.style.display = '';
    btn?.classList.add('open');
  }
}

// ── Autocomplete dropdown ─────────────────────────────────────────────────

function closeLibraryAutocomplete() {
  state.libraryAutoVisible = false;
  if (state.libraryAutoTimer) { clearTimeout(state.libraryAutoTimer); state.libraryAutoTimer = null; }
  const drop = document.getElementById('library-code-dropdown');
  if (drop) drop.innerHTML = '';
  const wrap = document.getElementById('library-code-wrap');
  if (wrap) wrap.classList.remove('autocomplete-open');
}

function openLibraryAutocomplete(items, inputEl) {
  const drop = document.getElementById('library-code-dropdown');
  if (!drop || items.length === 0) { closeLibraryAutocomplete(); return; }
  state.libraryAutoVisible = true;
  drop.innerHTML = '';
  items.forEach((code, i) => {
    const item = document.createElement('div');
    item.className = 'library-autocomplete-item';
    item.textContent = code;
    item.dataset.idx = i;
    item.addEventListener('mousedown', e => {
      e.preventDefault(); // don't blur the input
      selectAutocompleteItem(code, inputEl);
    });
    drop.appendChild(item);
  });
  document.getElementById('library-code-wrap')?.classList.add('autocomplete-open');
}

function selectAutocompleteItem(code, inputEl) {
  state.libraryCode = code;
  if (inputEl) { inputEl.value = code; inputEl.focus(); }
  closeLibraryAutocomplete();
  scheduleLibraryQuery();
}

function moveAutocompleteSelection(dir) {
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

async function fetchAutocomplete(prefix) {
  if (!prefix || prefix.length < 1) { closeLibraryAutocomplete(); return; }
  try {
    const res = await fetch(`/api/labels/autocomplete?prefix=${encodeURIComponent(prefix)}`);
    if (!res.ok) return;
    const codes = await res.json();
    const inputEl = document.getElementById('library-code-input');
    if (state.mode === 'library' && inputEl) openLibraryAutocomplete(codes, inputEl);
  } catch { /* ignore */ }
}

// ── Library filter panel render ───────────────────────────────────────────

async function renderLibraryFilterPanel() {
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
  let totalTitles = 0;
  let enrichmentDefs = [];
  try {
    const { catalog, countData, healthData } = await ensureTagPanelData();
    groups = catalog || [];
    tagCounts = countData?.counts || {};
    totalTitles = countData?.totalTitles || 0;
    // Filter enrichment tags: surface=true, 1% <= libraryPct <= 50%
    enrichmentDefs = (healthData?.definitions || []).filter(d =>
      d.surface && d.libraryPct >= 0.01 && d.libraryPct <= 0.50
    );
  } catch { /* ignore */ }

  // Build curated tag groups, omitting tags with zero count
  function curatedTagHtml(t) {
    const count = tagCounts[t.name] || 0;
    if (count === 0) return '';
    const active = state.activeTags.has(t.name) ? ' active' : '';
    const countBadge = `<span class="tag-toggle-count">${count}</span>`;
    return `<button type="button" class="tag-toggle${active}" data-tag="${esc(t.name)}" title="${esc(t.description || '')}">${esc(t.name)}${countBadge}</button>`;
  }

  function enrichmentTagHtml(d) {
    const active = state.activeEnrichmentTagIds.has(d.id) ? ' active' : '';
    const pct = (d.libraryPct * 100).toFixed(0);
    const label = d.curatedAlias ? esc(d.curatedAlias) : esc(d.name);
    const title = d.curatedAlias ? `${d.name} → ${d.curatedAlias}` : d.name;
    const countBadge = `<span class="tag-toggle-count">${d.titleCount}</span>`;
    return `<button type="button" class="tag-toggle tag-toggle--enrichment${active}" data-enrichment-id="${d.id}" title="${esc(title)}">${label}${countBadge}</button>`;
  }

  const curatedGroupsHtml = groups.map(g => {
    const tagsHtml = g.tags.map(curatedTagHtml).join('');
    if (!tagsHtml) return ''; // skip empty group
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

  // Build the panel HTML
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

  // Wire code input
  const codeInput = document.getElementById('library-code-input');
  if (codeInput) {
    codeInput.addEventListener('input', () => {
      state.libraryCode = codeInput.value;
      // Determine if we should show autocomplete:
      // only when there's no sequence part yet (pure label prefix)
      const upper = state.libraryCode.trim().toUpperCase().replace(/\s+/g, '');
      const isLabelPrefixOnly = upper.length > 0 && /^[A-Z][A-Z0-9]*-?$/.test(upper);
      if (isLabelPrefixOnly) {
        if (state.libraryAutoTimer) clearTimeout(state.libraryAutoTimer);
        state.libraryAutoTimer = setTimeout(() => {
          state.libraryAutoTimer = null;
          fetchAutocomplete(upper.replace(/-+$/, ''));
        }, 200);
      } else {
        closeLibraryAutocomplete();
      }
      scheduleLibraryQuery();
    });

    codeInput.addEventListener('keydown', e => {
      if (e.key === 'ArrowDown') { e.preventDefault(); moveAutocompleteSelection(1); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); moveAutocompleteSelection(-1); }
      else if (e.key === 'Enter') {
        if (state.libraryAutoVisible) {
          const focused = document.querySelector('#library-code-dropdown .library-autocomplete-item.focused');
          if (focused) { e.preventDefault(); selectAutocompleteItem(focused.textContent, codeInput); return; }
        }
        closeLibraryAutocomplete();
      }
      else if (e.key === 'Escape') { closeLibraryAutocomplete(); }
    });

    codeInput.addEventListener('blur', () => {
      // Small delay so mousedown on dropdown item fires first
      setTimeout(closeLibraryAutocomplete, 150);
    });
  }

  // Wire company select
  const compSel = document.getElementById('library-company-select');
  if (compSel) {
    compSel.addEventListener('change', () => {
      state.libraryCompany = compSel.value || null;
      scheduleLibraryQuery();
    });
  }

  // Wire sort select
  const sortSel = document.getElementById('library-sort-select');
  if (sortSel) {
    sortSel.addEventListener('change', () => {
      state.librarySort = sortSel.value;
      scheduleLibraryQuery();
    });
  }

  // Wire order toggle
  const orderBtn = document.getElementById('library-order-btn');
  if (orderBtn) {
    orderBtn.addEventListener('click', () => {
      state.libraryOrder = state.libraryOrder === 'desc' ? 'asc' : 'desc';
      orderBtn.textContent = state.libraryOrder === 'asc' ? 'A–Z' : 'Z–A';
      scheduleLibraryQuery();
    });
  }

  // Wire cols slider
  wireColsSlider('title-cols-slider', 'title-cols-label', applyTitleGridCols);

  // Wire tags toggle button
  document.getElementById('library-tags-toggle-btn')?.addEventListener('click', toggleTagsSection);

  // Render any already-active tags as chips
  renderTagChips();

  // Wire curated tag toggles — deferred execution: accumulate changes, run query on panel close
  titleTagsPanel.querySelectorAll('.tag-toggle[data-tag]').forEach(btn => {
    btn.addEventListener('click', () => {
      const tag = btn.dataset.tag;
      if (state.activeTags.has(tag)) { state.activeTags.delete(tag); btn.classList.remove('active'); }
      else                           { state.activeTags.add(tag);    btn.classList.add('active'); }
      state.tagsPendingChanged = true;
      renderTagChips();
    });
  });

  // Wire enrichment tag toggles
  titleTagsPanel.querySelectorAll('.tag-toggle[data-enrichment-id]').forEach(btn => {
    btn.addEventListener('click', () => {
      const id = parseInt(btn.dataset.enrichmentId, 10);
      if (state.activeEnrichmentTagIds.has(id)) { state.activeEnrichmentTagIds.delete(id); btn.classList.remove('active'); }
      else                                       { state.activeEnrichmentTagIds.add(id);    btn.classList.add('active'); }
      state.tagsPendingChanged = true;
      renderTagChips();
    });
  });
}

// ── Browse-mode filter bar (Collections / Unsorted / Archives) ────────────

function resetBrowseFilters() { state.resetBrowse(); }

function hideBrowseFilterBar() {
  const bar   = document.getElementById('title-browse-filter-bar');
  const panel = document.getElementById('title-browse-tags-panel');
  if (bar)   { bar.innerHTML = '';   bar.style.display   = 'none'; }
  if (panel) { panel.innerHTML = ''; panel.style.display = 'none'; }
}

async function showBrowseFilterBar() {
  if (!state.allCompanies) {
    try {
      const res = await fetch('/api/companies');
      state.allCompanies = res.ok ? await res.json() : [];
    } catch { state.allCompanies = []; }
  }

  // Guard: mode may have changed while companies were loading
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
  updateBrowseTagsBtn();
  wireColsSlider('title-cols-slider', 'title-cols-label', applyTitleGridCols);

  sel.addEventListener('change', e => {
    state.browseCompanyFilter = e.target.value || null;
    updateCompanyMarquee(document.getElementById('browse-company-marquee'), state.browseCompanyFilter);
    scheduleBrowseFilteredQuery();
  });
  document.getElementById('browse-tags-btn').addEventListener('click', toggleBrowseTagsPanel);
}

function updateBrowseTagsBtn() {
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

function scheduleBrowseFilteredQuery() {
  updateBrowseTagsBtn();
  if (state.browseFilterTimer) clearTimeout(state.browseFilterTimer);
  state.browseFilterTimer = setTimeout(() => {
    state.browseFilterTimer = null;
    document.getElementById('sentinel')?.remove();
    allTitlesGrid.reset();
    ensureSentinel();
    allTitlesGrid.loadMore();
  }, BROWSE_FILTER_DEBOUNCE_MS);
}

async function toggleBrowseTagsPanel() {
  const panel = document.getElementById('title-browse-tags-panel');
  if (!panel) return;

  if (panel.style.display !== 'none') {
    panel.style.display = 'none';
    return;
  }

  // Load tags if not yet cached for this mode
  if (!state.browseCatalogTags || state.browseTagsForMode !== state.mode) {
    panel.innerHTML = '<div class="detail-tags-loading">Loading tags\u2026</div>';
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

  renderBrowseTagsPanel(panel);
  panel.style.display = '';
}

function renderBrowseTagsPanel(panel) {
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
      scheduleBrowseFilteredQuery();
    });
  });
}

titleTagsBtn.addEventListener('click', async () => {
  if (state.mode === 'library') return;
  try {
    selectTitleBrowseMode('library');
    await renderLibraryFilterPanel();
  } catch (err) {
    console.error('Failed to render library panel', err);
  }
});

// ── Studio browser ────────────────────────────────────────────────────────
function renderStudioGroupRow(groups) {
  titleStudioGroupRow.innerHTML = '';
  groups.forEach(g => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'studio-group-btn' + (g.slug === state.selectedStudioSlug ? ' selected' : '');
    btn.textContent = g.name;
    btn.dataset.slug = g.slug;
    btn.addEventListener('click', () => selectStudioGroup(g.slug));
    titleStudioGroupRow.appendChild(btn);
  });
}

async function selectStudioGroup(slug) {
  state.selectedStudioSlug = slug;
  titleStudioGroupRow.querySelectorAll('.studio-group-btn').forEach(btn => {
    btn.classList.toggle('selected', btn.dataset.slug === slug);
  });

  const groups = await ensureStudioGroups();
  const group = groups.find(g => g.slug === slug);
  if (!group) return;

  const allLabels = await ensureTitleLabels();
  const companySet = new Set(group.companies);

  const byCompany = new Map();
  group.companies.forEach(c => byCompany.set(c, []));
  allLabels.forEach(lbl => {
    if (companySet.has(lbl.company)) byCompany.get(lbl.company).push(lbl);
  });

  renderStudioLabels(byCompany);
}

function renderStudioLabels(byCompany) {
  renderTwoColumnStudioPanel(
    titleStudioLabelsEl, 'studio-label-detail', byCompany, selectStudioCompany,
    () => { document.getElementById('titles-browse-grid').style.display = 'none'; }
  );
}

function selectStudioCompany(company, byCompany) {
  titleStudioLabelsEl.querySelectorAll('.studio-label-item').forEach(el => {
    el.classList.toggle('selected', el.dataset.company === company);
  });

  const detailEl = document.getElementById('studio-label-detail');
  if (!detailEl) return;

  const labels = byCompany.get(company) || [];
  const labelCodeSet = new Set(labels.map(l => l.code.toUpperCase()));

  const companyDesc = labels.length > 0 && labels[0].companyDescription ? labels[0].companyDescription : null;
  let html = `<div class="studio-detail-heading">${esc(company)}</div>`;
  if (companyDesc) html += `<div class="studio-detail-company-desc">${esc(companyDesc)}</div>`;

  html += `<div class="studio-detail-section-label">${esc(company)}'s Top 10</div>
           <div class="studio-top-actress-grid" id="studio-top-actresses"><span class="studio-detail-loading">loading…</span></div>`;
  html += `<div class="studio-detail-section-label">Newest Actresses</div>
           <div class="studio-top-actress-grid" id="studio-newest-actresses"><span class="studio-detail-loading">loading…</span></div>`;

  const byLabel = new Map();
  labels.forEach(lbl => {
    const key = lbl.labelName || lbl.code;
    if (!byLabel.has(key)) byLabel.set(key, []);
    byLabel.get(key).push(lbl);
  });
  html += '<div class="studio-detail-section-label" style="margin-top:32px">labels</div>';
  html += '<div class="studio-detail-label-list">';
  byLabel.forEach((codes, labelName) => {
    html += `<div class="studio-detail-label-group">
      <div class="studio-detail-label-name">${esc(labelName)}</div>
      <div class="studio-detail-code-rows">`;
    codes.forEach(lbl => {
      html += `<div class="studio-detail-code-row">
        <span class="studio-detail-code-badge">${esc(lbl.code)}</span>
        ${lbl.description ? `<span class="studio-detail-label-desc">${esc(lbl.description)}</span>` : ''}
      </div>`;
    });
    html += `</div></div>`;
  });
  html += '</div>';

  detailEl.innerHTML = html;

  const labelCodes = labels.map(l => l.code).join(',');

  function renderActressGridSection(containerId, apiUrl) {
    fetch(apiUrl)
      .then(r => r.ok ? r.json() : Promise.reject(r.status))
      .then(ranked => {
        const el = document.getElementById(containerId);
        if (!el) return;
        if (ranked.length === 0) {
          el.innerHTML = '<span class="studio-detail-loading">none in library</span>';
          return;
        }
        const ids = ranked.map(a => a.id).join(',');
        return fetch(`/api/actresses?ids=${encodeURIComponent(ids)}`)
          .then(r => r.ok ? r.json() : Promise.reject(r.status))
          .then(summaries => {
            const el2 = document.getElementById(containerId);
            if (!el2) return;
            el2.innerHTML = '';
            summaries.forEach(a => {
              const allCovers = a.coverUrls || [];
              const filtered = allCovers.filter(url => {
                const seg = url.split('/')[2];
                return seg && labelCodeSet.has(seg.toUpperCase());
              });
              const card = makeActressCard({ ...a, coverUrls: filtered.length > 0 ? filtered : allCovers });
              card.addEventListener('click', () => openActressDetail(a.id));
              el2.appendChild(card);
            });
          });
      })
      .catch(() => {
        const el = document.getElementById(containerId);
        if (el) el.innerHTML = '<span class="studio-detail-loading">failed to load</span>';
      });
  }

  renderActressGridSection('studio-top-actresses',    `/api/titles/top-actresses?labels=${encodeURIComponent(labelCodes)}&limit=10`);
  renderActressGridSection('studio-newest-actresses', `/api/titles/newest-actresses?labels=${encodeURIComponent(labelCodes)}&limit=10`);
}

function showStudioGroupRow() {
  titleStudioDivider.style.display = '';
  titleStudioGroupRow.style.display = '';
}

function hideStudioGroupRow() {
  titleStudioDivider.style.display = 'none';
  titleStudioGroupRow.style.display = 'none';
  titleStudioLabelsEl.style.display = 'none';
  state.selectedStudioSlug = null;
}
