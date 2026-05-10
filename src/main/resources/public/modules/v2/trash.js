/* ─────────────────────────────────────────────────────────────────────
   Wave 3 — Trash (workbench mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md (workbench surfaces sweep)
   Per-volume table of trashed items with Restore action.
   Schedule-deletion + bulk-select deferred (need date picker + modal).
   ───────────────────────────────────────────────────────────────────── */

const PAGE_SIZE = 50;

function escapeHtml(s) {
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
  if (!iso) return '';
  const then = new Date(iso).getTime();
  const diff = (Date.now() - then) / 1000;
  if (diff < 0)         return new Date(iso).toLocaleString();
  if (diff < 60)        return `${Math.floor(diff)}s ago`;
  if (diff < 3600)      return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400)     return `${Math.floor(diff / 3600)}h ago`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)}d ago`;
  return new Date(iso).toLocaleDateString();
}

function timeUntil(iso) {
  if (!iso) return '—';
  const then = new Date(iso).getTime();
  const diff = (then - Date.now()) / 1000;
  if (diff <= 0) return 'overdue';
  if (diff < 3600)      return `in ${Math.floor(diff / 60)}m`;
  if (diff < 86400)     return `in ${Math.floor(diff / 3600)}h`;
  return `in ${Math.floor(diff / 86400)}d`;
}

function shortPath(p) {
  if (!p) return '';
  // Show last 2 path segments
  const parts = p.split('/').filter(Boolean);
  return parts.length <= 2 ? p : '…/' + parts.slice(-2).join('/');
}

function rowHtml(item) {
  const failed = !!item.lastDeletionError;
  const status = failed ? `<span class="pill error" title="${escapeHtml(item.lastDeletionError)}">failed</span>`
                        : (item.scheduledDeletionAt ? `<span class="pill warn">scheduled</span>` : `<span class="pill">held</span>`);
  return `
    <tr data-sidecar="${escapeHtml(item.sidecarPath)}" data-volume="${escapeHtml(item.volumeId)}">
      <td>${status}</td>
      <td class="mono truncate" title="${escapeHtml(item.originalPath)}">${escapeHtml(shortPath(item.originalPath))}</td>
      <td>${escapeHtml(item.reason || '—')}</td>
      <td class="mono">${escapeHtml(timeAgo(item.trashedAt))}</td>
      <td class="mono">${escapeHtml(timeUntil(item.scheduledDeletionAt))}</td>
      <td class="actions">
        <button class="btn sm" data-act="restore">Restore</button>
      </td>
    </tr>
  `;
}

async function restoreItem(volumeId, sidecarPath) {
  const r = await fetch('/api/utilities/trash/restore', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ volumeId, sidecarPaths: [sidecarPath] }),
  });
  return r.ok;
}

export async function mountTrash(rootEl) {
  rootEl.innerHTML = `
    <div class="wb-page">
      <h1 class="wb-page-title">Trash</h1>
      <div class="wb-page-subtitle">Items pending deletion. Restore puts them back at their original path.</div>

      <div class="filter-bar">
        <div class="filter-group" id="vol-chips">
          <span class="shelf-loading">Loading volumes…</span>
        </div>
        <div class="filter-spacer"></div>
        <div class="filter-meta" id="result-meta"></div>
      </div>

      <div class="wb-table-wrap">
        <table class="wb-table">
          <thead><tr>
            <th style="width:90px">Status</th>
            <th>Original path</th>
            <th style="width:140px">Reason</th>
            <th style="width:90px">Trashed</th>
            <th style="width:100px">Sweep</th>
            <th style="width:90px"></th>
          </tr></thead>
          <tbody id="rows"></tbody>
        </table>
      </div>

      <div class="grid-status" id="grid-status"></div>
    </div>
  `;

  const volChips = rootEl.querySelector('#vol-chips');
  const tbody    = rootEl.querySelector('#rows');
  const meta     = rootEl.querySelector('#result-meta');
  const status   = rootEl.querySelector('#grid-status');

  const state = { volumeId: null, page: 0, total: 0 };

  // Load volumes that have a trash dir, then auto-select first
  const vols = await fetchJson('/api/utilities/trash/volumes', []);
  if (!vols || vols.length === 0) {
    volChips.innerHTML = `<span class="filter-label">No volumes with trash.</span>`;
    return;
  }
  volChips.innerHTML = vols.map((v, i) => {
    const id = v.id || v.volumeId || v;
    return `<span class="chip${i === 0 ? ' on' : ''}" data-vol="${escapeHtml(id)}">${escapeHtml(id)}</span>`;
  }).join('');
  state.volumeId = vols[0].id || vols[0].volumeId || vols[0];

  const load = async () => {
    tbody.innerHTML = '';
    status.innerHTML = '<div class="shelf-loading">Loading…</div>';
    meta.textContent = '';

    const url = `/api/utilities/trash/volumes/${encodeURIComponent(state.volumeId)}/items?page=${state.page}&pageSize=${PAGE_SIZE}`;
    const data = await fetchJson(url, { items: [], totalCount: 0 });
    const items = data.items || [];
    state.total = data.totalCount ?? items.length;

    if (items.length === 0) {
      tbody.innerHTML = `<tr><td colspan="6"><div class="empty-state">
        <div class="empty-state-title">Trash is empty</div>
        <div class="empty-state-body">No items pending deletion on <code>${escapeHtml(state.volumeId)}</code>.</div>
      </div></td></tr>`;
      status.innerHTML = '';
      return;
    }

    tbody.innerHTML = items.map(rowHtml).join('');
    status.innerHTML = '';
    meta.textContent = `${items.length} of ${state.total} items`;
  };

  // Volume chip click
  volChips.addEventListener('click', (e) => {
    const chip = e.target.closest('.chip');
    if (!chip) return;
    volChips.querySelectorAll('.chip').forEach(c => c.classList.remove('on'));
    chip.classList.add('on');
    state.volumeId = chip.dataset.vol;
    state.page = 0;
    load();
  });

  // Restore button delegation
  tbody.addEventListener('click', async (e) => {
    const btn = e.target.closest('button[data-act="restore"]');
    if (!btn) return;
    const tr = btn.closest('tr');
    const sidecar = tr?.dataset.sidecar;
    const vol     = tr?.dataset.volume;
    if (!sidecar || !vol) return;
    btn.disabled = true;
    btn.textContent = '…';
    const ok = await restoreItem(vol, sidecar);
    if (ok) {
      tr.style.opacity = '0.4';
      tr.style.pointerEvents = 'none';
      btn.textContent = 'Restored';
    } else {
      btn.disabled = false;
      btn.textContent = 'Restore';
      alert('Restore failed — see logs.');
    }
  });

  load();
}
