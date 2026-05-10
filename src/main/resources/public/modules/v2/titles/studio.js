/* ─────────────────────────────────────────────────────────────────────
   Titles v2 — Studio mode
   Renders: group-row (tab strip), two-column panel (company list |
   company detail: description, Top 10 actresses, Newest Actresses,
   per-label code+description listing).
   Endpoints: /api/titles/studios, /api/titles/labels,
              /api/titles/top-actresses, /api/titles/newest-actresses,
              /api/actresses?ids=...
   ───────────────────────────────────────────────────────────────────── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

// ── Cache ─────────────────────────────────────────────────────────────────
let _studioGroupsCache = null;
let _titleLabelsCache  = null;

async function ensureStudioGroups() {
  if (_studioGroupsCache) return _studioGroupsCache;
  const res = await fetch('/api/titles/studios');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  _studioGroupsCache = await res.json();
  return _studioGroupsCache;
}

async function ensureTitleLabels() {
  if (_titleLabelsCache) return _titleLabelsCache;
  const res = await fetch('/api/titles/labels');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  _titleLabelsCache = Array.isArray(data) ? data : [];
  return _titleLabelsCache;
}

// ── Actress mini-card for studio detail ───────────────────────────────────
function makeActressMini(a) {
  const cover = (a.coverUrls && a.coverUrls[0]) || null;
  const el = document.createElement('a');
  el.className = 'tit-studio-actress-card';
  el.href = `/v2-actress-detail.html?id=${encodeURIComponent(a.id)}`;
  el.title = a.canonicalName || '';
  el.innerHTML = `
    <div class="tit-studio-actress-cover"${cover ? ` style="background-image:url('${esc(cover)}');background-size:cover;background-position:center top"` : ''}></div>
    <div class="tit-studio-actress-name">${esc(a.canonicalName || a.displayName || '')}</div>`;
  return el;
}

// ── Actress grid section loader ───────────────────────────────────────────
function loadActressGrid(containerId, labelCodeSet, apiUrl) {
  fetch(apiUrl, { cache: 'no-cache' })
    .then(r => r.ok ? r.json() : Promise.reject(r.status))
    .then(ranked => {
      const el = document.getElementById(containerId);
      if (!el) return;
      if (ranked.length === 0) {
        el.innerHTML = '<span class="tit-studio-loading">none in library</span>';
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
            el2.appendChild(makeActressMini({ ...a, coverUrls: filtered.length > 0 ? filtered : allCovers }));
          });
        });
    })
    .catch(() => {
      const el = document.getElementById(containerId);
      if (el) el.innerHTML = '<span class="tit-studio-loading">failed to load</span>';
    });
}

// ── Company detail panel ──────────────────────────────────────────────────
function renderCompanyDetail(company, byCompany, detailEl) {
  const labels = byCompany.get(company) || [];
  const labelCodeSet = new Set(labels.map(l => l.code.toUpperCase()));

  const companyDesc = labels.length > 0 && labels[0].companyDescription
    ? labels[0].companyDescription : null;

  let html = `<div class="tit-studio-detail-heading">${esc(company)}</div>`;
  if (companyDesc) html += `<div class="tit-studio-detail-desc">${esc(companyDesc)}</div>`;

  const topId    = `tit-studio-top-${company.replace(/\W/g,'_')}`;
  const newestId = `tit-studio-newest-${company.replace(/\W/g,'_')}`;

  html += `<div class="tit-studio-detail-section-label">${esc(company)}'s Top 10</div>
           <div class="tit-studio-actress-grid" id="${topId}"><span class="tit-studio-loading">loading…</span></div>`;
  html += `<div class="tit-studio-detail-section-label">Newest Actresses</div>
           <div class="tit-studio-actress-grid" id="${newestId}"><span class="tit-studio-loading">loading…</span></div>`;

  const byLabel = new Map();
  labels.forEach(lbl => {
    const key = lbl.labelName || lbl.code;
    if (!byLabel.has(key)) byLabel.set(key, []);
    byLabel.get(key).push(lbl);
  });
  html += '<div class="tit-studio-detail-section-label" style="margin-top:24px">Labels</div>';
  html += '<div class="tit-studio-label-list">';
  byLabel.forEach((codes, labelName) => {
    html += `<div class="tit-studio-label-group">
      <div class="tit-studio-label-name">${esc(labelName)}</div>
      <div class="tit-studio-code-rows">`;
    codes.forEach(lbl => {
      html += `<div class="tit-studio-code-row">
        <span class="tit-studio-code-badge">${esc(lbl.code)}</span>
        ${lbl.description ? `<span class="tit-studio-label-desc">${esc(lbl.description)}</span>` : ''}
      </div>`;
    });
    html += '</div></div>';
  });
  html += '</div>';

  detailEl.innerHTML = html;

  const labelCodes = labels.map(l => l.code).join(',');
  loadActressGrid(topId,    labelCodeSet, `/api/titles/top-actresses?labels=${encodeURIComponent(labelCodes)}&limit=10`);
  loadActressGrid(newestId, labelCodeSet, `/api/titles/newest-actresses?labels=${encodeURIComponent(labelCodes)}&limit=10`);
}

// ── Group render ──────────────────────────────────────────────────────────
async function selectGroup(state, slug, groupRowEl, panelEl) {
  state.selectedStudioSlug = slug;
  groupRowEl.querySelectorAll('.tit-studio-group-btn').forEach(btn => {
    btn.classList.toggle('on', btn.dataset.slug === slug);
  });

  panelEl.innerHTML = '<div class="tit-studio-loading">Loading…</div>';
  panelEl.style.display = '';

  try {
    const groups    = await ensureStudioGroups();
    const allLabels = await ensureTitleLabels();
    const group = groups.find(g => g.slug === slug);
    if (!group) return;

    const companySet = new Set(group.companies);
    const byCompany  = new Map();
    group.companies.forEach(c => byCompany.set(c, []));
    allLabels.forEach(lbl => { if (companySet.has(lbl.company)) byCompany.get(lbl.company).push(lbl); });

    panelEl.innerHTML = '';

    // two-column: list + detail
    const listEl   = document.createElement('div');
    listEl.className = 'tit-studio-company-list';
    const detailEl = document.createElement('div');
    detailEl.className = 'tit-studio-company-detail';
    detailEl.id = 'tit-studio-detail-pane';

    let firstCompany = null;
    byCompany.forEach((labels, company) => {
      if (labels.length === 0) return;
      if (!firstCompany) firstCompany = company;
      const item = document.createElement('div');
      item.className = 'tit-studio-company-item';
      item.dataset.company = company;
      item.textContent = company;
      item.addEventListener('click', () => {
        listEl.querySelectorAll('.tit-studio-company-item').forEach(el => el.classList.remove('on'));
        item.classList.add('on');
        renderCompanyDetail(company, byCompany, detailEl);
      });
      listEl.appendChild(item);
    });

    panelEl.appendChild(listEl);
    panelEl.appendChild(detailEl);

    if (firstCompany) {
      const firstItem = listEl.querySelector('[data-company]');
      if (firstItem) firstItem.classList.add('on');
      renderCompanyDetail(firstCompany, byCompany, detailEl);
    }
  } catch (err) {
    console.error('[titles/studio]', err);
    panelEl.innerHTML = `<div class="tit-studio-loading">Failed to load studio data.</div>`;
  }
}

// ── Mount studio mode ─────────────────────────────────────────────────────
export async function mountStudio(state, groupRowEl, panelEl) {
  groupRowEl.innerHTML = '<div class="tit-studio-loading">Loading groups…</div>';
  groupRowEl.style.display = '';
  panelEl.innerHTML = '';
  panelEl.style.display = 'none';

  try {
    const groups = await ensureStudioGroups();
    groupRowEl.innerHTML = '';
    groups.forEach(g => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'tit-studio-group-btn' + (g.slug === state.selectedStudioSlug ? ' on' : '');
      btn.textContent = g.name;
      btn.dataset.slug = g.slug;
      btn.addEventListener('click', () => selectGroup(state, g.slug, groupRowEl, panelEl));
      groupRowEl.appendChild(btn);
    });

    if (groups.length > 0) {
      const slug = state.selectedStudioSlug || groups[0].slug;
      selectGroup(state, slug, groupRowEl, panelEl);
    }
  } catch (err) {
    console.error('[titles/studio]', err);
    groupRowEl.innerHTML = '<div class="tit-studio-loading">Failed to load studios.</div>';
  }
}

// ── Unmount studio mode ───────────────────────────────────────────────────
export function unmountStudio(groupRowEl, panelEl) {
  groupRowEl.innerHTML = '';
  groupRowEl.style.display = 'none';
  panelEl.innerHTML = '';
  panelEl.style.display = 'none';
}
