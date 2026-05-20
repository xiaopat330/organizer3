/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/pivots/actresses.js — Actresses pivot.

   Port of legacy v2/discovery/enrich.js + v2/discovery/enrich-panels.js
   adapted to the mountX(containerEl, opts) pattern:
   - No document.getElementById; all DOM scoped to containerEl.
   - Selection drives inspector content (0 = hint, 1 = detail, N>1 = bulk).
   - Inspector sub-tabs: titles | profile | conflicts | errors.
   - Error picker folded into inspector as inline drawer (B3).
   - Enrichment detail folded into inspector (B4) — no modal.
   - Cover image → showCoverLightbox() (the only modal, B5).
   ───────────────────────────────────────────────────────────────────── */

import { esc } from '../../../utils.js';
import {
  fmtDate, parseCast, showCoverLightbox,
  fetchTitlePeek, buildTitlePeekHtml,
} from './shared.js';

// ── Constants ─────────────────────────────────────────────────────────────

const BUCKET_THRESHOLD = 30;

const ERROR_REASON_LABELS = {
  ambiguous:                 'Ambiguous match',
  no_match:                  'No match on JavDB',
  fetch_failed:              'Fetch failed',
  cast_anomaly:              'Cast anomaly',
  not_found:                 'Not found',
  sentinel_actress:          'Sentinel actress',
  no_match_in_filmography:   'Not in filmography',
  no_slug:                   'No slug available',
  unknown_job_type:          'Unknown job type',
  title_not_in_db:           'Title not in DB',
  slug_conflict:             'Slug conflict',
};

const QUEUE_FAIL_META = {
  ambiguous:               { label: 'ambiguous',           icon: '⚠', cls: 'dr-qi-failed-resolvable' },
  cast_anomaly:            { label: 'cast anomaly',        icon: '⚠', cls: 'dr-qi-failed-resolvable' },
  sentinel_actress:        { label: 'needs actress',       icon: '⚠', cls: 'dr-qi-failed-resolvable' },
  not_found:               { label: 'not on javdb',        icon: '⊘', cls: 'dr-qi-failed-deadend'    },
  no_match_in_filmography: { label: 'not in filmography',  icon: '⊘', cls: 'dr-qi-failed-deadend'    },
  title_not_in_db:         { label: 'orphaned job',        icon: '⊘', cls: 'dr-qi-failed-deadend'    },
  unknown_job_type:        { label: 'internal error',      icon: '⊘', cls: 'dr-qi-failed-deadend'    },
  fetch_failed:            { label: 'fetch failed',        icon: '↻', cls: 'dr-qi-failed'            },
  no_slug:                 { label: 'no slug',             icon: '↻', cls: 'dr-qi-failed'            },
  slug_conflict:           { label: 'slug conflict',       icon: '⚠', cls: 'dr-qi-failed-resolvable' },
};

function errorReasonLabel(raw) {
  return ERROR_REASON_LABELS[raw] || raw || '(unknown)';
}

// ── Alpha-bucket helpers ───────────────────────────────────────────────────

function splitLetter(letter, sortedNames) {
  const bySecond = new Map();
  for (const name of sortedNames) {
    const s = (name.charAt(1) || ' ').toLowerCase();
    bySecond.set(s, (bySecond.get(s) || 0) + 1);
  }
  const seconds = [...bySecond.keys()].sort();
  const buckets = [];
  let rangeStart = seconds[0];
  let count = 0;
  for (let i = 0; i < seconds.length; i++) {
    const s = seconds[i];
    const c = bySecond.get(s);
    if (count > 0 && count + c > BUCKET_THRESHOLD) {
      buckets.push(makeBucket(letter, rangeStart, seconds[i - 1]));
      rangeStart = s;
      count = c;
    } else {
      count += c;
    }
  }
  buckets.push(makeBucket(letter, rangeStart, seconds[seconds.length - 1]));
  return buckets;
}

function makeBucket(letter, fromSecond, toSecond) {
  const lo = fromSecond.toUpperCase();
  const hi = toSecond.toUpperCase();
  const label = lo === hi ? `${letter}${lo}` : `${letter}${lo}–${letter}${hi}`;
  const key = `${letter}:${fromSecond}-${toSecond}`;
  return {
    label, key,
    test: n => {
      if (n.charAt(0).toUpperCase() !== letter) return false;
      const s = (n.charAt(1) || ' ').toLowerCase();
      return s >= fromSecond && s <= toSecond;
    },
  };
}

function computeAlphaBuckets(actresses) {
  const byLetter = new Map();
  let hasNonAlpha = false;
  for (const a of actresses) {
    const name = a.canonicalName || '';
    const ch = name.charAt(0).toUpperCase();
    if (ch >= 'A' && ch <= 'Z') {
      if (!byLetter.has(ch)) byLetter.set(ch, []);
      byLetter.get(ch).push(name);
    } else {
      hasNonAlpha = true;
    }
  }
  const buckets = [{ label: 'All', key: 'All', test: () => true }];
  for (const [letter, names] of [...byLetter.entries()].sort()) {
    names.sort();
    if (names.length <= BUCKET_THRESHOLD) {
      buckets.push({ label: letter, key: letter, test: n => n.charAt(0).toUpperCase() === letter });
    } else {
      buckets.push(...splitLetter(letter, names));
    }
  }
  if (hasNonAlpha) {
    buckets.push({
      label: '#', key: '#',
      test: n => { const ch = n.charAt(0).toUpperCase(); return ch < 'A' || ch > 'Z'; },
    });
  }
  return buckets;
}

function computeTier(totalTitles) {
  if (totalTitles >= 100) return 'goddess';
  if (totalTitles >= 50)  return 'superstar';
  if (totalTitles >= 20)  return 'popular';
  return null;
}

// ── Main mount function ───────────────────────────────────────────────────

/**
 * Mount the Actresses pivot into containerEl.
 *
 * @param {HTMLElement} containerEl
 * @param {{
 *   pivotState: object,        — state.actresses sub-object
 *   selection: Set<number>,    — state.selection (actress IDs)
 *   onSelectionChange: (ids: number[]) => void,
 *   inspectorHandle: object,   — { setTitle, setContent, showEmpty }
 *   refreshQueue?: () => void,
 *   initialActressId?: number|null,
 *   initialPanel?: string|null,
 *   onUrlChange?: (params: object) => void, — notify index.js of URL-relevant state changes
 * }} opts
 * @returns {{
 *   load(): Promise<void>,
 *   navigateToActress(id: number, panel?: string): Promise<void>,
 *   destroy(): void,
 * }}
 */
export function mountActresses(containerEl, {
  pivotState,
  selection,
  onSelectionChange,
  inspectorHandle,
  refreshQueue,
  initialActressId,
  initialPanel,
  onUrlChange,
}) {
  const noop = () => Promise.resolve();
  const doRefreshQueue = refreshQueue ?? noop;
  const doUrlChange    = onUrlChange  ?? (() => {});

  let alphaBuckets = [{ label: 'All', key: 'All', test: () => true }];
  // Which actress is currently shown in the inspector.
  let activeInspectorId = null;
  let activeInspectorTab = initialPanel || 'titles';
  // Cancellation for async inspector loads.
  let _inspectorSeq = 0;
  // Per-actress titles panel handles.
  let titlesHandle = null;

  // ── DOM structure ────────────────────────────────────────────────

  containerEl.innerHTML = `
    <div class="dr-actresses-layout">
      <div class="dr-actresses-list-pane">
        <div class="dr-actresses-toolbar">
          <div class="dr-alpha-bar" id="dr-alpha-bar"></div>
          <div class="dr-filter-bar" id="dr-actress-filter-bar"></div>
          <div class="dr-sort-bar" id="dr-sort-bar"></div>
          <div class="dr-actress-select-all-row" id="dr-actress-select-all-row" style="display:none">
            <label class="dr-actress-select-all-label">
              <input type="checkbox" id="dr-actress-select-all-cb">
              <span id="dr-actress-select-all-txt">Select all visible</span>
            </label>
            <button type="button" class="dr-actress-clear-sel-btn" id="dr-actress-clear-sel">Clear</button>
          </div>
        </div>
        <ul class="dr-actress-list" id="dr-actress-list"></ul>
        <div class="dr-actress-empty" id="dr-actress-empty" style="display:none">
          No actresses match the current filter.
        </div>
      </div>
    </div>
  `;

  const alphaBarEl       = containerEl.querySelector('#dr-alpha-bar');
  const filterBarEl      = containerEl.querySelector('#dr-actress-filter-bar');
  const sortBarEl        = containerEl.querySelector('#dr-sort-bar');
  const actressListEl    = containerEl.querySelector('#dr-actress-list');
  const emptyMsgEl       = containerEl.querySelector('#dr-actress-empty');
  const selectAllRow     = containerEl.querySelector('#dr-actress-select-all-row');
  const selectAllCb      = containerEl.querySelector('#dr-actress-select-all-cb');
  const selectAllTxt     = containerEl.querySelector('#dr-actress-select-all-txt');
  const clearSelBtn      = containerEl.querySelector('#dr-actress-clear-sel');

  // ── Filtering + sorting ──────────────────────────────────────────

  function filteredActresses() {
    let list = pivotState.rows;

    if (pivotState.alphaFilter !== 'All') {
      const bucket = alphaBuckets.find(b => b.key === pivotState.alphaFilter);
      if (bucket) list = list.filter(a => bucket.test(a.canonicalName || ''));
    }

    if (pivotState.tierFilter.size > 0) {
      list = list.filter(a => pivotState.tierFilter.has(computeTier(a.totalTitles)));
    }
    if (pivotState.favoritesOnly)  list = list.filter(a => a.favorite);
    if (pivotState.bookmarkedOnly) list = list.filter(a => a.bookmark);

    return [...list].sort((a, b) => {
      let cmp;
      if (pivotState.sortField === 'titles') {
        cmp = a.totalTitles - b.totalTitles;
        if (cmp === 0) cmp = a.canonicalName.localeCompare(b.canonicalName);
      } else {
        cmp = a.canonicalName.localeCompare(b.canonicalName);
      }
      return pivotState.sortDir === 'asc' ? cmp : -cmp;
    });
  }

  // ── Alpha bar ────────────────────────────────────────────────────

  function renderAlphaBar() {
    alphaBarEl.innerHTML = '';
    for (const bucket of alphaBuckets) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.textContent = bucket.label;
      btn.className = 'dr-alpha-btn' + (bucket.key === pivotState.alphaFilter ? ' active' : '');
      btn.addEventListener('click', async () => {
        if (bucket.key === pivotState.alphaFilter) return;
        pivotState.alphaFilter = bucket.key;
        renderAlphaBar();
        await applyFilterChange();
      });
      alphaBarEl.appendChild(btn);
    }
  }

  // ── Filter chips ─────────────────────────────────────────────────

  function renderFilterBar() {
    filterBarEl.innerHTML = '';
    const chips = [
      { key: 'fav',       label: '♥ Favorites',  cls: 'dr-filter-fav',       active: pivotState.favoritesOnly },
      { key: 'bkm',       label: '◉ Bookmarked', cls: 'dr-filter-bkm',       active: pivotState.bookmarkedOnly },
      { key: 'goddess',   label: 'Goddess',      cls: 'dr-filter-goddess',   active: pivotState.tierFilter.has('goddess') },
      { key: 'superstar', label: 'Superstar',    cls: 'dr-filter-superstar', active: pivotState.tierFilter.has('superstar') },
      { key: 'popular',   label: 'Popular',      cls: 'dr-filter-popular',   active: pivotState.tierFilter.has('popular') },
    ];
    for (const chip of chips) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.textContent = chip.label;
      btn.className = `dr-filter-chip ${chip.cls}` + (chip.active ? ' active' : '');
      btn.addEventListener('click', async () => {
        if (chip.key === 'fav') {
          pivotState.favoritesOnly = !pivotState.favoritesOnly;
        } else if (chip.key === 'bkm') {
          pivotState.bookmarkedOnly = !pivotState.bookmarkedOnly;
        } else {
          if (pivotState.tierFilter.has(chip.key)) pivotState.tierFilter.delete(chip.key);
          else pivotState.tierFilter.add(chip.key);
        }
        renderFilterBar();
        await applyFilterChange();
      });
      filterBarEl.appendChild(btn);
    }
  }

  // ── Sort bar ──────────────────────────────────────────────────────

  function renderSortBar() {
    sortBarEl.innerHTML = '';
    for (const { id, label } of [{ id: 'name', label: 'Name' }, { id: 'titles', label: 'Titles' }]) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.textContent = label;
      btn.className = 'dr-sort-btn' + (id === pivotState.sortField ? ' active' : '');
      btn.addEventListener('click', () => {
        if (pivotState.sortField === id) return;
        pivotState.sortField = id;
        renderSortBar();
        renderActressList();
      });
      sortBarEl.appendChild(btn);
    }
    const dirBtn = document.createElement('button');
    dirBtn.type = 'button';
    dirBtn.className = 'dr-sort-btn dr-sort-dir';
    dirBtn.title = pivotState.sortDir === 'asc' ? 'Ascending' : 'Descending';
    dirBtn.textContent = pivotState.sortDir === 'asc' ? '↑' : '↓';
    dirBtn.addEventListener('click', () => {
      pivotState.sortDir = pivotState.sortDir === 'asc' ? 'desc' : 'asc';
      renderSortBar();
      renderActressList();
    });
    sortBarEl.appendChild(dirBtn);
  }

  // ── Actress list ──────────────────────────────────────────────────

  function actressStatusDot(a) {
    if (a.activeJobs > 0) {
      return `<span class="dr-dot dr-dot-queued" title="${a.activeJobs} job${a.activeJobs !== 1 ? 's' : ''} in queue"></span>`;
    }
    if (a.enrichedTitles === a.totalTitles && a.totalTitles > 0) {
      return '<span class="dr-dot dr-dot-done" title="All titles enriched"></span>';
    }
    if (a.enrichedTitles > 0) {
      return '<span class="dr-dot dr-dot-partial" title="Partially enriched"></span>';
    }
    return '<span class="dr-dot dr-dot-none" title="Not started"></span>';
  }

  function renderActressList() {
    const visible = filteredActresses();
    actressListEl.innerHTML = '';

    if (visible.length === 0) {
      emptyMsgEl.style.display = '';
      updateSelectAllState();
      return;
    }
    emptyMsgEl.style.display = 'none';

    for (const a of visible) {
      const li = document.createElement('li');
      li.className = 'dr-actress-item';
      li.dataset.id = String(a.id);
      if (selection.has(a.id)) li.classList.add('selected');

      const enrichedPct = a.totalTitles > 0
        ? Math.round((a.enrichedTitles / a.totalTitles) * 100)
        : 0;
      li.innerHTML = `
        <span class="dr-actress-name">${actressStatusDot(a)}${esc(a.canonicalName)}</span>
        <span class="dr-actress-counts">${a.enrichedTitles}/${a.totalTitles} (${enrichedPct}%)</span>
      `;
      li.addEventListener('click', e => handleActressClick(a.id, e));
      actressListEl.appendChild(li);
    }
    updateSelectAllState();
  }

  function highlightSelection() {
    actressListEl.querySelectorAll('.dr-actress-item').forEach(li => {
      li.classList.toggle('selected', selection.has(Number(li.dataset.id)));
    });
    updateSelectAllState();
  }

  // ── Select-all row ────────────────────────────────────────────────

  function updateSelectAllState() {
    const visible = filteredActresses();
    const total   = selection.size;
    // Show the row only when we have actresses to work with.
    if (visible.length === 0) { selectAllRow.style.display = 'none'; return; }
    selectAllRow.style.display = '';

    const visibleSelected = visible.filter(a => selection.has(a.id)).length;
    if (total === 0 || visibleSelected === 0) {
      selectAllCb.checked = false; selectAllCb.indeterminate = false;
      selectAllTxt.textContent = 'Select all visible';
    } else if (visibleSelected === visible.length) {
      selectAllCb.checked = true; selectAllCb.indeterminate = false;
      selectAllTxt.textContent = `${total} selected`;
    } else {
      selectAllCb.checked = false; selectAllCb.indeterminate = true;
      selectAllTxt.textContent = `${visibleSelected}/${visible.length} visible selected (${total} total)`;
    }
  }

  selectAllCb.addEventListener('click', () => {
    const visible = filteredActresses();
    if (selectAllCb.checked) {
      visible.forEach(a => selection.add(a.id));
    } else {
      visible.forEach(a => selection.delete(a.id));
    }
    highlightSelection();
    onSelectionChange(Array.from(selection));
    refreshInspector();
  });

  clearSelBtn.addEventListener('click', () => {
    selection.clear();
    highlightSelection();
    onSelectionChange([]);
    doUrlChange({ id: null, panel: null });
    refreshInspector();
  });

  // ── Click handling: click / Cmd-click / Shift-click ──────────────

  let _lastClickedIdx = -1;

  function handleActressClick(id, e) {
    const visible = filteredActresses();
    const idx = visible.findIndex(a => a.id === id);

    if (e.shiftKey && _lastClickedIdx >= 0) {
      // Range select.
      const lo = Math.min(_lastClickedIdx, idx);
      const hi = Math.max(_lastClickedIdx, idx);
      for (let i = lo; i <= hi; i++) selection.add(visible[i].id);
    } else if (e.metaKey || e.ctrlKey) {
      // Toggle one.
      if (selection.has(id)) selection.delete(id);
      else selection.add(id);
      _lastClickedIdx = idx;
    } else {
      // Select-one; clear others.
      selection.clear();
      selection.add(id);
      _lastClickedIdx = idx;
      // Single-select: replaceState (not push) — clicking around actresses
      // shouldn't flood history; back-button navigates away from the pivot.
      doUrlChange({ id, panel: activeInspectorTab });
    }

    // For multi-select (shift/ctrl), sync URL: clear id if >1 selected,
    // write remaining id if exactly 1, or null if 0.
    if (e.shiftKey || e.metaKey || e.ctrlKey) {
      if (selection.size > 1) {
        doUrlChange({ id: null, panel: null });
      } else if (selection.size === 1) {
        doUrlChange({ id: Array.from(selection)[0], panel: activeInspectorTab });
      } else {
        doUrlChange({ id: null, panel: null });
      }
    }

    highlightSelection();
    onSelectionChange(Array.from(selection));
    refreshInspector();
  }

  async function applyFilterChange() {
    // Per §4.3: selected-but-filtered-out rows stay selected (invisible).
    // Only update the visual list and inspector; do NOT drop IDs from selection.
    renderActressList();
    updateSelectAllState();
    refreshInspector();
  }

  // ── Inspector: drive content from selection ───────────────────────

  function refreshInspector() {
    const ids = Array.from(selection);
    if (ids.length === 0) {
      inspectorHandle.showEmpty('Select an actress to see enrichment details.');
      activeInspectorId = null;
      titlesHandle = null;
      doUrlChange({ id: null, panel: null });
      return;
    }
    if (ids.length > 1) {
      renderBulkInspector(ids);
      activeInspectorId = null;
      return;
    }
    const id = ids[0];
    if (id === activeInspectorId) return; // already showing
    activeInspectorId = id;
    titlesHandle = null;
    showActressInspector(id, activeInspectorTab);
  }

  function renderBulkInspector(ids) {
    // Look up selected rows (some may not be in filtered view).
    const rows     = pivotState.rows.filter(a => ids.includes(a.id));
    const found    = rows.length; // may be < ids.length if some not yet loaded

    const totalTitles  = rows.reduce((s, a) => s + a.totalTitles,    0);
    const enriched     = rows.reduce((s, a) => s + a.enrichedTitles, 0);
    const unenriched   = totalTitles - enriched;
    const withJobs     = rows.filter(a => a.activeJobs > 0).length;
    const pct          = totalTitles > 0 ? Math.round((enriched / totalTitles) * 100) : 0;

    // Inline names: up to 10, then "+N more".
    const MAX_NAMES = 10;
    const nameList  = rows.slice(0, MAX_NAMES).map(a => esc(a.canonicalName)).join(', ');
    const nameExtra = found > MAX_NAMES ? ` <span class="dr-muted">+${found - MAX_NAMES} more</span>` : '';

    inspectorHandle.setTitle(`${ids.length} actresses selected`);
    inspectorHandle.setContent(`
      <div class="dr-bulk-inspector">
        <div class="dr-bulk-names">${nameList}${nameExtra}</div>
        <div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Selected</span><span class="dr-bulk-stat-value">${ids.length}</span></div>
        <div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Total titles</span><span class="dr-bulk-stat-value">${totalTitles}</span></div>
        <div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Enriched</span><span class="dr-bulk-stat-value">${enriched} (${pct}%)</span></div>
        <div class="dr-bulk-stat"><span class="dr-bulk-stat-label">Unenriched</span><span class="dr-bulk-stat-value">${unenriched}</span></div>
        <div class="dr-bulk-stat"><span class="dr-bulk-stat-label">With active jobs</span><span class="dr-bulk-stat-value">${withJobs}</span></div>
        <div class="dr-bulk-actions">
          <button type="button" class="dr-btn dr-btn-primary" id="dr-bulk-enqueue-btn">
            ▶ Enrich Selected (${ids.length} actresses, ${unenriched} unenriched titles)
          </button>
        </div>
        <div class="dr-bulk-clear">
          <button type="button" class="dr-link-btn" id="dr-bulk-clear-btn">Clear selection</button>
        </div>
      </div>
    `);

    // Wire bulk enqueue — querySelector from pageEl since inspector body isn't directly accessible.
    const pageEl     = containerEl.closest('.dr-page') ?? containerEl.parentElement;
    const enqueueBtn = pageEl.querySelector('#dr-bulk-enqueue-btn');
    if (enqueueBtn) enqueueBtn.addEventListener('click', () => bulkEnqueue(ids, enqueueBtn, pageEl));

    const clearBtn = pageEl.querySelector('#dr-bulk-clear-btn');
    if (clearBtn) clearBtn.addEventListener('click', () => {
      selection.clear();
      highlightSelection();
      onSelectionChange([]);
      doUrlChange({ id: null, panel: null });
      refreshInspector();
    });
  }

  /**
   * Fan-out enqueue across N actresses, max 4 concurrent.
   * Shows a toast summary on completion.
   */
  async function bulkEnqueue(ids, btn, pageEl) {
    btn.disabled = true;
    btn.textContent = `Enqueuing ${ids.length} actresses…`;

    const CONCURRENCY = 4;
    let enqueued  = 0;
    let failures  = 0;
    let done      = 0;

    async function enqueueOne(id) {
      try {
        const res = await fetch(`/api/javdb/discovery/actresses/${id}/enqueue`, { method: 'POST' });
        if (res.ok) { const d = await res.json(); enqueued += d.enqueued || 0; }
        else        { failures++; }
      } catch (_) { failures++; }
      done++;
      if (btn.isConnected) btn.textContent = `Enqueuing… (${done}/${ids.length})`;
    }

    // Chunk into batches of CONCURRENCY.
    for (let i = 0; i < ids.length; i += CONCURRENCY) {
      await Promise.all(ids.slice(i, i + CONCURRENCY).map(id => enqueueOne(id)));
    }

    const summary = failures > 0
      ? `Enqueued ${enqueued} titles (${failures} actress${failures !== 1 ? 'es' : ''} failed)`
      : `Enqueued ${enqueued} titles across ${ids.length} actress${ids.length !== 1 ? 'es' : ''}`;

    if (btn.isConnected) {
      btn.textContent = `${summary} ✓`;
    }
    showBulkToast(pageEl, summary, failures > 0 ? 'error' : 'success');
    await doRefreshQueue();
    setTimeout(() => load(), 1000);
  }

  /**
   * Show a transient toast anchored to the workbench page element.
   */
  function showBulkToast(pageEl, msg, kind = 'success') {
    const t = document.createElement('div');
    t.className = 'dr-toast' + (kind === 'error' ? ' dr-toast--error' : '');
    t.textContent = msg;
    (pageEl ?? document.body).appendChild(t);
    setTimeout(() => t.remove(), 4500);
  }

  // ── Single-actress inspector ──────────────────────────────────────

  async function showActressInspector(actressId, tab) {
    const actress = pivotState.rows.find(a => a.id === actressId);
    const name = actress?.canonicalName ?? `Actress #${actressId}`;
    inspectorHandle.setTitle(name);
    activeInspectorTab = tab || 'titles';

    // Render the sub-tab chrome first.
    inspectorHandle.setContent(`
      <div class="dr-actress-inspector">
        <div class="dr-inspector-subtabs" id="dr-inspector-subtabs">
          <button class="dr-inspector-subtab${activeInspectorTab === 'titles'    ? ' active' : ''}" data-tab="titles">Titles</button>
          <button class="dr-inspector-subtab${activeInspectorTab === 'profile'   ? ' active' : ''}" data-tab="profile">Profile</button>
          <button class="dr-inspector-subtab${activeInspectorTab === 'conflicts' ? ' active' : ''}" data-tab="conflicts">Conflicts</button>
          <button class="dr-inspector-subtab${activeInspectorTab === 'errors'    ? ' active' : ''}" data-tab="errors">Errors</button>
        </div>
        <div class="dr-inspector-header-actions" id="dr-inspector-header-actions">
          <button type="button" class="dr-btn dr-btn-sm dr-btn-primary" id="dr-enrich-btn">▶ Enrich</button>
          <button type="button" class="dr-btn dr-btn-sm" id="dr-cancel-btn">⏹ Cancel</button>
        </div>
        <div class="dr-inspector-tab-body" id="dr-inspector-tab-body">
          <div class="dr-loading">Loading…</div>
        </div>
      </div>
    `);

    // Scope DOM queries to the workbench page.
    const pageEl = containerEl.closest('.dr-page') ?? containerEl.parentElement;

    // Wire sub-tabs — use containerEl.ownerDocument to traverse up.
    pageEl.querySelectorAll('.dr-inspector-subtab').forEach(btn => {
      btn.addEventListener('click', async () => {
        activeInspectorTab = btn.dataset.tab;
        pageEl.querySelectorAll('.dr-inspector-subtab').forEach(b => b.classList.toggle('active', b === btn));
        // Write URL for single-select panel change (replaceState per §4.5).
        doUrlChange({ id: actressId, panel: activeInspectorTab });
        await loadInspectorTab(actressId, activeInspectorTab, pageEl);
      });
    });

    // Wire enrich/cancel.
    const enrichBtn = pageEl.querySelector('#dr-enrich-btn');
    const cancelBtn = pageEl.querySelector('#dr-cancel-btn');
    if (enrichBtn) enrichBtn.addEventListener('click', () => enrichActress(actressId, enrichBtn));
    if (cancelBtn) cancelBtn.addEventListener('click', () => cancelActress(actressId));

    await loadInspectorTab(actressId, activeInspectorTab, pageEl);
  }

  async function loadInspectorTab(actressId, tab, pageEl) {
    const bodyEl = pageEl.querySelector('#dr-inspector-tab-body');
    if (!bodyEl) return;
    const seq = ++_inspectorSeq;
    bodyEl.innerHTML = '<div class="dr-loading">Loading…</div>';
    if (tab === 'titles')    await renderTitlesTab(actressId, bodyEl, pageEl, seq);
    if (tab === 'profile')   await renderProfileTab(actressId, bodyEl, seq);
    if (tab === 'conflicts') await renderConflictsTab(actressId, bodyEl, seq);
    if (tab === 'errors')    await renderErrorsTab(actressId, bodyEl, seq);
  }

  // ── Titles sub-tab ────────────────────────────────────────────────

  async function renderTitlesTab(actressId, bodyEl, pageEl, seq) {
    let titleFilter = { tags: [], minRatingAvg: null, minRatingCount: null };

    async function fetchAndRender() {
      if (_inspectorSeq !== seq) return; // stale
      const f = titleFilter;
      const parts = [];
      if (f.tags.length > 0)        parts.push(`tags=${encodeURIComponent(f.tags.join(','))}`);
      if (f.minRatingAvg !== null)   parts.push(`minRatingAvg=${f.minRatingAvg}`);
      if (f.minRatingCount !== null) parts.push(`minRatingCount=${f.minRatingCount}`);
      const qs = parts.length ? '?' + parts.join('&') : '';
      try {
        const [titlesRes, facetsRes] = await Promise.all([
          fetch(`/api/javdb/discovery/actresses/${actressId}/titles${qs}`),
          fetch(`/api/javdb/discovery/actresses/${actressId}/tag-facets${qs}`),
        ]);
        if (_inspectorSeq !== seq) return;
        if (!titlesRes.ok) { bodyEl.innerHTML = '<div class="dr-error">Failed to load titles.</div>'; return; }
        const titles = await titlesRes.json();
        const facets = facetsRes.ok ? await facetsRes.json() : [];
        bodyEl.innerHTML = buildFilterBarHtml(facets, titles.length, f) + buildTitlesTableHtml(titles);
        wireFilterBar(bodyEl, titleFilter, fetchAndRender);
        wireCoverLinks(bodyEl);
        wireDetailTriggers(bodyEl, actressId, pageEl, seq);
        wireFailureBadges(bodyEl, actressId);
        wireReenrichButtons(bodyEl, actressId);
      } catch (_) {
        if (_inspectorSeq !== seq) return;
        bodyEl.innerHTML = '<div class="dr-error">Network error.</div>';
      }
    }

    titlesHandle = { refresh: fetchAndRender };
    await fetchAndRender();
  }

  function buildFilterBarHtml(facets, matchCount, f) {
    const selectedSet = new Set(f.tags);
    const ordered = [
      ...facets.filter(x => selectedSet.has(x.name)),
      ...facets.filter(x => !selectedSet.has(x.name)),
    ];
    const tagChips = ordered.slice(0, 30).map(x => {
      const sel = selectedSet.has(x.name);
      return `<button type="button" class="dr-tag-chip${sel ? ' selected' : ''}" data-tag="${esc(x.name)}">
        ${esc(x.name)} <span class="dr-tag-count">${x.count}</span>
      </button>`;
    }).join('');
    const isActive = f.tags.length > 0 || f.minRatingAvg !== null || f.minRatingCount !== null;
    const summary  = isActive ? `<span class="dr-filter-summary">${matchCount} matching</span>` : '';
    const clearBtn = isActive ? `<button type="button" class="dr-titles-filter-clear">Clear filters</button>` : '';
    return `
      <div class="dr-titles-filter-bar">
        <div class="dr-filter-row">
          <label class="dr-filter-label">Min rating</label>
          <input class="dr-titles-min-avg dr-filter-num" type="number" step="0.1" min="0" max="5"
                 placeholder="4.2" value="${f.minRatingAvg ?? ''}">
          <label class="dr-filter-label">Min votes</label>
          <input class="dr-titles-min-cnt dr-filter-num" type="number" step="1" min="0"
                 placeholder="50" value="${f.minRatingCount ?? ''}">
          ${summary}${clearBtn}
        </div>
        <div class="dr-filter-row dr-tag-chips">
          ${tagChips || '<span class="dr-filter-hint">No tags yet.</span>'}
        </div>
      </div>`;
  }

  function wireFilterBar(bodyEl, titleFilter, onChange) {
    const minAvg = bodyEl.querySelector('.dr-titles-min-avg');
    const minCnt = bodyEl.querySelector('.dr-titles-min-cnt');
    if (minAvg) minAvg.addEventListener('change', () => {
      const v = minAvg.value.trim();
      titleFilter.minRatingAvg = v === '' ? null : parseFloat(v);
      onChange();
    });
    if (minCnt) minCnt.addEventListener('change', () => {
      const v = minCnt.value.trim();
      titleFilter.minRatingCount = v === '' ? null : parseInt(v, 10);
      onChange();
    });
    bodyEl.querySelectorAll('.dr-tag-chip').forEach(chip => {
      chip.addEventListener('click', () => {
        const tag = chip.dataset.tag;
        const idx = titleFilter.tags.indexOf(tag);
        if (idx >= 0) titleFilter.tags.splice(idx, 1);
        else titleFilter.tags.push(tag);
        onChange();
      });
    });
    const clearBtn = bodyEl.querySelector('.dr-titles-filter-clear');
    if (clearBtn) clearBtn.addEventListener('click', () => {
      titleFilter.tags = [];
      titleFilter.minRatingAvg = null;
      titleFilter.minRatingCount = null;
      onChange();
    });
  }

  function buildTitlesTableHtml(titles) {
    if (titles.length === 0) return '<div class="dr-empty-tab">No titles match the current filter.</div>';
    return `<div class="dr-titles-table-wrap"><table class="dr-titles-table">
      <thead><tr>
        <th>Code</th><th>Status</th><th>Original Title</th><th>Release</th><th>Maker</th><th>Rating</th><th></th>
      </tr></thead>
      <tbody>${titles.map(t => buildTitleRow(t)).join('')}</tbody>
    </table></div>`;
  }

  function titleEffectiveStatus(t) {
    if (t.queueStatus === 'in_flight') return { key: 'in_flight', label: '⟳ In Progress' };
    if (t.queueStatus === 'pending')   return { key: 'pending',   label: '◌ Queued' };
    if (t.status === 'fetched')        return { key: 'fetched',   label: '✓ Enriched' };
    if (t.queueStatus === 'failed') {
      const meta  = QUEUE_FAIL_META[t.lastError];
      const icon  = meta?.icon  ?? '✗';
      const label = meta?.label ?? (t.lastError ? t.lastError.replace(/_/g, ' ') : 'failed');
      const cls   = meta?.cls   ?? 'dr-qi-failed';
      return { key: 'failed', label: `${icon} ${label}`, cls, lastError: t.lastError, reviewQueueId: t.reviewQueueId };
    }
    if (t.status === 'slug_only') return { key: 'slug_only', label: '⌁ Slug Only' };
    if (t.queueStatus === 'done') return { key: 'done',      label: '✓ Done' };
    return { key: 'none', label: '— Not Started' };
  }

  function buildTitleRow(t) {
    const st = titleEffectiveStatus(t);
    const { key, label } = st;
    const isEnriched = key === 'fetched';
    let statusCell;
    if (isEnriched) {
      statusCell = `<span class="dr-status dr-status-${key} dr-status-clickable" data-title-id="${t.titleId}" title="View enrichment details">${label}</span>`;
    } else if (key === 'failed') {
      const cls     = st.cls;
      const tooltip = esc(st.lastError || '');
      if (st.reviewQueueId != null) {
        statusCell = `<button class="dr-status ${cls} dr-titles-review-link" data-review-id="${st.reviewQueueId}" title="${tooltip}">${label}</button>`;
      } else if (st.lastError === 'no_slug') {
        statusCell = `<button class="dr-status ${cls} dr-titles-profile-link" title="${tooltip}">${label}</button>`;
      } else {
        statusCell = `<span class="dr-status ${cls}" title="${tooltip}">${label}</span>`;
      }
    } else {
      statusCell = `<span class="dr-status dr-status-${key}">${label}</span>`;
    }
    const canReenrich = isEnriched || key === 'done' || key === 'failed' || t.status === 'not_found';
    const infoBtn = isEnriched
      ? `<button class="dr-enrich-detail-btn" data-title-id="${t.titleId}" title="View enrichment details">ⓘ</button>` : '';
    const reenrichBtn = canReenrich
      ? `<button class="dr-reenrich-btn" data-title-id="${t.titleId}" title="Force re-enrich">↺</button>` : '';
    const rating = t.ratingAvg != null
      ? `<span class="dr-rating">${t.ratingAvg.toFixed(2)}<span class="dr-rating-count"> · ${t.ratingCount ?? 0}</span></span>`
      : '—';
    const codeCell = t.localCoverUrl
      ? `<button class="dr-cover-link" data-cover-url="${esc(t.localCoverUrl)}" data-code="${esc(t.code)}">${esc(t.code)}</button>`
      : esc(t.code);
    return `<tr>
      <td class="dr-code">${codeCell}</td>
      <td>${statusCell}</td>
      <td>${t.titleOriginal ? esc(t.titleOriginal) : '—'}</td>
      <td>${fmtDate(t.releaseDate)}</td>
      <td>${t.maker ? esc(t.maker) : '—'}</td>
      <td class="dr-rating-cell">${rating}</td>
      <td class="dr-action-cell">${infoBtn}${reenrichBtn}</td>
    </tr>`;
  }

  function wireCoverLinks(el) {
    el.querySelectorAll('.dr-cover-link[data-cover-url]').forEach(btn => {
      btn.addEventListener('click', () => showCoverLightbox(btn.dataset.coverUrl, btn.dataset.code || ''));
    });
  }

  function wireDetailTriggers(bodyEl, actressId, pageEl, seq) {
    bodyEl.querySelectorAll('.dr-enrich-detail-btn, .dr-status-clickable').forEach(el => {
      el.addEventListener('click', async e => {
        e.stopPropagation();
        if (_inspectorSeq !== seq) return;
        await openEnrichmentDetail(actressId, Number(el.dataset.titleId), pageEl);
      });
    });
  }

  async function openEnrichmentDetail(actressId, titleId, pageEl) {
    const tabBodyEl = pageEl.querySelector('#dr-inspector-tab-body');
    if (!tabBodyEl) return;
    tabBodyEl.innerHTML = '<div class="dr-loading">Loading enrichment detail…</div>';
    try {
      const res = await fetch(`/api/javdb/discovery/titles/${titleId}/enrichment`);
      if (!res.ok) { tabBodyEl.innerHTML = '<div class="dr-error">Failed to load enrichment data.</div>'; return; }
      renderEnrichmentDetail(await res.json(), tabBodyEl);
    } catch (_) {
      tabBodyEl.innerHTML = '<div class="dr-error">Network error.</div>';
    }
  }

  function renderEnrichmentDetail(d, bodyEl) {
    const metaRows = [];
    if (d.releaseDate)     metaRows.push(['Release', fmtDate(d.releaseDate)]);
    if (d.durationMinutes) metaRows.push(['Duration', `${d.durationMinutes} min`]);
    if (d.maker)           metaRows.push(['Maker', esc(d.maker)]);
    if (d.publisher && d.publisher !== d.maker) metaRows.push(['Publisher', esc(d.publisher)]);
    if (d.series)          metaRows.push(['Series', esc(d.series)]);
    if (d.ratingAvg != null) {
      const votes = d.ratingCount != null ? ` · ${d.ratingCount} votes` : '';
      metaRows.push(['Rating', `${d.ratingAvg.toFixed(2)} / 5${votes}`]);
    }
    const metaHtml = metaRows.length > 0
      ? `<div class="dr-enrich-section-label">Details</div>
         <div class="dr-enrich-meta-grid">${metaRows.map(([k, v]) =>
           `<span class="dr-enrich-meta-label">${k}</span><span class="dr-enrich-meta-value">${v}</span>`).join('')}</div>` : '';
    const cast = parseCast(d.castJson);
    const castHtml = cast.length > 0
      ? `<div class="dr-enrich-section-label">Cast</div>
         <div class="dr-enrich-cast-list">${cast.map(e => `<span class="dr-enrich-cast-name">${esc(e.name)}</span>`).join('')}</div>` : '';
    const tagsHtml = d.tags && d.tags.length > 0
      ? `<div class="dr-enrich-section-label">Tags from javdb</div>
         <div class="dr-enrich-tag-list">${d.tags.map(t => `<span class="dr-enrich-tag">${esc(t)}</span>`).join('')}</div>` : '';
    const javdbUrl = d.javdbSlug ? `https://javdb.com/v/${esc(d.javdbSlug)}` : null;
    bodyEl.innerHTML = `
      <div class="dr-enrich-detail">
        <div class="dr-enrich-detail-header">
          <span class="dr-enrich-modal-code">${esc(d.code)}</span>
          ${d.titleOriginal ? `<span class="dr-enrich-modal-title">${esc(d.titleOriginal)}</span>` : ''}
        </div>
        ${metaHtml}${castHtml}${tagsHtml}
        <div class="dr-enrich-footer">
          ${javdbUrl ? `<a class="dr-enrich-source-link" href="${javdbUrl}" target="_blank" rel="noopener">View on javdb ↗</a>` : ''}
          ${d.fetchedAt ? `<span class="dr-enrich-fetched-at">Fetched ${fmtDate(d.fetchedAt.slice(0, 10))}</span>` : ''}
        </div>
        <div class="dr-enrich-detail-back"><button type="button" class="dr-btn dr-btn-sm dr-enrich-back-btn">← Back to titles</button></div>
      </div>`;
    bodyEl.querySelector('.dr-enrich-back-btn')?.addEventListener('click', async () => {
      const pageEl = containerEl.closest('.dr-page') ?? containerEl.parentElement;
      if (titlesHandle) await titlesHandle.refresh();
      else {
        const seq = ++_inspectorSeq;
        await renderTitlesTab(activeInspectorId, bodyEl, pageEl, seq);
      }
    });
  }

  function wireFailureBadges(bodyEl, actressId) {
    bodyEl.querySelectorAll('.dr-titles-review-link[data-review-id]').forEach(btn => {
      btn.addEventListener('click', () => {
        document.dispatchEvent(new CustomEvent('navigate-to-review-item', {
          detail: { reviewQueueId: parseInt(btn.dataset.reviewId, 10) }
        }));
      });
    });
    bodyEl.querySelectorAll('.dr-titles-profile-link').forEach(btn => {
      btn.addEventListener('click', () => {
        // Switch to profile sub-tab for this actress.
        const pageEl = containerEl.closest('.dr-page') ?? containerEl.parentElement;
        pageEl.querySelectorAll('.dr-inspector-subtab').forEach(b =>
          b.classList.toggle('active', b.dataset.tab === 'profile'));
        activeInspectorTab = 'profile';
        const tabBodyEl = pageEl.querySelector('#dr-inspector-tab-body');
        if (tabBodyEl) {
          const seq = ++_inspectorSeq;
          renderProfileTab(actressId, tabBodyEl, seq);
        }
      });
    });
  }

  function wireReenrichButtons(bodyEl, actressId) {
    bodyEl.querySelectorAll('.dr-reenrich-btn').forEach(btn => {
      btn.addEventListener('click', async () => {
        const titleId = btn.dataset.titleId;
        const orig = btn.textContent;
        btn.textContent = '⌛';
        btn.disabled = true;
        btn.classList.add('dr-btn-busy');
        try {
          const r = await fetch(`/api/javdb/discovery/actresses/${actressId}/titles/${titleId}/reenrich`, { method: 'POST' });
          if (!r.ok) throw new Error('http ' + r.status);
          btn.classList.remove('dr-btn-busy');
          btn.classList.add('dr-btn-success');
          btn.textContent = '✓';
          setTimeout(() => { if (titlesHandle) titlesHandle.refresh(); }, 800);
          await doRefreshQueue();
        } catch (_) {
          btn.classList.remove('dr-btn-busy');
          btn.classList.add('dr-btn-error');
          btn.textContent = '✗';
          setTimeout(() => {
            btn.classList.remove('dr-btn-error');
            btn.textContent = orig;
            btn.disabled = false;
          }, 2500);
        }
      });
    });
  }

  // ── Profile sub-tab ───────────────────────────────────────────────

  async function renderProfileTab(actressId, bodyEl, seq) {
    try {
      const res = await fetch(`/api/javdb/discovery/actresses/${actressId}/profile`);
      if (_inspectorSeq !== seq) return;
      if (res.status === 404) {
        await renderProfileNotFound(actressId, bodyEl, seq);
        return;
      }
      if (!res.ok) { bodyEl.innerHTML = '<div class="dr-error">Failed to load profile.</div>'; return; }
      renderProfileContent(actressId, await res.json(), bodyEl);
    } catch (_) {
      if (_inspectorSeq !== seq) return;
      bodyEl.innerHTML = '<div class="dr-error">Network error.</div>';
    }
  }

  async function renderProfileNotFound(actressId, bodyEl, seq) {
    let enrichedTitles = 0;
    try {
      const r = await fetch(`/api/javdb/discovery/actresses/${actressId}/titles`);
      if (r.ok) enrichedTitles = (await r.json()).filter(t => t.status === 'fetched').length;
    } catch (_) { /* show no-data path */ }
    if (_inspectorSeq !== seq) return;
    bodyEl.innerHTML = `
      <div class="dr-empty-tab">
        <div>No staging profile yet.</div>
        ${enrichedTitles > 0 ? `
          <div class="dr-derive-help">${enrichedTitles} title(s) enriched. Derive slug from co-stars:</div>
          <div class="dr-profile-actions">
            <button class="dr-btn dr-btn-sm dr-ep-derive-btn">⚡ Find Slug from Titles</button>
          </div>
          <div class="dr-ep-derive-result"></div>
        ` : '<div class="dr-derive-help">No enriched titles — no cast data to derive from.</div>'}
      </div>`;
    bodyEl.querySelector('.dr-ep-derive-btn')?.addEventListener('click', e => {
      deriveSlug(actressId, e.target, bodyEl, seq);
    });
  }

  function renderProfileContent(actressId, p, bodyEl) {
    bodyEl.innerHTML = `
      <div class="dr-profile">
        ${(p.localAvatarUrl || p.avatarUrl)
          ? `<img class="dr-avatar" src="${esc(p.localAvatarUrl || p.avatarUrl)}" alt="avatar">`
          : ''}
        <dl class="dr-profile-fields">
          <dt>Slug</dt><dd>${p.javdbSlug ? esc(p.javdbSlug) : '—'}</dd>
          <dt>Status</dt><dd><span class="dr-status dr-status-${esc(p.status ?? 'none')}">${esc(p.status ?? '—')}</span></dd>
          <dt>Fetched at</dt><dd>${p.rawFetchedAt ? esc(p.rawFetchedAt) : '—'}</dd>
          <dt>Title count</dt><dd>${p.titleCount != null ? p.titleCount : '—'}</dd>
          <dt>Twitter</dt><dd>${p.twitterHandle ? esc(p.twitterHandle) : '—'}</dd>
          <dt>Instagram</dt><dd>${p.instagramHandle ? esc(p.instagramHandle) : '—'}</dd>
          ${p.nameVariantsJson ? `<dt>Name variants</dt><dd class="dr-variants">${esc(p.nameVariantsJson)}</dd>` : ''}
        </dl>
        <div class="dr-profile-actions">
          <button class="dr-btn dr-btn-sm dr-ep-refetch-btn">↺ Re-fetch Profile</button>
          ${p.avatarUrl && !p.localAvatarUrl
            ? `<button class="dr-btn dr-btn-sm dr-ep-download-avatar-btn">⬇ Download Avatar</button>`
            : ''}
        </div>
      </div>`;

    bodyEl.querySelector('.dr-ep-refetch-btn')?.addEventListener('click', async () => {
      const btn = bodyEl.querySelector('.dr-ep-refetch-btn');
      const orig = btn.textContent;
      btn.textContent = '⌛ Enqueuing…'; btn.disabled = true; btn.classList.add('dr-btn-busy');
      try {
        const r = await fetch(`/api/javdb/discovery/actresses/${actressId}/profile/reenrich`, { method: 'POST' });
        if (!r.ok) throw new Error('http ' + r.status);
        btn.classList.remove('dr-btn-busy');
        btn.classList.add('dr-btn-success');
        btn.textContent = '✓ Queued';
        await doRefreshQueue();
        setTimeout(() => { btn.classList.remove('dr-btn-success'); btn.textContent = orig; btn.disabled = false; }, 2500);
      } catch (_) {
        btn.classList.remove('dr-btn-busy');
        btn.classList.add('dr-btn-error');
        btn.textContent = '✗ Failed';
        setTimeout(() => { btn.classList.remove('dr-btn-error'); btn.textContent = orig; btn.disabled = false; }, 2500);
      }
    });

    const dlBtn = bodyEl.querySelector('.dr-ep-download-avatar-btn');
    if (dlBtn) dlBtn.addEventListener('click', async () => {
      const orig = dlBtn.textContent;
      dlBtn.textContent = '⌛ Downloading…'; dlBtn.disabled = true; dlBtn.classList.add('dr-btn-busy');
      try {
        const r = await fetch(`/api/javdb/discovery/actresses/${actressId}/avatar/download`, { method: 'POST' });
        const body = await r.json().catch(() => ({}));
        if (!r.ok) {
          const reason = body.status === 'no_url'     ? 'no avatar URL'
                       : body.status === 'failed'     ? 'CDN download failed'
                       : body.status === 'no_profile' ? 'no profile'
                       : `error (${r.status})`;
          dlBtn.classList.remove('dr-btn-busy');
          dlBtn.classList.add('dr-btn-error');
          dlBtn.textContent = `✗ ${reason}`;
          setTimeout(() => { dlBtn.classList.remove('dr-btn-error'); dlBtn.textContent = orig; dlBtn.disabled = false; }, 2500);
          return;
        }
        dlBtn.classList.remove('dr-btn-busy');
        dlBtn.classList.add('dr-btn-success');
        dlBtn.textContent = '✓ Downloaded';
        setTimeout(() => renderProfileTab(actressId, bodyEl, _inspectorSeq), 700);
      } catch (_) {
        dlBtn.classList.remove('dr-btn-busy');
        dlBtn.classList.add('dr-btn-error');
        dlBtn.textContent = '✗ network error';
        setTimeout(() => { dlBtn.classList.remove('dr-btn-error'); dlBtn.textContent = orig; dlBtn.disabled = false; }, 2500);
      }
    });
  }

  async function deriveSlug(actressId, btn, bodyEl, seq) {
    const orig = btn.textContent;
    btn.textContent = '⌛ Deriving…'; btn.disabled = true; btn.classList.add('dr-btn-busy');
    const out = bodyEl.querySelector('.dr-ep-derive-result');
    try {
      const r = await fetch(`/api/javdb/discovery/actresses/${actressId}/profile/derive-slug`, { method: 'POST' });
      const body = await r.json().catch(() => ({}));
      if (_inspectorSeq !== seq) return;
      if (r.ok) {
        btn.classList.remove('dr-btn-busy');
        btn.classList.add('dr-btn-success');
        btn.textContent = body.status === 'already_resolved' ? '✓ Already had slug — re-queued' : '✓ Slug found — queued';
        if (out) out.innerHTML = `<div class="dr-derive-success">
          Picked slug <code>${esc(body.chosenSlug)}</code>${body.chosenName ? ` (${esc(body.chosenName)})` : ''}
          ${body.chosenTitleCount ? ` — appears in ${body.chosenTitleCount} of her ${body.totalEnrichedTitles} enriched titles.` : ''}
          See Queue tab for fetch progress.
        </div>`;
        setTimeout(() => renderProfileTab(actressId, bodyEl, _inspectorSeq), 1500);
        return;
      }
      btn.classList.remove('dr-btn-busy');
      btn.classList.add('dr-btn-error');
      if (body.status === 'ambiguous' && out) {
        const rows = (body.candidates || []).slice(0, 5).map(c =>
          `<tr><td><code>${esc(c.slug)}</code></td><td>${esc(c.name || '—')}</td><td>${c.titleCount}</td></tr>`).join('');
        btn.textContent = '✗ Ambiguous';
        out.innerHTML = `<div class="dr-derive-error">Top candidates tied:<table class="dr-derive-candidates">
          <thead><tr><th>Slug</th><th>Name</th><th>Titles</th></tr></thead>
          <tbody>${rows}</tbody></table></div>`;
      } else if (body.status === 'no_data') {
        btn.textContent = '✗ No cast data';
        if (out) out.innerHTML = `<div class="dr-derive-error">Enriched titles have no cast data with slugs.</div>`;
      } else {
        btn.textContent = `✗ Error (${r.status})`;
      }
      setTimeout(() => { btn.classList.remove('dr-btn-error'); btn.textContent = orig; btn.disabled = false; }, 3500);
    } catch (_) {
      if (_inspectorSeq !== seq) return;
      btn.classList.remove('dr-btn-busy');
      btn.classList.add('dr-btn-error');
      btn.textContent = '✗ Network error';
      setTimeout(() => { btn.classList.remove('dr-btn-error'); btn.textContent = orig; btn.disabled = false; }, 2500);
    }
  }

  // ── Conflicts sub-tab ─────────────────────────────────────────────

  async function renderConflictsTab(actressId, bodyEl, seq) {
    try {
      const res = await fetch(`/api/javdb/discovery/actresses/${actressId}/conflicts`);
      if (_inspectorSeq !== seq) return;
      if (!res.ok) { bodyEl.innerHTML = '<div class="dr-error">Failed to load conflicts.</div>'; return; }
      const rows = await res.json();
      if (rows.length === 0) {
        bodyEl.innerHTML = '<div class="dr-empty-tab">No conflicts — javdb cast matches for all enriched titles.</div>';
        return;
      }
      const slug = rows[0].ourJavdbSlug;
      const slugNote = slug
        ? `We are looking for slug <code class="dr-inline-code">${esc(slug)}</code> in the Discovery cast.`
        : `No Discovery profile slug on record — profile fetch may still be pending.`;
      bodyEl.innerHTML = `
        <div class="dr-conflict-explainer">
          <strong>${rows[0].ourActressName}</strong> is attributed to these titles in our library,
          but Discovery's enriched cast does not include her slug.
          ${slugNote}
        </div>
        <table class="dr-titles-table dr-conflicts-table">
          <thead><tr><th>Code</th><th>javdb Cast (name · slug)</th></tr></thead>
          <tbody>${rows.map(r => {
            const cast = parseCast(r.castJson);
            const castEntries = cast.length > 0
              ? cast.map(e => `<span class="dr-cast-entry">${esc(e.name)}<span class="dr-cast-slug"> · ${esc(e.slug ?? '?')}</span></span>`).join('')
              : '<span class="dr-muted">— (empty cast)</span>';
            const codeCell = r.coverUrl
              ? `<button class="dr-cover-link" data-cover-url="${esc(r.coverUrl)}" data-code="${esc(r.code)}">${esc(r.code)}</button>`
              : esc(r.code);
            return `<tr><td class="dr-code">${codeCell}</td><td class="dr-conflict-cast">${castEntries}</td></tr>`;
          }).join('')}</tbody>
        </table>`;
      wireCoverLinks(bodyEl);
    } catch (_) {
      if (_inspectorSeq !== seq) return;
      bodyEl.innerHTML = '<div class="dr-error">Network error.</div>';
    }
  }

  // ── Errors sub-tab ────────────────────────────────────────────────

  async function renderErrorsTab(actressId, bodyEl, seq) {
    try {
      const res = await fetch(`/api/javdb/discovery/actresses/${actressId}/errors`);
      if (_inspectorSeq !== seq) return;
      if (!res.ok) { bodyEl.innerHTML = '<div class="dr-error">Failed to load errors.</div>'; return; }
      const jobs = await res.json();
      if (jobs.length === 0) {
        bodyEl.innerHTML = '<div class="dr-empty-tab">No failed jobs.</div>';
        return;
      }
      bodyEl.innerHTML = '';
      const actionsBar = document.createElement('div');
      actionsBar.className = 'dr-errors-actions';
      const retryAllBtn = document.createElement('button');
      retryAllBtn.type = 'button';
      retryAllBtn.className = 'dr-btn';
      retryAllBtn.textContent = `Retry All (${jobs.length})`;
      retryAllBtn.addEventListener('click', async () => {
        if (_inspectorSeq !== seq) return;
        await fetch(`/api/javdb/discovery/actresses/${actressId}/retry`, { method: 'POST' });
        await doRefreshQueue();
        await renderErrorsTab(actressId, bodyEl, seq);
      });
      actionsBar.appendChild(retryAllBtn);
      bodyEl.appendChild(actionsBar);

      const list = document.createElement('ul');
      list.className = 'dr-errors-list';
      for (const job of jobs) {
        list.appendChild(makeErrorRow(job, list, actressId, seq));
      }
      bodyEl.appendChild(list);
    } catch (_) {
      if (_inspectorSeq !== seq) return;
      bodyEl.innerHTML = '<div class="dr-error">Network error.</div>';
    }
  }

  function makeErrorRow(job, list, actressId, seq) {
    const li = document.createElement('li');
    li.className = 'dr-error-row';

    let codeEl;
    if (job.coverUrl) {
      codeEl = document.createElement('button');
      codeEl.className = 'dr-error-code';
      codeEl.addEventListener('click', () => showCoverLightbox(job.coverUrl, job.titleCode || ''));
    } else {
      codeEl = document.createElement('span');
      codeEl.className = 'dr-error-code';
    }
    codeEl.textContent = job.titleCode || '(unknown)';

    const reasonEl = document.createElement('span');
    reasonEl.className = `dr-error-msg dr-error-reason-${esc(job.lastError || 'unknown')}`;
    reasonEl.textContent = errorReasonLabel(job.lastError);

    const acts = document.createElement('span');
    acts.className = 'dr-error-row-actions';

    if (job.titleId) {
      const retryBtn = document.createElement('button');
      retryBtn.type = 'button';
      retryBtn.className = 'dr-btn dr-btn-sm';
      retryBtn.textContent = 'Retry';
      retryBtn.addEventListener('click', async () => {
        if (_inspectorSeq !== seq) return;
        retryBtn.disabled = true; retryBtn.textContent = 'Retrying…';
        try {
          const r = await fetch(
            `/api/javdb/discovery/actresses/${actressId}/titles/${job.titleId}/reenrich`,
            { method: 'POST' }
          );
          if (r.ok) {
            li.style.opacity = '0.4';
            await Promise.all([doRefreshQueue(), renderErrorsTab(actressId, list.closest('.dr-inspector-tab-body') || list.parentElement, seq)]);
          } else {
            retryBtn.disabled = false; retryBtn.textContent = 'Retry';
          }
        } catch (_) {
          retryBtn.disabled = false; retryBtn.textContent = 'Retry';
        }
      });
      acts.appendChild(retryBtn);
    }

    if (job.lastError === 'ambiguous' && job.reviewQueueId) {
      const pickerBtn = document.createElement('button');
      pickerBtn.type = 'button';
      pickerBtn.className = 'dr-btn dr-btn-sm';
      pickerBtn.textContent = 'Open picker';
      pickerBtn.addEventListener('click', () => toggleErrorPicker(job, li, list, pickerBtn, actressId, seq));
      acts.appendChild(pickerBtn);
    }

    li.appendChild(codeEl);
    li.appendChild(reasonEl);
    li.appendChild(acts);
    return li;
  }

  // ── Error picker (inline drawer, B3) ─────────────────────────────

  function toggleErrorPicker(job, li, list, btn, actressId, seq) {
    const next = li.nextElementSibling;
    if (next && next.classList.contains('dr-error-picker-li')) {
      next.remove();
      btn.classList.remove('active');
      return;
    }
    list.querySelectorAll('.dr-error-picker-li').forEach(el => el.remove());
    list.querySelectorAll('.dr-btn.active').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');

    const pickerLi = document.createElement('li');
    pickerLi.className = 'dr-error-picker-li';
    const panelEl = document.createElement('div');
    panelEl.className = 'dr-picker-panel';
    pickerLi.appendChild(panelEl);
    li.insertAdjacentElement('afterend', pickerLi);

    let detail = null;
    try { detail = job.reviewDetail ? JSON.parse(job.reviewDetail) : null; } catch {}
    if (!detail || !detail.candidates || !detail.candidates.length) {
      renderPickerMissing(panelEl, job, li, pickerLi, actressId, seq);
    } else {
      renderPickerContent(panelEl, job, detail, li, pickerLi, actressId, seq);
    }
  }

  function renderPickerMissing(panelEl, job, li, pickerLi, actressId, seq) {
    panelEl.innerHTML = `
      <div class="dr-picker-missing">
        <span>Candidates not yet loaded.</span>
        <button type="button" class="dr-btn dr-btn-sm dr-picker-load-btn">Load candidates</button>
      </div>`;
    panelEl.querySelector('.dr-picker-load-btn').addEventListener('click', async () => {
      await doPickerRefresh(panelEl, job, li, pickerLi, actressId, seq);
    });
  }

  function renderPickerContent(panelEl, job, detail, li, pickerLi, actressId, seq) {
    const linkedSlugs = new Set(detail.linked_slugs || []);
    const age = detail.fetched_at ? formatRelative(detail.fetched_at) : '?';
    panelEl.innerHTML = '';

    const headerEl = document.createElement('div');
    headerEl.className = 'dr-picker-header';
    headerEl.innerHTML = `
      <span class="dr-picker-age">Candidates fetched ${esc(age)}</span>
      <button type="button" class="dr-btn dr-btn-sm dr-picker-refresh-btn">Refresh</button>`;
    panelEl.appendChild(headerEl);
    headerEl.querySelector('.dr-picker-refresh-btn').addEventListener('click', async () => {
      await doPickerRefresh(panelEl, job, li, pickerLi, actressId, seq);
    });

    const aiBanner = buildAiBanner(job, panelEl, detail, li, pickerLi, actressId, seq);
    if (aiBanner) panelEl.appendChild(aiBanner);

    const cardsEl = document.createElement('div');
    cardsEl.className = 'dr-picker-cards';
    if (job.coverUrl) cardsEl.appendChild(buildReferenceCard(job.coverUrl));
    detail.candidates.forEach(c => {
      cardsEl.appendChild(buildCandidateCard(job, c, linkedSlugs, li, pickerLi, actressId, seq));
    });
    panelEl.appendChild(cardsEl);

    const footerEl = document.createElement('div');
    footerEl.className = 'dr-picker-footer';
    const noneBtn = document.createElement('button');
    noneBtn.type = 'button';
    noneBtn.className = 'dr-btn dr-btn-sm';
    noneBtn.textContent = 'None of these (accept as gap)';
    noneBtn.addEventListener('click', async () => {
      await doPickerResolve(job.reviewQueueId, 'accepted_gap', li, pickerLi);
    });
    footerEl.appendChild(noneBtn);
    panelEl.appendChild(footerEl);
  }

  function buildReferenceCard(coverUrl) {
    const card = document.createElement('div');
    card.className = 'dr-picker-card dr-picker-reference-card';
    const cover = document.createElement('div');
    cover.className = 'dr-picker-cover';
    cover.style.cursor = 'zoom-in';
    const img = document.createElement('img');
    img.src = coverUrl; img.alt = ''; img.loading = 'lazy'; img.className = 'dr-picker-img';
    cover.appendChild(img);
    cover.addEventListener('click', () => showCoverLightbox(coverUrl, ''));
    card.appendChild(cover);
    const info = document.createElement('div');
    info.className = 'dr-picker-info';
    info.innerHTML = `<div class="dr-picker-title">Local cover</div>
                      <div class="dr-picker-meta">Match candidates against this</div>`;
    card.appendChild(info);
    return card;
  }

  function buildAiBanner(job, panelEl, detail, li, pickerLi, actressId, seq) {
    const conf = job.aiSuggestionConfidence;
    const at   = job.aiSuggestionAt;

    if (!at && !conf) {
      const banner = document.createElement('div');
      banner.className = 'dr-picker-ai-banner dr-picker-ai-banner-pending';
      const text = document.createElement('span');
      text.className = 'dr-picker-ai-banner-text';
      text.textContent = 'AI assist pending';
      banner.appendChild(text);
      const refreshBtn = document.createElement('button');
      refreshBtn.type = 'button';
      refreshBtn.className = 'dr-btn dr-btn-sm';
      refreshBtn.textContent = 'Refresh';
      refreshBtn.addEventListener('click', async () => {
        refreshBtn.disabled = true;
        try {
          const res = await fetch(`/api/utilities/review-queue/${job.reviewQueueId}/ai-suggestion`);
          if (!res.ok) { refreshBtn.disabled = false; return; }
          const data = await res.json();
          if (data?.at) {
            job.aiSuggestionSlug = data.slug;
            job.aiSuggestionConfidence = data.confidence;
            job.aiSuggestionReason = data.reason;
            job.aiSuggestionAt = data.at;
            renderPickerContent(panelEl, job, detail, li, pickerLi, actressId, seq);
          } else {
            refreshBtn.disabled = false;
          }
        } catch (_) { refreshBtn.disabled = false; }
      });
      banner.appendChild(refreshBtn);
      banner.appendChild(buildAiDismiss(banner));
      return banner;
    }
    if (conf === 'error' || !conf) return null;

    let modifier, textContent;
    const slug   = job.aiSuggestionSlug;
    const reason = job.aiSuggestionReason || '';
    switch (conf) {
      case 'agreed':       modifier = 'dr-picker-ai-banner-agreed';  textContent = `AI suggests: ${slug} (both agreed) — ${reason}`; break;
      case 'phi4_only':    modifier = 'dr-picker-ai-banner-single';  textContent = `AI suggests: ${slug} (phi4 only) — ${reason}`; break;
      case 'gemma_only':   modifier = 'dr-picker-ai-banner-single';  textContent = `AI suggests: ${slug} (gemma only) — ${reason}`; break;
      case 'conflict':     modifier = 'dr-picker-ai-banner-neutral'; textContent = `AI couldn't pick — models disagreed`; break;
      case 'both_abstain': modifier = 'dr-picker-ai-banner-neutral'; textContent = `AI abstained — both models couldn't pick`; break;
      default: return null;
    }
    const banner = document.createElement('div');
    banner.className = `dr-picker-ai-banner ${modifier}`;
    const text = document.createElement('span');
    text.className = 'dr-picker-ai-banner-text';
    text.textContent = textContent;
    banner.appendChild(text);
    banner.appendChild(buildAiDismiss(banner));
    return banner;
  }

  function buildAiDismiss(banner) {
    const btn = document.createElement('button');
    btn.type = 'button'; btn.className = 'dr-picker-ai-dismiss';
    btn.setAttribute('aria-label', 'Dismiss AI suggestion'); btn.textContent = '×';
    btn.addEventListener('click', () => { banner.style.display = 'none'; });
    return btn;
  }

  function buildCandidateCard(job, candidate, linkedSlugs, li, pickerLi, actressId, seq) {
    const card = document.createElement('div');
    card.className = 'dr-picker-card';

    const isAiPick = job.aiSuggestionSlug && candidate.slug === job.aiSuggestionSlug
      && ['agreed', 'phi4_only', 'gemma_only'].includes(job.aiSuggestionConfidence);
    if (isAiPick) {
      card.classList.add('dr-picker-card-ai-pick');
      const pill = document.createElement('span');
      pill.className = 'dr-ai-pick-pill';
      pill.textContent = job.aiSuggestionConfidence === 'agreed' ? 'AI pick ✓' : 'AI pick';
      if (job.aiSuggestionReason) pill.title = job.aiSuggestionReason;
      card.appendChild(pill);
    }

    const cover = document.createElement('div');
    cover.className = 'dr-picker-cover';
    if (candidate.cover_url) {
      const img = document.createElement('img');
      img.src = candidate.cover_url; img.alt = ''; img.loading = 'lazy'; img.className = 'dr-picker-img';
      cover.appendChild(img);
    } else {
      cover.innerHTML = '<div class="dr-picker-no-cover">No cover</div>';
    }
    card.appendChild(cover);

    const info = document.createElement('div');
    info.className = 'dr-picker-info';
    info.innerHTML += `<div class="dr-picker-title">${esc(candidate.title_original || '(no title)')}</div>`;
    info.innerHTML += `<div class="dr-picker-meta">${esc([candidate.release_date, candidate.maker].filter(Boolean).join(' · '))}</div>`;
    const castEl = document.createElement('div');
    castEl.className = 'dr-picker-cast';
    (candidate.cast || []).forEach(ce => {
      const span = document.createElement('span');
      span.className = 'dr-picker-cast-name' + (linkedSlugs.has(ce.slug) ? ' linked' : '');
      span.textContent = ce.name || ce.slug || '?';
      castEl.appendChild(span);
    });
    info.appendChild(castEl);

    const pickBtn = document.createElement('button');
    pickBtn.type = 'button'; pickBtn.className = 'dr-btn dr-btn-sm dr-picker-pick-btn';
    pickBtn.textContent = 'Pick this';
    pickBtn.addEventListener('click', async () => {
      pickBtn.disabled = true; pickBtn.textContent = 'Picking…';
      try {
        const res = await fetch(`/api/utilities/enrichment-review/queue/${job.reviewQueueId}/pick`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ slug: candidate.slug }),
        });
        const data = await res.json();
        if (!res.ok || !data.ok) {
          alert('Pick failed: ' + (data.error || data.message || res.statusText));
          pickBtn.disabled = false; pickBtn.textContent = 'Pick this';
        } else {
          pickerLi.remove();
          const actsEl = li.querySelector('.dr-error-row-actions');
          if (actsEl) {
            actsEl.innerHTML = '';
            const pill = document.createElement('span');
            pill.className = 'dr-error-resolved-pill';
            pill.textContent = '✓ Submitted — re-enriching…';
            actsEl.appendChild(pill);
          }
          li.style.opacity = '0.5';
          await doRefreshQueue();
        }
      } catch (err) {
        alert('Pick failed: ' + err.message);
        pickBtn.disabled = false; pickBtn.textContent = 'Pick this';
      }
    });
    info.appendChild(pickBtn);
    card.appendChild(info);
    return card;
  }

  async function doPickerRefresh(panelEl, job, li, pickerLi, actressId, seq) {
    const btn = panelEl.querySelector('.dr-picker-refresh-btn, .dr-picker-load-btn');
    if (btn) { btn.disabled = true; btn.textContent = 'Loading…'; }
    try {
      const res = await fetch(`/api/utilities/enrichment-review/queue/${job.reviewQueueId}/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
      });
      const data = await res.json();
      if (!res.ok || !data.ok) {
        alert('Refresh failed: ' + (data.error || data.message || res.statusText));
        if (btn) { btn.disabled = false; btn.textContent = 'Refresh candidates'; }
        return;
      }
      job.reviewDetail = data.detailJson;
      let freshDetail = null;
      try { freshDetail = data.detailJson ? JSON.parse(data.detailJson) : null; } catch {}
      panelEl.innerHTML = '';
      if (!freshDetail?.candidates?.length) {
        renderPickerMissing(panelEl, job, li, pickerLi, actressId, seq);
      } else {
        renderPickerContent(panelEl, job, freshDetail, li, pickerLi, actressId, seq);
      }
    } catch (err) {
      alert('Refresh failed: ' + err.message);
      if (btn) { btn.disabled = false; btn.textContent = 'Refresh candidates'; }
    }
  }

  async function doPickerResolve(reviewQueueId, resolution, li, pickerLi) {
    try {
      const res = await fetch(`/api/utilities/enrichment-review/queue/${reviewQueueId}/resolve`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ resolution }),
      });
      const data = await res.json();
      if (!res.ok || !data.ok) { alert('Resolve failed: ' + (data.error || data.message || res.statusText)); return; }
      pickerLi.remove(); li.remove();
    } catch (err) {
      alert('Resolve failed: ' + err.message);
    }
  }

  // ── formatRelative helper (local copy to avoid re-importing) ──────

  function formatRelative(isoStr) {
    if (!isoStr) return '—';
    try {
      const diff = Date.now() - new Date(isoStr).getTime();
      const days = Math.floor(diff / 86400000);
      if (days === 0) return 'Today';
      if (days === 1) return 'Yesterday';
      if (days < 30)  return `${days}d ago`;
      if (days < 365) return `${Math.floor(days / 30)}mo ago`;
      return `${Math.floor(days / 365)}y ago`;
    } catch { return isoStr; }
  }

  // ── Actress-level actions ─────────────────────────────────────────

  async function enrichActress(actressId, btn) {
    const orig = btn.textContent;
    btn.disabled = true; btn.textContent = 'Enqueueing…';
    try {
      const res = await fetch(`/api/javdb/discovery/actresses/${actressId}/enqueue`, { method: 'POST' });
      if (res.ok) {
        const { enqueued } = await res.json();
        btn.textContent = `Enqueued ${enqueued} ✓`;
        await doRefreshQueue();
        await load();
        if (titlesHandle) await titlesHandle.refresh();
        setTimeout(() => { btn.textContent = orig; btn.disabled = false; }, 1500);
        return;
      }
    } catch (_) { /* fall through */ }
    btn.textContent = orig; btn.disabled = false;
  }

  async function cancelActress(actressId) {
    await fetch(`/api/javdb/discovery/actresses/${actressId}/queue`, { method: 'DELETE' });
    await doRefreshQueue();
  }

  // ── Data load ─────────────────────────────────────────────────────

  async function load() {
    pivotState.loading = true;
    try {
      const res = await fetch('/api/javdb/discovery/actresses');
      if (res.ok) {
        pivotState.rows = await res.json();
        alphaBuckets = computeAlphaBuckets(pivotState.rows);
        if (!alphaBuckets.some(b => b.key === pivotState.alphaFilter)) {
          pivotState.alphaFilter = 'All';
        }
      }
    } catch (_) { /* ignore */ }
    pivotState.loading = false;
    renderAlphaBar();
    renderFilterBar();
    renderSortBar();
    renderActressList();
    // Auto-select actress from ?id= URL param on first load.
    if (initialActressId != null) {
      const id = initialActressId;
      initialActressId = null; // consume once — don't re-trigger on pivot re-mounts
      await navigateToActress(id, initialPanel || 'titles');
    }
  }

  // ── Public API ────────────────────────────────────────────────────

  /**
   * Navigate directly to an actress by ID (used from queue-dock actress links).
   */
  async function navigateToActress(id, tab) {
    // Ensure the actress is in the unfiltered list; reset filters if not.
    const inList = pivotState.rows.some(a => a.id === id);
    if (!inList) {
      pivotState.alphaFilter = 'All';
      pivotState.tierFilter = new Set();
      pivotState.favoritesOnly = false;
      pivotState.bookmarkedOnly = false;
      renderAlphaBar();
      renderFilterBar();
      renderSortBar();
    }
    selection.clear();
    selection.add(id);
    renderActressList();
    onSelectionChange([id]);
    activeInspectorId = null; // force refresh
    activeInspectorTab = tab || 'titles';
    doUrlChange({ id, panel: activeInspectorTab });
    await showActressInspector(id, activeInspectorTab);
    // Scroll into view.
    const li = actressListEl.querySelector(`.dr-actress-item[data-id="${id}"]`);
    li?.scrollIntoView({ block: 'nearest' });
  }

  function destroy() {
    containerEl.innerHTML = '';
  }

  // ── Init ──────────────────────────────────────────────────────────

  // If we already have rows (re-mount), just render.
  if (pivotState.rows.length > 0) {
    alphaBuckets = computeAlphaBuckets(pivotState.rows);
    renderAlphaBar();
    renderFilterBar();
    renderSortBar();
    renderActressList();
    if (initialActressId) {
      navigateToActress(initialActressId, initialPanel || 'titles');
    }
  }

  return { load, navigateToActress, destroy };
}
