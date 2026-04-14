import { esc } from './utils.js';

// ── State ─────────────────────────────────────────────────────────────────
let modalEl = null;
let isOpen  = false;
let currentVideo = null;

// ── Public API ────────────────────────────────────────────────────────────

export async function openAvVideoDetail(videoId) {
  let v;
  try {
    const res = await fetch(`/api/av/videos/${videoId}`);
    if (!res.ok) return;
    v = await res.json();
  } catch {
    return;
  }
  currentVideo = v;
  _ensureModal();
  _render(v);
  modalEl.style.display = 'flex';
  isOpen = true;
  document.addEventListener('keydown', _onKeyDown);
}

export function closeAvVideoModal() {
  if (!isOpen || !modalEl) return;
  isOpen = false;
  modalEl.style.display = 'none';
  document.removeEventListener('keydown', _onKeyDown);
  currentVideo = null;
}

// ── Modal scaffold ────────────────────────────────────────────────────────

function _ensureModal() {
  if (modalEl) return;
  modalEl = document.createElement('div');
  modalEl.id = 'av-video-modal';
  modalEl.className = 'av-video-modal';
  document.body.appendChild(modalEl);

  // Click on backdrop → close
  modalEl.addEventListener('click', e => {
    if (e.target === modalEl) closeAvVideoModal();
  });
}

function _onKeyDown(e) {
  if (e.key === 'Escape') closeAvVideoModal();
}

// ── Render ────────────────────────────────────────────────────────────────

function _render(v) {
  modalEl.innerHTML = `
    <div class="av-vm-panel">
      <div class="av-vm-header">
        <span class="av-vm-filename" title="${esc(v.relativePath)}">${esc(v.filename)}</span>
        <button class="av-vm-close" aria-label="Close">✕</button>
      </div>
      <div class="av-vm-body">
        ${_renderScreenshots(v)}
        ${_renderActionBar(v)}
        ${v.parsedTitle ? `<div class="av-vm-parsed-title">${esc(v.parsedTitle)}</div>` : ''}
        ${_renderMeta(v)}
        ${_renderWatchSection(v)}
        ${v.smbUrl ? _renderPlaySection(v.smbUrl) : ''}
      </div>
    </div>`;

  // Close button
  modalEl.querySelector('.av-vm-close').addEventListener('click', closeAvVideoModal);

  // Action bar buttons
  _wireActions(v);
}

function _renderScreenshots(v) {
  const urls = v.screenshotUrls || [];
  if (urls.length === 0) {
    return `<div class="av-vm-screenshots-empty">
      No screenshots — run <code>av screenshots &lt;actress&gt;</code> in the terminal
    </div>`;
  }
  const thumbs = urls.map((url, i) =>
    `<img class="av-vm-thumb${i === 0 ? ' active' : ''}" src="${esc(url)}" data-idx="${i}" alt="Screenshot ${i + 1}">`
  ).join('');
  return `
    <div class="av-vm-screenshots">
      <div class="av-vm-carousel-wrap">
        <img class="av-vm-carousel-img" id="av-vm-carousel-img" src="${esc(urls[0])}" alt="Screenshot">
      </div>
      <div class="av-vm-thumbs" id="av-vm-thumbs">${thumbs}</div>
    </div>`;
}

function _renderActionBar(v) {
  const favCls = v.favorite ? ' active' : '';
  const bmCls  = v.bookmark ? ' active' : '';
  const watchCls = v.watched ? ' active' : '';
  return `
    <div class="av-vm-actions">
      <button class="av-vm-btn av-vm-fav-btn${favCls}" data-action="favorite"
              aria-pressed="${v.favorite}" title="${v.favorite ? 'Remove favorite' : 'Add favorite'}">
        <svg viewBox="0 0 24 24" width="14" height="14"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26" fill="${v.favorite ? 'currentColor' : 'none'}" stroke="currentColor" stroke-width="2"/></svg>
        ${v.favorite ? 'Favorited' : 'Favorite'}
      </button>
      <button class="av-vm-btn av-vm-bm-btn${bmCls}" data-action="bookmark"
              aria-pressed="${v.bookmark}" title="${v.bookmark ? 'Remove bookmark' : 'Bookmark'}">
        <svg viewBox="0 0 24 24" width="14" height="14"><path d="M6 2h12a1 1 0 0 1 1 1v18.5a.5.5 0 0 1-.8.4L12 17.5 5.8 21.9a.5.5 0 0 1-.8-.4V3a1 1 0 0 1 1-1z" fill="${v.bookmark ? 'currentColor' : 'none'}" stroke="currentColor" stroke-width="2"/></svg>
        ${v.bookmark ? 'Bookmarked' : 'Bookmark'}
      </button>
      <button class="av-vm-btn av-vm-watch-btn${watchCls}" data-action="watch"
              aria-pressed="${v.watched}" title="Mark watched">
        ✓ ${v.watched ? `Watched ${v.watchCount}×` : 'Mark watched'}
      </button>
    </div>`;
}

function _renderMeta(v) {
  const rows = [];
  if (v.relativePath) rows.push(['Path',       esc(v.relativePath)]);
  if (v.bucket)       rows.push(['Bucket',     esc(v.bucket)]);
  if (v.sizeBytes)    rows.push(['Size',       _formatBytes(v.sizeBytes)]);
  if (v.resolution)   rows.push(['Resolution', esc(v.resolution)]);
  if (v.codec)        rows.push(['Codec',      esc(v.codec)]);
  if (v.studio)       rows.push(['Studio',     esc(v.studio)]);
  if (v.releaseDate)  rows.push(['Date',       esc(v.releaseDate)]);
  if (v.volumeId)     rows.push(['Volume',     esc(v.volumeId)]);
  if (rows.length === 0) return '';
  return `
    <dl class="av-vm-meta">
      ${rows.map(([k, val]) => `<dt>${k}</dt><dd>${val}</dd>`).join('')}
    </dl>`;
}

function _renderWatchSection(v) {
  if (!v.watched) return '';
  const last = v.lastWatchedAt ? ` — last ${v.lastWatchedAt.substring(0, 10)}` : '';
  return `<div class="av-vm-watched-badge">✓ Watched ${v.watchCount}×${last}</div>`;
}

function _renderPlaySection(smbUrl) {
  return `
    <div class="av-vm-play-section">
      <div class="av-vm-smb-url" id="av-vm-smb-url-text">${esc(smbUrl)}</div>
      <div class="av-vm-play-btns">
        <button class="av-vm-copy-btn" id="av-vm-copy-btn">Copy path</button>
        <a class="av-vm-open-btn" href="${esc(smbUrl)}" title="Open in player (macOS/Safari)">Open in player</a>
      </div>
    </div>`;
}

// ── Wire interactive elements ─────────────────────────────────────────────

function _wireActions(v) {
  // Screenshot thumbnail clicks
  const thumbsEl = modalEl.querySelector('#av-vm-thumbs');
  if (thumbsEl) {
    thumbsEl.addEventListener('click', e => {
      const thumb = e.target.closest('img[data-idx]');
      if (!thumb) return;
      const mainImg = modalEl.querySelector('#av-vm-carousel-img');
      if (mainImg) mainImg.src = thumb.src;
      thumbsEl.querySelectorAll('.av-vm-thumb').forEach(t => t.classList.remove('active'));
      thumb.classList.add('active');
    });
  }

  // Copy button
  const copyBtn = modalEl.querySelector('#av-vm-copy-btn');
  if (copyBtn && v.smbUrl) {
    copyBtn.addEventListener('click', () => {
      navigator.clipboard.writeText(v.smbUrl).then(() => {
        copyBtn.textContent = 'Copied!';
        setTimeout(() => { copyBtn.textContent = 'Copy path'; }, 1500);
      }).catch(() => {
        // Fallback: select the text in the URL display
        const urlEl = modalEl.querySelector('#av-vm-smb-url-text');
        if (urlEl) {
          const sel = window.getSelection();
          const range = document.createRange();
          range.selectNodeContents(urlEl);
          sel.removeAllRanges();
          sel.addRange(range);
        }
      });
    });
  }

  // Favorite toggle
  modalEl.querySelector('[data-action="favorite"]')?.addEventListener('click', async () => {
    const newVal = !currentVideo.favorite;
    const res = await fetch(`/api/av/videos/${currentVideo.id}/favorite?value=${newVal}`, { method: 'POST' });
    if (res.ok) {
      const d = await res.json();
      currentVideo = { ...currentVideo, favorite: d.favorite };
      _render(currentVideo);
    }
  });

  // Bookmark toggle
  modalEl.querySelector('[data-action="bookmark"]')?.addEventListener('click', async () => {
    const newVal = !currentVideo.bookmark;
    const res = await fetch(`/api/av/videos/${currentVideo.id}/bookmark?value=${newVal}`, { method: 'POST' });
    if (res.ok) {
      const d = await res.json();
      currentVideo = { ...currentVideo, bookmark: d.bookmark };
      _render(currentVideo);
    }
  });

  // Mark watched
  modalEl.querySelector('[data-action="watch"]')?.addEventListener('click', async () => {
    const res = await fetch(`/api/av/videos/${currentVideo.id}/watch`, { method: 'POST' });
    if (res.ok) {
      const d = await res.json();
      currentVideo = { ...currentVideo, watched: d.watched, watchCount: d.watchCount };
      _render(currentVideo);
    }
  });
}

// ── Utilities ─────────────────────────────────────────────────────────────

function _formatBytes(bytes) {
  if (!bytes) return '';
  if (bytes < 1024)         return bytes + ' B';
  if (bytes < 1024 * 1024)  return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 ** 3)    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  return (bytes / 1024 ** 3).toFixed(2) + ' GB';
}
