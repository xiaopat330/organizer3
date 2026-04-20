import { esc, fmtDate, timeAgo } from './utils.js';
import { ICON_FAV_LG, ICON_BM_LG, gradeBadgeHtml, tagBadgeHtml } from './icons.js';
import { showView, updateBreadcrumb, mode } from './grid.js';
import { makeTitleCard, updateCardIndicators } from './cards.js';
import { getActressBrowseMode, actressBrowseLabel, selectActressBrowseMode, showActressLanding } from './actress-browse.js';
import { THUMBNAIL_COLUMNS } from './config.js';
import { pushNav } from './nav.js';

// ── Visit tracking ────────────────────────────────────────────────────────
let pendingVisitTimer = null;

// ── Known video durations (videoId → seconds) ─────────────────────────────
const videoDurations = {};

// ── Thumbnail poll timer tracking ─────────────────────────────────────────
const activePollTimers = new Set();

export function cancelVideoPolling() {
  for (const id of activePollTimers) clearTimeout(id);
  activePollTimers.clear();
}

export function cancelPendingVisit() {
  if (pendingVisitTimer !== null) {
    clearTimeout(pendingVisitTimer);
    pendingVisitTimer = null;
  }
}

// ── Open title detail ─────────────────────────────────────────────────────
export async function openTitleDetail(t) {
  pushNav({ view: 'title-detail', title: t }, 'title/' + encodeURIComponent(t.code));
  cancelPendingVisit();
  cancelVideoPolling();

  const sourceMode          = mode;
  const sourceHomeTab       = window._homeTab || 'latest';

  // If navigating from actress detail, that counts as an immediate actress visit.
  if (sourceMode === 'actress-detail') {
    const ad = await import('./actress-detail.js');
    const actressId = ad.detailActressId;
    if (actressId) {
      ad.cancelPendingVisit();
      fetch(`/api/actresses/${actressId}/visit`, { method: 'POST' }).catch(() => {});
    }
  }

  // Capture title-browse state at call time — dynamic import avoids circular static dep
  const tb = await import('./title-browse.js');
  const sourceTitleBrowseMode = tb.getTitleBrowseMode();
  const sourceActiveTags      = tb.getActiveTags();

  showView('title-detail');
  document.getElementById('title-detail-cover').innerHTML = '';
  document.getElementById('title-detail-info').innerHTML  = '';
  document.getElementById('title-detail-right').innerHTML =
    '<div id="title-video-container"></div><div id="title-more-container"></div>';

  renderTitleDetail(t);
  loadLastWatched(t.code);
  loadTitleVideos(t.code);
  loadMoreFromActress(t);

  // Start the 5-second visit timer for this title.
  const titleCode = t.code;
  pendingVisitTimer = setTimeout(() => {
    pendingVisitTimer = null;
    fetch(`/api/titles/${encodeURIComponent(titleCode)}/visit`, { method: 'POST' })
      .then(r => r.json())
      .then(data => updateTitleVisitedRow(data.visitCount, data.lastVisitedAt))
      .catch(() => {});
  }, 5000);

  // Breadcrumb
  let crumbs = [];
  if (sourceMode === 'actresses' || sourceMode === 'actress-detail') {
    crumbs = [{ label: 'Actresses', action: () => showActressLanding() }];
    const abMode = getActressBrowseMode();
    if (abMode) {
      crumbs.push({ label: actressBrowseLabel(abMode), action: () => selectActressBrowseMode(abMode) });
    }
  } else if (sourceMode === 'titles-browse' && sourceTitleBrowseMode === 'collections') {
    crumbs = [{ label: 'Collections', action: () => document.getElementById('title-collections-btn').click() }];
  } else if (sourceMode === 'titles-browse' && sourceTitleBrowseMode === 'unsorted') {
    crumbs = [{ label: 'Unsorted', action: () => document.getElementById('title-unsorted-btn').click() }];
  } else if (sourceMode === 'titles-browse' && sourceTitleBrowseMode === 'archive-pool') {
    crumbs = [{ label: 'Archives', action: () => document.getElementById('title-archives-btn').click() }];
  } else if (sourceMode === 'titles-browse' && sourceTitleBrowseMode === 'tags') {
    const tagCount = sourceActiveTags ? sourceActiveTags.size : 0;
    crumbs = [{ label: tagCount > 0 ? `Tags (${tagCount})` : 'Tags', action: () => document.getElementById('title-tags-btn').click() }];
  } else if (sourceMode === 'titles-browse') {
    crumbs = [{ label: 'Titles', action: () => document.getElementById('titles-browse-btn').click() }];
  }
  crumbs.push({ label: t.code });
  updateBreadcrumb(crumbs);
}

// ── Render title detail panel ─────────────────────────────────────────────
function renderTitleDetail(t) {
  const coverEl = document.getElementById('title-detail-cover');
  const infoEl  = document.getElementById('title-detail-info');

  // Cover
  if (t.coverUrl) {
    coverEl.innerHTML = `<img src="${esc(t.coverUrl)}" alt="${esc(t.code)}" loading="lazy">`;
  } else {
    coverEl.innerHTML = `<div class="title-detail-cover-placeholder">${esc(t.code)}</div>`;
  }

  // Titles — Japanese first (large bold white), then English
  const hasJa = !!t.titleOriginal;
  const hasEn = !!t.titleEnglish;
  const jaTitleHtml = hasJa ? `<div class="title-detail-title-ja">${esc(t.titleOriginal)}</div>` : '';
  const enClass = hasJa ? 'title-detail-title-en title-detail-title-en--secondary' : 'title-detail-title-en';
  const enTitleHtml = hasEn ? `<div class="${enClass}">${esc(t.titleEnglish)}</div>` : '';

  // Actresses — prefer multi-actress list, fall back to single actressId
  const actresses = (t.actresses && t.actresses.length > 0)
    ? t.actresses
    : (t.actressId ? [{ id: t.actressId, name: t.actressName, tier: t.actressTier }] : []);

  let actressesHtml = '';
  if (actresses.length > 0) {
    const lines = actresses
      .filter(a => a.name)
      .map(a => {
        const spaceIdx = a.name.indexOf(' ');
        let nameHtml;
        if (spaceIdx === -1) {
          nameHtml = `<span class="title-detail-actress-first">${esc(a.name)}</span>`;
        } else {
          const first = a.name.slice(0, spaceIdx);
          const last  = a.name.slice(spaceIdx + 1);
          nameHtml = `<span class="title-detail-actress-first">${esc(first)}</span> <span class="title-detail-actress-last">${esc(last)}</span>`;
        }
        const tierHtml = a.tier
          ? ` <span class="tier-badge tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>`
          : '';
        return `<div class="title-detail-actress-row"><a class="actress-link title-detail-actress-link" href="#" data-actress-id="${a.id}">${nameHtml}${tierHtml}</a></div>`;
      })
      .join('');
    actressesHtml = `<div class="title-detail-row">
      <span class="title-detail-label">Cast</span>
      <span class="title-detail-value title-detail-cast">${lines}</span>
    </div>`;
  }

  // Label / company
  let labelHtml = '';
  if (t.companyName || t.labelName) {
    let text = '';
    if (t.companyName) text += `<span class="title-detail-company">${esc(t.companyName)}</span>`;
    if (t.labelName)   text += ` <span class="title-detail-label-name">(${esc(t.labelName)})</span>`;
    labelHtml = `<div class="title-detail-row">
      <span class="title-detail-label">Label</span>
      <span class="title-detail-value">${text}</span>
    </div>`;
  }

  // Date
  const displayDate = t.releaseDate || t.addedDate;
  const dateLabel   = t.releaseDate ? 'Released' : 'Added';
  const dateHtml = displayDate ? `<div class="title-detail-row">
    <span class="title-detail-label">${dateLabel}</span>
    <span class="title-detail-value">${esc(fmtDate(displayDate))}</span>
  </div>` : '';

  // Tags
  const tags = t.tags || [];
  const tagsHtml = tags.length > 0 ? `<div class="title-detail-row">
    <span class="title-detail-label">Tags</span>
    <span class="title-detail-value title-detail-tags">${tags.map(tagBadgeHtml).join('')}</span>
  </div>` : '';

  // Grade
  const gradeHtml = t.grade ? `<div class="title-detail-row title-detail-grade-row">
    <span class="title-detail-label title-detail-grade-label">Grade</span>
    <span class="title-detail-value">${gradeBadgeHtml(t.grade)}</span>
  </div>` : '';

  // NAS paths
  const paths = t.nasPaths || [];
  const nasHtml = paths.length > 0 ? `<div class="title-detail-row title-detail-nas-row">
    <span class="title-detail-label title-detail-location-label">Location</span>
    <span class="title-detail-value title-detail-nas-paths">${paths.map(p => `<div class="title-detail-nas-path">${esc(p)}</div>`).join('')}</span>
  </div>` : '';

  infoEl.innerHTML = `
    ${jaTitleHtml}
    ${enTitleHtml}
    <div class="title-detail-code">${esc(t.code)}</div>
    <div class="title-detail-actions">
      <button class="title-action-btn${t.favorite ? ' active' : ''}" id="title-fav-btn" title="Favorite">${ICON_FAV_LG}</button>
      <button class="title-action-btn${t.bookmark ? ' active' : ''}" id="title-bm-btn" title="Bookmark">${ICON_BM_LG}</button>
    </div>
    <div class="title-detail-meta">
      ${actressesHtml}
      ${labelHtml}
      ${dateHtml}
      ${gradeHtml}
      <div class="title-detail-row title-detail-watched-row" id="title-detail-watched" style="display:none">
        <span class="title-detail-label">Watched</span>
        <span class="title-detail-value title-detail-watched-value" id="title-detail-watched-value"></span>
      </div>
      <div class="title-detail-row title-detail-visited-row" id="title-detail-visited" style="${t.visitCount > 0 ? '' : 'display:none'}">
        <span class="title-detail-label">Visited</span>
        <span class="title-detail-value" id="title-detail-visited-value">${t.visitCount > 0 ? formatVisited(t.visitCount, t.lastVisitedAt) : ''}</span>
      </div>
      ${nasHtml}
      ${tagsHtml}
    </div>
  `;

  document.getElementById('title-fav-btn').addEventListener('click', () => {
    fetch(`/api/titles/${encodeURIComponent(t.code)}/favorite`, { method: 'POST' })
      .then(r => r.json())
      .then(data => {
        t.favorite = data.favorite;
        document.getElementById('title-fav-btn').classList.toggle('active', data.favorite);
        updateCardIndicators(t.code, t.favorite, t.bookmark);
      });
  });

  document.getElementById('title-bm-btn').addEventListener('click', () => {
    fetch(`/api/titles/${encodeURIComponent(t.code)}/bookmark`, { method: 'POST' })
      .then(r => r.json())
      .then(data => {
        t.bookmark = data.bookmark;
        document.getElementById('title-bm-btn').classList.toggle('active', data.bookmark);
        updateCardIndicators(t.code, t.favorite, t.bookmark);
      });
  });

  infoEl.querySelectorAll('.actress-link').forEach(link => {
    link.addEventListener('click', e => {
      e.preventDefault();
      import('./actress-detail.js').then(m => m.openActressDetail(Number(link.dataset.actressId)));
    });
  });
}

// ── More from same actress ────────────────────────────────────────────────
async function loadMoreFromActress(t) {
  const actressId   = t.actressId || (t.actresses && t.actresses.length === 1 ? t.actresses[0].id : null);
  const actressName = t.actressName || (t.actresses && t.actresses.length === 1 ? t.actresses[0].name : null);
  const container   = document.getElementById('title-more-container');
  if (!actressId || !container) return;

  container.innerHTML = '<div class="more-from-loading">Loading more titles\u2026</div>';

  try {
    const res    = await fetch(`/api/actresses/${actressId}/titles?limit=13`);
    const titles = await res.json();
    const others = titles.filter(x => x.code !== t.code).slice(0, 12);
    if (others.length === 0) { container.innerHTML = ''; return; }

    const section = document.createElement('div');
    section.className = 'more-from-section';
    section.innerHTML = `<div class="more-from-heading">More from <span class="more-from-name">${esc(actressName || '')}</span></div>`;

    const row = document.createElement('div');
    row.className = 'more-from-row';
    others.forEach(other => row.appendChild(makeTitleCard(other)));
    section.appendChild(row);
    container.innerHTML = '';
    container.appendChild(section);
  } catch (e) {
    container.innerHTML = '';
  }
}

// ── Title videos ──────────────────────────────────────────────────────────
function loadTitleVideos(titleCode) {
  const rightCol = document.getElementById('title-video-container');
  rightCol.innerHTML = '<div class="video-loading">Discovering videos\u2026</div>';

  fetch(`/api/titles/${encodeURIComponent(titleCode)}/videos`)
    .then(r => r.json())
    .then(videos => {
      if (!videos || videos.length === 0) {
        rightCol.innerHTML = '<div class="video-empty">No video files found</div>';
        return;
      }
      rightCol.innerHTML = '';
      videos.forEach((v, idx) => {
        if (idx > 0) {
          rightCol.appendChild(Object.assign(document.createElement('hr'), {
            className: 'video-divider'
          }));
        }
        rightCol.appendChild(renderVideoSection(v, titleCode));
      });
    })
    .catch(() => {
      rightCol.innerHTML = '<div class="video-empty">Could not load videos</div>';
    });
}

export function renderVideoSection(v, titleCode, { thumbnails = true } = {}) {
  const section = document.createElement('div');
  section.className = 'video-section';

  const sizeStr = v.fileSize != null ? formatFileSize(v.fileSize) : '';
  section.innerHTML = `
    <div class="video-header">
      <span class="video-filename">${esc(v.filename)}</span>
      ${sizeStr ? `<span class="video-size">${esc(sizeStr)}</span>` : ''}
      <span class="video-meta" id="video-meta-${v.id}"></span>
      ${v.folderUrl ? `
        <a class="video-folder-link" href="${esc(v.folderUrl)}">
          <svg width="11" height="11" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M1 3.5A1.5 1.5 0 0 1 2.5 2H5l1 1.5h3.5A1.5 1.5 0 0 1 11 5v4a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 1 9V3.5z"/>
          </svg>
          Open folder
        </a>
        <button class="video-folder-copy">
          <svg width="11" height="11" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="4" y="4" width="7" height="7" rx="1.2"/>
            <path d="M8 4V2.5A1.5 1.5 0 0 0 6.5 1H2.5A1.5 1.5 0 0 0 1 2.5v4A1.5 1.5 0 0 0 2.5 8H4"/>
          </svg>
          Copy path
        </button>` : ''}
    </div>
    ${thumbnails ? `<div class="video-thumbs" id="video-thumbs-${v.id}">
      <div class="video-thumbs-loading">Loading previews\u2026</div>
    </div>` : ''}
    <div class="video-player-wrap" id="video-wrap-${v.id}">
      <video class="video-player" id="video-player-${v.id}" controls preload="none"
             src="/api/stream/${v.id}"
             type="${esc(v.mimeType)}">
      </video>
      <button class="theater-btn">Theater</button>
    </div>
  `;

  if (v.folderUrl) {
    const copyBtn = section.querySelector('.video-folder-copy');
    if (copyBtn) copyBtn.addEventListener('click', () => copyFolderPath(copyBtn, v.folderUrl));
  }

  const theaterBtn = section.querySelector('.theater-btn');
  if (theaterBtn) theaterBtn.addEventListener('click', () => toggleTheater(v.id));

  if (thumbnails) loadVideoThumbnails(v.id);
  loadVideoMetadata(v.id);

  const player = section.querySelector(`#video-player-${v.id}`);
  if (player) initResumePlayback(player, v.id, titleCode);

  return section;
}

function loadVideoThumbnails(videoId, attempt = 0) {
  const MAX_ATTEMPTS = 60;
  fetch(`/api/videos/${videoId}/thumbnails`)
    .then(r => r.json())
    .then(data => {
      const container = document.getElementById(`video-thumbs-${videoId}`);
      if (!container) return;

      const urls = data.urls || [];
      const total = data.total || 10;
      const generating = data.generating;

      if (urls.length > 0) {
        container.style.gridTemplateColumns = `repeat(${THUMBNAIL_COLUMNS}, 1fr)`;
        container.innerHTML = urls.map((url, i) => {
          const fraction = total > 1 ? 0.03 + (0.94 * i / (total - 1)) : 0.5;
          return `<div class="thumb-wrapper" data-fraction="${fraction}">
            <img class="video-thumb" src="${esc(url)}" loading="lazy" data-fraction="${fraction}">
            <span class="thumb-time" data-video-id="${videoId}" data-fraction="${fraction}">--:--</span>
          </div>`;
        }).join('');
        container.onclick = e => {
          const wrapper = e.target.closest('.thumb-wrapper');
          if (wrapper) seekVideoTo(videoId, parseFloat(wrapper.dataset.fraction));
        };
        if (videoDurations[videoId]) {
          updateThumbTimestamps(videoId, videoDurations[videoId]);
        }
      }

      if (urls.length < total && (generating || attempt < 3)) {
        let progressEl = document.getElementById(`video-thumb-progress-${videoId}`);
        if (!progressEl) {
          progressEl = document.createElement('div');
          progressEl.id = `video-thumb-progress-${videoId}`;
          progressEl.className = 'thumb-progress';
          container.parentNode.insertBefore(progressEl, container.nextSibling);
        }
        const pct = Math.round((urls.length / total) * 100);
        progressEl.innerHTML = `<div class="thumb-progress-bar"><div class="thumb-progress-fill" style="width:${pct}%"></div></div>`
          + `<span class="thumb-progress-text">${urls.length}/${total} previews</span>`;

        if (attempt < MAX_ATTEMPTS) {
          const timerId = setTimeout(() => {
            activePollTimers.delete(timerId);
            loadVideoThumbnails(videoId, attempt + 1);
          }, 2000);
          activePollTimers.add(timerId);
        }
      } else {
        const progressEl = document.getElementById(`video-thumb-progress-${videoId}`);
        if (progressEl) progressEl.remove();

        if (urls.length === 0) {
          container.innerHTML = '<span class="video-thumbs-loading">no preview available</span>';
        }
      }
    })
    .catch(() => {
      const container = document.getElementById(`video-thumbs-${videoId}`);
      if (container) container.innerHTML = '';
    });
}

function loadVideoMetadata(videoId) {
  fetch(`/api/videos/${videoId}/info`)
    .then(r => r.json())
    .then(info => {
      const el = document.getElementById(`video-meta-${videoId}`);
      if (!el || !info || !info.duration) return;
      const parts = [info.duration];
      if (info.resolution) parts.push(info.resolution);
      if (info.videoCodec) parts.push(info.videoCodec);
      if (info.bitrate) parts.push(info.bitrate);
      el.textContent = parts.join(' \u00b7 ');
      if (info.durationSeconds) {
        videoDurations[videoId] = info.durationSeconds;
        updateThumbTimestamps(videoId, info.durationSeconds);
      }
    })
    .catch(() => {});
}

// ── Timestamp formatting ──────────────────────────────────────────────────
function formatTimestamp(seconds) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function updateThumbTimestamps(videoId, durationSeconds) {
  document.querySelectorAll(`.thumb-time[data-video-id="${videoId}"]`).forEach(el => {
    const fraction = parseFloat(el.dataset.fraction);
    el.textContent = formatTimestamp(fraction * durationSeconds);
  });
}

// ── Folder path copy ──────────────────────────────────────────────────────
function copyFolderPath(btn, smbUrl) {
  const isMac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
  const text = isMac
    ? smbUrl
    : smbUrl.replace(/^smb:\/\//, '\\\\').replace(/\//g, '\\');

  const confirm = () => {
    const orig = btn.textContent;
    btn.textContent = 'Copied!';
    setTimeout(() => { btn.textContent = orig; }, 1500);
  };

  if (navigator.clipboard) {
    navigator.clipboard.writeText(text).then(confirm);
  } else {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.cssText = 'position:fixed;opacity:0';
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    confirm();
  }
}
// ── Seek + Theater ────────────────────────────────────────────────────────
function seekVideoTo(videoId, fraction) {
  const player = document.getElementById(`video-player-${videoId}`);
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

function toggleTheater(videoId) {
  const wrap = document.getElementById(`video-wrap-${videoId}`);
  if (!wrap) return;
  const isActive = wrap.classList.toggle('theater-mode');
  const btn = wrap.querySelector('.theater-btn');
  if (btn) btn.textContent = isActive ? 'Exit Theater' : 'Theater';
  const leftPanel = document.querySelector('.title-detail-left');
  if (leftPanel) leftPanel.classList.toggle('theater-dimmed', isActive);
}

// ── Resume playback ───────────────────────────────────────────────────────
function initResumePlayback(player, videoId, titleCode) {
  const key = `resume_${videoId}`;

  let watchRecorded = false;
  player.addEventListener('play', () => {
    if (!watchRecorded && titleCode) {
      watchRecorded = true;
      fetch(`/api/watch-history/${encodeURIComponent(titleCode)}`, { method: 'POST' })
        .then(() => loadLastWatched(titleCode))
        .catch(() => {});
    }
  });

  let saveInterval = null;
  player.addEventListener('play', () => {
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

  player.addEventListener('pause', () => { clearInterval(saveInterval); });
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
        showResumeToast(player, data.time);
      }
    } catch (e) { /* ignore corrupt data */ }
  });
}

function showResumeToast(player, time) {
  const wrap = player.closest('.video-player-wrap');
  if (!wrap) return;
  const toast = document.createElement('div');
  toast.className = 'resume-toast';
  toast.textContent = 'Resuming from ' + formatTimestamp(time);
  wrap.appendChild(toast);
  setTimeout(() => toast.remove(), 3000);
}

// ── Visit tracking helpers ────────────────────────────────────────────────
function formatVisited(count, lastVisitedAt) {
  const countLabel = count === 1 ? '1 view' : `${count} views`;
  return lastVisitedAt ? `${countLabel} (Visited ${timeAgo(lastVisitedAt)})` : countLabel;
}

function updateTitleVisitedRow(visitCount, lastVisitedAt) {
  const row = document.getElementById('title-detail-visited');
  const val = document.getElementById('title-detail-visited-value');
  if (!row || !val || visitCount <= 0) return;
  val.textContent = formatVisited(visitCount, lastVisitedAt || null);
  row.style.display = '';
}

// ── Watch history ─────────────────────────────────────────────────────────
function loadLastWatched(titleCode) {
  fetch(`/api/watch-history/${encodeURIComponent(titleCode)}`)
    .then(r => r.json())
    .then(history => {
      const row = document.getElementById('title-detail-watched');
      const val = document.getElementById('title-detail-watched-value');
      if (!row || !val) return;
      if (history.length === 0) {
        row.style.display = 'none';
        return;
      }
      const latest = history[0];
      val.textContent = timeAgo(latest.watchedAt);
      if (history.length > 1) {
        val.textContent += ` (${history.length} times)`;
      }
      row.style.display = '';
    })
    .catch(() => {});
}

function formatFileSize(bytes) {
  if (bytes < 0) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}
