import { esc, fmtDate, isStale, setStatus, splitName, timeAgo } from './utils.js';
import { ICON_FAV_LG, ICON_BM_LG, ICON_REJ_LG } from './icons.js';
import { showView, setActiveGrid, ensureActressDetailSentinel, ScrollingGrid, updateBreadcrumb, mode } from './grid.js';
import { makeTitleCard, updateActressCardIndicators } from './cards.js';
import { getActressBrowseMode, actressBrowseLabel, selectActressBrowseMode, showActressLanding, hideAllActressSubNavRows } from './actress-browse.js';
import { pushNav } from './nav.js';

// ── State ─────────────────────────────────────────────────────────────────
export let detailActressId    = null;
export let detailCompanyFilter = null;
let detailActiveTags  = new Set();
let detailFilterTimer = null;
let detailActressTags = null;   // lazy-loaded tag list for current actress
let coverRotateTimer  = null;

function cancelCoverRotation() {
  if (coverRotateTimer !== null) {
    clearInterval(coverRotateTimer);
    coverRotateTimer = null;
  }
}

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
  pushNav({ view: 'actress-detail', actressId }, 'actress/' + actressId);
  cancelPendingVisit();
  cancelCoverRotation();
  hideAllActressSubNavRows();

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
  document.getElementById('detail-sidebar').innerHTML       = '';
  document.getElementById('detail-filter-bar').innerHTML    = '';
  const tagsPanel = document.getElementById('detail-actress-tags-panel');
  if (tagsPanel) { tagsPanel.innerHTML = ''; tagsPanel.style.display = 'none'; }
  ensureActressDetailSentinel();
  setStatus('loading');

  let crumbs;
  if (sourceMode === 'titles' && sourceHomeTab === 'random-actresses') {
    crumbs = [];
  } else if (getActressBrowseMode()) {
    const modeKey = getActressBrowseMode();
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
//
// Sidebar is composed of sections, each hidden when empty. The visible order is:
//   1. Cover image
//   2. Identity (name, kanji + reading, tier/grade badges, flag buttons)
//   3. Also-known-as (aliases with notes)
//   4. Career strip (date range + duration + retirement + visits)
//   5. Vitals (born / from / blood / height / measures)
//   6. Library (count, studio count, first/last added)
//   7. Studios timeline (YAML primary_studios)
//   8. Awards
//   9. Biography
//  10. Legacy
//  11. Research gaps (hidden when complete)
//
// Most actresses will only render the Cover/Identity/Library/Research sections —
// enriched YAML data is still rare.
function renderDetailPanel(a) {
  renderCover(a);
  renderSidebarSections(a);
  wireActionButtons(a);
  renderDetailFilterBar(a);
}

function renderCover(a) {
  const coverCol = document.getElementById('detail-cover');
  const covers = a.coverUrls || [];
  if (covers.length === 0) {
    coverCol.innerHTML = `<div class="detail-cover-placeholder">—</div>`;
    return;
  }

  let currentIdx = Math.floor(Math.random() * covers.length);
  coverCol.innerHTML = `<img src="${esc(covers[currentIdx])}" alt="${esc(a.canonicalName)}" loading="lazy">`;

  if (covers.length < 2) return;

  coverRotateTimer = setInterval(() => {
    const col = document.getElementById('detail-cover');
    if (!col) { cancelCoverRotation(); return; }
    let nextIdx;
    do { nextIdx = Math.floor(Math.random() * covers.length); } while (nextIdx === currentIdx);
    currentIdx = nextIdx;
    const img = col.querySelector('img');
    if (img) img.src = covers[currentIdx];
  }, 10_000);
}

function renderSidebarSections(a) {
  const sidebar = document.getElementById('detail-sidebar');
  const sections = [
    renderIdentitySection(a),
    renderPrimaryActressSection(a),
    renderAliasesSection(a),
    renderCareerSection(a),
    renderVitalsSection(a),
    renderLibrarySection(a),
    renderStudiosSection(a),
    renderAwardsSection(a),
    renderBiographySection(a),
    renderLegacySection(a),
    renderResearchChecklist(a),
  ].filter(Boolean);

  sidebar.innerHTML = sections.join('');

  sidebar.querySelectorAll('.alias-badge-link, .primary-actress-link').forEach(btn => {
    btn.addEventListener('click', () => openActressDetail(Number(btn.dataset.actressId)));
  });

  sidebar.querySelectorAll('.actress-detail-folder-path').forEach(el => {
    el.addEventListener('click', (e) => {
      e.preventDefault();
      copyActressFolderPath(el, el.dataset.smb);
    });
  });
}

// ── Section: Identity (name + kanji + badges + actions) ──────────────────
function renderIdentitySection(a) {
  const { first: firstName, last: lastName } = splitName(a.canonicalName);

  const stageNameHtml = a.stageName
    ? `<div class="detail-stage-name">
         <span class="detail-stage-name-kanji">${esc(a.stageName)}</span>
         ${a.nameReading ? `<span class="detail-stage-name-reading">${esc(a.nameReading)}</span>` : ''}
       </div>`
    : `<button class="btn-search-stage-name" id="btn-search-stage-name">Search for Stage Name</button>`;

  const tierBadge = `<span class="tier-badge tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>`;
  const gradeBadge = a.grade ? `<span class="detail-grade">${esc(a.grade)}</span>` : '';

  return `
    <section class="detail-section detail-section-identity">
      <div class="detail-name">
        <span class="detail-first-name">${esc(firstName)}</span>
        ${lastName ? `<span class="detail-last-name">${esc(lastName)}</span>` : ''}
      </div>
      ${stageNameHtml}
      <div class="detail-meta-row">
        ${tierBadge}
        ${gradeBadge}
      </div>
      <div class="actress-detail-actions">
        <button class="title-action-btn${a.favorite ? ' active' : ''}" id="actress-fav-btn" title="Favorite">${ICON_FAV_LG}</button>
        <button class="title-action-btn${a.bookmark ? ' active' : ''}" id="actress-bm-btn" title="Bookmark">${ICON_BM_LG}</button>
        <button class="title-action-btn reject-btn${a.rejected ? ' active' : ''}" id="actress-rej-btn" title="Reject">${ICON_REJ_LG}</button>
      </div>
      ${renderActressFolderPaths(a)}
    </section>
  `;
}

function renderActressFolderPaths(a) {
  const paths = a.folderPaths || [];
  if (paths.length === 0) return '';
  const links = paths.map(p =>
    `<a class="actress-detail-folder-path" href="#" data-smb="${esc(p)}">${esc(p)}</a>`
  ).join('');
  return `<div class="actress-detail-folder-paths">${links}</div>`;
}

function copyActressFolderPath(el, smbUrl) {
  const isMac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
  const text = isMac
    ? smbUrl
    : smbUrl.replace(/^smb:\/\//, '\\\\').replace(/\//g, '\\');

  const orig = el.textContent;
  const confirm = () => {
    el.textContent = 'Copied!';
    setTimeout(() => { el.textContent = orig; }, 1500);
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

// ── Section: Primary actress (shown when this actress is an alias of another) ─
function renderPrimaryActressSection(a) {
  if (!a.primaryActressId || !a.primaryActressName) return '';
  return `<section class="detail-section detail-section-primary-actress">
    <h3 class="detail-section-title">Primarily Known As</h3>
    <div class="detail-alias-cloud">
      <button class="alias-badge alias-badge-link primary-actress-link" data-actress-id="${a.primaryActressId}">${esc(a.primaryActressName)}</button>
    </div>
  </section>`;
}

// ── Section: Aliases / Also Known As ──────────────────────────────────────
// Prefers rich alternate_names (with attribution notes) from YAML; falls back
// to the flat alias list used for sync name resolution.
function renderAliasesSection(a) {
  const alt = a.alternateNames || [];
  if (alt.length > 0) {
    const rows = alt.map(n => `
      <li class="detail-alt-name">
        <span class="detail-alt-name-value">${esc(n.name || '')}</span>
        ${n.note ? `<span class="detail-alt-name-note">${esc(n.note)}</span>` : ''}
      </li>
    `).join('');
    return sectionShell('Also Known As', `<ul class="detail-alt-names-list">${rows}</ul>`);
  }
  const aliases = a.aliases || [];
  if (aliases.length > 0) {
    const badges = aliases.map(al => {
      if (al.actressId) {
        return `<button class="alias-badge alias-badge-link" data-actress-id="${al.actressId}">${esc(al.name)}</button>`;
      }
      return `<span class="alias-badge">${esc(al.name)}</span>`;
    }).join('');
    return sectionShell('Also Known As', `<div class="detail-alias-cloud">${badges}</div>`);
  }
  return '';
}

// ── Section: Career ───────────────────────────────────────────────────────
function renderCareerSection(a) {
  const careerStart = a.activeFrom || a.firstAddedDate;
  const careerEnd   = a.activeTo   || a.lastAddedDate;
  if (!careerStart && !careerEnd && !a.retirementAnnounced && !(a.visitCount > 0)) return '';

  const rangeHtml = (careerStart || careerEnd) ? (() => {
    const startHtml = careerStart ? `<span class="date-first">${esc(fmtDate(careerStart))}</span>` : '';
    const endHtml   = careerEnd   ? `<span class="${isStale(careerEnd) ? 'date-last-stale' : 'date-last'}">${esc(fmtDate(careerEnd))}</span>` : '';
    const sep = startHtml && endHtml ? ' → ' : '';
    return `<div class="detail-career">${startHtml}${sep}${endHtml}</div>`;
  })() : '';

  const durationHtml = (careerStart && careerEnd)
    ? `<div class="detail-career-duration">${esc(formatDuration(careerStart, careerEnd))} active</div>`
    : '';

  const retirementHtml = a.retirementAnnounced
    ? `<div class="detail-career-retirement">Retirement announced ${esc(fmtDate(a.retirementAnnounced))}</div>`
    : '';

  const visitedDisplay = a.visitCount > 0 ? formatActressVisited(a.visitCount, a.lastVisitedAt) : '';
  const visitedHtml = `<div class="detail-visited" id="detail-visited-row" ${a.visitCount > 0 ? '' : 'style="display:none"'}>
      <span id="detail-visited-value">${esc(visitedDisplay)}</span>
    </div>`;

  const inner = `
    ${rangeHtml}
    ${durationHtml}
    ${retirementHtml}
    ${visitedHtml}
  `;
  return sectionShell('Career', inner);
}

// ── Section: Vitals ───────────────────────────────────────────────────────
function renderVitalsSection(a) {
  const rows = [];
  if (a.dateOfBirth) {
    const age = computeAge(a.dateOfBirth, a.activeTo);
    const ageLabel = age != null
      ? (a.activeTo ? ` <span class="vital-subtle">age ${age} at retirement</span>` : ` <span class="vital-subtle">age ${age}</span>`)
      : '';
    rows.push(vitalRow('Born', `${esc(fmtDate(a.dateOfBirth))}${ageLabel}`));
  }
  if (a.birthplace)  rows.push(vitalRow('From',   esc(a.birthplace)));
  if (a.bloodType)   rows.push(vitalRow('Blood',  esc(a.bloodType)));
  if (a.heightCm)    rows.push(vitalRow('Height', `${a.heightCm} cm`));
  if (a.bust || a.waist || a.hip) {
    const bwh = [a.bust || '—', a.waist || '—', a.hip || '—'].join(' · ');
    const cupHtml = a.cup ? `<span class="vital-subtle">${esc(a.cup)} cup</span>` : '';
    rows.push(vitalRow('Measures', `${bwh} ${cupHtml}`));
  }
  if (rows.length === 0) return '';
  return sectionShell('Vitals', `<div class="detail-vitals">${rows.join('')}</div>`);
}

function vitalRow(label, valueHtml) {
  return `<div class="detail-vital-row">
    <span class="detail-vital-label">${label}</span>
    <span class="detail-vital-value">${valueHtml}</span>
  </div>`;
}

// ── Section: Library (derived from local DB) ─────────────────────────────
function renderLibrarySection(a) {
  if (!a.titleCount && !a.firstAddedDate && !a.lastAddedDate) return '';

  const rows = [];
  if (a.titleCount != null) {
    const titleWord = a.titleCount === 1 ? 'title' : 'titles';
    const companyCount = (a.companies || []).length;
    const studioFragment = companyCount > 0
      ? ` <span class="vital-subtle">· ${companyCount} ${companyCount === 1 ? 'company' : 'companies'}</span>`
      : '';
    rows.push(vitalRow('Library', `${a.titleCount} ${titleWord}${studioFragment}`));
  }
  if (a.firstAddedDate) rows.push(vitalRow('First seen', esc(fmtDate(a.firstAddedDate))));
  if (a.lastAddedDate)  rows.push(vitalRow('Last added', esc(fmtDate(a.lastAddedDate))));

  if (rows.length === 0) return '';
  return sectionShell('Library', `<div class="detail-vitals">${rows.join('')}</div>`);
}

// ── Section: Studio tenures (YAML primary_studios) ───────────────────────
function renderStudiosSection(a) {
  const studios = a.primaryStudios || [];
  if (studios.length === 0) return '';
  const items = studios.map(s => {
    const from = s.from ? fmtYearMonth(s.from) : '';
    const to   = s.to   ? fmtYearMonth(s.to)   : '';
    const sep  = from && to ? ' – ' : '';
    const dates = from || to ? `<div class="detail-studio-dates">${esc(from)}${sep}${esc(to)}</div>` : '';
    const name  = s.name    ? `<div class="detail-studio-name">${esc(s.name)}</div>` : '';
    const company = s.company && s.company !== s.name
      ? `<div class="detail-studio-company">${esc(s.company)}</div>`
      : '';
    const role = s.role ? `<div class="detail-studio-role">${esc(s.role)}</div>` : '';
    return `<li class="detail-studio-entry">${name}${company}${dates}${role}</li>`;
  }).join('');
  return sectionShell('Studios', `<ul class="detail-studios-list">${items}</ul>`);
}

// ── Section: Awards ───────────────────────────────────────────────────────
function renderAwardsSection(a) {
  const awards = a.awards || [];
  if (awards.length === 0) return '';
  const items = awards.map(aw => {
    const yearHtml = aw.year ? `<span class="detail-award-year">${esc(String(aw.year))}</span>` : '';
    const eventHtml = aw.event ? `<div class="detail-award-event">${esc(aw.event)}</div>` : '';
    const catHtml = aw.category ? `<div class="detail-award-category">${esc(aw.category)}</div>` : '';
    return `<li class="detail-award-entry">
      ${yearHtml}
      <div class="detail-award-body">${eventHtml}${catHtml}</div>
    </li>`;
  }).join('');
  return sectionShell('Awards', `<ul class="detail-awards-list">${items}</ul>`);
}

// ── Section: Biography ────────────────────────────────────────────────────
function renderBiographySection(a) {
  if (!a.biography || !a.biography.trim()) return '';
  return sectionShell('Biography', `<div class="detail-bio-text">${esc(a.biography)}</div>`);
}

// ── Section: Legacy (YAML legacy field — short capstone quote) ───────────
function renderLegacySection(a) {
  if (!a.legacy || !a.legacy.trim()) return '';
  return sectionShell('Legacy', `<blockquote class="detail-legacy-text">${esc(a.legacy)}</blockquote>`);
}

// ── Shared section shell ──────────────────────────────────────────────────
function sectionShell(title, inner) {
  return `<section class="detail-section">
    <h3 class="detail-section-title">${esc(title)}</h3>
    ${inner}
  </section>`;
}

// ── Date / duration helpers ───────────────────────────────────────────────
function fmtYearMonth(dateStr) {
  if (!dateStr) return '';
  const d = new Date(dateStr + 'T00:00:00');
  if (isNaN(d)) return dateStr;
  return d.toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
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

function computeAge(dobStr, asOfStr) {
  const dob = new Date(dobStr + 'T00:00:00');
  if (isNaN(dob)) return null;
  const asOf = asOfStr ? new Date(asOfStr + 'T00:00:00') : new Date();
  if (isNaN(asOf)) return null;
  let age = asOf.getFullYear() - dob.getFullYear();
  const m = asOf.getMonth() - dob.getMonth();
  if (m < 0 || (m === 0 && asOf.getDate() < dob.getDate())) age--;
  return age >= 0 ? age : null;
}

// ── Wire up flag buttons and stage-name search after render ──────────────
function wireActionButtons(a) {
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
