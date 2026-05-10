/* ─────────────────────────────────────────────────────────────────────
   Wave 4 — Title detail (library mode, full port)
   Spec: spec/DESIGN_SYSTEM_PAGES.md §3.1
   Hero (cover + bilingual title + actions) + meta rows + Videos
   section (player + thumbnails + theater) + Cast shelf + More-from
   shelf.
   Deferred (with reason):
   - Tag editor modal: legacy modal lives in tag-editor.js (~600 LOC).
     v2 modal primitive exists; full editor port is its own commit.
     Edit button alerts for now.
   - Resume playback cookie: minor; per-video cookie that seeks on
     load. Add when we touch video state more deeply.
   ───────────────────────────────────────────────────────────────────── */

const COVER_ROOT = '/covers';
const THUMB_RATIOS = [
  { label: '1/3', factor: 1 / 3 },
  { label: '1/2', factor: 1 / 2 },
  { label: '2/3', factor: 2 / 3 },
];
const THUMB_DEFAULT = 2;

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
  } catch (e) { return fallback; }
}

function fmtDate(s) {
  if (!s) return '';
  if (/^\d{4}-\d{2}-\d{2}/.test(s)) return s.slice(0, 10);
  return String(s);
}

function ageAtDate(dob, releaseDate) {
  if (!dob || !releaseDate) return null;
  const born = new Date(dob);
  const released = new Date(releaseDate);
  if (isNaN(born) || isNaN(released)) return null;
  let age = released.getFullYear() - born.getFullYear();
  const mDiff = released.getMonth() - born.getMonth();
  if (mDiff < 0 || (mDiff === 0 && released.getDate() < born.getDate())) age--;
  return (age >= 14 && age <= 99) ? age : null;
}

function fmtBytes(b) {
  if (!b) return '';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
  return `${b.toFixed(b >= 100 ? 0 : 1)} ${u[i]}`;
}

function fmtTimestamp(seconds) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function getThumbIdx() {
  try { return parseInt(localStorage.getItem('v2.thumbCols')) || THUMB_DEFAULT; }
  catch (e) { return THUMB_DEFAULT; }
}
function setThumbIdx(n) {
  n = Math.max(1, Math.min(THUMB_RATIOS.length, n));
  try { localStorage.setItem('v2.thumbCols', String(n)); } catch (e) {}
  document.querySelectorAll('.video-thumbs').forEach(el => applyThumbGrid(el, n));
  document.querySelectorAll('.thumb-size-label').forEach(el => el.textContent = THUMB_RATIOS[n - 1].label);
  document.querySelectorAll('.thumb-size-slider').forEach(el => { if (+el.value !== n) el.value = n; });
}
function applyThumbGrid(container, n) {
  const w = Math.round(240 * THUMB_RATIOS[n - 1].factor);
  container.style.gridTemplateColumns = `repeat(auto-fill, ${w}px)`;
}

/* ── Hero render ───────────────────────────────────────────────────── */
function renderHero(t) {
  const titleCode = t.code || '';
  const enText = t.titleEnglish || t.titleOriginalEn || '';
  const isLlm  = !t.titleEnglish && !!t.titleOriginalEn;
  const llmBadge = isLlm ? `<span class="hero-title-badge" title="Auto-translated by AI">auto</span>` : '';

  const cover = t.coverUrl
    ? t.coverUrl
    : (t.coverPath ? `${COVER_ROOT}/${encodeURIComponent(t.coverPath)}` : `/api/cover/${encodeURIComponent(titleCode)}`);

  return `
    <div class="hero-band hero-band-title">
      <div class="hero-cover" id="hero-cover" style="background-image:url('${escapeHtml(cover)}');cursor:zoom-in" title="Click to enlarge"></div>
      <div class="hero-content">
        <div class="hero-eyebrow">Title</div>
        ${enText ? `<h1 class="hero-name">${escapeHtml(enText)}${llmBadge}</h1>` : ''}
        ${t.titleOriginal ? `<div class="hero-name-secondary">${escapeHtml(t.titleOriginal)}</div>` : ''}
        ${!enText && !t.titleOriginal ? `<h1 class="hero-name">${escapeHtml(titleCode)}</h1>` : ''}
        <div class="hero-code">${escapeHtml(titleCode)}</div>
        <div class="hero-actions">
          <button class="btn primary${t.favorite ? ' active' : ''}${t.rejected ? ' disabled' : ''}" id="btn-favorite" title="Favorite">
            <svg viewBox="0 0 24 24"><polygon points="12 2 15 9 22 9 17 14 18 21 12 17 6 21 7 14 2 9 9 9"/></svg>
            ${t.favorite ? 'Favorited' : 'Favorite'}
          </button>
          <button class="btn${t.bookmark ? ' active' : ''}${t.rejected ? ' disabled' : ''}" id="btn-bookmark" title="Bookmark">
            <svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V3a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
            ${t.bookmark ? 'Bookmarked' : 'Bookmark'}
          </button>
          <button class="btn danger${t.rejected ? ' active' : ''}" id="btn-reject" title="Reject">
            <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/></svg>
            ${t.rejected ? 'Rejected' : 'Reject'}
          </button>
        </div>
      </div>
    </div>
  `;
}

/* ── Meta rows render ──────────────────────────────────────────────── */
function renderMeta(t) {
  const titleCode = t.code || '';
  const actresses = (t.actresses && t.actresses.length > 0)
    ? t.actresses
    : (t.actressId ? [{ id: t.actressId, name: t.actressName, tier: t.actressTier, dateOfBirth: t.actressDateOfBirth }] : []);

  const castHtml = actresses.length === 0 ? '' : `
    <div class="td-row">
      <span class="td-label">Cast</span>
      <span class="td-value">
        ${actresses.map(a => {
          const tier = a.tier ? `<span class="tier-badge tier-${escapeHtml(a.tier.toLowerCase())}">${escapeHtml(a.tier.toLowerCase())}</span>` : '';
          const age = ageAtDate(a.dateOfBirth, t.releaseDate);
          const ageHtml = age != null ? ` <span class="td-age">age ${age}</span>` : '';
          return `<a class="td-cast-link" href="/v2-actress-detail.html?id=${encodeURIComponent(a.id)}">${escapeHtml(a.name)}</a>${tier}${ageHtml}`;
        }).join('<span class="td-sep">·</span>')}
      </span>
    </div>
  `;

  const labelHtml = (t.companyName || t.labelName) ? `
    <div class="td-row">
      <span class="td-label">Label</span>
      <span class="td-value">
        ${t.companyName ? escapeHtml(t.companyName) : ''}
        ${t.labelName && t.labelName !== t.companyName ? `<span style="color:var(--text-faint)"> (${escapeHtml(t.labelName)})</span>` : ''}
      </span>
    </div>
  ` : '';

  const date = t.releaseDate || t.addedDate;
  const dateLabel = t.releaseDate ? 'Released' : 'Added';
  const dateHtml = date ? `
    <div class="td-row">
      <span class="td-label">${dateLabel}</span>
      <span class="td-value mono">${escapeHtml(fmtDate(date))}</span>
    </div>` : '';

  let gradeHtml = '';
  if (t.grade) {
    const ratingExtra = (t.ratingAvg != null && t.ratingCount != null)
      ? ` <span style="color:var(--text-faint);font-size:11px">(${t.ratingAvg.toFixed(2)} · ${t.ratingCount.toLocaleString()} votes)</span>`
      : '';
    gradeHtml = `<div class="td-row"><span class="td-label">Grade</span>
      <span class="td-value"><span class="grade-badge grade-${escapeHtml(t.grade)}">${escapeHtml(t.grade)}</span>${ratingExtra}</span></div>`;
  } else {
    gradeHtml = `<div class="td-row"><span class="td-label">Grade</span><span class="td-value" style="color:var(--text-faint);font-style:italic">Not rated</span></div>`;
  }

  const visitedHtml = t.visitCount > 0 ? `
    <div class="td-row">
      <span class="td-label">Visited</span>
      <span class="td-value mono">${t.visitCount}× · last ${escapeHtml(fmtDate(t.lastVisitedAt))}</span>
    </div>` : '';

  const watchedHtml = t.watchCount > 0 ? `
    <div class="td-row">
      <span class="td-label">Watched</span>
      <span class="td-value mono">${t.watchCount}× · last ${escapeHtml(fmtDate(t.lastWatchedAt))}</span>
    </div>` : '';

  const paths = t.nasPaths || [];
  const nasHtml = paths.length === 0 ? '' : `
    <div class="td-row">
      <span class="td-label">Location</span>
      <span class="td-value">
        ${paths.map(p => `<div class="td-path mono" data-path="${escapeHtml(p)}" title="Click to copy">${escapeHtml(p)}</div>`).join('')}
      </span>
    </div>`;

  const tags = t.tags || [];
  const tagsInner = tags.length > 0
    ? tags.map(tag => `<span class="td-tag">${escapeHtml(tag)}</span>`).join('')
    : `<span style="color:var(--text-faint);font-style:italic">No tags</span>`;
  const tagsHtml = `
    <div class="td-row">
      <span class="td-label">Tags <button class="td-tag-edit" id="btn-edit-tags" title="Edit tags">edit</button></span>
      <span class="td-value">${tagsInner}</span>
    </div>
    <div class="td-row" id="td-enrichment-row" style="display:none">
      <span class="td-label">Javdb</span>
      <span class="td-value" id="td-enrichment-value"></span>
    </div>`;

  return `
    <section class="td-meta">
      ${castHtml}
      ${labelHtml}
      ${dateHtml}
      ${gradeHtml}
      ${watchedHtml}
      ${visitedHtml}
      ${nasHtml}
      ${tagsHtml}
    </section>
  `;
}

/* ── Cover lightbox ────────────────────────────────────────────────── */
function showLightbox(url, code) {
  const el = document.createElement('div');
  el.className = 'modal-backdrop';
  el.innerHTML = `
    <div style="margin:auto;max-width:90vw;max-height:85vh;cursor:zoom-out">
      <img src="${escapeHtml(url)}" alt="${escapeHtml(code)}" style="max-width:90vw;max-height:85vh;border-radius:8px;box-shadow:0 20px 60px rgba(0,0,0,0.6)">
    </div>
  `;
  document.body.appendChild(el);
  const dismiss = () => el.remove();
  el.addEventListener('click', dismiss);
  document.addEventListener('keydown', function onEsc(e) {
    if (e.key === 'Escape') { dismiss(); document.removeEventListener('keydown', onEsc); }
  });
}

/* ── Path-copy helper (cross-platform formatting) ──────────────────── */
function copyPath(el) {
  const raw = el.dataset.path || el.textContent.trim();
  const ua = navigator.platform || navigator.userAgent;
  const isWin = /Win/.test(ua);
  const isMac = /Mac|iPhone|iPad/.test(ua);
  let text = raw;
  if (isWin)      text = raw.replace(/^\/\//, '\\\\').replace(/\//g, '\\');
  else if (isMac) text = raw.startsWith('//') ? 'smb:' + raw : raw;
  navigator.clipboard.writeText(text).then(() => {
    const orig = el.style.color;
    const origText = el.textContent;
    el.style.color = 'var(--ok)';
    el.textContent = 'copied!';
    setTimeout(() => { el.style.color = orig; el.textContent = origText; }, 900);
  }).catch(() => alert('Copy failed; path: ' + text));
}

/* ── Tag-state load (badges with source) ───────────────────────────── */
async function loadTagState(code) {
  const state = await fetchJson(`/api/titles/${encodeURIComponent(code)}/tag-state`, null);
  if (!state) return;
  const valueEl = document.querySelector('.td-row .td-value');
  // Tags row is the LAST row before enrichment row; query by id pattern
  const allRows = document.querySelectorAll('.td-row');
  // Find tags row by checking for the edit button
  let tagsValueEl = null;
  for (const row of allRows) {
    if (row.querySelector('#btn-edit-tags')) {
      tagsValueEl = row.querySelector('.td-value');
      break;
    }
  }
  if (!tagsValueEl) return;
  const direct = state.directTags || [];
  const implied = state.labelImpliedTags || [];
  const enriched = state.enrichmentImpliedTags || [];
  if (direct.length === 0 && implied.length === 0 && enriched.length === 0) {
    tagsValueEl.innerHTML = `<span style="color:var(--text-faint);font-style:italic">No tags</span>`;
    return;
  }
  let html = '';
  for (const t of direct)   html += `<span class="td-tag" title="Directly tagged">${escapeHtml(t)}</span>`;
  for (const t of implied)  html += `<span class="td-tag derived" title="Implied by label">${escapeHtml(t)}</span>`;
  for (const t of enriched) html += `<span class="td-tag derived" title="From JavDB enrichment">${escapeHtml(t)}</span>`;
  tagsValueEl.innerHTML = html;
}

async function loadEnrichmentTags(code) {
  const tags = await fetchJson(`/api/titles/${encodeURIComponent(code)}/enrichment-tags`, []);
  if (!tags || tags.length === 0) return;
  const row   = document.querySelector('#td-enrichment-row');
  const value = document.querySelector('#td-enrichment-value');
  if (!row || !value) return;
  row.style.display = '';
  value.innerHTML = tags.map(t => {
    const name = typeof t === 'string' ? t : (t.name || '');
    const alias = (typeof t === 'object' && t.curatedAlias) ? ` → ${t.curatedAlias}` : '';
    return `<span class="td-tag raw" title="JavDB raw tag${alias}">${escapeHtml(name)}${alias ? ` <span style="color:var(--accent)">${escapeHtml(alias)}</span>` : ''}</span>`;
  }).join('');
}

/* ── Watched / visited polish via /api/watch-history/{code} ────────── */
async function loadWatchHistory(code) {
  const list = await fetchJson(`/api/watch-history/${encodeURIComponent(code)}`, []);
  // (currently informational only; the meta render already shows watchCount/lastWatchedAt
  //  baked into the title summary. This endpoint exists if we want richer detail later.)
  return list;
}

/* ── Videos section ────────────────────────────────────────────────── */
const videoDurations = {};

async function renderVideosSection(code, container) {
  container.innerHTML = `<div class="shelf-loading">Discovering videos…</div>`;
  const videos = await fetchJson(`/api/titles/${encodeURIComponent(code)}/videos`, null);
  if (!videos || videos.length === 0) {
    container.innerHTML = `<div class="shelf-empty">No video files found.</div>`;
    return;
  }
  container.innerHTML = '';
  videos.forEach((v, i) => {
    if (i > 0) container.appendChild(Object.assign(document.createElement('hr'), { className: 'video-divider' }));
    container.appendChild(renderVideoCard(v, code));
  });
}

function renderVideoCard(v, code) {
  const section = document.createElement('div');
  section.className = 'video-section';
  const sizeStr = v.fileSize != null ? fmtBytes(v.fileSize) : '';
  const idx = getThumbIdx();
  section.innerHTML = `
    <div class="video-header">
      <span class="video-filename mono">${escapeHtml(v.filename)}</span>
      ${sizeStr ? `<span class="video-size mono">${escapeHtml(sizeStr)}</span>` : ''}
      <span class="video-meta mono" id="video-meta-${v.id}"></span>
      ${v.folderUrl ? `
        <a class="video-folder-link" href="${escapeHtml(v.folderUrl)}" title="Open containing folder">
          <svg width="11" height="11" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M1 3.5A1.5 1.5 0 0 1 2.5 2H5l1 1.5h3.5A1.5 1.5 0 0 1 11 5v4a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 1 9V3.5z"/>
          </svg>
          Open folder
        </a>
        <button class="btn sm video-folder-copy" data-folder="${escapeHtml(v.folderUrl)}" title="Copy folder path">Copy path</button>` : ''}
      <span class="thumb-size-control">
        <span class="thumb-size-caption">Thumb</span>
        <input type="range" class="thumb-size-slider" min="1" max="${THUMB_RATIOS.length}" step="1" value="${idx}">
        <span class="thumb-size-label">${THUMB_RATIOS[idx - 1].label}</span>
      </span>
    </div>
    <div class="video-thumbs" id="video-thumbs-${v.id}">
      <div class="video-thumbs-loading">Loading previews…</div>
    </div>
    <div class="video-player-wrap" id="video-wrap-${v.id}">
      <video class="video-player" id="video-player-${v.id}" controls preload="none"
             src="/api/stream/${v.id}" type="${escapeHtml(v.mimeType || '')}"></video>
      <button class="btn sm theater-btn" data-video-id="${v.id}">Theater</button>
    </div>
  `;
  section.querySelector('.thumb-size-slider').addEventListener('input', e => setThumbIdx(+e.target.value));
  const copyBtn = section.querySelector('.video-folder-copy');
  if (copyBtn) copyBtn.addEventListener('click', () => {
    const path = copyBtn.dataset.folder;
    navigator.clipboard.writeText(path).then(() => {
      const orig = copyBtn.textContent;
      copyBtn.textContent = 'copied';
      setTimeout(() => { copyBtn.textContent = orig; }, 900);
    });
  });
  section.querySelector('.theater-btn').addEventListener('click', () => toggleTheater(v.id));
  applyThumbGrid(section.querySelector('.video-thumbs'), idx);

  loadVideoThumbnails(v.id);
  loadVideoMetadata(v.id);

  // Mark watched on play
  const player = section.querySelector(`#video-player-${v.id}`);
  if (player) player.addEventListener('play', () => {
    fetch(`/api/watch-history/${encodeURIComponent(code)}`, { method: 'POST' }).catch(() => {});
  });
  return section;
}

async function loadVideoThumbnails(videoId, attempt = 0) {
  const data = await fetchJson(`/api/videos/${videoId}/thumbnails`, null);
  const container = document.getElementById(`video-thumbs-${videoId}`);
  if (!container || !data) return;
  const urls  = data.urls || [];
  const total = data.total || 10;
  const generating = data.generating;

  if (urls.length > 0) {
    container.innerHTML = urls.map((url, i) => {
      const fraction = total > 1 ? 0.03 + (0.94 * i / (total - 1)) : 0.5;
      return `<div class="thumb-wrapper" data-fraction="${fraction}">
        <img class="video-thumb" src="${escapeHtml(url)}" loading="lazy">
        <span class="thumb-time mono" data-video-id="${videoId}" data-fraction="${fraction}">--:--</span>
      </div>`;
    }).join('');
    container.onclick = (e) => {
      const wrap = e.target.closest('.thumb-wrapper');
      if (wrap) seekVideo(videoId, parseFloat(wrap.dataset.fraction));
    };
    if (videoDurations[videoId]) updateThumbTimes(videoId, videoDurations[videoId]);
  }
  if (urls.length < total && (generating || attempt < 3) && attempt < 60) {
    setTimeout(() => loadVideoThumbnails(videoId, attempt + 1), 2000);
  } else if (urls.length === 0) {
    container.innerHTML = `<span class="video-thumbs-loading">no preview available</span>`;
  }
}

async function loadVideoMetadata(videoId) {
  const info = await fetchJson(`/api/videos/${videoId}/info`, null);
  const el = document.getElementById(`video-meta-${videoId}`);
  if (!el || !info) return;
  const parts = [];
  if (info.duration)   parts.push(info.duration);
  if (info.resolution) parts.push(info.resolution);
  if (info.videoCodec) parts.push(info.videoCodec);
  if (info.bitrate)    parts.push(info.bitrate);
  el.textContent = parts.join(' · ');
  if (info.durationSeconds) {
    videoDurations[videoId] = info.durationSeconds;
    updateThumbTimes(videoId, info.durationSeconds);
  }
}

function updateThumbTimes(videoId, durationSeconds) {
  document.querySelectorAll(`.thumb-time[data-video-id="${videoId}"]`).forEach(el => {
    el.textContent = fmtTimestamp(parseFloat(el.dataset.fraction) * durationSeconds);
  });
}

function seekVideo(videoId, fraction) {
  const player = document.getElementById(`video-player-${videoId}`);
  if (!player) return;
  const dur = videoDurations[videoId] || player.duration;
  if (!dur) { player.play(); return; }
  player.currentTime = dur * fraction;
  player.play();
}

function toggleTheater(videoId) {
  const wrap = document.getElementById(`video-wrap-${videoId}`);
  if (!wrap) return;
  wrap.classList.toggle('theater');
}

/* ── Cast + More-from shelves ──────────────────────────────────────── */
function renderActressMiniCard(a) {
  const portrait = a.profileImagePath
    ? `/api/actress-image/${encodeURIComponent(a.slug || a.id)}`
    : null;
  return `
    <a class="card-actress" href="/v2-actress-detail.html?id=${encodeURIComponent(a.id)}">
      <div class="card-actress-portrait" style="${portrait ? `background-image:url('${portrait}');background-size:cover;background-position:center top` : ''}"></div>
      <div class="card-actress-name">${escapeHtml(a.name || a.displayName || '')}</div>
    </a>
  `;
}

function renderTitleMiniCard(t) {
  const code = t.code || '';
  const cover = t.coverPath ? `${COVER_ROOT}/${encodeURIComponent(t.coverPath)}` : `/api/cover/${encodeURIComponent(code)}`;
  const name = t.normalizedTitle || t.titleEn || t.titleJa || t.title || code;
  return `
    <a class="card-title" href="/v2-title-detail.html?code=${encodeURIComponent(code)}">
      <div class="card-title-cover" style="background-image:url('${cover}');background-size:cover;background-position:center"></div>
      <div class="card-title-code">${escapeHtml(code)}</div>
      <div class="card-title-name">${escapeHtml(name)}</div>
    </a>
  `;
}

async function loadMoreFromActress(t, container) {
  const actresses = (t.actresses && t.actresses.length > 0)
    ? t.actresses
    : (t.actressId ? [{ id: t.actressId, name: t.actressName }] : []);
  if (actresses.length === 0) return;
  const a = actresses[0];
  const titles = await fetchJson(`/api/actresses/${encodeURIComponent(a.id)}/titles?limit=6`, []);
  const others = (titles || []).filter(x => x.code !== t.code).slice(0, 5);
  if (others.length === 0) return;
  container.innerHTML = `
    <section class="shelf">
      <div class="shelf-head">
        <span class="shelf-title">More from ${escapeHtml(a.name)}</span>
        <a class="shelf-action" href="/v2-actress-detail.html?id=${encodeURIComponent(a.id)}">All titles
          <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18l6-6-6-6"/></svg>
        </a>
      </div>
      <div class="shelf-grid shelf-grid-titles">
        ${others.map(renderTitleMiniCard).join('')}
      </div>
    </section>
  `;
}

/* ── Bootstrap ─────────────────────────────────────────────────────── */
async function loadAndRender(rootEl, code) {
  const list = await fetchJson(`/api/titles?code=${encodeURIComponent(code)}&limit=1`, []);
  const t = Array.isArray(list) && list.length ? list[0] : null;
  if (!t) {
    rootEl.querySelector('#hero-slot').innerHTML = `
      <div class="empty-state">
        <div class="empty-state-title">Title not found</div>
        <div class="empty-state-body">No title with code <code>${escapeHtml(code)}</code>.</div>
      </div>`;
    return;
  }
  const titleCode = t.code || code;
  const crumbEl = document.querySelector('#crumb-name');
  if (crumbEl) crumbEl.textContent = titleCode;
  document.title = `${titleCode} — Organizer3 v2`;

  rootEl.querySelector('#hero-slot').innerHTML = renderHero(t);
  rootEl.querySelector('#meta-slot').innerHTML = renderMeta(t);

  // Wire hero actions
  const wireFlag = (id, ep) => {
    const btn = rootEl.querySelector(`#${id}`);
    if (!btn) return;
    btn.addEventListener('click', async () => {
      const r = await fetch(`/api/titles/${encodeURIComponent(titleCode)}/${ep}`, { method: 'POST' });
      if (r.ok) loadAndRender(rootEl, code);
    });
  };
  wireFlag('btn-favorite', 'favorite');
  wireFlag('btn-bookmark', 'bookmark');
  wireFlag('btn-reject',   'reject');

  // Cover lightbox
  const coverEl = rootEl.querySelector('#hero-cover');
  if (coverEl) coverEl.addEventListener('click', () => {
    const url = (t.coverUrl) || (t.coverPath ? `${COVER_ROOT}/${t.coverPath}` : `/api/cover/${titleCode}`);
    showLightbox(url, titleCode);
  });

  // NAS path copy
  rootEl.querySelectorAll('.td-path').forEach(el => el.addEventListener('click', () => copyPath(el)));

  // Tag editor placeholder (modal port deferred)
  const tagBtn = rootEl.querySelector('#btn-edit-tags');
  if (tagBtn) tagBtn.addEventListener('click', () => {
    alert('Tag editor lands in a follow-up — needs the legacy tag-editor.js modal ported to the v2 modal primitive.');
  });

  // Async enriches
  loadTagState(titleCode);
  loadEnrichmentTags(titleCode);
  renderVideosSection(titleCode, rootEl.querySelector('#videos-slot'));
  loadMoreFromActress(t, rootEl.querySelector('#more-slot'));

  // Auto visit
  fetch(`/api/titles/${encodeURIComponent(titleCode)}/visit`, { method: 'POST' }).catch(() => {});
}

export function mountTitleDetail(rootEl, code) {
  if (!code) {
    rootEl.innerHTML = `
      <div class="lib-page">
        <div class="empty-state">
          <div class="empty-state-title">Missing title code</div>
          <div class="empty-state-body">Append <code>?code=CODE</code> to the URL.</div>
        </div>
      </div>`;
    return;
  }
  rootEl.innerHTML = `
    <div class="lib-page">
      <div id="hero-slot"><div class="shelf-loading">Loading…</div></div>
      <div id="meta-slot"></div>
      <section class="shelf" style="margin-top:24px">
        <div class="shelf-head"><span class="shelf-title">Videos</span></div>
        <div id="videos-slot"></div>
      </section>
      <div id="more-slot"></div>
    </div>
  `;
  loadAndRender(rootEl, code);
}
