import { esc } from './utils.js';
import { showView, setActiveGrid, ensureSentinel, updateBreadcrumb, mode } from './grid.js';
import { pushNav } from './nav.js';
import { makeActressCard, makeCompactActressCard } from './cards.js';
import { ScrollingGrid } from './grid.js';
import { ARCHIVE_VOLUMES, EXHIBITION_VOLUMES } from './config.js';
import { effectiveCols, colsSliderHtml, wireColsSlider, injectColsSlider } from './grid-cols.js';
import { ensureStudioGroups, ensureTitleLabels, renderTwoColumnStudioPanel, updateCompanyMarquee } from './studio-data.js';
import {
  renderDashboardStrip,
  renderDashboardSection,
  renderSideBySidePanel,
  renderStatsTiles,
  createSpotlightRotator,
} from './dashboard-panels.js';

// ── DOM refs ──────────────────────────────────────────────────────────────
export const actressesBtn          = document.getElementById('actresses-btn');
export const actressLandingEl      = document.getElementById('actress-landing');
const actressDashboardBtn   = document.getElementById('actress-dashboard-btn');
const actressDashboardEl    = document.getElementById('actress-dashboard');
const actressFavoritesBtn   = document.getElementById('actress-favorites-btn');
const actressBookmarksBtn   = document.getElementById('actress-bookmarks-btn');
const actressExhibitionBtn       = document.getElementById('actress-exhibition-btn');
const actressExhibitionDivider   = document.getElementById('actress-exhibition-divider');
const actressExhibitionRow       = document.getElementById('actress-exhibition-row');
const actressExhibitionSelect    = document.getElementById('actress-exhibition-company-select');
const actressArchivesBtn         = document.getElementById('actress-archives-btn');
const actressArchivesDivider     = document.getElementById('actress-archives-divider');
const actressArchivesRow         = document.getElementById('actress-archives-row');
const actressArchivesSelect      = document.getElementById('actress-archives-company-select');
const actressStudioBtn      = document.getElementById('actress-studio-btn');
const actressStudioDivider  = document.getElementById('actress-studio-divider');
const actressStudioGroupRow = document.getElementById('actress-studio-group-row');
const actressStudioGroupHeaderEl = document.getElementById('actress-studio-group-header');
const actressStudioLabelsEl = document.getElementById('actress-studio-labels');
const actressGridHeaderEl = document.getElementById('actress-grid-header');
const actressTierBtn              = document.getElementById('actress-tier-btn');
const actressTierDivider          = document.getElementById('actress-tier-divider');
const actressTierRow              = document.getElementById('actress-landing-tier-row');
const actressTierCompanyDivider   = document.getElementById('actress-tier-company-divider');
const actressTierCompanySelect    = document.getElementById('actress-tier-company-select');
const actressTierCompanyMarquee   = document.getElementById('actress-tier-company-marquee');
const actressExhibitionMarquee    = document.getElementById('actress-exhibition-marquee');
const actressArchivesMarquee      = document.getElementById('actress-archives-marquee');
export const actressGridEl  = document.getElementById('actress-grid');

// ── State ─────────────────────────────────────────────────────────────────
export const ACTRESS_TIERS = ['GODDESS', 'SUPERSTAR', 'POPULAR', 'MINOR', 'LIBRARY'];

export let actressBrowseMode = null;         // null | 'favorites' | 'bookmarks' | 'exhibition-volumes' | 'archive-volumes' | 'studio' | 'studio-group:<slug>' | 'tier-<TIER>'
export let actressTierPanelOpen = false;
export let lastActressTier = 'GODDESS';
export let selectedActressStudioSlug = null;
// Display name for the active studio-group filtered grid (used by breadcrumb & header link).
let activeStudioGroupName = null;
// Optional company filter applied within the active studio-group filtered grid (null = all).
let studioGroupCompanyFilter = null;
// Optional company filter for exhibition mode (null = all companies on exhibition volumes).
let exhibitionCompanyFilter = null;
// Optional company filter for archives mode (null = all companies on archive volumes).
let archivesCompanyFilter = null;
// Optional company filter for tier mode (null = all companies in the tier).
let tierCompanyFilter = null;

// ── Column count control (universal — shares storage key with title-browse) ─
const ACTRESS_SLIDER_IDS = ['actress-cols-control', 'actress-cols-slider', 'actress-cols-label'];

function applyActressGridCols(cols) {
  if (actressGridEl) actressGridEl.style.gridTemplateColumns = `repeat(${cols}, 1fr)`;
}

function showActressColsFilterBar() {
  // For modes that already have a company filter row, inject the slider into that
  // row so both controls share one toolbar.  Fall back to the standalone bar for
  // simple modes (search, favorites, bookmarks) that have no filter row.
  const [controlId, sliderId, labelId] = ACTRESS_SLIDER_IDS;
  if (actressBrowseMode === 'exhibition-volumes') {
    const row = document.getElementById('actress-exhibition-row');
    if (row) { injectColsSlider(row, controlId, sliderId, labelId, applyActressGridCols); return; }
  }
  if (actressBrowseMode === 'archive-volumes') {
    const row = document.getElementById('actress-archives-row');
    if (row) { injectColsSlider(row, controlId, sliderId, labelId, applyActressGridCols); return; }
  }
  if (actressBrowseMode && actressBrowseMode.startsWith('tier-')) {
    const row = document.getElementById('actress-landing-tier-row');
    if (row) { injectColsSlider(row, controlId, sliderId, labelId, applyActressGridCols); return; }
  }
  if (actressBrowseMode && actressBrowseMode.startsWith('studio-group:')) {
    const right = actressGridHeaderEl.querySelector('.grid-header-right');
    if (right) { injectColsSlider(right, controlId, sliderId, labelId, applyActressGridCols); return; }
  }
  // search, favorites, bookmarks — no existing filter row; use standalone bar
  const bar = document.getElementById('actress-browse-filter-bar');
  if (!bar) return;
  bar.innerHTML = colsSliderHtml(effectiveCols(), controlId, sliderId, labelId);
  bar.style.display = '';
  wireColsSlider(sliderId, labelId, applyActressGridCols);
}

// ── Tier / studio row visibility ──────────────────────────────────────────
function showActressTierRow() {
  actressTierPanelOpen = true;
  actressTierDivider.style.display = '';
  actressTierRow.style.display = '';
}
function hideActressTierRow() {
  actressTierPanelOpen = false;
  actressTierDivider.style.display = 'none';
  actressTierRow.style.display = 'none';
  hideTierCompanyRow();
}
function showActressStudioGroupRow() {
  actressStudioDivider.style.display = '';
  actressStudioGroupRow.style.display = '';
}
export function hideActressStudioGroupRow() {
  actressStudioDivider.style.display = 'none';
  actressStudioGroupRow.style.display = 'none';
  actressStudioGroupHeaderEl.style.display = 'none';
  actressStudioGroupHeaderEl.innerHTML = '';
  actressStudioLabelsEl.style.display = 'none';
  selectedActressStudioSlug = null;
}
function showExhibitionRow() {
  actressExhibitionDivider.style.display = '';
  actressExhibitionRow.style.display = '';
}
function hideExhibitionRow() {
  actressExhibitionDivider.style.display = 'none';
  actressExhibitionRow.style.display = 'none';
  exhibitionCompanyFilter = null;
}
function showArchivesRow() {
  actressArchivesDivider.style.display = '';
  actressArchivesRow.style.display = '';
}
function hideArchivesRow() {
  actressArchivesDivider.style.display = 'none';
  actressArchivesRow.style.display = 'none';
  archivesCompanyFilter = null;
}
/** Hide all mode-specific sub-nav rows (tier chips, tier company, exhibition, archives). */
export function hideAllActressSubNavRows() {
  hideActressTierRow();
  hideExhibitionRow();
  hideArchivesRow();
}

function showTierCompanyRow() {
  actressTierCompanyDivider.style.display = '';
  actressTierCompanySelect.style.display = '';
}
function hideTierCompanyRow() {
  actressTierCompanyDivider.style.display = 'none';
  actressTierCompanySelect.style.display = 'none';
  actressTierCompanyMarquee.style.display = 'none';
  tierCompanyFilter = null;
}

// ── Tier chips ────────────────────────────────────────────────────────────
function buildActressTierChips() {
  actressTierRow.querySelectorAll('.actress-landing-tier').forEach(el => el.remove());
  for (const tier of ACTRESS_TIERS) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `actress-landing-tier tier-chip-${tier}`;
    btn.dataset.tier = tier;
    btn.textContent = tier.toLowerCase();
    btn.addEventListener('click', () => selectActressBrowseMode(`tier-${tier}`));
    actressTierRow.insertBefore(btn, actressTierCompanyDivider);
  }
}
buildActressTierChips();

// ── Selection state helpers ───────────────────────────────────────────────
export function updateActressLandingSelection() {
  actressDashboardBtn.classList.toggle('selected', actressBrowseMode === 'dashboard');
  actressFavoritesBtn.classList.toggle('selected', actressBrowseMode === 'favorites');
  actressBookmarksBtn.classList.toggle('selected', actressBrowseMode === 'bookmarks');
  actressExhibitionBtn.classList.toggle('selected', actressBrowseMode === 'exhibition-volumes');
  actressArchivesBtn.classList.toggle('selected',  actressBrowseMode === 'archive-volumes');
  // Keep Studio highlighted in both the catalog ("studio") and the filtered grid ("studio-group:*").
  actressStudioBtn.classList.toggle('selected',
    actressBrowseMode === 'studio' || (actressBrowseMode || '').startsWith('studio-group:'));
  actressTierBtn.classList.toggle('selected', actressTierPanelOpen);
  actressTierRow.querySelectorAll('.actress-landing-tier').forEach(btn => {
    btn.classList.toggle('selected', actressBrowseMode === `tier-${btn.dataset.tier}`);
  });
}

export function actressBrowseLabel(modeKey) {
  if (!modeKey) return '';
  if (modeKey === 'dashboard')       return 'Dashboard';
  if (modeKey === 'favorites')          return 'Favorites';
  if (modeKey === 'bookmarks')          return 'Bookmarks';
  if (modeKey === 'exhibition-volumes') return 'Exhibition';
  if (modeKey === 'archive-volumes')    return 'Archive';
  if (modeKey === 'studio')          return 'Studio';
  if (modeKey.startsWith('tier-'))   return modeKey.slice(5).toLowerCase();
  if (modeKey.startsWith('studio-group:')) return activeStudioGroupName || 'Studio Group';
  return modeKey;
}

export function updateActressBreadcrumb() {
  const crumbs = [{ label: 'Actresses', action: () => showActressLanding() }];
  if (actressBrowseMode && actressBrowseMode !== 'dashboard') {
    if (actressBrowseMode.startsWith('studio-group:')) {
      // 3-level: Actresses › Studio › {Group} — clicking Studio goes back to catalog.
      crumbs.push({ label: 'Studio', action: () => selectActressBrowseMode('studio') });
      crumbs.push({ label: actressBrowseLabel(actressBrowseMode) });
    } else {
      crumbs.push({ label: actressBrowseLabel(actressBrowseMode) });
    }
  }
  updateBreadcrumb(crumbs);
}

// ── Scrolling grid ────────────────────────────────────────────────────────
export const actressScrollGrid = new ScrollingGrid(
  actressGridEl,
  (o, l) => {
    if (actressBrowseMode === 'favorites')
      return `/api/actresses?favorites=true&offset=${o}&limit=${l}`;
    if (actressBrowseMode === 'bookmarks')
      return `/api/actresses?bookmarks=true&offset=${o}&limit=${l}`;
    if (actressBrowseMode === 'exhibition-volumes') {
      let url = `/api/actresses?volumes=${encodeURIComponent(EXHIBITION_VOLUMES)}&offset=${o}&limit=${l}`;
      if (exhibitionCompanyFilter) url += `&company=${encodeURIComponent(exhibitionCompanyFilter)}`;
      return url;
    }
    if (actressBrowseMode === 'archive-volumes') {
      let url = `/api/actresses?volumes=${encodeURIComponent(ARCHIVE_VOLUMES)}&offset=${o}&limit=${l}`;
      if (archivesCompanyFilter) url += `&company=${encodeURIComponent(archivesCompanyFilter)}`;
      return url;
    }
    if (actressBrowseMode && actressBrowseMode.startsWith('tier-')) {
      const tier = actressBrowseMode.slice(5);
      let url = `/api/actresses?tier=${encodeURIComponent(tier)}&offset=${o}&limit=${l}`;
      if (tierCompanyFilter) url += `&company=${encodeURIComponent(tierCompanyFilter)}`;
      return url;
    }
    if (actressBrowseMode && actressBrowseMode.startsWith('studio-group:')) {
      const slug = actressBrowseMode.slice('studio-group:'.length);
      let url = `/api/actresses?studioGroup=${encodeURIComponent(slug)}&offset=${o}&limit=${l}`;
      if (studioGroupCompanyFilter) url += `&company=${encodeURIComponent(studioGroupCompanyFilter)}`;
      return url;
    }
    return null; // no active mode → empty grid
  },
  makeActressCard,
  'no actresses'
);

// ── resetActressState — called by showTitlesBrowse and showTitlesView ──────
export function resetActressState() {
  actressBrowseMode = null;
  lastActressTier = 'GODDESS';
  activeStudioGroupName = null;
  studioGroupCompanyFilter = null;
  actressGridHeaderEl.style.display = 'none';
  actressGridHeaderEl.innerHTML = '';
  hideActressStudioGroupRow();
  hideActressTierRow();
  hideExhibitionRow();
  hideArchivesRow();
  updateActressLandingSelection();
}

// ── Dashboard render ──────────────────────────────────────────────────────

const ACTRESS_SPOTLIGHT_INTERVAL_MS = 30_000;
const actressSpotlightRotator = createSpotlightRotator({
  endpoint: '/api/actresses/spotlight',
  excludeAttr: 'actressId',
  cardSelector: '.actress-card',
  makeCard: a => {
    const card = makeActressCard(a);
    card.classList.add('card-spotlight');
    return card;
  },
  intervalMs: ACTRESS_SPOTLIGHT_INTERVAL_MS,
});

function renderTopGroupsLeaderboard(topGroups) {
  const section = document.createElement('section');
  section.className = 'dashboard-section dashboard-top-groups';
  const header = document.createElement('div');
  header.className = 'dashboard-section-title';
  header.textContent = 'Top Groups';
  section.appendChild(header);

  const list = document.createElement('div');
  list.className = 'dashboard-leaderboard';
  const maxScore = topGroups.reduce((m, g) => Math.max(m, g.score || 0), 0) || 1;
  topGroups.forEach((g, i) => {
    const row = document.createElement('div');
    row.className = 'leaderboard-row leaderboard-row-clickable';
    row.title = `Open ${g.name} in Studio browser`;
    const countLabel = `${g.actressCount} ${g.actressCount === 1 ? 'actress' : 'actresses'}`;
    row.innerHTML = `
      <span class="leaderboard-rank">${i + 1}</span>
      <span class="leaderboard-name-cell">
        <span class="leaderboard-name">${esc(g.name)}</span>
        <span class="leaderboard-company">${countLabel}</span>
      </span>
      <span class="leaderboard-bar-wrap"><span class="leaderboard-bar" style="width:${Math.round((g.score / maxScore) * 100)}%"></span></span>
    `;
    row.addEventListener('click', () => {
      selectActressBrowseMode(`studio-group:${g.slug}`);
    });
    list.appendChild(row);
  });
  section.appendChild(list);
  return section;
}

function renderActressLibraryStats(stats) {
  const researchPct = stats.researchTotal > 0
    ? Math.round((stats.researchCovered / stats.researchTotal) * 100)
    : 0;
  return renderStatsTiles({
    heading: 'Library',
    tiles: [
      { label: 'Actresses',     value: stats.totalActresses.toLocaleString() },
      { label: 'Favorites',     value: stats.favorites.toLocaleString() },
      { label: 'Graded',        value: stats.graded.toLocaleString() },
      { label: 'Elites',        value: stats.elites.toLocaleString() },
      { label: 'New this month', value: stats.newThisMonth.toLocaleString() },
      { label: 'Researched',    value: `${researchPct}%`, bar: researchPct },
    ],
  });
}

function renderActressTopInfoPanel(spotlight, topGroups, libraryStats, birthdaysToday, researchGaps) {
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
    const card = makeActressCard(spotlight);
    card.classList.add('card-spotlight');
    left.appendChild(card);
    panel.appendChild(left);
    setTimeout(() => actressSpotlightRotator.start(left), ACTRESS_SPOTLIGHT_INTERVAL_MS);
  }

  // Right column: upper row (Top Groups | Library+Research Gaps stack) + lower (Birthdays Today)
  const hasRight = topGroups.length > 0 || libraryStats || birthdaysToday.length > 0 || researchGaps.length > 0;
  if (hasRight) {
    const right = document.createElement('div');
    right.className = 'dashboard-top-panel-right';

    if (topGroups.length > 0 || libraryStats || researchGaps.length > 0) {
      const upper = document.createElement('div');
      upper.className = 'dashboard-top-right-upper';
      if (topGroups.length > 0) upper.appendChild(renderTopGroupsLeaderboard(topGroups));

      // Right side of upper row: Library on top, Research Gaps below — fills the
      // dead space that Library alone would leave next to the tall Top Groups list.
      if (libraryStats || researchGaps.length > 0) {
        const stack = document.createElement('div');
        stack.className = 'dashboard-top-right-stack';
        if (libraryStats) stack.appendChild(renderActressLibraryStats(libraryStats));
        if (researchGaps.length > 0) {
          stack.appendChild(renderDashboardSection({
            title: 'Research Gaps',
            badge: `${researchGaps.length}`,
            body: renderResearchGapsList(researchGaps),
          }));
        }
        upper.appendChild(stack);
      }

      right.appendChild(upper);
    }

    if (birthdaysToday.length > 0) {
      const shown = birthdaysToday.slice(0, 3);
      const strip = renderDashboardStrip(shown, { id: 'dash-birthdays-today', cardFactory: makeActressCard });
      right.appendChild(renderDashboardSection({
        title: 'Birthdays Today',
        badge: `${birthdaysToday.length} 🎂`,
        body: strip,
      }));
    }

    panel.appendChild(right);
  }

  return panel;
}

function renderResearchGapsList(entries) {
  const list = document.createElement('div');
  list.className = 'dashboard-research-gaps';
  entries.forEach(entry => {
    const a = entry.actress;
    const dots = [
      { filled: entry.profileFilled,    label: 'profile'   },
      { filled: entry.physicalFilled,   label: 'physical'  },
      { filled: entry.biographyFilled,  label: 'biography' },
      { filled: entry.portfolioCovered, label: 'portfolio' },
    ];
    const dotsHtml = dots.map(d => {
      const tip = `${d.label}: ${d.filled ? 'filled' : 'missing'}`;
      return `<span class="research-gap-dot ${d.filled ? 'filled' : 'empty'}" title="${tip}"></span>`;
    }).join('');
    const row = document.createElement('div');
    row.className = 'research-gap-row';
    row.innerHTML = `
      <span class="research-gap-name">${esc(a.canonicalName)}</span>
      <span class="research-gap-tier tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>
      <span class="research-gap-dots">${dotsHtml}</span>
    `;
    row.addEventListener('click', () => _openActressDetail(a.id));
    list.appendChild(row);
  });
  return list;
}

async function renderActressDashboard() {
  actressDashboardEl.innerHTML = '<div class="dashboard-loading">loading…</div>';
  try {
    const res = await fetch('/api/actresses/dashboard');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();

    const spotlight          = data.spotlight          || null;
    const birthdaysToday     = data.birthdaysToday     || [];
    const newFaces           = data.newFaces           || [];
    const bookmarks          = data.bookmarks          || [];
    const recentlyViewed     = data.recentlyViewed     || [];
    const undiscoveredElites = data.undiscoveredElites || [];
    const forgottenGems      = data.forgottenGems      || [];
    const topGroups          = data.topGroups          || [];
    const researchGaps       = data.researchGaps       || [];
    const libraryStats       = data.libraryStats       || null;

    const hasAny = spotlight || birthdaysToday.length || newFaces.length || bookmarks.length
                || recentlyViewed.length || undiscoveredElites.length || forgottenGems.length
                || topGroups.length || researchGaps.length;
    if (!hasAny) {
      actressDashboardEl.innerHTML = '<div class="dashboard-empty">No actresses yet — sync a volume to get started.</div>';
      return;
    }

    actressDashboardEl.innerHTML = '';

    // 0. Top info panel: Spotlight (left) + Top Groups | (Library + Research Gaps stack) | Birthdays (right).
    if (spotlight || topGroups.length > 0 || libraryStats || birthdaysToday.length > 0 || researchGaps.length > 0) {
      actressDashboardEl.appendChild(
        renderActressTopInfoPanel(spotlight, topGroups, libraryStats, birthdaysToday, researchGaps));
    }

    // 0b. Second panel: Recently Viewed (left) + New Faces (right).
    if (recentlyViewed.length > 0 || newFaces.length > 0) {
      const rvSection = recentlyViewed.length > 0
        ? renderDashboardSection({
            title: 'Recently Viewed',
            body: (() => {
              const strip = renderDashboardStrip(recentlyViewed, { id: 'dash-actress-recently-viewed', cardFactory: makeCompactActressCard });
              strip.classList.add('dashboard-card-grid-compact');
              return strip;
            })(),
          })
        : null;
      const nfSection = newFaces.length > 0
        ? renderDashboardSection({
            title: 'New Faces',
            body: renderDashboardStrip(newFaces, { id: 'dash-actress-new-faces', cardFactory: makeActressCard }),
          })
        : null;
      actressDashboardEl.appendChild(renderSideBySidePanel('dashboard-panel-2', rvSection, nfSection));
    }

    // 1. Bookmarks hero strip.
    if (bookmarks.length > 0) {
      actressDashboardEl.appendChild(renderDashboardSection({
        title: 'Bookmarked Actresses',
        accent: true,
        bordered: true,
        body: renderDashboardStrip(bookmarks, { id: 'dash-actress-bookmarks', cardFactory: makeCompactActressCard }),
      }));
    }

    // 2. Undiscovered Elites.
    if (undiscoveredElites.length > 0) {
      actressDashboardEl.appendChild(renderDashboardSection({
        title: 'Undiscovered Elites',
        bordered: true,
        body: renderDashboardStrip(undiscoveredElites, { id: 'dash-actress-undiscovered', cardFactory: makeActressCard }),
      }));
    }

    // 3. Forgotten Gems.
    if (forgottenGems.length > 0) {
      actressDashboardEl.appendChild(renderDashboardSection({
        title: 'Forgotten Gems',
        body: renderDashboardStrip(forgottenGems, { id: 'dash-actress-forgotten-gems', cardFactory: makeActressCard }),
      }));
    }

  } catch (err) {
    actressDashboardEl.innerHTML = '<div class="dashboard-empty">Error loading dashboard.</div>';
    console.error(err);
  }
}

// Local helper to open the actress detail view from research gap rows.
// We dynamic-import actress-detail.js (same pattern as title-browse uses for
// its actress-card click handlers) to avoid an import cycle with the
// dashboard module.
function _openActressDetail(id) {
  import('./actress-detail.js').then(m => m.openActressDetail(id));
}

// ── Browse mode selection ─────────────────────────────────────────────────
export async function selectActressBrowseMode(modeKey) {
  pushNav({ view: 'actresses', mode: modeKey }, 'actresses/' + encodeURIComponent(modeKey));
  actressBrowseMode = modeKey;
  document.getElementById('av-btn')?.classList.remove('active');
  document.getElementById('action-btn')?.classList.remove('active');
  if (modeKey === 'dashboard') {
    hideActressTierRow();
    hideActressStudioGroupRow();
    hideExhibitionRow();
    hideArchivesRow();
    actressGridHeaderEl.style.display = 'none';
    actressGridHeaderEl.innerHTML = '';
    activeStudioGroupName = null;
    studioGroupCompanyFilter = null;
    updateActressLandingSelection();
    updateActressBreadcrumb();
    actressesBtn.classList.add('active');
    showView('actresses');
    actressGridEl.style.display = 'none';
    actressDashboardEl.style.display = 'block';
    await renderActressDashboard();
    return;
  }
  actressDashboardEl.style.display = 'none';
  if (modeKey === 'studio') {
    hideActressTierRow();
    hideExhibitionRow();
    hideArchivesRow();
    actressGridHeaderEl.style.display = 'none';
    actressGridHeaderEl.innerHTML = '';
    activeStudioGroupName = null;
    studioGroupCompanyFilter = null;
    updateActressLandingSelection();
    updateActressBreadcrumb();
    actressesBtn.classList.add('active');
    showView('actresses');
    actressGridEl.style.display = 'none';
    showActressStudioGroupRow();
    ensureStudioGroups().then(groups => {
      renderActressStudioGroupRow(groups);
      if (groups.length > 0) {
        selectActressStudioGroup(selectedActressStudioSlug || groups[0].slug);
      }
    });
    return;
  }
  hideActressStudioGroupRow();
  if (modeKey === 'exhibition-volumes') {
    exhibitionCompanyFilter = null;
    hideActressTierRow();
    actressGridHeaderEl.style.display = 'none';
    actressGridHeaderEl.innerHTML = '';
    activeStudioGroupName = null;
    studioGroupCompanyFilter = null;
    showExhibitionRow();
    populateExhibitionCompanyDropdown();
    hideArchivesRow();
  } else if (modeKey === 'archive-volumes') {
    archivesCompanyFilter = null;
    hideActressTierRow();
    actressGridHeaderEl.style.display = 'none';
    actressGridHeaderEl.innerHTML = '';
    activeStudioGroupName = null;
    studioGroupCompanyFilter = null;
    showArchivesRow();
    populateArchivesCompanyDropdown();
    hideExhibitionRow();
  } else {
    hideExhibitionRow();
    hideArchivesRow();
  }
  if (modeKey.startsWith('studio-group:')) {
    const slug = modeKey.slice('studio-group:'.length);
    selectedActressStudioSlug = slug;
    // Fresh entry into the filtered grid → start with the "All" filter.
    studioGroupCompanyFilter = null;
    // Resolve the human-readable group name for the breadcrumb / header label.
    const groups = await ensureStudioGroups();
    const group = groups.find(g => g.slug === slug);
    activeStudioGroupName = group ? group.name : slug;
    // Fetch companies ordered by title count (server-side) so the dropdown surfaces
    // the most populous sub-labels first. Falls back to YAML order on fetch failure.
    let companyCounts;
    try {
      const resp = await fetch(`/api/studio-groups/${encodeURIComponent(slug)}/companies`);
      companyCounts = resp.ok ? await resp.json() : null;
    } catch {
      companyCounts = null;
    }
    renderActressGridHeader(group, slug, companyCounts);
    hideActressTierRow();
  } else {
    activeStudioGroupName = null;
    studioGroupCompanyFilter = null;
    actressGridHeaderEl.style.display = 'none';
    actressGridHeaderEl.innerHTML = '';
    if (modeKey.startsWith('tier-')) {
      lastActressTier = modeKey.slice(5);
      tierCompanyFilter = null;
      showActressTierRow();
      showTierCompanyRow();
      populateTierCompanyDropdown();
    } else {
      hideActressTierRow();
    }
  }
  updateActressLandingSelection();
  updateActressBreadcrumb();
  actressesBtn.classList.add('active');
  showView('actresses');
  showActressColsFilterBar();
  applyActressGridCols(effectiveCols());
  setActiveGrid(actressScrollGrid);
  actressScrollGrid.reset();
  ensureSentinel();
  await actressScrollGrid.loadMore();
}

export function showActressLanding() {
  selectActressBrowseMode('dashboard');
}

actressDashboardBtn.addEventListener('click',   () => selectActressBrowseMode('dashboard'));
actressFavoritesBtn.addEventListener('click',   () => selectActressBrowseMode('favorites'));
actressBookmarksBtn.addEventListener('click',   () => selectActressBrowseMode('bookmarks'));
actressExhibitionBtn.addEventListener('click',  () => selectActressBrowseMode('exhibition-volumes'));
actressArchivesBtn.addEventListener('click',    () => selectActressBrowseMode('archive-volumes'));
actressStudioBtn.addEventListener('click',    () => selectActressBrowseMode('studio'));
actressTierBtn.addEventListener('click', () => selectActressBrowseMode(`tier-${lastActressTier}`));

actressesBtn.addEventListener('click', e => {
  e.stopPropagation();
  selectActressBrowseMode('dashboard');
});

// ── Exhibition company dropdown ───────────────────────────────────────────
async function populateExhibitionCompanyDropdown() {
  const labels = await ensureTitleLabels();
  const companies = [...new Set(labels.map(l => l.company).filter(Boolean))].sort();
  actressExhibitionSelect.innerHTML =
    '<option value="">All Companies</option>' +
    companies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
  actressExhibitionSelect.value = exhibitionCompanyFilter || '';
}

actressExhibitionSelect.addEventListener('change', () => {
  exhibitionCompanyFilter = actressExhibitionSelect.value || null;
  updateCompanyMarquee(actressExhibitionMarquee, exhibitionCompanyFilter);
  document.getElementById('sentinel')?.remove();
  actressScrollGrid.reset();
  ensureSentinel();
  actressScrollGrid.loadMore();
});

async function populateArchivesCompanyDropdown() {
  const labels = await ensureTitleLabels();
  const companies = [...new Set(labels.map(l => l.company).filter(Boolean))].sort();
  actressArchivesSelect.innerHTML =
    '<option value="">All Companies</option>' +
    companies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
  actressArchivesSelect.value = archivesCompanyFilter || '';
}

actressArchivesSelect.addEventListener('change', () => {
  archivesCompanyFilter = actressArchivesSelect.value || null;
  updateCompanyMarquee(actressArchivesMarquee, archivesCompanyFilter);
  document.getElementById('sentinel')?.remove();
  actressScrollGrid.reset();
  ensureSentinel();
  actressScrollGrid.loadMore();
});

async function populateTierCompanyDropdown() {
  const labels = await ensureTitleLabels();
  const companies = [...new Set(labels.map(l => l.company).filter(Boolean))].sort();
  actressTierCompanySelect.innerHTML =
    '<option value="">All Companies</option>' +
    companies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
  actressTierCompanySelect.value = tierCompanyFilter || '';
}

actressTierCompanySelect.addEventListener('change', () => {
  tierCompanyFilter = actressTierCompanySelect.value || null;
  updateCompanyMarquee(actressTierCompanyMarquee, tierCompanyFilter);
  document.getElementById('sentinel')?.remove();
  actressScrollGrid.reset();
  ensureSentinel();
  actressScrollGrid.loadMore();
});

// ── Actress Studio browser ────────────────────────────────────────────────
function renderActressStudioGroupRow(groups) {
  actressStudioGroupRow.innerHTML = '';
  groups.forEach(g => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'studio-group-btn actress-studio-group-btn' +
      (g.slug === selectedActressStudioSlug ? ' selected' : '');
    btn.textContent = g.name;
    btn.dataset.slug = g.slug;
    btn.addEventListener('click', () => selectActressStudioGroup(g.slug));
    actressStudioGroupRow.appendChild(btn);
  });
}

async function selectActressStudioGroup(slug) {
  selectedActressStudioSlug = slug;
  actressStudioGroupRow.querySelectorAll('.actress-studio-group-btn').forEach(btn => {
    btn.classList.toggle('selected', btn.dataset.slug === slug);
  });

  const groups = await ensureStudioGroups();
  const group = groups.find(g => g.slug === slug);
  if (!group) return;

  renderActressStudioGroupHeader(group);

  const allLabels = await ensureTitleLabels();
  const companySet = new Set(group.companies);

  const byCompany = new Map();
  group.companies.forEach(c => byCompany.set(c, []));
  allLabels.forEach(lbl => {
    if (companySet.has(lbl.company)) byCompany.get(lbl.company).push(lbl);
  });

  renderActressStudioLabels(byCompany);
}

// Header above the actress grid in studio-group mode: group name on the left, a
// company-filter dropdown in the middle, and a "← View label catalog" crosslink on
// the right. The dropdown lists every company in the active group; "All" reloads
// the unfiltered grid.
//
// `companyCounts` (when provided) is an array of {company, titleCount} ordered by
// titleCount desc — produced by /api/studio-groups/:slug/companies. Falls back to
// the raw YAML order if the fetch failed.
function renderActressGridHeader(group, slug, companyCounts) {
  const name = group ? group.name : slug;
  const ordered = Array.isArray(companyCounts) && companyCounts.length > 0
    ? companyCounts
    : ((group && group.companies) ? group.companies : []).map(c => ({ company: c, titleCount: 0 }));
  const optionsHtml = ['<option value="">All labels</option>']
    .concat(ordered.map(({ company, titleCount }) => {
      const label = titleCount > 0 ? `${company} (${titleCount})` : company;
      return `<option value="${esc(company)}">${esc(label)}</option>`;
    }))
    .join('');
  actressGridHeaderEl.style.display = 'flex';
  actressGridHeaderEl.innerHTML = `
    <span class="grid-header-name">${esc(name)}</span>
    <div class="grid-header-right">
      <label class="grid-header-filter">
        <span class="grid-header-filter-label">Filter:</span>
        <select class="grid-header-filter-select" id="actress-grid-header-filter">
          ${optionsHtml}
        </select>
      </label>
      <button type="button" class="grid-header-action" id="actress-grid-header-action">
        ← View label catalog
      </button>
    </div>
  `;
  const select = document.getElementById('actress-grid-header-filter');
  select.value = studioGroupCompanyFilter || '';
  select.addEventListener('change', () => {
    studioGroupCompanyFilter = select.value || null;
    document.getElementById('sentinel')?.remove();
    actressScrollGrid.reset();
    ensureSentinel();
    actressScrollGrid.loadMore();
  });
  document.getElementById('actress-grid-header-action').addEventListener('click', () => {
    selectedActressStudioSlug = slug;
    selectActressBrowseMode('studio');
  });
}

// Header above the catalog two-column panel: shows the group name and a "View actresses"
// link that switches to the studio-group filtered grid for the same slug.
function renderActressStudioGroupHeader(group) {
  actressStudioGroupHeaderEl.style.display = 'flex';
  actressStudioGroupHeaderEl.innerHTML = `
    <span class="studio-group-header-name">${esc(group.name)}</span>
    <button type="button" class="studio-group-header-action" id="studio-group-header-action">
      View actresses in this group →
    </button>
  `;
  document.getElementById('studio-group-header-action').addEventListener('click', () => {
    selectActressBrowseMode(`studio-group:${group.slug}`);
  });
}

function renderActressStudioLabels(byCompany) {
  renderTwoColumnStudioPanel(
    actressStudioLabelsEl, 'actress-studio-label-detail', byCompany, selectActressStudioCompany,
    () => { actressGridEl.style.display = 'none'; }
  );
}

function selectActressStudioCompany(company, byCompany) {
  actressStudioLabelsEl.querySelectorAll('.studio-label-item').forEach(el => {
    el.classList.toggle('selected', el.dataset.company === company);
  });

  const detailEl = document.getElementById('actress-studio-label-detail');
  if (!detailEl) return;

  const labels = byCompany.get(company) || [];
  const companyDesc = labels.length > 0 && labels[0].companyDescription ? labels[0].companyDescription : null;

  let html = `<div class="studio-detail-heading">${esc(company)}</div>`;
  if (companyDesc) html += `<div class="studio-detail-company-desc">${esc(companyDesc)}</div>`;

  const byLabel = new Map();
  labels.forEach(lbl => {
    const key = lbl.labelName || lbl.code;
    if (!byLabel.has(key)) byLabel.set(key, []);
    byLabel.get(key).push(lbl);
  });
  html += '<div class="studio-detail-section-label">product codes</div>';
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
}
