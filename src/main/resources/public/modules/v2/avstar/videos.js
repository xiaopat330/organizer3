/* ─────────────────────────────────────────────────────────────────────
   v2/avstar/videos.js
   Video toolbar (search + column slider) and card grid.
   Delegates card clicks to the openVideoDetail callback.
   ───────────────────────────────────────────────────────────────────── */

// ── State ─────────────────────────────────────────────────────────────────

const COLS_KEY     = 'avd-grid-cols';
const COLS_DEFAULT = 4;
const COLS_VALUES  = [2, 3, 4, 5, 6];

let _allVideos   = [];
let _videoFilter = '';
let _gridCols    = COLS_DEFAULT;
let _onCardClick = null;  // (videoId) => void

// ── Utils ─────────────────────────────────────────────────────────────────

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function fmtBytes(bytes) {
  if (!bytes) return '';
  if (bytes < 1024)       return bytes + ' B';
  if (bytes < 1048576)    return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
  return (bytes / 1073741824).toFixed(2) + ' GB';
}

function _getSavedCols() {
  const saved = parseInt(localStorage.getItem(COLS_KEY), 10);
  return COLS_VALUES.includes(saved) ? saved : COLS_DEFAULT;
}

// ── Public API ────────────────────────────────────────────────────────────

/** Mount the toolbar + grid into rightEl; call onCardClick(videoId) on card click. */
export function mountVideosPanel(rightEl, videos, onCardClick) {
  _allVideos   = videos;
  _videoFilter = '';
  _gridCols    = _getSavedCols();
  _onCardClick = onCardClick;

  rightEl.innerHTML = `
    ${_renderToolbar()}
    <div id="avd-vc-grid" class="avd-vc-grid"></div>`;

  _applyGridCols(_gridCols);
  _wirePanel(rightEl);
  _renderGrid();
}

/** Called by index.js when an individual video's state changes. */
export function updateVideo(videoId, patch) {
  _allVideos = _allVideos.map(v => v.id === videoId ? { ...v, ...patch } : v);
  _renderGrid();
}

/** Called by index.js when the full video list refreshes (e.g. after screenshot queue done). */
export function refreshVideos(videos) {
  _allVideos = videos;
  _renderGrid();
}

// ── Toolbar ───────────────────────────────────────────────────────────────

function _renderToolbar() {
  const cols = _gridCols;
  return `
    <div class="avd-vc-toolbar">
      <input type="search" id="avd-vc-search" class="avd-vc-search"
             placeholder="filter…" autocomplete="off" spellcheck="false"
             value="${esc(_videoFilter)}">
      <div class="avd-vc-cols-ctrl">
        <span class="avd-vc-cols-caption">cols</span>
        <input type="range" id="avd-vc-cols-slider" class="avd-vc-cols-slider"
               min="${COLS_VALUES[0]}" max="${COLS_VALUES[COLS_VALUES.length - 1]}" step="1"
               value="${cols}">
        <span id="avd-vc-cols-label" class="avd-vc-cols-label">${cols}</span>
      </div>
      <span id="avd-vc-count" class="avd-vc-count"></span>
    </div>`;
}

// ── Grid ──────────────────────────────────────────────────────────────────

function _applyGridCols(cols) {
  const grid = document.getElementById('avd-vc-grid');
  if (grid) grid.style.gridTemplateColumns = `repeat(${cols}, 1fr)`;
}

function _renderGrid() {
  const grid = document.getElementById('avd-vc-grid');
  if (!grid) return;

  const q = _videoFilter.trim().toLowerCase();
  let data = _allVideos.slice();
  if (q) {
    data = data.filter(v =>
      v.filename.toLowerCase().includes(q) ||
      (v.parsedTitle || '').toLowerCase().includes(q) ||
      (v.relativePath || '').toLowerCase().includes(q)
    );
  }

  const countEl = document.getElementById('avd-vc-count');
  if (countEl) countEl.textContent = `${data.length} video${data.length === 1 ? '' : 's'}`;

  grid.innerHTML = data.length === 0
    ? '<div class="avd-vc-empty">No videos found.</div>'
    : data.map(_makeVideoCard).join('');
}

// ── Video card ────────────────────────────────────────────────────────────

function _makeVideoCard(v) {
  const count = v.screenshotCount || 0;

  let thumbHtml;
  if (count > 1) {
    // Marquee: animated tile loop (×2 UNIQUE set, -50% scroll)
    const perTileSec  = 3 + Math.random() * 2;
    const durationSec = count * perTileSec;
    const delayMs     = -(Math.random() * durationSec * 1000).toFixed(0);
    const tiles = Array.from({ length: count * 2 }, (_, i) =>
      `<div class="avd-vc-marquee-tile"><img src="/api/av/screenshots/${v.id}/${i % count}" alt="" loading="lazy"></div>`
    ).join('');
    thumbHtml = `<div class="avd-vc-marquee-track"
        style="animation-duration:${durationSec.toFixed(2)}s;animation-delay:${delayMs}ms">${tiles}</div>`;
  } else if (v.firstScreenshotUrl) {
    thumbHtml = `<img class="avd-vc-thumb" src="${esc(v.firstScreenshotUrl)}" alt="" loading="lazy">`;
  } else {
    thumbHtml = `<div class="avd-vc-thumb-placeholder">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"
           stroke-linecap="round" stroke-linejoin="round" width="32" height="32">
        <polygon points="5,3 19,12 5,21"/>
      </svg>
    </div>`;
  }

  const watchedBadge = v.watched
    ? `<div class="avd-vc-watched-badge">✓${v.watchCount > 1 ? ` ${v.watchCount}×` : ''}</div>`
    : '';

  const title = v.parsedTitle && v.parsedTitle !== v.filename
    ? esc(v.parsedTitle)
    : esc(v.filename);

  const metaParts = [v.resolution, v.sizeBytes ? fmtBytes(v.sizeBytes) : null, v.studio, v.releaseDate]
    .filter(Boolean);
  const metaHtml = metaParts.length > 0
    ? `<div class="avd-vc-meta">${metaParts.map(esc).join(' · ')}</div>`
    : '';

  const tags = v.tags || [];
  const tagsHtml = tags.length > 0
    ? `<div class="avd-vc-tags">${tags.map(t => `<span class="avd-vc-tag">${esc(t)}</span>`).join('')}</div>`
    : '';

  // Bookmark icon (two-state SVGs matching legacy icon-btn convention)
  const bmClass = `icon-btn sm avd-vc-bm-btn${v.bookmark ? ' on' : ''}`;
  const bmIcon = v.bookmark
    ? `<svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V3a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z" fill="currentColor"/></svg>`
    : `<svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V3a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z" fill="none" stroke="currentColor" stroke-width="2"/></svg>`;

  const favIcon = v.favorite
    ? `<svg class="avd-vc-fav-icon" viewBox="0 0 24 24" width="14" height="14"><polygon points="12 2 15 9 22 9 17 14 18 21 12 17 6 21 7 14 2 9 9 9" fill="#f59e0b"/></svg>`
    : '';

  return `
    <div class="avd-video-card" data-video-id="${v.id}">
      <div class="avd-vc-thumb-wrap">
        ${thumbHtml}
        ${watchedBadge}
      </div>
      <div class="avd-vc-body">
        <div class="avd-vc-header">
          <button class="${bmClass}" data-type="bookmark" data-id="${v.id}" title="Bookmark">${bmIcon}</button>
          ${favIcon}
          <span class="avd-vc-title" title="${esc(v.filename)}">${title}</span>
        </div>
        ${metaHtml}
        ${tagsHtml}
      </div>
    </div>`;
}

// ── Wire ──────────────────────────────────────────────────────────────────

function _wirePanel(rightEl) {
  // Search
  rightEl.querySelector('#avd-vc-search')?.addEventListener('input', e => {
    _videoFilter = e.target.value;
    _renderGrid();
  });

  // Column slider
  const slider = rightEl.querySelector('#avd-vc-cols-slider');
  const label  = rightEl.querySelector('#avd-vc-cols-label');
  if (slider) {
    slider.addEventListener('input', () => {
      const cols = parseInt(slider.value, 10);
      if (COLS_VALUES.includes(cols)) {
        _gridCols = cols;
        if (label) label.textContent = cols;
        _applyGridCols(cols);
        localStorage.setItem(COLS_KEY, String(cols));
      }
    });
  }

  // Grid delegated clicks
  const grid = rightEl.querySelector('#avd-vc-grid');
  if (!grid) return;

  grid.addEventListener('click', async e => {
    // Bookmark toggle
    const bmBtn = e.target.closest('[data-type="bookmark"]');
    if (bmBtn) {
      e.stopPropagation();
      const vid = parseInt(bmBtn.dataset.id, 10);
      const cur = _allVideos.find(v => v.id === vid);
      if (!cur) return;
      const res = await fetch(`/api/av/videos/${vid}/bookmark?value=${!cur.bookmark}`, { method: 'POST' });
      if (res.ok) {
        const d = await res.json();
        updateVideo(vid, { bookmark: d.bookmark });
      }
      return;
    }

    // Card click → open video detail
    const card = e.target.closest('.avd-video-card');
    if (card) {
      const vid = parseInt(card.dataset.videoId, 10);
      if (!isNaN(vid) && _onCardClick) _onCardClick(vid);
    }
  });
}
