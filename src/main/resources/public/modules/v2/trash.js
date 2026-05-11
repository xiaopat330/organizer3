/* ─────────────────────────────────────────────────────────────────────
   Wave 3 — Trash (workbench mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md (workbench surfaces sweep)

   Per-volume table of trashed items.
   Features:
     - All-volumes aggregation via volume picker
     - Bulk select (checkbox column + check-all + action toolbar)
     - Schedule deletion (10-day countdown, SVG ring, SSE task trash.schedule)
     - Unschedule deletion (SSE task trash.unschedule)
     - Restore (SSE task trash.restore; warns to re-sync after)
     - Pagination (prev/next, disabled in All-volumes aggregate mode)
     - Per-volume count badges via volume picker
     - Scheduled-status ring: yellow→red hue + arc fill over 10-day window
     - lastDeletionError pill with title tooltip
   ───────────────────────────────────────────────────────────────────── */
import { createVolumePicker } from './volume-picker.js';

const PAGE_SIZE = 50;

// ── Helpers ───────────────────────────────────────────────────────────

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[c]));
}

async function fetchJson(url, fallback = null) {
  try {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) return fallback;
    return await r.json();
  } catch (e) {
    console.warn('[trash] fetch failed:', url, e);
    return fallback;
  }
}

function timeAgo(iso) {
  if (!iso) return '—';
  const then = new Date(iso).getTime();
  const diff = (Date.now() - then) / 1000;
  if (diff < 0)         return new Date(iso).toLocaleString();
  if (diff < 60)        return `${Math.floor(diff)}s ago`;
  if (diff < 3600)      return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400)     return `${Math.floor(diff / 3600)}h ago`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)}d ago`;
  return new Date(iso).toLocaleDateString();
}

function shortPath(p) {
  if (!p) return '';
  const parts = p.split('/').filter(Boolean);
  return parts.length <= 2 ? p : '…/' + parts.slice(-2).join('/');
}

// ── Scheduled-status ring (mirrors legacy utilities-trash.js) ─────────
// 10-day window; arc fills day-by-day; hue slides yellow→red.
const SCHEDULE_DAYS = 10;
const RING_R        = 6;
const RING_CIRC     = 2 * Math.PI * RING_R;

function scheduledStatusHtml(scheduledAt) {
  const deadline     = new Date(scheduledAt).getTime();
  const now          = Date.now();
  const daysRemaining = (deadline - now) / 86400000;

  // progress 0→1 over the 10-day window (clamped)
  const progress = Math.min(1, Math.max(0, (SCHEDULE_DAYS - daysRemaining) / SCHEDULE_DAYS));
  // hue: 45 (yellow) → 0 (red)
  const hue   = Math.round(45 * (1 - progress));
  const color = `hsl(${hue}, 90%, 58%)`;
  const offset = RING_CIRC * (1 - progress);

  const ring = `<svg class="tr-ring" viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">
    <circle cx="8" cy="8" r="${RING_R}" stroke="rgba(255,255,255,.12)" stroke-width="2.5"/>
    <circle cx="8" cy="8" r="${RING_R}" stroke="${color}" stroke-width="2.5"
      stroke-dasharray="${RING_CIRC.toFixed(2)}" stroke-dashoffset="${offset.toFixed(2)}"
      stroke-linecap="round" transform="rotate(-90 8 8)"/>
  </svg>`;

  const label = daysRemaining <= 0
    ? 'Overdue'
    : daysRemaining < 1
      ? 'Today'
      : new Date(scheduledAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });

  return `<span class="tr-status-scheduled" style="color:${color}">${ring}${label}</span>`;
}

function statusCellHtml(item) {
  if (item.lastDeletionError) {
    return `<span class="pill error" title="${esc(item.lastDeletionError)}">failed</span>`;
  }
  if (item.scheduledDeletionAt) {
    return scheduledStatusHtml(item.scheduledDeletionAt);
  }
  return `<span class="pill tr-pill-held">held</span>`;
}

// ── Row HTML ──────────────────────────────────────────────────────────
function rowHtml(item, idx) {
  return `
    <tr data-sidecar="${esc(item.sidecarPath)}" data-volume="${esc(item.volumeId)}" data-idx="${idx}">
      <td class="tr-col-check">
        <input type="checkbox" class="tr-row-check" data-idx="${idx}" aria-label="Select row">
      </td>
      <td>${statusCellHtml(item)}</td>
      <td class="mono">${esc(item.volumeId)}</td>
      <td class="mono truncate" title="${esc(item.originalPath)}">${esc(shortPath(item.originalPath))}</td>
      <td>${esc(item.reason || '—')}</td>
      <td class="mono">${esc(timeAgo(item.trashedAt))}</td>
      <td class="mono tr-col-sweep">${item.scheduledDeletionAt
          ? `<span title="${esc(new Date(item.scheduledDeletionAt).toLocaleString())}">
               ${esc(new Date(item.scheduledDeletionAt).toLocaleDateString())}</span>`
          : '—'
        }</td>
    </tr>
  `;
}

// ── Task runner (SSE) ─────────────────────────────────────────────────
async function runTask(taskId, body) {
  const res = await fetch(`/api/utilities/tasks/${encodeURIComponent(taskId)}/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (res.status === 409) {
    const err = await res.json().catch(() => ({}));
    throw new Error(`Another task is already running: ${err.runningTaskId || '?'}`);
  }
  if (!res.ok) {
    const err = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(err.error || res.statusText);
  }
  const { runId } = await res.json();
  await awaitRun(runId);
}

function awaitRun(runId) {
  return new Promise((resolve) => {
    const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
    es.addEventListener('task.ended', () => { es.close(); resolve(); });
    es.addEventListener('error', () => { es.close(); resolve(); });
  });
}

// ── Confirm dialog helper ─────────────────────────────────────────────
// Returns a cleanup fn. Resolves with true on confirm, false on cancel.
function showConfirmDialog(rootEl, { title, body, confirmLabel = 'Confirm', danger = false }) {
  return new Promise((resolve) => {
    const overlay = document.createElement('div');
    overlay.className = 'tr-overlay';
    overlay.innerHTML = `
      <div class="tr-dialog" role="dialog" aria-modal="true">
        <div class="tr-dialog-title">${esc(title)}</div>
        <div class="tr-dialog-body">${esc(body)}</div>
        <div class="tr-dialog-actions">
          <button class="btn sm" id="tr-dlg-cancel">Cancel</button>
          <button class="btn sm${danger ? ' danger' : ' primary'}" id="tr-dlg-confirm">${esc(confirmLabel)}</button>
        </div>
      </div>
    `;
    const dismiss = (result) => {
      overlay.remove();
      resolve(result);
    };
    overlay.addEventListener('click', e => { if (e.target === overlay) dismiss(false); });
    overlay.querySelector('#tr-dlg-cancel').addEventListener('click',  () => dismiss(false));
    overlay.querySelector('#tr-dlg-confirm').addEventListener('click', () => dismiss(true));
    document.addEventListener('keydown', function onKey(e) {
      if (e.key === 'Escape') { document.removeEventListener('keydown', onKey); dismiss(false); }
    });
    rootEl.appendChild(overlay);
  });
}

// ── Main mount ────────────────────────────────────────────────────────
export async function mountTrash(rootEl) {
  rootEl.innerHTML = `
    <div class="wb-page tr-page">
      <h1 class="wb-page-title">Trash</h1>
      <div class="wb-page-subtitle">Items pending deletion. Restore puts them back at their original path; re-sync the volume after restore to reindex.</div>

      <div class="tr-filter-bar">
        <div class="filter-group">
          <span class="filter-label">Volume:</span>
          <div id="vol-picker"></div>
        </div>
        <div class="filter-spacer"></div>
        <div class="filter-meta" id="result-meta"></div>
      </div>

      <div class="tr-toolbar" id="tr-toolbar" style="display:none">
        <span class="tr-toolbar-sel" id="tr-sel-label"></span>
        <button class="btn sm" id="btn-restore"   disabled>Restore</button>
        <button class="btn sm" id="btn-schedule"  disabled>Schedule deletion</button>
        <button class="btn sm" id="btn-unschedule" disabled>Unschedule</button>
      </div>

      <div class="wb-table-wrap">
        <table class="wb-table">
          <thead><tr>
            <th class="tr-col-check"><input type="checkbox" id="tr-check-all" aria-label="Select all"></th>
            <th style="width:100px">Status</th>
            <th style="width:100px">Volume</th>
            <th>Original path</th>
            <th style="width:140px">Reason</th>
            <th style="width:90px">Trashed</th>
            <th style="width:120px">Scheduled</th>
          </tr></thead>
          <tbody id="rows"></tbody>
        </table>
      </div>

      <div class="tr-pagination" id="tr-pagination" style="display:none">
        <button class="btn sm" id="tr-prev">← Prev</button>
        <span class="tr-page-label" id="tr-page-label"></span>
        <button class="btn sm" id="tr-next">Next →</button>
      </div>

      <div class="grid-status" id="grid-status"></div>
    </div>
  `;

  const tbody      = rootEl.querySelector('#rows');
  const meta       = rootEl.querySelector('#result-meta');
  const statusEl   = rootEl.querySelector('#grid-status');
  const toolbar    = rootEl.querySelector('#tr-toolbar');
  const selLabel   = rootEl.querySelector('#tr-sel-label');
  const checkAll   = rootEl.querySelector('#tr-check-all');
  const btnRestore = rootEl.querySelector('#btn-restore');
  const btnSched   = rootEl.querySelector('#btn-schedule');
  const btnUnsched = rootEl.querySelector('#btn-unschedule');
  const pagination = rootEl.querySelector('#tr-pagination');
  const prevBtn    = rootEl.querySelector('#tr-prev');
  const nextBtn    = rootEl.querySelector('#tr-next');
  const pageLabel  = rootEl.querySelector('#tr-page-label');

  // Current-page items (for checkbox lookups)
  let pageItems = [];

  const state = {
    volumeId: null,       // '' = all volumes
    page: 0,
    total: 0,
    isAllMode: false,     // true when volumeId is ''
  };

  // Volume list for "All" aggregation
  let allVolumeIds = [];

  // ── Data fetching ─────────────────────────────────────────────────

  const fetchVolumeItems = async (vol, page = 0) => {
    const url = `/api/utilities/trash/volumes/${encodeURIComponent(vol)}/items?page=${page}&pageSize=${PAGE_SIZE}`;
    const data = await fetchJson(url, { items: [], totalCount: 0 });
    return {
      vol,
      items: data.items || [],
      total: data.totalCount ?? (data.items?.length ?? 0),
      pageCount: Math.ceil((data.totalCount || 0) / PAGE_SIZE),
      page: data.page ?? page,
      pageSize: data.pageSize ?? PAGE_SIZE,
    };
  };

  const load = async () => {
    tbody.innerHTML = '';
    statusEl.innerHTML = '<div class="shelf-loading">Loading…</div>';
    meta.textContent = '';
    checkAll.checked = false;
    checkAll.indeterminate = false;
    updateToolbar([]);
    pagination.style.display = 'none';

    let items, total, totalPages = 1, currentPage = 0;

    if (!state.volumeId) {
      // "All volumes" — fan out (no server-side pagination in this mode)
      state.isAllMode = true;
      const results = await Promise.all(allVolumeIds.map(v => fetchVolumeItems(v, 0)));
      items = results.flatMap(r => r.items);
      total = results.reduce((s, r) => s + r.total, 0);
      // Most-recently trashed first across volumes
      items.sort((a, b) => (b.trashedAt || '').localeCompare(a.trashedAt || ''));
    } else {
      // Single volume — use server pagination
      state.isAllMode = false;
      const r = await fetchVolumeItems(state.volumeId, state.page);
      items = r.items;
      total = r.total;
      totalPages = r.pageCount;
      currentPage = r.page;
    }

    pageItems = items;
    state.total = total;

    if (items.length === 0) {
      const emptyMsg = state.volumeId
        ? `No items pending deletion on ${esc(state.volumeId)}.`
        : 'Trash is empty.';
      tbody.innerHTML = `<tr><td colspan="7"><div class="dis-empty">◌<br>${emptyMsg}</div></td></tr>`;
      statusEl.innerHTML = '';
      return;
    }

    tbody.innerHTML = items.map((item, idx) => rowHtml(item, idx)).join('');
    statusEl.innerHTML = '';

    const scope = state.volumeId || 'all volumes';
    meta.textContent = `${items.length} of ${total} item${total !== 1 ? 's' : ''} · ${scope}`;

    // Pagination (single-volume mode only)
    if (!state.isAllMode && totalPages > 1) {
      pagination.style.display = 'flex';
      pageLabel.textContent = `Page ${currentPage + 1} of ${totalPages}`;
      prevBtn.disabled = currentPage === 0;
      nextBtn.disabled = currentPage >= totalPages - 1;
    }

    // Wire up checkbox listeners
    tbody.querySelectorAll('.tr-row-check').forEach(cb => {
      cb.addEventListener('change', onRowCheckChange);
    });
  };

  // ── Bulk-select machinery ─────────────────────────────────────────

  function getCheckedItems() {
    const checked = [];
    tbody.querySelectorAll('.tr-row-check:checked').forEach(cb => {
      const idx = parseInt(cb.dataset.idx, 10);
      if (pageItems[idx]) checked.push(pageItems[idx]);
    });
    return checked;
  }

  function updateToolbar(checkedItems) {
    const count = checkedItems.length;
    if (count === 0) {
      toolbar.style.display = 'none';
    } else {
      toolbar.style.display = 'flex';
      selLabel.textContent = `${count} selected`;
    }
    btnRestore.disabled  = count === 0;
    btnSched.disabled    = count === 0;
    const hasScheduled = checkedItems.some(it => !!it.scheduledDeletionAt);
    btnUnsched.disabled  = !hasScheduled;
  }

  function onRowCheckChange() {
    const allBoxes     = Array.from(tbody.querySelectorAll('.tr-row-check'));
    const checkedBoxes = allBoxes.filter(cb => cb.checked);
    checkAll.checked       = checkedBoxes.length === allBoxes.length && allBoxes.length > 0;
    checkAll.indeterminate = checkedBoxes.length > 0 && checkedBoxes.length < allBoxes.length;
    updateToolbar(getCheckedItems());
  }

  checkAll.addEventListener('change', () => {
    tbody.querySelectorAll('.tr-row-check').forEach(cb => { cb.checked = checkAll.checked; });
    checkAll.indeterminate = false;
    updateToolbar(getCheckedItems());
  });

  // ── Pagination ────────────────────────────────────────────────────

  prevBtn.addEventListener('click', () => {
    if (state.page > 0) { state.page--; load(); }
  });
  nextBtn.addEventListener('click', () => {
    const totalPages = Math.ceil(state.total / PAGE_SIZE);
    if (state.page < totalPages - 1) { state.page++; load(); }
  });

  // ── Action helpers ────────────────────────────────────────────────

  async function runAndReload(taskId, body) {
    try {
      await runTask(taskId, body);
      // Reset selection state after task
      checkAll.checked = false;
      checkAll.indeterminate = false;
      await load();
    } catch (err) {
      console.error('[trash] task error', err);
      alert(`Task failed: ${err.message}`);
    }
  }

  // ── Schedule ──────────────────────────────────────────────────────

  btnSched.addEventListener('click', async () => {
    const items = getCheckedItems();
    if (items.length === 0) return;

    const deletionDate = new Date(Date.now() + SCHEDULE_DAYS * 24 * 3600 * 1000);
    const dateStr = deletionDate.toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });

    const confirmed = await showConfirmDialog(rootEl, {
      title: 'Schedule for deletion',
      body: `Schedule ${items.length} item${items.length !== 1 ? 's' : ''} for permanent deletion on or after ${dateStr}?`,
      confirmLabel: 'Schedule',
      danger: true,
    });
    if (!confirmed) return;

    const scheduledAt = deletionDate.toISOString();

    // Group by volume for the task body — all volumes in one request if single-volume mode,
    // otherwise group per-volume (task API expects volumeId + sidecarPaths).
    if (!state.isAllMode) {
      await runAndReload('trash.schedule', {
        volumeId: state.volumeId,
        sidecarPaths: items.map(it => it.sidecarPath),
        scheduledAt,
      });
    } else {
      // All-volumes mode: group and run one task per affected volume sequentially
      const byVolume = groupByVolume(items);
      for (const [vol, sidecarPaths] of Object.entries(byVolume)) {
        try {
          await runTask('trash.schedule', { volumeId: vol, sidecarPaths, scheduledAt });
        } catch (err) {
          console.error('[trash] schedule error for', vol, err);
          alert(`Schedule failed for ${vol}: ${err.message}`);
          break;
        }
      }
      checkAll.checked = false;
      checkAll.indeterminate = false;
      await load();
    }
  });

  // ── Unschedule ────────────────────────────────────────────────────

  btnUnsched.addEventListener('click', async () => {
    const items = getCheckedItems().filter(it => !!it.scheduledDeletionAt);
    if (items.length === 0) return;

    const confirmed = await showConfirmDialog(rootEl, {
      title: 'Remove deletion schedule',
      body: `Remove deletion schedule from ${items.length} item${items.length !== 1 ? 's' : ''}? They will remain in trash but won't be automatically deleted.`,
      confirmLabel: 'Unschedule',
    });
    if (!confirmed) return;

    if (!state.isAllMode) {
      await runAndReload('trash.unschedule', {
        volumeId: state.volumeId,
        sidecarPaths: items.map(it => it.sidecarPath),
      });
    } else {
      const byVolume = groupByVolume(items);
      for (const [vol, sidecarPaths] of Object.entries(byVolume)) {
        try {
          await runTask('trash.unschedule', { volumeId: vol, sidecarPaths });
        } catch (err) {
          console.error('[trash] unschedule error for', vol, err);
          alert(`Unschedule failed for ${vol}: ${err.message}`);
          break;
        }
      }
      checkAll.checked = false;
      checkAll.indeterminate = false;
      await load();
    }
  });

  // ── Restore ───────────────────────────────────────────────────────

  btnRestore.addEventListener('click', async () => {
    const items = getCheckedItems();
    if (items.length === 0) return;

    const confirmed = await showConfirmDialog(rootEl, {
      title: 'Restore items',
      body: `Restore ${items.length} item${items.length !== 1 ? 's' : ''} to their original paths? The database will not be updated — re-sync the volume after restore to reindex.`,
      confirmLabel: 'Restore',
    });
    if (!confirmed) return;

    if (!state.isAllMode) {
      await runAndReload('trash.restore', {
        volumeId: state.volumeId,
        sidecarPaths: items.map(it => it.sidecarPath),
      });
    } else {
      const byVolume = groupByVolume(items);
      for (const [vol, sidecarPaths] of Object.entries(byVolume)) {
        try {
          await runTask('trash.restore', { volumeId: vol, sidecarPaths });
        } catch (err) {
          console.error('[trash] restore error for', vol, err);
          alert(`Restore failed for ${vol}: ${err.message}`);
          break;
        }
      }
      checkAll.checked = false;
      checkAll.indeterminate = false;
      await load();
    }
  });

  // ── Volume grouping (for all-volumes bulk ops) ─────────────────────

  function groupByVolume(items) {
    const byVol = {};
    for (const item of items) {
      const vol = item.volumeId;
      if (!byVol[vol]) byVol[vol] = [];
      byVol[vol].push(item.sidecarPath);
    }
    return byVol;
  }

  // ── Volume picker init ─────────────────────────────────────────────

  // Pre-fetch volumes for "All" aggregation.
  const vols = await fetchJson('/api/utilities/trash/volumes', []);
  allVolumeIds = (vols || []).map(v => v.id || v.volumeId || v).filter(Boolean);

  await createVolumePicker({
    rootEl: rootEl.querySelector('#vol-picker'),
    storageKey: 'v2.trash.volume',
    allLabel: 'All volumes',
    volumesUrl: '/api/utilities/trash/volumes',
    getCount: async (id) => {
      const c = await fetchJson(`/api/utilities/trash/volumes/${encodeURIComponent(id)}/count`, { count: null });
      return c?.count;
    },
    onChange: (vol) => {
      state.volumeId = vol || '';
      state.page = 0;
      load();
    },
  });
}
