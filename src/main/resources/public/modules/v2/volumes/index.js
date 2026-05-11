// volumes/index.js — Entry point for the v2 Volumes workbench page.
//
// Layout: two-column. Left: volume picker list. Right: detail pane with three
// tabs (Health, Operations/Sync, Organize). A separate "Sync Health" section
// below the cards exposes the full reconcile dashboard and per-volume run states.
//
// Run view: when a sync (or clean-stale-locations) starts, the right pane shows
// a phase-by-phase SSE progress view until the task ends, then returns to detail.
//
// probeActiveRun: polls /api/utilities/active at page load. If a task is running
// from a previous navigation, the SSE stream is reattached and the pill + run
// view reconstruct without the user re-triggering.

import * as taskCenter from '../../task-center.js';
import { esc, hueFor, volumeIconSVG, badgeHTML, formatLastSynced } from './cards.js';
import { startSync, confirmAndStartCoherentSync, beginRunView, renderRunView, wireRunDone, activeRun, labelFor } from './sync.js';
import { renderOrgSection, getOrganizeFlow, resetOrganizeFlow } from './organize.js';
import { renderHealthTab, showStaleLocationsVisualize } from './health.js';
import {
  loadReconcileData, renderReconcilePanel, wireReconcilePanel,
  renderVolRows, renderCoherentSubtitle,
  startCoherentSyncFromSyncHealth, startSingleVolumeSyncFromSyncHealth,
  handleTaskCenterUpdate,
} from './reconcile.js';

const SELECTION_KEY = 'volumes.v2.selection';

let volumes      = [];
let selectedId   = null;
let activeTab    = 'health';
let lastDetailVolumeId = null;
let rootEl       = null;

// ── DOM accessors (relative to rootEl) ───────────────────────────────────────
const listEl      = () => rootEl?.querySelector('#vol-picker-list');
const emptyEl     = () => rootEl?.querySelector('#vol-detail-empty');
const detailEl    = () => rootEl?.querySelector('#vol-detail');
const visualizeEl = () => rootEl?.querySelector('#vol-visualize');
const runEl       = () => rootEl?.querySelector('#vol-run');
const syncHealthEl= () => rootEl?.querySelector('#vol-sync-health');

function hideAllRightPanes() {
  for (const el of [emptyEl(), detailEl(), visualizeEl(), runEl()]) {
    if (el) el.style.display = 'none';
  }
}

// ── Entry point ───────────────────────────────────────────────────────────────

export async function mountVolumes(root) {
  rootEl = root;
  rootEl.innerHTML = buildPageHTML();

  // Restore selection.
  selectedId = localStorage.getItem(SELECTION_KEY);

  // Wire the taskCenter floating-pill "open" handler — navigates back here.
  taskCenter.onOpenRequested((state) => {
    if (!state) return;
    // Focus the volumes page when pill is clicked.
    // In a multi-page app this would navigate; here the page is always this module.
  });

  // taskCenter subscription: update buttons + reconcile rows on state change.
  taskCenter.subscribe((active) => {
    handleTaskCenterUpdate(active);
    // Re-render sync-health rows if visible
    renderSyncHealthVolRows();
    // Re-render detail if open and not in run view
    if (detailEl()?.style.display !== 'none' && selectedId) {
      if (getOrganizeFlow()) {
        updateOrgPanel(selectedId);
      } else {
        refreshDetailButtons();
      }
    }
  });

  // Load volumes + reconcile data.
  await Promise.all([refreshVolumes(), loadReconcileData()]);

  // Probe active run (reattach after page reload).
  await probeActiveRun();

  // If run is in progress, show run view.
  if (activeRun && activeRun.taskStatus === 'running') {
    if (activeRun.volumeId) {
      selectedId = activeRun.volumeId;
      localStorage.setItem(SELECTION_KEY, selectedId);
    }
    renderList();
    hideAllRightPanes();
    runEl().style.display = '';
    renderRunViewInPlace();
    renderSyncHealth();
    return;
  }

  renderList();
  renderSyncHealth();

  if (selectedId && volumes.some(v => v.id === selectedId)) {
    openVolume(selectedId);
  } else {
    showEmpty();
  }
}

// ── Active-run probe (reattach after page reload) ─────────────────────────────

async function probeActiveRun() {
  try {
    const res = await fetch('/api/utilities/active');
    if (!res.ok) return;
    const body = await res.json();
    if (!body.active) return;
    const runId = body.runId;
    taskCenter.start({ taskId: body.taskId, runId, label: labelFor(body.taskId) });
    // beginRunView sets activeRun internally and opens the EventSource.
    beginRunView(null, runId, body.taskId, () => {
      renderRunViewInPlace();
      renderSyncHealthVolRows();
    });
  } catch { /* convenience probe — ignore errors */ }
}

// ── Volume list ───────────────────────────────────────────────────────────────

async function refreshVolumes() {
  try {
    const res = await fetch('/api/utilities/volumes');
    volumes = await res.json();
  } catch {
    volumes = [];
  }
  renderList();
}

function renderList() {
  const ul = listEl();
  if (!ul) return;
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

// ── Right-pane dispatch ───────────────────────────────────────────────────────

function openVolume(volumeId) {
  const run = activeRun;
  if (run && run.taskStatus === 'running' && run.volumeId === volumeId) {
    hideAllRightPanes();
    runEl().style.display = '';
    renderRunViewInPlace();
    return;
  }
  showDetail(volumeId);
}

function showEmpty() {
  hideAllRightPanes();
  emptyEl().style.display = '';
}

function showDetail(volumeId) {
  // Reset on volume switch.
  if (lastDetailVolumeId !== volumeId) {
    const flow = getOrganizeFlow();
    if (flow?.eventSource) flow.eventSource.close();
    resetOrganizeFlow();
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
    <div class="vol-kpi-strip">
      ${(v.titleCount || 0).toLocaleString()} titles
      · ${esc(v.structureType || '—')} structure
      · ${esc(formatLastSynced(v.lastSyncedAt))}
    </div>
    <nav class="vol-tab-bar">
      <button type="button" class="vol-tab${activeTab === 'health'   ? ' selected' : ''}" data-tab="health">
        <svg class="vol-tab-icon" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
        Health
      </button>
      <button type="button" class="vol-tab${activeTab === 'sync'     ? ' selected' : ''}" data-tab="sync">
        <svg class="vol-tab-icon" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>
        Operations
      </button>
      <button type="button" class="vol-tab${activeTab === 'organize' ? ' selected' : ''}" data-tab="organize">
        <svg class="vol-tab-icon" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>
        Organize${orgBadge}
      </button>
    </nav>
    <div class="vol-tab-panels">
      <div class="vol-tab-panel" data-panel="health"${activeTab !== 'health'   ? ' style="display:none"' : ''}>
        ${renderHealthTab(v)}
      </div>
      <div class="vol-tab-panel" data-panel="sync"${activeTab !== 'sync'     ? ' style="display:none"' : ''}>
        ${renderSyncTab(v)}
      </div>
      <div class="vol-tab-panel" data-panel="organize"${activeTab !== 'organize' ? ' style="display:none"' : ''}>
        <div id="vol-org-section"></div>
      </div>
    </div>
  `;

  // Wire tab switching.
  d.querySelectorAll('.vol-tab').forEach(btn => {
    btn.addEventListener('click', () => {
      activeTab = btn.getAttribute('data-tab');
      d.querySelectorAll('.vol-tab').forEach(b => b.classList.toggle('selected', b === btn));
      d.querySelectorAll('.vol-tab-panel').forEach(p => {
        p.style.display = p.getAttribute('data-panel') === activeTab ? '' : 'none';
      });
    });
  });

  // Wire health action buttons.
  d.querySelectorAll('.vol-health-action').forEach(btn => {
    btn.addEventListener('click', async () => {
      const cat = btn.getAttribute('data-cat');
      if (cat === 'stale_locations') {
        hideAllRightPanes();
        const pane = visualizeEl();
        pane.style.display = '';
        await showStaleLocationsVisualize(
          volumeId,
          pane,
          () => showDetail(volumeId),
          (vid, runId, taskId) => {
            hideAllRightPanes();
            runEl().style.display = '';
            beginRunView(vid, runId, taskId, () => renderRunViewInPlace());
          }
        );
      }
    });
  });

  // Wire sync buttons.
  d.querySelector('#vol-op-sync')?.addEventListener('click', () => {
    startSync(volumeId, () => {
      hideAllRightPanes();
      runEl().style.display = '';
      renderRunViewInPlace();
      refreshVolumes();
    });
  });

  d.querySelector('#vol-op-coherent-sync')?.addEventListener('click', () => {
    confirmAndStartCoherentSync(() => {
      hideAllRightPanes();
      runEl().style.display = '';
      renderRunViewInPlace();
      refreshVolumes();
    });
  });

  // Populate organize panel.
  updateOrgPanel(volumeId);
}

function renderSyncTab(v) {
  const isBlocked = taskCenter.isRunning();
  const blockedNote = isBlocked
    ? '<div class="vol-op-blocked">Another utility task is running. Wait for it to finish.</div>'
    : '';
  return `
    <button type="button" class="btn primary vol-op-primary" id="vol-op-sync"${isBlocked ? ' disabled' : ''}>Sync</button>
    <div class="vol-op-divider"></div>
    <button type="button" class="btn vol-op-secondary" id="vol-op-coherent-sync"${isBlocked ? ' disabled' : ''}>Coherent sync (all volumes)</button>
    <div class="vol-op-desc">
      Scans every configured volume in turn and evaluates orphans once after all volumes have been
      observed. Recommended for overnight runs after manual cross-volume folder movement.
      Holds the task lock for the duration — may run for hours.
    </div>
    ${blockedNote}
  `;
}

function refreshDetailButtons() {
  if (!selectedId || detailEl()?.style.display === 'none') return;
  const v = volumes.find(x => x.id === selectedId);
  if (!v) return;
  const isBlocked = taskCenter.isRunning();
  const syncBtn = detailEl()?.querySelector('#vol-op-sync');
  const cohBtn  = detailEl()?.querySelector('#vol-op-coherent-sync');
  if (syncBtn) syncBtn.disabled = isBlocked;
  if (cohBtn)  cohBtn.disabled  = isBlocked;
}

// ── Organize panel ────────────────────────────────────────────────────────────

function updateOrgPanel(volumeId) {
  const el = document.getElementById('vol-org-section');
  if (!el) return;
  const v = volumes.find(x => x.id === volumeId) || { id: volumeId, queueCount: 0 };
  const { html, wire } = renderOrgSection(v, updateOrgPanel);
  el.innerHTML = html;
  wire(el);
}

// ── Run view rendering ────────────────────────────────────────────────────────

function renderRunViewInPlace() {
  const r = runEl();
  if (!r) return;
  r.innerHTML = renderRunView(volumes);
  wireRunDone(r, (vid) => {
    // activeRun was cleared by wireRunDone; go to detail or empty.
    if (vid) showDetail(vid);
    else showEmpty();
    refreshVolumes();
  });
}

// ── Sync Health section ───────────────────────────────────────────────────────

function renderSyncHealth() {
  const el = syncHealthEl();
  if (!el) return;

  el.innerHTML = `
    <div class="vol-sh-head">
      <div class="vol-sh-title">Sync Health</div>
      <button type="button" class="btn sm" id="vol-sh-coherent-btn"${taskCenter.isRunning() ? ' disabled' : ''}>
        Coherent sync (all volumes)
      </button>
    </div>
    <div class="vol-sh-subtitle" id="vol-sh-subtitle"></div>
    <div class="vol-sh-vol-list" id="vol-sh-vol-list"></div>
    <div class="vol-sh-divider"></div>
    ${renderReconcilePanel()}
  `;

  el.querySelector('#vol-sh-coherent-btn')?.addEventListener('click', () => {
    startCoherentSyncFromSyncHealth(volumes, renderSyncHealthVolRows);
  });

  // Per-volume sync buttons.
  wireVolRows(el);

  // Wire reconcile actions.
  wireReconcilePanel(el, renderSyncHealth);

  // Render initial vol rows.
  renderSyncHealthVolRows();
}

function renderSyncHealthVolRows() {
  const listEl2 = document.getElementById('vol-sh-vol-list');
  if (!listEl2) return;
  listEl2.innerHTML = renderVolRows(volumes, null);
  wireVolRows(document.getElementById('vol-sync-health'));

  const subEl = document.getElementById('vol-sh-subtitle');
  if (subEl) subEl.innerHTML = renderCoherentSubtitle();

  // Keep coherent-sync button in sync with task state.
  const cohBtn = document.getElementById('vol-sh-coherent-btn');
  if (cohBtn) cohBtn.disabled = taskCenter.isRunning();
}

function wireVolRows(parentEl) {
  if (!parentEl) return;
  parentEl.querySelectorAll('.vol-sh-vol-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const action = btn.dataset.action;
      const volumeId = btn.dataset.vol;
      if (action === 'sync') {
        startSingleVolumeSyncFromSyncHealth(volumeId, volumes, renderSyncHealthVolRows);
      }
    });
  });
}

// ── Page scaffold HTML ────────────────────────────────────────────────────────

function buildPageHTML() {
  return `
    <div class="wb-page vol-wb">
      <div class="vol-layout">
        <!-- Left: volume picker -->
        <aside class="vol-sidebar">
          <div class="vol-sidebar-head">Volumes</div>
          <ul class="vol-picker-list" id="vol-picker-list"></ul>
        </aside>

        <!-- Right: detail / run / visualize / empty panes -->
        <div class="vol-main">
          <!-- Empty state -->
          <div id="vol-detail-empty" class="vol-empty">
            <span class="vol-empty-glyph">◌</span>
            <div>Select a volume to view details and operations.</div>
          </div>

          <!-- Detail pane (tabs) -->
          <div id="vol-detail" style="display:none"></div>

          <!-- Visualize pane (stale-locations preview) -->
          <div id="vol-visualize" style="display:none" class="vol-visualize-pane"></div>

          <!-- Run pane (SSE progress) -->
          <div id="vol-run" style="display:none" class="vol-run-pane"></div>
        </div>
      </div>

      <!-- Sync Health — full-width below the two-column layout -->
      <div id="vol-sync-health" class="vol-sync-health-section"></div>
    </div>
  `;
}
