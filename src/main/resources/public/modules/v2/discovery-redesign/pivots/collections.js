/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/pivots/collections.js — Collections pivot.

   Port of legacy v2/discovery/collections.js adapted to mountX pattern:
   - Multi-cast titles (source: 'collection') from the staged-titles API.
   - Cast eligibility chips per row.
   - Row click → select + inspector shows title-peek content.
   - Multi-select + "Enqueue N" footer action.
   ───────────────────────────────────────────────────────────────────── */

import { esc } from '../../../utils.js';
import {
  fmtDate,
  attachFilterHandlers,
  attachPagerHandlers,
  renderPagerInto,
  showCoverLightbox,
  fetchTitlePeek,
  buildTitlePeekHtml,
} from './shared.js';

// ── Cast chips ────────────────────────────────────────────────────────────

function renderCastChips(cast) {
  if (!cast || !cast.length) return '<span class="dr-muted">—</span>';
  return `<span class="dr-cast-strip">${cast.map(a => {
    const cls  = a.eligibility === 'eligible' ? 'dr-cast-chip-elig'
               : a.eligibility === 'sentinel' ? 'dr-cast-chip-sentinel'
               :                                'dr-cast-chip-below';
    const icon = a.eligibility === 'eligible' ? '✓'
               : a.eligibility === 'sentinel' ? '✗'
               :                                '◌';
    const tip  = a.eligibility === 'eligible' ? 'Will chain a profile fetch'
               : a.eligibility === 'sentinel' ? 'Sentinel actress (no chain)'
               :                                'Below threshold (no chain)';
    return `<span class="dr-cast-chip ${cls}" title="${esc(tip)}">` +
           `<span class="dr-cast-chip-icon">${icon}</span>${esc(a.name)}</span>`;
  }).join('')}</span>`;
}

// ── Status badge ──────────────────────────────────────────────────────────

function renderStatusBadge(r) {
  if (r.queueStatus === 'in_flight') return '<span class="dr-titles-status dr-titles-status-inflight">in flight</span>';
  if (r.queueStatus === 'pending')   return '<span class="dr-titles-status dr-titles-status-pending">pending</span>';
  if (r.queueStatus === 'failed')    return '<span class="dr-titles-status dr-titles-status-failed">failed</span>';
  if (r.stagingStatus === 'slug_only') return '<span class="dr-titles-status dr-titles-status-pending">slug only</span>';
  return '<span class="dr-muted">—</span>';
}

// ── Mount ─────────────────────────────────────────────────────────────────

/**
 * Mount the Collections pivot into containerEl.
 *
 * @param {HTMLElement} containerEl
 * @param {{
 *   pivotState: object,    — state.collections sub-object
 *   inspectorHandle: object,
 *   initialFilter?: string|null,
 *   onUrlChange?: (params: object) => void,
 * }} opts
 * @returns {{ load(): Promise<void>, destroy(): void }}
 */
export function mountCollections(containerEl, {
  pivotState,
  inspectorHandle,
  initialFilter,
  onUrlChange,
}) {
  const doUrlChange = onUrlChange ?? (() => {});

  if (initialFilter && !pivotState.filter) {
    pivotState.filter = initialFilter;
  }

  containerEl.innerHTML = `
    <div class="dr-titles-pivot">
      <div class="dr-titles-toolbar">
        <div class="dr-titles-filter-wrap">
          <input type="text" class="dr-filter-input" id="dr-coll-filter-input"
                 placeholder="Filter by code, label…" value="${esc(pivotState.filter || '')}">
          <button type="button" class="dr-filter-clear" id="dr-coll-filter-clear"
                  style="${pivotState.filter ? '' : 'display:none'}">✕</button>
          <div class="dr-filter-autocomplete" id="dr-coll-filter-auto"></div>
        </div>
      </div>
      <div class="dr-titles-pager" id="dr-coll-pager-top"></div>
      <div class="dr-titles-table-wrap" id="dr-coll-table-wrap" style="display:none">
        <table class="dr-titles-table">
          <thead>
            <tr>
              <th class="dr-titles-cb-col"><input type="checkbox" id="dr-coll-select-all" title="Select all visible"></th>
              <th>Code</th>
              <th>Title</th>
              <th>Cast</th>
              <th>Volume</th>
              <th>Date</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody id="dr-coll-table-body"></tbody>
        </table>
      </div>
      <div class="dr-titles-empty" id="dr-coll-empty">No collection titles to display.</div>
      <div class="dr-titles-pager" id="dr-coll-pager-bottom"></div>
      <div class="dr-titles-footer" id="dr-coll-footer" style="display:none">
        <span id="dr-coll-footer-count"></span>
        <button type="button" class="dr-btn dr-btn-primary" id="dr-coll-enqueue-btn">Enqueue 0</button>
        <button type="button" class="dr-btn" id="dr-coll-clear-btn">Clear</button>
      </div>
    </div>
  `;

  const filterInput  = containerEl.querySelector('#dr-coll-filter-input');
  const filterClear  = containerEl.querySelector('#dr-coll-filter-clear');
  const filterAuto   = containerEl.querySelector('#dr-coll-filter-auto');
  const pagerTopEl   = containerEl.querySelector('#dr-coll-pager-top');
  const pagerBotEl   = containerEl.querySelector('#dr-coll-pager-bottom');
  const tableWrap    = containerEl.querySelector('#dr-coll-table-wrap');
  const tableBody    = containerEl.querySelector('#dr-coll-table-body');
  const emptyEl      = containerEl.querySelector('#dr-coll-empty');
  const footerEl     = containerEl.querySelector('#dr-coll-footer');
  const footerCntEl  = containerEl.querySelector('#dr-coll-footer-count');
  const enqueueBtn   = containerEl.querySelector('#dr-coll-enqueue-btn');
  const clearBtn     = containerEl.querySelector('#dr-coll-clear-btn');
  const selectAllCb  = containerEl.querySelector('#dr-coll-select-all');

  let _lastClickedIdx = -1;

  // ── Selection ─────────────────────────────────────────────────────

  function updateSelectAll() {
    if (!selectAllCb) return;
    const c = pivotState;
    const enabled = c.rows.filter(r => r.queueStatus !== 'pending' && r.queueStatus !== 'in_flight');
    const selectedOnPage = enabled.filter(r => c.selected.has(r.titleId)).length;
    if (enabled.length === 0 || selectedOnPage === 0) {
      selectAllCb.checked = false; selectAllCb.indeterminate = false;
    } else if (selectedOnPage === enabled.length) {
      selectAllCb.checked = true; selectAllCb.indeterminate = false;
    } else {
      selectAllCb.checked = false; selectAllCb.indeterminate = true;
    }
  }

  function renderFooter() {
    const n = pivotState.selected.size;
    if (n === 0) { footerEl.style.display = 'none'; return; }
    footerEl.style.display = '';
    footerCntEl.textContent = `${n} selected`;
    enqueueBtn.disabled = false;
    enqueueBtn.textContent = `Enqueue ${n}`;
  }

  // ── Table render ──────────────────────────────────────────────────

  function renderTable() {
    const c = pivotState;
    if (c.rows.length === 0) {
      emptyEl.style.display = ''; tableWrap.style.display = 'none';
      return;
    }
    emptyEl.style.display = 'none'; tableWrap.style.display = '';

    tableBody.innerHTML = c.rows.map((r, idx) => {
      const blocked = r.queueStatus === 'pending' || r.queueStatus === 'in_flight';
      const checked = c.selected.has(r.titleId);
      const cb = blocked
        ? '<span class="dr-titles-cb-blocked" aria-hidden="true">·</span>'
        : `<input type="checkbox" class="dr-coll-cb" data-title-id="${r.titleId}" data-idx="${idx}" ${checked ? 'checked' : ''}>`;
      const codeCell = `<span class="dr-titles-code dr-row-select" data-idx="${idx}" data-title-id="${r.titleId}">${esc(r.code)}</span>`;
      const castCell = renderCastChips(r.cast || []);
      return `<tr>
        <td class="dr-titles-cb-col">${cb}</td>
        <td>${codeCell}</td>
        <td>${esc(r.titleEnglish || '')}</td>
        <td>${castCell}</td>
        <td class="dr-titles-volume">${esc(r.volumeId || '')}</td>
        <td class="dr-titles-date">${fmtDate(r.addedDate)}</td>
        <td>${renderStatusBadge(r)}</td>
      </tr>`;
    }).join('');
    updateSelectAll();
  }

  // ── Row selection ─────────────────────────────────────────────────

  tableBody.addEventListener('click', async e => {
    const rowEl = e.target.closest('.dr-row-select');
    if (!rowEl) return;
    const titleId = parseInt(rowEl.dataset.titleId, 10);
    const idx     = parseInt(rowEl.dataset.idx, 10);
    const c = pivotState;

    if (e.shiftKey && _lastClickedIdx >= 0) {
      const lo = Math.min(_lastClickedIdx, idx);
      const hi = Math.max(_lastClickedIdx, idx);
      for (let i = lo; i <= hi; i++) {
        const r = c.rows[i];
        if (r && r.queueStatus !== 'pending' && r.queueStatus !== 'in_flight') {
          c.selected.add(r.titleId);
        }
      }
      refreshBulkInspector();
    } else if (e.metaKey || e.ctrlKey) {
      if (c.selected.has(titleId)) c.selected.delete(titleId);
      else c.selected.add(titleId);
      _lastClickedIdx = idx;
      refreshBulkInspector();
    } else {
      c.selected.clear();
      c.selected.add(titleId);
      _lastClickedIdx = idx;
      const row = c.rows[idx];
      doUrlChange({ code: row?.code ?? null });
      await showTitlePeek(row);
    }
    renderTable();
    renderFooter();
  });

  async function showTitlePeek(row) {
    if (!row) return;
    inspectorHandle.setTitle(row.code);
    inspectorHandle.setContent('<div class="dr-loading">Loading…</div>');
    const t = await fetchTitlePeek(row.code);
    inspectorHandle.setContent(buildTitlePeekHtml(t, row.code));
  }

  // ── Bulk inspector (N>1 selected) ─────────────────────────────────

  /**
   * Compute cast eligibility rollup across all selected rows.
   * Per-actress dedup: if same actress appears in multiple selected titles,
   * count her once (use the eligibility from the first occurrence).
   */
  function refreshBulkInspector() {
    const n = pivotState.selected.size;
    if (n === 0) { inspectorHandle.showEmpty('Select a title to view details.'); return; }
    if (n === 1) return;

    const selectedIds = pivotState.selected;
    const rows = pivotState.rows.filter(r => selectedIds.has(r.titleId));

    const seenActresses = new Map(); // actressName → eligibility
    for (const r of rows) {
      if (!r.cast || !r.cast.length) continue;
      for (const a of r.cast) {
        if (!seenActresses.has(a.name)) seenActresses.set(a.name, a.eligibility);
      }
    }

    let eligible = 0, sentinel = 0, below = 0;
    for (const [, elig] of seenActresses) {
      if (elig === 'eligible')  eligible++;
      else if (elig === 'sentinel') sentinel++;
      else below++;
    }
    const unknown = n - rows.length;

    const statLines = [
      `<div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Unique actresses seen</span><span class="dr-bulk-stat-value">${seenActresses.size}</span></div>`,
      `<div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Will chain profile fetch</span><span class="dr-bulk-stat-value">${eligible}</span></div>`,
      sentinel > 0 ? `<div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Sentinel</span><span class="dr-bulk-stat-value">${sentinel}</span></div>` : '',
      below > 0    ? `<div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Below threshold</span><span class="dr-bulk-stat-value">${below}</span></div>` : '',
      unknown > 0  ? `<div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Selected (other pages)</span><span class="dr-bulk-stat-value">${unknown}</span></div>` : '',
    ].filter(Boolean).join('');

    inspectorHandle.setTitle(`${n} collection titles selected`);
    inspectorHandle.setContent(`
      <div class="dr-bulk-inspector">
        <div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Selected</span><span class="dr-bulk-stat-value">${n}</span></div>
        ${statLines}
        <div class="dr-bulk-actions">
          <button type="button" class="dr-btn dr-btn-primary" id="dr-coll-bulk-enqueue-btn">▶ Enqueue Selected</button>
        </div>
      </div>
    `);
    const pageEl  = containerEl.closest('.dr-page') ?? containerEl.parentElement;
    const bulkBtn = pageEl?.querySelector('#dr-coll-bulk-enqueue-btn');
    if (bulkBtn) bulkBtn.addEventListener('click', () => enqueueBtn.click());
  }

  tableBody.addEventListener('change', e => {
    const cb = e.target.closest('.dr-coll-cb');
    if (!cb) return;
    const id = parseInt(cb.dataset.titleId, 10);
    if (cb.checked) pivotState.selected.add(id);
    else            pivotState.selected.delete(id);
    renderFooter();
    updateSelectAll();
    refreshBulkInspector();
  });

  if (selectAllCb) {
    selectAllCb.addEventListener('click', () => {
      const c = pivotState;
      const enabledIds = c.rows
        .filter(r => r.queueStatus !== 'pending' && r.queueStatus !== 'in_flight')
        .map(r => r.titleId);
      if (selectAllCb.checked) enabledIds.forEach(id => c.selected.add(id));
      else                     enabledIds.forEach(id => c.selected.delete(id));
      renderTable();
      renderFooter();
      refreshBulkInspector();
    });
  }

  // ── Data loading ──────────────────────────────────────────────────

  async function loadPage() {
    pivotState.loading = true;
    const c = pivotState;
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
    pivotState.loading = false;
    renderTable();
    renderPagerInto(pagerTopEl, pivotState, 'coll-top');
    renderPagerInto(pagerBotEl, pivotState, 'coll-bot');
    renderFooter();
  }

  async function load() {
    await loadPage();
  }

  // ── Pager ─────────────────────────────────────────────────────────

  async function jumpToPage(newPage) {
    pivotState.page = newPage;
    await loadPage();
  }

  attachPagerHandlers(pagerTopEl, 'coll-top', () => pivotState, jumpToPage);
  attachPagerHandlers(pagerBotEl, 'coll-bot', () => pivotState, jumpToPage);

  // ── Footer actions ────────────────────────────────────────────────

  clearBtn.addEventListener('click', () => {
    pivotState.selected.clear();
    renderTable();
    renderFooter();
  });

  enqueueBtn.addEventListener('click', async () => {
    const c = pivotState;
    const ids = Array.from(c.selected);
    if (ids.length === 0) return;
    enqueueBtn.disabled = true;
    try {
      const res = await fetch('/api/javdb/discovery/titles/enqueue', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ source: 'collection', titleIds: ids }),
      });
      if (!res.ok) { enqueueBtn.disabled = false; return; }
      c.selected.clear();
      await loadPage();
    } catch (_) {
      enqueueBtn.disabled = false;
    }
  });

  // ── Filter ────────────────────────────────────────────────────────

  attachFilterHandlers(filterInput, filterClear, filterAuto,
    () => pivotState,
    async () => {
      doUrlChange({ filter: pivotState.filter || null });
      await loadPage();
    }
  );

  // ── Public API ────────────────────────────────────────────────────

  return {
    load,
    destroy() { containerEl.innerHTML = ''; },
  };
}
