// volumes/reconcile.js — Reconcile dashboard: last-reconcile age, last-coherent-run
// age, four signal tiles, 30-row history table, Run / Run+Sweep buttons, and
// per-volume row state machine for coherent sync tracking.
// Mirrors utilities-sync-health.js reconcile + volume-actions logic verbatim; endpoints unchanged.
// Drilldown: signal tiles are clickable when count > 0; detail panel renders inline below tiles.

import * as taskCenter from '../../task-center.js';
import { esc, formatLastSynced } from './cards.js';

// ── Module-level state ────────────────────────────────────────────────────────
let lastReconcile   = null;  // most recent PersistedReport from /api/reconcile/recent?limit=1
let lastCoherentRun = null;  // PersistedReport or null from /api/reconcile/last?trigger=coherent_sync

// Drilldown state
let selectedSignal  = null;  // 'dupLive' | 'pendingGrace' | 'pastGrace' | 'mismatch' | null
let cachedDetails   = null;  // parsed detail object from a verbose reconcile run
let liveMode        = false; // true when details came from a live verbose run

const SIGNALS = {
  dupLive:      { countField: 'duplicateLiveLocations', label: 'Duplicate live locations', detailKey: 'duplicateLive' },
  pendingGrace: { countField: 'pendingGrace',            label: 'Pending grace',             detailKey: 'pendingGrace'  },
  pastGrace:    { countField: 'pastGraceStragglers',     label: 'Past-grace stragglers',     detailKey: 'pastGrace'     },
  mismatch:     { countField: 'actressFolderMismatches', label: 'Actress folder mismatches', detailKey: 'mismatches'    },
};

// Per-volume run-state during a coherent sync.
// Map<volumeId, { state: 'idle'|'queued'|'scanning'|'cancelling'|'done'|'failed'|'skipped',
//                 startedAt, endedAt, detail, errorMsg, current, total }>
export let volStates = new Map();
export let activeRunId   = null;
export let activeES      = null;
export let runIsCoherent = false;
export let runStartedAt  = null;
export let coherentTotal = 0;
export let coherentDone  = 0;
export let currentVolId  = null;
let tickInterval = null;

// ── Data loading ──────────────────────────────────────────────────────────────
export async function loadReconcileData() {
  await Promise.all([loadLatestReconcile(), loadLastCoherentRun()]);
}

async function loadLatestReconcile() {
  try {
    const res = await fetch('/api/reconcile/recent?limit=1');
    if (!res.ok) { lastReconcile = null; return; }
    const list = await res.json();
    lastReconcile = Array.isArray(list) && list.length > 0 ? list[0] : null;
  } catch { lastReconcile = null; }
}

async function loadLastCoherentRun() {
  try {
    const res = await fetch('/api/reconcile/last?trigger=coherent_sync');
    lastCoherentRun = res.ok ? await res.json() : null;
  } catch { lastCoherentRun = null; }
}

// ── Format helpers ────────────────────────────────────────────────────────────
function formatAge(date) {
  const secs = Math.floor((Date.now() - date.getTime()) / 1000);
  if (secs < 60)   return 'just now';
  if (secs < 3600) return Math.floor(secs / 60) + 'm ago';
  if (secs < 86400) return Math.floor(secs / 3600) + 'h ago';
  const days = Math.floor(secs / 86400);
  if (days === 1) return 'yesterday';
  if (days < 30)  return days + ' days ago';
  return date.toLocaleDateString();
}

function formatDuration(ms) {
  const totalSecs = Math.floor(ms / 1000);
  const mins = Math.floor(totalSecs / 60);
  const secs = totalSecs % 60;
  if (mins === 0) return `${secs}s`;
  return `${mins}m ${String(secs).padStart(2, '0')}s`;
}

// ── Reconcile panel HTML ──────────────────────────────────────────────────────

export function renderReconcilePanel() {
  const r = lastReconcile;
  const rcAge = r?.generatedAt
    ? `<span title="${esc(new Date(r.generatedAt).toLocaleString())}">Last reconcile: ${formatAge(new Date(r.generatedAt))}</span>`
    : 'Last reconcile: never run';

  const chAge = lastCoherentRun?.generatedAt
    ? `<span title="${esc(new Date(lastCoherentRun.generatedAt).toLocaleString())}">Last coherent run: ${formatAge(new Date(lastCoherentRun.generatedAt))}</span>`
    : 'Last coherent run: never';

  const numCell = (val, warnAt) => {
    if (val == null) return `<span class="vol-rc-n">—</span>`;
    const cls = val >= warnAt ? ' warn' : '';
    return `<span class="vol-rc-n${cls}">${val}</span>`;
  };

  return `
    <div class="vol-reconcile-panel">
      <div class="vol-reconcile-age-bar">
        <span class="vol-reconcile-age">${rcAge}</span>
        <span class="vol-reconcile-age">${chAge}</span>
      </div>

      <div class="vol-rc-tiles">
        <div class="vol-rc-tile" id="vol-rc-tile-dupLive">
          <div class="vol-rc-tile-label">Duplicate live locations</div>
          <div class="vol-rc-tile-val">${numCell(r?.duplicateLiveLocations, 1)}</div>
          <div class="vol-rc-tile-chevron" id="vol-rc-chevron-dupLive">▼</div>
        </div>
        <div class="vol-rc-tile" id="vol-rc-tile-pendingGrace">
          <div class="vol-rc-tile-label">Pending grace</div>
          <div class="vol-rc-tile-val">${numCell(r?.pendingGrace, 1)}</div>
          <div class="vol-rc-tile-chevron" id="vol-rc-chevron-pendingGrace">▼</div>
        </div>
        <div class="vol-rc-tile" id="vol-rc-tile-pastGrace">
          <div class="vol-rc-tile-label">Past-grace stragglers</div>
          <div class="vol-rc-tile-val">${numCell(r?.pastGraceStragglers, 1)}</div>
          <div class="vol-rc-tile-chevron" id="vol-rc-chevron-pastGrace">▼</div>
        </div>
        <div class="vol-rc-tile" id="vol-rc-tile-mismatch">
          <div class="vol-rc-tile-label">Actress folder mismatches</div>
          <div class="vol-rc-tile-val">${numCell(r?.actressFolderMismatches, 1)}</div>
          <div class="vol-rc-tile-chevron" id="vol-rc-chevron-mismatch">▼</div>
        </div>
      </div>

      <!-- Drilldown detail panel (inline below tiles) -->
      <div id="vol-rc-detail-panel" class="vol-rc-detail-panel" style="display:none"></div>

      <div class="vol-rc-actions">
        <button type="button" class="btn sm" id="vol-rc-run"${taskCenter.isRunning() ? ' disabled' : ''}>Run reconcile</button>
        <button type="button" class="btn sm" id="vol-rc-sweep"${taskCenter.isRunning() ? ' disabled' : ''}>Run + sweep past-grace</button>
      </div>

      <div class="vol-rc-history-head">Reconcile history (last 30)</div>
      <div class="vol-rc-history-wrap">
        <table class="vol-rc-history-table">
          <thead>
            <tr>
              <th>Time</th>
              <th>Trigger</th>
              <th>Dup locs</th>
              <th>Pending</th>
              <th>Past-grace</th>
              <th>Folder mismatch</th>
            </tr>
          </thead>
          <tbody id="vol-rc-history-tbody"><tr><td colspan="6" class="vol-rc-history-empty">Loading…</td></tr></tbody>
        </table>
      </div>
    </div>
  `;
}

export function wireReconcilePanel(el, onRefresh) {
  const runBtn   = el.querySelector('#vol-rc-run');
  const sweepBtn = el.querySelector('#vol-rc-sweep');

  if (runBtn)   runBtn.addEventListener('click', () => runReconcile(false, el, onRefresh));
  if (sweepBtn) sweepBtn.addEventListener('click', () => {
    const past = lastReconcile?.pastGraceStragglers ?? 0;
    const msg = past > 0
      ? `Run reconcile and sweep ${past} past-grace stale row(s)? This deletes location rows older than the grace window.`
      : 'Run reconcile and sweep past-grace stale rows? (None currently — sweep will be a no-op.)';
    if (!confirm(msg)) return;
    runReconcile(true, el, onRefresh);
  });

  // Wire drilldown tile clicks
  updateDetailCacheFromReport(lastReconcile, false);
  wireTileClicks(el, onRefresh);
  updateTileStates(el);

  loadReportsTable();
}

async function runReconcile(sweep, el, onRefresh) {
  const runBtn   = el?.querySelector('#vol-rc-run');
  const sweepBtn = el?.querySelector('#vol-rc-sweep');
  const origRun   = runBtn?.textContent;
  const origSweep = sweepBtn?.textContent;
  if (runBtn)   { runBtn.disabled   = true; runBtn.textContent   = sweep ? 'Sweeping…' : 'Running…'; }
  if (sweepBtn) { sweepBtn.disabled = true; sweepBtn.textContent = sweep ? 'Sweeping…' : 'Running…'; }
  try {
    const res = await fetch('/api/reconcile/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ verbose: false, sweep: !!sweep }),
    });
    if (!res.ok) { alert(`Reconcile failed (${res.status})`); return; }
    const body = await res.json();
    lastReconcile = body;
    // Invalidate drilldown cache after a new reconcile run
    cachedDetails = null;
    liveMode = false;
    selectedSignal = null;
    hideDrilldownPanel();
    updateTileStates(el);
    if (onRefresh) onRefresh();
    loadReportsTable();
    if (sweep) {
      const result = body.sweepResult || `Swept ${body.sweptCount ?? 0} row(s)`;
      alert(result);
    }
  } catch (e) {
    alert('Reconcile failed: ' + e.message);
  } finally {
    if (runBtn)   { runBtn.textContent   = origRun;   runBtn.disabled   = false; }
    if (sweepBtn) { sweepBtn.textContent = origSweep; sweepBtn.disabled = false; }
  }
}

// ── Drilldown helpers (v2 reconcile) ─────────────────────────────────────────

function updateDetailCacheFromReport(report, isLive) {
  if (!report || !report.detailJson) {
    cachedDetails = null;
    liveMode = false;
    return;
  }
  try {
    cachedDetails = JSON.parse(report.detailJson);
    liveMode = !!isLive;
  } catch (e) {
    console.warn('vol-reconcile: failed to parse detailJson', e);
    cachedDetails = null;
    liveMode = false;
  }
}

function updateTileStates(parentEl) {
  const signalMap = {
    dupLive:      lastReconcile?.duplicateLiveLocations  ?? 0,
    pendingGrace: lastReconcile?.pendingGrace             ?? 0,
    pastGrace:    lastReconcile?.pastGraceStragglers      ?? 0,
    mismatch:     lastReconcile?.actressFolderMismatches  ?? 0,
  };
  for (const [sig, count] of Object.entries(signalMap)) {
    const tile    = (parentEl || document).querySelector(`#vol-rc-tile-${sig}`);
    const chevron = (parentEl || document).querySelector(`#vol-rc-chevron-${sig}`);
    if (!tile) continue;
    const clickable = count > 0;
    tile.classList.toggle('vol-rc-tile--clickable', clickable);
    tile.classList.toggle('vol-rc-tile--open', selectedSignal === sig);
    if (chevron) {
      chevron.style.visibility = clickable ? 'visible' : 'hidden';
      chevron.textContent = selectedSignal === sig ? '▲' : '▼';
    }
  }
}

function wireTileClicks(parentEl, onRefresh) {
  const signalIds = {
    'vol-rc-tile-dupLive':      'dupLive',
    'vol-rc-tile-pendingGrace': 'pendingGrace',
    'vol-rc-tile-pastGrace':    'pastGrace',
    'vol-rc-tile-mismatch':     'mismatch',
  };
  for (const [id, sig] of Object.entries(signalIds)) {
    const tile = (parentEl || document).querySelector(`#${id}`);
    if (!tile) continue;
    tile.addEventListener('click', async () => {
      const count = (lastReconcile?.[SIGNALS[sig].countField] ?? 0);
      if (!count) return;
      if (selectedSignal === sig) {
        selectedSignal = null;
        hideDrilldownPanel();
        updateTileStates(parentEl);
        return;
      }
      selectedSignal = sig;
      updateTileStates(parentEl);
      if (cachedDetails && liveMode) {
        renderDrilldownPanel(parentEl);
        return;
      }
      renderDrilldownLoading(sig);
      try {
        await runVerboseReconcileForDrilldown(parentEl, onRefresh);
      } catch (e) {
        renderDrilldownError('Failed to load details: ' + e.message);
        return;
      }
      renderDrilldownPanel(parentEl);
    });
  }
}

async function runVerboseReconcileForDrilldown(parentEl, onRefresh) {
  const res = await fetch('/api/reconcile/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ verbose: true }),
  });
  if (!res.ok) throw new Error(`Reconcile returned ${res.status}`);

  const recentRes = await fetch('/api/reconcile/recent?limit=1');
  if (!recentRes.ok) throw new Error('Could not retrieve report details');
  const list = await recentRes.json();
  const report = Array.isArray(list) && list.length > 0 ? list[0] : null;
  if (report) {
    lastReconcile = report;
    updateDetailCacheFromReport(report, true);
  }
  updateTileStates(parentEl);
  if (onRefresh) onRefresh();
}

function getDrilldownPanelEl() {
  return document.getElementById('vol-rc-detail-panel');
}

function hideDrilldownPanel() {
  const el = getDrilldownPanelEl();
  if (el) { el.style.display = 'none'; el.innerHTML = ''; }
}

function renderDrilldownLoading(signal) {
  const el = getDrilldownPanelEl();
  if (!el) return;
  const meta = SIGNALS[signal] || {};
  el.style.display = '';
  el.innerHTML = `
    <div class="vol-rc-dp-card">
      <div class="vol-rc-dp-head">
        <span class="vol-rc-dp-title">${esc(meta.label || signal)}</span>
        <button type="button" class="vol-rc-dp-close">×</button>
      </div>
      <div class="vol-rc-dp-body"><div class="vol-rc-dp-loading">Running reconcile to load details…</div></div>
    </div>
  `;
  el.querySelector('.vol-rc-dp-close')?.addEventListener('click', () => {
    selectedSignal = null;
    hideDrilldownPanel();
    updateTileStates(null);
  });
}

function renderDrilldownError(msg) {
  const el = getDrilldownPanelEl();
  if (!el) return;
  el.style.display = '';
  el.innerHTML = `
    <div class="vol-rc-dp-card vol-rc-dp-card--error">
      <div class="vol-rc-dp-head">
        <span class="vol-rc-dp-title">Error</span>
        <button type="button" class="vol-rc-dp-close">×</button>
      </div>
      <div class="vol-rc-dp-body"><div class="vol-rc-dp-error">${esc(msg)}</div></div>
    </div>
  `;
  el.querySelector('.vol-rc-dp-close')?.addEventListener('click', () => {
    selectedSignal = null;
    hideDrilldownPanel();
    updateTileStates(null);
  });
}

function renderDrilldownPanel(parentEl) {
  const el = getDrilldownPanelEl();
  if (!el || !selectedSignal || !cachedDetails) return;

  const meta = SIGNALS[selectedSignal];
  if (!meta) return;

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

  el.style.display = '';
  el.innerHTML = `
    <div class="vol-rc-dp-card">
      <div class="vol-rc-dp-head">
        <span class="vol-rc-dp-title">${esc(meta.label)}</span>
        <span class="vol-rc-dp-count">${rows.length} row${rows.length === 1 ? '' : 's'}</span>
        <button type="button" class="vol-rc-dp-close">×</button>
      </div>
      ${!liveMode ? '<div class="vol-rc-dp-readonly-banner">Historical report — actions unavailable. Run a new reconcile to enable actions.</div>' : ''}
      <div class="vol-rc-dp-body">
        ${rows.length === 0
          ? '<div class="vol-rc-dp-empty">No rows to show.</div>'
          : bodyHtml
        }
      </div>
    </div>
  `;

  el.querySelector('.vol-rc-dp-close')?.addEventListener('click', () => {
    selectedSignal = null;
    hideDrilldownPanel();
    updateTileStates(parentEl);
  });

  wireDrilldownActions(el, parentEl);
}

function wireDrilldownActions(el, parentEl) {
  el.querySelectorAll('[data-dp-action]').forEach(btn => {
    btn.addEventListener('click', () => handleDrilldownAction(btn, parentEl));
  });
}

async function handleDrilldownAction(btn, parentEl) {
  const action = btn.dataset.dpAction;
  switch (action) {
    case 'trust-volume':
      await handleDpTrustVolume(btn, parentEl);
      break;
    case 'sweep-row':
      await handleDpSweepRow(btn, parentEl);
      break;
    case 'sync-vol':
      await handleDpSyncVol(btn, parentEl);
      break;
    case 'open-title':
      await handleDpOpenTitle(btn);
      break;
    case 'open-actress':
      await handleDpOpenActress(btn);
      break;
    case 'load-more':
      handleDpLoadMore();
      break;
  }
}

// Row renderers for v2

function renderDupLiveRows(rows, truncated, total) {
  const rowsHtml = rows.map(r => {
    const locs = r.locations || [];
    const locsHtml = locs.map(l =>
      `<span class="vol-rc-dp-loc">vol-${esc(l.volumeId)}<span class="vol-rc-dp-loc-path" title="${esc(l.path)}">${esc(shortenPath(l.path))}</span></span>`
    ).join('<span class="vol-rc-dp-loc-sep">·</span>');

    const actionsHtml = liveMode ? locs.map(l =>
      `<button type="button" class="vol-rc-dp-btn vol-rc-dp-btn--trust"
         data-dp-action="trust-volume"
         data-title-id="${esc(r.titleId)}"
         data-trust-vol="${esc(l.volumeId)}"
         data-other-vols="${esc(locs.filter(x => x.volumeId !== l.volumeId).map(x => x.volumeId).join(','))}">Trust vol-${esc(l.volumeId)}</button>`
    ).join('') : '';

    return `<div class="vol-rc-dp-row">
      <div class="vol-rc-dp-row-left">
        <span class="vol-rc-dp-code">${esc(r.code)}</span>
        <span class="vol-rc-dp-locs">${locsHtml}</span>
      </div>
      <div class="vol-rc-dp-row-actions">
        ${actionsHtml}
        <button type="button" class="vol-rc-dp-btn vol-rc-dp-btn--open"
          data-dp-action="open-title" data-code="${esc(r.code)}">Open title</button>
      </div>
    </div>`;
  }).join('');
  return rowsHtml + renderDpTruncatedNote(truncated, total);
}

function renderGraceRows(rows, truncated, total, kind) {
  const rowsHtml = rows.map(r => {
    const daysLabel  = r.daysStale != null ? `${r.daysStale}d stale` : '';
    const staleClass = kind === 'past' ? 'vol-rc-dp-days--warn' : 'vol-rc-dp-days--pending';
    const actionsHtml = liveMode
      ? (kind === 'past'
          ? `<button type="button" class="vol-rc-dp-btn vol-rc-dp-btn--sweep"
               data-dp-action="sweep-row"
               data-location-id="${esc(r.locationId)}"
               data-code="${esc(r.code)}">Sweep this row</button>`
          : `<button type="button" class="vol-rc-dp-btn vol-rc-dp-btn--sync"
               data-dp-action="sync-vol"
               data-vol="${esc(r.volumeId)}"
               data-code="${esc(r.code)}">Sync vol-${esc(r.volumeId)}</button>`)
      : '';
    return `<div class="vol-rc-dp-row">
      <div class="vol-rc-dp-row-left">
        <span class="vol-rc-dp-code">${esc(r.code)}</span>
        <span class="vol-rc-dp-vol">vol-${esc(r.volumeId)}</span>
        <span class="vol-rc-dp-path" title="${esc(r.path)}">${esc(shortenPath(r.path))}</span>
        ${daysLabel ? `<span class="vol-rc-dp-days ${staleClass}">${esc(daysLabel)}</span>` : ''}
      </div>
      <div class="vol-rc-dp-row-actions">
        ${actionsHtml}
        <button type="button" class="vol-rc-dp-btn vol-rc-dp-btn--open"
          data-dp-action="open-title" data-code="${esc(r.code)}">Open title</button>
      </div>
    </div>`;
  }).join('');
  return rowsHtml + renderDpTruncatedNote(truncated, total);
}

function renderMismatchRows(rows, truncated, total) {
  const rowsHtml = rows.map(r => {
    const actionsHtml = liveMode
      ? `<button type="button" class="vol-rc-dp-btn vol-rc-dp-btn--actress"
           data-dp-action="open-actress"
           data-actress-id="${esc(r.actressId)}"
           data-actress-name="${esc(r.actressName)}">Open in Misnamed Folders</button>`
      : '';
    return `<div class="vol-rc-dp-row">
      <div class="vol-rc-dp-row-left">
        <span class="vol-rc-dp-code">${esc(r.code)}</span>
        <span class="vol-rc-dp-actress">${esc(r.actressName)}</span>
        <span class="vol-rc-dp-vol">vol-${esc(r.volumeId)}</span>
        <span class="vol-rc-dp-path" title="${esc(r.path)}">${esc(shortenPath(r.path))}</span>
      </div>
      <div class="vol-rc-dp-row-actions">
        ${actionsHtml}
        <button type="button" class="vol-rc-dp-btn vol-rc-dp-btn--open"
          data-dp-action="open-title" data-code="${esc(r.code)}">Open title</button>
      </div>
    </div>`;
  }).join('');
  return rowsHtml + renderDpTruncatedNote(truncated, total);
}

function renderDpTruncatedNote(truncated, total) {
  if (!truncated) return '';
  return `<div class="vol-rc-dp-truncated">Showing 50 of ${total} rows.
    <button type="button" class="vol-rc-dp-load-more" data-dp-action="load-more">Show all</button>
  </div>`;
}

function handleDpLoadMore() {
  if (!selectedSignal || !cachedDetails) return;
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
  const el = getDrilldownPanelEl();
  const body = el?.querySelector('.vol-rc-dp-body');
  if (body) {
    body.innerHTML = bodyHtml;
    wireDrilldownActions(el, null);
  }
}

async function handleDpTrustVolume(btn, parentEl) {
  const titleId    = btn.dataset.titleId;
  const trustVolId = btn.dataset.trustVol;
  const otherVols  = (btn.dataset.otherVols || '').split(',').filter(Boolean);
  const syncVolId  = otherVols[0] || '(other)';

  const confirmed = await showVolTrustModal(trustVolId, syncVolId);
  if (!confirmed) return;

  if (taskCenter.isRunning()) { showVolToast('Another task is already running.', 'error'); return; }

  btn.disabled = true;
  btn.textContent = 'Starting…';
  try {
    const res = await fetch(
      `/api/reconcile/trust-volume?titleId=${encodeURIComponent(titleId)}&trustVolumeId=${encodeURIComponent(trustVolId)}`,
      { method: 'POST' }
    );
    if (res.status === 202) {
      const body = await res.json();
      taskCenter.start({ taskId: body.taskId, runId: body.runId, label: `Syncing vol-${body.otherVolumeId} (trust-volume)` });
      subscribeToVolRun(body.runId, parentEl);
      showVolToast(`Sync of vol-${body.otherVolumeId} started.`);
    } else if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      showVolToast(body.error || 'Cannot trust volume: conflict.', 'error');
    } else {
      const body = await res.json().catch(() => ({}));
      showVolToast(body.error || `Unexpected status ${res.status}`, 'error');
    }
  } catch (e) {
    showVolToast('Request failed: ' + e.message, 'error');
  } finally {
    if (btn.isConnected) { btn.disabled = false; btn.textContent = `Trust vol-${trustVolId}`; }
  }
}

async function handleDpSweepRow(btn, parentEl) {
  const locationId = btn.dataset.locationId;
  const code = btn.dataset.code;
  btn.disabled = true;
  btn.textContent = 'Sweeping…';
  try {
    const res = await fetch(`/api/reconcile/sweep-row?id=${encodeURIComponent(locationId)}`, { method: 'POST' });
    if (res.ok) {
      showVolToast(`Swept row for ${code}.`);
      cachedDetails = null; liveMode = false; selectedSignal = null;
      hideDrilldownPanel(); updateTileStates(parentEl);
    } else if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      const staleTxt = body.staleDays != null ? `${body.staleDays}d stale` : 'not stale';
      showVolToast(`Row not past grace (${staleTxt}, grace=${body.graceDays}d).`, 'error');
    } else if (res.status === 404) {
      showVolToast('Row not found (may have already been swept).', 'error');
    } else {
      showVolToast(`Unexpected status ${res.status}`, 'error');
    }
  } catch (e) {
    showVolToast('Request failed: ' + e.message, 'error');
  } finally {
    if (btn.isConnected) { btn.disabled = false; btn.textContent = 'Sweep this row'; }
  }
}

async function handleDpSyncVol(btn, parentEl) {
  const volumeId = btn.dataset.vol;
  if (taskCenter.isRunning()) { showVolToast('Another task is already running.', 'error'); return; }
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
      showVolToast(body.error || 'Task already running.', 'error');
    } else if (res.ok) {
      const { runId } = await res.json();
      taskCenter.start({ taskId: 'volume.sync', runId, label: `Syncing vol-${volumeId}` });
      subscribeToVolRun(runId, parentEl);
      showVolToast(`Sync of vol-${volumeId} started.`);
    } else {
      showVolToast(`Sync failed (${res.status})`, 'error');
    }
  } catch (e) {
    showVolToast('Request failed: ' + e.message, 'error');
  } finally {
    if (btn.isConnected) { btn.disabled = false; btn.textContent = `Sync vol-${volumeId}`; }
  }
}

async function handleDpOpenTitle(btn) {
  const code = btn.dataset.code;
  if (!code) return;
  try {
    const { openTitleDetail } = await import('../../title-detail.js');
    await openTitleDetail({ code });
  } catch (e) {
    console.error('vol-reconcile drilldown: openTitle failed', e);
  }
}

async function handleDpOpenActress(btn) {
  const actressId = Number(btn.dataset.actressId);
  try {
    const { openActressDetail } = await import('../../actress-detail.js');
    await openActressDetail(actressId);
  } catch (e) {
    console.error('vol-reconcile drilldown: openActress failed', e);
  }
}

function subscribeToVolRun(runId, parentEl) {
  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  es.addEventListener('task.ended', e => {
    const ev = JSON.parse(e.data);
    taskCenter.finish({ status: ev.status, summary: ev.summary });
    es.close();
    cachedDetails = null;
    liveMode = false;
  });
  es.onerror = () => { es.close(); };
}

// ── Trust-volume modal (v2) ───────────────────────────────────────────────────

function showVolTrustModal(trustVolId, syncVolId) {
  return new Promise(resolve => {
    document.getElementById('vol-trust-modal')?.remove();
    const backdrop = document.createElement('div');
    backdrop.id = 'vol-trust-modal';
    backdrop.className = 'sh-modal-backdrop';
    backdrop.innerHTML = `
      <div class="sh-modal" role="dialog" aria-modal="true">
        <div class="sh-modal-head">Trust vol-${esc(trustVolId)}?</div>
        <div class="sh-modal-body">
          <p>Trusting <strong>vol-${esc(trustVolId)}</strong> means the system believes that volume holds the
          canonical copy of this title.</p>
          <p>A <strong>full volume sync of vol-${esc(syncVolId)}</strong> will be started — this syncs the
          entire other volume, not just the affected partition.</p>
          <p class="sh-modal-note">The task lock will be held while the sync completes.</p>
        </div>
        <div class="sh-modal-actions">
          <button type="button" class="sh-modal-btn sh-modal-btn--cancel" id="vol-modal-cancel">Cancel</button>
          <button type="button" class="sh-modal-btn sh-modal-btn--confirm" id="vol-modal-confirm">Sync vol-${esc(syncVolId)} now</button>
        </div>
      </div>
    `;
    document.body.appendChild(backdrop);
    const cleanup = (r) => {
      backdrop.remove();
      document.removeEventListener('keydown', kh);
      resolve(r);
    };
    const kh = (e) => { if (e.key === 'Escape') cleanup(false); };
    document.addEventListener('keydown', kh);
    backdrop.addEventListener('click', e => { if (e.target === backdrop) cleanup(false); });
    document.getElementById('vol-modal-cancel').addEventListener('click',  () => cleanup(false));
    document.getElementById('vol-modal-confirm').addEventListener('click', () => cleanup(true));
  });
}

// ── Toast (v2) ────────────────────────────────────────────────────────────────

function showVolToast(msg, kind = 'success') {
  const t = document.createElement('div');
  t.className = 'sh-toast' + (kind === 'error' ? ' sh-toast--error' : '');
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 4000);
}

// ── Utility ───────────────────────────────────────────────────────────────────

function shortenPath(path) {
  if (!path) return '';
  const parts = path.replace(/\\/g, '/').split('/').filter(Boolean);
  if (parts.length <= 2) return path;
  return '…/' + parts.slice(-2).join('/');
}

function loadReportsTable() {
  const tbody = document.getElementById('vol-rc-history-tbody');
  if (!tbody) return;
  fetch('/api/reconcile/recent?limit=30')
    .then(r => r.ok ? r.json() : [])
    .then(rows => {
      if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="vol-rc-history-empty">No reconcile reports yet.</td></tr>';
        return;
      }
      tbody.innerHTML = rows.map(r => {
        const t = r.generatedAt ? new Date(r.generatedAt) : null;
        const timeCell = t
          ? `<span class="vol-rc-time" title="${esc(t.toLocaleString())}">${esc(formatAge(t))}</span>`
          : '<span class="vol-rc-time">—</span>';
        const trigger = r.triggeredBy === 'coherent_sync' ? 'coherent_sync' : (r.triggeredBy || '—');
        const trigCls = r.triggeredBy === 'coherent_sync' ? 'vol-rc-trigger-coherent' : 'vol-rc-trigger-manual';
        const wc = (n) => n >= 1 ? ' class="vol-rc-num warn"' : ' class="vol-rc-num"';
        return `<tr>
          <td>${timeCell}</td>
          <td><span class="${trigCls}">${esc(trigger)}</span></td>
          <td${wc(r.duplicateLiveLocations)}>${r.duplicateLiveLocations ?? '—'}</td>
          <td${wc(r.pendingGrace)}>${r.pendingGrace ?? '—'}</td>
          <td${wc(r.pastGraceStragglers)}>${r.pastGraceStragglers ?? '—'}</td>
          <td${wc(r.actressFolderMismatches)}>${r.actressFolderMismatches ?? '—'}</td>
        </tr>`;
      }).join('');
    })
    .catch(() => {
      const tbody2 = document.getElementById('vol-rc-history-tbody');
      if (tbody2) tbody2.innerHTML = '<tr><td colspan="6" class="vol-rc-history-empty">Could not load reports.</td></tr>';
    });
}

// ── Per-volume row state for coherent sync ────────────────────────────────────

export function renderVolRows(volumes, onSync) {
  if (!volumes.length) {
    return '<div class="vol-sh-empty">No volumes configured.</div>';
  }
  const anyRunning = taskCenter.isRunning();
  return volumes.map(v => renderVolRow(v, volStates.get(v.id) || { state: 'idle' }, anyRunning)).join('');
}

function renderVolRow(v, vs, anyRunning) {
  const state = vs.state || 'idle';
  const mounted = v.status !== 'offline';

  let dotHtml, metaHtml, rightHtml, rowStyle = '', rowCls = 'vol-sh-vol-row';

  if (state === 'idle') {
    dotHtml  = mounted ? '<span class="vol-sh-dot vol-sh-dot--online"  title="online"></span>'
                       : '<span class="vol-sh-dot vol-sh-dot--offline" title="offline"></span>';
    metaHtml = esc(formatLastSynced(v.lastSyncedAt));
    rightHtml = `<button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}"${anyRunning ? ' disabled' : ''}>Sync</button>`;

  } else if (state === 'queued') {
    dotHtml   = mounted ? '<span class="vol-sh-dot vol-sh-dot--online"  title="online"></span>'
                        : '<span class="vol-sh-dot vol-sh-dot--offline" title="offline"></span>';
    metaHtml  = esc(formatLastSynced(v.lastSyncedAt));
    rightHtml = `<span class="vol-sh-state-label vol-sh-queued">queued</span>
      <button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;
    rowCls   += ' vol-sh-vol-row--muted';

  } else if (state === 'scanning') {
    dotHtml  = '<span class="vol-sh-spinner" title="scanning"></span>';
    const elapsed = vs.startedAt ? formatDuration(Date.now() - vs.startedAt) : '…';
    const progressText = vs.detail ? esc(vs.detail) : `Scanning… (${elapsed})`;
    metaHtml = `<span class="vol-sh-scanning-detail">${progressText}</span>`;
    rightHtml = `<span class="vol-sh-duration vol-sh-duration--ticking">(${elapsed})</span>
      <button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;
    rowStyle  = 'background: rgba(59, 130, 246, 0.08);';
    const bar = renderRowProgressBar(vs);
    return `<div class="${rowCls}" style="${rowStyle}">
      <div class="vol-sh-vol-id">${dotHtml}${esc(v.id.toUpperCase())}</div>
      <div class="vol-sh-vol-meta">${metaHtml}</div>
      <div class="vol-sh-vol-btns">${rightHtml}</div>
      ${bar}
    </div>`;

  } else if (state === 'cancelling') {
    dotHtml  = '<span class="vol-sh-spinner vol-sh-spinner--cancelling" title="cancelling"></span>';
    const elapsed = vs.startedAt ? formatDuration(Date.now() - vs.startedAt) : '…';
    metaHtml = `<span class="vol-sh-scanning-detail">⏸ Cancelling…</span>`;
    rightHtml = `<span class="vol-sh-duration vol-sh-duration--ticking">(${elapsed})</span>
      <button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;
    rowStyle  = 'background: rgba(59, 130, 246, 0.08);';
    const bar = renderRowProgressBar(vs);
    return `<div class="${rowCls}" style="${rowStyle}">
      <div class="vol-sh-vol-id">${dotHtml}${esc(v.id.toUpperCase())}</div>
      <div class="vol-sh-vol-meta">${metaHtml}</div>
      <div class="vol-sh-vol-btns">${rightHtml}</div>
      ${bar}
    </div>`;

  } else if (state === 'done') {
    dotHtml  = '<span class="vol-sh-dot vol-sh-dot--done" title="done">✓</span>';
    metaHtml = 'Just now';
    const dur = vs.startedAt && vs.endedAt ? formatDuration(vs.endedAt - vs.startedAt) : '';
    rightHtml = `<span class="vol-sh-duration vol-sh-duration--final">${esc(dur)}</span>
      <button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;

  } else if (state === 'failed') {
    dotHtml  = '<span class="vol-sh-dot vol-sh-dot--failed" title="failed">✗</span>';
    metaHtml = esc(formatLastSynced(v.lastSyncedAt));
    const dur = vs.startedAt && vs.endedAt ? formatDuration(vs.endedAt - vs.startedAt) : '';
    const errTitle = vs.errorMsg ? ` title="${esc(vs.errorMsg)}"` : '';
    rightHtml = `<span class="vol-sh-duration vol-sh-duration--failed"${errTitle}>Failed (${esc(dur)})</span>
      <button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;

  } else if (state === 'skipped') {
    dotHtml   = mounted ? '<span class="vol-sh-dot vol-sh-dot--online"  title="online"></span>'
                        : '<span class="vol-sh-dot vol-sh-dot--offline" title="offline"></span>';
    metaHtml  = esc(formatLastSynced(v.lastSyncedAt));
    rightHtml = `<span class="vol-sh-state-label vol-sh-skipped">skipped</span>
      <button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}" disabled>Sync</button>`;
    rowCls   += ' vol-sh-vol-row--muted';

  } else {
    dotHtml  = mounted ? '<span class="vol-sh-dot vol-sh-dot--online"  title="online"></span>'
                       : '<span class="vol-sh-dot vol-sh-dot--offline" title="offline"></span>';
    metaHtml = esc(formatLastSynced(v.lastSyncedAt));
    rightHtml = `<button type="button" class="btn sm vol-sh-vol-btn" data-action="sync" data-vol="${esc(v.id)}"${anyRunning ? ' disabled' : ''}>Sync</button>`;
  }

  const styleAttr = rowStyle ? ` style="${rowStyle}"` : '';
  return `<div class="${rowCls}"${styleAttr}>
    <div class="vol-sh-vol-id">${dotHtml}${esc(v.id.toUpperCase())}</div>
    <div class="vol-sh-vol-meta">${metaHtml}</div>
    <div class="vol-sh-vol-btns">${rightHtml}</div>
  </div>`;
}

function renderRowProgressBar(vs) {
  const hasProgress = (vs.total || 0) > 0;
  const pct = hasProgress ? Math.max(0, Math.min(100, (vs.current / vs.total) * 100)) : 0;
  const cls = hasProgress ? 'vol-sh-progress-fill' : 'vol-sh-progress-fill vol-sh-progress-fill--indeterminate';
  return `<div class="vol-sh-progress"><div class="${cls}" style="width: ${pct}%;"></div></div>`;
}

// ── Coherent-sync progress subtitle ─────────────────────────────────────────

export function renderCoherentSubtitle() {
  if (!runIsCoherent || !activeRunId) return '';
  const elapsed  = runStartedAt ? formatDuration(Date.now() - runStartedAt) : '—';
  const volLabel = currentVolId ? `vol-${currentVolId}` : '—';
  const pct      = coherentTotal > 0 ? Math.min(100, (coherentDone / coherentTotal) * 100) : 0;
  return `
    <div class="vol-sh-coherent-progress-text">Coherent sync running — ${esc(volLabel)} (${coherentDone}/${coherentTotal} done) — ${esc(elapsed)} elapsed</div>
    <div class="vol-sh-coherent-progress-bar"><div class="vol-sh-coherent-progress-fill" style="width: ${pct}%;"></div></div>
  `;
}

// ── Coherent sync start ───────────────────────────────────────────────────────

export async function startCoherentSyncFromSyncHealth(volumes, onRender) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running. Wait for it to finish before starting a coherent sync.');
    return;
  }
  const confirmed = confirm(
    'Coherent multi-volume sync scans every configured volume in turn and only evaluates orphans '
    + 'after all volumes are observed.\n\n'
    + 'This holds the task lock for the duration and may run for hours. '
    + 'Recommended for overnight runs after manual cross-volume movement.\n\n'
    + 'Continue?'
  );
  if (!confirmed) return;

  try {
    const res = await fetch('/api/utilities/tasks/volume.sync_coherent/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    initVolStatesQueued(volumes);
    runIsCoherent  = true;
    runStartedAt   = Date.now();
    coherentTotal  = volumes.length;
    coherentDone   = 0;
    currentVolId   = null;
    taskCenter.start({ taskId: 'volume.sync_coherent', runId, label: 'Coherent sync (all volumes)' });
    openEventSource(runId, volumes, onRender);
    if (onRender) onRender();
  } catch (err) {
    alert('Failed to start coherent sync: ' + err.message);
  }
}

export async function startSingleVolumeSyncFromSyncHealth(volumeId, volumes, onRender) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running. Wait for it to finish.');
    return;
  }
  try {
    const res = await fetch('/api/utilities/tasks/volume.sync/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (res.status === 409) {
      const b = await res.json().catch(() => ({}));
      alert(b.error || 'Task already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    runIsCoherent = false;
    runStartedAt  = Date.now();
    volStates.clear();
    volStates.set(volumeId, { state: 'scanning', startedAt: Date.now(), endedAt: null, detail: '', errorMsg: '', current: 0, total: 0 });
    taskCenter.start({ taskId: 'volume.sync', runId, label: `Syncing Volume ${volumeId.toUpperCase()}` });
    openEventSource(runId, volumes, onRender);
    if (onRender) onRender();
  } catch (err) {
    alert('Sync failed: ' + err.message);
  }
}

// ── EventSource management ────────────────────────────────────────────────────

function initVolStatesQueued(volumes) {
  volStates.clear();
  for (const v of volumes) {
    volStates.set(v.id, { state: 'queued', startedAt: null, endedAt: null, detail: '', errorMsg: '', current: 0, total: 0 });
  }
}

export function openEventSource(runId, volumes, onRender) {
  if (activeES && activeRunId === runId) return;
  if (activeES) { activeES.close(); activeES = null; }
  activeRunId = runId;
  startTickInterval(onRender);
  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  activeES = es;
  es.addEventListener('task.started',   () => {});
  es.addEventListener('phase.started',  e => handlePhaseStarted(JSON.parse(e.data), onRender));
  es.addEventListener('phase.progress', e => handlePhaseProgress(JSON.parse(e.data)));
  es.addEventListener('phase.ended',    e => handlePhaseEnded(JSON.parse(e.data), onRender));
  es.addEventListener('task.ended',     e => handleTaskEnded(JSON.parse(e.data), volumes, onRender));
  es.onerror = () => {};
}

function startTickInterval(onRender) {
  stopTickInterval();
  tickInterval = setInterval(() => { if (onRender) onRender(); }, 1000);
}

function stopTickInterval() {
  if (tickInterval != null) { clearInterval(tickInterval); tickInterval = null; }
}

// ── Phase handlers ────────────────────────────────────────────────────────────

function handlePhaseStarted(ev, onRender) {
  const phaseId = ev.phaseId;
  if (phaseId && phaseId.startsWith('vol.')) {
    const volId = phaseId.slice(4);
    currentVolId = volId;
    volStates.set(volId, { state: 'scanning', startedAt: Date.now(), endedAt: null, detail: '', errorMsg: '', current: 0, total: 0 });
    if (runIsCoherent) {
      const mins = runStartedAt ? Math.floor((Date.now() - runStartedAt) / 60000) : 0;
      taskCenter.updateLabel(`Coherent sync — vol-${volId} (${coherentDone}/${coherentTotal}) — ${mins}m`);
      taskCenter.updateProgress({ phaseLabel: `Scanning ${volId.toUpperCase()}` });
    }
  }
  if (onRender) onRender();
}

function handlePhaseProgress(ev) {
  const phaseId = ev.phaseId;
  if (!phaseId || !phaseId.startsWith('vol.')) return;
  const volId = phaseId.slice(4);
  const vs = volStates.get(volId);
  if (!vs) return;
  if (ev.total > 0) {
    vs.detail  = `${ev.detail || 'Saving'} ${ev.current}/${ev.total}`;
    vs.current = ev.current;
    vs.total   = ev.total;
  } else if (ev.detail) {
    vs.detail = ev.detail;
  }
  // onRender called by tick interval
}

function handlePhaseEnded(ev, onRender) {
  const phaseId = ev.phaseId;
  if (phaseId && phaseId.startsWith('vol.')) {
    const volId = phaseId.slice(4);
    const vs = volStates.get(volId);
    if (vs) {
      vs.state    = ev.status === 'ok' ? 'done' : 'failed';
      vs.endedAt  = Date.now();
      vs.detail   = ev.summary || '';
      vs.errorMsg = ev.status !== 'ok' ? (ev.summary || 'Unknown error') : '';
    }
    if (ev.status === 'ok') coherentDone++;
    if (volId === currentVolId) currentVolId = null;
    if (runIsCoherent) {
      const mins = runStartedAt ? Math.floor((Date.now() - runStartedAt) / 60000) : 0;
      taskCenter.updateLabel(`Coherent sync — (${coherentDone}/${coherentTotal}) — ${mins}m`);
    }
  }
  if (onRender) onRender();
}

async function handleTaskEnded(ev, volumes, onRender) {
  stopTickInterval();
  if (activeES) { activeES.close(); activeES = null; }

  if (!runIsCoherent) {
    for (const [, vs] of volStates) {
      if (vs.state === 'scanning' || vs.state === 'cancelling') {
        vs.state   = ev.status === 'ok' ? 'done' : 'failed';
        vs.endedAt = Date.now();
        vs.errorMsg = ev.status !== 'ok' ? (ev.summary || 'Unknown error') : '';
      }
    }
  }
  for (const [, vs] of volStates) {
    if (vs.state === 'queued') vs.state = 'skipped';
  }

  taskCenter.finish({ status: ev.status, summary: ev.summary });
  activeRunId   = null;
  runIsCoherent = false;
  currentVolId  = null;

  if (onRender) onRender();

  // Refresh reconcile data and clear run states after a short pause.
  try {
    await Promise.all([loadLatestReconcile(), loadLastCoherentRun()]);
    volStates.clear();
    runStartedAt  = null;
    coherentTotal = 0;
    coherentDone  = 0;
    if (onRender) onRender();
  } catch {}
}

// ── taskCenter subscription helper (cancel-detection) ────────────────────────

export function handleTaskCenterUpdate(active) {
  if (active && active.cancelRequested && active.status === 'running' && runIsCoherent) {
    for (const [, vs] of volStates) {
      if (vs.state === 'queued') vs.state = 'skipped';
      else if (vs.state === 'scanning') vs.state = 'cancelling';
    }
    if (currentVolId) {
      taskCenter.updateLabel(`Cancelling… vol-${currentVolId} must finish first`);
      taskCenter.updateProgress({ phaseLabel: '' });
    }
  }
}
