/* ─────────────────────────────────────────────────────────────────────
   Wave 2 — AV Stars browse (library mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md §1.4
   Card grid + filter chips + sort. Backed by /api/utilities/avstars.
   ───────────────────────────────────────────────────────────────────── */

const FILTERS = [
  ['ALL',        'All'],
  ['UNRESOLVED', 'Unresolved'],
  ['FAVORITES',  'Favorites'],
  ['BOOKMARKS',  'Bookmarks'],
  ['REJECTED',   'Rejected'],
];

const SORTS = [
  ['VIDEO_COUNT_DESC',  'Most videos'],
  ['STAGE_NAME_ASC',    'Name (A-Z)'],
  ['LAST_SCANNED_DESC', 'Recently scanned'],
];

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[c]));
}

async function fetchJson(url, fallback = null) {
  try {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) return fallback;
    return await r.json();
  } catch (e) {
    console.warn('[avstars] fetch failed:', url, e);
    return fallback;
  }
}

function renderCard(row) {
  const portrait = row.headshotUrl || null;
  const grade    = row.grade ? row.grade.toLowerCase() : '';
  const meta     = row.videoCount != null ? `${row.videoCount} videos` : '';
  const tier     = !row.resolved ? 'unresolved' : '';
  return `
    <a class="card-actress" href="/v2-avstar-detail.html?id=${encodeURIComponent(row.id)}">
      <div class="card-actress-portrait" style="${portrait ? `background-image:url('${portrait}');background-size:cover;background-position:center top` : ''}">
        ${tier ? `<span class="card-actress-tier">${escapeHtml(tier)}</span>` : ''}
        ${grade ? `<span class="card-actress-grade">${escapeHtml(grade.toUpperCase())}</span>` : ''}
      </div>
      <div class="card-actress-name">${escapeHtml(row.stageName || row.folderName || '')}</div>
      ${meta ? `<div class="card-actress-meta">${escapeHtml(meta)}</div>` : ''}
    </a>
  `;
}

export function mountAvStars(rootEl) {
  rootEl.innerHTML = `
    <div class="lib-page">
      <div class="lib-page-header">
        <h1 class="lib-page-title">AV Stars</h1>
      </div>

      <div class="filter-bar">
        <div class="filter-group" id="filter-chips">
          ${FILTERS.map(([k, label], i) => `<span class="chip${i === 0 ? ' on' : ''}" data-filter="${k}">${label}</span>`).join('')}
        </div>
        <div class="filter-divider"></div>
        <div class="filter-group">
          <span class="filter-label">Sort:</span>
          <select class="form-select" id="sort-select" style="width:auto;padding:4px 8px;font-size:12px">
            ${SORTS.map(([k, label]) => `<option value="${k}">${label}</option>`).join('')}
          </select>
        </div>
        <div class="filter-spacer"></div>
        <div class="filter-meta" id="result-meta"></div>
      </div>

      <div class="shelf-grid shelf-grid-actress" id="grid"></div>
      <div class="grid-status" id="grid-status"><div class="shelf-loading">Loading…</div></div>
    </div>
  `;

  const state = { filter: 'ALL', sort: 'VIDEO_COUNT_DESC' };
  const grid   = rootEl.querySelector('#grid');
  const status = rootEl.querySelector('#grid-status');
  const meta   = rootEl.querySelector('#result-meta');

  const load = async () => {
    grid.innerHTML = '';
    status.innerHTML = '<div class="shelf-loading">Loading…</div>';
    meta.textContent = '';
    const url = `/api/utilities/avstars/actresses?filter=${state.filter}&sort=${state.sort}`;
    const data = await fetchJson(url, { rows: [], counts: {} });
    const rows = data?.rows ?? [];
    const counts = data?.counts ?? {};

    if (rows.length === 0) {
      status.innerHTML = `
        <div class="empty-state">
          <div class="empty-state-title">No AV stars match these filters</div>
          <div class="empty-state-body">Try a different filter or scan a volume.</div>
        </div>`;
      return;
    }

    grid.innerHTML = rows.map(renderCard).join('');
    status.innerHTML = '';
    const c = counts;
    meta.textContent = `${rows.length} shown · ${c.total ?? '?'} total · ${c.resolved ?? '?'} resolved · ${c.favorites ?? '?'} favorites`;
  };

  rootEl.querySelector('#filter-chips').addEventListener('click', (e) => {
    const chip = e.target.closest('.chip');
    if (!chip) return;
    rootEl.querySelectorAll('#filter-chips .chip').forEach(c => c.classList.remove('on'));
    chip.classList.add('on');
    state.filter = chip.dataset.filter;
    load();
  });

  rootEl.querySelector('#sort-select').addEventListener('change', (e) => {
    state.sort = e.target.value;
    load();
  });

  load();
}
