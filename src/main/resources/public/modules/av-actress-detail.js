import { esc, timeAgo } from './utils.js';
import { showView, updateBreadcrumb } from './grid.js';
import { pushNav } from './nav.js';
import { avBrowseMode, selectAvBrowseMode } from './av-browse.js';
import { openAvVideoDetail } from './av-video-detail.js';
import { COLS_VALUES, colsSliderHtml, wireColsSlider } from './grid-cols.js';
import { ICON_FAV_LG, ICON_BM_LG, ICON_FAV_SM, ICON_BM_SM, ICON_BM_SM_OFF } from './icons.js';
import { mount as mountScreenshotControls, unmount as unmountScreenshotControls } from './av-screenshot-controls.js';

// ── Constants ─────────────────────────────────────────────────────────────
const AV_DETAIL_COLS_KEY     = 'av-detail-grid-cols';
const AV_DETAIL_COLS_DEFAULT = 4;

// ── State ─────────────────────────────────────────────────────────────────
let currentActressId = null;
let currentActress   = null;
let allVideos        = [];
let videoFilter      = '';
let gridCols         = AV_DETAIL_COLS_DEFAULT;


// ── Visit tracking ────────────────────────────────────────────────────────
let pendingVisitTimer = null;

function cancelPendingVisit() {
  if (pendingVisitTimer !== null) {
    clearTimeout(pendingVisitTimer);
    pendingVisitTimer = null;
  }
}

// ── Column helper ─────────────────────────────────────────────────────────
function getSavedCols() {
  const saved = parseInt(localStorage.getItem(AV_DETAIL_COLS_KEY), 10);
  return COLS_VALUES.includes(saved) ? saved : AV_DETAIL_COLS_DEFAULT;
}

// ── Entry point ───────────────────────────────────────────────────────────
export async function openAvActressDetail(actressId) {
  cancelPendingVisit();
  unmountScreenshotControls();
  currentActressId = actressId;
  currentActress   = null;
  allVideos        = [];
  videoFilter      = '';
  gridCols         = getSavedCols();

  showView('av-actress-detail');
  // Fit the detail panel to the remaining viewport below the header + landing nav
  const landingEl = document.getElementById('av-landing');
  const landingH  = landingEl ? landingEl.offsetHeight : 0;
  document.getElementById('av-actress-detail').style.height = `calc(100vh - 49px - ${landingH}px)`;
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

  pendingVisitTimer = setTimeout(() => {
    pendingVisitTimer = null;
    fetch(`/api/av/actresses/${actressId}/visit`, { method: 'POST' })
      .then(r => r.ok ? r.json() : null)
      .then(d => { if (d) updateVisitedRow(d.visitCount, d.lastVisitedAt); })
      .catch(() => {});
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
    <div class="av-detail-right" id="av-detail-right">
      ${renderToolbar()}
      <div id="av-vc-grid" class="av-vc-grid"></div>
    </div>`;

  applyGridCols(gridCols);
  wireSidebarButtons();
  mountScreenshotControls(
    document.getElementById('av-ss-controls'),
    currentActressId,
    allVideos.filter(v => !v.screenshotCount).length
  );
  wireRightPanel();
  renderVideoGrid();
}

// ── Restore video grid (called by av-video-detail back button) ───────────
export function showAvActressVideoGrid() {
  const rightEl = document.getElementById('av-detail-right');
  if (!rightEl) return;
  rightEl.innerHTML = `
    ${renderToolbar()}
    <div id="av-vc-grid" class="av-vc-grid"></div>`;
  applyGridCols(gridCols);
  wireRightPanel();
  renderVideoGrid();
}

// ── Profile card (left panel) ─────────────────────────────────────────────
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
      <div class="detail-visited" id="av-detail-visited-row"${a.visitCount > 0 ? '' : ' style="display:none"'}>
        <span id="av-detail-visited-value">${a.visitCount > 0 ? formatVisited(a.visitCount, a.lastVisitedAt) : ''}</span>
      </div>
    </div>`;
}

function renderActionBar(a) {
  return `
    <div class="title-detail-actions">
      <button class="title-action-btn${a.favorite ? ' active' : ''}" id="av-detail-fav-btn" title="Favorite">${ICON_FAV_LG}</button>
      <button class="title-action-btn${a.bookmark ? ' active' : ''}" id="av-detail-bm-btn" title="Bookmark">${ICON_BM_LG}</button>
    </div>
    <div id="av-ss-controls"></div>`;
}

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

// ── Toolbar (right panel top) ─────────────────────────────────────────────
function renderToolbar() {
  const cols = gridCols;
  return `
    <div class="av-vc-toolbar">
      <input type="search" id="av-vc-search" class="av-vc-search"
             placeholder="filter…" autocomplete="off" spellcheck="false"
             value="${esc(videoFilter)}">
      ${colsSliderHtml(cols, 'av-vc-cols-ctrl', 'av-vc-cols-slider', 'av-vc-cols-label')}
      <span id="av-vc-count" class="av-vc-count"></span>
    </div>`;
}

// ── Video grid ────────────────────────────────────────────────────────────
function applyGridCols(cols) {
  const grid = document.getElementById('av-vc-grid');
  if (grid) grid.style.gridTemplateColumns = `repeat(${cols}, 1fr)`;
}

function renderVideoGrid() {
  const grid = document.getElementById('av-vc-grid');
  if (!grid) return;

  const q = videoFilter.trim().toLowerCase();
  let data = allVideos.slice();
  if (q) {
    data = data.filter(v =>
      v.filename.toLowerCase().includes(q) ||
      (v.parsedTitle || '').toLowerCase().includes(q) ||
      (v.relativePath || '').toLowerCase().includes(q));
  }

  const countEl = document.getElementById('av-vc-count');
  if (countEl) countEl.textContent = `${data.length} video${data.length === 1 ? '' : 's'}`;

  grid.innerHTML = data.length === 0
    ? '<div class="av-vc-empty">No videos found.</div>'
    : data.map(makeVideoCard).join('');
}

// ── Video card ────────────────────────────────────────────────────────────
function makeVideoCard(v) {
  const count = v.screenshotCount || 0;

  let thumbHtml;
  if (count > 1) {
    // Marquee: mirrors cover-marquee-track — UNIQUE tiles × 2, -50% loop
    const perTileSec = 3 + Math.random() * 2;
    const durationSec = count * perTileSec;
    const delayMs = -(Math.random() * durationSec * 1000).toFixed(0);
    const tiles = Array.from({ length: count * 2 }, (_, i) =>
      `<div class="av-vc-marquee-tile"><img src="/api/av/screenshots/${v.id}/${i % count}" alt="" loading="lazy"></div>`
    ).join('');
    thumbHtml = `<div class="av-vc-marquee-track" style="animation-duration:${durationSec.toFixed(2)}s;animation-delay:${delayMs}ms">${tiles}</div>`;
  } else if (v.firstScreenshotUrl) {
    thumbHtml = `<img class="av-vc-thumb" src="${esc(v.firstScreenshotUrl)}" alt="" loading="lazy">`;
  } else {
    thumbHtml = `<div class="av-vc-thumb-placeholder">
         <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"
              stroke-linecap="round" stroke-linejoin="round" width="32" height="32">
           <polygon points="5,3 19,12 5,21"/>
         </svg>
       </div>`;
  }

  const watchedBadge = v.watched
    ? `<div class="av-vc-watched-badge">✓${v.watchCount > 1 ? ` ${v.watchCount}×` : ''}</div>`
    : '';

  const title = v.parsedTitle && v.parsedTitle !== v.filename
    ? esc(v.parsedTitle)
    : esc(v.filename);

  const metaParts = [v.resolution, v.sizeBytes ? formatBytes(v.sizeBytes) : null, v.studio, v.releaseDate]
    .filter(Boolean);
  const metaHtml = metaParts.length > 0
    ? `<div class="av-vc-meta">${metaParts.map(esc).join(' · ')}</div>`
    : '';

  const tags = v.tags || [];
  const tagsHtml = tags.length > 0
    ? `<div class="av-vc-tags">${tags.map(t => `<span class="av-vc-tag">${esc(t)}</span>`).join('')}</div>`
    : '';

  const bmIcon   = v.bookmark ? ICON_BM_SM : ICON_BM_SM_OFF;
  const favIcon  = v.favorite ? ICON_FAV_SM : '';

  return `
    <div class="av-video-card" data-video-id="${v.id}">
      <div class="av-vc-thumb-wrap">
        ${thumbHtml}
        ${watchedBadge}
      </div>
      <div class="av-vc-body">
        <div class="av-vc-header">
          <button class="av-vc-bm-btn${v.bookmark ? ' av-vc-bm-active' : ''}" data-type="bookmark" data-id="${v.id}">${bmIcon}</button>
          ${favIcon}
          <span class="av-vc-title" title="${esc(v.filename)}">${title}</span>
        </div>
        ${metaHtml}
        ${tagsHtml}
      </div>
    </div>`;
}

// ── Wire interactions ─────────────────────────────────────────────────────
function wireSidebarButtons() {
  document.getElementById('av-detail-fav-btn')?.addEventListener('click', async () => {
    const newVal = !currentActress.favorite;
    const res = await fetch(`/api/av/actresses/${currentActressId}/favorite?value=${newVal}`, { method: 'POST' });
    if (res.ok) {
      const data = await res.json();
      currentActress = { ...currentActress, favorite: data.favorite };
      document.getElementById('av-detail-fav-btn').classList.toggle('active', data.favorite);
    }
  });

  document.getElementById('av-detail-bm-btn')?.addEventListener('click', async () => {
    const newVal = !currentActress.bookmark;
    const res = await fetch(`/api/av/actresses/${currentActressId}/bookmark?value=${newVal}`, { method: 'POST' });
    if (res.ok) {
      const data = await res.json();
      currentActress = { ...currentActress, bookmark: data.bookmark };
      document.getElementById('av-detail-bm-btn').classList.toggle('active', data.bookmark);
    }
  });
}

function formatVisited(count, lastVisitedAt) {
  const label = count === 1 ? '1 view' : `${count} views`;
  return lastVisitedAt ? `${label} · visited ${timeAgo(lastVisitedAt)}` : label;
}

function updateVisitedRow(visitCount, lastVisitedAt) {
  const row = document.getElementById('av-detail-visited-row');
  const val = document.getElementById('av-detail-visited-value');
  if (!row || !val || visitCount <= 0) return;
  val.textContent = formatVisited(visitCount, lastVisitedAt || null);
  row.style.display = '';
}

function wireRightPanel() {
  // Search
  document.getElementById('av-vc-search')?.addEventListener('input', e => {
    videoFilter = e.target.value;
    renderVideoGrid();
  });

  // Column slider — use separate storage key so it doesn't clobber the title grid setting
  wireColsSlider('av-vc-cols-slider', 'av-vc-cols-label', cols => {
    gridCols = cols;
    applyGridCols(cols);
  }, AV_DETAIL_COLS_KEY);

  // Grid delegated clicks
  const grid = document.getElementById('av-vc-grid');
  if (!grid) return;

  grid.addEventListener('click', async e => {
    // Bookmark toggle
    const bmBtn = e.target.closest('[data-type="bookmark"]');
    if (bmBtn) {
      e.stopPropagation();
      const vid = parseInt(bmBtn.dataset.id, 10);
      const cur = allVideos.find(v => v.id === vid);
      if (!cur) return;
      const res = await fetch(`/api/av/videos/${vid}/bookmark?value=${!cur.bookmark}`, { method: 'POST' });
      if (res.ok) { const d = await res.json(); updateVideo(vid, { bookmark: d.bookmark }); }
      return;
    }

    // Card click → open video detail
    const card = e.target.closest('.av-video-card');
    if (card) {
      const vid = parseInt(card.dataset.videoId, 10);
      if (!isNaN(vid)) openAvVideoDetail(vid, showAvActressVideoGrid);
    }
  });

}

function updateVideo(videoId, patch) {
  allVideos = allVideos.map(v => v.id === videoId ? { ...v, ...patch } : v);
  renderVideoGrid();
}

window.addEventListener('av-screenshots-generated', e => {
  const { videoId, count } = e.detail;
  if (!allVideos.some(v => v.id === videoId)) return;
  updateVideo(videoId, {
    screenshotCount: count,
    firstScreenshotUrl: `/api/av/screenshots/${videoId}/0`
  });
});

window.addEventListener('av-screenshots-queue-done', e => {
  if (e.detail.actressId !== currentActressId) return;
  fetch(`/api/av/actresses/${currentActressId}/videos`)
    .then(r => r.ok ? r.json() : null)
    .then(videos => {
      if (!videos) return;
      allVideos = videos;
      renderVideoGrid();
      const ctrl = document.getElementById('av-ss-controls');
      if (ctrl) mountScreenshotControls(ctrl, currentActressId, 0);
    })
    .catch(() => {});
});

// ── Utilities ─────────────────────────────────────────────────────────────
function formatBytes(bytes) {
  if (!bytes) return '';
  if (bytes < 1024)         return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 ** 3)   return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  return (bytes / 1024 ** 3).toFixed(2) + ' GB';
}
