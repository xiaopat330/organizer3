/* ─────────────────────────────────────────────────────────────────────
   v2/avstar/hero.js
   Left rail: portrait, name/folder/years, stats, IAFD badge, visit row,
   action buttons (favorite/bookmark), screenshot controls placeholder,
   and profile details (nationality, ethnicity, bio, etc.).
   ───────────────────────────────────────────────────────────────────── */

import { mount as mountScreenshotControls, unmount as unmountScreenshotControls } from './screenshot-controls.js';

export { unmountScreenshotControls };

// ── Shared utils ──────────────────────────────────────────────────────────

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

function timeAgo(s) {
  if (!s) return '';
  const d = new Date(s);
  if (isNaN(d)) return s;
  const sec = Math.floor((Date.now() - d.getTime()) / 1000);
  if (sec < 60)    return `${sec}s ago`;
  if (sec < 3600)  return `${Math.floor(sec / 60)}m ago`;
  if (sec < 86400) return `${Math.floor(sec / 3600)}h ago`;
  if (sec < 2592000) return `${Math.floor(sec / 86400)}d ago`;
  const ds = new Date(s.slice(0, 10) + 'T00:00:00');
  return ds.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
}

// ── Render ────────────────────────────────────────────────────────────────

export function renderHero(a) {
  const imgHtml = a.headshotUrl
    ? `<img class="avd-headshot-img" src="${esc(a.headshotUrl)}" alt="${esc(a.stageName || a.folderName || '')}">`
    : `<div class="avd-headshot-placeholder">
         <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"
              stroke-linecap="round" stroke-linejoin="round" width="48" height="48">
           <circle cx="12" cy="8" r="4"/>
           <path d="M4 20c0-4 3.6-7 8-7s8 3 8 7"/>
         </svg>
       </div>`;

  const years = (a.activeFrom || a.activeTo)
    ? `<div class="avd-years">${esc(a.activeFrom || '?')}–${esc(a.activeTo || 'present')}</div>`
    : '';

  const resolvedBadge = a.resolved
    ? `<span class="avd-badge avd-badge-resolved" title="IAFD resolved">IAFD</span>`
    : `<span class="avd-badge avd-badge-unresolved" title="Not yet resolved">unresolved</span>`;

  const visitedHtml = `
    <div class="avd-visited" id="avd-visited-row" ${a.visitCount > 0 ? '' : 'style="display:none"'}>
      <span id="avd-visited-value">${a.visitCount > 0 ? _fmtVisited(a.visitCount, a.lastVisitedAt) : ''}</span>
    </div>`;

  // Item 1: suppress folder-name row when it duplicates the stage name
  const stageName   = (a.stageName   || '').trim();
  const folderName  = (a.folderName  || '').trim();
  const showFolder  = folderName && folderName.toLowerCase() !== stageName.toLowerCase();

  return `
    <div class="avd-profile-card">
      <div class="avd-headshot-wrap">${imgHtml}</div>
      <div class="avd-name">${esc(a.stageName || a.folderName || '')}</div>
      ${showFolder ? `<div class="avd-folder-name">${esc(a.folderName)}</div>` : ''}
      ${years}
      <div class="avd-stats">
        <span>${a.videoCount} video${a.videoCount === 1 ? '' : 's'}</span>
        ${a.totalSizeBytes ? `<span>${esc(fmtBytes(a.totalSizeBytes))}</span>` : ''}
      </div>
      <div class="avd-badges">${resolvedBadge}</div>
      ${visitedHtml}
    </div>
    <div class="avd-actions">
      <button class="avd-action-chip ${a.favorite ? 'on' : ''}" id="avd-fav-btn" title="Favorite">
        <svg viewBox="0 0 24 24"><polygon points="12 2 15 9 22 9 17 14 18 21 12 17 6 21 7 14 2 9 9 9"/></svg>
        <span>Favorite</span>
      </button>
      <button class="avd-action-chip ${a.bookmark ? 'on' : ''}" id="avd-bm-btn" title="Bookmark">
        <svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V3a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
        <span>Bookmark</span>
      </button>
    </div>
    ${renderProfileDetails(a)}
    <div id="avd-ss-controls"></div>`;
}

function renderProfileDetails(a) {
  const rows = [];
  if (a.nationality)  rows.push(['Nationality',  esc(a.nationality)]);
  if (a.ethnicity)    rows.push(['Ethnicity',    esc(a.ethnicity)]);
  if (a.dateOfBirth)  rows.push(['Born',         esc(a.dateOfBirth)]);
  if (a.birthplace)   rows.push(['Birthplace',   esc(a.birthplace)]);
  if (a.heightCm)     rows.push(['Height',       `${a.heightCm} cm`]);
  if (a.measurements) rows.push(['Measurements', esc(a.measurements)]);
  if (a.hairColor)    rows.push(['Hair',         esc(a.hairColor)]);
  if (a.eyeColor)     rows.push(['Eyes',         esc(a.eyeColor)]);
  if (a.tattoos)      rows.push(['Tattoos',      esc(a.tattoos)]);
  if (a.piercings)    rows.push(['Piercings',    esc(a.piercings)]);
  if (a.grade)        rows.push(['Grade',        esc(a.grade)]);
  if (a.notes)        rows.push(['Notes',        esc(a.notes)]);
  if (rows.length === 0) return '';
  return `
    <div class="avd-section-title">Profile</div>
    <dl class="avd-profile-dl">
      ${rows.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join('')}
    </dl>`;
}

// ── Wire ──────────────────────────────────────────────────────────────────

export function wireHero(actressId, actress, allVideos, onStateChange) {
  // Favorite
  document.getElementById('avd-fav-btn')?.addEventListener('click', async () => {
    const newVal = !actress.favorite;
    const res = await fetch(`/api/av/actresses/${actressId}/favorite?value=${newVal}`, { method: 'POST' });
    if (res.ok) {
      const d = await res.json();
      actress.favorite = d.favorite;
      document.getElementById('avd-fav-btn')?.classList.toggle('on', d.favorite);
      if (onStateChange) onStateChange();
    }
  });

  // Bookmark
  document.getElementById('avd-bm-btn')?.addEventListener('click', async () => {
    const newVal = !actress.bookmark;
    const res = await fetch(`/api/av/actresses/${actressId}/bookmark?value=${newVal}`, { method: 'POST' });
    if (res.ok) {
      const d = await res.json();
      actress.bookmark = d.bookmark;
      document.getElementById('avd-bm-btn')?.classList.toggle('on', d.bookmark);
      if (onStateChange) onStateChange();
    }
  });

  // Screenshot controls
  const ssContainer = document.getElementById('avd-ss-controls');
  if (ssContainer) {
    const pending = allVideos.filter(v => !v.screenshotCount).length;
    mountScreenshotControls(ssContainer, actressId, pending);
  }
}

// ── Visit ────────────────────────────────────────────────────────────────

function _fmtVisited(count, lastVisitedAt) {
  const label = count === 1 ? '1 view' : `${count} views`;
  return lastVisitedAt ? `${label} · visited ${timeAgo(lastVisitedAt)}` : label;
}

export function updateVisitedRow(visitCount, lastVisitedAt) {
  const row = document.getElementById('avd-visited-row');
  const val = document.getElementById('avd-visited-value');
  if (!row || !val || visitCount <= 0) return;
  val.textContent = _fmtVisited(visitCount, lastVisitedAt || null);
  row.style.display = '';
}
