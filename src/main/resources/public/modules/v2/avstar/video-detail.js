/* ─────────────────────────────────────────────────────────────────────
   v2/avstar/video-detail.js
   In-pane video detail view (player + screenshots + metadata + actions).
   Swaps the right pane; back button restores the grid (same pattern as
   legacy av-video-detail.js, no URL routing needed).
   ───────────────────────────────────────────────────────────────────── */

const THUMBNAIL_COLUMNS_DEFAULT = 5; // matches legacy default from config.js
const THUMBNAIL_COLUMNS_MIN     = 3;
const THUMBNAIL_COLUMNS_MAX     = 8;
const THUMBNAIL_COLUMNS_KEY     = 'v2-avstar-thumbs-cols';

function _getThumbCols() {
  const saved = parseInt(localStorage.getItem(THUMBNAIL_COLUMNS_KEY), 10);
  if (Number.isFinite(saved)
      && saved >= THUMBNAIL_COLUMNS_MIN
      && saved <= THUMBNAIL_COLUMNS_MAX) return saved;
  return THUMBNAIL_COLUMNS_DEFAULT;
}

// ── State ─────────────────────────────────────────────────────────────────
let _currentVideo = null;
let _onBack       = null;
let _durationSec  = null;

// ── Utils ─────────────────────────────────────────────────────────────────

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function _fmtBytes(bytes) {
  if (!bytes) return '';
  if (bytes < 1024)       return bytes + ' B';
  if (bytes < 1048576)    return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
  return (bytes / 1073741824).toFixed(2) + ' GB';
}

function _fmtTimestamp(seconds) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function _fmtWatched(count, lastWatchedAt) {
  const times = count === 1 ? '1 time' : `${count} times`;
  if (!lastWatchedAt) return times;
  const d = new Date(lastWatchedAt);
  if (isNaN(d)) return times;
  const sec = Math.floor((Date.now() - d.getTime()) / 1000);
  let ago;
  if (sec < 60)      ago = `${sec}s ago`;
  else if (sec < 3600) ago = `${Math.floor(sec / 60)}m ago`;
  else if (sec < 86400) ago = `${Math.floor(sec / 3600)}h ago`;
  else               ago = `${Math.floor(sec / 86400)}d ago`;
  return `${times} · last ${ago}`;
}

// ── Entry point ───────────────────────────────────────────────────────────

export async function openVideoDetail(videoId, onBack) {
  let v;
  try {
    const res = await fetch(`/api/av/videos/${videoId}`);
    if (!res.ok) return;
    v = await res.json();
  } catch {
    return;
  }
  _currentVideo = v;
  _onBack       = onBack || null;
  _durationSec  = null;

  const rightEl = document.getElementById('avd-right');
  if (!rightEl) return;
  rightEl.innerHTML = '';
  rightEl.appendChild(_renderPanel(v));
  _wirePanel(v);

  // Auto-generate screenshots if none exist yet
  if ((v.screenshotUrls || []).length === 0) {
    _generateScreenshots(v.id);
  }
}

// ── Panel render ──────────────────────────────────────────────────────────

function _renderPanel(v) {
  const panel = document.createElement('div');
  panel.className = 'avd-vpanel';
  panel.innerHTML = `
    <div class="avd-vpanel-header">
      <button class="btn sm ghost avd-vpanel-back" id="avd-vpanel-back">← Videos</button>
      <span class="avd-vpanel-title" title="${esc(v.filename)}">${esc(v.parsedTitle || v.filename)}</span>
    </div>
    <div class="avd-vpanel-body" id="avd-vpanel-body">
      <div class="video-section">
        <div class="video-header">
          <span class="video-filename">${esc(v.filename)}</span>
          ${v.sizeBytes ? `<span class="video-size">${esc(_fmtBytes(v.sizeBytes))}</span>` : ''}
          <span class="video-meta" id="avd-vpanel-meta-line">${esc(_buildMetaLine(v))}</span>
          ${v.smbUrl ? `
            <div class="video-folder-actions">
              <button class="btn sm" id="avd-vpanel-copy-btn">Copy path</button>
              <a class="video-folder-link" href="${esc(v.smbUrl)}" title="Open in player (macOS/Safari)">Open in player</a>
            </div>` : ''}
        </div>
        ${_renderThumbs(v)}
        <div class="video-player-wrap" id="avd-vpanel-wrap-${v.id}">
          <video class="video-player" id="avd-vpanel-player-${v.id}" controls preload="none"
                 src="/api/av/stream/${v.id}">
          </video>
          <button class="theater-btn">Theater</button>
        </div>
      </div>
      <div class="avd-vpanel-actions" id="avd-vpanel-actions">
        ${_renderActions(v)}
      </div>
      ${_renderMeta(v)}
    </div>`;
  return panel;
}

function _buildMetaLine(v) {
  return [v.resolution, v.codec].filter(Boolean).map(esc).join(' · ');
}

function _renderThumbsColsCtrl(videoId) {
  const cols = _getThumbCols();
  return `<div class="avd-vpanel-thumbs-ctrl" id="avd-vpanel-thumbs-ctrl-${videoId}">
    <span class="avd-vpanel-thumbs-ctrl-caption">cols</span>
    <input type="range" class="avd-vpanel-thumbs-slider"
           id="avd-vpanel-thumbs-slider-${videoId}"
           min="${THUMBNAIL_COLUMNS_MIN}" max="${THUMBNAIL_COLUMNS_MAX}" step="1"
           value="${cols}">
    <span class="avd-vpanel-thumbs-ctrl-label"
          id="avd-vpanel-thumbs-label-${videoId}">${cols}</span>
  </div>`;
}

function _renderThumbs(v) {
  const urls = v.screenshotUrls || [];
  if (urls.length === 0) {
    return `${_renderThumbsColsCtrl(v.id)}
      <div class="video-thumbs-loading" id="avd-vpanel-thumbs-loading-${v.id}">
      Generating previews…
    </div>`;
  }
  return `${_renderThumbsColsCtrl(v.id)}${_buildThumbsHtml(v.id, urls)}`;
}

function _buildThumbsHtml(videoId, urls) {
  const cols = Math.min(urls.length, _getThumbCols());
  const thumbs = urls.map((url, i) => {
    const fraction = 0.05 + i * 0.10;
    return `<div class="thumb-wrapper" data-fraction="${fraction}">
      <img class="video-thumb" src="${esc(url)}" loading="lazy" data-fraction="${fraction}">
      <span class="thumb-time" data-video-id="avd-${videoId}" data-fraction="${fraction}">--:--</span>
    </div>`;
  }).join('');
  return `<div class="video-thumbs" id="avd-vpanel-thumbs-${videoId}"
               style="grid-template-columns: repeat(${cols}, 1fr)">${thumbs}</div>`;
}

function _wireThumbsColsSlider(videoId) {
  const slider = document.getElementById(`avd-vpanel-thumbs-slider-${videoId}`);
  const label  = document.getElementById(`avd-vpanel-thumbs-label-${videoId}`);
  if (!slider) return;
  slider.addEventListener('input', () => {
    const n = parseInt(slider.value, 10);
    if (!Number.isFinite(n)) return;
    const cols = Math.max(THUMBNAIL_COLUMNS_MIN, Math.min(THUMBNAIL_COLUMNS_MAX, n));
    if (label) label.textContent = cols;
    localStorage.setItem(THUMBNAIL_COLUMNS_KEY, String(cols));
    const grid = document.getElementById(`avd-vpanel-thumbs-${videoId}`);
    if (grid) {
      const tileCount = grid.querySelectorAll('.thumb-wrapper').length;
      const applied = tileCount > 0 ? Math.min(tileCount, cols) : cols;
      grid.style.gridTemplateColumns = `repeat(${applied}, 1fr)`;
    }
  });
}

function _renderActions(v) {
  return `
    <button class="icon-btn ${v.favorite ? 'on' : ''}" id="avd-vpanel-fav-btn" title="Favorite">
      <svg viewBox="0 0 24 24"><polygon points="12 2 15 9 22 9 17 14 18 21 12 17 6 21 7 14 2 9 9 9"/></svg>
    </button>
    <button class="icon-btn ${v.bookmark ? 'on' : ''}" id="avd-vpanel-bm-btn" title="Bookmark">
      <svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V3a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
    </button>`;
}

function _renderMeta(v) {
  const rows = [];
  if (v.studio)       rows.push(['Studio',  esc(v.studio), null]);
  if (v.releaseDate)  rows.push(['Date',    esc(v.releaseDate), null]);
  if (v.bucket)       rows.push(['Bucket',  esc(v.bucket), null]);
  if (v.relativePath) rows.push(['Path',    esc(v.relativePath), null]);
  if (v.watched && v.lastWatchedAt)
    rows.push(['Watched', _fmtWatched(v.watchCount, v.lastWatchedAt), 'avd-vpanel-watched-row']);
  const tags = v.tags || [];
  if (tags.length > 0)
    rows.push(['Tags', tags.map(t => `<span class="avd-vc-tag">${esc(t)}</span>`).join(''), null]);

  if (rows.length === 0) return '';
  return `<dl class="avd-vpanel-meta">
    ${rows.map(([k, val, id]) =>
      `<dt${id ? ` id="${id}-dt"` : ''}>${k}</dt><dd${id ? ` id="${id}"` : ''}>${val}</dd>`
    ).join('')}
  </dl>`;
}

// ── Wire ──────────────────────────────────────────────────────────────────

function _wirePanel(v) {
  // Back button
  document.getElementById('avd-vpanel-back')?.addEventListener('click', () => {
    if (_onBack) _onBack();
  });

  // Copy SMB path
  const copyBtn = document.getElementById('avd-vpanel-copy-btn');
  if (copyBtn && v.smbUrl) {
    copyBtn.addEventListener('click', () => {
      navigator.clipboard?.writeText(v.smbUrl).then(() => {
        const orig = copyBtn.textContent;
        copyBtn.textContent = 'Copied!';
        setTimeout(() => { copyBtn.textContent = orig; }, 1500);
      }).catch(() => {});
    });
  }

  // Theater mode
  const wrap = document.getElementById(`avd-vpanel-wrap-${v.id}`);
  wrap?.querySelector('.theater-btn')?.addEventListener('click', () => {
    const isActive = wrap.classList.toggle('theater-mode');
    const btn = wrap.querySelector('.theater-btn');
    if (btn) btn.textContent = isActive ? 'Exit Theater' : 'Theater';
    // Dim the left rail (sidebar) in theater mode — mirrors legacy
    document.querySelector('.avd-rail')?.classList.toggle('theater-dimmed', isActive);
  });

  // Thumbnail column slider
  _wireThumbsColsSlider(v.id);

  // Thumbnail seek
  const thumbsEl = document.getElementById(`avd-vpanel-thumbs-${v.id}`);
  if (thumbsEl) {
    thumbsEl.addEventListener('click', e => {
      const wrapper = e.target.closest('.thumb-wrapper');
      if (wrapper) _seekTo(v.id, parseFloat(wrapper.dataset.fraction));
    });
  }

  // Video player: timestamps + resume + auto-watch
  const player = document.getElementById(`avd-vpanel-player-${v.id}`);
  if (player) {
    player.addEventListener('loadedmetadata', () => {
      if (player.duration && isFinite(player.duration)) {
        _durationSec = player.duration;
        _updateThumbTimestamps(v.id, player.duration);
      }
    });
    _initResume(player, v.id);
    _initAutoWatch(player, v.id);
  }

  // Action buttons
  _wireActionButtons(v);
}

function _wireActionButtons(v) {
  document.getElementById('avd-vpanel-fav-btn')?.addEventListener('click', async () => {
    const newVal = !_currentVideo.favorite;
    const res = await fetch(`/api/av/videos/${_currentVideo.id}/favorite?value=${newVal}`, { method: 'POST' });
    if (res.ok) {
      const d = await res.json();
      _currentVideo = { ..._currentVideo, favorite: d.favorite };
      _refreshActions();
    }
  });

  document.getElementById('avd-vpanel-bm-btn')?.addEventListener('click', async () => {
    const newVal = !_currentVideo.bookmark;
    const res = await fetch(`/api/av/videos/${_currentVideo.id}/bookmark?value=${newVal}`, { method: 'POST' });
    if (res.ok) {
      const d = await res.json();
      _currentVideo = { ..._currentVideo, bookmark: d.bookmark };
      _refreshActions();
    }
  });
}

function _refreshActions() {
  const el = document.getElementById('avd-vpanel-actions');
  if (el) el.innerHTML = _renderActions(_currentVideo);
  _wireActionButtons(_currentVideo);
}

// ── Screenshot generation (on open when none exist) ────────────────────

async function _generateScreenshots(videoId) {
  try {
    const res = await fetch(`/api/av/videos/${videoId}/screenshots`, { method: 'POST' });
    if (!res.ok) return;
    const data = await res.json();
    const urls = data.screenshotUrls || [];
    if (urls.length === 0) return;

    const loadingEl = document.getElementById(`avd-vpanel-thumbs-loading-${videoId}`);
    if (!loadingEl) return;
    loadingEl.outerHTML = _buildThumbsHtml(videoId, urls);

    // Wire seek on newly generated thumbnails
    const thumbsEl = document.getElementById(`avd-vpanel-thumbs-${videoId}`);
    if (thumbsEl) {
      thumbsEl.addEventListener('click', e => {
        const wrapper = e.target.closest('.thumb-wrapper');
        if (wrapper) _seekTo(videoId, parseFloat(wrapper.dataset.fraction));
      });
    }

    if (_durationSec) _updateThumbTimestamps(videoId, _durationSec);

    // Notify grid so the video card can show the marquee
    window.dispatchEvent(new CustomEvent('av-screenshots-generated', {
      detail: { videoId, count: urls.length },
    }));
  } catch {
    const loadingEl = document.getElementById(`avd-vpanel-thumbs-loading-${videoId}`);
    if (loadingEl) loadingEl.textContent = 'Preview generation failed.';
  }
}

// ── Auto-watch on first play ──────────────────────────────────────────────

function _initAutoWatch(player, videoId) {
  let recorded = false;
  player.addEventListener('play', () => {
    if (recorded) return;
    recorded = true;
    fetch(`/api/av/videos/${videoId}/watch`, { method: 'POST' })
      .then(r => r.ok ? r.json() : null)
      .then(d => {
        if (!d) return;
        _currentVideo = {
          ..._currentVideo,
          watched: d.watched,
          watchCount: d.watchCount,
          lastWatchedAt: d.lastWatchedAt,
        };
        _refreshWatchedMeta();
      })
      .catch(() => {});
  });
}

function _refreshWatchedMeta() {
  if (!_currentVideo.watched || !_currentVideo.lastWatchedAt) return;
  const text = _fmtWatched(_currentVideo.watchCount, _currentVideo.lastWatchedAt);
  const dd = document.getElementById('avd-vpanel-watched-row');
  if (dd) {
    dd.textContent = text;
    return;
  }
  // Row not yet in DOM (video was never watched before) — append dt+dd to meta dl
  const dl = document.querySelector('.avd-vpanel-meta');
  if (!dl) return;
  const dt = document.createElement('dt');
  dt.textContent = 'Watched';
  const newDd = document.createElement('dd');
  newDd.id = 'avd-vpanel-watched-row';
  newDd.textContent = text;
  dl.appendChild(dt);
  dl.appendChild(newDd);
}

// ── Seek ──────────────────────────────────────────────────────────────────

function _seekTo(videoId, fraction) {
  const player = document.getElementById(`avd-vpanel-player-${videoId}`);
  if (!player) return;

  function doSeek() {
    if (player.duration && isFinite(player.duration)) {
      player.currentTime = player.duration * fraction;
      if (player.paused) player.play();
    }
  }

  if (player.readyState >= 1) {
    doSeek();
  } else {
    player.preload = 'metadata';
    player.addEventListener('loadedmetadata', doSeek, { once: true });
    player.load();
  }
}

// ── Thumbnail timestamps ──────────────────────────────────────────────────

function _updateThumbTimestamps(videoId, durationSeconds) {
  document.querySelectorAll(`.thumb-time[data-video-id="avd-${videoId}"]`).forEach(el => {
    el.textContent = _fmtTimestamp(parseFloat(el.dataset.fraction) * durationSeconds);
  });
}

// ── Resume playback ───────────────────────────────────────────────────────

function _initResume(player, videoId) {
  const key = `av_resume_${videoId}`;

  let saveInterval = null;
  player.addEventListener('play', () => {
    clearInterval(saveInterval);
    saveInterval = setInterval(() => {
      if (player.currentTime > 5) {
        localStorage.setItem(key, JSON.stringify({
          time: player.currentTime,
          duration: player.duration,
          ts: Date.now(),
        }));
      }
    }, 5000);
  });

  player.addEventListener('pause', () => clearInterval(saveInterval));
  player.addEventListener('ended', () => {
    clearInterval(saveInterval);
    localStorage.removeItem(key);
  });

  let resumed = false;
  player.addEventListener('loadedmetadata', () => {
    if (resumed) return;
    resumed = true;
    const saved = localStorage.getItem(key);
    if (!saved) return;
    try {
      const data = JSON.parse(saved);
      const pct = data.time / data.duration;
      if (pct > 0.05 && pct < 0.90 && data.time > 10) {
        player.currentTime = data.time;
        _showResumeToast(player, data.time);
      }
    } catch (_) { /* ignore */ }
  });
}

function _showResumeToast(player, time) {
  const wrap = player.closest('.video-player-wrap');
  if (!wrap) return;
  const toast = document.createElement('div');
  toast.className = 'resume-toast';
  toast.textContent = 'Resuming from ' + _fmtTimestamp(time);
  wrap.appendChild(toast);
  setTimeout(() => toast.remove(), 3000);
}
