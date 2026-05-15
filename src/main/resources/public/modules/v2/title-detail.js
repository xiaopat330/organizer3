/* ─────────────────────────────────────────────────────────────────────
   Wave 4 — Title detail (library mode, full port)
   Spec: spec/DESIGN_SYSTEM_PAGES.md §3.1
   Hero (cover + bilingual title + actions) + meta rows + Videos
   section (player + thumbnails + theater) + Cast shelf + More-from
   shelf.
   Deferred (with reason):
   - Resume playback cookie: minor; per-video cookie that seeks on
     load. Add when we touch video state more deeply.
   ───────────────────────────────────────────────────────────────────── */

import { openTitleTagEditor } from './title-tag-editor.js';
import { renderTitleCard }   from './cards/title-card.js';
import { getNote, putNote, deleteNote, attachCharCounter } from '/modules/notes/index.js';

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
  const enText = t.titleEnglish || t.titleOriginalEn || t.titleOriginal || '';
  const isLlm  = !t.titleEnglish && !!t.titleOriginalEn;
  const llmBadge = isLlm ? `<span class="hero-title-badge" title="Auto-translated by AI">auto</span>` : '';

  // Eyebrow: "{labelName} · {year}" — omit missing parts
  const releaseYear = (t.releaseDate || '').slice(0, 4) || null;
  const eyebrowParts = [t.labelName || null, releaseYear].filter(Boolean);
  const eyebrowHtml = eyebrowParts.length > 0
    ? `<div class="hero-eyebrow">${escapeHtml(eyebrowParts.join(' · '))}</div>`
    : '';

  const cover = t.coverUrl
    ? t.coverUrl
    : (t.coverPath ? `${COVER_ROOT}/${encodeURIComponent(t.coverPath)}` : `/api/cover/${encodeURIComponent(titleCode)}`);

  // Japanese subtitle: show titleOriginal only if it differs from what we're showing as description
  const hasJpSubtitle = t.titleOriginal && t.titleOriginal !== enText;

  return `
    <div class="hero-band hero-band-title">
      <div class="hero-cover-col">
        <div class="hero-cover" id="hero-cover" style="background-image:url('${escapeHtml(cover)}');cursor:zoom-in" title="Click to enlarge"></div>
        <div id="v2-title-note-panel"></div>
      </div>
      <div class="hero-content">
        ${eyebrowHtml}
        <h1 class="hero-name hero-name-code">${escapeHtml(titleCode)}${llmBadge}</h1>
        ${enText ? `<div class="hero-desc" title="${escapeHtml(enText)}">${escapeHtml(enText)}</div>` : ''}
        ${hasJpSubtitle ? `<div class="hero-jp-sub">${escapeHtml(t.titleOriginal)}</div>` : ''}
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
  const actresses = (t.actresses && t.actresses.length > 0)
    ? t.actresses
    : (t.actressId ? [{ id: t.actressId, name: t.actressName, tier: t.actressTier, dateOfBirth: t.actressDateOfBirth }] : []);

  /* ── Top info strip (horizontal, dot-separated) ─── */
  const stripParts = [];

  if (actresses.length > 0) {
    const castChips = actresses.map(a => {
      const tier = a.tier ? `<span class="tier-badge tier-${escapeHtml(a.tier.toLowerCase())}">${escapeHtml(a.tier.toLowerCase())}</span>` : '';
      const age = ageAtDate(a.dateOfBirth, t.releaseDate);
      const ageHtml = age != null ? ` <span class="td-age">age ${age}</span>` : '';
      return `<a class="td-cast-link" href="/v2-actress-detail.html?id=${encodeURIComponent(a.id)}">${escapeHtml(a.name)}</a>${tier}${ageHtml}`;
    }).join('<span class="td-sep">·</span>');
    stripParts.push(castChips);
  }

  const labelDisplay = t.labelName || t.companyName;
  if (labelDisplay) {
    let lbl = escapeHtml(labelDisplay);
    if (t.companyName && t.labelName && t.labelName !== t.companyName) {
      lbl = `${escapeHtml(t.companyName)} <span style="color:var(--text-faint)">(${escapeHtml(t.labelName)})</span>`;
    }
    stripParts.push(lbl);
  }

  const date = t.releaseDate || t.addedDate;
  if (date) stripParts.push(`<span class="mono">${escapeHtml(fmtDate(date))}</span>`);

  if (t.grade) {
    const ratingExtra = (t.ratingAvg != null && t.ratingCount != null)
      ? ` <span style="color:var(--text-faint);font-size:11px">(${t.ratingAvg.toFixed(2)} · ${t.ratingCount.toLocaleString()} votes)</span>`
      : '';
    stripParts.push(`<span class="grade-badge grade-${escapeHtml(t.grade)}">${escapeHtml(t.grade)}</span>${ratingExtra}`);
  }

  const stripHtml = stripParts.length > 0
    ? `<div class="td-info-strip">${stripParts.join('<span class="td-sep">·</span>')}</div>`
    : '';

  /* ── Watch/visit summary (optional, keep compact) ── */
  const visitedHtml = t.visitCount > 0
    ? `<div class="td-aux-row"><span class="td-aux-key">Visited</span> <span class="mono">${t.visitCount}×</span> · last <span class="mono">${escapeHtml(fmtDate(t.lastVisitedAt))}</span></div>`
    : '';
  const watchedHtml = t.watchCount > 0
    ? `<div class="td-aux-row"><span class="td-aux-key">Watched</span> <span class="mono">${t.watchCount}×</span> · last <span class="mono">${escapeHtml(fmtDate(t.lastWatchedAt))}</span></div>`
    : '';
  const auxHtml = (visitedHtml || watchedHtml)
    ? `<div class="td-aux">${watchedHtml}${visitedHtml}</div>`
    : '';

  /* ── Location sub-block ─── */
  const paths = t.nasPaths || [];
  const nasHtml = paths.length === 0 ? '' : `
    <div class="td-sub-block">
      <div class="act-dash-section-title">Location</div>
      ${paths.map(p => `<div class="td-path mono" data-path="${escapeHtml(p)}" title="Click to copy">${escapeHtml(p)}</div>`).join('')}
    </div>`;

  /* ── Tags sub-block ─── */
  // Core tags (t.tags) and enrichment tags (t.enrichmentTags) rendered as separate groups.
  // loadTagState() will async-replace the core group with richer derived-tag data.
  // loadEnrichmentTags() will async-populate the enrichment group from /api/…/enrichment-tags.
  const coreTags = t.tags || [];
  const coreGroupHtml = coreTags.length > 0
    ? `<div class="td-tag-group" id="td-core-tags">${coreTags.map(tag => `<span class="td-tag">${escapeHtml(tag)}</span>`).join('')}</div>`
    : `<div class="td-tag-group" id="td-core-tags"></div>`;
  // Enrichment group starts hidden; loadEnrichmentTags() fills and shows it
  const tagsHtml = `
    <div class="td-sub-block">
      <div class="act-dash-section-title">Tags <button class="td-tag-edit" id="btn-edit-tags" title="Edit tags">edit</button></div>
      ${coreGroupHtml}
      <div class="td-tag-group td-tag-group-enrichment" id="td-enrichment-tags" style="display:none">
        <span class="td-tag-group-label">enrichment</span>
        <span id="td-enrichment-value"></span>
      </div>
    </div>`;

  return `
    <section class="td-meta">
      ${stripHtml}
      ${auxHtml}
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
  // Replace the core tags group with richer derived-tag data from the tag-state endpoint.
  // group 1 = directTags + labelImpliedTags (curated); group 2 = enrichmentImpliedTags goes
  // into the enrichment group along with the raw JavDB tags loaded by loadEnrichmentTags().
  const coreGroupEl = document.querySelector('#td-core-tags');
  if (!coreGroupEl) return;
  const direct = state.directTags || [];
  const implied = state.labelImpliedTags || [];
  const enrichedImplied = state.enrichmentImpliedTags || [];
  if (direct.length === 0 && implied.length === 0 && enrichedImplied.length === 0) {
    coreGroupEl.innerHTML = `<span style="color:var(--text-faint);font-style:italic">No tags</span>`;
    return;
  }
  let html = '';
  for (const t of direct)   html += `<span class="td-tag" title="Directly tagged">${escapeHtml(t)}</span>`;
  for (const t of implied)  html += `<span class="td-tag derived" title="Implied by label">${escapeHtml(t)}</span>`;
  coreGroupEl.innerHTML = html || '';
  // enrichmentImplied tags are shown in the enrichment group alongside raw JavDB tags
  if (enrichedImplied.length > 0) {
    const enrichGroupEl = document.querySelector('#td-enrichment-tags');
    const enrichValueEl = document.querySelector('#td-enrichment-value');
    if (enrichGroupEl && enrichValueEl) {
      const impliedHtml = enrichedImplied.map(t =>
        `<span class="td-tag raw" title="From JavDB enrichment (implied)">${escapeHtml(t)}</span>`
      ).join('');
      enrichValueEl.insertAdjacentHTML('beforeend', impliedHtml);
      enrichGroupEl.style.display = '';
    }
  }
}

async function loadEnrichmentTags(code) {
  const tags = await fetchJson(`/api/titles/${encodeURIComponent(code)}/enrichment-tags`, []);
  if (!tags || tags.length === 0) return;
  // Populate the enrichment tags group (raw JavDB tags — separate taxonomy from core tags).
  const groupEl = document.querySelector('#td-enrichment-tags');
  const valueEl = document.querySelector('#td-enrichment-value');
  if (!groupEl || !valueEl) return;
  const html = tags.map(t => {
    const name = typeof t === 'string' ? t : (t.name || '');
    const alias = (typeof t === 'object' && t.curatedAlias) ? ` → ${t.curatedAlias}` : '';
    return `<span class="td-tag raw" title="JavDB raw tag${alias ? `: ${alias}` : ''}">${escapeHtml(name)}${alias ? ` <span style="color:var(--accent)">${escapeHtml(alias)}</span>` : ''}</span>`;
  }).join('');
  valueEl.insertAdjacentHTML('afterbegin', html);
  groupEl.style.display = '';
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

async function loadMoreFromActress(t, container) {
  const actresses = (t.actresses && t.actresses.length > 0)
    ? t.actresses
    : (t.actressId ? [{ id: t.actressId, name: t.actressName }] : []);
  if (actresses.length === 0) return;
  const a = actresses[0];
  const titles = await fetchJson(`/api/actresses/${encodeURIComponent(a.id)}/titles?limit=6`, []);
  const others = (titles || []).filter(x => x.code !== t.code).slice(0, 5);
  if (others.length === 0) return;

  const section = document.createElement('section');
  section.className = 'shelf';
  const head = document.createElement('div');
  head.className = 'shelf-head';
  head.innerHTML = `
    <span class="act-dash-section-title">More from ${escapeHtml(a.name)}</span>
    <a class="shelf-action" href="/v2-actress-detail.html?id=${encodeURIComponent(a.id)}">All titles
      <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18l6-6-6-6"/></svg>
    </a>`;
  section.appendChild(head);
  const grid = document.createElement('div');
  grid.className = 'shelf-grid shelf-grid-titles';
  others.forEach(other => grid.appendChild(renderTitleCard(other, { variant: 'compact' })));
  section.appendChild(grid);
  container.appendChild(section);
}

/* ── Post-It Notes: sticky panel (§5.3) ───────────────────────────── */

const V2_NOTE_TOKENS_LINK_ID = 'v2-notes-tokens-css';
const V2_NOTE_PANEL_STYLE_ID = 'v2-title-detail-note-panel-styles';
const V2_NOTE_PANEL_ID       = 'v2-title-note-panel';
const V2_NOTE_MAX_CHARS      = 280;

/** Injects notes design tokens (idempotent). */
function v2EnsureNoteTokens() {
  if (document.getElementById(V2_NOTE_TOKENS_LINK_ID)) return;
  const link = document.createElement('link');
  link.id   = V2_NOTE_TOKENS_LINK_ID;
  link.rel  = 'stylesheet';
  link.href = '/modules/notes/tokens.css';
  document.head.appendChild(link);
}

/** Injects v2 panel-specific styles (idempotent). */
function v2EnsureNotePanelStyles() {
  if (document.getElementById(V2_NOTE_PANEL_STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = V2_NOTE_PANEL_STYLE_ID;
  style.textContent = `
/* ── v2 title-detail cover column ─────────────────────────────────── */
.hero-cover-col {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  width: 160px;
  align-items: stretch;
}

/* ── v2 title-detail sticky-note panel (§5.3) ─────────────────────── */
#${V2_NOTE_PANEL_ID} {
  background: var(--postit-yellow, #FFF59D);
  color: var(--postit-ink, #1A1A1A);
  border-radius: 2px;
  box-shadow: var(--postit-shadow, 0 2px 4px rgba(0,0,0,.15));
  transform: rotate(var(--postit-rotation, -1deg));
  padding: 12px 14px 10px;
  margin-top: 14px;
  width: 100%;
  box-sizing: border-box;
}

/* Empty state */
#${V2_NOTE_PANEL_ID}.v2tdnp-empty {
  background: transparent;
  border: 2px dashed var(--postit-empty-outline, #BDBDBD);
  box-shadow: none;
  color: #757575;
  cursor: pointer;
  font-size: 0.84rem;
  text-align: center;
  padding: 14px;
  transform: none;
}

#${V2_NOTE_PANEL_ID}.v2tdnp-empty:hover {
  border-color: var(--postit-yellow-edge, #FFEB3B);
  color: var(--postit-ink, #1A1A1A);
}

/* Present state: note body */
.v2tdnp-body {
  font-size: 0.88rem;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0 0 6px;
  font-family: inherit;
}

/* Edit affordance */
.v2tdnp-edit-btn {
  float: right;
  background: none;
  border: none;
  color: var(--postit-ink, #1A1A1A);
  font-family: inherit;
  font-size: 0.75rem;
  cursor: pointer;
  padding: 0 0 4px 8px;
  opacity: 0.6;
  margin: 0;
}
.v2tdnp-edit-btn:hover { opacity: 1; text-decoration: underline; }

/* Edited-at line */
.v2tdnp-edited-at {
  display: block;
  text-align: right;
  font-size: 0.72rem;
  color: rgba(26,26,26,0.5);
  margin-top: 6px;
}

/* Edit mode */
.v2tdnp-textarea {
  width: 100%;
  box-sizing: border-box;
  background: transparent;
  border: 1px solid var(--postit-yellow-edge, #FFEB3B);
  border-radius: 2px;
  color: var(--postit-ink, #1A1A1A);
  font-family: inherit;
  font-size: 0.88rem;
  line-height: 1.5;
  padding: 6px;
  resize: vertical;
  outline: none;
  min-height: 72px;
}
.v2tdnp-textarea:focus { border-color: var(--postit-ink, #1A1A1A); }
.v2tdnp-textarea::placeholder { color: rgba(26,26,26,0.4); }

.v2tdnp-counter {
  font-size: 0.75rem;
  color: var(--postit-counter-ok, #1A1A1A);
  text-align: right;
  margin: 2px 0 6px;
}
.v2tdnp-counter.over-limit {
  color: var(--postit-counter-over, #D32F2F);
  font-weight: 600;
}

.v2tdnp-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
}

.v2tdnp-btn {
  background: none;
  border: none;
  color: var(--postit-ink, #1A1A1A);
  font-family: inherit;
  font-size: 0.82rem;
  cursor: pointer;
  padding: 3px 5px;
  border-radius: 2px;
}
.v2tdnp-btn:hover { text-decoration: underline; }
.v2tdnp-btn:disabled { opacity: 0.4; cursor: not-allowed; text-decoration: none; }
.v2tdnp-btn-clear { margin-right: auto; }
`;
  document.head.appendChild(style);
}

/**
 * Converts epoch millis to a relative-time string.
 * Mirrors v1 title-detail.js#relativeMillis.
 */
function v2RelativeMillis(ms) {
  const seconds = Math.floor((Date.now() - ms) / 1000);
  if (seconds < 60)    return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60)    return minutes === 1 ? '1 minute ago' : `${minutes} minutes ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24)      return hours === 1 ? '1 hour ago' : `${hours} hours ago`;
  const days = Math.floor(hours / 24);
  if (days < 14)       return days === 1 ? '1 day ago' : `${days} days ago`;
  const weeks = Math.floor(days / 7);
  if (weeks < 9)       return weeks === 1 ? '1 week ago' : `${weeks} weeks ago`;
  const months = Math.floor(days / 30);
  if (months <= 3)     return months === 1 ? '1 month ago' : `${months} months ago`;
  return 'more than 3 months ago';
}

/**
 * Renders the sticky-note panel into #v2-title-note-panel.
 * @param {string} titleCode
 * @param {{body:string, createdAt:number, updatedAt:number}|null} note
 */
function v2RenderTitleNotePanel(titleCode, note) {
  const panel = document.getElementById(V2_NOTE_PANEL_ID);
  if (!panel) return;

  // Reset
  panel.innerHTML = '';
  panel.className = '';
  panel.removeAttribute('id');
  panel.id = V2_NOTE_PANEL_ID;

  if (!note) {
    panel.classList.add('v2tdnp-empty');
    panel.textContent = 'No note yet — click to add';
    panel.addEventListener('click', () => v2ActivateEditMode(panel, titleCode, null));
    return;
  }

  v2RenderPresentState(panel, titleCode, note);
}

function v2RenderPresentState(panel, titleCode, note) {
  panel.className = '';
  panel.id = V2_NOTE_PANEL_ID;
  panel.innerHTML = '';

  const editBtn = document.createElement('button');
  editBtn.type = 'button';
  editBtn.className = 'v2tdnp-edit-btn';
  editBtn.textContent = 'Edit';
  editBtn.addEventListener('click', () => v2ActivateEditMode(panel, titleCode, note));

  const body = document.createElement('pre');
  body.className = 'v2tdnp-body';
  body.textContent = note.body;

  const editedAt = document.createElement('span');
  editedAt.className = 'v2tdnp-edited-at';
  editedAt.textContent = `edited ${v2RelativeMillis(note.updatedAt)}`;
  editedAt.title = new Date(note.updatedAt).toLocaleString();

  panel.appendChild(editBtn);
  panel.appendChild(body);
  panel.appendChild(editedAt);
}

function v2ActivateEditMode(panel, titleCode, currentNote) {
  panel.className = '';
  panel.id = V2_NOTE_PANEL_ID;
  panel.innerHTML = '';

  const textarea = document.createElement('textarea');
  textarea.className = 'v2tdnp-textarea';
  textarea.rows = 4;
  textarea.placeholder = 'Add a note…';
  textarea.value = currentNote ? currentNote.body : '';

  const counter = document.createElement('div');
  counter.className = 'v2tdnp-counter';

  const detachCounter = attachCharCounter(textarea, counter, { max: V2_NOTE_MAX_CHARS });

  const actions = document.createElement('div');
  actions.className = 'v2tdnp-actions';

  const saveBtn = document.createElement('button');
  saveBtn.type = 'button';
  saveBtn.className = 'v2tdnp-btn';
  saveBtn.textContent = 'Save';

  const cancelBtn = document.createElement('button');
  cancelBtn.type = 'button';
  cancelBtn.className = 'v2tdnp-btn';
  cancelBtn.textContent = 'Cancel';

  function updateSaveState() {
    saveBtn.disabled = textarea.value.length > V2_NOTE_MAX_CHARS;
  }
  textarea.addEventListener('input', updateSaveState);
  updateSaveState();

  if (currentNote) {
    const clearBtn = document.createElement('button');
    clearBtn.type = 'button';
    clearBtn.className = 'v2tdnp-btn v2tdnp-btn-clear';
    clearBtn.textContent = 'Clear';
    clearBtn.addEventListener('click', async () => {
      detachCounter();
      try { await deleteNote('title', titleCode); } catch (e) { console.error('notes: deleteNote failed', e); }
      v2RenderTitleNotePanel(titleCode, null);
    });
    actions.appendChild(clearBtn);
  }

  actions.appendChild(cancelBtn);
  actions.appendChild(saveBtn);

  cancelBtn.addEventListener('click', () => {
    detachCounter();
    v2RenderTitleNotePanel(titleCode, currentNote);
  });

  saveBtn.addEventListener('click', async () => {
    let result;
    try {
      result = await putNote('title', titleCode, textarea.value);
    } catch (e) {
      console.error('notes: putNote failed', e);
      return;
    }
    detachCounter();
    v2RenderTitleNotePanel(titleCode, result);
  });

  panel.appendChild(textarea);
  panel.appendChild(counter);
  panel.appendChild(actions);
  textarea.focus();
  textarea.setSelectionRange(textarea.value.length, textarea.value.length);
}

/** Fetches the note for this title and hydrates the panel. */
async function v2LoadTitleNote(titleCode) {
  v2EnsureNoteTokens();
  v2EnsureNotePanelStyles();
  let note = null;
  try {
    note = await getNote('title', titleCode);
  } catch (e) {
    console.error('notes: getNote failed', e);
  }
  v2RenderTitleNotePanel(titleCode, note);
}

/* ── Bootstrap ─────────────────────────────────────────────────────── */
async function loadAndRender(rootEl, code) {
  const t = await fetchJson(`/api/titles/by-code/${encodeURIComponent(code)}`, null);
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

  // Tag editor — opens the v2 modal, then refreshes the tags display on save.
  const tagBtn = rootEl.querySelector('#btn-edit-tags');
  if (tagBtn) tagBtn.addEventListener('click', async () => {
    try {
      const saved = await openTitleTagEditor(titleCode);
      if (saved) {
        // Refresh the tag row to reflect the saved state (including derived tags).
        loadTagState(titleCode);
      }
    } catch (err) {
      console.error('tag editor error', err);
    }
  });

  // Async enriches
  loadTagState(titleCode);
  loadEnrichmentTags(titleCode);
  renderVideosSection(titleCode, rootEl.querySelector('#videos-slot'));
  loadMoreFromActress(t, rootEl.querySelector('#more-slot'));
  v2LoadTitleNote(titleCode);

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
        <div class="shelf-head"><span class="act-dash-section-title">Videos</span></div>
        <div id="videos-slot"></div>
      </section>
      <div id="more-slot"></div>
    </div>
  `;
  loadAndRender(rootEl, code);
}
