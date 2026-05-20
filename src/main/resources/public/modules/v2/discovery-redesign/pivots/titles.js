/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/pivots/titles.js — Titles pivot.

   Port of legacy v2/discovery/titles.js adapted to mountX pattern:
   - Source chips: "All recent" + per-pool volume chips.
   - Paginated table with code/title/actress/volume/date/status cols.
   - Multi-select (click / Cmd / Shift / header-checkbox).
   - Row click → select + inspector shows title-peek content (B4).
   - Footer: "Enqueue N" action bar when N>0 selected.
   - Filter: label-prefix autocomplete + debounce.
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
 * Mount the Titles pivot into containerEl.
 *
 * @param {HTMLElement} containerEl
 * @param {{
 *   pivotState: object,             — state.titles sub-object
 *   inspectorHandle: object,        — { setTitle, setContent, showEmpty }
 *   initialPool?: string|null,
 *   initialFilter?: string|null,
 *   onUrlChange?: (params: object) => void,
 * }} opts
 * @returns {{
 *   load(): Promise<void>,
 *   destroy(): void,
 * }}
 */
export function mountTitles(containerEl, {
  pivotState,
  inspectorHandle,
  initialPool,
  initialFilter,
  onUrlChange,
}) {
  const doUrlChange = onUrlChange ?? (() => {});
  // Apply deep-link params.
  if (initialPool && !pivotState.poolVolumeId) {
    pivotState.source = 'pool';
    pivotState.poolVolumeId = initialPool;
  }
  if (initialFilter && !pivotState.filter) {
    pivotState.filter = initialFilter;
  }

  containerEl.innerHTML = `
    <div class="dr-titles-pivot">
      <div class="dr-titles-toolbar">
        <div class="dr-titles-chips" id="dr-titles-chips"></div>
        <div class="dr-titles-filter-wrap">
          <input type="text" class="dr-filter-input" id="dr-titles-filter-input"
                 placeholder="Filter by code, label…" value="${esc(pivotState.filter || '')}">
          <button type="button" class="dr-filter-clear" id="dr-titles-filter-clear"
                  style="${pivotState.filter ? '' : 'display:none'}">✕</button>
          <div class="dr-filter-autocomplete" id="dr-titles-filter-autocomplete"></div>
        </div>
      </div>
      <div class="dr-titles-pager" id="dr-titles-pager-top"></div>
      <div class="dr-titles-table-wrap" id="dr-titles-table-wrap" style="display:none">
        <table class="dr-titles-table">
          <thead>
            <tr>
              <th class="dr-titles-cb-col"><input type="checkbox" id="dr-titles-select-all" title="Select all visible"></th>
              <th>Code</th>
              <th>Title</th>
              <th>Actress</th>
              <th>Volume</th>
              <th>Date</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody id="dr-titles-table-body"></tbody>
        </table>
      </div>
      <div class="dr-titles-empty" id="dr-titles-empty">No titles to display.</div>
      <div class="dr-titles-pager" id="dr-titles-pager-bottom"></div>
      <div class="dr-titles-footer" id="dr-titles-footer" style="display:none">
        <span id="dr-titles-footer-count"></span>
        <button type="button" class="dr-btn dr-btn-primary" id="dr-titles-enqueue-btn">Enqueue 0</button>
        <button type="button" class="dr-btn" id="dr-titles-clear-btn">Clear</button>
      </div>
    </div>
  `;

  const chipsEl      = containerEl.querySelector('#dr-titles-chips');
  const filterInput  = containerEl.querySelector('#dr-titles-filter-input');
  const filterClear  = containerEl.querySelector('#dr-titles-filter-clear');
  const filterAuto   = containerEl.querySelector('#dr-titles-filter-autocomplete');
  const pagerTopEl   = containerEl.querySelector('#dr-titles-pager-top');
  const pagerBotEl   = containerEl.querySelector('#dr-titles-pager-bottom');
  const tableWrap    = containerEl.querySelector('#dr-titles-table-wrap');
  const tableBody    = containerEl.querySelector('#dr-titles-table-body');
  const emptyEl      = containerEl.querySelector('#dr-titles-empty');
  const footerEl     = containerEl.querySelector('#dr-titles-footer');
  const footerCntEl  = containerEl.querySelector('#dr-titles-footer-count');
  const enqueueBtn   = containerEl.querySelector('#dr-titles-enqueue-btn');
  const clearBtn     = containerEl.querySelector('#dr-titles-clear-btn');
  const selectAllCb  = containerEl.querySelector('#dr-titles-select-all');

  // ── Selection ─────────────────────────────────────────────────────

  let _lastClickedIdx = -1;

  function updateSelectAll() {
    if (!selectAllCb) return;
    const t = pivotState;
    const enabled = t.rows.filter(r => r.queueStatus !== 'pending' && r.queueStatus !== 'in_flight');
    const selectedOnPage = enabled.filter(r => t.selected.has(r.titleId)).length;
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

  // ── Source chips ──────────────────────────────────────────────────

  function renderChips() {
    const t = pivotState;
    const chips = [
      `<button type="button" class="dr-source-chip${t.source === 'recent' ? ' selected' : ''}" data-chip="recent">All recent</button>`,
    ];
    for (const p of t.pools) {
      const sel   = t.source === 'pool' && t.poolVolumeId === p.volumeId;
      const empty = (p.unenrichedCount || 0) === 0;
      chips.push(
        `<button type="button" class="dr-source-chip${sel ? ' selected' : ''}${empty ? ' empty' : ''}"` +
        ` data-chip="pool" data-volume-id="${esc(p.volumeId)}"${empty ? ' disabled' : ''}>` +
          `Pool: ${esc(p.volumeId)}<span class="dr-source-chip-count">${p.unenrichedCount}</span>` +
        `</button>`
      );
    }
    chipsEl.innerHTML = chips.join('');
  }

  chipsEl.addEventListener('click', async e => {
    const btn = e.target.closest('[data-chip]');
    if (!btn || btn.disabled) return;
    const t = pivotState;
    if (btn.dataset.chip === 'recent') {
      t.source = 'recent'; t.poolVolumeId = null;
      doUrlChange({ pool: null });
    } else {
      t.source = 'pool'; t.poolVolumeId = btn.dataset.volumeId;
      doUrlChange({ pool: btn.dataset.volumeId });
    }
    t.page = 0; t.selected.clear();
    renderChips();
    await loadPage();
    renderFooter();
  });

  // ── Table render ──────────────────────────────────────────────────

  function renderTable() {
    const t = pivotState;
    if (t.rows.length === 0) {
      emptyEl.style.display = ''; tableWrap.style.display = 'none';
      return;
    }
    emptyEl.style.display = 'none'; tableWrap.style.display = '';

    tableBody.innerHTML = t.rows.map((r, idx) => {
      const blocked = r.queueStatus === 'pending' || r.queueStatus === 'in_flight';
      const checked = t.selected.has(r.titleId);
      const cb = blocked
        ? '<span class="dr-titles-cb-blocked" aria-hidden="true">·</span>'
        : `<input type="checkbox" class="dr-titles-cb" data-title-id="${r.titleId}" data-idx="${idx}" ${checked ? 'checked' : ''}>`;
      const codeCell = `<span class="dr-titles-code dr-row-select" data-idx="${idx}" data-title-id="${r.titleId}">${esc(r.code)}</span>`;
      const actressCell = r.actress
        ? `<span class="dr-titles-actress">
             <span class="dr-elig-dot${r.actress.eligibility === 'eligible' ? ' yes' : ' no'}"
                   title="${r.actress.eligibility === 'eligible' ? 'Will chain a profile fetch' : 'Title-only fetch'}"></span>
             ${esc(r.actress.name)}
           </span>`
        : '<span class="dr-muted">—</span>';
      return `<tr>
        <td class="dr-titles-cb-col">${cb}</td>
        <td>${codeCell}</td>
        <td>${esc(r.titleEnglish || '')}</td>
        <td>${actressCell}</td>
        <td class="dr-titles-volume">${esc(r.volumeId || '')}</td>
        <td class="dr-titles-date">${fmtDate(r.addedDate)}</td>
        <td>${renderStatusBadge(r)}</td>
      </tr>`;
    }).join('');
    updateSelectAll();
  }

  // ── Row selection via click ───────────────────────────────────────

  tableBody.addEventListener('click', async e => {
    const rowEl = e.target.closest('.dr-row-select');
    if (!rowEl) return;
    const titleId = parseInt(rowEl.dataset.titleId, 10);
    const idx     = parseInt(rowEl.dataset.idx, 10);
    const t = pivotState;

    if (e.shiftKey && _lastClickedIdx >= 0) {
      const lo = Math.min(_lastClickedIdx, idx);
      const hi = Math.max(_lastClickedIdx, idx);
      for (let i = lo; i <= hi; i++) {
        const r = t.rows[i];
        if (r && r.queueStatus !== 'pending' && r.queueStatus !== 'in_flight') {
          t.selected.add(r.titleId);
        }
      }
      refreshBulkInspector();
    } else if (e.metaKey || e.ctrlKey) {
      if (t.selected.has(titleId)) t.selected.delete(titleId);
      else t.selected.add(titleId);
      _lastClickedIdx = idx;
      refreshBulkInspector();
    } else {
      t.selected.clear();
      t.selected.add(titleId);
      _lastClickedIdx = idx;
      // Show title peek in inspector + write URL.
      const row = t.rows[idx];
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
   * Re-derive eligibility rollup from currently-visible rows for selected IDs.
   * Only rows currently rendered are known; selected-but-paged-away rows
   * are counted as unknown eligibility.
   */
  function refreshBulkInspector() {
    const n = pivotState.selected.size;
    if (n === 0) { inspectorHandle.showEmpty('Select a title to view details.'); return; }
    if (n === 1) return; // single-select handled by showTitlePeek

    const selectedIds = pivotState.selected;
    const rows = pivotState.rows.filter(r => selectedIds.has(r.titleId));

    let eligible = 0, sentinel = 0, belowThreshold = 0, noActress = 0;
    for (const r of rows) {
      if (!r.actress) { noActress++; continue; }
      if (r.actress.eligibility === 'eligible')  { eligible++; }
      else if (r.actress.eligibility === 'sentinel') { sentinel++; }
      else { belowThreshold++; }
    }
    const unknown = n - rows.length; // selected but not on current page

    const statLines = [
      `<div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Will chain profile fetch</span><span class="dr-bulk-stat-value">${eligible}</span></div>`,
      sentinel > 0       ? `<div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Sentinel actress</span><span class="dr-bulk-stat-value">${sentinel}</span></div>` : '',
      belowThreshold > 0 ? `<div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Below threshold (no chain)</span><span class="dr-bulk-stat-value">${belowThreshold}</span></div>` : '',
      noActress > 0      ? `<div class="dr-bulk-stat"><span class="dr-bulk-stat-label">No primary actress</span><span class="dr-bulk-stat-value">${noActress}</span></div>` : '',
      unknown > 0        ? `<div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Selected (other pages)</span><span class="dr-bulk-stat-value">${unknown}</span></div>` : '',
    ].filter(Boolean).join('');

    inspectorHandle.setTitle(`${n} titles selected`);
    inspectorHandle.setContent(`
      <div class="dr-bulk-inspector">
        <div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Selected</span><span class="dr-bulk-stat-value">${n}</span></div>
        ${statLines}
        <div class="dr-bulk-actions">
          <button type="button" class="dr-btn dr-btn-primary" id="dr-titles-bulk-enqueue-btn">▶ Enqueue Selected</button>
        </div>
      </div>
    `);
    // Wire the inspector bulk enqueue button.
    const pageEl = containerEl.closest('.dr-page') ?? containerEl.parentElement;
    const bulkBtn = pageEl?.querySelector('#dr-titles-bulk-enqueue-btn');
    if (bulkBtn) bulkBtn.addEventListener('click', () => enqueueBtn.click());
  }

  // ── Checkbox column ───────────────────────────────────────────────

  tableBody.addEventListener('change', e => {
    const cb = e.target.closest('.dr-titles-cb');
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
      const t = pivotState;
      const enabledIds = t.rows
        .filter(r => r.queueStatus !== 'pending' && r.queueStatus !== 'in_flight')
        .map(r => r.titleId);
      if (selectAllCb.checked) enabledIds.forEach(id => t.selected.add(id));
      else                     enabledIds.forEach(id => t.selected.delete(id));
      renderTable();
      renderFooter();
      refreshBulkInspector();
    });
  }

  // ── Data loading ──────────────────────────────────────────────────

  async function loadPools() {
    try {
      const res = await fetch('/api/javdb/discovery/titles/pools');
      if (res.ok) {
        pivotState.pools = await res.json();
        renderChips();
      }
    } catch (_) { /* ignore */ }
  }

  async function loadPage() {
    pivotState.loading = true;
    const t = pivotState;
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
        t.rows = []; t.hasMore = false; t.totalPages = 0;
      } else {
        const page = await res.json();
        t.rows = Array.isArray(page.rows) ? page.rows : [];
        t.hasMore = !!page.hasMore;
        t.totalPages = page.totalPages || 0;
      }
    } catch (_) {
      t.rows = []; t.hasMore = false; t.totalPages = 0;
    }
    pivotState.loading = false;
    renderTable();
    renderPagerInto(pagerTopEl, pivotState, 'titles-top');
    renderPagerInto(pagerBotEl, pivotState, 'titles-bot');
    renderFooter();
  }

  async function load() {
    await loadPools();
    await loadPage();
  }

  // ── Pager ─────────────────────────────────────────────────────────

  async function jumpToPage(newPage) {
    pivotState.page = newPage;
    await loadPage();
  }

  attachPagerHandlers(pagerTopEl, 'titles-top', () => pivotState, jumpToPage);
  attachPagerHandlers(pagerBotEl, 'titles-bot', () => pivotState, jumpToPage);

  // ── Footer actions ────────────────────────────────────────────────

  clearBtn.addEventListener('click', () => {
    pivotState.selected.clear();
    renderTable();
    renderFooter();
  });

  enqueueBtn.addEventListener('click', async () => {
    const t = pivotState;
    const ids = Array.from(t.selected);
    if (ids.length === 0) return;
    enqueueBtn.disabled = true;
    try {
      const res = await fetch('/api/javdb/discovery/titles/enqueue', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ source: t.source, titleIds: ids }),
      });
      if (!res.ok) { enqueueBtn.disabled = false; return; }
      t.selected.clear();
      await loadPools();
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
      renderChips();
    }
  );

  // ── Public API ────────────────────────────────────────────────────

  return {
    load,
    destroy() { containerEl.innerHTML = ''; },
  };
}
