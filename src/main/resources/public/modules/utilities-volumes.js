// Utilities → Volumes screen.
// Two-pane target + operations layout. Left: volume picker with health badges.
// Right: volume detail and operations stage (5 modes: empty / detail / visualize
// / running / summary). Backed by /api/utilities/* endpoints; progress streams
// via SSE on /api/utilities/runs/{runId}/events. See spec/UTILITIES_VOLUMES.md.

import { esc } from './utils.js';
import * as taskCenter from './task-center.js';

const SELECTION_KEY = 'utilities.volumes.selection';

const ORGANIZE_ACTIONS = [
  {
    id: 'prep', label: 'Prep',
    icon: '<path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/>',
    previewId: 'prep.preview', executeId: 'prep',
    structureTypes: ['queue'],
    desc: 'Scans the queue partition for raw video files and organizes each one into a title folder skeleton.',
    steps: [
      'Parses each filename to extract the product code (e.g. PRED-848-h265.mkv → PRED-848)',
      'Creates a title folder (PRED-848) with a subfolder matching the encoding (video/, h265/, 4K/)',
      'Moves the video into that subfolder',
      'Files that cannot be parsed are skipped and listed',
    ],
  },
  {
    id: 'normalize', label: 'Normalize',
    icon: '<polyline points="4 7 4 4 20 4 20 7"/><line x1="9" y1="20" x2="15" y2="20"/><line x1="12" y1="4" x2="12" y2="20"/>',
    previewId: 'organize.normalize.preview', executeId: 'organize.normalize',
    structureTypes: ['queue', 'conventional', 'exhibition', 'collections', 'sort_pool'],
    desc: 'Renames the cover image and video file in each title folder to the standard CODE.ext filename.',
    steps: [
      'Renames the cover image to CODE.jpg (e.g. mide123pl.jpg → MIDE-123.jpg)',
      'Renames the single video file to CODE.mkv, preserving quality suffixes like -h265 or _4K',
      'Strips site watermark prefixes from filenames before renaming (e.g. hhd800.com@)',
      'Skips titles with multiple covers or multiple video files — those need manual attention first',
    ],
  },
  {
    id: 'restructure', label: 'Restructure',
    icon: '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>',
    previewId: 'organize.restructure.preview', executeId: 'organize.restructure',
    structureTypes: ['queue', 'conventional', 'exhibition', 'collections', 'sort_pool'],
    desc: 'Moves video files from the title folder root into a proper named subfolder.',
    steps: [
      'Finds video files sitting directly in the title folder alongside the cover image',
      'Determines the correct subfolder name from quality markers in the filename (video/, h265/, 4K/)',
      'Moves the video into that subfolder',
    ],
  },
  {
    id: 'timestamps', label: 'Fix timestamps',
    icon: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    previewId: 'organize.timestamps.preview', executeId: 'organize.timestamps',
    structureTypes: ['conventional', 'exhibition', 'collections'],
    desc: 'Corrects each title folder\'s creation and modification date to match the earliest file it contains.',
    steps: [
      'Walks every title in the curated area (stars/, actress folders, collections)',
      'Reads the creation and modification timestamps of all child files',
      'Sets the folder timestamp to the earliest value found across all children',
      'Skips folders that already have the correct timestamp',
    ],
  },
  {
    id: 'sort', label: 'Sort',
    icon: '<polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/>',
    previewId: 'organize.sort.preview', executeId: 'organize.sort',
    structureTypes: ['conventional'],
    desc: 'Files each title into the permanent library under stars/{actress}/, organized by primary actress.',
    steps: [
      'Looks up the primary actress for each title in the database',
      'Moves the title folder to its permanent home at stars/{actress-name}/',
      'Sets the folder\'s timestamp to the earliest date found among its contents',
      'Updates the database to reflect the new location',
      'Titles with no known actress are routed to attention/ for manual review',
    ],
  },
  {
    id: 'classify', label: 'Classify',
    icon: '<path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><line x1="7" y1="7" x2="7.01" y2="7"/>',
    previewId: 'organize.classify.preview', executeId: 'organize.classify',
    structureTypes: ['conventional'],
    desc: 'Updates actress tier ratings (SSS / SS / S / A / B) based on their current title count and portfolio.',
    steps: [
      'Identifies actresses whose titles were touched in recent organize runs',
      'Counts titles and evaluates portfolio scores for each',
      'Promotes or adjusts each actress\'s tier in the database',
    ],
  },
  {
    id: 'all', label: 'Organize all',
    icon: '<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>',
    previewId: 'organize.preview', executeId: 'organize.queue',
    structureTypes: ['conventional'],
    desc: 'Runs the full pipeline — Normalize, Restructure, Sort, and Classify — on every queued title in sequence.',
    steps: [
      'Normalize — renames covers and videos to CODE.ext',
      'Restructure — moves loose videos from the folder root into named subfolders',
      'Sort — files each title into stars/{actress}/ and corrects folder timestamps',
      'Classify — updates actress tier ratings for all affected actresses',
    ],
  },
];

// When the user clicks the floating task pill elsewhere in the app, they want
// to come back here and see the run. Register a callback once at module load.
taskCenter.onOpenRequested((state) => {
  if (!state) return;
  const volumesBtn = document.getElementById('tools-volumes-btn');
  if (volumesBtn) volumesBtn.click();
});

// Pick up any server-side active run after a page refresh. The SSE stream will
// replay history, so the pill + run view reconstruct themselves without the
// user needing to be on Volumes at load time.
(async function probeActiveRun() {
  try {
    const res = await fetch('/api/utilities/active');
    if (!res.ok) return;
    const body = await res.json();
    if (!body.active) return;
    // Best-effort: we don't know the originating volumeId for arbitrary tasks,
    // but volume.sync encodes it in the run's first PhaseStarted. For now we
    // adopt the run and let the SSE stream populate phases; if the user opens
    // Volumes, showVolumesView will restore the pane. We just need the pill
    // to show immediately.
    const runId = body.runId;
    taskCenter.start({
      taskId: body.taskId,
      runId,
      label: labelFor(body.taskId),
    });
    // Adopt into the module's activeRun state so subsequent view-switches work.
    activeRun = {
      runId,
      volumeId: null,   // unknown until we see the first phase log / task inputs
      eventSource: null,
      phases: new Map(),
      taskStatus: body.status || 'running',
      taskSummary: '',
    };
    const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
    activeRun.eventSource = es;
    es.addEventListener('task.started',  () => {});
    es.addEventListener('phase.started', e => handlePhaseStarted(JSON.parse(e.data)));
    es.addEventListener('phase.progress',e => handlePhaseProgress(JSON.parse(e.data)));
    es.addEventListener('phase.log',     e => handlePhaseLog(JSON.parse(e.data)));
    es.addEventListener('phase.ended',   e => handlePhaseEnded(JSON.parse(e.data)));
    es.addEventListener('task.ended',    e => handleTaskEnded(JSON.parse(e.data)));
  } catch { /* silently ignore — this is a convenience probe */ }
})();

function labelFor(taskId) {
  if (taskId === 'volume.sync') return 'Syncing volume';
  if (taskId === 'volume.clean_stale_locations') return 'Cleaning stale locations';
  const action = ORGANIZE_ACTIONS.find(a => a.previewId === taskId || a.executeId === taskId);
  if (action) return action.label;
  return taskId;
}

// If the detail view is showing and a task finishes (or starts) elsewhere,
// re-render so the Sync button's disabled state is current.
// Skip re-render if an organize flow is active — it manages its own section updates.
taskCenter.subscribe(() => {
  if (viewEl().style.display !== 'none' && detailEl().style.display !== 'none' && selectedId) {
    if (organizeFlow) {
      updateOrgSection(selectedId);
    } else {
      showDetail(selectedId);
    }
  }
});

const listEl      = () => document.getElementById('volumes-list');
const emptyEl     = () => document.getElementById('volumes-empty');
const detailEl    = () => document.getElementById('volumes-detail');
const visualizeEl = () => document.getElementById('volumes-visualize');
const runEl       = () => document.getElementById('volumes-run');
const viewEl      = () => document.getElementById('tools-volumes-view');

function hideAllRightPanes() {
  emptyEl().style.display = 'none';
  detailEl().style.display = 'none';
  visualizeEl().style.display = 'none';
  runEl().style.display = 'none';
}

let volumes = [];              // last-known list from /api/utilities/volumes
let selectedId = null;         // currently selected volume id
let activeRun = null;          // { runId, eventSource, phases: Map<id,state>, logBuffer }
let organizeFlow = null;       // { volumeId, action, state, planResult, execResult, progress, error, eventSource }
let activeTab = 'health';      // current tab within the detail pane
let lastDetailVolumeId = null; // tracks which volume showDetail last rendered

export async function showVolumesView() {
  viewEl().style.display = 'flex';
  selectedId = localStorage.getItem(SELECTION_KEY);
  await refreshVolumes();

  // If a task is still running from a previous navigation, return the user to
  // that run view regardless of what's currently selected. They expect
  // continuity.
  if (activeRun && activeRun.taskStatus === 'running') {
    if (activeRun.volumeId) {
      selectedId = activeRun.volumeId;
      localStorage.setItem(SELECTION_KEY, selectedId);
      renderList();
    }
    hideAllRightPanes();
    runEl().style.display = '';
    renderRun();
    return;
  }

  if (selectedId && volumes.some(v => v.id === selectedId)) {
    openVolume(selectedId);
  } else {
    showEmpty();
  }
}

export function hideVolumesView() {
  // Intentionally leave the EventSource and activeRun in place. A sync that
  // kicks off here must keep streaming while the user browses elsewhere;
  // the floating task pill surfaces state during that time.
  viewEl().style.display = 'none';
}

async function refreshVolumes() {
  try {
    const res = await fetch('/api/utilities/volumes');
    volumes = await res.json();
  } catch (err) {
    console.error('Failed to load volumes', err);
    volumes = [];
  }
  renderList();
}

// Palette of distinct volume hues. A stable index per id gives each volume its
// own color identity across re-renders and across sessions.
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

function tabSVG(paths) {
  return `<svg class="vol-tab-icon" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`;
}

function actionSVG(paths) {
  return `<svg class="org-action-icon" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`;
}

const STRUCTURE_ICON_PATHS = {
  queue:        '<path d="M22 12h-6l-2 3H10l-2-3H2"/><path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
  conventional: '<path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>',
  exhibition:   '<rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>',
  archive:      '<polyline points="21 8 21 21 3 21 3 8"/><rect x="1" y="3" width="22" height="5"/><line x1="10" y1="12" x2="14" y2="12"/>',
  avstars:      '<rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/>',
  _default:     '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v6c0 1.66 4 3 9 3s9-1.34 9-3V5"/><path d="M3 11v6c0 1.66 4 3 9 3s9-1.34 9-3v-6"/>',
};

function volumeIconSVG(structureType, color) {
  const paths = STRUCTURE_ICON_PATHS[structureType] || STRUCTURE_ICON_PATHS._default;
  return `<svg class="vol-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="${color}" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`;
}

function renderList() {
  const ul = listEl();
  ul.innerHTML = '';
  for (const v of volumes) {
    const li = document.createElement('li');
    if (v.id === selectedId) li.classList.add('selected');
    li.addEventListener('click', () => {
      selectedId = v.id;
      localStorage.setItem(SELECTION_KEY, v.id);
      renderList();
      openVolume(v.id);
    });

    const color = hueFor(v.id);
    li.innerHTML = `
      <div class="vol-row">
        <div class="vol-row-icon">${volumeIconSVG(v.structureType, color)}</div>
        <div class="vol-row-body">
          <div class="vol-row-title">
            <span class="vol-row-name" style="color:${color}">${esc(v.id.toUpperCase())}</span>
            ${badgeHTML(v)}
          </div>
          <div class="vol-row-path">${esc(v.smbPath || '')}</div>
          <div class="vol-row-meta">${v.titleCount || 0} titles · ${esc(formatLastSynced(v.lastSyncedAt))}</div>
        </div>
      </div>
    `;

    ul.appendChild(li);
  }
}

function badgeHTML(v) {
  if (v.status === 'offline') {
    return `<span class="vol-badge offline"><span class="vol-badge-dot"></span>offline</span>`;
  }
  const errors = (v.health || []).filter(h => h.level === 'error').length;
  const warns  = (v.health || []).filter(h => h.level === 'warn').length;
  if (errors > 0) return `<span class="vol-badge error"><span class="vol-badge-dot"></span>${errors}</span>`;
  if (warns > 0)  return `<span class="vol-badge warn"><span class="vol-badge-dot"></span>${warns}</span>`;
  return `<span class="vol-badge healthy"><span class="vol-badge-dot"></span>healthy</span>`;
}

function formatLastSynced(ts) {
  if (!ts) return 'Never synced';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return 'Never synced';
  const diff = Date.now() - d.getTime();
  const days = Math.floor(diff / 86400000);
  if (days >= 2) return `${days} days ago`;
  if (days === 1) return 'Yesterday';
  const hours = Math.floor(diff / 3600000);
  if (hours >= 1) return `${hours}h ago`;
  const mins = Math.floor(diff / 60000);
  if (mins >= 1) return `${mins}m ago`;
  return 'Just now';
}

function showEmpty() {
  hideAllRightPanes();
  emptyEl().style.display = '';
}

/**
 * Dispatch for opening a volume from the picker. If a run is in flight *for this
 * volume*, show the run pane; otherwise show the static detail pane. This is what
 * the user expects after clicking a volume whose sync they already started.
 */
function openVolume(volumeId) {
  if (activeRun && activeRun.taskStatus === 'running' && activeRun.volumeId === volumeId) {
    hideAllRightPanes();
    runEl().style.display = '';
    renderRun();
    return;
  }
  showDetail(volumeId);
}

function showDetail(volumeId) {
  // Detect volume switch — reset tab and any in-progress organize flow.
  if (lastDetailVolumeId !== volumeId) {
    if (organizeFlow?.eventSource) organizeFlow.eventSource.close();
    organizeFlow = null;
    activeTab = 'health';
    lastDetailVolumeId = volumeId;
  }

  const v = volumes.find(x => x.id === volumeId);
  if (!v) { showEmpty(); return; }

  hideAllRightPanes();
  const d = detailEl();
  d.style.display = '';

  const color = hueFor(v.id);
  const orgBadge = v.queueCount > 0
    ? ` <span class="vol-tab-badge">${v.queueCount}</span>` : '';

  d.innerHTML = `
    <div class="vol-detail-head">
      <div class="vol-detail-name">
        <span class="vol-detail-icon">${volumeIconSVG(v.structureType, color)}</span>
        <span>Volume <span style="color:${color}">${esc(v.id.toUpperCase())}</span></span>
      </div>
      <div class="vol-detail-path">${esc(v.smbPath || '')}</div>
    </div>
    <div class="vol-detail-stats">
      <div><span class="vol-stat-label">Titles</span><span class="vol-stat-value">${v.titleCount || 0}</span></div>
      <div><span class="vol-stat-label">Structure</span><span class="vol-stat-value">${esc(v.structureType || '—')}</span></div>
      <div><span class="vol-stat-label">Last synced</span><span class="vol-stat-value">${esc(formatLastSynced(v.lastSyncedAt))}</span></div>
    </div>
    <nav class="vol-tab-bar">
      <button type="button" class="vol-tab${activeTab === 'health'   ? ' selected' : ''}" data-tab="health">${tabSVG('<polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>')} Health</button>
      <button type="button" class="vol-tab${activeTab === 'sync'     ? ' selected' : ''}" data-tab="sync">${tabSVG('<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>')} Operations</button>
      <button type="button" class="vol-tab${activeTab === 'organize' ? ' selected' : ''}" data-tab="organize">${tabSVG('<polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/>')} Organize${orgBadge}</button>
    </nav>
    <div class="vol-tab-panels">
      <div class="vol-tab-panel" data-panel="health"${activeTab !== 'health'   ? ' style="display:none"' : ''}>
        ${renderHealth(v)}
      </div>
      <div class="vol-tab-panel" data-panel="sync"${activeTab !== 'sync'     ? ' style="display:none"' : ''}>
        ${renderSyncTab(v)}
      </div>
      <div class="vol-tab-panel" data-panel="organize"${activeTab !== 'organize' ? ' style="display:none"' : ''}>
        <div id="org-section"></div>
      </div>
    </div>
  `;

  // Wire tab switching.
  d.querySelectorAll('.vol-tab').forEach(btn => {
    btn.addEventListener('click', () => {
      const tab = btn.getAttribute('data-tab');
      activeTab = tab;
      d.querySelectorAll('.vol-tab').forEach(b => b.classList.toggle('selected', b === btn));
      d.querySelectorAll('.vol-tab-panel').forEach(p => {
        p.style.display = p.getAttribute('data-panel') === tab ? '' : 'none';
      });
    });
  });

  // Wire health action buttons.
  d.querySelectorAll('.vol-health-action').forEach(btn => {
    btn.addEventListener('click', async () => {
      const cat = btn.getAttribute('data-cat');
      if (cat === 'stale_locations') await showStaleLocationsVisualize(v.id);
    });
  });

  // Wire sync button.
  document.getElementById('vol-op-sync')?.addEventListener('click', () => startSync(v.id));

  // Populate organize tab panel (always in DOM, even when not visible).
  updateOrgSection(v.id);
}

function renderSyncTab(v) {
  const isBlocked = taskCenter.isRunning();
  return `
    <button type="button" class="vol-op-primary" id="vol-op-sync"${isBlocked ? ' disabled' : ''}>Sync</button>
    ${isBlocked ? '<div class="vol-op-blocked">Another utility task is running. Wait for it to finish.</div>' : ''}
  `;
}

function renderHealth(v) {
  const issues = v.health || [];
  if (issues.length === 0) {
    return `<div class="vol-health-healthy">
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
      All healthy
    </div>`;
  }
  const rows = issues.map(h => {
    const action = healthAction(h);
    const btn = action
      ? `<button type="button" class="vol-health-action" data-cat="${esc(h.category)}"${taskCenter.isRunning() ? ' disabled' : ''}>${esc(action.label)}</button>`
      : '';
    const tip = healthTooltip(h.category);
    const info = tip
      ? `<span class="vol-health-info" tabindex="0" title="${esc(tip)}">
          <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
         </span>`
      : '';
    return `<li><span class="vol-health-desc">${esc(h.description)}${info}</span>${btn}</li>`;
  }).join('');
  return `<ul class="vol-health-list">${rows}</ul>`;
}

/** Returns the action metadata for a health category, or null if there's no action yet. */
function healthAction(h) {
  if (h.category === 'stale_locations') return { label: 'Clean up', kind: 'visualize-task', taskId: 'volume.clean_stale_locations' };
  return null;
}

/** Short plain-text tooltip keyed by health category. Shown on hover of the (i) icon. */
function healthTooltip(category) {
  switch (category) {
    case 'stale_locations':
      return 'A stale location is a DB row saying "this file is at path X on this volume," but '
           + 'the file wasn\'t found during the last sync — it was moved, renamed, or deleted. '
           + 'Cleaning up removes the index row only; nothing on disk is touched.';
    default:
      return null;
  }
}

// ── Visualize-then-confirm: Clean stale locations ──────────────────────────
async function showStaleLocationsVisualize(volumeId) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running. Wait for it to finish.');
    return;
  }
  const pane = visualizeEl();
  hideAllRightPanes();
  pane.style.display = '';
  pane.innerHTML = `
    <div class="vol-visualize-head">
      <div class="vol-visualize-title">Clean stale locations</div>
      <div class="vol-visualize-sub">Fetching preview…</div>
    </div>
  `;

  let preview;
  try {
    const res = await fetch(`/api/utilities/tasks/volume.clean_stale_locations/preview`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    preview = await res.json();
  } catch (err) {
    pane.innerHTML = `
      <div class="vol-visualize-head">
        <div class="vol-visualize-title">Clean stale locations</div>
        <div class="vol-visualize-sub">Failed to fetch preview: ${esc(err.message)}</div>
      </div>
      <div class="vol-visualize-actions">
        <button type="button" class="vol-visualize-cancel">Back</button>
      </div>
    `;
    pane.querySelector('.vol-visualize-cancel').addEventListener('click', () => showDetail(volumeId));
    return;
  }

  const rows = preview.rows || [];
  if (rows.length === 0) {
    pane.innerHTML = `
      <div class="vol-visualize-head">
        <div class="vol-visualize-title">Clean stale locations</div>
        <div class="vol-visualize-sub">No stale locations found. Nothing to clean up.</div>
      </div>
      <div class="vol-visualize-actions">
        <button type="button" class="vol-visualize-cancel">Back</button>
      </div>
    `;
    pane.querySelector('.vol-visualize-cancel').addEventListener('click', () => showDetail(volumeId));
    return;
  }

  const rowsHTML = rows.map(r => `
    <tr>
      <td class="vol-visualize-cell-code">${esc(r.titleCode || '')}</td>
      <td class="vol-visualize-cell-path">${esc(r.path || '')}</td>
      <td class="vol-visualize-cell-date">${esc(shortDate(r.lastSeenAt))}</td>
    </tr>
  `).join('');

  pane.innerHTML = `
    <div class="vol-visualize-head">
      <div class="vol-visualize-title">Clean stale locations</div>
      <div class="vol-visualize-sub">
        The following <b>${rows.length}</b> location record${rows.length === 1 ? '' : 's'} will be removed from the database.
        Files on disk are not touched — only the index entries pointing at files no longer observed on the last sync.
      </div>
    </div>
    <div class="vol-visualize-table-wrap">
      <table class="vol-visualize-table">
        <thead><tr><th>Title</th><th>Path</th><th>Last seen</th></tr></thead>
        <tbody>${rowsHTML}</tbody>
      </table>
    </div>
    <div class="vol-visualize-actions">
      <button type="button" class="vol-visualize-proceed">Proceed — remove ${rows.length}</button>
      <button type="button" class="vol-visualize-cancel">Cancel</button>
    </div>
  `;
  pane.querySelector('.vol-visualize-cancel').addEventListener('click', () => showDetail(volumeId));
  pane.querySelector('.vol-visualize-proceed').addEventListener('click', () =>
      startCleanStaleLocations(volumeId));
}

async function startCleanStaleLocations(volumeId) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  try {
    const res = await fetch(`/api/utilities/tasks/volume.clean_stale_locations/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    taskCenter.start({
      taskId: 'volume.clean_stale_locations',
      runId,
      label: `Cleaning stale locations on Volume ${volumeId.toUpperCase()}`,
    });
    beginRunView(volumeId, runId, 'volume.clean_stale_locations');
  } catch (err) {
    alert('Failed to start cleanup: ' + err.message);
  }
}

function shortDate(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return ts;
  return d.toISOString().slice(0, 10);
}

async function startSync(volumeId) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running. Wait for it to finish before starting a new sync.');
    return;
  }
  try {
    const res = await fetch(`/api/utilities/tasks/volume.sync/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    taskCenter.start({
      taskId: 'volume.sync',
      runId,
      label: `Syncing Volume ${volumeId.toUpperCase()}`,
    });
    beginRunView(volumeId, runId, 'volume.sync');
  } catch (err) {
    alert('Failed to start sync: ' + err.message);
  }
}

function beginRunView(volumeId, runId, taskId) {
  // Close any previous SSE.
  if (activeRun?.eventSource) activeRun.eventSource.close();

  activeRun = {
    runId,
    volumeId,
    taskId: taskId || 'volume.sync',
    eventSource: null,
    phases: new Map(),   // phaseId → { label, status, detail, durationMs }
    taskStatus: 'running',
    taskSummary: '',
  };

  hideAllRightPanes();
  const r = runEl();
  r.style.display = '';
  renderRun();

  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  activeRun.eventSource = es;

  es.addEventListener('task.started',  e => { renderRun(); });
  es.addEventListener('phase.started', e => handlePhaseStarted(JSON.parse(e.data)));
  es.addEventListener('phase.progress',e => handlePhaseProgress(JSON.parse(e.data)));
  es.addEventListener('phase.log',     e => handlePhaseLog(JSON.parse(e.data)));
  es.addEventListener('phase.ended',   e => handlePhaseEnded(JSON.parse(e.data)));
  es.addEventListener('task.ended',    e => handleTaskEnded(JSON.parse(e.data)));
  es.onerror = () => { /* server closes on terminal event; browser reconnect attempts are harmless */ };
}

function handlePhaseStarted(ev) {
  if (!activeRun) return;
  activeRun.phases.set(ev.phaseId, {
    label: ev.label,
    status: 'running',
    detail: '',
    durationMs: null,
    current: 0,
    total: -1,
    lastTick: Date.now(),
  });
  taskCenter.updateProgress({
    phaseLabel: ev.label,
    overallPct: computeOverallPct(),
  });
  renderRun();
}

function handlePhaseProgress(ev) {
  if (!activeRun) return;
  const p = activeRun.phases.get(ev.phaseId);
  if (!p) return;
  p.current = ev.current;
  p.total = ev.total;
  if (ev.total > 0) {
    p.detail = `${ev.current} / ${ev.total}${ev.detail ? ' — ' + ev.detail : ''}`;
  } else if (ev.detail) {
    p.detail = ev.detail;
  }
  p.lastTick = Date.now();
  taskCenter.updateProgress({
    phaseLabel: p.label,
    overallPct: computeOverallPct(),
    detail: p.detail,
  });
  renderRun();
}

function handlePhaseLog(ev) {
  // Raw log lines no longer surfaced in the UI — users care about "scanned 120/1500",
  // not per-file log noise. The task layer already emits structured progress events;
  // raw log output lives in logs/organizer3.log for debugging.
}

function handlePhaseEnded(ev) {
  if (!activeRun) return;
  const p = activeRun.phases.get(ev.phaseId);
  if (!p) return;
  p.status = ev.status;
  p.durationMs = ev.durationMs;
  if (ev.summary) p.detail = ev.summary;
  taskCenter.updateProgress({ overallPct: computeOverallPct() });
  renderRun();
}

function handleTaskEnded(ev) {
  if (!activeRun) return;
  activeRun.taskStatus = ev.status;
  activeRun.taskSummary = ev.summary;
  if (activeRun.eventSource) { activeRun.eventSource.close(); activeRun.eventSource = null; }
  taskCenter.finish({ status: ev.status, summary: ev.summary });
  renderRun();
  // Refresh volumes list so last-synced updates.
  refreshVolumes();
}

// Overall % across all known phases of the current run. Phases that have not
// started yet count as 0, completed phases as 100, running as their current
// ratio (or 50 if indeterminate — just so the pill moves).
// Uses a fixed denominator equal to the expected phase count (4 for
// SyncVolumeTask). Overestimates if a task has fewer actual phases; good
// enough for a progress pill, not a billing system.
function computeOverallPct() {
  if (!activeRun) return 0;
  const EXPECTED_PHASES = 4;
  let sum = 0;
  for (const [, p] of activeRun.phases) {
    if (p.status === 'ok')      sum += 100;
    else if (p.status === 'failed') sum += 100;
    else if (p.total > 0)       sum += Math.min(100, (100 * p.current / p.total));
    else                        sum += 50;
  }
  return Math.min(100, sum / EXPECTED_PHASES);
}

function renderRun() {
  if (!activeRun) return;
  const r = runEl();
  const v = volumes.find(x => x.id === activeRun.volumeId) || { id: activeRun.volumeId };
  const statusLabel = activeRun.taskStatus === 'running' ? 'running'
      : activeRun.taskStatus === 'ok'       ? 'complete'
      : activeRun.taskStatus === 'partial'  ? 'partial'
      : activeRun.taskStatus === 'cancelled' ? 'cancelled'
      : 'failed';

  const phasesHTML = Array.from(activeRun.phases.entries()).map(([id, p]) => {
    const cls = p.status;
    const icon = p.status === 'running' ? '<span class="vol-run-spinner"></span>'
              : p.status === 'ok'      ? '✓'
              : p.status === 'failed'  ? '✗'
              : '○';
    const dur = p.durationMs != null ? formatMs(p.durationMs) : '';
    return `
      <div class="vol-run-phase ${cls}">
        <div class="vol-run-phase-icon">${icon}</div>
        <div>
          <div class="vol-run-phase-label">${esc(p.label)}</div>
          ${p.detail ? `<div class="vol-run-phase-detail">${esc(p.detail)}</div>` : ''}
          ${renderPhaseBar(p)}
        </div>
        <div class="vol-run-phase-duration">${esc(dur)}</div>
      </div>
    `;
  }).join('');

  const actions = activeRun.taskStatus === 'running'
      ? ''
      : `<div class="vol-run-actions"><button type="button" id="vol-run-done">Done</button></div>`;

  const heading = activeRun.taskId === 'volume.clean_stale_locations'
      ? `Cleaning stale locations · Volume ${esc((v.id || '').toUpperCase())}`
      : `Syncing Volume ${esc((v.id || '').toUpperCase())}`;

  r.innerHTML = `
    <div class="vol-run-head">
      <span>${heading}</span>
      <span class="vol-run-status ${activeRun.taskStatus}">${esc(statusLabel)}</span>
    </div>
    <div class="vol-run-phases">${phasesHTML}</div>
    ${actions}
  `;

  if (activeRun.taskStatus !== 'running') {
    document.getElementById('vol-run-done').addEventListener('click', () => {
      activeRun = null;
      showDetail(v.id);
    });
  }
}

function renderPhaseBar(p) {
  if (p.status !== 'running') return '';
  if (p.total > 0 && p.current >= 0) {
    const pct = Math.min(100, Math.max(0, Math.floor(100 * p.current / p.total)));
    return `<div class="vol-phase-bar"><div class="vol-phase-bar-fill" style="width:${pct}%"></div></div>`;
  }
  return `<div class="vol-phase-bar"><div class="vol-phase-bar-indet"></div></div>`;
}

function formatMs(ms) {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  const s = Math.floor(ms / 1000);
  return Math.floor(s / 60) + 'm ' + (s % 60) + 's';
}

// ── Organize queue ─────────────────────────────────────────────────────────

function updateOrgSection(volumeId) {
  const el = document.getElementById('org-section');
  if (!el) return;
  const v = volumes.find(x => x.id === volumeId) || { id: volumeId, queueCount: 1 };
  el.innerHTML = renderOrgSectionHTML(v);
  wireOrgSection(volumeId);
}

function renderOrgSectionHTML(v) {
  const isBlocked = taskCenter.isRunning();

  if (!organizeFlow) {
    const structureType = v.structureType || '';
    const visibleActions = ORGANIZE_ACTIONS.filter(a =>
      !a.structureTypes || a.structureTypes.includes(structureType));
    if (visibleActions.length === 0) {
      return `<div class="org-no-actions">No organize operations are available for ${esc(structureType || 'this')} volumes.</div>`;
    }
    const disabledAttr = isBlocked ? ' disabled' : '';
    const btns = visibleActions.map(a =>
      `<button type="button" class="org-action-btn${a.id === 'all' ? ' org-action-all' : ''}" data-action="${esc(a.id)}"${disabledAttr}>${actionSVG(a.icon)} ${esc(a.label)}</button>`
    ).join('');
    const queueLine = v.queueCount > 0
      ? `<div class="org-queue-count">${v.queueCount} title${v.queueCount === 1 ? '' : 's'} in queue</div>`
      : '';
    const blockedNote = isBlocked ? '<div class="vol-op-blocked">Another utility task is running. Wait for it to finish.</div>' : '';
    return `${queueLine}<div class="org-actions">${btns}</div>${blockedNote}`;
  }

  const { action, state, planResult, execResult, progress, error } = organizeFlow;
  const descHTML = renderActionDescriptionHTML(action);

  if (state === 'planning') {
    return `${descHTML}
      <div class="org-flow-head">Planning…</div>
      <div class="org-spinner"></div>`;
  }

  if (state === 'plan-ready') {
    if (action.id === 'prep') {
      return `${descHTML}${renderPrepPlanReadyHTML(planResult)}`;
    }
    if (action.id === 'timestamps') {
      return `${descHTML}${renderTimestampsPlanReadyHTML(planResult)}`;
    }
    const allRows = (planResult?.titles || []).map(renderPlanRows).filter(r => r);
    const titleCount = allRows.length;
    const tbody = allRows.join('');
    return `${descHTML}
      <div class="org-flow-head">Plan — ${titleCount} title${titleCount === 1 ? '' : 's'} with changes</div>
      ${planTableHTML(tbody)}
      <div class="org-flow-actions">
        <button type="button" class="org-execute-btn">Execute</button>
        <button type="button" class="org-cancel-btn">Cancel</button>
      </div>`;
  }

  if (state === 'executing') {
    const cur = progress?.current ?? 0;
    const tot = progress?.total ?? 0;
    const pct = tot > 0 ? Math.floor(100 * cur / tot) : 0;
    const bar = tot > 0
      ? `<div class="vol-phase-bar"><div class="vol-phase-bar-fill" style="width:${pct}%"></div></div>`
      : `<div class="vol-phase-bar"><div class="vol-phase-bar-indet"></div></div>`;
    const progressText = tot > 0 ? `${cur} / ${tot}` : 'Working…';
    return `${descHTML}
      <div class="org-flow-head">Running…</div>
      <div class="org-progress">${esc(progressText)}</div>
      ${bar}`;
  }

  if (state === 'done') {
    const result = execResult || planResult;
    const summaryHTML = action.id === 'prep'
      ? (result?.summary ? renderPrepSummaryHTML(result.summary) : '')
      : action.id === 'timestamps'
      ? (result?.summary ? renderTimestampsSummaryHTML(result.summary) : '')
      : (result?.summary ? renderOrgSummaryHTML(result.summary) : '');
    const errorHTML = error ? `<div class="org-error">${esc(error)}</div>` : '';
    const statusLabel = error ? 'Failed' : 'Done';
    return `${descHTML}
      <div class="org-flow-head">${statusLabel}</div>
      ${errorHTML}
      ${summaryHTML}
      <div class="org-flow-actions">
        <button type="button" class="org-runagain-btn">Run again</button>
        <button type="button" class="org-back-btn">Back</button>
      </div>`;
  }

  return '';
}

function wireOrgSection(volumeId) {
  const el = document.getElementById('org-section');
  if (!el) return;

  el.querySelectorAll('.org-action-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const actionId = btn.getAttribute('data-action');
      const action = ORGANIZE_ACTIONS.find(a => a.id === actionId);
      if (action) beginOrgPreview(volumeId, action);
    });
  });

  el.querySelector('.org-execute-btn')?.addEventListener('click', () => beginOrgExecute(volumeId));

  el.querySelector('.org-cancel-btn')?.addEventListener('click', () => {
    if (organizeFlow?.eventSource) organizeFlow.eventSource.close();
    organizeFlow = null;
    updateOrgSection(volumeId);
  });

  el.querySelector('.org-back-btn')?.addEventListener('click', () => {
    if (organizeFlow?.eventSource) organizeFlow.eventSource.close();
    organizeFlow = null;
    updateOrgSection(volumeId);
  });

  el.querySelector('.org-runagain-btn')?.addEventListener('click', () => {
    const action = organizeFlow?.action;
    if (!action) return;
    organizeFlow = null;
    beginOrgPreview(volumeId, action);
  });
}

function renderPlanRows(t) {
  const code = t.titleCode || t.path?.split('/').pop() || '?';

  if (t.error) {
    return `<tr class="org-plan-tr org-plan-tr-error">
      <td class="org-plan-td-title">${esc(code)}</td>
      <td class="org-plan-td-err" colspan="2">${esc(t.error)}</td>
    </tr>`;
  }

  const ops = []; // {before, after}

  if (t.normalize?.planned?.length > 0) {
    for (const a of t.normalize.planned) {
      ops.push({
        before: a.from?.split('/').pop() || a.from || '',
        after:  a.to?.split('/').pop()   || a.to   || '',
      });
    }
  }

  if (t.restructure?.planned?.length > 0) {
    for (const a of t.restructure.planned) {
      const segs = (a.to || '').split('/').filter(Boolean);
      ops.push({
        before: a.from?.split('/').pop() || a.from || '',
        after:  segs.length >= 2 ? segs.slice(-2).join('/') : (a.to || ''),
      });
    }
  }

  if (t.sort) {
    const s = t.sort;
    const fromSegs = (t.path || '').split('/').filter(Boolean);
    const before = fromSegs.length >= 2 ? fromSegs.slice(-2).join('/') : (t.path || '');
    if (s.outcome === 'WOULD_SORT') {
      const toSegs = (s.to || '').split('/').filter(Boolean);
      const after = toSegs.length >= 3 ? toSegs.slice(-3).join('/') : (s.to || '');
      ops.push({ before, after });
    } else if (s.outcome === 'WOULD_ROUTE_TO_ATTENTION') {
      ops.push({ before, after: `attention/ — ${s.reason || ''}` });
    }
  }

  if (ops.length === 0) return '';

  return ops.map((op, i) => {
    const titleCell = i === 0
      ? `<td class="org-plan-td-title">${esc(code)}</td>`
      : `<td class="org-plan-td-title org-plan-td-cont"></td>`;
    return `<tr class="org-plan-tr">
      ${titleCell}
      <td class="org-plan-td-before">${esc(op.before)}</td>
      <td class="org-plan-td-after">${esc(op.after)}</td>
    </tr>`;
  }).join('');
}

function planTableHTML(tbody) {
  return `<div class="org-plan-wrap">
    <table class="org-plan-table">
      <colgroup>
        <col style="width:22%">
        <col style="width:39%">
        <col style="width:39%">
      </colgroup>
      <thead><tr>
        <th class="org-plan-th">Title</th>
        <th class="org-plan-th">Before</th>
        <th class="org-plan-th">After</th>
      </tr></thead>
      <tbody>${tbody}</tbody>
    </table>
  </div>`;
}

function renderOrgSummaryHTML(s) {
  const rows = [];
  if (s.normalizeSuccesses   > 0) rows.push(['Renamed',             s.normalizeSuccesses]);
  if (s.restructureSuccesses > 0) rows.push(['Restructured',        s.restructureSuccesses]);
  if (s.sortedToStars        > 0) rows.push(['Filed to stars',      s.sortedToStars]);
  if (s.sortedToAttention    > 0) rows.push(['Routed to attention', s.sortedToAttention]);
  if (s.sortsSkipped         > 0) rows.push(['Already in place',    s.sortsSkipped]);
  if (s.actressesPromoted    > 0) rows.push(['Actresses promoted',  s.actressesPromoted]);
  if (s.titlesWithErrors     > 0) rows.push(['Errors',              s.titlesWithErrors]);
  if (rows.length === 0)          rows.push(['Processed',           s.titlesProcessed]);

  return `<div class="org-summary">
    ${rows.map(([k, n]) =>
      `<div class="org-summary-row">
        <span class="org-summary-key">${esc(k)}</span>
        <span class="org-summary-val">${n}</span>
      </div>`
    ).join('')}
  </div>`;
}

function renderActionDescriptionHTML(action) {
  if (!action.desc) return '';
  const stepsHTML = (action.steps || []).map(s => `<li>${esc(s)}</li>`).join('');
  return `<div class="org-action-desc">
    <div class="org-action-desc-summary">${esc(action.desc)}</div>
    ${stepsHTML ? `<ol class="org-action-desc-steps">${stepsHTML}</ol>` : ''}
  </div>`;
}

function renderPrepPlanReadyHTML(planResult) {
  const allPlanned = (planResult?.partitions || []).flatMap(p => p.planned || []);
  const allSkipped = (planResult?.partitions || []).flatMap(p => p.skipped || []);
  const planTrs = allPlanned.map(p => {
    const before = (p.sourcePath || '').split('/').pop();
    const toSegs = (p.targetVideoPath || '').split('/').filter(Boolean);
    const after  = toSegs.length >= 3 ? toSegs.slice(-3).join('/') : (p.targetVideoPath || '');
    return `<tr class="org-plan-tr">
      <td class="org-plan-td-title">${esc(p.code || '?')}</td>
      <td class="org-plan-td-before">${esc(before)}</td>
      <td class="org-plan-td-after">${esc(after)}</td>
    </tr>`;
  }).join('');
  const skipTrs = allSkipped.map(s =>
    `<tr class="org-plan-tr org-plan-tr-skipped">
      <td class="org-plan-td-title">—</td>
      <td class="org-plan-td-before">${esc(s.filename || '')}</td>
      <td class="org-plan-td-after org-plan-td-skip">${esc(s.reason || '')}</td>
    </tr>`
  ).join('');
  const count = allPlanned.length;
  const skipNote = allSkipped.length > 0 ? ` (${allSkipped.length} skipped)` : '';
  return `<div class="org-flow-head">Plan — ${count} file${count === 1 ? '' : 's'} to move${skipNote}</div>
    ${planTableHTML(planTrs + skipTrs)}
    <div class="org-flow-actions">
      <button type="button" class="org-execute-btn">Execute</button>
      <button type="button" class="org-cancel-btn">Cancel</button>
    </div>`;
}

function renderPrepSummaryHTML(s) {
  const rows = [];
  if (s.moved   > 0) rows.push(['Moved',   s.moved]);
  if (s.skipped > 0) rows.push(['Skipped', s.skipped]);
  if (s.failed  > 0) rows.push(['Failed',  s.failed]);
  if (rows.length === 0) rows.push(['Total videos', s.totalVideos || 0]);
  return `<div class="org-summary">
    ${rows.map(([k, n]) =>
      `<div class="org-summary-row">
        <span class="org-summary-key">${esc(k)}</span>
        <span class="org-summary-val">${n}</span>
      </div>`
    ).join('')}
  </div>`;
}

function fmtDate(iso) {
  if (!iso) return '—';
  return iso.slice(0, 10);
}

function renderTimestampsPlanReadyHTML(planResult) {
  const toFix = (planResult?.titles || []).filter(t => t.needsChange);
  const tbody = toFix.map(t =>
    `<tr class="org-plan-tr">
      <td class="org-plan-td-title">${esc(t.titleCode || '?')}</td>
      <td class="org-plan-td-before">${esc(fmtDate(t.currentTimestamp))}</td>
      <td class="org-plan-td-after">${esc(fmtDate(t.targetTimestamp))}</td>
    </tr>`
  ).join('');
  const count = toFix.length;
  const total = planResult?.summary?.checked ?? 0;
  const alreadyOk = total - count;
  const skipNote = alreadyOk > 0 ? ` (${alreadyOk} already correct)` : '';
  return `<div class="org-flow-head">Plan — ${count} folder${count === 1 ? '' : 's'} to correct${skipNote}</div>
    ${planTableHTML(tbody)}
    <div class="org-flow-actions">
      <button type="button" class="org-execute-btn">Execute</button>
      <button type="button" class="org-cancel-btn">Cancel</button>
    </div>`;
}

function renderTimestampsSummaryHTML(s) {
  const rows = [];
  if (s.changed > 0) rows.push(['Corrected', s.changed]);
  if (s.skipped > 0) rows.push(['Already correct', s.skipped]);
  if (s.failed  > 0) rows.push(['Failed', s.failed]);
  if (rows.length === 0) rows.push(['Checked', s.checked || 0]);
  return `<div class="org-summary">
    ${rows.map(([k, n]) =>
      `<div class="org-summary-row">
        <span class="org-summary-key">${esc(k)}</span>
        <span class="org-summary-val">${n}</span>
      </div>`
    ).join('')}
  </div>`;
}

async function beginOrgPreview(volumeId, action) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  organizeFlow = { volumeId, action, state: 'planning', planResult: null, execResult: null, progress: null, error: null, eventSource: null };
  updateOrgSection(volumeId);

  try {
    const res = await fetch(`/api/utilities/tasks/${encodeURIComponent(action.previewId)}/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      organizeFlow = null;
      updateOrgSection(volumeId);
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    taskCenter.start({ taskId: action.previewId, runId, label: `Planning: ${action.label}` });

    const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
    organizeFlow.eventSource = es;

    es.addEventListener('phase.ended', e => {
      const ev = JSON.parse(e.data);
      if ((ev.phaseId === 'organize' || ev.phaseId === 'prep' || ev.phaseId === 'timestamps') && ev.summary) {
        try { organizeFlow.planResult = JSON.parse(ev.summary); } catch {}
      }
    });
    es.addEventListener('task.ended', e => {
      const ev = JSON.parse(e.data);
      es.close();
      organizeFlow.eventSource = null;
      taskCenter.finish({ status: ev.status, summary: '' });
      if (ev.status === 'ok' && organizeFlow.planResult) {
        organizeFlow.state = 'plan-ready';
      } else {
        organizeFlow.error = ev.status === 'ok' ? 'No plan data received' : (ev.summary || 'Preview failed');
        organizeFlow.state = 'done';
      }
      updateOrgSection(volumeId);
    });
    es.onerror = () => {};
  } catch (err) {
    organizeFlow.error = err.message;
    organizeFlow.state = 'done';
    updateOrgSection(volumeId);
  }
}

async function beginOrgExecute(volumeId) {
  if (!organizeFlow) return;
  const action = organizeFlow.action;
  organizeFlow.state = 'executing';
  organizeFlow.progress = { current: 0, total: 0 };
  updateOrgSection(volumeId);

  try {
    const res = await fetch(`/api/utilities/tasks/${encodeURIComponent(action.executeId)}/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      organizeFlow.state = 'plan-ready';
      updateOrgSection(volumeId);
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    taskCenter.start({ taskId: action.executeId, runId, label: `${action.label}…` });

    const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
    organizeFlow.eventSource = es;

    es.addEventListener('phase.progress', e => {
      const ev = JSON.parse(e.data);
      if (ev.phaseId === 'organize' || ev.phaseId === 'prep' || ev.phaseId === 'timestamps') {
        organizeFlow.progress = { current: ev.current, total: ev.total };
        if (ev.total > 0) taskCenter.updateProgress({ overallPct: Math.floor(100 * ev.current / ev.total) });
        updateOrgSection(volumeId);
      }
    });
    es.addEventListener('phase.ended', e => {
      const ev = JSON.parse(e.data);
      if ((ev.phaseId === 'organize' || ev.phaseId === 'prep' || ev.phaseId === 'timestamps') && ev.summary) {
        try { organizeFlow.execResult = JSON.parse(ev.summary); } catch {}
      }
    });
    es.addEventListener('task.ended', e => {
      const ev = JSON.parse(e.data);
      es.close();
      organizeFlow.eventSource = null;
      taskCenter.finish({ status: ev.status, summary: '' });
      if (ev.status !== 'ok') organizeFlow.error = ev.summary || 'Execute failed';
      organizeFlow.state = 'done';
      updateOrgSection(volumeId);
      refreshVolumes();
    });
    es.onerror = () => {};
  } catch (err) {
    organizeFlow.error = err.message;
    organizeFlow.state = 'done';
    updateOrgSection(volumeId);
  }
}
