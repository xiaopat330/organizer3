// actresses/studio.js — Studio mode: group chip row, two-column company/label
// catalog panel, and studio-group filtered grid header.
//
// Two sub-states:
//   1. Catalog view: group chips + two-column company-label panel (no grid)
//   2. Grid view: filtered actress grid with group header + company dropdown + "← label catalog" button

import { ensureStudioGroups, ensureTitleLabels } from '../../studio-data.js';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[c]));
}

// ── Group row ─────────────────────────────────────────────────────────────

export async function renderStudioGroupRow(groupRowEl, state, onSelect) {
  const groups = await ensureStudioGroups();
  groupRowEl.innerHTML = '';
  groups.forEach(g => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'act-studio-group-btn' + (g.slug === state.studioSlug ? ' on' : '');
    btn.textContent = g.name;
    btn.dataset.slug = g.slug;
    btn.addEventListener('click', () => onSelect(g.slug));
    groupRowEl.appendChild(btn);
  });
  return groups;
}

export function updateGroupRowSelection(groupRowEl, slug) {
  groupRowEl.querySelectorAll('.act-studio-group-btn').forEach(btn => {
    btn.classList.toggle('on', btn.dataset.slug === slug);
  });
}

// ── Company detail selection ──────────────────────────────────────────────

function selectStudioCompany(company, byCompany, labelsEl) {
  labelsEl.querySelectorAll('.act-studio-company-item').forEach(el => {
    el.classList.toggle('on', el.dataset.company === company);
  });

  const detailEl = labelsEl.querySelector('.act-studio-label-detail') ||
                   document.getElementById('act-studio-label-detail');
  if (!detailEl) return;

  const labels = byCompany.get(company) || [];
  const companyDesc = labels.length > 0 && labels[0].companyDescription ? labels[0].companyDescription : null;

  let html = `<div class="act-studio-detail-heading">${esc(company)}</div>`;
  if (companyDesc) html += `<div class="act-studio-detail-company-desc">${esc(companyDesc)}</div>`;

  const byLabel = new Map();
  labels.forEach(lbl => {
    const key = lbl.labelName || lbl.code;
    if (!byLabel.has(key)) byLabel.set(key, []);
    byLabel.get(key).push(lbl);
  });

  html += '<div class="act-studio-detail-section-label">product codes</div>';
  html += '<div class="act-studio-detail-label-list">';
  byLabel.forEach((codes, labelName) => {
    html += `<div class="act-studio-detail-label-group">
      <div class="act-studio-detail-label-name">${esc(labelName)}</div>
      <div class="act-studio-detail-code-rows">`;
    codes.forEach(lbl => {
      html += `<div class="act-studio-detail-code-row">
        <span class="act-studio-detail-code-badge">${esc(lbl.code)}</span>
        ${lbl.description ? `<span class="act-studio-detail-label-desc">${esc(lbl.description)}</span>` : ''}
      </div>`;
    });
    html += '</div></div>';
  });
  html += '</div>';

  detailEl.innerHTML = html;
}

// ── Group catalog panel ───────────────────────────────────────────────────

function renderGroupHeader(group, groupHeaderEl, onViewActresses) {
  groupHeaderEl.style.display = 'flex';
  groupHeaderEl.innerHTML = `
    <span class="act-studio-group-header-name">${esc(group.name)}</span>
    <button type="button" class="act-studio-group-header-action">
      View actresses in this group →
    </button>
  `;
  groupHeaderEl.querySelector('.act-studio-group-header-action').addEventListener('click', () => {
    onViewActresses(group.slug);
  });
}

/**
 * Select a studio group in catalog mode: render group header + two-column label panel.
 * @param {HTMLElement} groupRowEl     — the group chip row
 * @param {HTMLElement} groupHeaderEl  — the header bar beneath the row
 * @param {HTMLElement} labelsEl       — the flex container for the two-column panel
 * @param {HTMLElement} gridEl         — actress grid (hidden in catalog mode)
 * @param {object}      state
 * @param {string}      slug
 * @param {function}    onViewActresses — called(slug) when "View actresses" is clicked
 */
export async function selectStudioGroup(groupRowEl, groupHeaderEl, labelsEl, gridEl, state, slug, onViewActresses) {
  state.studioSlug = slug;
  updateGroupRowSelection(groupRowEl, slug);

  const groups = await ensureStudioGroups();
  const group  = groups.find(g => g.slug === slug);
  if (!group) return;

  renderGroupHeader(group, groupHeaderEl, onViewActresses);

  const allLabels  = await ensureTitleLabels();
  const companySet = new Set(group.companies);
  const byCompany  = new Map();
  group.companies.forEach(c => byCompany.set(c, []));
  allLabels.forEach(lbl => {
    if (companySet.has(lbl.company)) byCompany.get(lbl.company).push(lbl);
  });

  // Re-use renderTwoColumnStudioPanel but give the detail div our own id
  const wrappedLabelsEl = labelsEl;
  wrappedLabelsEl.innerHTML = '';

  const listEl = document.createElement('div');
  listEl.className = 'act-studio-company-list';

  let firstCompany = null;
  byCompany.forEach((labels, company) => {
    if (labels.length === 0) return;
    if (!firstCompany) firstCompany = company;
    const item = document.createElement('div');
    item.className = 'act-studio-company-item';
    item.dataset.company = company;
    item.textContent = company;
    item.addEventListener('click', () => selectStudioCompany(company, byCompany, wrappedLabelsEl));
    listEl.appendChild(item);
  });

  const detailEl = document.createElement('div');
  detailEl.className = 'act-studio-label-detail';
  detailEl.id = 'act-studio-label-detail';

  wrappedLabelsEl.appendChild(listEl);
  wrappedLabelsEl.appendChild(detailEl);
  wrappedLabelsEl.style.display = 'flex';

  gridEl.style.display = 'none';

  if (firstCompany) selectStudioCompany(firstCompany, byCompany, wrappedLabelsEl);
}

// ── Grid header (for studio-group: mode) ─────────────────────────────────

/**
 * Render the grid header for a studio-group mode (company filter + "← label catalog" link).
 * @param {HTMLElement}   gridHeaderEl
 * @param {object}        state
 * @param {object|null}   group          — full group object { name, companies, slug }
 * @param {string}        slug
 * @param {Array|null}    companyCounts  — [{ company, titleCount }]
 * @param {ScrollingGrid} scrollGrid     — has .reset() + .loadMore()
 * @param {function}      selectMode     — mode dispatcher callback
 * @param {function}      ensureSentinel — re-add scroll sentinel
 */
export function renderActressGridHeader(gridHeaderEl, state, group, slug, companyCounts, scrollGrid, selectMode, ensureSentinel) {
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

  gridHeaderEl.style.display = 'flex';
  gridHeaderEl.innerHTML = `
    <span class="act-grid-header-name">${esc(name)}</span>
    <div class="act-grid-header-right">
      <label class="act-grid-header-filter">
        <span class="act-grid-header-filter-label">Filter:</span>
        <select class="act-grid-header-filter-select" id="act-grid-header-filter">
          ${optionsHtml}
        </select>
      </label>
      <button type="button" class="act-grid-header-action" id="act-grid-header-action">
        ← View label catalog
      </button>
    </div>
  `;

  const select = gridHeaderEl.querySelector('#act-grid-header-filter');
  select.value = state.studioGroupCompany || '';
  select.addEventListener('change', () => {
    state.studioGroupCompany = select.value || null;
    scrollGrid.reset();
    ensureSentinel();
    scrollGrid.loadMore();
  });

  gridHeaderEl.querySelector('#act-grid-header-action').addEventListener('click', () => {
    state.studioSlug = slug;
    selectMode('studio');
  });
}
