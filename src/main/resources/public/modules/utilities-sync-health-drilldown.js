// utilities-sync-health-drilldown.js
// Drilldown detail panel for the four reconcile signals on the Sync Health view.
// Exports a single mount() function; the parent utilities-sync-health.js calls it.
//
// Detail panel layout: inline below the signals card (one panel at a time; clicking
// a different signal swaps; re-clicking the same collapses).
//
// Signals:
//   dupLive     — Duplicate live locations
//   pendingGrace — Pending-grace stale rows
//   pastGrace   — Past-grace stragglers
//   mismatch    — Actress folder mismatches

import * as taskCenter from './task-center.js';

// ── Helpers ───────────────────────────────────────────────────────────────────

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

// ── State ─────────────────────────────────────────────────────────────────────

let selectedSignal = null;   // 'dupLive' | 'pendingGrace' | 'pastGrace' | 'mismatch' | null
let cachedDetails  = null;   // parsed details object from last verbose run
let liveMode       = false;  // true when cachedDetails came from the current live view

// Callbacks provided by the parent
let _onActionSuccess = () => {};  // called after a successful action — parent re-renders counts

// ── Signal metadata ───────────────────────────────────────────────────────────

const SIGNALS = {
  dupLive:      { countField: 'duplicateLiveLocations', label: 'Duplicate live locations', detailKey: 'duplicateLive' },
  pendingGrace: { countField: 'pendingGrace',            label: 'Pending grace',             detailKey: 'pendingGrace'  },
  pastGrace:    { countField: 'pastGraceStragglers',     label: 'Past-grace stragglers',     detailKey: 'pastGrace'     },
  mismatch:     { countField: 'actressFolderMismatches', label: 'Actress folder mismatches', detailKey: 'mismatches'    },
};

// ── Mount ─────────────────────────────────────────────────────────────────────

/**
 * Mount the drilldown feature.
 *
 * @param {Object} opts
 * @param {Function} opts.onActionSuccess — callback invoked after a successful action
 *        (parent should refresh reconcile counts + rebuild cards).
 */
export function mount({ onActionSuccess } = {}) {
  if (onActionSuccess) _onActionSuccess = onActionSuccess;
}

// ── Public API ────────────────────────────────────────────────────────────────

/**
 * Sync fresh detail data from a PersistedReport. Called after every
 * reconcile data reload. `report` is the PersistedReport object (from
 * GET /api/reconcile/recent?limit=1). If it has a `detailJson` field,
 * parse and cache it.
 *
 * @param {Object} report       PersistedReport object (may be null)
 * @param {boolean} isLive      true when this came from a just-run verbose reconcile
 *                              (false = passive reload; does NOT downgrade a live session)
 */
export function updateDetailCache(report, isLive) {
  // If we're already in a live session, a passive reload (isLive=false) should
  // not downgrade live mode — only a new reconcile run or explicit invalidation
  // should change the live flag.
  if (!isLive && liveMode) return;

  if (!report || !report.detailJson) {
    cachedDetails = null;
    liveMode = false;
    return;
  }
  try {
    cachedDetails = JSON.parse(report.detailJson);
    liveMode = !!isLive;
  } catch (e) {
    console.warn('sync-health-drilldown: failed to parse detailJson', e);
    cachedDetails = null;
    liveMode = false;
  }
}

/**
 * Returns true when any signal card should show a clickable chevron.
 * A signal is clickable only when count > 0 and we are in live mode (or have cached details).
 */
export function isSignalClickable(signal, lastReconcile) {
  if (!lastReconcile) return false;
  const meta = SIGNALS[signal];
  if (!meta) return false;
  return (lastReconcile[meta.countField] ?? 0) > 0;
}

/**
 * Handle a click on a signal card. Toggles the selected signal.
 * If we have cached live details, renders immediately.
 * If not, triggers a verbose run first.
 *
 * @param {string} signal
 * @param {Object} lastReconcile — current reconcile counts object
 */
export async function handleSignalClick(signal, lastReconcile) {
  if (!isSignalClickable(signal, lastReconcile)) return;

  if (selectedSignal === signal) {
    // Re-click collapses
    selectedSignal = null;
    renderPanel();
    return;
  }

  selectedSignal = signal;

  // If we have live cached details already, render immediately.
  if (cachedDetails && liveMode) {
    renderPanel();
    return;
  }

  // No live details — run a verbose reconcile to populate them.
  renderPanelLoading(signal);
  try {
    await runVerboseReconcile();
  } catch (e) {
    renderPanelError('Failed to load details: ' + e.message);
    return;
  }
  renderPanel();
}

/**
 * Clear the panel and reset state. Called when hiding the sync-health view.
 */
export function clearPanel() {
  selectedSignal = null;
  renderPanel();
}

// ── Verbose reconcile run ────────────────────────────────────────────────────

async function runVerboseReconcile() {
  // POST run with verbose:true
  const runRes = await fetch('/api/reconcile/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ verbose: true }),
  });
  if (!runRes.ok) throw new Error(`Reconcile returned ${runRes.status}`);

  // Fetch the most recent stored report to get detailJson
  const recentRes = await fetch('/api/reconcile/recent?limit=1');
  if (!recentRes.ok) throw new Error(`Could not retrieve report details`);
  const list = await recentRes.json();
  const report = Array.isArray(list) && list.length > 0 ? list[0] : null;
  updateDetailCache(report, true);

  // Let the parent know counts may have changed
  _onActionSuccess();
}

// ── Panel rendering ──────────────────────────────────────────────────────────

function panelEl() {
  return document.getElementById('sh-detail-panel');
}

function renderPanel() {
  const el = panelEl();
  if (!el) return;

  if (!selectedSignal) {
    el.style.display = 'none';
    el.innerHTML = '';
    return;
  }

  const meta = SIGNALS[selectedSignal];
  if (!meta) { el.style.display = 'none'; return; }

  el.style.display = '';

  if (!cachedDetails) {
    el.innerHTML = renderPanelEmptyHtml(meta.label, 'No detail data available. Run a verbose reconcile first.');
    return;
  }

  const rows = cachedDetails[meta.detailKey] || [];
  const PAGE = 50;
  const displayRows = rows.slice(0, PAGE);
  const truncated = rows.length > PAGE;

  let bodyHtml;
  switch (selectedSignal) {
    case 'dupLive':
      bodyHtml = renderDupLiveRows(displayRows, truncated, rows.length);
      break;
    case 'pendingGrace':
      bodyHtml = renderGraceRows(displayRows, truncated, rows.length, 'pending');
      break;
    case 'pastGrace':
      bodyHtml = renderGraceRows(displayRows, truncated, rows.length, 'past');
      break;
    case 'mismatch':
      bodyHtml = renderMismatchRows(displayRows, truncated, rows.length);
      break;
    default:
      bodyHtml = '';
  }

  el.innerHTML = `
    <div class="sh-dp-card">
      <div class="sh-dp-head">
        <span class="sh-dp-title">${esc(meta.label)}</span>
        <span class="sh-dp-count">${rows.length} row${rows.length === 1 ? '' : 's'}</span>
        <button type="button" class="sh-dp-close" title="Collapse">×</button>
      </div>
      ${!liveMode ? '<div class="sh-dp-readonly-banner">Historical report — actions unavailable. Run a new reconcile to enable actions.</div>' : ''}
      <div class="sh-dp-body">
        ${rows.length === 0
          ? '<div class="sh-dp-empty">No rows to show.</div>'
          : bodyHtml
        }
      </div>
    </div>
  `;

  el.querySelector('.sh-dp-close')?.addEventListener('click', () => {
    selectedSignal = null;
    renderPanel();
    updateCardChevrons();
  });

  wirePanelActions(el);
}

function renderPanelLoading(signal) {
  const el = panelEl();
  if (!el) return;
  const meta = SIGNALS[signal] || {};
  el.style.display = '';
  el.innerHTML = `
    <div class="sh-dp-card">
      <div class="sh-dp-head">
        <span class="sh-dp-title">${esc(meta.label || signal)}</span>
        <button type="button" class="sh-dp-close" title="Collapse">×</button>
      </div>
      <div class="sh-dp-body"><div class="sh-dp-loading">Running reconcile to load details…</div></div>
    </div>
  `;
  el.querySelector('.sh-dp-close')?.addEventListener('click', () => {
    selectedSignal = null;
    renderPanel();
    updateCardChevrons();
  });
}

function renderPanelError(msg) {
  const el = panelEl();
  if (!el) return;
  el.style.display = '';
  el.innerHTML = `
    <div class="sh-dp-card sh-dp-card--error">
      <div class="sh-dp-head">
        <span class="sh-dp-title">Error</span>
        <button type="button" class="sh-dp-close" title="Collapse">×</button>
      </div>
      <div class="sh-dp-body"><div class="sh-dp-error">${esc(msg)}</div></div>
    </div>
  `;
  el.querySelector('.sh-dp-close')?.addEventListener('click', () => {
    selectedSignal = null;
    renderPanel();
    updateCardChevrons();
  });
}

function renderPanelEmptyHtml(title, msg) {
  return `
    <div class="sh-dp-card">
      <div class="sh-dp-head">
        <span class="sh-dp-title">${esc(title)}</span>
        <button type="button" class="sh-dp-close" title="Collapse">×</button>
      </div>
      <div class="sh-dp-body"><div class="sh-dp-empty">${esc(msg)}</div></div>
    </div>
  `;
}

// ── Row renderers ─────────────────────────────────────────────────────────────

function renderDupLiveRows(rows, truncated, total) {
  const rowsHtml = rows.map(r => {
    const locs = r.locations || [];
    const locsHtml = locs.map(l =>
      `<span class="sh-dp-loc">vol-${esc(l.volumeId)}<span class="sh-dp-loc-path" title="${esc(l.path)}">${esc(shortenPath(l.path))}</span></span>`
    ).join('<span class="sh-dp-loc-sep">·</span>');

    const actionsHtml = liveMode ? locs.map(l =>
      `<button type="button" class="sh-dp-btn sh-dp-btn--trust"
         data-action="trust-volume"
         data-title-id="${esc(r.titleId)}"
         data-trust-vol="${esc(l.volumeId)}"
         data-other-vols="${esc(locs.filter(x => x.volumeId !== l.volumeId).map(x => x.volumeId).join(','))}">Trust vol-${esc(l.volumeId)}</button>`
    ).join('') : '';

    return `<div class="sh-dp-row">
      <div class="sh-dp-row-left">
        <span class="sh-dp-code">${esc(r.code)}</span>
        <span class="sh-dp-locs">${locsHtml}</span>
      </div>
      <div class="sh-dp-row-actions">
        ${actionsHtml}
        <button type="button" class="sh-dp-btn sh-dp-btn--open"
          data-action="open-title" data-code="${esc(r.code)}">Open title</button>
      </div>
    </div>`;
  }).join('');

  return rowsHtml + renderTruncatedNote(truncated, total);
}

function renderGraceRows(rows, truncated, total, kind) {
  const rowsHtml = rows.map(r => {
    const daysLabel = r.daysStale != null ? `${r.daysStale}d stale` : '';
    const staleClass = kind === 'past' ? 'sh-dp-days--warn' : 'sh-dp-days--pending';

    const actionsHtml = liveMode
      ? (kind === 'past'
          ? `<button type="button" class="sh-dp-btn sh-dp-btn--sweep"
               data-action="sweep-row"
               data-location-id="${esc(r.locationId)}"
               data-code="${esc(r.code)}">Sweep this row</button>`
          : `<button type="button" class="sh-dp-btn sh-dp-btn--sync"
               data-action="sync-vol"
               data-vol="${esc(r.volumeId)}"
               data-code="${esc(r.code)}">Sync vol-${esc(r.volumeId)}</button>`)
      : '';

    return `<div class="sh-dp-row">
      <div class="sh-dp-row-left">
        <span class="sh-dp-code">${esc(r.code)}</span>
        <span class="sh-dp-vol">vol-${esc(r.volumeId)}</span>
        <span class="sh-dp-path" title="${esc(r.path)}">${esc(shortenPath(r.path))}</span>
        ${daysLabel ? `<span class="sh-dp-days ${staleClass}">${esc(daysLabel)}</span>` : ''}
      </div>
      <div class="sh-dp-row-actions">
        ${actionsHtml}
        <button type="button" class="sh-dp-btn sh-dp-btn--open"
          data-action="open-title" data-code="${esc(r.code)}">Open title</button>
      </div>
    </div>`;
  }).join('');

  return rowsHtml + renderTruncatedNote(truncated, total);
}

function renderMismatchRows(rows, truncated, total) {
  const rowsHtml = rows.map(r => {
    const actionsHtml = liveMode
      ? `<button type="button" class="sh-dp-btn sh-dp-btn--actress"
           data-action="open-actress"
           data-actress-id="${esc(r.actressId)}"
           data-actress-name="${esc(r.actressName)}">Open in Misnamed Folders</button>`
      : '';

    return `<div class="sh-dp-row">
      <div class="sh-dp-row-left">
        <span class="sh-dp-code">${esc(r.code)}</span>
        <span class="sh-dp-actress">${esc(r.actressName)}</span>
        <span class="sh-dp-vol">vol-${esc(r.volumeId)}</span>
        <span class="sh-dp-path" title="${esc(r.path)}">${esc(shortenPath(r.path))}</span>
      </div>
      <div class="sh-dp-row-actions">
        ${actionsHtml}
        <button type="button" class="sh-dp-btn sh-dp-btn--open"
          data-action="open-title" data-code="${esc(r.code)}">Open title</button>
      </div>
    </div>`;
  }).join('');

  return rowsHtml + renderTruncatedNote(truncated, total);
}

function renderTruncatedNote(truncated, total) {
  if (!truncated) return '';
  return `<div class="sh-dp-truncated">Showing 50 of ${total} rows.
    <button type="button" class="sh-dp-load-more" data-action="load-more">Show all</button>
  </div>`;
}

// ── Action wiring ────────────────────────────────────────────────────────────

function wirePanelActions(el) {
  el.querySelectorAll('[data-action]').forEach(btn => {
    btn.addEventListener('click', e => handleAction(e.currentTarget));
  });
}

async function handleAction(btn) {
  const action = btn.dataset.action;

  switch (action) {
    case 'trust-volume':
      await handleTrustVolume(btn);
      break;
    case 'sweep-row':
      await handleSweepRow(btn);
      break;
    case 'sync-vol':
      await handleSyncVol(btn);
      break;
    case 'open-title':
      await handleOpenTitle(btn);
      break;
    case 'open-actress':
      await handleOpenActress(btn);
      break;
    case 'load-more':
      handleLoadMore();
      break;
  }
}

// ── Trust-volume with confirmation modal ─────────────────────────────────────

async function handleTrustVolume(btn) {
  const titleId     = btn.dataset.titleId;
  const trustVolId  = btn.dataset.trustVol;
  const otherVols   = (btn.dataset.otherVols || '').split(',').filter(Boolean);
  const syncVolId   = otherVols[0] || '(other)';

  // Show confirmation modal
  const confirmed = await showTrustVolumeModal(trustVolId, syncVolId);
  if (!confirmed) return;

  if (taskCenter.isRunning()) {
    showToast('Another task is already running. Please wait.', 'error');
    return;
  }

  btn.disabled = true;
  btn.textContent = 'Starting…';
  try {
    const res = await fetch(
      `/api/reconcile/trust-volume?titleId=${encodeURIComponent(titleId)}&trustVolumeId=${encodeURIComponent(trustVolId)}`,
      { method: 'POST' }
    );

    if (res.status === 202) {
      const body = await res.json();
      // Wire up taskCenter so the sync pill appears
      taskCenter.start({
        taskId: body.taskId,
        runId:  body.runId,
        label:  `Syncing vol-${body.otherVolumeId} (trust-volume)`,
      });
      // Re-open the event source via the parent's handleVolAction path would need
      // access to the parent's openEventSource. We start via taskCenter and let
      // the TaskRunner event stream update the pill; a full EventSource subscription
      // for run tracking is intentionally out of scope here (the parent's sync
      // machinery will handle it if/when the user triggers a coherent sync).
      // We subscribe to task completion to trigger a panel refresh.
      subscribeToTrustVolumeRun(body.runId);

      const partitionHint = body.otherPartitionId
        ? ` (partition ${body.otherPartitionId})`
        : '';
      showToast(`Sync of vol-${body.otherVolumeId}${partitionHint} started.`);
      _onActionSuccess();
    } else if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      showToast(body.error || 'Cannot trust volume: conflict.', 'error');
    } else {
      const body = await res.json().catch(() => ({}));
      showToast(body.error || `Unexpected status ${res.status}`, 'error');
    }
  } catch (e) {
    showToast('Request failed: ' + e.message, 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = `Trust vol-${trustVolId}`;
  }
}

function subscribeToTrustVolumeRun(runId) {
  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  es.addEventListener('task.ended', e => {
    const ev = JSON.parse(e.data);
    taskCenter.finish({ status: ev.status, summary: ev.summary });
    es.close();
    _onActionSuccess();
    // Invalidate cache so next drilldown click re-runs verbose reconcile
    cachedDetails = null;
    liveMode = false;
  });
  es.onerror = () => { es.close(); };
}

// ── Sweep-row action ─────────────────────────────────────────────────────────

async function handleSweepRow(btn) {
  const locationId = btn.dataset.locationId;
  const code       = btn.dataset.code;

  btn.disabled = true;
  btn.textContent = 'Sweeping…';
  try {
    const res = await fetch(
      `/api/reconcile/sweep-row?id=${encodeURIComponent(locationId)}`,
      { method: 'POST' }
    );

    if (res.ok) {
      showToast(`Swept row for ${code}.`);
      _onActionSuccess();
      cachedDetails = null;
      liveMode = false;
      selectedSignal = null;
      renderPanel();
      updateCardChevrons();
    } else if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      const staleTxt = body.staleDays != null ? `${body.staleDays}d stale` : 'not stale';
      showToast(`Row not past grace (${staleTxt}, grace=${body.graceDays}d).`, 'error');
    } else if (res.status === 404) {
      showToast('Row not found (may have already been swept).', 'error');
      _onActionSuccess();
      cachedDetails = null;
      liveMode = false;
    } else {
      showToast(`Unexpected status ${res.status}`, 'error');
    }
  } catch (e) {
    showToast('Request failed: ' + e.message, 'error');
  } finally {
    if (btn.isConnected) {
      btn.disabled = false;
      btn.textContent = 'Sweep this row';
    }
  }
}

// ── Sync-vol action (pending-grace) ──────────────────────────────────────────

async function handleSyncVol(btn) {
  const volumeId = btn.dataset.vol;
  const code     = btn.dataset.code;

  if (taskCenter.isRunning()) {
    showToast('Another task is already running. Please wait.', 'error');
    return;
  }

  btn.disabled = true;
  btn.textContent = 'Starting…';
  try {
    const res = await fetch('/api/utilities/tasks/volume.sync/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });

    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      showToast(body.error || 'Task already running.', 'error');
    } else if (res.ok) {
      const { runId } = await res.json();
      taskCenter.start({
        taskId: 'volume.sync',
        runId,
        label: `Syncing vol-${volumeId}`,
      });
      subscribeToSyncVolRun(runId, volumeId);
      showToast(`Sync of vol-${volumeId} started.`);
      _onActionSuccess();
    } else {
      showToast(`Sync failed (${res.status})`, 'error');
    }
  } catch (e) {
    showToast('Request failed: ' + e.message, 'error');
  } finally {
    if (btn.isConnected) {
      btn.disabled = false;
      btn.textContent = `Sync vol-${volumeId}`;
    }
  }
}

function subscribeToSyncVolRun(runId) {
  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  es.addEventListener('task.ended', e => {
    const ev = JSON.parse(e.data);
    taskCenter.finish({ status: ev.status, summary: ev.summary });
    es.close();
    _onActionSuccess();
    cachedDetails = null;
    liveMode = false;
  });
  es.onerror = () => { es.close(); };
}

// ── Open-title action ─────────────────────────────────────────────────────────

async function handleOpenTitle(btn) {
  const code = btn.dataset.code;
  if (!code) return;
  try {
    const { openTitleDetail } = await import('./title-detail.js');
    await openTitleDetail({ code });
  } catch (e) {
    console.error('sync-health-drilldown: openTitle failed', e);
  }
}

// ── Open-actress (mismatch) action ───────────────────────────────────────────

async function handleOpenActress(btn) {
  const actressId   = Number(btn.dataset.actressId);
  const actressName = btn.dataset.actressName;
  try {
    const { openActressDetail } = await import('./actress-detail.js');
    await openActressDetail(actressId);
  } catch (e) {
    console.error('sync-health-drilldown: openActress failed for', actressName, e);
  }
}

// ── Load-more action ──────────────────────────────────────────────────────────

function handleLoadMore() {
  // Re-render with all rows by temporarily inflating the page size
  renderPanelAllRows();
}

function renderPanelAllRows() {
  const el = panelEl();
  if (!el || !selectedSignal || !cachedDetails) return;

  const meta = SIGNALS[selectedSignal];
  if (!meta) return;

  const rows = cachedDetails[meta.detailKey] || [];

  let bodyHtml;
  switch (selectedSignal) {
    case 'dupLive':      bodyHtml = renderDupLiveRows(rows, false, rows.length);   break;
    case 'pendingGrace': bodyHtml = renderGraceRows(rows, false, rows.length, 'pending'); break;
    case 'pastGrace':    bodyHtml = renderGraceRows(rows, false, rows.length, 'past');    break;
    case 'mismatch':     bodyHtml = renderMismatchRows(rows, false, rows.length);  break;
    default:             bodyHtml = '';
  }

  const body = el.querySelector('.sh-dp-body');
  if (body) {
    body.innerHTML = bodyHtml;
    wirePanelActions(el);
  }
}

// ── Trust-volume confirmation modal ─────────────────────────────────────────

/**
 * Show a confirmation modal for trust-volume and return a Promise<boolean>.
 */
function showTrustVolumeModal(trustVolId, syncVolId) {
  return new Promise(resolve => {
    // Remove any existing modal
    document.getElementById('sh-trust-modal')?.remove();

    const backdrop = document.createElement('div');
    backdrop.id = 'sh-trust-modal';
    backdrop.className = 'sh-modal-backdrop';
    backdrop.innerHTML = `
      <div class="sh-modal" role="dialog" aria-modal="true" aria-labelledby="sh-modal-title">
        <div class="sh-modal-head" id="sh-modal-title">Trust vol-${esc(trustVolId)}?</div>
        <div class="sh-modal-body">
          <p>Trusting <strong>vol-${esc(trustVolId)}</strong> means the system believes that volume holds the
          canonical copy of this title.</p>
          <p>A <strong>full volume sync of vol-${esc(syncVolId)}</strong> will be started immediately — this
          syncs the entire other volume, not just the affected partition. Any title that has
          moved away from vol-${esc(syncVolId)} will be marked stale on the next sync pass.</p>
          <p class="sh-modal-note">Note: the sync runs asynchronously; the task lock will be held
          while it completes.</p>
        </div>
        <div class="sh-modal-actions">
          <button type="button" class="sh-modal-btn sh-modal-btn--cancel" id="sh-modal-cancel">Cancel</button>
          <button type="button" class="sh-modal-btn sh-modal-btn--confirm" id="sh-modal-confirm">Sync vol-${esc(syncVolId)} now</button>
        </div>
      </div>
    `;

    document.body.appendChild(backdrop);

    const cleanup = (result) => {
      backdrop.remove();
      document.removeEventListener('keydown', keyHandler);
      resolve(result);
    };

    const keyHandler = (e) => { if (e.key === 'Escape') cleanup(false); };
    document.addEventListener('keydown', keyHandler);

    backdrop.addEventListener('click', e => { if (e.target === backdrop) cleanup(false); });
    document.getElementById('sh-modal-cancel').addEventListener('click',  () => cleanup(false));
    document.getElementById('sh-modal-confirm').addEventListener('click', () => cleanup(true));
  });
}

// ── Toast notification ───────────────────────────────────────────────────────

function showToast(msg, kind = 'success') {
  const t = document.createElement('div');
  t.className = 'sh-toast' + (kind === 'error' ? ' sh-toast--error' : '');
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 4000);
}

// ── Card chevron update ──────────────────────────────────────────────────────

/**
 * Update chevron direction on signal cards based on current selectedSignal.
 * Also adds/removes the --open class on the cards.
 */
export function updateCardChevrons() {
  const signalKeys = ['dupLive', 'pendingGrace', 'pastGrace', 'mismatch'];
  for (const sig of signalKeys) {
    const chevron = document.getElementById(`sh-rc-chevron-${sig}`);
    const card    = document.getElementById(`sh-rc-count-${sig}`);
    if (chevron) chevron.textContent = selectedSignal === sig ? '▲' : '▼';
    if (card) card.classList.toggle('sh-rc-count--open', selectedSignal === sig);
  }
}

// ── Utility helpers ───────────────────────────────────────────────────────────

function shortenPath(path) {
  if (!path) return '';
  // Show last 2 segments of the path to keep it compact
  const parts = path.replace(/\\/g, '/').split('/').filter(Boolean);
  if (parts.length <= 2) return path;
  return '…/' + parts.slice(-2).join('/');
}
