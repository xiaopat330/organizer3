// Tier chips, company dropdowns (tier/exhibition/archives), sub-nav row visibility,
// breadcrumb, landing selection, and column count bar wiring.
//
// Exports:
//   ACTRESS_TIERS
//   buildActressTierChips(state, actressTierRow, actressTierCompanyDivider, selectMode)
//   updateActressLandingSelection(state, domRefs)
//   actressBrowseLabel(modeKey, state)
//   updateActressBreadcrumb(state, domRefs, updateBreadcrumb, showActressLanding)
//   showActressColsFilterBar(state, domRefs, effectiveCols, actressGridEl)
//   populateExhibitionCompanyDropdown(state, actressExhibitionSelect)
//   populateArchivesCompanyDropdown(state, actressArchivesSelect)
//   populateTierCompanyDropdown(state, actressTierCompanySelect)
//   showActressTierRow / hideActressTierRow / showTierCompanyRow / hideTierCompanyRow
//   showExhibitionRow / hideExhibitionRow / showArchivesRow / hideArchivesRow
//   hideAllActressSubNavRows

import { esc } from '../utils.js';
import { ensureTitleLabels } from '../studio-data.js';
import { updateCompanyMarquee } from '../studio-data.js';
import { effectiveCols, colsSliderHtml, wireColsSlider, injectColsSlider } from '../grid-cols.js';
import { ensureSentinel } from '../grid.js';

export const ACTRESS_TIERS = ['GODDESS', 'SUPERSTAR', 'POPULAR', 'MINOR', 'LIBRARY'];

// ── Tier row ──────────────────────────────────────────────────────────────

export function showActressTierRow(state, actressTierDivider, actressTierRow) {
  state.tierPanelOpen = true;
  actressTierDivider.style.display = '';
  actressTierRow.style.display = '';
}

export function hideActressTierRow(state, actressTierDivider, actressTierRow, actressTierCompanyDivider, actressTierCompanySelect, actressTierCompanyMarquee) {
  state.tierPanelOpen = false;
  actressTierDivider.style.display = 'none';
  actressTierRow.style.display = 'none';
  hideTierCompanyRow(state, actressTierCompanyDivider, actressTierCompanySelect, actressTierCompanyMarquee);
}

export function showTierCompanyRow(actressTierCompanyDivider, actressTierCompanySelect) {
  actressTierCompanyDivider.style.display = '';
  actressTierCompanySelect.style.display = '';
}

export function hideTierCompanyRow(state, actressTierCompanyDivider, actressTierCompanySelect, actressTierCompanyMarquee) {
  actressTierCompanyDivider.style.display = 'none';
  actressTierCompanySelect.style.display = 'none';
  actressTierCompanyMarquee.style.display = 'none';
  state.tierCompany = null;
}

// ── Exhibition/Archives rows ──────────────────────────────────────────────

export function showExhibitionRow(actressExhibitionDivider, actressExhibitionRow) {
  actressExhibitionDivider.style.display = '';
  actressExhibitionRow.style.display = '';
}

export function hideExhibitionRow(state, actressExhibitionDivider, actressExhibitionRow) {
  actressExhibitionDivider.style.display = 'none';
  actressExhibitionRow.style.display = 'none';
  state.exhibitionCompany = null;
}

export function showArchivesRow(actressArchivesDivider, actressArchivesRow) {
  actressArchivesDivider.style.display = '';
  actressArchivesRow.style.display = '';
}

export function hideArchivesRow(state, actressArchivesDivider, actressArchivesRow) {
  actressArchivesDivider.style.display = 'none';
  actressArchivesRow.style.display = 'none';
  state.archivesCompany = null;
}

export function hideAllActressSubNavRows(state, refs) {
  hideActressTierRow(state, refs.actressTierDivider, refs.actressTierRow, refs.actressTierCompanyDivider, refs.actressTierCompanySelect, refs.actressTierCompanyMarquee);
  hideExhibitionRow(state, refs.actressExhibitionDivider, refs.actressExhibitionRow);
  hideArchivesRow(state, refs.actressArchivesDivider, refs.actressArchivesRow);
}

// ── Company dropdowns ─────────────────────────────────────────────────────

export async function populateExhibitionCompanyDropdown(state, actressExhibitionSelect) {
  const labels = await ensureTitleLabels();
  const companies = [...new Set(labels.map(l => l.company).filter(Boolean))].sort();
  actressExhibitionSelect.innerHTML =
    '<option value="">All Companies</option>' +
    companies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
  actressExhibitionSelect.value = state.exhibitionCompany || '';
}

export async function populateArchivesCompanyDropdown(state, actressArchivesSelect) {
  const labels = await ensureTitleLabels();
  const companies = [...new Set(labels.map(l => l.company).filter(Boolean))].sort();
  actressArchivesSelect.innerHTML =
    '<option value="">All Companies</option>' +
    companies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
  actressArchivesSelect.value = state.archivesCompany || '';
}

export async function populateTierCompanyDropdown(state, actressTierCompanySelect) {
  const labels = await ensureTitleLabels();
  const companies = [...new Set(labels.map(l => l.company).filter(Boolean))].sort();
  actressTierCompanySelect.innerHTML =
    '<option value="">All Companies</option>' +
    companies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
  actressTierCompanySelect.value = state.tierCompany || '';
}

// ── Tier chips ────────────────────────────────────────────────────────────

export function buildActressTierChips(state, actressTierRow, actressTierCompanyDivider, selectMode) {
  actressTierRow.querySelectorAll('.actress-landing-tier').forEach(el => el.remove());
  for (const tier of ACTRESS_TIERS) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `actress-landing-tier tier-chip-${tier}`;
    btn.dataset.tier = tier;
    btn.textContent = tier.toLowerCase();
    btn.addEventListener('click', () => selectMode(`tier-${tier}`));
    actressTierRow.insertBefore(btn, actressTierCompanyDivider);
  }
}

// ── Landing selection ─────────────────────────────────────────────────────

export function updateActressLandingSelection(state, refs) {
  refs.actressDashboardBtn.classList.toggle('selected', state.mode === 'dashboard');
  refs.actressFavoritesBtn.classList.toggle('selected', state.mode === 'favorites');
  refs.actressBookmarksBtn.classList.toggle('selected', state.mode === 'bookmarks');
  refs.actressExhibitionBtn.classList.toggle('selected', state.mode === 'exhibition-volumes');
  refs.actressArchivesBtn.classList.toggle('selected',  state.mode === 'archive-volumes');
  refs.actressStudioBtn.classList.toggle('selected',
    state.mode === 'studio' || (state.mode || '').startsWith('studio-group:'));
  refs.actressTierBtn.classList.toggle('selected', state.tierPanelOpen);
  refs.actressTierRow.querySelectorAll('.actress-landing-tier').forEach(btn => {
    btn.classList.toggle('selected', state.mode === `tier-${btn.dataset.tier}`);
  });
}

// ── Breadcrumb label helper ───────────────────────────────────────────────

export function actressBrowseLabel(modeKey, state) {
  if (!modeKey) return '';
  if (modeKey === 'dashboard')       return 'Dashboard';
  if (modeKey === 'favorites')          return 'Favorites';
  if (modeKey === 'bookmarks')          return 'Bookmarks';
  if (modeKey === 'exhibition-volumes') return 'Exhibition';
  if (modeKey === 'archive-volumes')    return 'Archive';
  if (modeKey === 'studio')          return 'Studio';
  if (modeKey.startsWith('tier-'))   return modeKey.slice(5).toLowerCase();
  if (modeKey.startsWith('studio-group:')) return state.studioGroupName || 'Studio Group';
  return modeKey;
}

export function updateActressBreadcrumb(state, updateBreadcrumbFn, showActressLanding, selectMode) {
  const crumbs = [{ label: 'Actresses', action: () => showActressLanding() }];
  if (state.mode && state.mode !== 'dashboard') {
    if (state.mode.startsWith('studio-group:')) {
      crumbs.push({ label: 'Studio', action: () => selectMode('studio') });
      crumbs.push({ label: actressBrowseLabel(state.mode, state) });
    } else {
      crumbs.push({ label: actressBrowseLabel(state.mode, state) });
    }
  }
  updateBreadcrumbFn(crumbs);
}

// ── Column count filter bar ───────────────────────────────────────────────

const ACTRESS_SLIDER_IDS = ['actress-cols-control', 'actress-cols-slider', 'actress-cols-label'];

export function showActressColsFilterBar(state, actressGridHeaderEl, actressGridEl, applyActressGridCols) {
  const [controlId, sliderId, labelId] = ACTRESS_SLIDER_IDS;
  if (state.mode === 'exhibition-volumes') {
    const row = document.getElementById('actress-exhibition-row');
    if (row) { injectColsSlider(row, controlId, sliderId, labelId, applyActressGridCols); return; }
  }
  if (state.mode === 'archive-volumes') {
    const row = document.getElementById('actress-archives-row');
    if (row) { injectColsSlider(row, controlId, sliderId, labelId, applyActressGridCols); return; }
  }
  if (state.mode && state.mode.startsWith('tier-')) {
    const row = document.getElementById('actress-landing-tier-row');
    if (row) { injectColsSlider(row, controlId, sliderId, labelId, applyActressGridCols); return; }
  }
  if (state.mode && state.mode.startsWith('studio-group:')) {
    const right = actressGridHeaderEl.querySelector('.grid-header-right');
    if (right) { injectColsSlider(right, controlId, sliderId, labelId, applyActressGridCols); return; }
  }
  const bar = document.getElementById('actress-browse-filter-bar');
  if (!bar) return;
  bar.innerHTML = colsSliderHtml(effectiveCols(), controlId, sliderId, labelId);
  bar.style.display = '';
  wireColsSlider(sliderId, labelId, applyActressGridCols);
}
