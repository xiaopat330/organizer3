import { esc, timeAgo } from './utils.js';
import { THUMBNAIL_COLUMNS } from './config.js';
import { ICON_FAV_LG, ICON_BM_LG } from './icons.js';

// ── State ─────────────────────────────────────────────────────────────────
let currentVideo = null;
let _onBack      = null;

// Known video duration (seconds) for thumbnail timestamp labels
let _durationSec = null;

// ── Entry point ───────────────────────────────────────────────────────────
export async function openAvVideoDetail(videoId, onBack) {
  let v;
  try {
    const res = await fetch(`/api/av/videos/${videoId}`);
    if (!res.ok) return;
    v = await res.json();
  } catch {
    return;
  }
  currentVideo  = v;
  _onBack       = onBack || null;
  _durationSec  = null;

  const rightEl = document.getElementById('av-detail-right');
  if (!rightEl) return;
  rightEl.innerHTML = '';
  rightEl.appendChild(_renderPanel(v));
  _wirePanel(v);

  if ((v.screenshotUrls || []).length === 0) {
    _generateScreenshots(v.id);
  }
}

// ── Panel render ──────────────────────────────────────────────────────────
function _renderPanel(v) {
  const panel = document.createElement('div');
  panel.className = 'av-vpanel';
  panel.innerHTML = `
    <div class="av-vpanel-header">
      <button class="av-vpanel-back-btn" id="av-vpanel-back">← Videos</button>
      <span class="av-vpanel-title" title="${esc(v.filename)}">${esc(v.parsedTitle || v.filename)}</span>
    </div>
    <div class="av-vpanel-body" id="av-vpanel-body">
      <div class="video-section">
        <div class="video-header">
          <span class="video-filename">${esc(v.filename)}</span>
          ${v.sizeBytes ? `<span class="video-size">${_formatBytes(v.sizeBytes)}</span>` : ''}
          <span class="video-meta" id="av-vpanel-meta-line">${_buildMetaLine(v)}</span>
          ${v.smbUrl ? `
            <div class="video-folder-actions">
              <button class="video-folder-copy" id="av-vpanel-copy-btn">Copy path</button>
              <a class="video-folder-link" href="${esc(v.smbUrl)}" title="Open in player (macOS/Safari)">Open in player</a>
            </div>
          ` : ''}
        </div>
        ${_renderThumbs(v)}
        <div class="video-player-wrap" id="av-vpanel-wrap-${v.id}">
          <video class="video-player" id="av-vpanel-player-${v.id}" controls preload="none"
                 src="/api/av/stream/${v.id}">
          </video>
          <button class="theater-btn">Theater</button>
        </div>
      </div>
      <div class="av-vpanel-actions" id="av-vpanel-actions">
        ${_renderActions(v)}
      </div>
      ${_renderMeta(v)}
    </div>`;
  return panel;
}

function _buildMetaLine(v) {
  const parts = [v.resolution, v.codec].filter(Boolean);
  return parts.map(esc).join(' · ');
}

function _renderThumbs(v) {
  const urls = v.screenshotUrls || [];
  if (urls.length === 0) {
    return `<div class="video-thumbs-loading" id="av-vpanel-thumbs-loading-${v.id}" style="padding:8px 0">
      Generating previews…
    </div>`;
  }
  return _buildThumbsHtml(v.id, urls);
}

function _buildThumbsHtml(videoId, urls) {
  const cols = Math.min(urls.length, THUMBNAIL_COLUMNS);
  const thumbs = urls.map((url, i) => {
    const fraction = 0.05 + i * 0.10;
    return `<div class="thumb-wrapper" data-fraction="${fraction}">
      <img class="video-thumb" src="${esc(url)}" loading="lazy" data-fraction="${fraction}">
      <span class="thumb-time" data-video-id="av-${videoId}" data-fraction="${fraction}">--:--</span>
    </div>`;
  }).join('');
  return `<div class="video-thumbs" id="av-vpanel-thumbs-${videoId}"
               style="grid-template-columns: repeat(${cols}, 1fr)">${thumbs}</div>`;
}

async function _generateScreenshots(videoId) {
  try {
    const res = await fetch(`/api/av/videos/${videoId}/screenshots`, { method: 'POST' });
    if (!res.ok) return;
    const data = await res.json();
    const urls = data.screenshotUrls || [];
    if (urls.length === 0) return;

    const loadingEl = document.getElementById(`av-vpanel-thumbs-loading-${videoId}`);
    if (!loadingEl) return;
    loadingEl.outerHTML = _buildThumbsHtml(videoId, urls);

    // Wire seek on new thumbs
    const thumbsEl = document.getElementById(`av-vpanel-thumbs-${videoId}`);
    if (thumbsEl) {
      thumbsEl.addEventListener('click', e => {
        const wrapper = e.target.closest('.thumb-wrapper');
        if (wrapper) _seekTo(videoId, parseFloat(wrapper.dataset.fraction));
      });
    }

    // Apply timestamps if duration already known
    if (_durationSec) _updateThumbTimestamps(videoId, _durationSec);

    // Notify the actress detail grid so the video card can show the marquee
    window.dispatchEvent(new CustomEvent('av-screenshots-generated', {
      detail: { videoId, count: urls.length }
    }));
  } catch {
    const loadingEl = document.getElementById(`av-vpanel-thumbs-loading-${videoId}`);
    if (loadingEl) loadingEl.textContent = 'Preview generation failed.';
  }
}

function _renderActions(v) {
  return `
    <button class="title-action-btn${v.favorite ? ' active' : ''}" id="av-vpanel-fav-btn" title="Favorite">${ICON_FAV_LG}</button>
    <button class="title-action-btn${v.bookmark ? ' active' : ''}" id="av-vpanel-bm-btn" title="Bookmark">${ICON_BM_LG}</button>`;
}

function _renderMeta(v) {
  const rows = [];
  if (v.studio)       rows.push(['Studio',     esc(v.studio)]);
  if (v.releaseDate)  rows.push(['Date',        esc(v.releaseDate)]);
  if (v.bucket)       rows.push(['Bucket',      esc(v.bucket)]);
  if (v.relativePath) rows.push(['Path',        esc(v.relativePath)]);
  if (v.watched && v.lastWatchedAt)
                      rows.push(['Watched', _formatWatched(v.watchCount, v.lastWatchedAt), 'av-vpanel-watched-row']);
  const tags = v.tags || [];
  if (tags.length > 0) rows.push(['Tags', tags.map(t => `<span class="av-vc-tag">${esc(t)}</span>`).join('')]);

  if (rows.length === 0) return '';
  return `<dl class="av-vpanel-meta">
    ${rows.map(([k, val, id]) => `<dt${id ? ` id="${id}-dt"` : ''}>${k}</dt><dd${id ? ` id="${id}"` : ''}>${val}</dd>`).join('')}
  </dl>`;
}

// ── Wire interactions ─────────────────────────────────────────────────────
function _wirePanel(v) {
  // Back button
  document.getElementById('av-vpanel-back')?.addEventListener('click', () => {
    if (_onBack) _onBack();
  });

  // Copy SMB path
  const copyBtn = document.getElementById('av-vpanel-copy-btn');
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
  const wrap = document.getElementById(`av-vpanel-wrap-${v.id}`);
  wrap?.querySelector('.theater-btn')?.addEventListener('click', () => {
    const isActive = wrap.classList.toggle('theater-mode');
    const btn = wrap.querySelector('.theater-btn');
    if (btn) btn.textContent = isActive ? 'Exit Theater' : 'Theater';
    document.querySelector('.av-detail-sidebar')?.classList.toggle('theater-dimmed', isActive);
  });

  // Thumbnail seek
  const thumbsEl = document.getElementById(`av-vpanel-thumbs-${v.id}`);
  if (thumbsEl) {
    thumbsEl.addEventListener('click', e => {
      const wrapper = e.target.closest('.thumb-wrapper');
      if (wrapper) _seekTo(v.id, parseFloat(wrapper.dataset.fraction));
    });
  }

  // Video player — duration → timestamp labels + resume + auto-watch
  const player = document.getElementById(`av-vpanel-player-${v.id}`);
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
  document.getElementById('av-vpanel-fav-btn')?.addEventListener('click', async () => {
    const newVal = !currentVideo.favorite;
    const res = await fetch(`/api/av/videos/${currentVideo.id}/favorite?value=${newVal}`, { method: 'POST' });
    if (res.ok) {
      const d = await res.json();
      currentVideo = { ...currentVideo, favorite: d.favorite };
      _refreshActions();
    }
  });

  document.getElementById('av-vpanel-bm-btn')?.addEventListener('click', async () => {
    const newVal = !currentVideo.bookmark;
    const res = await fetch(`/api/av/videos/${currentVideo.id}/bookmark?value=${newVal}`, { method: 'POST' });
    if (res.ok) {
      const d = await res.json();
      currentVideo = { ...currentVideo, bookmark: d.bookmark };
      _refreshActions();
    }
  });
}

function _refreshActions() {
  const el = document.getElementById('av-vpanel-actions');
  if (el) el.innerHTML = _renderActions(currentVideo);
  // Re-wire action buttons (innerHTML replaced)
  document.getElementById('av-vpanel-fav-btn')?.addEventListener('click', async () => {
    const newVal = !currentVideo.favorite;
    const res = await fetch(`/api/av/videos/${currentVideo.id}/favorite?value=${newVal}`, { method: 'POST' });
    if (res.ok) { const d = await res.json(); currentVideo = { ...currentVideo, favorite: d.favorite }; _refreshActions(); }
  });
  document.getElementById('av-vpanel-bm-btn')?.addEventListener('click', async () => {
    const newVal = !currentVideo.bookmark;
    const res = await fetch(`/api/av/videos/${currentVideo.id}/bookmark?value=${newVal}`, { method: 'POST' });
    if (res.ok) { const d = await res.json(); currentVideo = { ...currentVideo, bookmark: d.bookmark }; _refreshActions(); }
  });
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
        currentVideo = { ...currentVideo, watched: d.watched, watchCount: d.watchCount, lastWatchedAt: d.lastWatchedAt };
        // Update the watched row in the meta section without re-rendering the whole panel
        _refreshWatchedMeta();
      })
      .catch(() => {});
  });
}

function _refreshWatchedMeta() {
  if (!currentVideo.watched || !currentVideo.lastWatchedAt) return;
  const text = _formatWatched(currentVideo.watchCount, currentVideo.lastWatchedAt);
  const dd = document.getElementById('av-vpanel-watched-row');
  if (dd) {
    dd.textContent = text;
    return;
  }
  // Row not yet in DOM (video was never watched before) — append dt+dd to the meta dl
  const dl = document.querySelector('.av-vpanel-meta');
  if (!dl) return;
  const dt = document.createElement('dt');
  dt.textContent = 'Watched';
  const newDd = document.createElement('dd');
  newDd.id = 'av-vpanel-watched-row';
  newDd.textContent = text;
  dl.appendChild(dt);
  dl.appendChild(newDd);
}

function _formatWatched(count, lastWatchedAt) {
  const times = count === 1 ? '1 time' : `${count} times`;
  return lastWatchedAt ? `${times} · last ${timeAgo(lastWatchedAt)}` : times;
}

// ── Seek ──────────────────────────────────────────────────────────────────
function _seekTo(videoId, fraction) {
  const player = document.getElementById(`av-vpanel-player-${videoId}`);
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
function _formatTimestamp(seconds) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function _updateThumbTimestamps(videoId, durationSeconds) {
  document.querySelectorAll(`.thumb-time[data-video-id="av-${videoId}"]`).forEach(el => {
    el.textContent = _formatTimestamp(parseFloat(el.dataset.fraction) * durationSeconds);
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
          ts: Date.now()
        }));
      }
    }, 5000);
  });

  player.addEventListener('pause',  () => clearInterval(saveInterval));
  player.addEventListener('ended',  () => { clearInterval(saveInterval); localStorage.removeItem(key); });

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
    } catch (e) { /* ignore */ }
  });
}

function _showResumeToast(player, time) {
  const wrap = player.closest('.video-player-wrap');
  if (!wrap) return;
  const toast = document.createElement('div');
  toast.className = 'resume-toast';
  toast.textContent = 'Resuming from ' + _formatTimestamp(time);
  wrap.appendChild(toast);
  setTimeout(() => toast.remove(), 3000);
}

// ── Utilities ─────────────────────────────────────────────────────────────
function _formatBytes(bytes) {
  if (!bytes) return '';
  if (bytes < 1024)        return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 ** 3)   return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  return (bytes / 1024 ** 3).toFixed(2) + ' GB';
}
