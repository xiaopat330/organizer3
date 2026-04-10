import { esc } from './utils.js';
import { showView, setActiveGrid, ensureSentinel, updateBreadcrumb, ScrollingGrid, mode } from './grid.js';
import { makeTitleCard, makeCompactTitleCard, agingLabel } from './cards.js';
import { tagBadgeHtml } from './icons.js';
import { ensureStudioGroups, ensureTitleLabels, renderTwoColumnStudioPanel } from './studio-data.js';
import { resetActressState, actressesBtn } from './actress-browse.js';
import { MAX_TOTAL } from './config.js';

// ── DOM refs ──────────────────────────────────────────────────────────────
export const titlesBrowseBtn    = document.getElementById('titles-browse-btn');
const titleLandingEl            = document.getElementById('title-landing');
const titleSearchInput          = document.getElementById('title-search-input');
const titleSearchClearBtn       = document.getElementById('title-search-clear');
const titleDashboardBtn         = document.getElementById('title-dashboard-btn');
const titleDashboardEl          = document.getElementById('title-dashboard');
const titleFavoritesBtn         = document.getElementById('title-favorites-btn');
const titleBookmarksBtn         = document.getElementById('title-bookmarks-btn');
const titleStudioBtn            = document.getElementById('title-studio-btn');
const titleStudioDivider        = document.getElementById('title-studio-divider');
const titleStudioGroupRow       = document.getElementById('title-studio-group-row');
const titleStudioLabelsEl       = document.getElementById('title-studio-labels');
const titleLabelDropdown        = document.getElementById('title-label-dropdown');
export const collectionsBtn     = document.getElementById('title-collections-btn');
export const titleUnsortedBtn   = document.getElementById('title-unsorted-btn');
export const titleArchivesBtn   = document.getElementById('title-archives-btn');
const titleTagsBtn              = document.getElementById('title-tags-btn');
const titleTagsPanel            = document.getElementById('title-tags-panel');

// ── State ─────────────────────────────────────────────────────────────────
const TITLE_SEARCH_DELAY_MS  = 350;
const TITLE_SEARCH_MIN_CHARS = 1;

export let titleBrowseMode = null;   // null | 'dashboard' | 'search' | 'favorites' | 'bookmarks' | 'collections' | 'unsorted' | 'archive-pool' | 'tags' | 'studio'
export let titleSearchTerm = '';
let titleSearchTimer = null;

// Spotlight rotation
const SPOTLIGHT_INTERVAL_MS = 30_000;
let spotlightIntervalId = null;
let spotlightCardContainer = null;   // the .dashboard-top-panel-left div

export let activeTags = new Set();
let tagsDebounceTimer = null;
let tagsCatalog = null;

// Pool volumes (Unsorted / Archives) — both use the same API endpoint
export let poolVolumeId      = null;
export let poolSmbPath       = null;
export let archivePoolVolumeId = null;
export let archivePoolSmbPath  = null;
let queuesVolumeData = null;

// Studio
let selectedStudioSlug = null;

// Label dropdown
let labelDropdownItems = [];
let labelDropdownIndex = -1;

// ── Scrolling grid ────────────────────────────────────────────────────────
export const allTitlesGrid = new ScrollingGrid(
  document.getElementById('titles-browse-grid'),
  (o, l) => {
    if (titleBrowseMode === 'search')
      return `/api/titles?search=${encodeURIComponent(titleSearchTerm)}&offset=${o}&limit=${l}`;
    if (titleBrowseMode === 'favorites')
      return `/api/titles?favorites=true&offset=${o}&limit=${l}`;
    if (titleBrowseMode === 'bookmarks')
      return `/api/titles?bookmarks=true&offset=${o}&limit=${l}`;
    if (titleBrowseMode === 'collections')
      return `/api/collections/titles?offset=${o}&limit=${l}`;
    if (titleBrowseMode === 'unsorted')
      return `/api/pool/${encodeURIComponent(poolVolumeId)}/titles?offset=${o}&limit=${l}`;
    if (titleBrowseMode === 'archive-pool')
      return `/api/pool/${encodeURIComponent(archivePoolVolumeId)}/titles?offset=${o}&limit=${l}`;
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
  else if (titleBrowseMode === 'search')
    crumbs.push({ label: `search: "${titleSearchTerm}"` });
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

function renderDashboardStrip(titles, { id, cardFactory }) {
  const grid = document.createElement('div');
  grid.className = 'dashboard-card-grid';
  if (id) grid.id = id;
  titles.forEach(t => grid.appendChild(cardFactory(t)));
  return grid;
}

function renderDashboardSection({ title, accent = false, badge = null, body, bordered = false }) {
  const section = document.createElement('section');
  section.className = 'dashboard-section'
    + (accent   ? ' dashboard-section-accent'   : '')
    + (bordered ? ' dashboard-section-bordered' : '');
  const header = document.createElement('div');
  header.className = 'dashboard-section-title';
  header.textContent = title;
  if (badge) {
    const b = document.createElement('span');
    b.className = 'dashboard-section-badge';
    b.textContent = badge;
    header.appendChild(b);
  }
  section.appendChild(header);
  section.appendChild(body);
  return section;
}

function renderSideBySidePanel(panelClass, leftEl, rightEl) {
  const panel = document.createElement('div');
  panel.className = `dashboard-side-panel ${panelClass}`;
  if (leftEl) {
    const left = document.createElement('div');
    left.className = 'dashboard-side-panel-cell';
    left.appendChild(leftEl);
    panel.appendChild(left);
  }
  if (rightEl) {
    const right = document.createElement('div');
    right.className = 'dashboard-side-panel-cell';
    right.appendChild(rightEl);
    panel.appendChild(right);
  }
  return panel;
}

function stopSpotlightRotation() {
  if (spotlightIntervalId !== null) {
    clearInterval(spotlightIntervalId);
    spotlightIntervalId = null;
  }
  spotlightCardContainer = null;
}

async function rotateSpotlight() {
  if (!spotlightCardContainer) return;
  const currentCard = spotlightCardContainer.querySelector('.card');
  const currentCode = currentCard ? currentCard.dataset.code : null;
  const url = '/api/titles/spotlight' + (currentCode ? `?exclude=${encodeURIComponent(currentCode)}` : '');
  try {
    const res = await fetch(url);
    if (res.status === 204 || !res.ok) return;   // no candidates — keep current
    const t = await res.json();
    const newCard = makeTitleCard(t);
    newCard.classList.add('card-spotlight', 'spotlight-enter');
    if (currentCard) {
      currentCard.classList.add('spotlight-exit');
      currentCard.addEventListener('animationend', () => currentCard.remove(), { once: true });
    }
    spotlightCardContainer.appendChild(newCard);
    // Trigger reflow so the animation plays
    void newCard.offsetWidth;
    newCard.classList.remove('spotlight-enter');
  } catch (_) { /* network error — silently skip */ }
}

function startSpotlightRotation(container) {
  stopSpotlightRotation();
  spotlightCardContainer = container;
  spotlightIntervalId = setInterval(rotateSpotlight, SPOTLIGHT_INTERVAL_MS);
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
    setTimeout(() => startSpotlightRotation(left), SPOTLIGHT_INTERVAL_MS);
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

  const maxScore = topLabels.reduce((m, l) => Math.max(m, l.score || 0), 0) || 1;
  topLabels.forEach((lbl, i) => {
    const row = document.createElement('div');
    row.className = 'leaderboard-row';
    row.innerHTML = `
      <span class="leaderboard-rank">${i + 1}</span>
      <span class="leaderboard-code">${esc(lbl.code)}</span>
      <span class="leaderboard-name">${esc(lbl.labelName || '')}${lbl.company ? `<span class="leaderboard-company"> · ${esc(lbl.company)}</span>` : ''}</span>
      <span class="leaderboard-bar-wrap"><span class="leaderboard-bar" style="width:${Math.round((lbl.score / maxScore) * 100)}%"></span></span>
    `;
    row.addEventListener('click', () => {
      titleSearchInput.value = lbl.code + '-';
      scheduleTitleSearch(0);
    });
    list.appendChild(row);
  });
  section.appendChild(list);
  return section;
}

function renderLibraryStats(stats) {
  const section = document.createElement('section');
  section.className = 'dashboard-section dashboard-library-stats';
  const header = document.createElement('div');
  header.className = 'dashboard-section-title';
  header.textContent = 'Library';
  section.appendChild(header);

  const unseenPct = stats.totalTitles > 0
    ? Math.round((stats.unseen / stats.totalTitles) * 100)
    : 0;

  const tiles = document.createElement('div');
  tiles.className = 'dashboard-stats-grid';
  const tileData = [
    { label: 'Titles',          value: stats.totalTitles.toLocaleString() },
    { label: 'Labels',          value: stats.totalLabels.toLocaleString() },
    { label: 'Unseen',          value: stats.unseen.toLocaleString() },
    { label: 'Unseen %',        value: `${unseenPct}%`, bar: unseenPct },
    { label: 'Added this month', value: stats.addedThisMonth.toLocaleString() },
    { label: 'Added this year',  value: stats.addedThisYear.toLocaleString() },
  ];
  tileData.forEach(t => {
    const tile = document.createElement('div');
    tile.className = 'stats-tile';
    let html = `<div class="stats-tile-value">${esc(String(t.value))}</div><div class="stats-tile-label">${esc(t.label)}</div>`;
    if (t.bar != null) {
      html += `<div class="stats-tile-bar-wrap"><div class="stats-tile-bar" style="width:${t.bar}%"></div></div>`;
    }
    tile.innerHTML = html;
    tiles.appendChild(tile);
  });
  section.appendChild(tiles);
  return section;
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
  titleBrowseMode = modeKey;
  if (modeKey !== 'search') {
    if (titleSearchTimer) { clearTimeout(titleSearchTimer); titleSearchTimer = null; }
    titleSearchTerm = '';
    if (titleSearchInput.value !== '') titleSearchInput.value = '';
    closeLabelDropdown();
  }
  updateTitleLandingSelection();
  updateTitleBreadcrumb();
  titlesBrowseBtn.classList.add('active');
  if (modeKey === 'dashboard') {
    showView('titles-browse');
    document.getElementById('titles-browse-grid').style.display = 'none';
    hideStudioGroupRow();
    hideTagsPanel();
    titleDashboardEl.style.display = 'block';
    renderTitleDashboard();
    return;
  }
  stopSpotlightRotation();
  titleDashboardEl.style.display = 'none';
  if (modeKey === 'studio') {
    document.getElementById('titles-browse-grid').style.display = 'none';
    titleStudioLabelsEl.style.display = 'none';
    hideTagsPanel();
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
  runTitleBrowseQuery();
}

// ── showTitlesBrowse ──────────────────────────────────────────────────────
export function showTitlesBrowse() {
  titlesBrowseBtn.classList.add('active');
  actressesBtn.classList.remove('active');
  collectionsBtn.classList.remove('active');
  resetActressState();
  if (titleSearchTimer)   { clearTimeout(titleSearchTimer);   titleSearchTimer   = null; }
  if (tagsDebounceTimer) { clearTimeout(tagsDebounceTimer);  tagsDebounceTimer  = null; }
  titleBrowseMode = null;
  titleSearchTerm = '';
  activeTags.clear();
  titleSearchInput.value = '';
  closeLabelDropdown();
  hideStudioGroupRow();
  hideTagsPanel();
  updateTitleLandingSelection();
  showView('titles-browse');
  requestAnimationFrame(() => {
    const header = document.querySelector('header');
    if (header) titleLandingEl.style.top = header.offsetHeight + 'px';
  });
  selectTitleBrowseMode('dashboard');
  ensureTitleLabels(); // preload in background for tab-completion
}

titlesBrowseBtn.addEventListener('click', showTitlesBrowse);
titleDashboardBtn.addEventListener('click', () => selectTitleBrowseMode('dashboard'));

// ── Label dropdown ────────────────────────────────────────────────────────
function extractAlphaPrefix(raw) {
  if (!raw) return '';
  const m = raw.trim().toUpperCase().match(/^([A-Z][A-Z0-9]*)/);
  return m ? m[1] : '';
}

function closeLabelDropdown() {
  titleLabelDropdown.style.display = 'none';
  titleLabelDropdown.innerHTML = '';
  labelDropdownItems = [];
  labelDropdownIndex = -1;
}

function renderLabelDropdown(matches, prefix) {
  titleLabelDropdown.innerHTML = '';
  labelDropdownItems = matches;
  labelDropdownIndex = matches.length > 0 ? 0 : -1;

  if (matches.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'title-label-dropdown-empty';
    empty.textContent = `no labels match "${prefix}"`;
    titleLabelDropdown.appendChild(empty);
    titleLabelDropdown.style.display = 'block';
    return;
  }

  matches.forEach((lbl, i) => {
    const item = document.createElement('div');
    item.className = 'title-label-dropdown-item' + (i === 0 ? ' highlighted' : '');
    item.dataset.index = String(i);
    const metaParts = [];
    if (lbl.labelName) metaParts.push(lbl.labelName);
    if (lbl.company)   metaParts.push(lbl.company);
    const metaHtml = metaParts.length
      ? `<span class="title-label-dropdown-meta">${esc(metaParts.join(' · '))}</span>`
      : '';
    item.innerHTML = `<span class="title-label-dropdown-code">${esc(lbl.code)}</span>${metaHtml}`;
    item.addEventListener('mouseenter', () => highlightLabelDropdownItem(i));
    item.addEventListener('mousedown', e => {
      e.preventDefault();
      selectLabelDropdownItem(i);
    });
    titleLabelDropdown.appendChild(item);
  });
  titleLabelDropdown.style.display = 'block';
}

function highlightLabelDropdownItem(i) {
  const nodes = titleLabelDropdown.querySelectorAll('.title-label-dropdown-item');
  nodes.forEach((n, idx) => n.classList.toggle('highlighted', idx === i));
  labelDropdownIndex = i;
  const n = nodes[i];
  if (n) n.scrollIntoView({ block: 'nearest' });
}

function selectLabelDropdownItem(i) {
  const lbl = labelDropdownItems[i];
  if (!lbl) return;
  titleSearchInput.value = lbl.code + '-';
  closeLabelDropdown();
  titleSearchInput.focus();
  const v = titleSearchInput.value;
  titleSearchInput.setSelectionRange(v.length, v.length);
  scheduleTitleSearch(0);
}

async function openLabelDropdown() {
  const prefix = extractAlphaPrefix(titleSearchInput.value);
  if (!prefix) { closeLabelDropdown(); return; }
  const all = await ensureTitleLabels();
  const matches = all.filter(lbl => lbl.code && lbl.code.startsWith(prefix)).slice(0, 50);
  renderLabelDropdown(matches, prefix);
}

// ── Search ────────────────────────────────────────────────────────────────
function scheduleTitleSearch(delayOverride) {
  if (titleSearchTimer) { clearTimeout(titleSearchTimer); titleSearchTimer = null; }
  const raw = titleSearchInput.value.trim();
  if (raw.length < TITLE_SEARCH_MIN_CHARS) {
    if (titleBrowseMode === 'search') {
      titleBrowseMode = null;
      updateTitleLandingSelection();
      updateTitleBreadcrumb();
      runTitleBrowseQuery();
    }
    return;
  }
  const delay = delayOverride != null ? delayOverride : TITLE_SEARCH_DELAY_MS;
  titleSearchTimer = setTimeout(() => {
    titleSearchTimer = null;
    titleSearchTerm = raw;
    titleBrowseMode = 'search';
    hideTagsPanel();
    updateTitleLandingSelection();
    updateTitleBreadcrumb();
    runTitleBrowseQuery();
  }, delay);
}

titleSearchInput.addEventListener('input', () => {
  closeLabelDropdown();
  scheduleTitleSearch();
});

titleSearchInput.addEventListener('keydown', e => {
  const dropdownOpen = titleLabelDropdown.style.display !== 'none' && labelDropdownItems.length > 0;

  if (e.key === 'Tab' && !e.shiftKey) {
    e.preventDefault();
    if (dropdownOpen) {
      selectLabelDropdownItem(labelDropdownIndex >= 0 ? labelDropdownIndex : 0);
    } else if (extractAlphaPrefix(titleSearchInput.value)) {
      openLabelDropdown();
    }
    return;
  }
  if (e.key === 'Escape') {
    if (dropdownOpen) { e.preventDefault(); closeLabelDropdown(); }
    return;
  }
  if (dropdownOpen && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
    e.preventDefault();
    const delta = e.key === 'ArrowDown' ? 1 : -1;
    const next = (labelDropdownIndex + delta + labelDropdownItems.length) % labelDropdownItems.length;
    highlightLabelDropdownItem(next);
    return;
  }
  if (e.key === 'Enter') {
    e.preventDefault();
    if (dropdownOpen) { selectLabelDropdownItem(labelDropdownIndex >= 0 ? labelDropdownIndex : 0); return; }
    if (titleSearchTimer) { clearTimeout(titleSearchTimer); titleSearchTimer = null; }
    const raw = titleSearchInput.value.trim();
    if (raw.length < TITLE_SEARCH_MIN_CHARS) return;
    titleSearchTerm = raw;
    titleBrowseMode = 'search';
    updateTitleLandingSelection();
    updateTitleBreadcrumb();
    titlesBrowseBtn.classList.add('active');
    runTitleBrowseQuery();
  }
});

let titleBlurTimer = null;
titleSearchInput.addEventListener('blur', () => {
  titleBlurTimer = setTimeout(() => {
    titleBlurTimer = null;
    closeLabelDropdown();
    if (mode !== 'titles-browse') return;
    if (titleSearchInput.value.trim() === '') selectTitleBrowseMode('dashboard');
  }, 150);
});
titleSearchInput.addEventListener('focus', () => {
  if (titleBlurTimer) { clearTimeout(titleBlurTimer); titleBlurTimer = null; }
});

titleSearchClearBtn.addEventListener('click', () => {
  if (titleSearchTimer) { clearTimeout(titleSearchTimer); titleSearchTimer = null; }
  titleSearchInput.value = '';
  titleSearchTerm = '';
  closeLabelDropdown();
  if (titleBrowseMode === 'search') {
    titleBrowseMode = null;
    updateTitleLandingSelection();
    updateTitleBreadcrumb();
    runTitleBrowseQuery();
  }
  titleSearchInput.focus();
});

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

titleUnsortedBtn.addEventListener('click', async () => {
  if (!poolVolumeId) {
    try {
      const data = await ensureQueuesVolumes();
      if (!data.sortPool) { console.warn('No sort pool available'); return; }
      poolVolumeId = data.sortPool.id;
      poolSmbPath  = data.sortPool.smbPath || null;
    } catch (err) { console.error('Failed to load pool info', err); return; }
  }
  selectTitleBrowseMode('unsorted');
});

titleArchivesBtn.addEventListener('click', async () => {
  if (!archivePoolVolumeId) {
    try {
      const data = await ensureQueuesVolumes();
      if (!data.classicPool) { console.warn('No classic pool available'); return; }
      archivePoolVolumeId = data.classicPool.id;
      archivePoolSmbPath  = data.classicPool.smbPath || null;
    } catch (err) { console.error('Failed to load archive pool info', err); return; }
  }
  selectTitleBrowseMode('archive-pool');
});

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
