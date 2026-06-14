/* ─────────────────────────────────────────────────────────────────────
   v2 — Actress detail (full port from legacy actress-detail.js)
   Two-column: left rail (cover + sidebar sections) | right (tabs +
   filter bar + portfolio grid). Admin tab is wired to the v2 actress-admin
   workbench module.
   ───────────────────────────────────────────────────────────────────── */

import {
  mountAdminTab,
  unmountAdminTab,
  confirmDiscardIfStaged,
} from './actress-admin/index.js';
import { openCustomAvatarEditor } from './custom-avatar-editor.js';
import { renderTitleCard } from './cards/title-card.js';
import { mountActressNotePanel } from './actress-detail-notes.js';
import { mountTitlesPanel, mountProfilePanel } from './discovery/enrich-panels.js';
import { ageRangeHtml, wireAgeRange, AGE_MIN, AGE_MAX } from './widgets/age-range.js';

const PAGE_LIMIT = 24;
const FILTER_DEBOUNCE_MS = 350;
const VISIT_DELAY_MS = 5000;
const COVER_ROTATE_MS = 10_000;

// ── State ─────────────────────────────────────────────────────────────────
let currentTab = 'catalog';
let actressId = null;
let companyFilter = null;
let activeTags = new Set();
let activeEnrichmentTagIds = new Set();
let sortBy = 'release_date';
let sortDir = 'desc';
let catalogAgeMin = AGE_MIN;
let catalogAgeMax = AGE_MAX;
let actressTagsCache = null;
let enrichmentTagsCache = null;
let filterTimer = null;
let coverRotateTimer = null;
let visitTimer = null;
let portfolioState = null;
let portfolioIO = null;
let snModalEl = null;
let snKeyHandler = null;

// ── Utils ─────────────────────────────────────────────────────────────────
function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[c]));
}

async function fetchJson(url, fallback = null) {
  try {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) return fallback;
    return await r.json();
  } catch (e) {
    console.warn('[actress-detail] fetch failed:', url, e);
    return fallback;
  }
}

function fmtDate(s) {
  if (!s) return '';
  const d = new Date(s + (s.length === 10 ? 'T00:00:00' : ''));
  if (isNaN(d)) return s;
  return d.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
}

function fmtYearMonth(s) {
  if (!s) return '';
  const d = new Date(s + 'T00:00:00');
  if (isNaN(d)) return s;
  return d.toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
}

function isStale(dateStr) {
  if (!dateStr) return false;
  const d = new Date(dateStr + 'T00:00:00');
  if (isNaN(d)) return false;
  const monthsAgo = (Date.now() - d.getTime()) / (1000 * 60 * 60 * 24 * 30);
  return monthsAgo > 12;
}

function timeAgo(s) {
  if (!s) return '';
  const d = new Date(s);
  if (isNaN(d)) return s;
  const sec = Math.floor((Date.now() - d.getTime()) / 1000);
  if (sec < 60)    return `${sec}s ago`;
  if (sec < 3600)  return `${Math.floor(sec/60)}m ago`;
  if (sec < 86400) return `${Math.floor(sec/3600)}h ago`;
  if (sec < 2592000) return `${Math.floor(sec/86400)}d ago`;
  return fmtDate(s.slice(0, 10));
}

function splitName(full) {
  if (!full) return { first: '', last: '' };
  const parts = String(full).trim().split(/\s+/);
  if (parts.length === 1) return { first: parts[0], last: '' };
  return { first: parts[0], last: parts.slice(1).join(' ') };
}

function agePillTier(age) {
  if (age == null) return 'unknown';
  if (age < 25) return 'young';
  if (age < 30) return 'mid';
  if (age < 35) return 'mature';
  return 'senior';
}

function computeAge(dobStr, asOfStr) {
  if (!dobStr) return null;
  const dob = new Date(dobStr + 'T00:00:00');
  if (isNaN(dob)) return null;
  const asOf = asOfStr ? new Date(asOfStr + 'T00:00:00') : new Date();
  if (isNaN(asOf)) return null;
  let age = asOf.getFullYear() - dob.getFullYear();
  const m = asOf.getMonth() - dob.getMonth();
  if (m < 0 || (m === 0 && asOf.getDate() < dob.getDate())) age--;
  return age >= 0 ? age : null;
}

function formatDuration(fromStr, toStr) {
  const from = new Date(fromStr + 'T00:00:00');
  const to = new Date(toStr + 'T00:00:00');
  if (isNaN(from) || isNaN(to)) return '';
  let months = (to.getFullYear() - from.getFullYear()) * 12 + (to.getMonth() - from.getMonth());
  if (months < 0) months = 0;
  const years = Math.floor(months / 12);
  const rem = months % 12;
  if (years === 0 && rem === 0) return '< 1 month';
  if (years === 0) return `${rem} ${rem === 1 ? 'month' : 'months'}`;
  if (rem === 0)   return `${years} ${years === 1 ? 'year' : 'years'}`;
  return `${years}y ${rem}m`;
}

function formatVisited(count, lastVisitedAt) {
  const countLabel = count === 1 ? '1 view' : `${count} views`;
  return lastVisitedAt ? `${countLabel} (Visited ${timeAgo(lastVisitedAt)})` : countLabel;
}

function isMac() {
  return /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || '');
}

function displayPath(p) {
  if (!p) return '';
  return isMac() ? p.replace(/^smb:\/\//, 'smb://') : p.replace(/\//g, '\\');
}

function copyPathToClipboard(rawPath) {
  const formatted = isMac() ? `smb://${rawPath.replace(/^smb:\/\//, '')}` : rawPath.replace(/\//g, '\\');
  const text = formatted.startsWith('smb://') || formatted.startsWith('\\') ? formatted : (isMac() ? `smb://${rawPath}` : `\\${rawPath.replace(/\//g, '\\')}`);
  navigator.clipboard.writeText(text).catch(() => {});
}

function setStatus(msg) {
  console.log('[actress-detail]', msg);
}

// ── Cover (with rotation) ─────────────────────────────────────────────────
function cancelCoverRotation() {
  if (coverRotateTimer !== null) {
    clearInterval(coverRotateTimer);
    coverRotateTimer = null;
  }
}

function renderCover(container, a) {
  const covers = a.coverUrls || [];
  if (covers.length === 0) {
    container.innerHTML = `<div class="ad-cover-placeholder">—</div>`;
    return;
  }
  let currentIdx = Math.floor(Math.random() * covers.length);
  container.innerHTML = `<img class="ad-cover-img" src="${esc(covers[currentIdx])}" alt="${esc(a.canonicalName || '')}" loading="lazy">`;
  if (covers.length < 2) return;
  cancelCoverRotation();
  coverRotateTimer = setInterval(() => {
    if (!container.isConnected) { cancelCoverRotation(); return; }
    let nextIdx;
    do { nextIdx = Math.floor(Math.random() * covers.length); } while (nextIdx === currentIdx);
    currentIdx = nextIdx;
    const img = container.querySelector('.ad-cover-img');
    if (img) img.src = covers[currentIdx];
  }, COVER_ROTATE_MS);
}

// ── Sidebar sections ──────────────────────────────────────────────────────
function sectionShell(title, inner) {
  return `<section class="ad-section">
    <h3 class="ad-section-title">${esc(title)}</h3>
    ${inner}
  </section>`;
}

function vitalRow(label, valueHtml) {
  return `<div class="ad-vital-row">
    <span class="ad-vital-label">${label}</span>
    <span class="ad-vital-value">${valueHtml}</span>
  </div>`;
}

function renderAvatarFrame(a) {
  const url = a.localAvatarUrl;
  const ring = a.derivedGrade ? `ad-avatar-ring grade-${esc(a.derivedGrade)}` : '';
  const inner = url
    ? `<img class="ad-avatar-img" src="${esc(url)}" alt="">`
    : `<div class="ad-avatar-placeholder">${esc((a.canonicalName || '?').charAt(0))}</div>`;
  return `<div class="ad-avatar ${ring}" id="ad-avatar-btn" title="Set profile image">${inner}</div>`;
}

function renderIdentitySection(a) {
  const { first, last } = splitName(a.canonicalName);
  const stageBlock = a.stageName
    ? `<div class="ad-stage-name">
         <span class="ad-stage-name-kanji">${esc(a.stageName)}</span>
         ${a.nameReading ? `<span class="ad-stage-name-reading">${esc(a.nameReading)}</span>` : ''}
       </div>`
    : `<button class="btn sm" id="ad-search-stage-name">Search for stage name</button>`;

  const tier = a.tier ? `<span class="tier-badge tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>` : '';

  return `
    <section class="ad-section ad-section-identity">
      <div class="ad-identity-header">
        ${renderAvatarFrame(a)}
        <div class="ad-identity-text">
          <div class="ad-name">
            <span class="ad-first-name">${esc(first)}</span>
            ${last ? `<span class="ad-last-name">${esc(last)}</span>` : ''}
          </div>
          ${stageBlock}
          <button class="btn sm ghost" id="ad-edit-stage-name">Edit name</button>
        </div>
      </div>
      <div class="ad-meta-row">${tier}</div>
      <div class="ad-actions">
        <button class="icon-btn ad-action-chip ${a.favorite ? 'on' : ''}" id="ad-fav-btn" title="Favorite">
          <svg viewBox="0 0 24 24"><polygon points="12 2 15 9 22 9 17 14 18 21 12 17 6 21 7 14 2 9 9 9"/></svg>
          <span>Favorite</span>
        </button>
        <button class="icon-btn ad-action-chip ${a.bookmark ? 'on' : ''}" id="ad-bm-btn" title="Bookmark">
          <svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V3a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
          <span>Bookmark</span>
        </button>
        <button class="icon-btn ad-action-chip ad-reject ${a.rejected ? 'on' : ''}" id="ad-rej-btn" title="Reject">
          <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/></svg>
          <span>Reject</span>
        </button>
      </div>
      ${renderFolderPaths(a)}
    </section>`;
}

function renderFolderPaths(a) {
  const paths = a.folderPaths || [];
  if (paths.length === 0) return '';
  return `<div class="ad-folder-paths">${
    paths.map(p => `<span class="ad-folder-path" data-path="${esc(p)}" title="Click to copy">${esc(displayPath(p))}</span>`).join('')
  }</div>`;
}

function renderPrimaryActressSection(a) {
  if (!a.primaryActressId || !a.primaryActressName) return '';
  return sectionShell('Primarily Known As', `<div class="ad-alias-cloud">
    <button class="ad-alias-badge ad-alias-link" data-actress-id="${a.primaryActressId}">${esc(a.primaryActressName)}</button>
  </div>`);
}

function renderAliasesSection(a) {
  const alt = a.alternateNames || [];
  if (alt.length > 0) {
    const rows = alt.map(n => `
      <li class="ad-alt-name">
        <span class="ad-alt-name-value">${esc(n.name || '')}</span>
        ${n.note ? `<span class="ad-alt-name-note">${esc(n.note)}</span>` : ''}
      </li>`).join('');
    return sectionShell('Also Known As', `<ul class="ad-alt-names">${rows}</ul>`);
  }
  const aliases = a.aliases || [];
  if (aliases.length > 0) {
    const badges = aliases.map(al => al.actressId
      ? `<button class="ad-alias-badge ad-alias-link" data-actress-id="${al.actressId}">${esc(al.name)}</button>`
      : `<span class="ad-alias-badge">${esc(al.name)}</span>`
    ).join('');
    return sectionShell('Also Known As', `<div class="ad-alias-cloud">${badges}</div>`);
  }
  return '';
}

function renderCareerInner(a) {
  const start = a.activeFrom || a.firstAddedDate;
  const end   = a.activeTo   || a.lastAddedDate;
  if (!start && !end && !a.retirementAnnounced && !(a.visitCount > 0)) return '';

  const range = (start || end)
    ? `<div class="ad-career">
         ${start ? `<span class="ad-date-first">${esc(fmtDate(start))}</span>` : ''}
         ${start && end ? ' → ' : ''}
         ${end ? `<span class="ad-date-last ${isStale(end) ? 'stale' : ''}">${esc(fmtDate(end))}</span>` : ''}
       </div>` : '';
  const dur = (start && end)
    ? `<div class="ad-career-duration">${esc(formatDuration(start, end))} active</div>` : '';
  const ret = a.retirementAnnounced
    ? `<div class="ad-career-retirement">Retirement announced ${esc(fmtDate(a.retirementAnnounced))}</div>` : '';

  const visited = a.visitCount > 0 ? formatVisited(a.visitCount, a.lastVisitedAt) : '';
  const visitedHtml = `<div class="ad-visited" id="ad-visited-row" ${a.visitCount > 0 ? '' : 'style="display:none"'}>
    <span id="ad-visited-value">${esc(visited)}</span>
  </div>`;

  return `${range}${dur}${ret}${visitedHtml}`;
}

function renderVitalsInner(a) {
  const rows = [];
  if (a.dateOfBirth) {
    const age = computeAge(a.dateOfBirth, a.activeTo);
    const ageLabel = age != null
      ? ` <span class="age-pill" data-age-tier="${agePillTier(age)}">${a.activeTo ? `age ${age} at retirement` : `age ${age}`}</span>`
      : '';
    rows.push(vitalRow('Born', `${esc(fmtDate(a.dateOfBirth))}${ageLabel}`));
  }
  if (a.birthplace) rows.push(vitalRow('From',   esc(a.birthplace)));
  if (a.bloodType)  rows.push(vitalRow('Blood',  esc(a.bloodType)));
  if (a.heightCm)   rows.push(vitalRow('Height', `${a.heightCm} cm`));
  if (a.bust || a.waist || a.hip) {
    const bwh = [a.bust || '—', a.waist || '—', a.hip || '—'].join(' · ');
    const cup = a.cup ? ` <span class="ad-vital-subtle">${esc(a.cup)} cup</span>` : '';
    rows.push(vitalRow('Measures', `${bwh}${cup}`));
  }
  return rows.length ? `<div class="ad-vitals">${rows.join('')}</div>` : '';
}

function renderProfileSection(a) {
  const career = renderCareerInner(a);
  const vitals = renderVitalsInner(a);
  if (!career && !vitals) return '';
  const careerBlock = career ? `<div class="ad-profile-career">${career}</div>` : '';
  const vitalsBlock = vitals ? `<div class="ad-profile-vitals">${vitals}</div>` : '';
  return sectionShell('Profile', `${careerBlock}${vitalsBlock}`);
}

function renderLibrarySection(a) {
  if (!a.titleCount && !a.firstAddedDate && !a.lastAddedDate) return '';
  const rows = [];
  if (a.titleCount != null) {
    const titleWord = a.titleCount === 1 ? 'title' : 'titles';
    const cc = (a.companies || []).length;
    const studioFrag = cc > 0 ? ` <span class="ad-vital-subtle">· ${cc} ${cc === 1 ? 'company' : 'companies'}</span>` : '';
    rows.push(vitalRow('Library', `${a.titleCount} ${titleWord}${studioFrag}`));
  }
  if (a.firstAddedDate) rows.push(vitalRow('First seen', esc(fmtDate(a.firstAddedDate))));
  if (a.lastAddedDate)  rows.push(vitalRow('Last added', esc(fmtDate(a.lastAddedDate))));
  if (rows.length === 0) return '';
  return sectionShell('Library', `<div class="ad-vitals">${rows.join('')}</div>`);
}

const GRADE_GROUPS = [
  { key: 'S', members: ['SSS', 'SS', 'S'] },
  { key: 'A', members: ['A+', 'A', 'A-'] },
  { key: 'B', members: ['B+', 'B', 'B-'] },
  { key: 'C', members: ['C+', 'C', 'C-', 'D', 'F'] },
];

function renderGradesSection(a) {
  const breakdown = a.gradeBreakdown || {};
  const graded = a.gradedTitleCount || 0;
  const total = a.titleCount || 0;
  if (graded === 0) return '';

  const segments = GRADE_GROUPS.map(g => {
    const count = g.members.reduce((s, k) => s + (breakdown[k] || 0), 0);
    if (count === 0) return null;
    const detail = g.members.filter(k => breakdown[k]).map(k => `${k}:${breakdown[k]}`).join(' ');
    return `<span class="ad-grade-bar-segment grade-badge grade-${g.key}" style="flex:${count}" title="${esc(detail)}">${g.key}<span class="ad-grade-bar-count">${count}</span></span>`;
  }).filter(Boolean).join('');

  const ungraded = Math.max(0, total - graded);
  const ungradedLabel = ungraded > 0 ? ` <span class="ad-vital-subtle">· ${ungraded} ungraded</span>` : '';

  return sectionShell('Grades', `
    <div class="ad-vitals">
      <div class="ad-vital-row">
        <span class="ad-vital-label">Graded</span>
        <span class="ad-vital-value">${graded} of ${total}${ungradedLabel}</span>
      </div>
    </div>
    <div class="ad-grade-bar">${segments}</div>`);
}

function renderStudiosSection(a) {
  const studios = a.primaryStudios || [];
  if (studios.length === 0) return '';
  const items = studios.map(s => {
    const from = s.from ? fmtYearMonth(s.from) : '';
    const to   = s.to   ? fmtYearMonth(s.to)   : '';
    const sep  = from && to ? ' – ' : '';
    const dates = (from || to) ? `<div class="ad-studio-dates">${esc(from)}${sep}${esc(to)}</div>` : '';
    const name = s.name ? `<div class="ad-studio-name">${esc(s.name)}</div>` : '';
    const company = s.company && s.company !== s.name ? `<div class="ad-studio-company">${esc(s.company)}</div>` : '';
    const role = s.role ? `<div class="ad-studio-role">${esc(s.role)}</div>` : '';
    return `<li class="ad-studio-entry">${name}${company}${dates}${role}</li>`;
  }).join('');
  return sectionShell('Studios', `<ul class="ad-studios">${items}</ul>`);
}

function renderAwardsSection(a) {
  const awards = a.awards || [];
  if (awards.length === 0) return '';
  const items = awards.map(aw => `
    <li class="ad-award-entry">
      ${aw.year ? `<span class="ad-award-year">${esc(String(aw.year))}</span>` : ''}
      <div class="ad-award-body">
        ${aw.event    ? `<div class="ad-award-event">${esc(aw.event)}</div>` : ''}
        ${aw.category ? `<div class="ad-award-category">${esc(aw.category)}</div>` : ''}
      </div>
    </li>`).join('');
  return sectionShell('Awards', `<ul class="ad-awards">${items}</ul>`);
}

function renderBiographySection(a) {
  if (!a.biography || !a.biography.trim()) return '';
  return sectionShell('Biography', `<div class="ad-bio-text">${esc(a.biography)}</div>`);
}

function renderLegacySection(a) {
  if (!a.legacy || !a.legacy.trim()) return '';
  return sectionShell('Legacy', `<blockquote class="ad-legacy-text">${esc(a.legacy)}</blockquote>`);
}

function renderResearchChecklist(a) {
  const buckets = [
    { label: 'Profile', missing: [
      a.stageName   ? null : 'stage name',
      a.dateOfBirth ? null : 'date of birth',
      a.birthplace  ? null : 'birthplace',
    ].filter(Boolean) },
    { label: 'Physical', missing: [
      a.heightCm ? null : 'height',
      a.bust     ? null : 'bust',
      a.waist    ? null : 'waist',
      a.hip      ? null : 'hip',
    ].filter(Boolean) },
    { label: 'Biography', missing: (a.biography && a.biography.trim()) ? [] : ['biography'] },
    { label: 'Portfolio', missing: a.titleCount > 0 ? [] : ['no titles in library'] },
  ];
  const gapCount = buckets.filter(b => b.missing.length > 0).length;
  if (gapCount === 0) return '';
  const rows = buckets.map(b => {
    const status = b.missing.length === 0 ? 'filled' : 'missing';
    const detail = b.missing.length === 0
      ? '<span class="ad-research-detail">complete</span>'
      : `<span class="ad-research-detail">missing: ${esc(b.missing.join(', '))}</span>`;
    return `<div class="ad-research-bucket ad-research-${status}">
      <span class="ad-research-dot"></span>
      <span class="ad-research-label">${b.label}</span>
      ${detail}
    </div>`;
  }).join('');
  return `<div class="ad-research-checklist">
    <div class="ad-research-title">Research gaps <span class="ad-research-count">${gapCount}/4</span></div>
    ${rows}
  </div>`;
}

// ── Avatar button wiring (recursive: re-wires itself after each save) ────────
function wireAvatarBtn(rootEl, a) {
  const btn = rootEl.querySelector('#ad-avatar-btn');
  if (!btn) return;
  btn.addEventListener('click', () => {
    openCustomAvatarEditor(a.id, !!a.hasCustomAvatar, async () => {
      // Targeted refresh: re-fetch actress, replace avatar element, bust cache.
      const fresh = await fetchJson(`/api/actresses/${a.id}`, null);
      if (!fresh) return;
      a.hasCustomAvatar = fresh.hasCustomAvatar;
      a.localAvatarUrl  = fresh.localAvatarUrl;
      // Build replacement avatar frame with cache-busted URL.
      const displayA = { ...a };
      if (displayA.localAvatarUrl) {
        displayA.localAvatarUrl = displayA.localAvatarUrl.split('?')[0] + '?t=' + Date.now();
      }
      const tmp = document.createElement('div');
      tmp.innerHTML = renderAvatarFrame(displayA);
      const newBtn = tmp.firstElementChild;
      rootEl.querySelector('#ad-avatar-btn')?.replaceWith(newBtn);
      // Re-wire on the freshly inserted element using updated actress data.
      wireAvatarBtn(rootEl, a);
    });
  });
}

// ── Wire identity actions ────────────────────────────────────────────────
function wireIdentityActions(rootEl, a) {
  const searchBtn = rootEl.querySelector('#ad-search-stage-name');
  if (searchBtn) searchBtn.addEventListener('click', () => searchStageName(a.id, searchBtn));

  const editBtn = rootEl.querySelector('#ad-edit-stage-name');
  if (editBtn) editBtn.addEventListener('click', () => openStageNameModal(a.id, a.stageName));

  wireAvatarBtn(rootEl, a);

  const apply = (data) => {
    a.favorite = data.favorite;
    a.bookmark = data.bookmark;
    a.rejected = data.rejected;
    rootEl.querySelector('#ad-fav-btn')?.classList.toggle('on', !!data.favorite);
    rootEl.querySelector('#ad-bm-btn')?.classList.toggle('on', !!data.bookmark);
    rootEl.querySelector('#ad-rej-btn')?.classList.toggle('on', !!data.rejected);
  };

  rootEl.querySelector('#ad-fav-btn')?.addEventListener('click', () => {
    fetch(`/api/actresses/${a.id}/favorite`, { method: 'POST' }).then(r => r.json()).then(apply).catch(() => {});
  });
  rootEl.querySelector('#ad-bm-btn')?.addEventListener('click', () => {
    fetch(`/api/actresses/${a.id}/bookmark`, { method: 'POST' }).then(r => r.json()).then(apply).catch(() => {});
  });
  rootEl.querySelector('#ad-rej-btn')?.addEventListener('click', () => {
    fetch(`/api/actresses/${a.id}/reject`, { method: 'POST' }).then(r => r.json()).then(apply).catch(() => {});
  });

  rootEl.querySelectorAll('.ad-alias-link').forEach(btn => {
    btn.addEventListener('click', () => {
      const id = btn.dataset.actressId;
      if (id) location.href = `/v2-actress-detail.html?id=${encodeURIComponent(id)}`;
    });
  });

  rootEl.querySelectorAll('.ad-folder-path[data-path]').forEach(el => {
    el.addEventListener('click', () => copyPathToClipboard(el.dataset.path));
  });
}

// ── Stage name search ────────────────────────────────────────────────────
async function searchStageName(id, btn) {
  btn.disabled = true;
  btn.textContent = 'Searching…';
  try {
    const res = await fetch(`/api/actresses/${id}/stage-name/search`, { method: 'POST' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    if (data.reason === 'ok') {
      location.reload();
      return;
    }
    if (data.reason === 'low_corroboration') {
      setStatus(`AI candidate matched only ${data.matchCount} of ${data.enrichedTitles} enriched titles' cast — too low confidence. Use Edit to set kanji manually.`);
    } else {
      setStatus(data.reason === 'actress_not_found' ? 'actress not found' : 'stage name not found');
    }
  } catch (err) {
    console.error('Stage name search failed:', err);
    setStatus('search failed');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Search for stage name';
  }
}

// ── Stage name modal ────────────────────────────────────────────────────
function closeStageNameModal() {
  if (snKeyHandler) {
    document.removeEventListener('keydown', snKeyHandler);
    snKeyHandler = null;
  }
  if (snModalEl) {
    snModalEl.remove();
    snModalEl = null;
  }
}

async function openStageNameModal(id, currentStageName) {
  closeStageNameModal();
  snModalEl = document.createElement('div');
  snModalEl.className = 'modal-backdrop';
  snModalEl.innerHTML = `
    <div class="modal sm" role="dialog">
      <div class="modal-header">
        <span class="modal-title">Edit stage name</span>
        <button class="modal-close" id="sn-close" title="Cancel">×</button>
      </div>
      <div class="modal-body">
        <div id="sn-candidates" style="display:none">
          <div class="form-help" style="margin-bottom:6px">Suggestions from cast data:</div>
          <div class="ad-sn-chips" id="sn-chips"></div>
        </div>
        <input class="form-input mono" id="sn-input" type="text"
               placeholder="Kanji stage name…"
               value="${currentStageName ? esc(currentStageName) : ''}" autofocus>
      </div>
      <div class="modal-footer">
        <button class="btn ghost" id="sn-cancel">Cancel</button>
        <button class="btn primary" id="sn-save">Save</button>
      </div>
    </div>`;
  document.body.appendChild(snModalEl);

  const input = snModalEl.querySelector('#sn-input');
  setTimeout(() => { input?.focus(); input?.select(); }, 0);

  snModalEl.addEventListener('click', e => {
    if (e.target === snModalEl) closeStageNameModal();
  });
  snModalEl.querySelector('#sn-close').addEventListener('click', closeStageNameModal);
  snModalEl.querySelector('#sn-cancel').addEventListener('click', closeStageNameModal);
  snModalEl.querySelector('#sn-save').addEventListener('click', () => saveStageName(id));

  snKeyHandler = e => {
    if (e.key === 'Escape') closeStageNameModal();
    if (e.key === 'Enter')  saveStageName(id);
  };
  document.addEventListener('keydown', snKeyHandler);

  // Async: fetch cast-derived candidates
  try {
    const res = await fetch(`/api/actresses/${id}/stage-name-candidates`);
    if (!res.ok || !snModalEl) return;
    const data = await res.json();
    const candidates = data.candidates || [];
    if (candidates.length === 0 || !snModalEl) return;
    const chips = snModalEl.querySelector('#sn-chips');
    const wrap  = snModalEl.querySelector('#sn-candidates');
    if (!chips || !wrap) return;
    chips.innerHTML = candidates.map(c =>
      `<button class="chip" data-name="${esc(c.name)}">${esc(c.name)} <span class="ad-vital-subtle">(${c.hits})</span></button>`
    ).join('');
    chips.querySelectorAll('.chip').forEach(chip => {
      chip.addEventListener('click', () => {
        const inp = snModalEl?.querySelector('#sn-input');
        if (inp) { inp.value = chip.dataset.name; inp.focus(); }
      });
    });
    wrap.style.display = '';
  } catch (_) {}
}

async function saveStageName(id) {
  const input = snModalEl?.querySelector('#sn-input');
  const saveBtn = snModalEl?.querySelector('#sn-save');
  if (!input || !saveBtn) return;
  const stageName = input.value.trim();
  if (!stageName) { setStatus('stage name must not be blank'); return; }
  saveBtn.disabled = true;
  saveBtn.textContent = 'Saving…';
  try {
    const res = await fetch(`/api/actresses/${id}/stage-name`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ stageName }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      setStatus(err.error || `save failed (${res.status})`);
      saveBtn.disabled = false;
      saveBtn.textContent = 'Save';
      return;
    }
    closeStageNameModal();
    location.reload();
  } catch (err) {
    console.error('Stage name save failed:', err);
    setStatus('save failed');
    saveBtn.disabled = false;
    saveBtn.textContent = 'Save';
  }
}

// ── Filter bar ────────────────────────────────────────────────────────────
function renderFilterBar(barEl, a) {
  const companies = a.companies || [];
  barEl.innerHTML = `
    <select class="form-select ad-filter-select" id="ad-company-select">
      <option value="">All companies</option>
      ${companies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('')}
    </select>
    ${ageRangeHtml(catalogAgeMin, catalogAgeMax, { idPrefix: 'ad-age' })}
    <select class="form-select ad-filter-select" id="ad-sort-select">
      <option value="release_date">Release date</option>
      <option value="code">Product number</option>
      <option value="grade">Rating</option>
    </select>
    <button class="btn sm" id="ad-sort-dir" title="Toggle sort direction">↓</button>
    <button class="btn sm" id="ad-tags-btn">Tags<span class="badge" id="ad-tags-count" style="display:none"></span></button>
  `;

  barEl.querySelector('#ad-company-select').addEventListener('change', e => {
    companyFilter = e.target.value || null;
    scheduleRefresh();
  });
  wireAgeRange(barEl, {
    idPrefix: 'ad-age',
    getLo: () => catalogAgeMin,
    getHi: () => catalogAgeMax,
    setLo: v => { catalogAgeMin = v; },
    setHi: v => { catalogAgeMax = v; },
    onChange: scheduleRefresh,
  });
  barEl.querySelector('#ad-sort-select').addEventListener('change', e => {
    sortBy = e.target.value;
    scheduleRefresh();
  });
  barEl.querySelector('#ad-sort-dir').addEventListener('click', () => {
    sortDir = sortDir === 'desc' ? 'asc' : 'desc';
    barEl.querySelector('#ad-sort-dir').textContent = sortDir === 'desc' ? '↓' : '↑';
    scheduleRefresh();
  });
  barEl.querySelector('#ad-tags-btn').addEventListener('click', toggleTagsPanel);
}

function updateTagsBtn() {
  const total = activeTags.size + activeEnrichmentTagIds.size;
  const countEl = document.getElementById('ad-tags-count');
  const btn = document.getElementById('ad-tags-btn');
  if (countEl) {
    countEl.textContent = total;
    countEl.style.display = total > 0 ? '' : 'none';
  }
  if (btn) btn.classList.toggle('primary', total > 0);
}

async function toggleTagsPanel() {
  const panel = document.getElementById('ad-tags-panel');
  if (!panel) return;
  if (panel.style.display !== 'none') { panel.style.display = 'none'; return; }
  if (!actressTagsCache || !enrichmentTagsCache) {
    panel.innerHTML = '<div class="ad-tags-loading">Loading tags…</div>';
    panel.style.display = '';
    try {
      const [t, e] = await Promise.all([
        actressTagsCache    ? Promise.resolve(actressTagsCache)    : fetch(`/api/actresses/${actressId}/tags`).then(r => r.ok ? r.json() : []),
        enrichmentTagsCache ? Promise.resolve(enrichmentTagsCache) : fetch(`/api/actresses/${actressId}/enrichment-tags`).then(r => r.ok ? r.json() : []),
      ]);
      actressTagsCache = t;
      enrichmentTagsCache = e;
    } catch (err) {
      panel.innerHTML = '<div class="ad-tags-loading">Could not load tags</div>';
      return;
    }
  }
  renderTagsPanel(panel);
  panel.style.display = '';
}

function renderTagsPanel(panel) {
  const tags = actressTagsCache || [];
  const enrichDefs = (enrichmentTagsCache || [])
    .filter(d => d.surface && !d.curatedAlias && d.titleCount >= 1)
    .sort((a, b) => a.name.localeCompare(b.name));

  if (tags.length === 0 && enrichDefs.length === 0) {
    panel.innerHTML = '<div class="ad-tags-loading">No tags available</div>';
    return;
  }

  const curatedHtml = tags.length === 0 ? '' : `
    <div class="ad-tags-group">
      <div class="ad-tags-row">
        ${tags.map(t => `<button type="button" class="chip ${activeTags.has(t) ? 'on' : ''}" data-tag="${esc(t)}">${esc(t)}</button>`).join('')}
      </div>
    </div>`;

  const enrichHtml = enrichDefs.length === 0 ? '' : `
    <div class="ad-tags-group">
      <div class="ad-tags-group-label">Enrichment <span class="ad-vital-subtle">titles</span></div>
      <div class="ad-tags-row">
        ${enrichDefs.map(d => {
          const on = activeEnrichmentTagIds.has(d.id) ? ' on' : '';
          const label = d.curatedAlias ? esc(d.curatedAlias) : esc(d.name);
          const title = d.curatedAlias ? `${d.name} → ${d.curatedAlias}` : d.name;
          return `<button type="button" class="chip${on}" data-enrichment-id="${d.id}" title="${esc(title)}">${label} <span class="ad-vital-subtle">${d.titleCount}</span></button>`;
        }).join('')}
      </div>
    </div>`;

  panel.innerHTML = `<div class="ad-tags-inner">${curatedHtml}${enrichHtml}</div>`;

  panel.querySelectorAll('.chip[data-tag]').forEach(btn => {
    btn.addEventListener('click', () => {
      const tag = btn.dataset.tag;
      if (activeTags.has(tag)) { activeTags.delete(tag); btn.classList.remove('on'); }
      else                     { activeTags.add(tag);    btn.classList.add('on'); }
      scheduleRefresh();
    });
  });
  panel.querySelectorAll('.chip[data-enrichment-id]').forEach(btn => {
    btn.addEventListener('click', () => {
      const id = parseInt(btn.dataset.enrichmentId, 10);
      if (activeEnrichmentTagIds.has(id)) { activeEnrichmentTagIds.delete(id); btn.classList.remove('on'); }
      else                                 { activeEnrichmentTagIds.add(id);    btn.classList.add('on'); }
      scheduleRefresh();
    });
  });
}

// ── Portfolio grid ───────────────────────────────────────────────────────
function buildPortfolioUrl(offset) {
  let url = `/api/actresses/${actressId}/titles?offset=${offset}&limit=${PAGE_LIMIT}`;
  if (companyFilter) url += `&company=${encodeURIComponent(companyFilter)}`;
  if (activeTags.size > 0) url += `&tags=${encodeURIComponent([...activeTags].join(','))}`;
  if (activeEnrichmentTagIds.size > 0) url += `&enrichmentTagIds=${[...activeEnrichmentTagIds].join(',')}`;
  url += `&sortBy=${encodeURIComponent(sortBy)}&sortDir=${encodeURIComponent(sortDir)}`;
  if (catalogAgeMin > AGE_MIN) url += '&ageMin=' + catalogAgeMin;
  if (catalogAgeMax < AGE_MAX) url += '&ageMax=' + catalogAgeMax;
  return url;
}

async function loadPortfolio(gridEl, statusEl, metaEl) {
  if (portfolioState.loading || portfolioState.exhausted) return;
  portfolioState.loading = true;
  statusEl.innerHTML = `<div class="shelf-loading">Loading…</div>`;

  const data = await fetchJson(buildPortfolioUrl(portfolioState.offset), []);
  const list = Array.isArray(data) ? data : (data?.items ?? data?.titles ?? []);

  if (list.length === 0 && portfolioState.items.length === 0) {
    statusEl.innerHTML = `
      <div class="empty-state">
        <div class="empty-state-title">No titles in portfolio</div>
        <div class="empty-state-body">No titles match the current filters.</div>
      </div>`;
    portfolioState.exhausted = true;
    portfolioState.loading = false;
    return;
  }

  portfolioState.items.push(...list);
  list.forEach(t => gridEl.appendChild(renderTitleCard(t)));
  portfolioState.offset += list.length;
  metaEl.textContent = `${portfolioState.items.length} loaded`;

  if (list.length < PAGE_LIMIT) {
    portfolioState.exhausted = true;
    statusEl.innerHTML = `<div class="shelf-loading">End of portfolio.</div>`;
  } else {
    statusEl.innerHTML = '';
  }
  portfolioState.loading = false;
}

function scheduleRefresh() {
  updateTagsBtn();
  if (filterTimer) clearTimeout(filterTimer);
  filterTimer = setTimeout(() => {
    filterTimer = null;
    const grid = document.getElementById('ad-portfolio-grid');
    const status = document.getElementById('ad-portfolio-status');
    const meta = document.getElementById('ad-portfolio-meta');
    if (!grid) return;
    grid.innerHTML = '';
    portfolioState = { offset: 0, loading: false, exhausted: false, items: [] };
    loadPortfolio(grid, status, meta);
  }, FILTER_DEBOUNCE_MS);
}

// ── Enrichment tab sub-tab state ──────────────────────────────────────────
let enrichSubtab = 'titles';  // 'titles' | 'profile'

function mountEnrichmentTab(panelEl) {
  // Guard: wire click listeners only once per panel lifetime.  The panel DOM
  // is rebuilt on actress switch (rootEl.innerHTML rebuild), so a new panel
  // element is always a fresh element.
  if (panelEl.dataset.mounted === 'true') return;
  panelEl.dataset.mounted = 'true';

  const titlesContainer  = panelEl.querySelector('.ad-enrich-titles-view');
  const profileContainer = panelEl.querySelector('.ad-enrich-profile-view');
  const subtabBtns = panelEl.querySelectorAll('.ad-enrich-subtab');

  function applySubtab(tab) {
    enrichSubtab = tab;
    subtabBtns.forEach(b => b.classList.toggle('selected', b.dataset.subtab === tab));
    titlesContainer.style.display  = tab === 'titles'  ? '' : 'none';
    profileContainer.style.display = tab === 'profile' ? '' : 'none';
    if (tab === 'titles' && titlesContainer.innerHTML === '') {
      mountTitlesPanel(titlesContainer, {
        actressId,
        hooks: {
          switchToProfile: () => applySubtab('profile'),
        },
      });
    } else if (tab === 'profile' && profileContainer.innerHTML === '') {
      mountProfilePanel(profileContainer, { actressId });
    }
  }

  subtabBtns.forEach(btn => {
    btn.addEventListener('click', () => applySubtab(btn.dataset.subtab));
  });

  applySubtab(enrichSubtab);
}

// ── Tabs ──────────────────────────────────────────────────────────────────
async function selectTab(tab) {
  if (tab === currentTab) return;

  // Navigate-away guard: if leaving admin with staged edits, confirm discard.
  if (currentTab === 'admin') {
    const ok = await confirmDiscardIfStaged();
    if (!ok) return;  // user chose Stay — abort tab switch
    unmountAdminTab();
  }

  currentTab = tab;
  document.querySelectorAll('.ad-tab').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
  document.querySelectorAll('.ad-tab-panel').forEach(p => p.classList.toggle('active', p.dataset.tab === tab));

  if (tab === 'admin') {
    const panelEl = document.querySelector('.ad-tab-panel[data-tab="admin"]');
    if (panelEl) await mountAdminTab(panelEl, actressId);
  } else if (tab === 'enrichment') {
    const panelEl = document.querySelector('.ad-tab-panel[data-tab="enrichment"]');
    if (panelEl) mountEnrichmentTab(panelEl);
  }
}

// ── Mount ─────────────────────────────────────────────────────────────────
export async function mountActressDetail(rootEl, id) {
  // Guard: if the user is navigating to a different actress while on the
  // admin tab with staged changes, confirm discard first (mirrors legacy
  // actress-detail.js lines 64-69).
  if (currentTab === 'admin' && actressId !== null && actressId !== parseInt(id, 10)) {
    const ok = await confirmDiscardIfStaged();
    if (!ok) return;
    unmountAdminTab();
  }

  // Reset tab to catalog on actress switch.
  if (actressId !== null && actressId !== parseInt(id, 10)) {
    currentTab = 'catalog';
  }

  if (!id) {
    rootEl.innerHTML = `
      <div class="lib-page">
        <div class="empty-state">
          <div class="empty-state-title">Missing actress ID</div>
          <div class="empty-state-body">Append <code>?id=NUMBER</code> to the URL.</div>
        </div>
      </div>`;
    return;
  }
  actressId = parseInt(id, 10);
  companyFilter = null;
  activeTags = new Set();
  activeEnrichmentTagIds = new Set();
  sortBy = 'release_date';
  sortDir = 'desc';
  catalogAgeMin = AGE_MIN;
  catalogAgeMax = AGE_MAX;
  actressTagsCache = null;
  enrichmentTagsCache = null;

  rootEl.innerHTML = `
    <div class="ad-layout">
      <aside class="ad-rail">
        <div class="ad-cover" id="ad-cover"><div class="shelf-loading">Loading…</div></div>
        <div class="ad-sidebar" id="ad-sidebar"></div>
      </aside>
      <section class="ad-pane">
        <div class="tabs ad-tabs">
          <button class="tab ad-tab active" data-tab="catalog">Catalog</button>
          <button class="tab ad-tab" data-tab="enrichment">Enrichment</button>
          <button class="tab ad-tab" data-tab="admin">Admin</button>
        </div>
        <div class="ad-tab-panel active" data-tab="catalog">
          <div class="filter-bar ad-filter-bar" id="ad-filter-bar"></div>
          <div class="ad-tags-panel" id="ad-tags-panel" style="display:none"></div>
          <div class="shelf-grid shelf-grid-titles" id="ad-portfolio-grid"></div>
          <div class="grid-status" id="ad-portfolio-status"></div>
          <div class="shelf-meta" id="ad-portfolio-meta" style="margin-top:8px"></div>
          <div id="ad-sentinel" style="height:1px"></div>
        </div>
        <div class="ad-tab-panel" data-tab="enrichment">
          <div class="jd-subnav ad-enrich-subnav">
            <button type="button" class="jd-subtab ad-enrich-subtab selected" data-subtab="titles">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="20" rx="2"/><line x1="2" y1="12" x2="22" y2="12"/></svg>
              Titles
            </button>
            <button type="button" class="jd-subtab ad-enrich-subtab" data-subtab="profile">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="8" r="4"/><path d="M4 20v-1a8 8 0 0 1 16 0v1"/></svg>
              Profile
            </button>
          </div>
          <div class="ad-enrich-titles-view"></div>
          <div class="ad-enrich-profile-view" style="display:none"></div>
        </div>
        <div class="ad-tab-panel" data-tab="admin">
          <!-- Admin workbench mounts here when the Admin tab is selected.
               mountAdminTab() sets id="actress-detail-admin-view" on this element. -->
        </div>
      </section>
    </div>`;

  document.querySelectorAll('.ad-tab').forEach(b => b.addEventListener('click', () => selectTab(b.dataset.tab)));

  loadAndRender(rootEl);
}

async function loadAndRender(rootEl) {
  const a = await fetchJson(`/api/actresses/${actressId}`, null);
  if (!a) {
    rootEl.querySelector('#ad-cover').innerHTML = '';
    rootEl.querySelector('#ad-sidebar').innerHTML = `
      <div class="empty-state">
        <div class="empty-state-title">Actress not found</div>
        <div class="empty-state-body">No actress with ID ${esc(actressId)}.</div>
      </div>`;
    return;
  }

  const crumb = document.getElementById('crumb-name');
  if (crumb) crumb.textContent = a.canonicalName || a.displayName || a.slug || '';
  document.title = `${a.canonicalName || a.displayName || 'Actress'} — Organizer3 v2`;

  renderCover(rootEl.querySelector('#ad-cover'), a);

  const sidebar = rootEl.querySelector('#ad-sidebar');
  sidebar.innerHTML = [
    renderIdentitySection(a),
    renderPrimaryActressSection(a),
    renderAliasesSection(a),
    renderProfileSection(a),
    renderLibrarySection(a),
    renderGradesSection(a),
    renderStudiosSection(a),
    renderAwardsSection(a),
    renderBiographySection(a),
    renderLegacySection(a),
    renderResearchChecklist(a),
  ].filter(Boolean).join('');

  wireIdentityActions(rootEl, a);
  renderFilterBar(rootEl.querySelector('#ad-filter-bar'), a);

  // Mount the sticky-note panel as the last child of the left rail (aside.ad-rail).
  mountActressNotePanel(actressId);

  // Portfolio + infinite scroll
  const grid = rootEl.querySelector('#ad-portfolio-grid');
  const statusEl = rootEl.querySelector('#ad-portfolio-status');
  const metaEl = rootEl.querySelector('#ad-portfolio-meta');
  const sentinel = rootEl.querySelector('#ad-sentinel');
  portfolioState = { offset: 0, loading: false, exhausted: false, items: [] };
  if (portfolioIO) portfolioIO.disconnect();
  portfolioIO = new IntersectionObserver(entries => {
    if (entries.some(e => e.isIntersecting)) loadPortfolio(grid, statusEl, metaEl);
  }, { rootMargin: '400px' });
  portfolioIO.observe(sentinel);
  loadPortfolio(grid, statusEl, metaEl);

  // 5s visit timer
  if (visitTimer) clearTimeout(visitTimer);
  visitTimer = setTimeout(() => {
    visitTimer = null;
    fetch(`/api/actresses/${actressId}/visit`, { method: 'POST' })
      .then(r => r.json())
      .then(d => {
        const row = document.getElementById('ad-visited-row');
        const val = document.getElementById('ad-visited-value');
        if (row && val && d.visitCount > 0) {
          val.textContent = formatVisited(d.visitCount, d.lastVisitedAt || null);
          row.style.display = '';
        }
      })
      .catch(() => {});
  }, VISIT_DELAY_MS);
}
