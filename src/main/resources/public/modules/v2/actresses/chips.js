// actresses/chips.js — Tier chips, company dropdowns, sub-nav row visibility,
// breadcrumb label helpers, and column-count slider.
//
// All functions are pure helpers that accept state + DOM refs as arguments.
// No module-level DOM queries — everything scoped to the rootEl provided by index.js.

import { ensureTitleLabels } from '../../studio-data.js';
import { effectiveCols, COLS_VALUES } from '../../grid-cols.js';

// ── Constants ─────────────────────────────────────────────────────────────

export const ACTRESS_TIERS = ['GODDESS', 'SUPERSTAR', 'POPULAR', 'MINOR', 'LIBRARY'];

// ── Column slider ─────────────────────────────────────────────────────────

export function buildColsSlider(rowEl, colsSliderStorageKey, applyFn) {
  // Remove any existing slider first
  rowEl.querySelector('.act-cols-ctrl')?.remove();

  const current = effectiveCols(colsSliderStorageKey);
  const idx     = Math.max(0, COLS_VALUES.indexOf(current));

  const ctrl = document.createElement('div');
  ctrl.className = 'act-cols-ctrl';
  ctrl.innerHTML = `
    <span class="act-cols-caption">Cols</span>
    <input type="range" class="act-cols-slider" min="0" max="${COLS_VALUES.length - 1}" value="${idx}" step="1">
    <span class="act-cols-label">${COLS_VALUES[idx]}</span>
  `;
  rowEl.appendChild(ctrl);

  const slider = ctrl.querySelector('.act-cols-slider');
  const label  = ctrl.querySelector('.act-cols-label');
  slider.addEventListener('input', () => {
    const cols = COLS_VALUES[+slider.value] || current;
    label.textContent = cols;
    try { localStorage.setItem(colsSliderStorageKey, cols); } catch (_) {}
    applyFn(cols);
  });
}

// ── Tier chips ────────────────────────────────────────────────────────────

/**
 * Build tier chip buttons in tierRowEl.
 * @param {HTMLElement} tierRowEl     — row container
 * @param {HTMLElement|null} beforeEl — insert chips before this element (e.g. the company divider)
 * @param {function} selectMode
 */
export function buildTierChips(tierRowEl, beforeEl, selectMode) {
  tierRowEl.querySelectorAll('.act-tier-chip').forEach(el => el.remove());
  for (const tier of ACTRESS_TIERS) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `act-tier-chip act-tier-chip-${tier.toLowerCase()}`;
    btn.dataset.tier = tier;
    btn.textContent = tier.toLowerCase();
    btn.addEventListener('click', () => selectMode(`tier-${tier}`));
    if (beforeEl && beforeEl.parentNode === tierRowEl) {
      tierRowEl.insertBefore(btn, beforeEl);
    } else {
      tierRowEl.appendChild(btn);
    }
  }
}

export function updateTierChips(tierRowEl, state) {
  tierRowEl.querySelectorAll('.act-tier-chip').forEach(btn => {
    btn.classList.toggle('on', state.mode === `tier-${btn.dataset.tier}`);
  });
}

// ── Company dropdown helpers ───────────────────────────────────────────────

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[c]));
}

async function populateCompanySelect(selectEl, currentValue) {
  const labels = await ensureTitleLabels();
  const companies = [...new Set(labels.map(l => l.company).filter(Boolean))].sort();
  selectEl.innerHTML =
    '<option value="">All Companies</option>' +
    companies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('');
  selectEl.value = currentValue || '';
}

export async function populateExhibitionCompanyDropdown(state, selectEl) {
  await populateCompanySelect(selectEl, state.exhibitionCompany);
}

export async function populateArchivesCompanyDropdown(state, selectEl) {
  await populateCompanySelect(selectEl, state.archivesCompany);
}

export async function populateTierCompanyDropdown(state, selectEl) {
  await populateCompanySelect(selectEl, state.tierCompany);
}

// ── Breadcrumb label helper ───────────────────────────────────────────────

export function actressBrowseLabel(modeKey, state) {
  if (!modeKey) return '';
  if (modeKey === 'dashboard')          return 'Dashboard';
  if (modeKey === 'favorites')          return 'Favorites';
  if (modeKey === 'bookmarks')          return 'Bookmarks';
  if (modeKey === 'exhibition-volumes') return 'Exhibition';
  if (modeKey === 'archive-volumes')    return 'Archives';
  if (modeKey === 'studio')             return 'Studio';
  if (modeKey.startsWith('tier-'))      return modeKey.slice(5).toLowerCase();
  if (modeKey.startsWith('studio-group:')) return state.studioGroupName || 'Studio Group';
  return modeKey;
}
