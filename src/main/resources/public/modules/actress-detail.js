import { esc, fmtDate, isStale, setStatus, timeAgo } from './utils.js';
import { ICON_FAV_LG, ICON_BM_LG, ICON_REJ_LG } from './icons.js';
import { showView, setActiveGrid, ensureActressDetailSentinel, ScrollingGrid, updateBreadcrumb, mode } from './grid.js';
import { makeTitleCard, updateActressCardIndicators } from './cards.js';
import { actressBrowseMode, actressBrowseLabel, selectActressBrowseMode, showActressLanding } from './actress-browse.js';

// ── State ─────────────────────────────────────────────────────────────────
export let detailActressId    = null;
export let detailCompanyFilter = null;
let detailActiveTags  = new Set();
let detailFilterTimer = null;
let detailActressTags = null;   // lazy-loaded tag list for current actress

const FILTER_DEBOUNCE_MS = 350;

// ── Visit tracking ────────────────────────────────────────────────────────
let pendingVisitTimer = null;

export function cancelPendingVisit() {
  if (pendingVisitTimer !== null) {
    clearTimeout(pendingVisitTimer);
    pendingVisitTimer = null;
  }
}

export const actressDetailGrid = new ScrollingGrid(
  document.getElementById('detail-title-grid'),
  (o, l) => {
    let url = `/api/actresses/${detailActressId}/titles?offset=${o}&limit=${l}`;
    if (detailCompanyFilter) url += `&company=${encodeURIComponent(detailCompanyFilter)}`;
    if (detailActiveTags.size > 0) url += `&tags=${encodeURIComponent([...detailActiveTags].join(','))}`;
    return url;
  },
  makeTitleCard,
  'no titles'
);

// ── Open actress detail ───────────────────────────────────────────────────
export async function openActressDetail(actressId) {
  cancelPendingVisit();

  const sourceMode    = mode;
  const sourceHomeTab = window._homeTab || 'latest'; // set by home.js

  detailActressId     = actressId;
  detailCompanyFilter = null;
  detailActiveTags    = new Set();
  detailActressTags   = null;
  showView('actress-detail');
  setActiveGrid(actressDetailGrid);
  document.getElementById('sentinel')?.remove();
  actressDetailGrid.reset();
  document.getElementById('detail-cover').innerHTML         = '';
  document.getElementById('detail-info').innerHTML          = '';
  document.getElementById('detail-profile').innerHTML       = '';
  document.getElementById('detail-bio').innerHTML           = '';
  document.getElementById('detail-filter-bar').innerHTML    = '';
  const tagsPanel = document.getElementById('detail-actress-tags-panel');
  if (tagsPanel) { tagsPanel.innerHTML = ''; tagsPanel.style.display = 'none'; }
  ensureActressDetailSentinel();
  setStatus('loading');

  let crumbs;
  if (sourceMode === 'titles' && sourceHomeTab === 'random-actresses') {
    crumbs = [];
  } else if (actressBrowseMode) {
    const modeKey = actressBrowseMode;
    crumbs = [
      { label: 'Actresses', action: () => showActressLanding() },
      { label: actressBrowseLabel(modeKey), action: () => selectActressBrowseMode(modeKey) },
    ];
  } else {
    crumbs = [{ label: 'Actresses', action: () => showActressLanding() }];
  }
  crumbs.push({ label: '...' });
  updateBreadcrumb(crumbs);

  try {
    const res = await fetch(`/api/actresses/${actressId}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    renderDetailPanel(data);
    crumbs[crumbs.length - 1] = { label: data.canonicalName };
    updateBreadcrumb(crumbs);

    // Start the 5-second visit timer for this actress.
    pendingVisitTimer = setTimeout(() => {
      pendingVisitTimer = null;
      fetch(`/api/actresses/${actressId}/visit`, { method: 'POST' })
        .then(r => r.json())
        .then(d => updateActressVisitedRow(d.visitCount, d.lastVisitedAt))
        .catch(() => {});
    }, 5000);
  } catch (err) {
    setStatus('error loading actress');
    console.error(err);
    return;
  }

  await actressDetailGrid.loadMore();
}

// ── Search stage name ─────────────────────────────────────────────────────
async function searchStageName(actressId) {
  const btn = document.getElementById('btn-search-stage-name');
  if (!btn) return;
  btn.disabled = true;
  btn.textContent = 'Searching…';
  btn.classList.add('loading');
  try {
    const res = await fetch(`/api/actresses/${actressId}/stage-name/search`, { method: 'POST' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    if (data.stageName) {
      openActressDetail(actressId);
    } else {
      btn.disabled = false;
      btn.textContent = 'Search for Stage Name';
      btn.classList.remove('loading');
      setStatus('stage name not found');
    }
  } catch (err) {
    console.error('Stage name search failed:', err);
    btn.disabled = false;
    btn.textContent = 'Search for Stage Name';
    btn.classList.remove('loading');
    setStatus('search failed');
  }
}

// ── Render detail panel ───────────────────────────────────────────────────
function renderDetailPanel(a) {
  // Column 1: Cover
  const coverCol = document.getElementById('detail-cover');
  const covers = a.coverUrls || [];
  if (covers.length > 0) {
    const idx = Math.floor(Math.random() * covers.length);
    coverCol.innerHTML = `<img src="${esc(covers[idx])}" alt="${esc(a.canonicalName)}" loading="lazy">`;
  } else {
    coverCol.innerHTML = `<div class="detail-cover-placeholder">—</div>`;
  }

  // Column 1 continued: Info
  const { first: firstName, last: lastName } = splitName(a.canonicalName);

  const aliases = a.aliases || [];
  let aliasHtml = '';
  if (a.primaryName) {
    const { first: pFirst, last: pLast } = splitName(a.primaryName);
    const pNameHtml = pLast ? `${esc(pFirst)} ${esc(pLast)}` : esc(pFirst);
    aliasHtml = `<div class="detail-alias-row">
      <span class="detail-alias-label">Primarily known as</span>
      <span class="primary-badge" data-actress-id="${a.primaryId || ''}">${pNameHtml}</span>
    </div>`;
  } else if (aliases.length > 0) {
    aliasHtml = `<div class="detail-alias-row">
      <span class="detail-alias-label">Also known as</span>
      ${aliases.map(al => `<span class="alias-badge">${esc(al)}</span>`).join('')}
    </div>`;
  }

  const stageNameHtml = a.stageName
    ? `<div class="detail-stage-name">${esc(a.stageName)}</div>`
    : `<button class="btn-search-stage-name" id="btn-search-stage-name">Search for Stage Name</button>`;

  const careerStart = a.activeFrom || a.firstAddedDate;
  const careerEnd   = a.activeTo   || a.lastAddedDate;
  let careerHtml = '';
  if (careerStart || careerEnd) {
    const startHtml = careerStart ? `<span class="date-first">${esc(fmtDate(careerStart))}</span>` : '';
    const endHtml   = careerEnd   ? `<span class="${isStale(careerEnd) ? 'date-last-stale' : 'date-last'}">${esc(fmtDate(careerEnd))}</span>` : '';
    const sep = startHtml && endHtml ? ' → ' : '';
    careerHtml = `<div class="detail-career">${startHtml}${sep}${endHtml}</div>`;
  }

  const visitedInfoHtml = a.visitCount > 0
    ? `<div class="detail-visited" id="detail-visited-row"><span id="detail-visited-value">${esc(formatActressVisited(a.visitCount, a.lastVisitedAt))}</span></div>`
    : `<div class="detail-visited" id="detail-visited-row" style="display:none"><span id="detail-visited-value"></span></div>`;

  const researchChecklistHtml = renderResearchChecklist(a);

  document.getElementById('detail-info').innerHTML = `
    <div class="detail-name">
      <span class="detail-first-name">${esc(firstName)}</span>
      ${lastName ? `<span class="detail-last-name">${esc(lastName)}</span>` : ''}
    </div>
    ${stageNameHtml}
    <div class="detail-meta-row">
      <span class="tier-badge tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>
      ${a.grade ? `<span class="detail-grade">${esc(a.grade)}</span>` : ''}
    </div>
    <div class="actress-detail-actions">
      <button class="title-action-btn${a.favorite ? ' active' : ''}" id="actress-fav-btn" title="Favorite">${ICON_FAV_LG}</button>
      <button class="title-action-btn${a.bookmark ? ' active' : ''}" id="actress-bm-btn" title="Bookmark">${ICON_BM_LG}</button>
      <button class="title-action-btn reject-btn${a.rejected ? ' active' : ''}" id="actress-rej-btn" title="Reject">${ICON_REJ_LG}</button>
    </div>
    ${aliasHtml}
    ${careerHtml}
    ${visitedInfoHtml}
    ${researchChecklistHtml}
  `;

  const btn = document.getElementById('btn-search-stage-name');
  if (btn) btn.addEventListener('click', () => searchStageName(a.id));

  function applyActressFlags(data) {
    a.favorite = data.favorite;
    a.bookmark = data.bookmark;
    a.rejected = data.rejected;
    document.getElementById('actress-fav-btn').classList.toggle('active', data.favorite);
    document.getElementById('actress-bm-btn').classList.toggle('active', data.bookmark);
    document.getElementById('actress-rej-btn').classList.toggle('active', data.rejected);
    updateActressCardIndicators(a.id, data.favorite, data.bookmark, data.rejected);
  }

  document.getElementById('actress-fav-btn').addEventListener('click', () => {
    fetch(`/api/actresses/${a.id}/favorite`, { method: 'POST' })
      .then(r => r.json()).then(applyActressFlags);
  });
  document.getElementById('actress-bm-btn').addEventListener('click', () => {
    fetch(`/api/actresses/${a.id}/bookmark`, { method: 'POST' })
      .then(r => r.json()).then(applyActressFlags);
  });
  document.getElementById('actress-rej-btn').addEventListener('click', () => {
    fetch(`/api/actresses/${a.id}/reject`, { method: 'POST' })
      .then(r => r.json()).then(applyActressFlags);
  });

  // Column 2: Profile
  const profileLines = [];
  if (a.dateOfBirth)  profileLines.push(['Born', esc(fmtDate(a.dateOfBirth))]);
  if (a.birthplace)   profileLines.push(['Birthplace', esc(a.birthplace)]);
  if (a.bloodType)    profileLines.push(['Blood Type', esc(a.bloodType)]);
  if (a.heightCm)     profileLines.push(['Height', `${a.heightCm} cm`]);
  if (a.bust || a.waist || a.hip) {
    const bwh = [a.bust || '?', a.waist || '?', a.hip || '?'].join(' / ');
    profileLines.push(['Measurements', bwh + (a.cup ? ` (${esc(a.cup)})` : '')]);
  }
  if (a.titleCount) profileLines.push(['Titles', `${a.titleCount}`]);

  const profileEl = document.getElementById('detail-profile');
  profileEl.innerHTML = profileLines.length > 0
    ? profileLines.map(([label, value]) =>
        `<div class="detail-profile-row"><span class="detail-profile-label">${label}</span><span class="detail-profile-value">${value}</span></div>`
      ).join('')
    : '';

  // Column 3: Biography
  const bioEl = document.getElementById('detail-bio');
  bioEl.innerHTML = a.biography ? `<div class="detail-bio-text">${esc(a.biography)}</div>` : '';

  // Filter bar: company dropdown + Tags button
  renderDetailFilterBar(a);
}

// ── Research checklist ────────────────────────────────────────────────────
// Mirrors ActressBrowseService.is{Profile,Physical,Biography,Portfolio}Filled
// so the user sees, on the detail page, exactly which buckets the dashboard's
// Research Gaps panel flagged as missing — and what fields would fill them.
function renderResearchChecklist(a) {
  const buckets = [
    {
      label: 'Profile',
      missing: [
        a.stageName   ? null : 'stage name',
        a.dateOfBirth ? null : 'date of birth',
        a.birthplace  ? null : 'birthplace',
      ].filter(Boolean),
    },
    {
      label: 'Physical',
      missing: [
        a.heightCm ? null : 'height',
        a.bust     ? null : 'bust',
        a.waist    ? null : 'waist',
        a.hip      ? null : 'hip',
      ].filter(Boolean),
    },
    {
      label: 'Biography',
      missing: (a.biography && a.biography.trim()) ? [] : ['biography'],
    },
    {
      label: 'Portfolio',
      missing: a.titleCount > 0 ? [] : ['no titles in library'],
    },
  ];

  const gapCount = buckets.filter(b => b.missing.length > 0).length;
  if (gapCount === 0) return ''; // hide entirely when fully researched

  const rows = buckets.map(b => {
    const filled = b.missing.length === 0;
    const status = filled ? 'filled' : 'missing';
    const detail = filled
      ? '<span class="research-bucket-detail">complete</span>'
      : `<span class="research-bucket-detail">missing: ${esc(b.missing.join(', '))}</span>`;
    return `<div class="research-bucket research-bucket-${status}">
      <span class="research-bucket-dot"></span>
      <span class="research-bucket-label">${b.label}</span>
      ${detail}
    </div>`;
  }).join('');

  return `<div class="detail-research-checklist">
    <div class="detail-research-checklist-title">Research gaps <span class="research-gap-count">${gapCount}/4</span></div>
    ${rows}
  </div>`;
}

// ── Visit display helpers ─────────────────────────────────────────────────
function formatActressVisited(count, lastVisitedAt) {
  const countLabel = count === 1 ? '1 view' : `${count} views`;
  return lastVisitedAt ? `${countLabel} (Visited ${timeAgo(lastVisitedAt)})` : countLabel;
}

function updateActressVisitedRow(visitCount, lastVisitedAt) {
  const row = document.getElementById('detail-visited-row');
  const val = document.getElementById('detail-visited-value');
  if (!row || !val || visitCount <= 0) return;
  val.textContent = formatActressVisited(visitCount, lastVisitedAt || null);
  row.style.display = '';
}

// splitName is needed here — inline since we can't import from utils without creating a local alias
function splitName(name) {
  const i = name.indexOf(' ');
  return i >= 0 ? { first: name.slice(0, i), last: name.slice(i + 1) } : { first: name, last: '' };
}

// ── Filter bar ────────────────────────────────────────────────────────────
function renderDetailFilterBar(a) {
  const companies = a.companies || [];
  const bar = document.getElementById('detail-filter-bar');
  if (!bar) return;

  // Company dropdown
  const selectHtml = `
    <select class="detail-company-select" id="detail-company-select">
      <option value="">All Companies</option>
      ${companies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('')}
    </select>`;

  // Tags button (only if the actress has titles to tag)
  const tagsHtml = `<button type="button" class="detail-tags-btn" id="detail-tags-btn">
    Tags<span class="detail-tags-count" id="detail-tags-count" style="display:none"></span>
  </button>`;

  bar.innerHTML = selectHtml + tagsHtml;

  document.getElementById('detail-company-select').addEventListener('change', e => {
    detailCompanyFilter = e.target.value || null;
    scheduleFilteredQuery();
  });

  document.getElementById('detail-tags-btn').addEventListener('click', toggleTagsPanel);
}

function scheduleFilteredQuery() {
  updateTagsBtn();
  if (detailFilterTimer) clearTimeout(detailFilterTimer);
  detailFilterTimer = setTimeout(() => {
    detailFilterTimer = null;
    document.getElementById('sentinel')?.remove();
    actressDetailGrid.reset();
    ensureActressDetailSentinel();
    actressDetailGrid.loadMore();
  }, FILTER_DEBOUNCE_MS);
}

function updateTagsBtn() {
  const countEl = document.getElementById('detail-tags-count');
  if (!countEl) return;
  if (detailActiveTags.size > 0) {
    countEl.textContent = detailActiveTags.size;
    countEl.style.display = '';
  } else {
    countEl.style.display = 'none';
  }
  const btn = document.getElementById('detail-tags-btn');
  if (btn) btn.classList.toggle('has-active', detailActiveTags.size > 0);
}

async function toggleTagsPanel() {
  const panel = document.getElementById('detail-actress-tags-panel');
  if (!panel) return;

  if (panel.style.display !== 'none') {
    panel.style.display = 'none';
    return;
  }

  // Load tags if not yet cached
  if (!detailActressTags) {
    panel.innerHTML = '<div class="detail-tags-loading">Loading tags\u2026</div>';
    panel.style.display = '';
    try {
      const res = await fetch(`/api/actresses/${detailActressId}/tags`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      detailActressTags = await res.json();
    } catch (err) {
      panel.innerHTML = '<div class="detail-tags-loading">Could not load tags</div>';
      return;
    }
  }

  renderTagsPanel(panel);
  panel.style.display = '';
}

function renderTagsPanel(panel) {
  const tags = detailActressTags || [];
  if (tags.length === 0) {
    panel.innerHTML = '<div class="detail-tags-loading">No tags available</div>';
    return;
  }
  panel.innerHTML = `
    <div class="detail-tags-inner">
      ${tags.map(t => `<button type="button" class="tag-toggle${detailActiveTags.has(t) ? ' active' : ''}" data-tag="${esc(t)}">${esc(t)}</button>`).join('')}
    </div>`;
  panel.querySelectorAll('.tag-toggle').forEach(btn => {
    btn.addEventListener('click', () => {
      const tag = btn.dataset.tag;
      if (detailActiveTags.has(tag)) { detailActiveTags.delete(tag); btn.classList.remove('active'); }
      else                           { detailActiveTags.add(tag);    btn.classList.add('active'); }
      scheduleFilteredQuery();
    });
  });
}

// ── Company filter (legacy export, now delegates to unified scheduler) ─────
export function setDetailCompanyFilter(company) {
  detailCompanyFilter = company;
  const sel = document.getElementById('detail-company-select');
  if (sel) sel.value = company || '';
  scheduleFilteredQuery();
}
