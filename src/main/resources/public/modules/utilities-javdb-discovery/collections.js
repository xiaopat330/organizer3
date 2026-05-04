// Collections subtab: multi-cast titles browser.

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

function renderCastChips(cast) {
  if (!cast.length) return '<span style="color:#475569">—</span>';
  const chips = cast.map(a => {
    const cls = a.eligibility === 'eligible'        ? 'jd-cast-chip-elig'
              : a.eligibility === 'sentinel'        ? 'jd-cast-chip-sentinel'
              :                                       'jd-cast-chip-below';
    const icon = a.eligibility === 'eligible'  ? '✓'
               : a.eligibility === 'sentinel'  ? '✗'
               :                                 '◌';
    const tip = a.eligibility === 'eligible'  ? 'Will chain a profile fetch'
              : a.eligibility === 'sentinel'  ? 'Sentinel actress (no chain)'
              :                                 'Below threshold (no chain)';
    return `<span class="jd-cast-chip ${cls}" title="${esc(tip)}">` +
           `<span class="jd-cast-chip-icon">${icon}</span>${esc(a.name)}</span>`;
  });
  return `<span class="jd-cast-strip">${chips.join('')}</span>`;
}

export function initCollections(state) {
  const collectionsEmpty      = document.getElementById('jd-collections-empty');
  const collectionsTableWrap  = document.getElementById('jd-collections-table-wrap');
  const collectionsTableBody  = document.getElementById('jd-collections-table-body');
  const collectionsPager      = document.getElementById('jd-collections-pager');
  const collectionsFooter     = document.getElementById('jd-collections-footer');
  const collectionsFooterCnt  = document.getElementById('jd-collections-footer-count');
  const collectionsEnqueueBtn = document.getElementById('jd-collections-enqueue-btn');
  const collectionsClearBtn   = document.getElementById('jd-collections-clear-btn');
  const collectionsFilterInput    = document.getElementById('jd-collections-filter-input');
  const collectionsFilterClearBtn = document.getElementById('jd-collections-filter-clear');
  const collectionsFilterAutocomplete = document.getElementById('jd-collections-filter-autocomplete');

  function renderCollectionsTable() {
    const c = state.collections;
    if (c.rows.length === 0) {
      collectionsEmpty.style.display = '';
      collectionsTableWrap.style.display = 'none';
      return;
    }
    collectionsEmpty.style.display = 'none';
    collectionsTableWrap.style.display = '';

    collectionsTableBody.innerHTML = c.rows.map(r => {
      const blocked = r.queueStatus === 'pending' || r.queueStatus === 'in_flight';
      const checked = c.selected.has(r.titleId);
      const cb = blocked
        ? '<span class="jd-titles-cb-blocked" aria-hidden="true">·</span>'
        : `<input type="checkbox" class="jd-collections-cb" data-title-id="${r.titleId}" ${checked ? 'checked' : ''}>`;
      const codeCell = `<span class="jd-titles-code" data-title-id="${r.titleId}">${esc(r.code)}</span>`;
      const castCell = renderCastChips(r.cast || []);
      const statusCell = renderTitlesStatusBadge(r);
      return `<tr>
        <td class="jd-titles-cb-col">${cb}</td>
        <td>${codeCell}</td>
        <td>${esc(r.titleEnglish || '')}</td>
        <td>${castCell}</td>
        <td class="jd-titles-volume">${esc(r.volumeId || '')}</td>
        <td class="jd-titles-date">${fmtDate(r.addedDate)}</td>
        <td>${statusCell}</td>
      </tr>`;
    }).join('');
  }

  function renderCollectionsPager() {
    renderPagerInto(collectionsPager, state.collections, 'collections');
  }

  function renderCollectionsFooter() {
    const n = state.collections.selected.size;
    if (n === 0) {
      collectionsFooter.style.display = 'none';
      return;
    }
    collectionsFooter.style.display = '';
    collectionsFooterCnt.textContent = `${n} selected`;
    collectionsEnqueueBtn.disabled = false;
    collectionsEnqueueBtn.textContent = `Enqueue ${n}`;
  }

  async function loadCollectionsTab() {
    const c = state.collections;
    c.loading = true;
    const params = new URLSearchParams({
      source: 'collection',
      page:   String(c.page),
      pageSize: String(c.pageSize),
    });
    if (c.filter && c.filter.trim()) params.set('filter', c.filter.trim());
    try {
      const res = await fetch(`/api/javdb/discovery/titles?${params}`);
      if (!res.ok) {
        c.rows = []; c.hasMore = false; c.totalPages = 0;
      } else {
        const page = await res.json();
        c.rows = Array.isArray(page.rows) ? page.rows : [];
        c.hasMore = !!page.hasMore;
        c.totalPages = page.totalPages || 0;
      }
    } catch (_) {
      c.rows = []; c.hasMore = false; c.totalPages = 0;
    }
    c.loading = false;
    renderCollectionsTable();
    renderCollectionsPager();
    renderCollectionsFooter();
  }

  // Event delegation: row checkboxes.
  collectionsTableBody.addEventListener('change', e => {
    const cb = e.target.closest('.jd-collections-cb');
    if (!cb) return;
    const id = parseInt(cb.dataset.titleId, 10);
    if (cb.checked) state.collections.selected.add(id);
    else            state.collections.selected.delete(id);
    renderCollectionsFooter();
  });

  // Event delegation: code click → open title peek modal.
  collectionsTableBody.addEventListener('click', async e => {
    const codeEl = e.target.closest('.jd-titles-code');
    if (!codeEl) return;
    const titleId = parseInt(codeEl.dataset.titleId, 10);
    const row = state.collections.rows.find(r => r.titleId === titleId);
    if (!row) return;
    await openTitlePeekModal(row.code);
  });

  // Pager.
  attachPagerHandlers(collectionsPager, 'collections', () => state.collections, async (newPage) => {
    state.collections.page = newPage;
    await loadCollectionsTab();
  });

  // Clear.
  collectionsClearBtn.addEventListener('click', () => {
    state.collections.selected.clear();
    renderCollectionsTable();
    renderCollectionsFooter();
  });

  // Enqueue.
  collectionsEnqueueBtn.addEventListener('click', async () => {
    const c = state.collections;
    const ids = Array.from(c.selected);
    if (ids.length === 0) return;
    collectionsEnqueueBtn.disabled = true;
    try {
      const res = await fetch('/api/javdb/discovery/titles/enqueue', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ source: 'collection', titleIds: ids }),
      });
      if (!res.ok) {
        collectionsEnqueueBtn.disabled = false;
        return;
      }
      c.selected.clear();
      await loadCollectionsTab();
    } catch (_) {
      collectionsEnqueueBtn.disabled = false;
    }
  });

  // Filter input.
  attachFilterHandlers(collectionsFilterInput, collectionsFilterClearBtn, collectionsFilterAutocomplete,
      () => state.collections, async () => {
        await loadCollectionsTab();
      });

  return {
    loadCollectionsTab,
  };
}
