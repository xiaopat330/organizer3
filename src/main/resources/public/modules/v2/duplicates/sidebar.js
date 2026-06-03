/* ─────────────────────────────────────────────────────────────────────
   duplicates/sidebar.js — headline stats, alpha bar, sort bar, actress list.
   Mirrors legacy utilities-duplicate-triage.js sidebar/alpha/sort rendering.
   ───────────────────────────────────────────────────────────────────── */

import * as taskCenter from '../../task-center.js';
import {
  countCleaned, pendingTrashCount, actressTrashCount,
  groupResolutionState, filteredGroups, occupiedLetters,
} from './decision.js';
import { runExecuteTask } from './execute.js';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;',
  }[c]));
}

function makePieSvg(pct) {
  const r = 4.5, cx = 6, cy = 6;
  const angle = 2 * Math.PI * pct;
  const ex    = cx + r * Math.sin(angle);
  const ey    = cy - r * Math.cos(angle);
  const large = pct > 0.5 ? 1 : 0;
  return `<svg class="dup-actress-state dup-actress-state-partial" viewBox="0 0 12 12" title="${Math.round(pct * 100)}% resolved">
    <circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="currentColor" stroke-width="1.5" opacity="0.3"/>
    <path d="M${cx},${cy} L${cx},${cy - r} A${r},${r} 0 ${large},1 ${ex.toFixed(2)},${ey.toFixed(2)} Z" fill="currentColor"/>
  </svg>`;
}

// ── Headline stats + Execute-all ──────────────────────────────────────

export function renderHeadline(state, headlineEl) {
  const { allDuplicates, decisions } = state;
  const total   = allDuplicates.length;
  const cleaned = countCleaned(allDuplicates, decisions);
  const queued  = pendingTrashCount(decisions);
  const stats   = `${total} found · ${cleaned} cleaned · ${total - cleaned} remaining`;
  const running = taskCenter.isRunning();

  if (queued > 0) {
    headlineEl.innerHTML = `
      <span class="dup-headline-stats">${esc(stats)}</span>
      <span class="dup-queue-badge">${queued} action${queued !== 1 ? 's' : ''} queued</span>
      <button class="dup-exec-all-btn btn" type="button"
        ${running ? 'disabled title="Another utility task is running"' : ''}>
        Execute all (${queued})
      </button>
    `;
    headlineEl.querySelector('.dup-exec-all-btn')?.addEventListener('click', () => runExecuteTask(null));
  } else {
    headlineEl.textContent = stats;
  }
}

// ── Alpha bar ─────────────────────────────────────────────────────────

export function renderAlphaBar(state, alphaBarEl, onChange) {
  alphaBarEl.innerHTML = '';
  const letters = occupiedLetters(state.actressGroups);
  const all = ['All', ...letters];
  for (const ltr of all) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = ltr;
    btn.className = 'dup-alpha-btn' + (ltr === state.currentLetterFilter ? ' active' : '');
    btn.addEventListener('click', () => {
      if (ltr === state.currentLetterFilter) return;
      state.currentLetterFilter = ltr;
      // If selected actress no longer visible, pick first
      const visible = filteredGroups(state);
      if (!visible.some(([k]) => k === state.currentActressKey)) {
        state.currentActressKey = visible[0]?.[0] || null;
      }
      onChange();
    });
    alphaBarEl.appendChild(btn);
  }
}

// ── Sort bar ──────────────────────────────────────────────────────────

export function renderSortBar(state, sortBarEl, onChange) {
  sortBarEl.innerHTML = '';
  const fields = [{ id: 'name', label: 'Name' }, { id: 'count', label: 'Count' }];
  for (const f of fields) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = f.label;
    btn.className = 'dup-sort-btn' + (f.id === state.sortField ? ' active' : '');
    btn.addEventListener('click', () => {
      if (state.sortField === f.id) return;
      state.sortField = f.id;
      onChange();
    });
    sortBarEl.appendChild(btn);
  }
  const dirBtn = document.createElement('button');
  dirBtn.type = 'button';
  dirBtn.className = 'dup-sort-btn dup-sort-dir';
  dirBtn.title = state.sortDir === 'asc' ? 'Ascending' : 'Descending';
  dirBtn.textContent = state.sortDir === 'asc' ? '↑' : '↓';
  dirBtn.addEventListener('click', () => {
    state.sortDir = state.sortDir === 'asc' ? 'desc' : 'asc';
    onChange();
  });
  sortBarEl.appendChild(dirBtn);
}

// ── Actress sidebar ───────────────────────────────────────────────────

export function renderActressSidebar(state, sidebarEl, onSelectActress, scrollToSelected = false) {
  sidebarEl.innerHTML = '';
  const { decisions } = state;

  for (const [key, group] of filteredGroups(state)) {
    const repTitle = [...group.titles].sort((a, b) => b.code.localeCompare(a.code))[0];
    const coverUrl = repTitle?.coverUrl
      || (repTitle ? `/covers/${encodeURIComponent(repTitle.label || '')}/${encodeURIComponent(repTitle.code)}.jpg` : null);

    const { state: resState, pct } = groupResolutionState(group, decisions);
    const stateIcon = resState === 'complete'
      ? `<svg class="dup-actress-state dup-actress-state-complete" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" title="All resolved"><polyline points="2,6 5,9 10,3"/></svg>`
      : resState === 'partial'
      ? makePieSvg(pct)
      : '';
    const progressLine = resState === 'partial'
      ? `<div class="dup-actress-progress">${Math.round(pct * 100)}% resolved</div>`
      : '';

    const trashN  = actressTrashCount(group, decisions);
    const running = taskCenter.isRunning();
    const execBtn = trashN > 0
      ? `<button class="dup-exec-actress-btn btn sm" type="button"
           ${running ? 'disabled title="Another task is running"' : ''}
           data-actress-key="${esc(key)}">
           Execute (${trashN})
         </button>`
      : '';

    const row = document.createElement('div');
    row.className = 'dup-actress-row' + (key === state.currentActressKey ? ' selected dup-actress-row-selected' : '');
    row.innerHTML = `
      <div class="dup-actress-portrait">
        <div class="dup-portrait-fallback">${esc(group.name.charAt(0).toUpperCase())}</div>
        ${coverUrl ? `<img src="${esc(coverUrl)}" alt="" onerror="this.style.display='none'">` : ''}
      </div>
      <div class="dup-actress-info">
        <div class="dup-actress-name">${esc(group.name)}</div>
        <div class="dup-actress-count">${group.titles.length} title${group.titles.length !== 1 ? 's' : ''}</div>
        ${progressLine}
        ${execBtn}
      </div>
      ${stateIcon}
    `;

    row.addEventListener('click', () => {
      state.currentActressKey = key;
      onSelectActress();
    });
    row.querySelector('.dup-exec-actress-btn')?.addEventListener('click', (e) => {
      e.stopPropagation();
      runExecuteTask(e.currentTarget.dataset.actressKey);
    });
    sidebarEl.appendChild(row);
  }

  if (scrollToSelected) {
    sidebarEl.querySelector('.dup-actress-row.selected')?.scrollIntoView({ block: 'nearest' });
  }
}
