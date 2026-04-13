import { esc } from './utils.js';
import { pushNav } from './nav.js';
import { showView, setActiveGrid, ensureSentinel, updateBreadcrumb, ScrollingGrid, mode } from './grid.js';
import { makeTitleCard, makeCompactTitleCard, agingLabel } from './cards.js';
import { tagBadgeHtml } from './icons.js';
import { ensureStudioGroups, ensureTitleLabels, renderTwoColumnStudioPanel, updateCompanyMarquee } from './studio-data.js';
import { resetActressState, actressesBtn } from './actress-browse.js';
import { MAX_TOTAL } from './config.js';
import { effectiveCols, colsSliderHtml, wireColsSlider } from './grid-cols.js';
import {
  renderDashboardStrip,
  renderDashboardSection,
  renderSideBySidePanel,
  renderStatsTiles,
  createSpotlightRotator,
} from './dashboard-panels.js';

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
export let titleBrowseMode = null;   // null | 'dashboard' | 'favorites' | 'bookmarks' | 'collections' | 'unsorted' | 'archive-pool' | 'tags' | 'studio'

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

export let activeTags = new Set();
let tagsDebounceTimer = null;
let tagsCatalog = null;

// Pool volumes (Unsorted / Archives) — both use the same API endpoint
export let poolVolumeId      = null;
export let poolSmbPath       = null;
export let archivePoolVolumeId = null;
export let archivePoolSmbPath  = null;
let queuesVolumeData = null;

// ── Browse-mode filters (Collections / Unsorted / Archives) ──────────────
const FILTERABLE_MODES = new Set(['collections', 'unsorted', 'archive-pool']);
let browseCompanyFilter = null;
let browseActiveTags    = new Set();
let browseFilterTimer   = null;
let browseCatalogTags   = null;  // lazy-loaded; reset on mode change
let browseTagsForMode   = null;  // which mode browseCatalogTags belongs to
let allCompanies        = null;  // lazy-loaded once, shared across modes
const BROWSE_FILTER_DEBOUNCE_MS = 350;

// Studio
let selectedStudioSlug = null;

// ── Scrolling grid ────────────────────────────────────────────────────────
export const allTitlesGrid = new ScrollingGrid(
  document.getElementById('titles-browse-grid'),
  (o, l) => {
    if (titleBrowseMode === 'favorites')
      return `/api/titles?favorites=true&offset=${o}&limit=${l}`;
    if (titleBrowseMode === 'bookmarks')
      return `/api/titles?bookmarks=true&offset=${o}&limit=${l}`;
    if (titleBrowseMode === 'collections') {
      let url = `/api/collections/titles?offset=${o}&limit=${l}`;
      if (browseCompanyFilter) url += `&company=${encodeURIComponent(browseCompanyFilter)}`;
      if (browseActiveTags.size > 0) url += `&tags=${encodeURIComponent([...browseActiveTags].join(','))}`;
      return url;
    }
    if (titleBrowseMode === 'unsorted') {
      let url = `/api/pool/${encodeURIComponent(poolVolumeId)}/titles?offset=${o}&limit=${l}`;
      if (browseCompanyFilter) url += `&company=${encodeURIComponent(browseCompanyFilter)}`;
      if (browseActiveTags.size > 0) url += `&tags=${encodeURIComponent([...browseActiveTags].join(','))}`;
      return url;
    }
    if (titleBrowseMode === 'archive-pool') {
      let url = `/api/pool/${encodeURIComponent(archivePoolVolumeId)}/titles?offset=${o}&limit=${l}`;
      if (browseCompanyFilter) url += `&company=${encodeURIComponent(browseCompanyFilter)}`;
      if (browseActiveTags.size > 0) url += `&tags=${encodeURIComponent([...browseActiveTags].join(','))}`;
      return url;
    }
    if (titleBrowseMode === 'tags' && activeTags.size > 0)
      return `/api/titles?tags=${encodeURIComponent([...activeTags].join(','))}&offset=${o}&limit=${l}`;
    return `/api/titles?offset=${o}&limit=${l}`;
  },
  t => {
    if (titleBrowseMode === 'unsorted' && poolSmbPath) {
      if (t.location)  t.location  = poolSmbPath + t.location;
      if (t.locations) t.locations = t.locations.map(p => poolSmbPath + p);
    } else if (titleBrowseMode === 'archive-pool' && archivePoolSmbPath) {
      if (t.location)  t.location  = archivePoolSmbPath + t.location;
      if (t.locations) t.locations = t.locations.map(p => archivePoolSmbPath + p);
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
  if (!FILTERABLE_MODES.has(titleBrowseMode)) showColsOnlyFilterBar();
  applyTitleGridCols(effectiveCols());
  allTitlesGrid.reset();
  ensureSentinel();
  allTitlesGrid.loadMore();
}

// ── Landing UI state ──────────────────────────────────────────────────────
function updateTitleLandingSelection() {
  titleDashboardBtn.classList.toggle('selected', titleBrowseMode === 'dashboard');
  titleFavoritesBtn.classList.toggle('selected', titleBrowseMode === 'favorites');
  titleBookmarksBtn.classList.toggle('selected', titleBrowseMode === 'bookmarks');
  titleStudioBtn.classList.toggle('selected',    titleBrowseMode === 'studio');
  collectionsBtn.classList.toggle('selected',    titleBrowseMode === 'collections');
  titleUnsortedBtn.classList.toggle('selected',  titleBrowseMode === 'unsorted');
  titleArchivesBtn.classList.toggle('selected',  titleBrowseMode === 'archive-pool');
  titleTagsBtn.classList.toggle('selected',      titleBrowseMode === 'tags');
}

function updateTitleBreadcrumb() {
  const crumbs = [{ label: 'Titles', action: () => showTitlesBrowse() }];
  if (titleBrowseMode === 'dashboard')     { /* no sub-item — dashboard IS the home */ }
  else if (titleBrowseMode === 'favorites')     crumbs.push({ label: 'Favorites' });
  else if (titleBrowseMode === 'bookmarks')  crumbs.push({ label: 'Bookmarks' });
  else if (titleBrowseMode === 'studio')     crumbs.push({ label: 'Studio' });
  else if (titleBrowseMode === 'collections') crumbs.push({ label: 'Collections' });
  else if (titleBrowseMode === 'unsorted')   crumbs.push({ label: 'Unsorted' });
  else if (titleBrowseMode === 'archive-pool') crumbs.push({ label: 'Archives' });
  else if (titleBrowseMode === 'tags')
    crumbs.push({ label: activeTags.size > 0 ? `Tags (${activeTags.size})` : 'Tags' });
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
      if (topLabels.length > 0) upper.appendChild(renderTopLabelsLeaderboard(topLabels));
      if (libraryStats)         upper.appendChild(renderLibraryStats(libraryStats));
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

function renderTopLabelsLeaderboard(topLabels) {
  const section = document.createElement('section');
  section.className = 'dashboard-section dashboard-top-labels';
  const header = document.createElement('div');
  header.className = 'dashboard-section-title';
  header.textContent = 'Top Labels';
  section.appendChild(header);

  const list = document.createElement('div');
  list.className = 'dashboard-leaderboard';

  const displayed = topLabels.slice(0, 5);
  const maxScore = displayed.reduce((m, l) => Math.max(m, l.score || 0), 0) || 1;
  displayed.forEach((lbl, i) => {
    const row = document.createElement('div');
    row.className = 'leaderboard-row';
    row.innerHTML = `
      <span class="leaderboard-rank">${i + 1}</span>
      <span class="leaderboard-code">${esc(lbl.code)}</span>
      <span class="leaderboard-name">${esc(lbl.labelName || '')}${lbl.company ? `<span class="leaderboard-company"> · ${esc(lbl.company)}</span>` : ''}</span>
      <span class="leaderboard-bar-wrap"><span class="leaderboard-bar" style="width:${Math.round((lbl.score / maxScore) * 100)}%"></span></span>
    `;
    row.addEventListener('click', () => {
      const searchInput = document.getElementById('search-input');
      if (searchInput) {
        searchInput.value = lbl.code + '-';
        searchInput.dispatchEvent(new Event('input'));
        searchInput.focus();
      }
    });
    list.appendChild(row);
  });
  section.appendChild(list);
  return section;
}

function renderLibraryStats(stats) {
  const unseenPct = stats.totalTitles > 0
    ? Math.round((stats.unseen / stats.totalTitles) * 100)
    : 0;
  return renderStatsTiles({
    heading: 'Library',
    tiles: [
      { label: 'Titles',           value: stats.totalTitles.toLocaleString() },
      { label: 'Labels',           value: stats.totalLabels.toLocaleString() },
      { label: 'Unseen',           value: stats.unseen.toLocaleString() },
      { label: 'Unseen %',         value: `${unseenPct}%`, bar: unseenPct },
      { label: 'Added this month', value: stats.addedThisMonth.toLocaleString() },
      { label: 'Added this year',  value: stats.addedThisYear.toLocaleString() },
    ],
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
  if (modeKey !== titleBrowseMode) {
    resetBrowseFilters();
  }
  titleBrowseMode = modeKey;
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
      if (groups.length > 0 && !selectedStudioSlug) {
        selectStudioGroup(groups[0].slug);
      }
    });
    return;
  }
  if (modeKey === 'tags') {
    hideStudioGroupRow();
    titleTagsPanel.style.display = 'grid';
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
  resetActressState();
  if (tagsDebounceTimer) { clearTimeout(tagsDebounceTimer);  tagsDebounceTimer  = null; }
  titleBrowseMode = null;
  activeTags.clear();
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
  if (queuesVolumeData) return queuesVolumeData;
  const res = await fetch('/api/queues/volumes');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  queuesVolumeData = await res.json();
  return queuesVolumeData;
}

export async function enterUnsortedMode() {
  if (!poolVolumeId) {
    try {
      const data = await ensureQueuesVolumes();
      if (!data.sortPool) { console.warn('No sort pool available'); return; }
      poolVolumeId = data.sortPool.id;
      poolSmbPath  = data.sortPool.smbPath || null;
    } catch (err) { console.error('Failed to load pool info', err); return; }
  }
  selectTitleBrowseMode('unsorted');
}

export async function enterArchiveMode() {
  if (!archivePoolVolumeId) {
    try {
      const data = await ensureQueuesVolumes();
      if (!data.classicPool) { console.warn('No classic pool available'); return; }
      archivePoolVolumeId = data.classicPool.id;
      archivePoolSmbPath  = data.classicPool.smbPath || null;
    } catch (err) { console.error('Failed to load archive pool info', err); return; }
  }
  selectTitleBrowseMode('archive-pool');
}

titleUnsortedBtn.addEventListener('click', () => enterUnsortedMode());
titleArchivesBtn.addEventListener('click', () => enterArchiveMode());

// ── Tags browse ───────────────────────────────────────────────────────────
async function ensureTagsCatalog() {
  if (tagsCatalog) return tagsCatalog;
  const res = await fetch('/api/tags');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  tagsCatalog = await res.json();
  return tagsCatalog;
}

function renderTagsPanel(groups) {
  titleTagsPanel.innerHTML = groups.map(g => `
    <div class="tags-group">
      <div class="tags-group-label">${esc(g.label)}</div>
      <div class="tags-row">
        ${g.tags.map(t => `<button type="button" class="tag-toggle${activeTags.has(t.name) ? ' active' : ''}" data-tag="${esc(t.name)}" title="${esc(t.description || '')}">${esc(t.name)}</button>`).join('')}
      </div>
    </div>
  `).join('');
  titleTagsPanel.querySelectorAll('.tag-toggle').forEach(btn => {
    btn.addEventListener('click', () => {
      const tag = btn.dataset.tag;
      if (activeTags.has(tag)) { activeTags.delete(tag); btn.classList.remove('active'); }
      else                     { activeTags.add(tag);    btn.classList.add('active'); }
      scheduleTagsQuery();
    });
  });
}

const TAGS_DEBOUNCE_MS = 350;
function scheduleTagsQuery() {
  if (tagsDebounceTimer) clearTimeout(tagsDebounceTimer);
  tagsDebounceTimer = setTimeout(() => {
    tagsDebounceTimer = null;
    updateTitleBreadcrumb();
    runTitleBrowseQuery();
  }, TAGS_DEBOUNCE_MS);
}

function hideTagsPanel() {
  titleTagsPanel.style.display = 'none';
}

// ── Browse-mode filter bar (Collections / Unsorted / Archives) ────────────

function resetBrowseFilters() {
  browseCompanyFilter = null;
  browseActiveTags    = new Set();
  browseCatalogTags   = null;
  browseTagsForMode   = null;
  if (browseFilterTimer) { clearTimeout(browseFilterTimer); browseFilterTimer = null; }
}

function hideBrowseFilterBar() {
  const bar   = document.getElementById('title-browse-filter-bar');
  const panel = document.getElementById('title-browse-tags-panel');
  if (bar)   { bar.innerHTML = '';   bar.style.display   = 'none'; }
  if (panel) { panel.innerHTML = ''; panel.style.display = 'none'; }
}

async function showBrowseFilterBar() {
  if (!allCompanies) {
    try {
      const res = await fetch('/api/companies');
      allCompanies = res.ok ? await res.json() : [];
    } catch { allCompanies = []; }
  }

  // Guard: mode may have changed while companies were loading
  if (!FILTERABLE_MODES.has(titleBrowseMode)) return;

  const bar = document.getElementById('title-browse-filter-bar');
  if (!bar) return;

  bar.innerHTML = `
    <select class="detail-company-select" id="browse-company-select">
      <option value="">All Companies</option>
      ${allCompanies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('')}
    </select>
    <div class="company-marquee company-marquee-browse" id="browse-company-marquee" style="display:none"><span class="company-marquee-inner"></span></div>
    <button type="button" class="detail-tags-btn" id="browse-tags-btn">
      Tags<span class="detail-tags-count" id="browse-tags-count" style="display:none"></span>
    </button>
    ${colsSliderHtml(effectiveCols(), 'title-cols-control', 'title-cols-slider', 'title-cols-label')}`;
  bar.style.display = '';

  const sel = document.getElementById('browse-company-select');
  if (sel && browseCompanyFilter) {
    sel.value = browseCompanyFilter;
    updateCompanyMarquee(document.getElementById('browse-company-marquee'), browseCompanyFilter);
  }
  updateBrowseTagsBtn();
  wireColsSlider('title-cols-slider', 'title-cols-label', applyTitleGridCols);

  sel.addEventListener('change', e => {
    browseCompanyFilter = e.target.value || null;
    updateCompanyMarquee(document.getElementById('browse-company-marquee'), browseCompanyFilter);
    scheduleBrowseFilteredQuery();
  });
  document.getElementById('browse-tags-btn').addEventListener('click', toggleBrowseTagsPanel);
}

function updateBrowseTagsBtn() {
  const countEl = document.getElementById('browse-tags-count');
  if (!countEl) return;
  if (browseActiveTags.size > 0) {
    countEl.textContent = browseActiveTags.size;
    countEl.style.display = '';
  } else {
    countEl.style.display = 'none';
  }
  const btn = document.getElementById('browse-tags-btn');
  if (btn) btn.classList.toggle('has-active', browseActiveTags.size > 0);
}

function scheduleBrowseFilteredQuery() {
  updateBrowseTagsBtn();
  if (browseFilterTimer) clearTimeout(browseFilterTimer);
  browseFilterTimer = setTimeout(() => {
    browseFilterTimer = null;
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
  if (!browseCatalogTags || browseTagsForMode !== titleBrowseMode) {
    panel.innerHTML = '<div class="detail-tags-loading">Loading tags\u2026</div>';
    panel.style.display = '';
    const tagsUrl = titleBrowseMode === 'collections'
      ? '/api/collections/tags'
      : `/api/pool/${encodeURIComponent(titleBrowseMode === 'unsorted' ? poolVolumeId : archivePoolVolumeId)}/tags`;
    try {
      const res = await fetch(tagsUrl);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      browseCatalogTags = await res.json();
      browseTagsForMode = titleBrowseMode;
    } catch (err) {
      panel.innerHTML = '<div class="detail-tags-loading">Could not load tags</div>';
      return;
    }
  }

  renderBrowseTagsPanel(panel);
  panel.style.display = '';
}

function renderBrowseTagsPanel(panel) {
  const tags = browseCatalogTags || [];
  if (tags.length === 0) {
    panel.innerHTML = '<div class="detail-tags-loading">No tags available</div>';
    return;
  }
  panel.innerHTML = `
    <div class="detail-tags-inner">
      ${tags.map(t => `<button type="button" class="tag-toggle${browseActiveTags.has(t) ? ' active' : ''}" data-tag="${esc(t)}">${esc(t)}</button>`).join('')}
    </div>`;
  panel.querySelectorAll('.tag-toggle').forEach(btn => {
    btn.addEventListener('click', () => {
      const tag = btn.dataset.tag;
      if (browseActiveTags.has(tag)) { browseActiveTags.delete(tag); btn.classList.remove('active'); }
      else                           { browseActiveTags.add(tag);    btn.classList.add('active'); }
      scheduleBrowseFilteredQuery();
    });
  });
}

titleTagsBtn.addEventListener('click', async () => {
  if (titleBrowseMode === 'tags') return;
  try {
    const groups = await ensureTagsCatalog();
    renderTagsPanel(groups);
    selectTitleBrowseMode('tags');
  } catch (err) {
    console.error('Failed to load tags catalog', err);
  }
});

// ── Studio browser ────────────────────────────────────────────────────────
function renderStudioGroupRow(groups) {
  titleStudioGroupRow.innerHTML = '';
  groups.forEach(g => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'studio-group-btn' + (g.slug === selectedStudioSlug ? ' selected' : '');
    btn.textContent = g.name;
    btn.dataset.slug = g.slug;
    btn.addEventListener('click', () => selectStudioGroup(g.slug));
    titleStudioGroupRow.appendChild(btn);
  });
}

async function selectStudioGroup(slug) {
  selectedStudioSlug = slug;
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
              // Import makeActressCard dynamically to avoid circular reference in module init
              import('./cards.js').then(({ makeActressCard }) => {
                const card = makeActressCard({ ...a, coverUrls: filtered.length > 0 ? filtered : allCovers });
                card.addEventListener('click', () => import('./actress-detail.js').then(m => m.openActressDetail(a.id)));
                el2.appendChild(card);
              });
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
  selectedStudioSlug = null;
}
