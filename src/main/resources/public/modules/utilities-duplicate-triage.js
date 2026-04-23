// Utilities → Duplicate Triage POC.
// Per-actress view, comparison grid, KEEP/TRASH/VARIANT decisions (UI-only, no persistence).
// See spec/UTILITIES_DUPLICATE_TRIAGE.md.

import { esc } from './utils.js';
import { updateBreadcrumb } from './grid.js';
import { rankLocations } from './duplicate-ranker.js';
import * as taskCenter from './task-center.js';

// ── Cover tooltip ─────────────────────────────────────────────────────────────
let _coverTip   = null;
let _coverTimer = null;

function showCoverTip(src, anchor) {
  clearTimeout(_coverTimer);
  _coverTimer = setTimeout(() => {
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
  }, 400);
}

function hideCoverTip() {
  clearTimeout(_coverTimer);
  if (_coverTip) _coverTip.style.display = 'none';
}

// ── Icons ─────────────────────────────────────────────────────────────────────
const ICON_VOL     = `<svg class="dt-path-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><rect x="1" y="2" width="10" height="8" rx="1"/><line x1="1" y1="7" x2="11" y2="7"/><circle cx="9" cy="9.2" r="0.7" fill="currentColor" stroke="none"/></svg>`;
const ICON_DIR     = `<svg class="dt-path-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"><path d="M1 9V4.5C1 4 1.4 3.5 2 3.5h3L6 5h4c.6 0 1 .4 1 1V9c0 .6-.4 1-1 1H2c-.6 0-1-.4-1-1z"/></svg>`;
const ICON_FILE    = `<svg class="dt-path-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 1h5l3 3v7H2V1z"/><path d="M7 1v3h3"/></svg>`;
const ICON_INSPECT = `<svg class="dt-inspect-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"><circle cx="6" cy="6" r="4.5"/><polygon points="4.5,4 8.5,6 4.5,8" fill="currentColor" stroke="none"/></svg>`;

// ── DOM ───────────────────────────────────────────────────────────────────────
const viewEl            = () => document.getElementById('tools-dup-triage-view');
const headlineEl        = () => document.getElementById('dt-headline');
const alphaBarEl        = () => document.getElementById('dt-alpha-bar');
const sortBarEl         = () => document.getElementById('dt-sort-bar');
const actressSidebarEl  = () => document.getElementById('dt-actress-sidebar');
const groupsEl          = () => document.getElementById('dt-groups');

// ── State ─────────────────────────────────────────────────────────────────────
// decisions: Map<titleCode, Map<locationIndex, 'KEEP'|'TRASH'|'VARIANT'>>
let allDuplicates = [];       // flat list of TitleSummary from API
let actressGroups = new Map(); // actressKey → { name, titles: TitleSummary[] }
let decisions = new Map();     // titleCode → Map<locIdx, decision>
let currentActressKey = null;
let currentLetterFilter = 'All';
let sortField = 'count'; // 'count' | 'name'
let sortDir   = 'desc';  // 'asc'  | 'desc'
let taskCenterUnsub = null;

export async function showDupTriageView() {
  viewEl().style.display = 'flex';
  updateBreadcrumb([{ label: 'Tools' }, { label: 'Duplicate Triage' }]);
  await loadAll();
  taskCenterUnsub?.();
  taskCenterUnsub = taskCenter.subscribe(() => {
    renderHeadline();
    renderActressSidebar();
  });
}

export function hideDupTriageView() {
  viewEl().style.display = 'none';
  taskCenterUnsub?.();
  taskCenterUnsub = null;
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
    await loadDecisions();
    renderHeadline();
    // Restore or default actress selection
    if (!currentActressKey || !actressGroups.has(currentActressKey)) {
      currentActressKey = actressGroups.keys().next().value || null;
    }

    renderAlphaBar();
    renderSortBar();
    renderActressSidebar();
    renderGroups();
  } catch (err) {
    headlineEl().textContent = 'Failed to load duplicates.';
    console.error('Duplicate triage load error', err);
  }
}

async function loadDecisions() {
  decisions = new Map();
  try {
    const res = await fetch('/api/tools/duplicates/decisions');
    if (!res.ok) return;
    const rows = await res.json();
    for (const row of rows) {
      const title = allDuplicates.find(t => t.code === row.titleCode);
      if (!title) continue;
      const locs = title.locationEntries || [];
      const locIdx = locs.findIndex(l => l.volumeId === row.volumeId && l.nasPath === row.nasPath);
      if (locIdx === -1) continue;
      if (!decisions.has(row.titleCode)) decisions.set(row.titleCode, new Map());
      decisions.get(row.titleCode).set(locIdx, row.decision);
    }
  } catch (err) {
    console.warn('Failed to load saved decisions', err);
  }
}

async function persistDecision(titleCode, loc, decision) {
  try {
    if (decision === null) {
      await fetch(
        `/api/tools/duplicates/decisions/${encodeURIComponent(titleCode)}/${encodeURIComponent(loc.volumeId)}?nasPath=${encodeURIComponent(loc.nasPath)}`,
        { method: 'DELETE' }
      );
    } else {
      await fetch('/api/tools/duplicates/decisions', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ titleCode, volumeId: loc.volumeId, nasPath: loc.nasPath, decision }),
      });
    }
  } catch (err) {
    console.error('Failed to persist decision for', titleCode, loc.nasPath, err);
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

  // Keep insertion order; sorting is applied in filteredGroups()
}

// ── Headlines ─────────────────────────────────────────────────────────────────

function renderHeadline() {
  const total   = allDuplicates.length;
  const cleaned = countCleaned();
  const queued  = pendingTrashCount();
  const el = headlineEl();

  const stats = `${total} found · ${cleaned} cleaned · ${total - cleaned} remaining`;
  const running = taskCenter.isRunning();

  if (queued > 0) {
    el.innerHTML = `
      <span class="dt-headline-stats">${esc(stats)}</span>
      <span class="dt-queue-badge">${queued} action${queued !== 1 ? 's' : ''} queued</span>
      <button class="dt-exec-all-btn" type="button"
        ${running ? 'disabled title="Another utility task is running"' : ''}>
        Execute all (${queued})
      </button>
    `;
    el.querySelector('.dt-exec-all-btn')?.addEventListener('click', () => runExecuteTask(null));
  } else {
    el.textContent = stats;
  }
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

function pendingTrashCount() {
  let n = 0;
  for (const [, dec] of decisions) {
    for (const [, d] of dec) { if (d === 'TRASH') n++; }
  }
  return n;
}

function actressTrashCount(group) {
  let n = 0;
  for (const title of group.titles) {
    const dec = decisions.get(title.code);
    if (!dec) continue;
    for (const [, d] of dec) { if (d === 'TRASH') n++; }
  }
  return n;
}

// ── Alpha filter bar ──────────────────────────────────────────────────────────

function occupiedLetters() {
  const seen = new Set();
  for (const [, group] of actressGroups) {
    const ch = group.name.charAt(0).toUpperCase();
    if (ch >= 'A' && ch <= 'Z') seen.add(ch);
  }
  return [...seen].sort();
}

function filteredGroups() {
  let entries = [...actressGroups.entries()];
  if (currentLetterFilter !== 'All') {
    entries = entries.filter(([, g]) => g.name.charAt(0).toUpperCase() === currentLetterFilter);
  }
  entries.sort(([, a], [, b]) => {
    let cmp;
    if (sortField === 'name') {
      cmp = a.name.localeCompare(b.name);
    } else {
      cmp = a.titles.length - b.titles.length;
    }
    return sortDir === 'asc' ? cmp : -cmp;
  });
  return entries;
}

function renderAlphaBar() {
  const bar = alphaBarEl();
  bar.innerHTML = '';

  const letters = occupiedLetters();
  const all = ['All', ...letters];

  for (const ltr of all) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = ltr;
    btn.className = 'dt-alpha-btn' + (ltr === currentLetterFilter ? ' active' : '');
    btn.addEventListener('click', () => {
      if (ltr === currentLetterFilter) return;
      currentLetterFilter = ltr;
      // If current actress no longer visible under new filter, pick first in filtered set
      const visible = filteredGroups();
      if (!visible.some(([k]) => k === currentActressKey)) {
        currentActressKey = visible[0]?.[0] || null;
      }
      renderAlphaBar();
      renderActressSidebar();
      renderGroups();
    });
    bar.appendChild(btn);
  }
}

function renderSortBar() {
  const bar = sortBarEl();
  bar.innerHTML = '';

  const fields = [{ id: 'name', label: 'Name' }, { id: 'count', label: 'Count' }];
  for (const f of fields) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = f.label;
    btn.className = 'dt-sort-btn' + (f.id === sortField ? ' active' : '');
    btn.addEventListener('click', () => {
      if (sortField === f.id) return;
      sortField = f.id;
      renderSortBar();
      renderActressSidebar();
    });
    bar.appendChild(btn);
  }

  const dirBtn = document.createElement('button');
  dirBtn.type = 'button';
  dirBtn.className = 'dt-sort-btn dt-sort-dir';
  dirBtn.title = sortDir === 'asc' ? 'Ascending' : 'Descending';
  dirBtn.textContent = sortDir === 'asc' ? '↑' : '↓';
  dirBtn.addEventListener('click', () => {
    sortDir = sortDir === 'asc' ? 'desc' : 'asc';
    renderSortBar();
    renderActressSidebar();
  });
  bar.appendChild(dirBtn);
}

// ── Actress sidebar ───────────────────────────────────────────────────────────

// Returns { state: 'complete'|'partial'|'none', pct: 0-1 }
function groupResolutionState(group) {
  let resolved = 0;
  for (const t of group.titles) {
    const locs = t.locationEntries || [];
    const dec  = decisions.get(t.code);
    if (dec && locs.every((_, i) => dec.has(i))) resolved++;
  }
  const total = group.titles.length;
  const pct   = total > 0 ? resolved / total : 0;
  if (pct >= 1)  return { state: 'complete', pct: 1 };
  if (pct > 0)   return { state: 'partial',  pct };
  return { state: 'none', pct: 0 };
}

function makePieSvg(pct) {
  const r = 4.5, cx = 6, cy = 6;
  const angle = 2 * Math.PI * pct;
  const ex    = cx + r * Math.sin(angle);
  const ey    = cy - r * Math.cos(angle);
  const large = pct > 0.5 ? 1 : 0;
  return `<svg class="dt-actress-state dt-actress-state-partial" viewBox="0 0 12 12" title="${Math.round(pct * 100)}% resolved">
    <circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="currentColor" stroke-width="1.5" opacity="0.3"/>
    <path d="M${cx},${cy} L${cx},${cy - r} A${r},${r} 0 ${large},1 ${ex.toFixed(2)},${ey.toFixed(2)} Z" fill="currentColor"/>
  </svg>`;
}

function renderActressSidebar() {
  const el = actressSidebarEl();
  el.innerHTML = '';

  for (const [key, group] of filteredGroups()) {
    const repTitle = [...group.titles].sort((a, b) => b.code.localeCompare(a.code))[0];
    const coverUrl = repTitle?.coverUrl
      || (repTitle ? `/covers/${encodeURIComponent(repTitle.label || '')}/${encodeURIComponent(repTitle.code)}.jpg` : null);

    const { state, pct } = groupResolutionState(group);
    const stateIcon = state === 'complete'
      ? `<svg class="dt-actress-state dt-actress-state-complete" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" title="All resolved"><polyline points="2,6 5,9 10,3"/></svg>`
      : state === 'partial'
      ? makePieSvg(pct)
      : '';
    const progressLine = state === 'partial'
      ? `<div class="dt-actress-progress">${Math.round(pct * 100)}% resolved</div>`
      : '';

    const trashN  = actressTrashCount(group);
    const running = taskCenter.isRunning();
    const execBtn = trashN > 0
      ? `<button class="dt-exec-actress-btn" type="button"
           ${running ? 'disabled title="Another task is running"' : ''}
           data-actress-key="${esc(key)}">
           Execute (${trashN})
         </button>`
      : '';

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
        ${progressLine}
        ${execBtn}
      </div>
      ${stateIcon}
    `;
    row.addEventListener('click', () => {
      currentActressKey = key;
      renderActressSidebar();
      renderGroups();
    });
    row.querySelector('.dt-exec-actress-btn')?.addEventListener('click', (e) => {
      e.stopPropagation();
      runExecuteTask(e.currentTarget.dataset.actressKey);
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
    rat.className   = 'dt-rationale' + (rank.suggestedIndex === null ? ' dt-rationale-identical' : '');
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

  // Inspect button — opens video player modal for this location
  if (videos.length > 0) {
    const inspectBtn = document.createElement('button');
    inspectBtn.type = 'button';
    inspectBtn.className = 'dt-inspect-btn';
    inspectBtn.title = 'Inspect videos in this folder';
    inspectBtn.innerHTML = ICON_INSPECT;
    inspectBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      openInspectModal(title, i, loc, videos);
    });
    cell.appendChild(inspectBtn);
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

  persistDecision(title.code, locs[locIdx], decision);

  // Auto-keep: if exactly one location remains non-trashed and has no decision, promote it
  if (decision === 'TRASH') {
    const survivors = locs.reduce((acc, _, i) => {
      if (dec.get(i) !== 'TRASH') acc.push(i);
      return acc;
    }, []);
    if (survivors.length === 1 && !dec.has(survivors[0])) {
      const autoIdx = survivors[0];
      dec.set(autoIdx, 'KEEP');
      persistDecision(title.code, locs[autoIdx], 'KEEP');
    }
  }

  renderHeadline();
  renderActressSidebar();
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

// ── Inspect modal ─────────────────────────────────────────────────────────────

function openInspectModal(title, locIdx, loc, videos) {
  document.querySelector('.dt-inspect-overlay')?.remove();

  const overlay = document.createElement('div');
  overlay.className = 'dt-inspect-overlay';

  const modal = document.createElement('div');
  modal.className = 'dt-inspect-modal';

  // Header
  const header = document.createElement('div');
  header.className = 'dt-inspect-header';
  header.innerHTML = `
    <div class="dt-inspect-header-info">
      <span class="dt-inspect-code">${esc(title.code)}</span>
      <span class="dt-inspect-loc-num">Location ${locIdx + 1} of ${(title.locationEntries || []).length}</span>
    </div>
    <div class="dt-inspect-path-line">${esc(loc.nasPath || '')}</div>
  `;

  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'dt-inspect-close';
  closeBtn.textContent = '✕';
  header.appendChild(closeBtn);

  modal.appendChild(header);

  // Body
  const body = document.createElement('div');
  body.className = 'dt-inspect-body';
  for (const v of videos) body.appendChild(buildInspectVideoSection(v));
  modal.appendChild(body);

  overlay.appendChild(modal);
  document.body.appendChild(overlay);

  const ac = new AbortController();
  const close = () => { overlay.remove(); ac.abort(); };
  closeBtn.addEventListener('click', close, { signal: ac.signal });
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); }, { signal: ac.signal });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); }, { signal: ac.signal });
}

function buildInspectVideoSection(v) {
  const section = document.createElement('div');
  section.className = 'dt-inspect-video-section';

  const filename = v.filename || v.path?.split('/').pop() || `Video ${v.id}`;

  section.innerHTML = `
    <div class="dt-inspect-filename">${esc(filename)}</div>
    <div class="dt-inspect-meta" id="dt-inspect-meta-${v.id}">…</div>
    <div class="dt-inspect-player-wrap" id="dt-inspect-wrap-${v.id}">
      <video class="dt-inspect-player" id="dt-inspect-player-${v.id}"
             controls preload="none" src="/api/stream/${v.id}"></video>
      <button class="dt-inspect-theater-btn" type="button">Theater</button>
    </div>
  `;

  section.querySelector('.dt-inspect-theater-btn').addEventListener('click', () => {
    const wrap = document.getElementById(`dt-inspect-wrap-${v.id}`);
    const active = wrap.classList.toggle('theater-mode');
    section.querySelector('.dt-inspect-theater-btn').textContent = active ? 'Exit Theater' : 'Theater';
  });

  loadInspectMeta(v.id);
  return section;
}

function loadInspectMeta(videoId) {
  fetch(`/api/videos/${videoId}/info`)
    .then(r => r.json())
    .then(info => {
      const el = document.getElementById(`dt-inspect-meta-${videoId}`);
      if (!el || !info) return;
      const parts = [];
      if (info.duration)    parts.push(info.duration);
      if (info.resolution)  parts.push(info.resolution);
      if (info.videoCodec)  parts.push(info.videoCodec);
      if (info.bitrate)     parts.push(info.bitrate);
      el.textContent = parts.join(' · ') || '—';
    })
    .catch(() => {
      const el = document.getElementById(`dt-inspect-meta-${videoId}`);
      if (el) el.textContent = '—';
    });
}

// ── Execute task ──────────────────────────────────────────────────────────────

async function runExecuteTask(actressKey) {
  if (taskCenter.isRunning()) return;
  const body = actressKey ? { actressKey } : {};
  let res;
  try {
    res = await fetch('/api/utilities/tasks/duplicates.execute_trash/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch (err) {
    alert('Failed to start execute task: ' + err.message);
    return;
  }
  const data = await res.json();
  if (!res.ok) {
    alert(data.error || 'Could not start task — another task may be running');
    return;
  }
  const runId = data.runId;
  const label = actressKey ? `Execute trash (${actressKey})` : 'Execute all trash';
  taskCenter.start({ taskId: 'duplicates.execute_trash', runId, label });
  subscribeToRun(runId);
  renderHeadline();
  renderActressSidebar();
}

function subscribeToRun(runId) {
  const src = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  const failedPhases = [];

  src.onmessage = e => {
    let ev;
    try { ev = JSON.parse(e.data); } catch { return; }

    if (ev.type === 'PhaseStarted') {
      taskCenter.updateProgress({ phaseLabel: ev.label });
    } else if (ev.type === 'PhaseProgress' && ev.total > 0) {
      taskCenter.updateProgress({ overallPct: Math.round((ev.current / ev.total) * 100) });
    } else if (ev.type === 'PhaseEnded' && ev.status !== 'ok') {
      failedPhases.push(ev.summary || ev.phaseId);
    } else if (ev.type === 'TaskEnded') {
      taskCenter.finish({ status: ev.status, summary: ev.summary });
      src.close();
      reconcileAfterExecute(ev.status, failedPhases);
    }
  };

  src.onerror = () => {
    src.close();
    taskCenter.finish({ status: 'failed', summary: 'Connection lost' });
    reconcileAfterExecute('failed', []);
  };
}

async function reconcileAfterExecute(status, failedPhases) {
  // Re-fetch everything — removed locations drop off the duplicates list naturally
  await loadAll();

  if (status !== 'ok' && failedPhases.length > 0) {
    showExecBanner(`Some actions failed during execute. Check the task log for details.`);
  }
}

function showExecBanner(message) {
  groupsEl().querySelector('.dt-exec-banner')?.remove();
  const banner = document.createElement('div');
  banner.className = 'dt-exec-banner';
  banner.innerHTML = `
    <span>${esc(message)}</span>
    <button class="dt-exec-banner-close" type="button">✕</button>
  `;
  banner.querySelector('.dt-exec-banner-close').addEventListener('click', () => banner.remove());
  groupsEl().prepend(banner);
}

// ── Event wiring ──────────────────────────────────────────────────────────────

export function wireDupTriageEvents() {
  // Sidebar click events are bound per-row in renderActressSidebar
}
