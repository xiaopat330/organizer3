// Utilities → Duplicate Triage POC.
// Per-actress view, comparison grid, KEEP/TRASH/VARIANT decisions (UI-only, no persistence).
// See spec/UTILITIES_DUPLICATE_TRIAGE.md.

import { esc } from './utils.js';
import { updateBreadcrumb } from './grid.js';
import { rankLocations } from './duplicate-ranker.js';

// ── DOM ───────────────────────────────────────────────────────────────────────
const viewEl       = () => document.getElementById('tools-dup-triage-view');
const headlineEl   = () => document.getElementById('dt-headline');
const actressSelEl = () => document.getElementById('dt-actress-sel');
const groupsEl     = () => document.getElementById('dt-groups');

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
  actressSelEl().innerHTML = '';
  groupsEl().innerHTML     = '';

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
    populateActressDropdown();

    // Restore or default actress selection
    if (currentActressKey && actressGroups.has(currentActressKey)) {
      actressSelEl().value = currentActressKey;
    } else {
      currentActressKey = actressSelEl().value || null;
    }

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

// ── Actress dropdown ──────────────────────────────────────────────────────────

function populateActressDropdown() {
  const sel = actressSelEl();
  sel.innerHTML = '';

  for (const [key, group] of actressGroups) {
    const opt = document.createElement('option');
    opt.value       = key;
    opt.textContent = `${group.name} (${group.titles.length})`;
    sel.appendChild(opt);
  }
}

// ── Group rendering ───────────────────────────────────────────────────────────

async function renderGroups() {
  const el = groupsEl();
  el.innerHTML = '';

  if (!currentActressKey) return;
  const group = actressGroups.get(currentActressKey);
  if (!group) return;

  // Closure badge
  const allResolved = group.titles.every(t => {
    const dec = decisions.get(t.code);
    const locs = t.locationEntries || [];
    return dec && locs.every((_, i) => dec.has(i));
  });

  if (allResolved && group.titles.length > 0) {
    const done = document.createElement('div');
    done.className = 'dt-closure';
    done.textContent = `✓ ${group.name} — all duplicates resolved`;
    el.appendChild(done);
  }

  for (const title of group.titles) {
    el.appendChild(await buildTitleCard(title));
  }
}

async function buildTitleCard(title) {
  const locs = title.locationEntries || [];
  const card = document.createElement('div');
  card.className = 'dt-card';

  // Title header
  const header = document.createElement('div');
  header.className = 'dt-card-header';

  const coverUrl = title.coverUrl || `/covers/${encodeURIComponent(title.label || '')}/${encodeURIComponent(title.code)}.jpg`;
  header.innerHTML = `
    <img class="dt-cover" src="${esc(coverUrl)}" alt="" onerror="this.style.display='none'">
    <div class="dt-card-title">
      <span class="dt-code">${esc(title.code)}</span>
      ${title.titleEnglish ? `<span class="dt-title-en">${esc(title.titleEnglish)}</span>` : ''}
      <span class="dt-loc-count">${locs.length} locations</span>
    </div>
  `;
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
  const parts   = (loc.nasPath || '').split('/');
  const pathBase = parts.slice(0, 4).join('/');
  const pathRest = parts.slice(4).join('/');
  const pathEl   = document.createElement('div');
  pathEl.className = 'dt-cell-path';
  pathEl.innerHTML = `<span class="dt-path-base">${esc(pathBase)}</span>`
                   + (pathRest ? `<span class="dt-path-rest">/${esc(pathRest)}</span>` : '');
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

  // KEEP
  const keepBtn = makeDecisionBtn('Keep', 'KEEP', current === 'KEEP');
  keepBtn.addEventListener('click', () => setDecision(title, locs, i, current === 'KEEP' ? null : 'KEEP'));
  actions.appendChild(keepBtn);

  // TRASH — disabled if it's the last non-trashed location
  const trashBtn = makeDecisionBtn('Trash', 'TRASH', current === 'TRASH');
  const wouldTrashLast = isLastNonTrashed(title, locs, i);
  trashBtn.disabled = wouldTrashLast && current !== 'TRASH';
  if (trashBtn.disabled) trashBtn.title = 'Cannot trash the last copy';
  trashBtn.addEventListener('click', () => setDecision(title, locs, i, current === 'TRASH' ? null : 'TRASH'));
  actions.appendChild(trashBtn);

  // VARIANT
  const variantBtn = makeDecisionBtn('Variant', 'VARIANT', current === 'VARIANT');
  variantBtn.addEventListener('click', () => setDecision(title, locs, i, current === 'VARIANT' ? null : 'VARIANT'));
  actions.appendChild(variantBtn);

  cell.appendChild(actions);
  return cell;
}

function makeDecisionBtn(label, decision, active) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.textContent = label;
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
  actressSelEl()?.addEventListener('change', () => {
    currentActressKey = actressSelEl().value;
    renderGroups();
  });
}
