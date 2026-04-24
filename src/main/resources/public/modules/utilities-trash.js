// Utilities → Trash screen.
// Two-pane layout: left volume list, right paginated trash table.
// Supports Schedule for Deletion and Restore operations via SSE-backed tasks.

import { esc } from './utils.js';

// ── Volume tile helpers (mirrors utilities-volumes.js) ────────────────────
const VOLUME_HUES = [
  '#60a5fa', '#4ade80', '#fbbf24', '#f472b6', '#a78bfa',
  '#34d399', '#fb923c', '#22d3ee', '#e879f9', '#facc15',
  '#2dd4bf', '#f87171',
];
function hueFor(id) {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) | 0;
  return VOLUME_HUES[Math.abs(h) % VOLUME_HUES.length];
}
function diskIconSVG(color) {
  return `<svg class="vol-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="${color}" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
    <ellipse cx="12" cy="5" rx="9" ry="3"/>
    <path d="M3 5v6c0 1.66 4 3 9 3s9-1.34 9-3V5"/>
    <path d="M3 11v6c0 1.66 4 3 9 3s9-1.34 9-3v-6"/>
  </svg>`;
}

// ── DOM refs ──────────────────────────────────────────────────────────────
const trashView         = document.getElementById('tools-trash-view');
const volumeList        = document.getElementById('trash-volume-list');
const trashEmpty        = document.getElementById('trash-empty');
const trashContent      = document.getElementById('trash-content');
const countLabel        = document.getElementById('trash-count-label');
const scheduleBtn       = document.getElementById('trash-schedule-btn');
const restoreBtn        = document.getElementById('trash-restore-btn');
const tableBody         = document.getElementById('trash-table-body');
const checkAll          = document.getElementById('trash-check-all');
const pagination        = document.getElementById('trash-pagination');
const prevBtn           = document.getElementById('trash-prev-btn');
const nextBtn           = document.getElementById('trash-next-btn');
const pageLabel         = document.getElementById('trash-page-label');

const unscheduleBtn     = document.getElementById('trash-unschedule-btn');

const scheduleDialog    = document.getElementById('trash-schedule-dialog');
const scheduleBody      = document.getElementById('trash-schedule-body');
const scheduleCancelBtn = document.getElementById('trash-schedule-cancel-btn');
const scheduleConfirmBtn= document.getElementById('trash-schedule-confirm-btn');

const unscheduleDialog      = document.getElementById('trash-unschedule-dialog');
const unscheduleBody        = document.getElementById('trash-unschedule-body');
const unscheduleCancelBtn   = document.getElementById('trash-unschedule-cancel-btn');
const unscheduleConfirmBtn  = document.getElementById('trash-unschedule-confirm-btn');

const restoreDialog     = document.getElementById('trash-restore-dialog');
const restoreBody       = document.getElementById('trash-restore-body');
const restoreCancelBtn  = document.getElementById('trash-restore-cancel-btn');
const restoreConfirmBtn = document.getElementById('trash-restore-confirm-btn');

// ── State ─────────────────────────────────────────────────────────────────
let state = {
  selectedVolumeId: null,
  page: 0,
  pageSize: 50,
  totalCount: 0,
  items: [],      // current page items from the server
};

// ── Public API ────────────────────────────────────────────────────────────
export function showTrashView() {
  trashView.style.display = 'flex';
  loadVolumes();
}

export function hideTrashView() {
  trashView.style.display = 'none';
}

// ── Volume list ───────────────────────────────────────────────────────────
async function loadVolumes() {
  try {
    const res = await fetch('/api/utilities/trash/volumes');
    if (!res.ok) throw new Error(await res.text());
    const volumes = await res.json();
    renderVolumeList(volumes);
  } catch (err) {
    volumeList.innerHTML = `<li class="trash-volume-error">Failed to load volumes</li>`;
    console.error('Trash: failed to load volumes', err);
  }
}

function renderVolumeList(volumes) {
  volumeList.innerHTML = '';
  volumes.forEach(v => {
    const li = document.createElement('li');
    li.className = 'trash-volume-item';
    li.dataset.volumeId = v.id;
    const color = hueFor(v.id);
    li.innerHTML = `
      <div class="vol-row">
        <div class="vol-row-icon">${diskIconSVG(color)}</div>
        <div class="vol-row-body">
          <div class="vol-row-title">
            <span class="vol-row-name" style="color:${color}">${esc(v.id.toUpperCase())}</span>
            <span class="trash-vol-count" data-vol="${esc(v.id)}">…</span>
          </div>
          <div class="vol-row-path">${esc(v.smbPath || '')}</div>
        </div>
      </div>
    `;
    li.addEventListener('click', () => selectVolume(v.id, li));
    volumeList.appendChild(li);
  });
  loadVolumeCounts(volumes);
}

function loadVolumeCounts(volumes) {
  volumes.forEach(async v => {
    try {
      const res = await fetch(`/api/utilities/trash/volumes/${encodeURIComponent(v.id)}/count`);
      if (!res.ok) return;
      const data = await res.json();
      updateVolumeCountBadge(v.id, data.count);
    } catch { /* ignore */ }
  });
}

function updateVolumeCountBadge(volumeId, count) {
  const badge = volumeList.querySelector(`.trash-vol-count[data-vol="${CSS.escape(volumeId)}"]`);
  if (!badge) return;
  if (count <= 0) {
    badge.textContent = 'empty';
    badge.className = 'trash-vol-count empty';
  } else {
    badge.textContent = String(count);
    badge.className = 'trash-vol-count has-items';
  }
}

async function selectVolume(volumeId, li) {
  volumeList.querySelectorAll('.trash-volume-item').forEach(el => el.classList.remove('selected'));
  li.classList.add('selected');
  state.selectedVolumeId = volumeId;
  state.page = 0;
  await loadItems();
}

// ── Item table ────────────────────────────────────────────────────────────
async function loadItems() {
  if (!state.selectedVolumeId) return;
  trashEmpty.style.display   = 'none';
  trashContent.style.display = 'flex';
  tableBody.innerHTML = '<tr><td colspan="5" class="trash-loading">Loading…</td></tr>';
  checkAll.checked = false;
  updateActionButtons();

  try {
    const params = new URLSearchParams({ page: state.page, pageSize: state.pageSize });
    const res = await fetch(`/api/utilities/trash/volumes/${encodeURIComponent(state.selectedVolumeId)}/items?${params}`);
    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: res.statusText }));
      tableBody.innerHTML = `<tr><td colspan="5" class="trash-error">${esc(err.error || 'Failed to load')}</td></tr>`;
      return;
    }
    const data = await res.json();
    state.items      = data.items || [];
    state.totalCount = data.totalCount || 0;
    updateVolumeCountBadge(state.selectedVolumeId, state.totalCount);
    renderTable();
    renderPagination(data);
  } catch (err) {
    tableBody.innerHTML = `<tr><td colspan="5" class="trash-error">Error: ${esc(err.message)}</td></tr>`;
    console.error('Trash: failed to load items', err);
  }
}

function renderTable() {
  if (state.items.length === 0) {
    countLabel.textContent = 'No items in trash';
    tableBody.innerHTML = '<tr><td colspan="5" class="trash-empty-row">This volume\'s trash is empty.</td></tr>';
    return;
  }
  countLabel.textContent = `${state.totalCount} item${state.totalCount !== 1 ? 's' : ''} in trash`;
  tableBody.innerHTML = '';
  state.items.forEach((item, idx) => {
    const tr = document.createElement('tr');
    tr.className = 'trash-row';

    const status = item.scheduledDeletionAt
      ? buildScheduledStatus(item.scheduledDeletionAt)
      : `<span class="trash-status-none">—</span>`;

    tr.innerHTML = `
      <td class="trash-col-check"><input type="checkbox" class="trash-row-check" data-idx="${idx}"></td>
      <td class="trash-col-path">${esc(item.originalPath || '')}</td>
      <td class="trash-col-reason">${esc(item.reason || '')}</td>
      <td class="trash-col-trashed">${item.trashedAt ? formatRelative(item.trashedAt) : '—'}</td>
      <td class="trash-col-status">${status}</td>
    `;
    tableBody.appendChild(tr);
  });

  tableBody.querySelectorAll('.trash-row-check').forEach(cb => {
    cb.addEventListener('change', updateActionButtons);
  });
}

function renderPagination(data) {
  const totalPages = Math.ceil(data.totalCount / data.pageSize);
  if (totalPages <= 1) {
    pagination.style.display = 'none';
    return;
  }
  pagination.style.display = 'flex';
  const current = data.page + 1;
  pageLabel.textContent = `Page ${current} of ${totalPages}`;
  prevBtn.disabled = data.page === 0;
  nextBtn.disabled = data.page >= totalPages - 1;
}

function updateActionButtons() {
  const checkedBoxes = tableBody.querySelectorAll('.trash-row-check:checked');
  const count = checkedBoxes.length;
  scheduleBtn.disabled = count === 0;
  restoreBtn.disabled  = count === 0;
  const hasScheduled = Array.from(checkedBoxes).some(cb => {
    const idx = parseInt(cb.dataset.idx, 10);
    return !!(state.items[idx]?.scheduledDeletionAt);
  });
  unscheduleBtn.disabled = !hasScheduled;
}

function selectedSidecarPaths() {
  const paths = [];
  tableBody.querySelectorAll('.trash-row-check:checked').forEach(cb => {
    const idx = parseInt(cb.dataset.idx, 10);
    if (state.items[idx]) paths.push(state.items[idx].sidecarPath);
  });
  return paths;
}

// ── Select all ────────────────────────────────────────────────────────────
checkAll.addEventListener('change', () => {
  tableBody.querySelectorAll('.trash-row-check').forEach(cb => {
    cb.checked = checkAll.checked;
  });
  updateActionButtons();
});

// ── Pagination ────────────────────────────────────────────────────────────
prevBtn.addEventListener('click', () => { state.page--; loadItems(); });
nextBtn.addEventListener('click', () => { state.page++; loadItems(); });

// ── Schedule dialog ───────────────────────────────────────────────────────
scheduleBtn.addEventListener('click', () => {
  const paths = selectedSidecarPaths();
  if (paths.length === 0) return;
  const deletionDate = new Date(Date.now() + 10 * 24 * 3600 * 1000);
  const dateStr = deletionDate.toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });
  scheduleBody.textContent =
    `Schedule ${paths.length} item${paths.length !== 1 ? 's' : ''} for permanent deletion on or after ${dateStr}?`;
  scheduleDialog.style.display = 'flex';
  scheduleConfirmBtn.dataset.scheduledAt = deletionDate.toISOString();
  scheduleConfirmBtn.dataset.paths = JSON.stringify(paths);
});

scheduleCancelBtn.addEventListener('click', () => { scheduleDialog.style.display = 'none'; });
scheduleDialog.addEventListener('click', e => { if (e.target === scheduleDialog) scheduleDialog.style.display = 'none'; });

scheduleConfirmBtn.addEventListener('click', async () => {
  scheduleDialog.style.display = 'none';
  const paths = JSON.parse(scheduleConfirmBtn.dataset.paths);
  const scheduledAt = scheduleConfirmBtn.dataset.scheduledAt;
  await runTask('trash.schedule', { volumeId: state.selectedVolumeId, sidecarPaths: paths, scheduledAt });
});

// ── Unschedule dialog ─────────────────────────────────────────────────────
unscheduleBtn.addEventListener('click', () => {
  const paths = selectedSidecarPaths().filter(p => {
    const idx = state.items.findIndex(it => it.sidecarPath === p);
    return idx >= 0 && !!state.items[idx].scheduledDeletionAt;
  });
  if (paths.length === 0) return;
  unscheduleBody.textContent =
    `Remove deletion schedule from ${paths.length} item${paths.length !== 1 ? 's' : ''}? ` +
    `They will remain in trash but won't be automatically deleted.`;
  unscheduleDialog.style.display = 'flex';
  unscheduleConfirmBtn.dataset.paths = JSON.stringify(paths);
});

unscheduleCancelBtn.addEventListener('click', () => { unscheduleDialog.style.display = 'none'; });
unscheduleDialog.addEventListener('click', e => { if (e.target === unscheduleDialog) unscheduleDialog.style.display = 'none'; });

unscheduleConfirmBtn.addEventListener('click', async () => {
  unscheduleDialog.style.display = 'none';
  const paths = JSON.parse(unscheduleConfirmBtn.dataset.paths);
  await runTask('trash.unschedule', { volumeId: state.selectedVolumeId, sidecarPaths: paths });
});

// ── Restore dialog ────────────────────────────────────────────────────────
restoreBtn.addEventListener('click', () => {
  const paths = selectedSidecarPaths();
  if (paths.length === 0) return;
  restoreBody.textContent =
    `Restore ${paths.length} item${paths.length !== 1 ? 's' : ''} to their original paths? ` +
    `The database will not be updated — re-sync the volume after restore to reindex.`;
  restoreDialog.style.display = 'flex';
  restoreConfirmBtn.dataset.paths = JSON.stringify(paths);
});

restoreCancelBtn.addEventListener('click', () => { restoreDialog.style.display = 'none'; });
restoreDialog.addEventListener('click', e => { if (e.target === restoreDialog) restoreDialog.style.display = 'none'; });

restoreConfirmBtn.addEventListener('click', async () => {
  restoreDialog.style.display = 'none';
  const paths = JSON.parse(restoreConfirmBtn.dataset.paths);
  await runTask('trash.restore', { volumeId: state.selectedVolumeId, sidecarPaths: paths });
});

// ── Task execution (via SSE) ──────────────────────────────────────────────
async function runTask(taskId, body) {
  try {
    const res = await fetch(`/api/utilities/tasks/${encodeURIComponent(taskId)}/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (res.status === 409) {
      const err = await res.json();
      alert(`Another task is already running: ${err.runningTaskId}`);
      return;
    }
    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: res.statusText }));
      alert(`Failed to start task: ${err.error || res.statusText}`);
      return;
    }
    const { runId } = await res.json();
    await awaitRun(runId);
    await loadItems();
  } catch (err) {
    console.error('Trash: task failed', err);
    alert(`Task error: ${err.message}`);
  }
}

function awaitRun(runId) {
  return new Promise((resolve) => {
    const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
    es.addEventListener('task.ended', () => { es.close(); resolve(); });
    es.addEventListener('error', () => { es.close(); resolve(); });
  });
}

// ── Scheduled-status cell ─────────────────────────────────────────────────
// 10-day window assumed; ring fills day-by-day, hue slides yellow→red.
const SCHEDULE_DAYS = 10;
const RING_R = 6;
const RING_CIRC = 2 * Math.PI * RING_R;

function buildScheduledStatus(scheduledAt) {
  const deadline = new Date(scheduledAt).getTime();
  const now = Date.now();
  const msRemaining = deadline - now;
  const daysRemaining = msRemaining / 86400000;

  // progress 0→1 over the 10-day window (clamp to [0,1])
  const progress = Math.min(1, Math.max(0, (SCHEDULE_DAYS - daysRemaining) / SCHEDULE_DAYS));

  // hue: 45 (yellow) → 0 (red)
  const hue = Math.round(45 * (1 - progress));
  const color = `hsl(${hue}, 90%, 58%)`;

  const offset = RING_CIRC * (1 - progress);
  const ring = `<svg class="trash-ring" viewBox="0 0 16 16" width="14" height="14" fill="none">
    <circle cx="8" cy="8" r="${RING_R}" stroke="rgba(255,255,255,.12)" stroke-width="2.5"/>
    <circle cx="8" cy="8" r="${RING_R}" stroke="${color}" stroke-width="2.5"
      stroke-dasharray="${RING_CIRC.toFixed(2)}" stroke-dashoffset="${offset.toFixed(2)}"
      stroke-linecap="round" transform="rotate(-90 8 8)"/>
  </svg>`;

  const label = daysRemaining <= 0
    ? 'Overdue'
    : daysRemaining < 1
      ? 'Today'
      : `${formatDate(scheduledAt)}`;

  return `<span class="trash-status-scheduled" style="color:${color}">${ring}${label}</span>`;
}

// ── Date helpers ──────────────────────────────────────────────────────────
function formatDate(isoStr) {
  if (!isoStr) return '—';
  try {
    const d = new Date(isoStr);
    return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
  } catch { return isoStr; }
}

function formatRelative(isoStr) {
  if (!isoStr) return '—';
  try {
    const d = new Date(isoStr);
    const diff = Date.now() - d.getTime();
    const days = Math.floor(diff / 86400000);
    if (days === 0) return 'Today';
    if (days === 1) return 'Yesterday';
    if (days < 30) return `${days}d ago`;
    if (days < 365) return `${Math.floor(days / 30)}mo ago`;
    return `${Math.floor(days / 365)}y ago`;
  } catch { return isoStr; }
}
