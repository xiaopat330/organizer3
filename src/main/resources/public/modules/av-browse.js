import { showView, updateBreadcrumb } from './grid.js';
import { esc } from './utils.js';
import { pushNav } from './nav.js';

// ── DOM refs ──────────────────────────────────────────────────────────────
export const avBtn          = document.getElementById('av-btn');
export const avLandingEl    = document.getElementById('av-landing');
const avDashboardBtn        = document.getElementById('av-dashboard-btn');
const avFavoritesBtn        = document.getElementById('av-favorites-btn');
const avBookmarksBtn        = document.getElementById('av-bookmarks-btn');
const avIndexBtn            = document.getElementById('av-index-btn');
const avDashboardEl         = document.getElementById('av-dashboard');
const avGridEl              = document.getElementById('av-grid');

// ── State ─────────────────────────────────────────────────────────────────
export let avBrowseMode = null;
let allActresses = [];      // full loaded set for client-side filter/sort
let activeSort = 'count';   // 'count' | 'name'
let filterText = '';
let activeTagFilters = new Set(); // tag slugs for actress index filtering
let allTagDefs = [];               // loaded from /api/av/tags

// ── Mode selection ────────────────────────────────────────────────────────
export async function selectAvBrowseMode(mode) {
  avBrowseMode = mode;
  avBtn.classList.add('active');
  document.getElementById('actresses-btn')?.classList.remove('active');
  document.getElementById('titles-browse-btn')?.classList.remove('active');

  [avDashboardBtn, avFavoritesBtn, avBookmarksBtn, avIndexBtn].forEach(b => b?.classList.remove('selected'));

  if (avDashboardEl) avDashboardEl.style.display = 'none';
  if (avGridEl)      avGridEl.style.display = 'none';

  switch (mode) {
    case 'dashboard':
      avDashboardBtn?.classList.add('selected');
      showView('av');
      if (avDashboardEl) avDashboardEl.style.display = 'block';
      pushNav({ view: 'av', mode: 'dashboard' }, 'av');
      updateBreadcrumb([{ label: 'AV' }]);
      break;

    case 'favorites':
      avFavoritesBtn?.classList.add('selected');
      showView('av-index');
      pushNav({ view: 'av', mode: 'favorites' }, 'av/favorites');
      updateBreadcrumb([{ label: 'AV' }, { label: 'Favorites' }]);
      await loadActressGrid('favorites');
      break;

    case 'bookmarks':
      avBookmarksBtn?.classList.add('selected');
      showView('av-index');
      pushNav({ view: 'av', mode: 'bookmarks' }, 'av/bookmarks');
      updateBreadcrumb([{ label: 'AV' }, { label: 'Bookmarks' }]);
      await loadActressGrid('bookmarks');
      break;

    case 'index':
      avIndexBtn?.classList.add('selected');
      showView('av-index');
      pushNav({ view: 'av', mode: 'index' }, 'av/index');
      updateBreadcrumb([{ label: 'AV' }, { label: 'Index' }]);
      await loadActressGrid('all');
      break;
  }
}

export function showAvLanding() {
  selectAvBrowseMode('dashboard');
}

// ── Index grid ─────────────────────────────────────────────────────────────
async function loadActressGrid(mode) {
  if (!avGridEl) return;
  avGridEl.style.display = 'grid';
  avGridEl.innerHTML = '<div class="av-grid-loading">Loading…</div>';

  try {
    const url = mode === 'favorites' ? '/api/av/actresses?mode=favorites' : '/api/av/actresses';
    const [res, tagsRes] = await Promise.all([fetch(url), fetch('/api/av/tags')]);
    let data = await res.json();
    allTagDefs = tagsRes.ok ? await tagsRes.json() : [];

    if (mode === 'bookmarks') data = data.filter(a => a.bookmark);

    allActresses = data;
    activeTagFilters.clear();
    renderFilterBar();
    renderGrid();
  } catch (e) {
    avGridEl.innerHTML = '<div class="av-grid-loading">Failed to load actresses.</div>';
  }
}

function renderFilterBar() {
  // Insert filter bar above grid if not already there
  let bar = document.getElementById('av-index-filter-bar');
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'av-index-filter-bar';
    bar.className = 'av-index-filter-bar';
    avGridEl.parentNode.insertBefore(bar, avGridEl);
  }
  bar.style.display = '';
  // Collect tag slugs that appear in any loaded actress's topTags
  const usedTagSlugs = [...new Set(allActresses.flatMap(a => a.topTags || []))].sort();
  const tagPills = usedTagSlugs.length > 0
    ? `<div class="av-tag-filter-row" id="av-index-tag-row">
        ${usedTagSlugs.map(slug =>
          `<button class="av-tag-pill${activeTagFilters.has(slug) ? ' active' : ''}" data-tag="${esc(slug)}">${esc(slug)}</button>`
        ).join('')}
       </div>`
    : '';

  bar.innerHTML = `
    <input type="search" id="av-index-search" class="av-index-search" placeholder="filter by name…" value="${esc(filterText)}" autocomplete="off" spellcheck="false">
    <div class="av-index-sort-group">
      <button class="av-index-sort-btn${activeSort === 'count' ? ' selected' : ''}" data-sort="count">Most Videos</button>
      <button class="av-index-sort-btn${activeSort === 'name'  ? ' selected' : ''}" data-sort="name">A–Z</button>
    </div>
    <span class="av-index-count" id="av-index-count"></span>
    ${tagPills}
  `;

  bar.querySelector('#av-index-search').addEventListener('input', e => {
    filterText = e.target.value;
    renderGrid();
  });
  bar.querySelectorAll('.av-index-sort-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      activeSort = btn.dataset.sort;
      bar.querySelectorAll('.av-index-sort-btn').forEach(b => b.classList.remove('selected'));
      btn.classList.add('selected');
      renderGrid();
    });
  });
  bar.querySelectorAll('.av-tag-pill').forEach(pill => {
    pill.addEventListener('click', () => {
      const slug = pill.dataset.tag;
      if (activeTagFilters.has(slug)) {
        activeTagFilters.delete(slug);
        pill.classList.remove('active');
      } else {
        activeTagFilters.add(slug);
        pill.classList.add('active');
      }
      renderGrid();
    });
  });
}

function renderGrid() {
  if (!avGridEl) return;

  let data = allActresses.slice();

  // Filter by name
  const q = filterText.trim().toLowerCase();
  if (q) data = data.filter(a => a.stageName.toLowerCase().includes(q));

  // Filter by tag (OR logic against topTags)
  if (activeTagFilters.size > 0) {
    data = data.filter(a => (a.topTags || []).some(t => activeTagFilters.has(t)));
  }

  // Sort
  if (activeSort === 'name') {
    data.sort((a, b) => a.stageName.localeCompare(b.stageName));
  } else {
    data.sort((a, b) => b.videoCount - a.videoCount || a.stageName.localeCompare(b.stageName));
  }

  const countEl = document.getElementById('av-index-count');
  if (countEl) countEl.textContent = `${data.length} actress${data.length === 1 ? '' : 'es'}`;

  avGridEl.innerHTML = data.length === 0
    ? '<div class="av-grid-loading">No actresses found.</div>'
    : data.map(makeAvActressCard).join('');
}

function makeAvActressCard(a) {
  const imgHtml = a.headshotUrl
    ? `<img class="av-card-headshot" src="${esc(a.headshotUrl)}" alt="${esc(a.stageName)}" loading="lazy">`
    : `<div class="av-card-headshot av-card-headshot-placeholder"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="4"/><path d="M4 20c0-4 3.6-7 8-7s8 3 8 7"/></svg></div>`;

  const years = (a.activeFrom || a.activeTo)
    ? `<span class="av-card-years">${a.activeFrom || '?'}–${a.activeTo || 'present'}</span>`
    : '';

  const favHtml = a.favorite
    ? `<span class="av-card-fav" title="Favorite">★</span>` : '';
  const bmHtml  = a.bookmark
    ? `<span class="av-card-bm"  title="Bookmarked">⊿</span>` : '';

  return `
    <div class="av-card" data-id="${a.id}">
      <div class="av-card-img-wrap">${imgHtml}</div>
      <div class="av-card-body">
        <div class="av-card-name">${esc(a.stageName)}</div>
        <div class="av-card-meta">
          <span class="av-card-count">${a.videoCount} video${a.videoCount === 1 ? '' : 's'}</span>
          ${years}
        </div>
        <div class="av-card-indicators">${favHtml}${bmHtml}</div>
      </div>
    </div>`;
}

// ── Card click delegation ─────────────────────────────────────────────────
avGridEl?.addEventListener('click', e => {
  const card = e.target.closest('.av-card');
  if (!card) return;
  const id = parseInt(card.dataset.id, 10);
  if (!isNaN(id)) _openDetail(id);
});

function _openDetail(id) {
  import('./av-actress-detail.js').then(m => m.openAvActressDetail(id));
}

// ── Button click handlers ─────────────────────────────────────────────────
avDashboardBtn?.addEventListener('click', () => selectAvBrowseMode('dashboard'));
avFavoritesBtn?.addEventListener('click', () => selectAvBrowseMode('favorites'));
avBookmarksBtn?.addEventListener('click', () => selectAvBrowseMode('bookmarks'));
avIndexBtn?.addEventListener('click',     () => selectAvBrowseMode('index'));

avBtn?.addEventListener('click', e => {
  e.stopPropagation();
  selectAvBrowseMode('dashboard');
});
