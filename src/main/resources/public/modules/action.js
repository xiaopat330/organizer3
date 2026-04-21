import { showView, updateBreadcrumb } from './grid.js';
import { pushNav } from './nav.js';
import { makeCompactTitleCard } from './cards.js';
import { esc } from './utils.js';
import { renderVideoSection } from './title-detail.js';
import { showAliasEditor, hideAliasEditorView } from './alias-editor.js';
import { showTitleEditor, hideTitleEditorView } from './title-editor.js';
import { showLogsView, hideLogsView } from './log-viewer.js';

// ── DOM refs ──────────────────────────────────────────────────────────────
const actionBtn         = document.getElementById('action-btn');
const aliasesBtn        = document.getElementById('tools-aliases-btn');
const duplicatesBtn     = document.getElementById('tools-duplicates-btn');
const queueBtn          = document.getElementById('tools-queue-btn');
const logsBtn           = document.getElementById('tools-logs-btn');
const duplicatesView    = document.getElementById('tools-duplicates-view');
const duplicatesFilters = document.getElementById('tools-duplicates-filters');
const duplicatesList    = document.getElementById('tools-duplicates-list');
const duplicatesStatus  = document.getElementById('tools-duplicates-status');
const dupSentinel       = document.getElementById('tools-duplicates-sentinel');
const dupVolumeSelect   = document.getElementById('dup-volume-select');
const dupDetailOverlay  = document.getElementById('dup-detail-overlay');
const dupDetailHeading  = document.getElementById('dup-detail-heading');
const dupDetailBody     = document.getElementById('dup-detail-body');
const dupDetailClose    = document.getElementById('dup-detail-close');

// ── Tool buttons ──────────────────────────────────────────────────────────
const TOOL_BTNS = [aliasesBtn, duplicatesBtn, queueBtn, logsBtn];

function selectTool(btn) {
  TOOL_BTNS.forEach(b => b?.classList.remove('selected'));
  btn?.classList.add('selected');
}

function hideAllToolViews() {
  hideAliasEditorView();
  hideTitleEditorView();
  hideLogsView();
  duplicatesView.style.display    = 'none';
  duplicatesFilters.style.display = 'none';
}

// ── Show Tools landing ────────────────────────────────────────────────────
export function showActionView(tool) {
  actionBtn.classList.add('active');
  document.getElementById('actresses-btn')?.classList.remove('active');
  document.getElementById('titles-browse-btn')?.classList.remove('active');
  document.getElementById('av-btn')?.classList.remove('active');

  hideAllToolViews();
  selectTool(null);

  showView('action');
  pushNav({ view: 'action', tool: tool || null }, 'tools');
  updateBreadcrumb([{ label: 'Tools' }]);
}

// ── Volume dropdown ───────────────────────────────────────────────────────
let volumesLoaded = false;

async function ensureVolumesLoaded() {
  if (volumesLoaded) return;
  try {
    const res  = await fetch('/api/tools/volumes');
    const vols = await res.json();  // [{ id, smbPath }]
    vols.forEach(v => {
      const opt = document.createElement('option');
      opt.value       = v.id;
      opt.textContent = v.smbPath;
      dupVolumeSelect.appendChild(opt);
    });
    volumesLoaded = true;
  } catch (err) {
    console.error('Failed to load volumes', err);
  }
}

dupVolumeSelect.addEventListener('change', () => {
  resetDuplicates();
  initDupInfiniteScroll();
  loadMoreDuplicates();
});

// ── Duplicates ────────────────────────────────────────────────────────────
let dupOffset  = 0;
let dupTotal   = null;
let dupLoading = false;
let dupObserver = null;
const DUP_LIMIT = 50;

function resetDuplicates() {
  dupOffset  = 0;
  dupTotal   = null;
  dupLoading = false;
  duplicatesList.innerHTML     = '';
  duplicatesStatus.textContent = '';
  if (dupObserver) { dupObserver.disconnect(); dupObserver = null; }
}

function renderDupRow(t) {
  const row = document.createElement('div');
  row.className = 'dup-row';
  row.style.cursor = 'pointer';
  row.addEventListener('click', () => openDupDetail(t));

  // Left: compact card
  const cardCol = document.createElement('div');
  cardCol.className = 'dup-card-col';
  cardCol.appendChild(makeCompactTitleCard(t));

  // Right: NAS paths
  const pathsCol = document.createElement('div');
  pathsCol.className = 'dup-paths-col';

  const labelEl = document.createElement('div');
  labelEl.className    = 'dup-path-label';
  labelEl.textContent  = `${(t.nasPaths || []).length} locations`;
  pathsCol.appendChild(labelEl);

  (t.nasPaths || []).forEach(p => {
    const pathEl = document.createElement('div');
    pathEl.className = 'dup-path';
    // Split "//server/share/rest/of/path" → white base + orange relative
    const parts = p.split('/');
    const base  = parts.slice(0, 4).join('/');
    const rest  = parts.slice(4).join('/');
    pathEl.innerHTML = `<span class="dup-path-base">${esc(base)}</span>`
                     + (rest ? `<span class="dup-path-rest">/${esc(rest)}</span>` : '');
    pathsCol.appendChild(pathEl);
  });

  row.appendChild(cardCol);
  row.appendChild(pathsCol);
  return row;
}

async function loadMoreDuplicates() {
  if (dupLoading) return;
  if (dupTotal !== null && dupOffset >= dupTotal) return;

  dupLoading = true;
  try {
    const volumeId = dupVolumeSelect.value;
    const params   = new URLSearchParams({ offset: dupOffset, limit: DUP_LIMIT });
    if (volumeId) params.set('volumeId', volumeId);

    const res  = await fetch(`/api/tools/duplicates?${params}`);
    const data = await res.json();  // { titles: [...], total: N }

    if (dupTotal === null) {
      dupTotal = data.total;
      duplicatesStatus.textContent = `${dupTotal} title${dupTotal !== 1 ? 's' : ''} with duplicate locations`;
    }

    data.titles.forEach(t => duplicatesList.appendChild(renderDupRow(t)));
    dupOffset += data.titles.length;

    if (dupOffset >= dupTotal) {
      if (dupObserver) { dupObserver.disconnect(); dupObserver = null; }
    }
  } catch (err) {
    console.error('Failed to load duplicates', err);
  } finally {
    dupLoading = false;
  }
}

function initDupInfiniteScroll() {
  if (dupObserver) dupObserver.disconnect();
  dupObserver = new IntersectionObserver(entries => {
    if (entries[0].isIntersecting) loadMoreDuplicates();
  }, { rootMargin: '200px' });
  dupObserver.observe(dupSentinel);
}

export async function showDuplicates() {
  showActionView('duplicates');
  selectTool(duplicatesBtn);
  updateBreadcrumb([{ label: 'Tools' }, { label: 'Duplicates' }]);

  hideAllToolViews();
  duplicatesFilters.style.display = 'flex';
  duplicatesView.style.display    = '';

  // Pin filter bar below header + action-landing
  requestAnimationFrame(() => {
    const header        = document.querySelector('header');
    const actionLanding = document.getElementById('action-landing');
    const headerH       = header        ? header.offsetHeight        : 0;
    const alH           = actionLanding ? actionLanding.offsetHeight : 0;
    duplicatesFilters.style.top = (headerH + alH) + 'px';
  });

  await ensureVolumesLoaded();
  resetDuplicates();
  initDupInfiniteScroll();
  loadMoreDuplicates();
}

// ── Duplicate detail modal ────────────────────────────────────────────────
function closeDupDetail() {
  // Pause all playing videos before clearing the DOM
  dupDetailOverlay.querySelectorAll('video').forEach(v => { v.pause(); });
  dupDetailOverlay.style.display = 'none';
  dupDetailBody.innerHTML = '';
  dupDetailHeading.textContent = '';
}

async function openDupDetail(t) {
  const locs = t.locationEntries || [];

  dupDetailHeading.textContent = t.code + (t.titleEnglish ? ' — ' + t.titleEnglish : '');
  dupDetailBody.innerHTML = '<div class="dup-detail-loading">Loading\u2026</div>';
  dupDetailOverlay.style.display = 'flex';

  // Build the table: one row per location
  const table = document.createElement('table');
  table.className = 'dup-detail-table';

  const thead = document.createElement('thead');
  thead.innerHTML = '<tr><th class="dup-dt-path-col">Location</th><th class="dup-dt-video-col">Videos</th></tr>';
  table.appendChild(thead);

  const tbody = document.createElement('tbody');
  table.appendChild(tbody);

  dupDetailBody.innerHTML = '';
  dupDetailBody.appendChild(table);

  for (const loc of locs) {
    const tr = document.createElement('tr');
    tr.className = 'dup-dt-row';

    // Path cell
    const pathTd = document.createElement('td');
    pathTd.className = 'dup-dt-path-td';
    const parts = loc.nasPath.split('/');
    const base  = parts.slice(0, 4).join('/');
    const rest  = parts.slice(4).join('/');
    pathTd.innerHTML = `<span class="dup-path-base">${esc(base)}</span>`
                     + (rest ? `<span class="dup-path-rest">/${esc(rest)}</span>` : '');
    tr.appendChild(pathTd);

    // Video cell — async load
    const videoTd = document.createElement('td');
    videoTd.className = 'dup-dt-video-td';
    videoTd.innerHTML = '<div class="video-loading">Discovering videos\u2026</div>';
    tr.appendChild(videoTd);

    tbody.appendChild(tr);

    // Kick off discovery for this location (non-blocking per row)
    fetchVideosForLocation(t.code, loc.volumeId, videoTd);
  }
}

async function fetchVideosForLocation(titleCode, volumeId, container) {
  try {
    const res    = await fetch(`/api/titles/${encodeURIComponent(titleCode)}/videos?volumeId=${encodeURIComponent(volumeId)}`);
    const videos = await res.json();
    container.innerHTML = '';
    if (!videos || videos.length === 0) {
      container.innerHTML = '<div class="video-empty">No video files found</div>';
      return;
    }
    videos.forEach((v, idx) => {
      if (idx > 0) {
        container.appendChild(Object.assign(document.createElement('hr'), { className: 'video-divider' }));
      }
      container.appendChild(renderVideoSection(v, titleCode, { thumbnails: false }));
    });
  } catch (err) {
    container.innerHTML = '<div class="video-empty">Could not load videos</div>';
    console.error('Failed to load videos for location', volumeId, err);
  }
}

// Close modal on button click, overlay click (outside modal), or ESC
dupDetailClose.addEventListener('click', closeDupDetail);
dupDetailOverlay.addEventListener('click', e => {
  if (e.target === dupDetailOverlay) closeDupDetail();
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && dupDetailOverlay.style.display !== 'none') closeDupDetail();
});

// ── Button wiring ─────────────────────────────────────────────────────────
actionBtn.addEventListener('click', () => showActionView());

aliasesBtn.addEventListener('click', () => {
  showActionView('aliases');
  selectTool(aliasesBtn);
  updateBreadcrumb([{ label: 'Tools' }, { label: 'Aliases' }]);
  hideAllToolViews();
  showAliasEditor();
});

duplicatesBtn.addEventListener('click', showDuplicates);

queueBtn.addEventListener('click', () => {
  showActionView('queue');
  selectTool(queueBtn);
  updateBreadcrumb([{ label: 'Tools' }, { label: 'Queue' }]);
  hideAllToolViews();
  showTitleEditor();
});

logsBtn.addEventListener('click', () => {
  showActionView('logs');
  selectTool(logsBtn);
  updateBreadcrumb([{ label: 'Tools' }, { label: 'Logs' }]);
  hideAllToolViews();
  showLogsView();
});
