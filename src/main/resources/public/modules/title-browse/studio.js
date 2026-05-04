// Studio browser: group-row, label catalog, company detail panel.
//
// Exports:
//   loadAndRenderStudioGroupRow(state, titleStudioGroupRow, onSelect)
//   selectStudioGroup(state, slug, titleStudioLabelsEl)
//   showStudioGroupRow(titleStudioDivider, titleStudioGroupRow)
//   hideStudioGroupRow(state, titleStudioDivider, titleStudioGroupRow, titleStudioLabelsEl)

import { esc } from '../utils.js';
import { ensureStudioGroups, ensureTitleLabels, renderTwoColumnStudioPanel } from '../studio-data.js';
import { makeActressCard } from '../cards.js';
import { openActressDetail } from '../actress-detail.js';

// Internal — called by loadAndRenderStudioGroupRow
function _renderStudioGroupRow(state, titleStudioGroupRow, groups, onSelect) {
  titleStudioGroupRow.innerHTML = '';
  groups.forEach(g => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'studio-group-btn' + (g.slug === state.selectedStudioSlug ? ' selected' : '');
    btn.textContent = g.name;
    btn.dataset.slug = g.slug;
    btn.addEventListener('click', () => onSelect(g.slug));
    titleStudioGroupRow.appendChild(btn);
  });
}

function renderActressGridSection(labelCodeSet, containerId, apiUrl) {
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

function selectStudioCompany(company, byCompany, titleStudioLabelsEl) {
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
  renderActressGridSection(labelCodeSet, 'studio-top-actresses',    `/api/titles/top-actresses?labels=${encodeURIComponent(labelCodes)}&limit=10`);
  renderActressGridSection(labelCodeSet, 'studio-newest-actresses', `/api/titles/newest-actresses?labels=${encodeURIComponent(labelCodes)}&limit=10`);
}

export async function selectStudioGroup(state, slug, titleStudioGroupRow, titleStudioLabelsEl) {
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

  renderTwoColumnStudioPanel(
    titleStudioLabelsEl, 'studio-label-detail', byCompany,
    (company, byCompanyArg) => selectStudioCompany(company, byCompanyArg, titleStudioLabelsEl),
    () => { document.getElementById('titles-browse-grid').style.display = 'none'; }
  );
}

export async function loadAndRenderStudioGroupRow(state, titleStudioGroupRow, titleStudioLabelsEl) {
  const groups = await ensureStudioGroups();
  _renderStudioGroupRow(state, titleStudioGroupRow, groups, (slug) => selectStudioGroup(state, slug, titleStudioGroupRow, titleStudioLabelsEl));
  return groups;
}

export function showStudioGroupRow(titleStudioDivider, titleStudioGroupRow) {
  titleStudioDivider.style.display = '';
  titleStudioGroupRow.style.display = '';
}

export function hideStudioGroupRow(state, titleStudioDivider, titleStudioGroupRow, titleStudioLabelsEl) {
  titleStudioDivider.style.display = 'none';
  titleStudioGroupRow.style.display = 'none';
  titleStudioLabelsEl.style.display = 'none';
  state.selectedStudioSlug = null;
}
