/* ─────────────────────────────────────────────────────────────────────
   duplicates/group-card.js — title card + location cell rendering.
   Mirrors legacy utilities-duplicate-triage.js buildTitleCard / buildLocCell.
   ───────────────────────────────────────────────────────────────────── */

import { rankLocations } from '../../duplicate-ranker.js';
import { openInspectModal } from './inspect-modal.js';
import { applyDecision, isLastNonTrashed } from './decision.js';

// ── Icons ─────────────────────────────────────────────────────────────

const ICON_VOL  = `<svg class="dup-path-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><rect x="1" y="2" width="10" height="8" rx="1"/><line x1="1" y1="7" x2="11" y2="7"/><circle cx="9" cy="9.2" r="0.7" fill="currentColor" stroke="none"/></svg>`;
const ICON_DIR  = `<svg class="dup-path-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"><path d="M1 9V4.5C1 4 1.4 3.5 2 3.5h3L6 5h4c.6 0 1 .4 1 1V9c0 .6-.4 1-1 1H2c-.6 0-1-.4-1-1z"/></svg>`;
const ICON_FILE = `<svg class="dup-path-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 1h5l3 3v7H2V1z"/><path d="M7 1v3h3"/></svg>`;
const ICON_INSPECT = `<svg class="dup-inspect-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"><circle cx="6" cy="6" r="4.5"/><polygon points="4.5,4 8.5,6 4.5,8" fill="currentColor" stroke="none"/></svg>`;

// ── Cover tooltip ─────────────────────────────────────────────────────

let _coverTip   = null;
let _coverTimer = null;

function showCoverTip(src, anchor) {
  clearTimeout(_coverTimer);
  _coverTimer = setTimeout(() => {
    if (!_coverTip) {
      _coverTip = document.createElement('div');
      _coverTip.className = 'dup-cover-tip';
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

// ── Helpers ───────────────────────────────────────────────────────────

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;',
  }[c]));
}

function fmtBytes(b) {
  if (b >= 1e9) return (b / 1e9).toFixed(1) + ' GB';
  if (b >= 1e6) return (b / 1e6).toFixed(0) + ' MB';
  return (b / 1e3).toFixed(0) + ' KB';
}

function videoTagPillsHtml(v) {
  const fn      = v.filename || '';
  const fnLower = fn.toLowerCase();
  const dot     = fn.lastIndexOf('.');
  const ext     = dot >= 0 ? fn.substring(dot + 1).toUpperCase() : null;
  const isHevc  = fnLower.includes('-h265');
  const is4k    = fnLower.includes('_4k') || fnLower.includes('-4k');
  let html = '';
  if (v.fileSize) html += `<span class="dup-chip">${fmtBytes(v.fileSize)}</span>`;
  if (ext)        html += `<span class="dup-chip dup-chip-ext">${esc(ext)}</span>`;
  if (isHevc)     html += `<span class="dup-chip dup-chip-hevc">HEVC</span>`;
  if (is4k)       html += `<span class="dup-chip dup-chip-4k">4K</span>`;
  return html;
}

async function fetchLocVideos(titleCode, locs) {
  return Promise.all(locs.map(async (loc) => {
    try {
      let url = `/api/titles/${encodeURIComponent(titleCode)}/videos?volumeId=${encodeURIComponent(loc.volumeId)}`;
      if (loc.locPath) url += `&locPath=${encodeURIComponent(loc.locPath)}`;
      const res = await fetch(url);
      return await res.json();
    } catch {
      return [];
    }
  }));
}

// ── Decision buttons ──────────────────────────────────────────────────

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
  btn.innerHTML = `<span class="dup-btn-icon">${ICONS[decision]}</span>${LABELS[decision]}`;
  btn.className = `dup-dec-btn dup-dec-btn-pill dup-dec-${decision.toLowerCase()}` + (active ? ' active' : '');
  return btn;
}

// ── Location cell ─────────────────────────────────────────────────────

function buildLocCell(state, title, locs, i, videos, suggestedIndex, onDecisionChange) {
  const loc     = locs[i];
  const dec     = state.decisions.get(title.code);
  const current = dec?.get(i) || null;

  const cell = document.createElement('div');
  cell.className = 'dup-cell';
  if (current) cell.classList.add(`dup-cell-${current.toLowerCase()}`);
  if (i === suggestedIndex) cell.classList.add('dup-cell-suggested');

  // Path parsing (vol / dir / filename)
  const fullPath    = loc.nasPath || '';
  const parts       = fullPath.split('/');
  const volPath     = parts.slice(0, 4).join('/');
  const relParts    = parts.slice(4);
  const titleFolder = relParts[relParts.length - 1] || '';
  const relParent   = relParts.slice(0, -1).join('/');

  function makeCopyRow(icon, text) {
    const row = document.createElement('div');
    row.className = 'dup-path-row dup-path-row-copy';
    row.title = 'Click to copy full path';
    row.innerHTML = `${icon}<span class="dup-path-rest">${esc(text)}</span>`;
    row.addEventListener('click', () => {
      navigator.clipboard.writeText(fullPath).then(() => {
        row.classList.add('dup-path-copied');
        setTimeout(() => row.classList.remove('dup-path-copied'), 1400);
      });
    });
    return row;
  }

  const pathEl = document.createElement('div');
  pathEl.className = 'dup-cell-path';
  const volRow = document.createElement('div');
  volRow.className = 'dup-path-row';
  volRow.innerHTML = `${ICON_VOL}<span class="dup-path-base">${esc(volPath)}</span>`;
  pathEl.appendChild(volRow);
  if (relParent)   pathEl.appendChild(makeCopyRow(ICON_DIR,  relParent));
  if (titleFolder) pathEl.appendChild(makeCopyRow(ICON_FILE, titleFolder));
  cell.appendChild(pathEl);

  // Video chip pills
  if (videos.length > 0) {
    const vsum = document.createElement('div');
    vsum.className = 'dup-cell-vsum';
    vsum.innerHTML = videos.map(v => videoTagPillsHtml(v)).join('');
    cell.appendChild(vsum);
  }

  // Suggested-keep badge
  if (i === suggestedIndex) {
    const badge = document.createElement('span');
    badge.className = 'dup-suggested-badge';
    badge.textContent = 'Suggested keep';
    cell.appendChild(badge);
  }

  // Inspect button (only if there are videos)
  if (videos.length > 0) {
    const inspectBtn = document.createElement('button');
    inspectBtn.type = 'button';
    inspectBtn.className = 'dup-inspect-btn';
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
  actions.className = 'dup-cell-actions';

  const keepBtn = makeDecisionBtn('KEEP', current === 'KEEP');
  keepBtn.addEventListener('click', () => {
    applyDecision(state, title, locs, i, current === 'KEEP' ? null : 'KEEP');
    onDecisionChange();
  });
  actions.appendChild(keepBtn);

  const trashBtn = makeDecisionBtn('TRASH', current === 'TRASH');
  const wouldTrashLast = isLastNonTrashed(title, locs, i, state.decisions);
  trashBtn.disabled = wouldTrashLast && current !== 'TRASH';
  if (trashBtn.disabled) trashBtn.title = 'Cannot trash the last copy';
  trashBtn.addEventListener('click', () => {
    applyDecision(state, title, locs, i, current === 'TRASH' ? null : 'TRASH');
    onDecisionChange();
  });
  actions.appendChild(trashBtn);

  const variantBtn = makeDecisionBtn('VARIANT', current === 'VARIANT');
  variantBtn.addEventListener('click', () => {
    applyDecision(state, title, locs, i, current === 'VARIANT' ? null : 'VARIANT');
    onDecisionChange();
  });
  actions.appendChild(variantBtn);

  cell.appendChild(actions);
  return cell;
}

// ── Title card ────────────────────────────────────────────────────────

export async function buildTitleCard(state, title, onDecisionChange) {
  const locs = title.locationEntries || [];
  const card = document.createElement('div');
  card.className = 'dup-card';

  // Title header with cover
  const header = document.createElement('div');
  header.className = 'dup-card-header';

  const coverUrl = title.coverUrl || `/covers/${encodeURIComponent(title.label || '')}/${encodeURIComponent(title.code)}.jpg`;
  const coverImg = document.createElement('img');
  coverImg.className = 'dup-cover';
  coverImg.src = coverUrl;
  coverImg.alt = '';
  coverImg.onerror = () => { coverImg.style.display = 'none'; };
  coverImg.addEventListener('mouseenter', () => showCoverTip(coverUrl, coverImg));
  coverImg.addEventListener('mouseleave', hideCoverTip);
  header.appendChild(coverImg);

  const titleInfo = document.createElement('div');
  titleInfo.className = 'dup-card-title-info';
  titleInfo.innerHTML = `
    <span class="dup-code">${esc(title.code)}</span>
    ${title.titleEnglish ? `<span class="dup-title-en">${esc(title.titleEnglish)}</span>` : ''}
    <span class="dup-loc-count">${locs.length} locations</span>
  `;
  header.appendChild(titleInfo);
  card.appendChild(header);

  // Fetch videos per location
  const locVideos = await fetchLocVideos(title.code, locs);

  // Rank locations
  const locData = locs.map((loc, i) => ({ ...loc, videos: locVideos[i] || [] }));
  const rank    = rankLocations(locData);

  if (rank.rationale) {
    const rat = document.createElement('div');
    rat.className = 'dup-rationale' + (rank.suggestedIndex === null ? ' dup-rationale-identical dup-decision-hint' : '');
    rat.textContent = rank.rationale;
    card.appendChild(rat);
  }

  // Location grid
  const grid = document.createElement('div');
  grid.className = 'dup-cell-grid';
  for (let i = 0; i < locs.length; i++) {
    grid.appendChild(buildLocCell(state, title, locs, i, locVideos[i] || [], rank.suggestedIndex, onDecisionChange));
  }
  card.appendChild(grid);

  return card;
}

// ── Group panel (all titles for selected actress) ─────────────────────

export async function renderGroups(state, groupsEl, onDecisionChange) {
  groupsEl.innerHTML = '';

  if (!state.currentActressKey) return;
  const group = state.actressGroups.get(state.currentActressKey);
  if (!group || group.titles.length === 0) return;

  // Closure badge — all resolved
  const allResolved = group.titles.every(t => {
    const dec  = state.decisions.get(t.code);
    const locs = t.locationEntries || [];
    return dec && locs.every((_, i) => dec.has(i));
  });
  if (allResolved) {
    const done = document.createElement('div');
    done.className = 'dup-closure';
    done.textContent = `✓ ${group.name} — all duplicates resolved`;
    groupsEl.appendChild(done);
  }

  const total = group.titles.length;
  const prog  = document.createElement('div');
  prog.className = 'dup-load-progress';
  groupsEl.appendChild(prog);

  for (let n = 0; n < total; n++) {
    prog.innerHTML = `<span class="dup-spinner"></span><span>Loading ${n + 1} / ${total}…</span>`;
    groupsEl.appendChild(await buildTitleCard(state, group.titles[n], onDecisionChange));
  }
  prog.remove();
}
