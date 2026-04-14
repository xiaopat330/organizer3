import { esc } from './utils.js';
import { showView, updateBreadcrumb } from './grid.js';
import { pushNav } from './nav.js';
import { avBrowseMode, selectAvBrowseMode } from './av-browse.js';
import { openAvVideoDetail } from './av-video-detail.js';

// ── State ─────────────────────────────────────────────────────────────────
let currentActressId = null;
let currentActress   = null;
let allVideos        = [];
let videoFilter      = '';
let videoSort        = 'path';   // 'path' | 'date' | 'size'
let activeTagFilters = new Set(); // tag slugs selected for filtering

// ── Visit tracking ────────────────────────────────────────────────────────
let pendingVisitTimer = null;

function cancelPendingVisit() {
  if (pendingVisitTimer !== null) {
    clearTimeout(pendingVisitTimer);
    pendingVisitTimer = null;
  }
}

// ── Entry point ───────────────────────────────────────────────────────────
export async function openAvActressDetail(actressId) {
  cancelPendingVisit();
  currentActressId = actressId;
  currentActress   = null;
  allVideos        = [];
  videoFilter      = '';
  videoSort        = 'path';
  activeTagFilters = new Set();

  showView('av-actress-detail');
  pushNav({ view: 'av-actress-detail', actressId }, `av/actress/${actressId}`);
  updateBreadcrumb([
    { label: 'AV', action: () => selectAvBrowseMode(avBrowseMode || 'index') },
    { label: '…' }
  ]);

  const el = document.getElementById('av-actress-detail');
  el.innerHTML = '<div class="av-detail-loading">Loading…</div>';

  try {
    const [profileRes, videosRes] = await Promise.all([
      fetch(`/api/av/actresses/${actressId}`),
      fetch(`/api/av/actresses/${actressId}/videos`)
    ]);
    if (!profileRes.ok) throw new Error(`HTTP ${profileRes.status}`);
    currentActress = await profileRes.json();
    allVideos = videosRes.ok ? await videosRes.json() : [];
  } catch (err) {
    el.innerHTML = '<div class="av-detail-loading">Failed to load actress.</div>';
    return;
  }

  updateBreadcrumb([
    { label: 'AV', action: () => selectAvBrowseMode(avBrowseMode || 'index') },
    { label: currentActress.stageName }
  ]);

  renderDetail();

  // 5-second debounce before recording visit
  pendingVisitTimer = setTimeout(() => {
    pendingVisitTimer = null;
    fetch(`/api/av/actresses/${actressId}/visit`, { method: 'POST' }).catch(() => {});
  }, 5000);
}

// ── Main render ───────────────────────────────────────────────────────────
function renderDetail() {
  const el = document.getElementById('av-actress-detail');
  el.innerHTML = `
    <div class="av-detail-sidebar">
      ${renderProfileCard(currentActress)}
      ${renderActionBar(currentActress)}
      ${renderProfileDetails(currentActress)}
    </div>
    <div class="av-detail-main">
      ${renderVideoFilterBar()}
      <div id="av-detail-video-grid" class="av-detail-video-grid"></div>
    </div>`;
  wireSidebarButtons();
  wireMainOnce();
  renderVideoGrid();
}

// ── Profile card ──────────────────────────────────────────────────────────
function renderProfileCard(a) {
  const imgHtml = a.headshotUrl
    ? `<img class="av-detail-headshot" src="${esc(a.headshotUrl)}" alt="${esc(a.stageName)}">`
    : `<div class="av-detail-headshot av-detail-headshot-placeholder">
         <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"
              stroke-linecap="round" stroke-linejoin="round">
           <circle cx="12" cy="8" r="4"/><path d="M4 20c0-4 3.6-7 8-7s8 3 8 7"/>
         </svg>
       </div>`;

  const years = (a.activeFrom || a.activeTo)
    ? `<div class="av-detail-years">${a.activeFrom || '?'}–${a.activeTo || 'present'}</div>`
    : '';

  const resolvedBadge = a.resolved
    ? `<span class="av-detail-badge av-detail-badge-resolved" title="IAFD resolved">IAFD</span>`
    : `<span class="av-detail-badge av-detail-badge-unresolved" title="Not yet resolved">unresolved</span>`;

  return `
    <div class="av-detail-profile-card">
      <div class="av-detail-headshot-wrap">${imgHtml}</div>
      <div class="av-detail-name">${esc(a.stageName)}</div>
      <div class="av-detail-folder-name">${esc(a.folderName)}</div>
      ${years}
      <div class="av-detail-stats">
        <span>${a.videoCount} video${a.videoCount === 1 ? '' : 's'}</span>
        ${a.totalSizeBytes ? `<span>${formatBytes(a.totalSizeBytes)}</span>` : ''}
      </div>
      <div class="av-detail-badges">${resolvedBadge}</div>
    </div>`;
}

// ── Action bar (favorite / bookmark toggles) ──────────────────────────────
function renderActionBar(a) {
  return `
    <div class="av-detail-action-bar">
      <button id="av-detail-fav-btn" class="av-detail-action-btn${a.favorite ? ' active' : ''}" title="${a.favorite ? 'Remove favorite' : 'Add to favorites'}">
        <svg viewBox="0 0 24 24" width="16" height="16"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26" fill="${a.favorite ? 'currentColor' : 'none'}" stroke="currentColor" stroke-width="2"/></svg>
        ${a.favorite ? 'Favorited' : 'Favorite'}
      </button>
      <button id="av-detail-bm-btn" class="av-detail-action-btn${a.bookmark ? ' active' : ''}" title="${a.bookmark ? 'Remove bookmark' : 'Bookmark'}">
        <svg viewBox="0 0 24 24" width="16" height="16"><path d="M6 2h12a1 1 0 0 1 1 1v18.5a.5.5 0 0 1-.8.4L12 17.5 5.8 21.9a.5.5 0 0 1-.8-.4V3a1 1 0 0 1 1-1z" fill="${a.bookmark ? 'currentColor' : 'none'}" stroke="currentColor" stroke-width="2"/></svg>
        ${a.bookmark ? 'Bookmarked' : 'Bookmark'}
      </button>
    </div>`;
}

// ── Profile details (IAFD enrichment — hidden when sparse) ────────────────
function renderProfileDetails(a) {
  const rows = [];
  if (a.nationality)   rows.push(['Nationality', esc(a.nationality)]);
  if (a.ethnicity)     rows.push(['Ethnicity',   esc(a.ethnicity)]);
  if (a.dateOfBirth)   rows.push(['Born',        esc(a.dateOfBirth)]);
  if (a.birthplace)    rows.push(['Birthplace',  esc(a.birthplace)]);
  if (a.heightCm)      rows.push(['Height',      `${a.heightCm} cm`]);
  if (a.measurements)  rows.push(['Measurements',esc(a.measurements)]);
  if (a.hairColor)     rows.push(['Hair',        esc(a.hairColor)]);
  if (a.eyeColor)      rows.push(['Eyes',        esc(a.eyeColor)]);
  if (a.tattoos)       rows.push(['Tattoos',     esc(a.tattoos)]);
  if (a.piercings)     rows.push(['Piercings',   esc(a.piercings)]);
  if (a.grade)         rows.push(['Grade',       esc(a.grade)]);
  if (a.notes)         rows.push(['Notes',       esc(a.notes)]);

  if (rows.length === 0) return '';
  return `
    <dl class="av-detail-profile-dl">
      ${rows.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join('')}
    </dl>`;
}

// ── Video filter bar ──────────────────────────────────────────────────────
function renderVideoFilterBar() {
  // Collect all unique tag slugs across all videos
  const allTags = [...new Set(allVideos.flatMap(v => v.tags || []))].sort();
  const tagPills = allTags.length > 0
    ? `<div class="av-tag-filter-row" id="av-tag-filter-row">
        ${allTags.map(slug =>
          `<button class="av-tag-pill${activeTagFilters.has(slug) ? ' active' : ''}" data-tag="${esc(slug)}">${esc(slug)}</button>`
        ).join('')}
       </div>`
    : '';
  return `
    <div class="av-detail-filter-bar">
      <input type="search" id="av-detail-video-search" class="av-detail-video-search"
             placeholder="filter by filename…" autocomplete="off" spellcheck="false">
      <div class="av-index-sort-group">
        <button class="av-index-sort-btn${videoSort === 'path'  ? ' selected' : ''}" data-vsort="path">Path</button>
        <button class="av-index-sort-btn${videoSort === 'date'  ? ' selected' : ''}" data-vsort="date">Date</button>
        <button class="av-index-sort-btn${videoSort === 'size'  ? ' selected' : ''}" data-vsort="size">Size</button>
      </div>
      <span id="av-detail-video-count" class="av-index-count"></span>
    </div>
    ${tagPills}`;
}

// ── Video grid ────────────────────────────────────────────────────────────
function renderVideoGrid() {
  const gridEl = document.getElementById('av-detail-video-grid');
  if (!gridEl) return;

  const q = videoFilter.trim().toLowerCase();
  let data = allVideos.slice();
  if (q) data = data.filter(v => v.filename.toLowerCase().includes(q)
                               || (v.relativePath || '').toLowerCase().includes(q));

  // Tag filter: OR logic — video must have at least one of the active tags
  if (activeTagFilters.size > 0) {
    data = data.filter(v => (v.tags || []).some(t => activeTagFilters.has(t)));
  }

  if (videoSort === 'date') {
    data.sort((a, b) => (b.releaseDate || '').localeCompare(a.releaseDate || '')
                     || a.filename.localeCompare(b.filename));
  } else if (videoSort === 'size') {
    data.sort((a, b) => (b.sizeBytes || 0) - (a.sizeBytes || 0));
  } else {
    data.sort((a, b) => a.relativePath.localeCompare(b.relativePath));
  }

  const countEl = document.getElementById('av-detail-video-count');
  if (countEl) countEl.textContent = `${data.length} video${data.length === 1 ? '' : 's'}`;

  gridEl.innerHTML = data.length === 0
    ? '<div class="av-grid-loading">No videos found.</div>'
    : data.map(makeVideoRow).join('');
}

function makeVideoRow(v) {
  const size = v.sizeBytes ? formatBytes(v.sizeBytes) : '';
  const res  = v.resolution ? `<span class="av-video-res">${esc(v.resolution)}</span>` : '';
  const stu  = v.studio     ? `<span class="av-video-studio">${esc(v.studio)}</span>`   : '';
  const date = v.releaseDate ? `<span class="av-video-date">${esc(v.releaseDate)}</span>` : '';
  const bucket = v.bucket ? `<span class="av-video-bucket">${esc(v.bucket)}</span>` : '';

  const watched = v.watched
    ? `<span class="av-video-watched" title="Watched ${v.watchCount}×">✓</span>` : '';
  const favCls  = v.favorite ? ' active' : '';
  const bmCls   = v.bookmark ? ' active' : '';

  return `
    <div class="av-video-row" data-video-id="${v.id}">
      <div class="av-video-main">
        <span class="av-video-name">${esc(v.filename)}</span>
        <span class="av-video-meta">${[size, res, stu, date, bucket].filter(Boolean).join(' · ')}</span>
      </div>
      <div class="av-video-actions">
        ${watched}
        <button class="av-video-btn av-video-fav-btn${favCls}" data-id="${v.id}" data-type="favorite" title="Favorite" aria-pressed="${v.favorite}">★</button>
        <button class="av-video-btn av-video-bm-btn${bmCls}" data-id="${v.id}" data-type="bookmark" title="Bookmark" aria-pressed="${v.bookmark}">⊿</button>
        <button class="av-video-btn av-video-watch-btn${v.watched ? ' active' : ''}" data-id="${v.id}" data-type="watch" title="Mark watched">✓</button>
      </div>
    </div>`;
}

// ── Wire interactive elements ─────────────────────────────────────────────

/** Wires the sidebar actress favorite/bookmark buttons. Called on every sidebar re-render. */
function wireSidebarButtons() {
  const el = document.getElementById('av-actress-detail');

  // Actress favorite toggle
  el.querySelector('#av-detail-fav-btn')?.addEventListener('click', async () => {
    const newVal = !currentActress.favorite;
    const res = await fetch(`/api/av/actresses/${currentActressId}/favorite?value=${newVal}`, { method: 'POST' });
    if (res.ok) {
      const data = await res.json();
      currentActress = { ...currentActress, favorite: data.favorite };
      el.querySelector('.av-detail-sidebar').innerHTML = renderProfileCard(currentActress)
        + renderActionBar(currentActress) + renderProfileDetails(currentActress);
      wireSidebarButtons();
    }
  });

  // Actress bookmark toggle
  el.querySelector('#av-detail-bm-btn')?.addEventListener('click', async () => {
    const newVal = !currentActress.bookmark;
    const res = await fetch(`/api/av/actresses/${currentActressId}/bookmark?value=${newVal}`, { method: 'POST' });
    if (res.ok) {
      const data = await res.json();
      currentActress = { ...currentActress, bookmark: data.bookmark };
      el.querySelector('.av-detail-sidebar').innerHTML = renderProfileCard(currentActress)
        + renderActionBar(currentActress) + renderProfileDetails(currentActress);
      wireSidebarButtons();
    }
  });
}

/**
 * Wires the main-panel listeners (filter bar + video grid). Called once per full render.
 * Separate from wireSidebarButtons() to avoid listener duplication on sidebar-only re-renders.
 */
function wireMainOnce() {
  const el = document.getElementById('av-actress-detail');

  // Video filter search
  el.querySelector('#av-detail-video-search')?.addEventListener('input', e => {
    videoFilter = e.target.value;
    renderVideoGrid();
  });

  // Video sort buttons
  el.querySelectorAll('[data-vsort]').forEach(btn => {
    btn.addEventListener('click', () => {
      videoSort = btn.dataset.vsort;
      el.querySelectorAll('[data-vsort]').forEach(b => b.classList.remove('selected'));
      btn.classList.add('selected');
      renderVideoGrid();
    });
  });

  // Tag filter pills (delegated — row is re-rendered when filter bar re-renders)
  el.querySelector('.av-detail-main')?.addEventListener('click', e => {
    const pill = e.target.closest('.av-tag-pill');
    if (!pill) return;
    const slug = pill.dataset.tag;
    if (activeTagFilters.has(slug)) {
      activeTagFilters.delete(slug);
      pill.classList.remove('active');
    } else {
      activeTagFilters.add(slug);
      pill.classList.add('active');
    }
    renderVideoGrid();
  });

  const grid = document.getElementById('av-detail-video-grid');
  if (!grid) return;

  // Video row click → open detail modal (clicking main area, not action buttons)
  grid.addEventListener('click', e => {
    if (e.target.closest('[data-type]')) return; // let action button handler take it
    const row = e.target.closest('.av-video-row');
    if (!row) return;
    const vid = parseInt(row.dataset.videoId, 10);
    if (!isNaN(vid)) openAvVideoDetail(vid);
  });

  // Video action buttons (delegated)
  grid.addEventListener('click', async e => {
    const btn = e.target.closest('[data-type]');
    if (!btn) return;
    const vid = parseInt(btn.dataset.id, 10);
    if (isNaN(vid)) return;
    const type = btn.dataset.type;

    if (type === 'favorite') {
      const cur = allVideos.find(v => v.id === vid);
      if (!cur) return;
      const res = await fetch(`/api/av/videos/${vid}/favorite?value=${!cur.favorite}`, { method: 'POST' });
      if (res.ok) { const d = await res.json(); updateVideo(vid, { favorite: d.favorite }); }
    } else if (type === 'bookmark') {
      const cur = allVideos.find(v => v.id === vid);
      if (!cur) return;
      const res = await fetch(`/api/av/videos/${vid}/bookmark?value=${!cur.bookmark}`, { method: 'POST' });
      if (res.ok) { const d = await res.json(); updateVideo(vid, { bookmark: d.bookmark }); }
    } else if (type === 'watch') {
      const res = await fetch(`/api/av/videos/${vid}/watch`, { method: 'POST' });
      if (res.ok) { const d = await res.json(); updateVideo(vid, { watched: d.watched, watchCount: d.watchCount }); }
    }
  });
}

function updateVideo(videoId, patch) {
  allVideos = allVideos.map(v => v.id === videoId ? { ...v, ...patch } : v);
  renderVideoGrid();
}

// ── Utilities ─────────────────────────────────────────────────────────────
function formatBytes(bytes) {
  if (!bytes) return '';
  if (bytes < 1024)             return bytes + ' B';
  if (bytes < 1024 * 1024)     return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 ** 3)       return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  return (bytes / 1024 ** 3).toFixed(2) + ' GB';
}
