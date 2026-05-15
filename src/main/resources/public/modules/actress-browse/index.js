import { esc } from '../utils.js';
import { showView, setActiveGrid, ensureSentinel, updateBreadcrumb, ScrollingGrid } from '../grid.js';
import { pushNav } from '../nav.js';
import { ARCHIVE_VOLUMES, EXHIBITION_VOLUMES } from '../config.js';
import { effectiveCols } from '../grid-cols.js';
import { ensureStudioGroups } from '../studio-data.js';
import { updateCompanyMarquee } from '../studio-data.js';
import { makeActressCardWithNotes, resetNotesState } from './notes.js';
import { renderActressDashboard } from './dashboard.js';
import {
  selectActressStudioGroup,
  loadAndRenderActressStudioGroupRow,
  renderActressGridHeader,
  showActressStudioGroupRow as _showStudioGroupRow,
  hideActressStudioGroupRow as _hideStudioGroupRow,
} from './studio.js';
import {
  ACTRESS_TIERS,
  buildActressTierChips,
  updateActressLandingSelection as _updateLandingSelection,
  actressBrowseLabel as _actressBrowseLabel,
  updateActressBreadcrumb as _updateBreadcrumb,
  showActressColsFilterBar as _showColsFilterBar,
  populateExhibitionCompanyDropdown,
  populateArchivesCompanyDropdown,
  populateTierCompanyDropdown,
  showActressTierRow as _showTierRow,
  hideActressTierRow as _hideTierRow,
  showTierCompanyRow as _showTierCompanyRow,
  showExhibitionRow as _showExhibitionRow,
  hideExhibitionRow as _hideExhibitionRow,
  showArchivesRow as _showArchivesRow,
  hideArchivesRow as _hideArchivesRow,
} from './chips.js';

// ── Notes design tokens (CSS custom properties) ───────────────────────────
// Injected here so no HTML file needs to be modified (additive only).
// The notes.js module injects its own scoped styles; tokens.css provides the
// shared palette (--postit-yellow, etc.) used by the shared icon SVG.
{
  const LINK_ID = 'notes-tokens-css';
  if (!document.getElementById(LINK_ID)) {
    const link = document.createElement('link');
    link.id   = LINK_ID;
    link.rel  = 'stylesheet';
    link.href = '/modules/notes/tokens.css';
    document.head.appendChild(link);
  }
}

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
export { ACTRESS_TIERS };

function createActressBrowseState() {
  return {
    mode: null,
    tierPanelOpen: false,
    lastTier: 'GODDESS',
    studioSlug: null,
    studioGroupName: null,
    studioGroupCompany: null,
    exhibitionCompany: null,
    archivesCompany: null,
    tierCompany: null,
    // Post-It Notes filter: null | 'has_note' | 'no_note'
    notesFilter: null,
    reset() {
      this.mode = null;
      this.lastTier = 'GODDESS';
      this.studioGroupName = null;
      this.studioGroupCompany = null;
    },
  };
}
const state = createActressBrowseState();

/** Getter for external consumers (replaces the previous live-binding export). */
export function getActressBrowseMode() { return state.mode; }

// ── Column count control ──────────────────────────────────────────────────
function applyActressGridCols(cols) {
  if (actressGridEl) actressGridEl.style.gridTemplateColumns = `repeat(${cols}, 1fr)`;
}

// ── Local sub-nav helpers (call chips.js with DOM refs) ───────────────────

function hideTierRow() {
  _hideTierRow(state, actressTierDivider, actressTierRow, actressTierCompanyDivider, actressTierCompanySelect, actressTierCompanyMarquee);
}

function hideExhibition() {
  _hideExhibitionRow(state, actressExhibitionDivider, actressExhibitionRow);
}

function hideArchives() {
  _hideArchivesRow(state, actressArchivesDivider, actressArchivesRow);
}

// ── Exported sub-nav helpers ──────────────────────────────────────────────

export function hideActressStudioGroupRow() {
  _hideStudioGroupRow(state, actressStudioDivider, actressStudioGroupRow, actressStudioGroupHeaderEl, actressStudioLabelsEl);
}

/** Hide all mode-specific sub-nav rows (tier chips, tier company, exhibition, archives). */
export function hideAllActressSubNavRows() {
  hideTierRow();
  hideExhibition();
  hideArchives();
}

// ── Exported breadcrumb / selection helpers ───────────────────────────────

export function updateActressLandingSelection() {
  _updateLandingSelection(state, {
    actressDashboardBtn, actressFavoritesBtn, actressBookmarksBtn,
    actressExhibitionBtn, actressArchivesBtn, actressStudioBtn,
    actressTierBtn, actressTierRow,
    actressTierDivider, actressTierCompanyDivider, actressTierCompanySelect, actressTierCompanyMarquee,
    actressExhibitionDivider, actressExhibitionRow, actressArchivesDivider, actressArchivesRow,
  });
}

export function actressBrowseLabel(modeKey) {
  return _actressBrowseLabel(modeKey, state);
}

export function updateActressBreadcrumb() {
  _updateBreadcrumb(state, updateBreadcrumb, showActressLanding, selectActressBrowseMode);
}

// ── Scrolling grid ────────────────────────────────────────────────────────

// Helper: append ?notes= param when a notes filter is active.
function _appendNotesParam(url) {
  if (state.notesFilter) url += `&notes=${encodeURIComponent(state.notesFilter)}`;
  return url;
}

export const actressScrollGrid = new ScrollingGrid(
  actressGridEl,
  (o, l) => {
    if (state.mode === 'favorites')
      return _appendNotesParam(`/api/actresses?favorites=true&offset=${o}&limit=${l}`);
    if (state.mode === 'bookmarks')
      return _appendNotesParam(`/api/actresses?bookmarks=true&offset=${o}&limit=${l}`);
    if (state.mode === 'exhibition-volumes') {
      let url = `/api/actresses?volumes=${encodeURIComponent(EXHIBITION_VOLUMES)}&offset=${o}&limit=${l}`;
      if (state.exhibitionCompany) url += `&company=${encodeURIComponent(state.exhibitionCompany)}`;
      return _appendNotesParam(url);
    }
    if (state.mode === 'archive-volumes') {
      let url = `/api/actresses?volumes=${encodeURIComponent(ARCHIVE_VOLUMES)}&offset=${o}&limit=${l}`;
      if (state.archivesCompany) url += `&company=${encodeURIComponent(state.archivesCompany)}`;
      return _appendNotesParam(url);
    }
    if (state.mode && state.mode.startsWith('tier-')) {
      const tier = state.mode.slice(5);
      let url = `/api/actresses?tier=${encodeURIComponent(tier)}&offset=${o}&limit=${l}`;
      if (state.tierCompany) url += `&company=${encodeURIComponent(state.tierCompany)}`;
      return _appendNotesParam(url);
    }
    if (state.mode && state.mode.startsWith('studio-group:')) {
      const slug = state.mode.slice('studio-group:'.length);
      let url = `/api/actresses?studioGroup=${encodeURIComponent(slug)}&offset=${o}&limit=${l}`;
      if (state.studioGroupCompany) url += `&company=${encodeURIComponent(state.studioGroupCompany)}`;
      return _appendNotesParam(url);
    }
    return null;
  },
  makeActressCardWithNotes,
  'no actresses'
);

// ── resetActressState — called by showTitlesBrowse and showTitlesView ──────
export function resetActressState() {
  state.reset();
  actressGridHeaderEl.style.display = 'none';
  actressGridHeaderEl.innerHTML = '';
  hideActressStudioGroupRow();
  hideTierRow();
  hideExhibition();
  hideArchives();
  updateActressLandingSelection();
  // Reset notes filter chip to "Any" and clear notes state.
  state.notesFilter = null;
  resetNotesState();
  _syncNotesChip();
}

// ── Browse mode selection ─────────────────────────────────────────────────
export async function selectActressBrowseMode(modeKey) {
  pushNav({ view: 'actresses', mode: modeKey }, 'actresses/' + encodeURIComponent(modeKey));
  state.mode = modeKey;
  document.getElementById('av-btn')?.classList.remove('active');
  document.getElementById('action-btn')?.classList.remove('active');
  if (modeKey === 'dashboard') {
    hideTierRow();
    hideActressStudioGroupRow();
    hideExhibition();
    hideArchives();
    hideActressNotesFilterRow();
    actressGridHeaderEl.style.display = 'none';
    actressGridHeaderEl.innerHTML = '';
    state.studioGroupName = null;
    state.studioGroupCompany = null;
    updateActressLandingSelection();
    updateActressBreadcrumb();
    actressesBtn.classList.add('active');
    showView('actresses');
    actressGridEl.style.display = 'none';
    actressDashboardEl.style.display = 'block';
    await renderActressDashboard(actressDashboardEl, (slug) => selectActressBrowseMode(`studio-group:${slug}`));
    return;
  }
  actressDashboardEl.style.display = 'none';
  if (modeKey === 'studio') {
    hideTierRow();
    hideExhibition();
    hideArchives();
    hideActressNotesFilterRow();
    actressGridHeaderEl.style.display = 'none';
    actressGridHeaderEl.innerHTML = '';
    state.studioGroupName = null;
    state.studioGroupCompany = null;
    updateActressLandingSelection();
    updateActressBreadcrumb();
    actressesBtn.classList.add('active');
    showView('actresses');
    actressGridEl.style.display = 'none';
    _showStudioGroupRow(actressStudioDivider, actressStudioGroupRow);
    loadAndRenderActressStudioGroupRow(
      state, actressStudioGroupRow, actressStudioGroupHeaderEl, actressStudioLabelsEl, actressGridEl,
      (slug) => selectActressStudioGroup(
        state, slug, actressStudioGroupRow, actressStudioGroupHeaderEl, actressStudioLabelsEl, actressGridEl,
        (s) => selectActressBrowseMode(`studio-group:${s}`)
      ),
      (slug) => selectActressBrowseMode(`studio-group:${slug}`)
    ).then(groups => {
      if (groups.length > 0) {
        selectActressStudioGroup(
          state, state.studioSlug || groups[0].slug,
          actressStudioGroupRow, actressStudioGroupHeaderEl, actressStudioLabelsEl, actressGridEl,
          (s) => selectActressBrowseMode(`studio-group:${s}`)
        );
      }
    });
    return;
  }
  hideActressStudioGroupRow();
  if (modeKey === 'exhibition-volumes') {
    state.exhibitionCompany = null;
    hideTierRow();
    actressGridHeaderEl.style.display = 'none';
    actressGridHeaderEl.innerHTML = '';
    state.studioGroupName = null;
    state.studioGroupCompany = null;
    _showExhibitionRow(actressExhibitionDivider, actressExhibitionRow);
    populateExhibitionCompanyDropdown(state, actressExhibitionSelect);
    hideArchives();
  } else if (modeKey === 'archive-volumes') {
    state.archivesCompany = null;
    hideTierRow();
    actressGridHeaderEl.style.display = 'none';
    actressGridHeaderEl.innerHTML = '';
    state.studioGroupName = null;
    state.studioGroupCompany = null;
    _showArchivesRow(actressArchivesDivider, actressArchivesRow);
    populateArchivesCompanyDropdown(state, actressArchivesSelect);
    hideExhibition();
  } else {
    hideExhibition();
    hideArchives();
  }
  if (modeKey.startsWith('studio-group:')) {
    const slug = modeKey.slice('studio-group:'.length);
    state.studioSlug = slug;
    state.studioGroupCompany = null;
    const groups = await ensureStudioGroups();
    const group = groups.find(g => g.slug === slug);
    state.studioGroupName = group ? group.name : slug;
    let companyCounts;
    try {
      const resp = await fetch(`/api/studio-groups/${encodeURIComponent(slug)}/companies`);
      companyCounts = resp.ok ? await resp.json() : null;
    } catch {
      companyCounts = null;
    }
    renderActressGridHeader(state, group, slug, companyCounts, actressGridHeaderEl, actressScrollGrid, selectActressBrowseMode);
    hideTierRow();
  } else {
    state.studioGroupName = null;
    state.studioGroupCompany = null;
    actressGridHeaderEl.style.display = 'none';
    actressGridHeaderEl.innerHTML = '';
    if (modeKey.startsWith('tier-')) {
      state.lastTier = modeKey.slice(5);
      state.tierCompany = null;
      _showTierRow(state, actressTierDivider, actressTierRow);
      _showTierCompanyRow(actressTierCompanyDivider, actressTierCompanySelect);
      populateTierCompanyDropdown(state, actressTierCompanySelect);
    } else {
      hideTierRow();
    }
  }
  updateActressLandingSelection();
  updateActressBreadcrumb();
  actressesBtn.classList.add('active');
  showView('actresses');
  showActressNotesFilterRow();
  _showColsFilterBar(state, actressGridHeaderEl, actressGridEl, applyActressGridCols);
  applyActressGridCols(effectiveCols());
  setActiveGrid(actressScrollGrid);
  resetNotesState();
  actressScrollGrid.reset();
  ensureSentinel();
  await actressScrollGrid.loadMore();
}

export function showActressLanding() {
  selectActressBrowseMode('dashboard');
}

// ── Button wiring ─────────────────────────────────────────────────────────

buildActressTierChips(state, actressTierRow, actressTierCompanyDivider, selectActressBrowseMode);

actressDashboardBtn.addEventListener('click',   () => selectActressBrowseMode('dashboard'));
actressFavoritesBtn.addEventListener('click',   () => selectActressBrowseMode('favorites'));
actressBookmarksBtn.addEventListener('click',   () => selectActressBrowseMode('bookmarks'));
actressExhibitionBtn.addEventListener('click',  () => selectActressBrowseMode('exhibition-volumes'));
actressArchivesBtn.addEventListener('click',    () => selectActressBrowseMode('archive-volumes'));
actressStudioBtn.addEventListener('click',    () => selectActressBrowseMode('studio'));
actressTierBtn.addEventListener('click', () => selectActressBrowseMode(`tier-${state.lastTier}`));

actressesBtn.addEventListener('click', e => {
  e.stopPropagation();
  selectActressBrowseMode('dashboard');
});

// ── Exhibition/Archives/Tier company select wiring ────────────────────────

actressExhibitionSelect.addEventListener('change', () => {
  state.exhibitionCompany = actressExhibitionSelect.value || null;
  updateCompanyMarquee(actressExhibitionMarquee, state.exhibitionCompany);
  document.getElementById('sentinel')?.remove();
  actressScrollGrid.reset();
  ensureSentinel();
  actressScrollGrid.loadMore();
});

actressArchivesSelect.addEventListener('change', () => {
  state.archivesCompany = actressArchivesSelect.value || null;
  updateCompanyMarquee(actressArchivesMarquee, state.archivesCompany);
  document.getElementById('sentinel')?.remove();
  actressScrollGrid.reset();
  ensureSentinel();
  actressScrollGrid.loadMore();
});

actressTierCompanySelect.addEventListener('change', () => {
  state.tierCompany = actressTierCompanySelect.value || null;
  updateCompanyMarquee(actressTierCompanyMarquee, state.tierCompany);
  document.getElementById('sentinel')?.remove();
  actressScrollGrid.reset();
  ensureSentinel();
  actressScrollGrid.loadMore();
});

// ── Notes filter chip (tri-state: Any / Has note / No note) ──────────────
//
// The actress-landing area has a special-row with mode buttons, but no
// existing filter-chip row.  We inject a dedicated row and a thin divider
// BEFORE #actress-browse-filter-bar (the cols-slider target) so the chip
// is always visible whenever the actress grid is shown and is never
// clobbered by the cols-slider innerHTML reset in chips.js.
//
// The chip cycles: Any → Has note → No note → Any.
// Styling matches actress-landing-special buttons but with a fixed-width
// compact look so it reads as a filter, not a navigation mode.

const NOTES_FILTER_VALUES = [null, 'has_note', 'no_note'];
const NOTES_FILTER_LABELS = {
  null:       'Notes: Any',
  has_note:   'Notes: Has note',
  no_note:    'Notes: No note',
};

// Build the row — injected once, then kept stable.
const _notesFilterDivider = document.createElement('div');
_notesFilterDivider.className = 'actress-landing-divider actress-notes-filter-divider';
_notesFilterDivider.style.display = 'none';

const _notesFilterRow = document.createElement('div');
_notesFilterRow.className = 'actress-landing-row actress-notes-filter-row';
_notesFilterRow.style.display = 'none';

const _notesChipEl = document.createElement('button');
_notesChipEl.type = 'button';
_notesChipEl.className = 'actress-notes-filter-chip';
_notesChipEl.textContent = NOTES_FILTER_LABELS[null];
_notesFilterRow.appendChild(_notesChipEl);

// Insert before #actress-browse-filter-bar so it is stable and unaffected
// by the filter-bar innerHTML reset in chips.js.
const _filterBarEl = document.getElementById('actress-browse-filter-bar');
if (_filterBarEl && _filterBarEl.parentNode) {
  _filterBarEl.parentNode.insertBefore(_notesFilterDivider, _filterBarEl);
  _filterBarEl.parentNode.insertBefore(_notesFilterRow, _filterBarEl);
}

// Sync chip label + selected state from state.notesFilter.
function _syncNotesChip() {
  const key = state.notesFilter === 'has_note' ? 'has_note'
            : state.notesFilter === 'no_note'   ? 'no_note'
            : null;
  _notesChipEl.textContent = NOTES_FILTER_LABELS[key];
  _notesChipEl.classList.toggle('actress-notes-chip-active', key !== null);
}

// Show/hide the filter row.  Called from selectActressBrowseMode when the
// grid is visible (mode is not dashboard/studio).
export function showActressNotesFilterRow() {
  _notesFilterDivider.style.display = '';
  _notesFilterRow.style.display = '';
}

export function hideActressNotesFilterRow() {
  _notesFilterDivider.style.display = 'none';
  _notesFilterRow.style.display = 'none';
}

// Chip click — cycle through the three states.
_notesChipEl.addEventListener('click', () => {
  const currentIdx = NOTES_FILTER_VALUES.indexOf(state.notesFilter);
  const nextIdx = (currentIdx + 1) % NOTES_FILTER_VALUES.length;
  state.notesFilter = NOTES_FILTER_VALUES[nextIdx];
  _syncNotesChip();
  resetNotesState();
  document.getElementById('sentinel')?.remove();
  actressScrollGrid.reset();
  ensureSentinel();
  actressScrollGrid.loadMore();
});
