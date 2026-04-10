import { esc } from './utils.js';
import { showView, setActiveGrid, ensureSentinel, updateBreadcrumb } from './grid.js';
import { makeActressCard } from './cards.js';
import { ScrollingGrid } from './grid.js';
import { ARCHIVE_VOLUMES } from './config.js';
import { ensureStudioGroups, ensureTitleLabels, renderTwoColumnStudioPanel } from './studio-data.js';

// ── DOM refs ──────────────────────────────────────────────────────────────
export const actressesBtn          = document.getElementById('actresses-btn');
export const actressLandingEl      = document.getElementById('actress-landing');
const actressSearchInput    = document.getElementById('actress-search-input');
const actressSearchClearBtn = document.getElementById('actress-search-clear');
const actressFavoritesBtn   = document.getElementById('actress-favorites-btn');
const actressBookmarksBtn   = document.getElementById('actress-bookmarks-btn');
const actressArchivesBtn    = document.getElementById('actress-archives-btn');
const actressStudioBtn      = document.getElementById('actress-studio-btn');
const actressStudioDivider  = document.getElementById('actress-studio-divider');
const actressStudioGroupRow = document.getElementById('actress-studio-group-row');
const actressStudioLabelsEl = document.getElementById('actress-studio-labels');
const actressTierBtn        = document.getElementById('actress-tier-btn');
const actressTierDivider    = document.getElementById('actress-tier-divider');
const actressTierRow        = document.getElementById('actress-landing-tier-row');
export const actressGridEl  = document.getElementById('actress-grid');

// ── State ─────────────────────────────────────────────────────────────────
export const ACTRESS_TIERS = ['GODDESS', 'SUPERSTAR', 'POPULAR', 'MINOR', 'LIBRARY'];
const ACTRESS_SEARCH_DELAY_MS  = 350;
const ACTRESS_SEARCH_MIN_CHARS = 2;

export let actressBrowseMode = null;         // null | 'search' | 'favorites' | 'bookmarks' | 'archive-volumes' | 'studio' | 'tier-<TIER>'
export let actressSearchTerm = '';
export let actressSearchTimer = null;
export let actressTierPanelOpen = false;
export let lastActressTier = 'GODDESS';
export let selectedActressStudioSlug = null;

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
}
function showActressStudioGroupRow() {
  actressStudioDivider.style.display = '';
  actressStudioGroupRow.style.display = '';
}
export function hideActressStudioGroupRow() {
  actressStudioDivider.style.display = 'none';
  actressStudioGroupRow.style.display = 'none';
  actressStudioLabelsEl.style.display = 'none';
  selectedActressStudioSlug = null;
}

// ── Tier chips ────────────────────────────────────────────────────────────
function buildActressTierChips() {
  actressTierRow.innerHTML = '';
  for (const tier of ACTRESS_TIERS) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `actress-landing-tier tier-chip-${tier}`;
    btn.dataset.tier = tier;
    btn.textContent = tier.toLowerCase();
    btn.addEventListener('click', () => selectActressBrowseMode(`tier-${tier}`));
    actressTierRow.appendChild(btn);
  }
}
buildActressTierChips();

// ── Selection state helpers ───────────────────────────────────────────────
export function updateActressLandingSelection() {
  actressFavoritesBtn.classList.toggle('selected', actressBrowseMode === 'favorites');
  actressBookmarksBtn.classList.toggle('selected', actressBrowseMode === 'bookmarks');
  actressArchivesBtn.classList.toggle('selected',  actressBrowseMode === 'archive-volumes');
  actressStudioBtn.classList.toggle('selected',    actressBrowseMode === 'studio');
  actressTierBtn.classList.toggle('selected', actressTierPanelOpen);
  actressTierRow.querySelectorAll('.actress-landing-tier').forEach(btn => {
    btn.classList.toggle('selected', actressBrowseMode === `tier-${btn.dataset.tier}`);
  });
}

export function actressBrowseLabel(modeKey) {
  if (!modeKey) return '';
  if (modeKey === 'favorites')       return 'Favorites';
  if (modeKey === 'bookmarks')       return 'Bookmarks';
  if (modeKey === 'archive-volumes') return 'Archive';
  if (modeKey === 'studio')          return 'Studio';
  if (modeKey === 'search')          return `search: "${actressSearchTerm}"`;
  if (modeKey.startsWith('tier-'))   return modeKey.slice(5).toLowerCase();
  return modeKey;
}

export function updateActressBreadcrumb() {
  const crumbs = [{ label: 'Actresses', action: () => showActressLanding() }];
  if (actressBrowseMode) crumbs.push({ label: actressBrowseLabel(actressBrowseMode) });
  updateBreadcrumb(crumbs);
}

// ── Scrolling grid ────────────────────────────────────────────────────────
export const actressScrollGrid = new ScrollingGrid(
  actressGridEl,
  (o, l) => {
    if (actressBrowseMode === 'search')
      return `/api/actresses?search=${encodeURIComponent(actressSearchTerm)}&offset=${o}&limit=${l}`;
    if (actressBrowseMode === 'favorites')
      return `/api/actresses?favorites=true&offset=${o}&limit=${l}`;
    if (actressBrowseMode === 'bookmarks')
      return `/api/actresses?bookmarks=true&offset=${o}&limit=${l}`;
    if (actressBrowseMode === 'archive-volumes')
      return `/api/actresses?volumes=${encodeURIComponent(ARCHIVE_VOLUMES)}&offset=${o}&limit=${l}`;
    if (actressBrowseMode && actressBrowseMode.startsWith('tier-')) {
      const tier = actressBrowseMode.slice(5);
      return `/api/actresses?tier=${encodeURIComponent(tier)}&offset=${o}&limit=${l}`;
    }
    return null; // no active mode → empty grid
  },
  makeActressCard,
  'no actresses'
);

export function clearActressGrid() {
  actressScrollGrid.reset();
}

// ── resetActressState — called by showTitlesBrowse and showTitlesView ──────
export function resetActressState() {
  if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
  actressBrowseMode = null;
  actressSearchTerm = '';
  if (actressSearchInput) {
    actressSearchInput.value = '';
    actressSearchInput.classList.remove('invalid');
  }
  lastActressTier = 'GODDESS';
  hideActressStudioGroupRow();
  hideActressTierRow();
  updateActressLandingSelection();
}

// ── Browse mode selection ─────────────────────────────────────────────────
export async function selectActressBrowseMode(modeKey) {
  actressBrowseMode = modeKey;
  if (modeKey !== 'search') {
    if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
    actressSearchTerm = '';
    if (actressSearchInput.value !== '') actressSearchInput.value = '';
    actressSearchInput.classList.remove('invalid');
  }
  if (modeKey === 'studio') {
    hideActressTierRow();
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
  if (modeKey.startsWith('tier-')) {
    lastActressTier = modeKey.slice(5);
    showActressTierRow();
  } else {
    hideActressTierRow();
  }
  updateActressLandingSelection();
  updateActressBreadcrumb();
  actressesBtn.classList.add('active');
  showView('actresses');
  setActiveGrid(actressScrollGrid);
  actressScrollGrid.reset();
  ensureSentinel();
  await actressScrollGrid.loadMore();
}

export function showActressLanding() {
  actressesBtn.classList.add('active');
  if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
  actressBrowseMode = null;
  actressSearchTerm = '';
  actressSearchInput.value = '';
  actressSearchInput.classList.remove('invalid');
  hideActressStudioGroupRow();
  hideActressTierRow();
  updateActressLandingSelection();
  updateBreadcrumb([{ label: 'Actresses' }]);
  showView('actresses');
  requestAnimationFrame(() => {
    const header = document.querySelector('header');
    if (header) actressLandingEl.style.top = header.offsetHeight + 'px';
  });
  setActiveGrid(actressScrollGrid);
  clearActressGrid();
}

// ── Search input ──────────────────────────────────────────────────────────
function updateActressSearchValidity() {
  const raw = actressSearchInput.value.trim();
  actressSearchInput.classList.toggle('invalid', raw.length > 0 && raw.length < ACTRESS_SEARCH_MIN_CHARS);
}

function scheduleActressSearch() {
  if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
  updateActressSearchValidity();
  const raw = actressSearchInput.value.trim();
  if (raw.length < ACTRESS_SEARCH_MIN_CHARS) {
    if (actressBrowseMode === 'search') {
      actressBrowseMode = null;
      updateActressLandingSelection();
      updateActressBreadcrumb();
      clearActressGrid();
    }
    return;
  }
  actressSearchTimer = setTimeout(() => {
    actressSearchTimer = null;
    actressSearchTerm = raw;
    actressBrowseMode = 'search';
    hideActressTierRow();
    updateActressLandingSelection();
    updateActressBreadcrumb();
    actressesBtn.classList.add('active');
    setActiveGrid(actressScrollGrid);
    actressScrollGrid.reset();
    ensureSentinel();
    actressScrollGrid.loadMore();
  }, ACTRESS_SEARCH_DELAY_MS);
}

actressSearchInput.addEventListener('input', scheduleActressSearch);
actressSearchInput.addEventListener('keydown', e => {
  if (e.key !== 'Enter') return;
  e.preventDefault();
  if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
  const raw = actressSearchInput.value.trim();
  if (raw.length < ACTRESS_SEARCH_MIN_CHARS) { clearActressGrid(); return; }
  actressSearchTerm = raw;
  actressBrowseMode = 'search';
  hideActressTierRow();
  updateActressLandingSelection();
  updateActressBreadcrumb();
  actressesBtn.classList.add('active');
  setActiveGrid(actressScrollGrid);
  actressScrollGrid.reset();
  ensureSentinel();
  actressScrollGrid.loadMore();
});

actressSearchClearBtn.addEventListener('click', () => {
  if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
  actressSearchInput.value = '';
  actressSearchInput.classList.remove('invalid');
  actressSearchTerm = '';
  if (actressBrowseMode === 'search') {
    actressBrowseMode = null;
    updateActressLandingSelection();
    updateActressBreadcrumb();
    clearActressGrid();
  }
  actressSearchInput.focus();
});

actressFavoritesBtn.addEventListener('click', () => selectActressBrowseMode('favorites'));
actressBookmarksBtn.addEventListener('click', () => selectActressBrowseMode('bookmarks'));
actressArchivesBtn.addEventListener('click',  () => selectActressBrowseMode('archive-volumes'));
actressStudioBtn.addEventListener('click',    () => selectActressBrowseMode('studio'));
actressTierBtn.addEventListener('click', () => selectActressBrowseMode(`tier-${lastActressTier}`));

actressesBtn.addEventListener('click', e => {
  e.stopPropagation();
  selectActressBrowseMode('favorites');
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

  const allLabels = await ensureTitleLabels();
  const companySet = new Set(group.companies);

  const byCompany = new Map();
  group.companies.forEach(c => byCompany.set(c, []));
  allLabels.forEach(lbl => {
    if (companySet.has(lbl.company)) byCompany.get(lbl.company).push(lbl);
  });

  renderActressStudioLabels(byCompany);
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
