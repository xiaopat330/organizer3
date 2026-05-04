// Titles subtab: title-driven enrichment surface (recent + per-pool browsing).

import { esc } from '../utils.js';
import {
  fmtDate,
  attachFilterHandlers,
  attachPagerHandlers,
  renderPagerInto,
  openTitlePeekModal,
} from './shared.js';

function renderTitlesStatusBadge(r) {
  if (r.queueStatus === 'in_flight') return '<span class="jd-titles-status jd-titles-status-inflight">in flight</span>';
  if (r.queueStatus === 'pending')   return '<span class="jd-titles-status jd-titles-status-pending">pending</span>';
  if (r.queueStatus === 'failed')    return '<span class="jd-titles-status jd-titles-status-failed">failed</span>';
  if (r.stagingStatus === 'slug_only') return '<span class="jd-titles-status jd-titles-status-pending">slug only</span>';
  return '<span style="color:#475569">—</span>';
}

export function initTitles(state) {
  const titlesChips      = document.getElementById('jd-titles-chips');
  const titlesEmpty      = document.getElementById('jd-titles-empty');
  const titlesTableWrap  = document.getElementById('jd-titles-table-wrap');
  const titlesTableBody  = document.getElementById('jd-titles-table-body');
  const titlesPager      = document.getElementById('jd-titles-pager');
  const titlesFooter     = document.getElementById('jd-titles-footer');
  const titlesFooterCnt  = document.getElementById('jd-titles-footer-count');
  const titlesEnqueueBtn = document.getElementById('jd-titles-enqueue-btn');
  const titlesClearBtn   = document.getElementById('jd-titles-clear-btn');
  const titlesFilterInput = document.getElementById('jd-titles-filter-input');
  const titlesFilterClearBtn = document.getElementById('jd-titles-filter-clear');
  const titlesFilterAutocomplete = document.getElementById('jd-titles-filter-autocomplete');

  function renderTitlesChips() {
    const t = state.titles;
    const recentSelected = t.source === 'recent';
    const chips = [
      `<button type="button" class="jd-titles-chip ${recentSelected ? 'selected' : ''}" data-titles-chip="recent">All recent</button>`,
    ];
    for (const p of t.pools) {
      const sel = t.source === 'pool' && t.poolVolumeId === p.volumeId;
      const empty = (p.unenrichedCount || 0) === 0;
      chips.push(
        `<button type="button" class="jd-titles-chip ${sel ? 'selected' : ''} ${empty ? 'empty' : ''}" ` +
        `data-titles-chip="pool" data-volume-id="${esc(p.volumeId)}" ${empty ? 'disabled' : ''}>` +
          `Pool: ${esc(p.volumeId)}` +
          `<span class="jd-titles-chip-count">${p.unenrichedCount}</span>` +
        `</button>`
      );
    }
    titlesChips.innerHTML = chips.join('');
  }

  function renderTitlesTable() {
    const t = state.titles;
    if (t.rows.length === 0) {
      titlesEmpty.style.display = '';
      titlesTableWrap.style.display = 'none';
      return;
    }
    titlesEmpty.style.display = 'none';
    titlesTableWrap.style.display = '';

    titlesTableBody.innerHTML = t.rows.map(r => {
      const blocked = r.queueStatus === 'pending' || r.queueStatus === 'in_flight';
      const checked = t.selected.has(r.titleId);
      const cb = blocked
        ? '<span class="jd-titles-cb-blocked" aria-hidden="true">·</span>'
        : `<input type="checkbox" class="jd-titles-cb" data-title-id="${r.titleId}" ${checked ? 'checked' : ''}>`;
      const codeCell = `<span class="jd-titles-code" data-title-id="${r.titleId}">${esc(r.code)}</span>`;
      const actressCell = r.actress
        ? `<span class="jd-titles-actress">` +
          `<span class="jd-titles-elig-dot ${r.actress.eligibility === 'eligible' ? 'jd-titles-elig-yes' : 'jd-titles-elig-no'}" ` +
                `title="${r.actress.eligibility === 'eligible' ? 'Will chain a profile fetch' : 'Title-only fetch (no profile chain)'}"></span>` +
          `<span>${esc(r.actress.name)}</span>` +
          `</span>`
        : '<span class="jd-titles-actress" style="color:#475569">—</span>';
      const statusCell = renderTitlesStatusBadge(r);
      return `<tr>
        <td class="jd-titles-cb-col">${cb}</td>
        <td>${codeCell}</td>
        <td>${esc(r.titleEnglish || '')}</td>
        <td>${actressCell}</td>
        <td class="jd-titles-volume">${esc(r.volumeId || '')}</td>
        <td class="jd-titles-date">${fmtDate(r.addedDate)}</td>
        <td>${statusCell}</td>
      </tr>`;
    }).join('');
  }

  function renderTitlesPager() {
    renderPagerInto(titlesPager, state.titles, 'titles');
  }

  function renderTitlesFooter() {
    const n = state.titles.selected.size;
    if (n === 0) {
      titlesFooter.style.display = 'none';
      return;
    }
    titlesFooter.style.display = '';
    titlesFooterCnt.textContent = `${n} selected`;
    titlesEnqueueBtn.disabled = false;
    titlesEnqueueBtn.textContent = `Enqueue ${n}`;
  }

  async function loadTitlesPools() {
    try {
      const res = await fetch('/api/javdb/discovery/titles/pools');
      if (!res.ok) return;
      state.titles.pools = await res.json();
      renderTitlesChips();
    } catch (_) { /* ignore */ }
  }

  async function loadTitlesPage() {
    state.titles.loading = true;
    const t = state.titles;
    const params = new URLSearchParams({
      source: t.source,
      page:   String(t.page),
      pageSize: String(t.pageSize),
    });
    if (t.source === 'pool' && t.poolVolumeId) params.set('volumeId', t.poolVolumeId);
    if (t.filter && t.filter.trim()) params.set('filter', t.filter.trim());
    try {
      const res = await fetch(`/api/javdb/discovery/titles?${params}`);
      if (!res.ok) {
        t.rows = [];
        t.hasMore = false;
        t.totalPages = 0;
      } else {
        const page = await res.json();
        t.rows = Array.isArray(page.rows) ? page.rows : [];
        t.hasMore = !!page.hasMore;
        t.totalPages = page.totalPages || 0;
      }
    } catch (_) {
      t.rows = [];
      t.hasMore = false;
      t.totalPages = 0;
    }
    state.titles.loading = false;
    renderTitlesTable();
    renderTitlesPager();
    renderTitlesFooter();
  }

  async function loadTitlesTab() {
    await loadTitlesPools();
    await loadTitlesPage();
  }

  // Event delegation: chip strip.
  titlesChips.addEventListener('click', async e => {
    const btn = e.target.closest('[data-titles-chip]');
    if (!btn || btn.disabled) return;
    const t = state.titles;
    if (btn.dataset.titlesChip === 'recent') {
      t.source = 'recent';
      t.poolVolumeId = null;
    } else {
      t.source = 'pool';
      t.poolVolumeId = btn.dataset.volumeId;
    }
    t.page = 0;
    t.selected.clear();
    renderTitlesChips();
    await loadTitlesPage();
  });

  // Event delegation: row checkboxes.
  titlesTableBody.addEventListener('change', e => {
    const cb = e.target.closest('.jd-titles-cb');
    if (!cb) return;
    const id = parseInt(cb.dataset.titleId, 10);
    if (cb.checked) state.titles.selected.add(id);
    else            state.titles.selected.delete(id);
    renderTitlesFooter();
  });

  // Event delegation: code click → open peek modal.
  titlesTableBody.addEventListener('click', async e => {
    const codeEl = e.target.closest('.jd-titles-code');
    if (!codeEl) return;
    const titleId = parseInt(codeEl.dataset.titleId, 10);
    const row = state.titles.rows.find(r => r.titleId === titleId);
    if (!row) return;
    await openTitlePeekModal(row.code);
  });

  // Pager handlers.
  attachPagerHandlers(titlesPager, 'titles', () => state.titles, async (newPage) => {
    state.titles.page = newPage;
    await loadTitlesPage();
  });

  // Clear.
  titlesClearBtn.addEventListener('click', () => {
    state.titles.selected.clear();
    renderTitlesTable();
    renderTitlesFooter();
  });

  // Enqueue selected.
  titlesEnqueueBtn.addEventListener('click', async () => {
    const t = state.titles;
    const ids = Array.from(t.selected);
    if (ids.length === 0) return;
    titlesEnqueueBtn.disabled = true;
    try {
      const res = await fetch('/api/javdb/discovery/titles/enqueue', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ source: t.source, titleIds: ids }),
      });
      if (!res.ok) {
        titlesEnqueueBtn.disabled = false;
        return;
      }
      t.selected.clear();
      await loadTitlesPools();
      await loadTitlesPage();
    } catch (_) {
      titlesEnqueueBtn.disabled = false;
    }
  });

  // Filter input.
  attachFilterHandlers(titlesFilterInput, titlesFilterClearBtn, titlesFilterAutocomplete,
      () => state.titles, async () => {
        await loadTitlesPage();
        renderTitlesChips();
      });

  return {
    loadTitlesTab,
  };
}
