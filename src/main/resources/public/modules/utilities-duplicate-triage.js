// Utilities → Duplicate Triage POC.
// Per-actress view, comparison grid, KEEP/TRASH/VARIANT decisions (UI-only, no persistence).
// See spec/UTILITIES_DUPLICATE_TRIAGE.md.

import { esc } from './utils.js';
import { updateBreadcrumb } from './grid.js';
import { rankLocations } from './duplicate-ranker.js';

// ── Cover tooltip ─────────────────────────────────────────────────────────────
let _coverTip = null;

function showCoverTip(src, anchor) {
  if (!_coverTip) {
    _coverTip = document.createElement('div');
    _coverTip.className = 'dt-cover-tip';
    document.body.appendChild(_coverTip);
  }
  _coverTip.innerHTML = `<img src="${esc(src)}" alt="">`;
  _coverTip.style.display = 'block';

  const rect = anchor.getBoundingClientRect();
  const tipW = Math.min(480, window.innerWidth * 0.45);
  const left = (rect.right + 10 + tipW > window.innerWidth - 8)
    ? rect.left - tipW - 10
    : rect.right + 10;
  _coverTip.style.left = left + 'px';
  _coverTip.style.top  = rect.top  + 'px';
}

function hideCoverTip() {
  if (_coverTip) _coverTip.style.display = 'none';
}

// ── Icons ─────────────────────────────────────────────────────────────────────
const ICON_VOL  = `<svg class="dt-path-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><rect x="1" y="2" width="10" height="8" rx="1"/><line x1="1" y1="7" x2="11" y2="7"/><circle cx="9" cy="9.2" r="0.7" fill="currentColor" stroke="none"/></svg>`;
const ICON_DIR  = `<svg class="dt-path-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"><path d="M1 9V4.5C1 4 1.4 3.5 2 3.5h3L6 5h4c.6 0 1 .4 1 1V9c0 .6-.4 1-1 1H2c-.6 0-1-.4-1-1z"/></svg>`;
const ICON_FILE = `<svg class="dt-path-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 1h5l3 3v7H2V1z"/><path d="M7 1v3h3"/></svg>`;

// ── DOM ───────────────────────────────────────────────────────────────────────
const viewEl            = () => document.getElementById('tools-dup-triage-view');
const headlineEl        = () => document.getElementById('dt-headline');
const actressSidebarEl  = () => document.getElementById('dt-actress-sidebar');
const groupsEl          = () => document.getElementById('dt-groups');

// ── State ─────────────────────────────────────────────────────────────────────
// decisions: Map<titleCode, Map<locationIndex, 'KEEP'|'TRASH'|'VARIANT'>>
let allDuplicates = [];       // flat list of TitleSummary from API
let actressGroups = new Map(); // actressKey → { name, titles: TitleSummary[] }
let decisions = new Map();     // titleCode → Map<locIdx, decision>
let currentActressKey = null;

export async function showDupTriageView() {
  viewEl().style.display = 'flex';
  updateBreadcrumb([{ label: 'Tools' }, { label: 'Duplicate Triage' }]);
  await loadAll();
}

export function hideDupTriageView() {
  viewEl().style.display = 'none';
}

// ── Data loading ──────────────────────────────────────────────────────────────

async function loadAll() {
  headlineEl().textContent = 'Loading…';
  actressSidebarEl().innerHTML = '';
  groupsEl().innerHTML = '<div class="dt-load-splash"><span class="dt-spinner"></span><span>Fetching duplicates…</span></div>';

  try {
    // Fetch all duplicates (no pagination — bounded dataset)
    const all = [];
    let offset = 0;
    const limit = 200;
    while (true) {
      const res  = await fetch(`/api/tools/duplicates?offset=${offset}&limit=${limit}`);
      const data = await res.json();
      all.push(...data.titles);
      offset += data.titles.length;
      if (all.length >= data.total) break;
    }

    allDuplicates = all;
    buildActressGroups();
    renderHeadline();
    // Restore or default actress selection
    if (!currentActressKey || !actressGroups.has(currentActressKey)) {
      currentActressKey = actressGroups.keys().next().value || null;
    }

    renderActressSidebar();
    renderGroups();
  } catch (err) {
    headlineEl().textContent = 'Failed to load duplicates.';
    console.error('Duplicate triage load error', err);
  }
}

function buildActressGroups() {
  actressGroups = new Map();

  for (const title of allDuplicates) {
    const key  = title.actressId ? `id:${title.actressId}` : `name:${title.actressName || '(no actress)'}`;
    const name = title.actressName || '(no actress)';

    if (!actressGroups.has(key)) {
      actressGroups.set(key, { name, key, titles: [] });
    }
    actressGroups.get(key).titles.push(title);
  }

  // Sort groups: most titles first
  actressGroups = new Map(
    [...actressGroups.entries()].sort((a, b) => b[1].titles.length - a[1].titles.length)
  );
}

// ── Headlines ─────────────────────────────────────────────────────────────────

function renderHeadline() {
  const total   = allDuplicates.length;
  const cleaned = countCleaned();
  headlineEl().textContent =
    `${total} found · ${cleaned} cleaned · ${total - cleaned} remaining`;
}

function countCleaned() {
  let n = 0;
  for (const title of allDuplicates) {
    const locs = title.locationEntries || [];
    const dec  = decisions.get(title.code);
    if (!dec) continue;
    // A group is "cleaned" when every location has a decision
    if (locs.every((_, i) => dec.has(i))) n++;
  }
  return n;
}

// ── Actress sidebar ───────────────────────────────────────────────────────────

function renderActressSidebar() {
  const el = actressSidebarEl();
  el.innerHTML = '';

  for (const [key, group] of actressGroups) {
    const repTitle = [...group.titles].sort((a, b) => b.code.localeCompare(a.code))[0];
    const coverUrl = repTitle?.coverUrl
      || (repTitle ? `/covers/${encodeURIComponent(repTitle.label || '')}/${encodeURIComponent(repTitle.code)}.jpg` : null);

    const row = document.createElement('div');
    row.className = 'dt-actress-row' + (key === currentActressKey ? ' selected' : '');
    row.innerHTML = `
      <div class="dt-actress-portrait">
        <div class="dt-portrait-fallback">${esc(group.name.charAt(0).toUpperCase())}</div>
        ${coverUrl ? `<img src="${esc(coverUrl)}" alt="" onerror="this.style.display='none'">` : ''}
      </div>
      <div class="dt-actress-info">
        <div class="dt-actress-name">${esc(group.name)}</div>
        <div class="dt-actress-count">${group.titles.length} title${group.titles.length !== 1 ? 's' : ''}</div>
      </div>
    `;
    row.addEventListener('click', () => {
      currentActressKey = key;
      renderActressSidebar();
      renderGroups();
    });
    el.appendChild(row);
  }

  el.querySelector('.dt-actress-row.selected')?.scrollIntoView({ block: 'nearest' });
}

// ── Group rendering ───────────────────────────────────────────────────────────

async function renderGroups() {
  const el = groupsEl();
  el.innerHTML = '';

  if (!currentActressKey) return;
  const group = actressGroups.get(currentActressKey);
  if (!group || group.titles.length === 0) return;

  // Closure badge
  const allResolved = group.titles.every(t => {
    const dec = decisions.get(t.code);
    const locs = t.locationEntries || [];
    return dec && locs.every((_, i) => dec.has(i));
  });
  if (allResolved) {
    const done = document.createElement('div');
    done.className = 'dt-closure';
    done.textContent = `✓ ${group.name} — all duplicates resolved`;
    el.appendChild(done);
  }

  const total = group.titles.length;
  const prog  = document.createElement('div');
  prog.className = 'dt-load-progress';
  el.appendChild(prog);

  for (let n = 0; n < total; n++) {
    prog.innerHTML = `<span class="dt-spinner"></span><span>Loading ${n + 1} / ${total}…</span>`;
    el.appendChild(await buildTitleCard(group.titles[n]));
  }

  prog.remove();
}

async function buildTitleCard(title) {
  const locs = title.locationEntries || [];
  const card = document.createElement('div');
  card.className = 'dt-card';

  // Title header
  const header = document.createElement('div');
  header.className = 'dt-card-header';

  const coverUrl = title.coverUrl || `/covers/${encodeURIComponent(title.label || '')}/${encodeURIComponent(title.code)}.jpg`;

  const coverImg = document.createElement('img');
  coverImg.className = 'dt-cover';
  coverImg.src = coverUrl;
  coverImg.alt = '';
  coverImg.onerror = () => { coverImg.style.display = 'none'; };
  coverImg.addEventListener('mouseenter', () => showCoverTip(coverUrl, coverImg));
  coverImg.addEventListener('mouseleave', hideCoverTip);
  header.appendChild(coverImg);

  const titleInfo = document.createElement('div');
  titleInfo.className = 'dt-card-title';
  titleInfo.innerHTML = `
    <span class="dt-code">${esc(title.code)}</span>
    ${title.titleEnglish ? `<span class="dt-title-en">${esc(title.titleEnglish)}</span>` : ''}
    <span class="dt-loc-count">${locs.length} locations</span>
  `;
  header.appendChild(titleInfo);
  card.appendChild(header);

  // Fetch videos for each location
  const locVideos = await fetchLocVideos(title.code, locs);

  // Rank
  const locData = locs.map((loc, i) => ({ ...loc, videos: locVideos[i] || [] }));
  const rank    = rankLocations(locData);

  if (rank.rationale) {
    const rat = document.createElement('div');
    rat.className   = 'dt-rationale';
    rat.textContent = rank.rationale;
    card.appendChild(rat);
  }

  // Location grid
  const grid = document.createElement('div');
  grid.className = 'dt-grid';

  for (let i = 0; i < locs.length; i++) {
    grid.appendChild(buildLocCell(title, locs, i, locVideos[i] || [], rank.suggestedIndex));
  }

  card.appendChild(grid);
  return card;
}

function buildLocCell(title, locs, i, videos, suggestedIndex) {
  const loc = locs[i];
  const dec = decisions.get(title.code);
  const current = dec?.get(i) || null;

  const cell = document.createElement('div');
  cell.className = 'dt-cell';
  if (current) cell.classList.add(`dt-cell-${current.toLowerCase()}`);
  if (i === suggestedIndex) cell.classList.add('dt-cell-suggested');

  // Path
  const fullPath   = loc.nasPath || '';
  const parts      = fullPath.split('/');
  const volPath    = parts.slice(0, 4).join('/');
  const relParts   = parts.slice(4);
  const titleFolder = relParts[relParts.length - 1] || '';
  const relParent  = relParts.slice(0, -1).join('/');

  function makeCopyRow(icon, text) {
    const row = document.createElement('div');
    row.className = 'dt-path-row dt-path-row-copy';
    row.title = 'Click to copy full path';
    row.innerHTML = `${icon}<span class="dt-path-rest">${esc(text)}</span>`;
    row.addEventListener('click', () => {
      navigator.clipboard.writeText(fullPath).then(() => {
        row.classList.add('dt-path-copied');
        setTimeout(() => row.classList.remove('dt-path-copied'), 1400);
      });
    });
    return row;
  }

  const pathEl = document.createElement('div');
  pathEl.className = 'dt-cell-path';

  const volRow = document.createElement('div');
  volRow.className = 'dt-path-row';
  volRow.innerHTML = `${ICON_VOL}<span class="dt-path-base">${esc(volPath)}</span>`;
  pathEl.appendChild(volRow);

  if (relParent)   pathEl.appendChild(makeCopyRow(ICON_DIR,  relParent));
  if (titleFolder) pathEl.appendChild(makeCopyRow(ICON_FILE, titleFolder));

  cell.appendChild(pathEl);

  // Video summary
  if (videos.length > 0) {
    const vsum = document.createElement('div');
    vsum.className = 'dt-cell-vsum';
    vsum.innerHTML = videos.map(v => videoChip(v)).join('');
    cell.appendChild(vsum);
  }

  if (i === suggestedIndex) {
    const badge = document.createElement('span');
    badge.className = 'dt-suggested-badge';
    badge.textContent = 'Suggested keep';
    cell.appendChild(badge);
  }

  // Decision buttons
  const actions = document.createElement('div');
  actions.className = 'dt-cell-actions';

  const keepBtn = makeDecisionBtn('KEEP', current === 'KEEP');
  keepBtn.addEventListener('click', () => setDecision(title, locs, i, current === 'KEEP' ? null : 'KEEP'));
  actions.appendChild(keepBtn);

  // TRASH — disabled if it's the last non-trashed location
  const trashBtn = makeDecisionBtn('TRASH', current === 'TRASH');
  const wouldTrashLast = isLastNonTrashed(title, locs, i);
  trashBtn.disabled = wouldTrashLast && current !== 'TRASH';
  if (trashBtn.disabled) trashBtn.title = 'Cannot trash the last copy';
  trashBtn.addEventListener('click', () => setDecision(title, locs, i, current === 'TRASH' ? null : 'TRASH'));
  actions.appendChild(trashBtn);

  const variantBtn = makeDecisionBtn('VARIANT', current === 'VARIANT');
  variantBtn.addEventListener('click', () => setDecision(title, locs, i, current === 'VARIANT' ? null : 'VARIANT'));
  actions.appendChild(variantBtn);

  cell.appendChild(actions);
  return cell;
}

function makeDecisionBtn(decision, active) {
  const LABELS   = { KEEP: 'Keep', TRASH: 'Trash', VARIANT: 'Alt Copy' };
  const ICONS    = { KEEP: '✓', TRASH: '✕', VARIANT: '⊕' };
  const TOOLTIPS = {
    KEEP:    'Keep this copy — mark it as the version to preserve',
    TRASH:   'Trash this copy — mark it for deletion',
    VARIANT: 'Alt Copy — keep as a separate version (different cut, format, or quality)',
  };
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.title = TOOLTIPS[decision];
  btn.innerHTML = `<span class="dt-btn-icon">${ICONS[decision]}</span>${LABELS[decision]}`;
  btn.className = `dt-dec-btn dt-dec-${decision.toLowerCase()}` + (active ? ' active' : '');
  return btn;
}

function videoChip(v) {
  const parts = [];
  if (v.width && v.height) parts.push(`${v.width}×${v.height}`);
  if (v.videoCodec) parts.push(v.videoCodec.toUpperCase());
  if (v.fileSize) parts.push(fmtBytes(v.fileSize));
  return `<span class="dt-chip">${esc(parts.join(' · '))}</span>`;
}

function fmtBytes(b) {
  if (b >= 1e9) return (b / 1e9).toFixed(1) + ' GB';
  if (b >= 1e6) return (b / 1e6).toFixed(0) + ' MB';
  return (b / 1e3).toFixed(0) + ' KB';
}

// ── Invariant check ───────────────────────────────────────────────────────────

function isLastNonTrashed(title, locs, candidateIdx) {
  const dec = decisions.get(title.code) || new Map();
  // Count locations that are NOT trashed (excluding the candidate)
  const nonTrashedOthers = locs.filter((_, i) => {
    if (i === candidateIdx) return false;
    return dec.get(i) !== 'TRASH';
  });
  return nonTrashedOthers.length === 0;
}

// ── Decision management ───────────────────────────────────────────────────────

function setDecision(title, locs, locIdx, decision) {
  if (!decisions.has(title.code)) decisions.set(title.code, new Map());
  const dec = decisions.get(title.code);

  if (decision === null) {
    dec.delete(locIdx);
  } else {
    dec.set(locIdx, decision);
  }

  renderHeadline();
  renderGroups();
}

// ── Video fetching ────────────────────────────────────────────────────────────

async function fetchLocVideos(titleCode, locs) {
  const results = await Promise.all(
    locs.map(async (loc) => {
      try {
        const res = await fetch(`/api/titles/${encodeURIComponent(titleCode)}/videos?volumeId=${encodeURIComponent(loc.volumeId)}`);
        return await res.json();
      } catch {
        return [];
      }
    })
  );
  return results;
}

// ── Event wiring ──────────────────────────────────────────────────────────────

export function wireDupTriageEvents() {
  // Sidebar click events are bound per-row in renderActressSidebar
}
