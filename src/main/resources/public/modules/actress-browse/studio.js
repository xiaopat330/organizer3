// Actress studio browser: group row, label catalog, company detail panel,
// studio-group filtered grid header.
//
// Exports:
//   loadAndRenderActressStudioGroupRow(state, actressStudioGroupRow, ...)
//   selectActressStudioGroup(state, slug, actressStudioGroupRow, actressStudioGroupHeaderEl, actressStudioLabelsEl, actressGridEl)
//   renderActressGridHeader(state, group, slug, companyCounts, actressGridHeaderEl, actressScrollGrid, ensureSentinel, selectMode)
//   showActressStudioGroupRow(actressStudioDivider, actressStudioGroupRow)
//   hideActressStudioGroupRow(state, actressStudioDivider, actressStudioGroupRow, actressStudioGroupHeaderEl, actressStudioLabelsEl)

import { esc } from '../utils.js';
import { ensureStudioGroups, ensureTitleLabels, renderTwoColumnStudioPanel } from '../studio-data.js';
import { ensureSentinel } from '../grid.js';

// Internal — called by loadAndRenderActressStudioGroupRow
function _renderActressStudioGroupRow(state, actressStudioGroupRow, groups, onSelect) {
  actressStudioGroupRow.innerHTML = '';
  groups.forEach(g => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'studio-group-btn actress-studio-group-btn' +
      (g.slug === state.studioSlug ? ' selected' : '');
    btn.textContent = g.name;
    btn.dataset.slug = g.slug;
    btn.addEventListener('click', () => onSelect(g.slug));
    actressStudioGroupRow.appendChild(btn);
  });
}

function selectActressStudioCompany(company, byCompany, actressStudioLabelsEl) {
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

function renderActressStudioGroupHeader(group, actressStudioGroupHeaderEl, actressStudioLabelsEl, onViewActresses) {
  actressStudioGroupHeaderEl.style.display = 'flex';
  actressStudioGroupHeaderEl.innerHTML = `
    <span class="studio-group-header-name">${esc(group.name)}</span>
    <button type="button" class="studio-group-header-action" id="studio-group-header-action">
      View actresses in this group →
    </button>
  `;
  document.getElementById('studio-group-header-action').addEventListener('click', () => {
    onViewActresses(group.slug);
  });
}

export async function selectActressStudioGroup(state, slug, actressStudioGroupRow, actressStudioGroupHeaderEl, actressStudioLabelsEl, actressGridEl, onViewActresses) {
  state.studioSlug = slug;
  actressStudioGroupRow.querySelectorAll('.actress-studio-group-btn').forEach(btn => {
    btn.classList.toggle('selected', btn.dataset.slug === slug);
  });

  const groups = await ensureStudioGroups();
  const group = groups.find(g => g.slug === slug);
  if (!group) return;

  renderActressStudioGroupHeader(group, actressStudioGroupHeaderEl, actressStudioLabelsEl, onViewActresses);

  const allLabels = await ensureTitleLabels();
  const companySet = new Set(group.companies);

  const byCompany = new Map();
  group.companies.forEach(c => byCompany.set(c, []));
  allLabels.forEach(lbl => {
    if (companySet.has(lbl.company)) byCompany.get(lbl.company).push(lbl);
  });

  renderTwoColumnStudioPanel(
    actressStudioLabelsEl, 'actress-studio-label-detail', byCompany,
    (company, byCompanyArg) => selectActressStudioCompany(company, byCompanyArg, actressStudioLabelsEl),
    () => { actressGridEl.style.display = 'none'; }
  );
}

export async function loadAndRenderActressStudioGroupRow(state, actressStudioGroupRow, actressStudioGroupHeaderEl, actressStudioLabelsEl, actressGridEl, onSelect, onViewActresses) {
  const groups = await ensureStudioGroups();
  _renderActressStudioGroupRow(state, actressStudioGroupRow, groups, onSelect);
  return groups;
}

export function renderActressGridHeader(state, group, slug, companyCounts, actressGridHeaderEl, actressScrollGrid, selectMode) {
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
  select.value = state.studioGroupCompany || '';
  select.addEventListener('change', () => {
    state.studioGroupCompany = select.value || null;
    document.getElementById('sentinel')?.remove();
    actressScrollGrid.reset();
    ensureSentinel();
    actressScrollGrid.loadMore();
  });
  document.getElementById('actress-grid-header-action').addEventListener('click', () => {
    state.studioSlug = slug;
    selectMode('studio');
  });
}

export function showActressStudioGroupRow(actressStudioDivider, actressStudioGroupRow) {
  actressStudioDivider.style.display = '';
  actressStudioGroupRow.style.display = '';
}

export function hideActressStudioGroupRow(state, actressStudioDivider, actressStudioGroupRow, actressStudioGroupHeaderEl, actressStudioLabelsEl) {
  actressStudioDivider.style.display = 'none';
  actressStudioGroupRow.style.display = 'none';
  actressStudioGroupHeaderEl.style.display = 'none';
  actressStudioGroupHeaderEl.innerHTML = '';
  actressStudioLabelsEl.style.display = 'none';
  state.studioSlug = null;
}
