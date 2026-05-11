// health/library.js — Library Health tab.
// Ports utilities-library-health.js verbatim into the v2 workbench pattern.
// Two-pane: diagnostic check list (left) + selected-check detail (right).
// Inline "Clean orphaned covers" task + scan + recompute ratings + reconcile.
// Endpoints unchanged; taskCenter import is the shared module.

import * as taskCenter from '../../task-center.js';

const SELECTION_KEY = 'utilities.libraryHealth.selection';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function formatAge(date) {
  const secs = Math.floor((Date.now() - date.getTime()) / 1000);
  if (secs < 60)   return 'just now';
  if (secs < 3600) return Math.floor(secs / 60) + 'm ago';
  if (secs < 86400) return Math.floor(secs / 3600) + 'h ago';
  const days = Math.floor(secs / 86400);
  if (days === 1)   return 'yesterday';
  if (days < 30)    return days + ' days ago';
  return date.toLocaleDateString();
}

function formatRelativeAge(date) {
  const diffMs  = Date.now() - date.getTime();
  const diffMin = Math.round(diffMs / 60000);
  if (diffMin < 1)  return 'just now';
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffH = Math.round(diffMin / 60);
  if (diffH < 24)   return `${diffH}h ago`;
  return `${Math.round(diffH / 24)}d ago`;
}

function formatSize(bytes) {
  if (bytes == null || bytes < 0) return '?';
  if (bytes < 1024)               return bytes + ' B';
  if (bytes < 1024 * 1024)        return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

// ── Module state (one instance per page load) ─────────────────────────────────

let rootEl     = null;
let checks     = [];
let report     = null;
let selectedId = null;
let isScanning = false;
let lastReconcile = null;

// ── DOM helpers (scoped to rootEl) ────────────────────────────────────────────

const $  = (id) => rootEl?.querySelector(`#hl-${id}`);
const listEl            = () => $('list');
const detailEl          = () => $('detail');
const emptyEl           = () => $('empty');
const scanBtn           = () => $('scan');
const recomputeRatingsBtn = () => $('recompute-ratings');
const scanAgeEl         = () => $('scan-age');
const ratingStatusEl    = () => $('rating-status');
const emptyTitle        = () => $('empty-title');
const emptySub          = () => $('empty-sub');
const rcAgeEl           = () => $('reconcile-age');
const rcDupEl           = () => $('rc-dup');
const rcPendingEl       = () => $('rc-pending');
const rcPastEl          = () => $('rc-past');
const rcMismatchEl      = () => $('rc-mismatch');
const rcRunBtn          = () => $('rc-run');
const rcSweepBtn        = () => $('rc-sweep');

// ── Entry ─────────────────────────────────────────────────────────────────────

export async function mountLibrary(root) {
  rootEl = root;
  root.innerHTML = renderShell();
  selectedId = localStorage.getItem(SELECTION_KEY);

  await Promise.all([
    loadChecks(),
    loadLatestReport(),
    loadRatingCurveStatus(),
    loadLatestReconcile(),
  ]);

  renderScanAge();
  renderList();
  if (selectedId) showDetail(selectedId);
  else showEmpty();
  wireScanButton();
  wireRecomputeRatingsButton();
  wireReconcileButtons();

  // Keep buttons in sync when another tab triggers a task.
  taskCenter.subscribe(() => {
    const scan = scanBtn();
    if (scan) scan.disabled = taskCenter.isRunning();
    const recompute = recomputeRatingsBtn();
    if (recompute && recompute.textContent !== 'Recomputing…') {
      recompute.disabled = taskCenter.isRunning();
    }
  });
}

// ── Shell HTML ────────────────────────────────────────────────────────────────

function renderShell() {
  return `
    <div class="hl-wrap">

      <!-- Left: check list -->
      <div class="hl-left">
        <div class="hl-left-header">
          <div class="hl-left-header-top">
            <span>Library checks</span>
            <span id="hl-scan-age" class="hl-scan-age" style="display:none"></span>
          </div>
          <div id="hl-rating-status" class="hl-rating-status"></div>

          <!-- Reconcile mini-panel -->
          <div class="hl-reconcile">
            <div class="hl-reconcile-header">
              <span class="hl-reconcile-title">Reconcile</span>
              <span class="hl-reconcile-age" id="hl-reconcile-age">—</span>
            </div>
            <div class="hl-reconcile-counts">
              <div class="hl-reconcile-count">
                <span class="n" id="hl-rc-dup">—</span>
                <span class="lbl">Dup live</span>
              </div>
              <div class="hl-reconcile-count">
                <span class="n" id="hl-rc-pending">—</span>
                <span class="lbl">Pending</span>
              </div>
              <div class="hl-reconcile-count">
                <span class="n" id="hl-rc-past">—</span>
                <span class="lbl">Past grace</span>
              </div>
              <div class="hl-reconcile-count">
                <span class="n" id="hl-rc-mismatch">—</span>
                <span class="lbl">Folder ∆</span>
              </div>
            </div>
            <div class="hl-reconcile-actions">
              <button type="button" class="btn sm" id="hl-rc-run">Reconcile</button>
              <button type="button" class="btn sm" id="hl-rc-sweep">Sweep</button>
            </div>
          </div>

          <div class="hl-header-actions">
            <button type="button" class="btn sm" id="hl-scan">Scan library</button>
            <button type="button" class="btn sm" id="hl-recompute-ratings">Recompute ratings</button>
          </div>
        </div>
        <div class="hl-status-legend">
          <span class="hl-status-legend-item hl-status-legend--clean">
            <span class="hl-status-legend-dot"></span>clean
          </span>
          <span class="hl-status-legend-item hl-status-legend--warn">
            <span class="hl-status-legend-dot"></span>needs attention
          </span>
        </div>
        <ul class="hl-list" id="hl-list"></ul>
      </div>

      <!-- Right: empty state / detail -->
      <div class="hl-right">
        <div class="hl-empty" id="hl-empty" style="display:none">
          <span class="hl-empty-glyph">◌</span>
          <div class="hl-empty-title" id="hl-empty-title">Run a scan to see health findings</div>
          <div class="hl-empty-sub"   id="hl-empty-sub">Each check reports a count; click a check to see details.</div>
        </div>
        <div class="hl-detail" id="hl-detail" style="display:none"></div>
      </div>

    </div>
  `;
}

// ── Data loading ──────────────────────────────────────────────────────────────

async function loadChecks() {
  try {
    const res = await fetch('/api/utilities/health/checks');
    checks = await res.json();
  } catch (e) {
    console.error('Failed to load health checks', e);
    checks = [];
  }
}

async function loadLatestReport() {
  try {
    const res = await fetch('/api/utilities/health/report/latest');
    const body = await res.json();
    report = body.scanned ? body : null;
  } catch (e) {
    console.error('Failed to load latest report', e);
    report = null;
  }
}

function countFor(id) {
  if (!report) return null;
  const row = report.checks.find(c => c.id === id);
  return row ? row.total : null;
}

// ── Render: scan age + list ───────────────────────────────────────────────────

function renderScanAge() {
  const el = scanAgeEl();
  if (!el) return;
  if (!report || !report.scannedAt) { el.style.display = 'none'; return; }
  const scanned = new Date(report.scannedAt);
  el.textContent = 'Last scan: ' + formatAge(scanned);
  el.title = scanned.toLocaleString();
  el.style.display = '';
}

function renderList() {
  const ul = listEl();
  if (!ul) return;
  ul.innerHTML = '';
  if (checks.length === 0) {
    const li = document.createElement('li');
    li.className = 'hl-empty-list';
    li.textContent = 'No checks registered.';
    ul.appendChild(li);
    return;
  }
  for (const c of checks) {
    const count = countFor(c.id);
    const hasFindings = count != null && count > 0;
    const cls = ['hl-row'];
    if (c.id === selectedId) cls.push('selected');
    if (hasFindings) cls.push('has-findings');
    else if (count === 0) cls.push('clean');
    const li = document.createElement('li');
    li.className = cls.join(' ');
    const countHtml = count == null
      ? '<span class="hl-row-count unscanned">—</span>'
      : `<span class="hl-row-count">${count}</span>`;
    li.innerHTML = `
      <span class="hl-row-dot"></span>
      <span class="hl-row-label">${esc(c.label)}</span>
      ${countHtml}
    `;
    li.addEventListener('click', () => {
      selectedId = c.id;
      localStorage.setItem(SELECTION_KEY, c.id);
      renderList();
      showDetail(c.id);
    });
    ul.appendChild(li);
  }
}

// ── Empty / detail panes ──────────────────────────────────────────────────────

function showEmpty() {
  const detail = detailEl();
  const empty  = emptyEl();
  if (detail) detail.style.display = 'none';
  if (empty)  empty.style.display  = '';
  if (report) {
    if (emptyTitle()) emptyTitle().textContent = 'Select a check to see its findings';
    if (emptySub())   emptySub().textContent   = 'Results are from the last scan. Rescan to refresh.';
  } else {
    if (emptyTitle()) emptyTitle().textContent = 'Run a scan to see health findings';
    if (emptySub())   emptySub().textContent   = 'Each check reports a count; click a check to see details.';
  }
}

async function showDetail(checkId) {
  const empty  = emptyEl();
  const detail = detailEl();
  if (empty)  empty.style.display  = 'none';
  if (detail) detail.style.display = '';

  const meta = checks.find(c => c.id === checkId);
  if (!meta) { if (detail) detail.innerHTML = `<div class="hl-detail-body">No such check.</div>`; return; }

  if (!report) {
    if (detail) detail.innerHTML = `
      <div class="hl-detail-head">
        <div class="hl-detail-label">${esc(meta.label)}</div>
        <div class="hl-detail-desc">${esc(meta.description)}</div>
      </div>
      <div class="hl-detail-body hl-detail-unscanned">
        Run a scan to populate this check's findings.
      </div>
    `;
    return;
  }

  try {
    const res = await fetch(`/api/utilities/health/report/latest/${encodeURIComponent(checkId)}`);
    if (!res.ok) {
      if (detail) detail.innerHTML = `<div class="hl-detail-body">Detail unavailable.</div>`;
      return;
    }
    const entry = await res.json();
    renderDetail(meta, entry);
  } catch (e) {
    console.error('Failed to load check detail', e);
    if (detail) detail.innerHTML = `<div class="hl-detail-body">Detail unavailable.</div>`;
  }
}

function renderDetail(meta, entry) {
  const total = entry.result.total;
  const rows  = entry.result.rows || [];
  const statusHTML = total === 0
    ? `<div class="hl-detail-healthy">✓ No findings — this check is clean.</div>`
    : `<div class="hl-detail-count"><strong>${total}</strong> finding${total === 1 ? '' : 's'}</div>`;

  const sampleHTML = rows.length === 0 ? '' : `
    <div class="hl-findings">
      ${rows.map(r => `
        <div class="hl-finding">
          <div class="hl-finding-label">${esc(r.label)}</div>
          <div class="hl-finding-detail">${esc(r.detail || '')}</div>
        </div>
      `).join('')}
      ${total > rows.length ? `<div class="hl-findings-more">… and ${total - rows.length} more</div>` : ''}
    </div>
  `;

  const fixHTML = renderFixCTA(entry);

  const detail = detailEl();
  if (!detail) return;
  detail.innerHTML = `
    <div class="hl-detail-head">
      <div class="hl-detail-label">${esc(meta.label)}</div>
      <div class="hl-detail-desc">${esc(meta.description)}</div>
    </div>
    <div class="hl-detail-body">
      ${statusHTML}
      ${sampleHTML}
      ${fixHTML}
    </div>
  `;

  const fixBtn = detail.querySelector('.hl-fix-cta');
  if (fixBtn) fixBtn.addEventListener('click', () => handleFixRoute(entry, entry.fixRouting));
}

function renderFixCTA(entry) {
  if (entry.result.total === 0) return '';
  switch (entry.fixRouting) {
    case 'INLINE':
      return inlineCtaFor(entry);
    case 'VOLUMES_SCREEN':
      return `<button type="button" class="hl-fix-cta">Open Volumes screen →</button>`;
    case 'ACTRESS_DATA_SCREEN':
      return `<button type="button" class="hl-fix-cta">Open Actress data →</button>`;
    case 'SURFACE_ONLY':
      return `<div class="hl-fix-surface-only">No automatic fix — resolve manually.</div>`;
    default:
      return '';
  }
}

function inlineCtaFor(entry) {
  if (entry.id === 'orphaned_covers') {
    return `<button type="button" class="hl-fix-cta" data-inline="orphaned_covers">Clean all (delete local files) →</button>`;
  }
  return '';
}

function handleFixRoute(entry, routing) {
  switch (routing) {
    case 'INLINE':
      if (entry.id === 'orphaned_covers') startOrphanedCoversFlow();
      break;
    case 'VOLUMES_SCREEN':
      // v2: navigate to the Volumes page
      window.location.href = '/v2-volumes.html';
      break;
    case 'ACTRESS_DATA_SCREEN':
      // Legacy actress-data screen: navigate to legacy app
      window.location.href = '/#actress-data';
      break;
  }
}

// ── Inline cleanup: orphaned covers ──────────────────────────────────────────

async function startOrphanedCoversFlow() {
  if (taskCenter.isRunning()) { alert('Another task is running.'); return; }
  let preview;
  try {
    const res = await fetch('/api/utilities/tasks/covers.clean_orphaned/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    });
    if (!res.ok) { alert(`Preview failed (${res.status})`); return; }
    preview = await res.json();
  } catch (e) {
    console.error('preview failed', e);
    alert('Preview failed — see console.');
    return;
  }
  renderOrphanedCoversVisualize(preview);
}

function renderOrphanedCoversVisualize(preview) {
  const rows  = preview.rows || [];
  const total = preview.count || 0;
  const size  = formatSize(preview.totalBytes || 0);
  const sampleHTML = rows.slice(0, 100).map(r => `
    <div class="hl-finding">
      <div class="hl-finding-label">${esc(r.label)}/${esc(r.filename)}</div>
      <div class="hl-finding-detail">${esc(r.absolutePath)} · ${formatSize(r.sizeBytes)}</div>
    </div>
  `).join('');
  const moreHTML      = rows.length > 100 ? `<div class="hl-findings-more">… and ${rows.length - 100} more in preview</div>` : '';
  const truncatedHTML = preview.truncated ? `<div class="hl-findings-more">Preview capped at 200 rows — delete will process all ${total}.</div>` : '';

  const detail = detailEl();
  if (!detail) return;
  detail.innerHTML = `
    <div class="hl-detail-head">
      <div class="hl-detail-label">Clean orphaned covers</div>
      <div class="hl-detail-desc">Delete ${total} local cover file${total === 1 ? '' : 's'} · ${size} to free</div>
    </div>
    <div class="hl-detail-body">
      <div class="hl-findings">
        ${sampleHTML}
        ${moreHTML}
        ${truncatedHTML}
      </div>
      <div class="hl-visualize-actions">
        <button type="button" class="hl-fix-cta" id="hl-orphans-proceed">Proceed — delete ${total}</button>
        <button type="button" class="hl-visualize-cancel" id="hl-orphans-cancel">Cancel</button>
      </div>
    </div>
  `;
  detail.querySelector('#hl-orphans-proceed').addEventListener('click', runOrphanedCoversDelete);
  detail.querySelector('#hl-orphans-cancel').addEventListener('click', () => showDetail('orphaned_covers'));
}

async function runOrphanedCoversDelete() {
  try {
    const res = await fetch('/api/utilities/tasks/covers.clean_orphaned/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    });
    if (!res.ok) {
      if (res.status === 409) {
        const body = await res.json();
        alert(`Another task is already running: ${body.runningTaskId}`);
      } else {
        alert(`Start failed (${res.status})`);
      }
      return;
    }
    const { runId } = await res.json();
    taskCenter.start({ taskId: 'covers.clean_orphaned', runId, label: 'Cleaning orphaned covers' });
    subscribeToInlineRun(runId);
  } catch (e) {
    console.error('orphan delete start failed', e);
  }
}

function subscribeToInlineRun(runId) {
  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  es.addEventListener('phase.started', e => {
    const ev = JSON.parse(e.data);
    taskCenter.updateProgress({ phaseLabel: ev.label });
  });
  es.addEventListener('task.ended', async e => {
    const ev = JSON.parse(e.data);
    taskCenter.finish({ status: ev.status, summary: ev.summary });
    await loadLatestReport();
    renderList();
    showDetail('orphaned_covers');
    es.close();
  });
  es.onerror = () => {};
}

// ── Scan ──────────────────────────────────────────────────────────────────────

function wireScanButton() {
  const btn = scanBtn();
  if (!btn) return;
  btn.onclick   = startScan;
  btn.disabled  = taskCenter.isRunning();
}

async function startScan() {
  if (taskCenter.isRunning()) return;
  try {
    const res = await fetch('/api/utilities/tasks/library.scan/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    });
    if (!res.ok) {
      if (res.status === 409) {
        const body = await res.json();
        alert(`Another task is already running: ${body.runningTaskId}`);
      } else {
        alert(`Scan failed to start (${res.status})`);
      }
      return;
    }
    const { runId } = await res.json();
    isScanning = true;
    taskCenter.start({ taskId: 'library.scan', runId, label: 'Scanning library' });
    subscribeToScan(runId);
  } catch (e) {
    console.error('Scan start failed', e);
  }
}

function subscribeToScan(runId) {
  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  let phasesDone = 0;
  const totalPhases = checks.length || 1;
  es.addEventListener('phase.started', e => {
    const ev = JSON.parse(e.data);
    taskCenter.updateProgress({
      phaseLabel: ev.label,
      overallPct: Math.round((phasesDone / totalPhases) * 100),
    });
  });
  es.addEventListener('phase.ended', () => {
    phasesDone++;
    taskCenter.updateProgress({ overallPct: Math.round((phasesDone / totalPhases) * 100) });
  });
  es.addEventListener('task.ended', async e => {
    const ev = JSON.parse(e.data);
    taskCenter.finish({ status: ev.status, summary: ev.summary });
    isScanning = false;
    await loadLatestReport();
    renderScanAge();
    renderList();
    if (selectedId) showDetail(selectedId);
    es.close();
  });
  es.onerror = () => {
    if (!isScanning) es.close();
  };
}

// ── Rating curve ──────────────────────────────────────────────────────────────

async function loadRatingCurveStatus() {
  try {
    const res = await fetch('/api/utilities/rating-curve/status');
    if (!res.ok) return;
    const data = await res.json();
    renderRatingStatus(data);
  } catch (e) {
    // Non-fatal
  }
}

function renderRatingStatus(data) {
  const el = ratingStatusEl();
  if (!el) return;
  if (!data.computedAt) {
    el.textContent = 'Rating curve: not computed';
    return;
  }
  const when = formatRelativeAge(new Date(data.computedAt));
  el.textContent = `Rating curve: ${data.population.toLocaleString()} titles graded · ${when}`;
}

function wireRecomputeRatingsButton() {
  const btn = recomputeRatingsBtn();
  if (!btn) return;
  btn.onclick  = startRecomputeRatings;
  btn.disabled = taskCenter.isRunning();
}

async function startRecomputeRatings() {
  if (taskCenter.isRunning()) return;
  const btn = recomputeRatingsBtn();
  const originalText = btn.textContent;
  btn.textContent = 'Recomputing…';
  btn.disabled = true;
  try {
    const res = await fetch('/api/utilities/tasks/rating.recompute_curve/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    });
    if (!res.ok) {
      btn.textContent = originalText;
      btn.disabled = taskCenter.isRunning();
      if (res.status === 409) {
        const body = await res.json();
        alert(`Another task is already running: ${body.runningTaskId}`);
      } else {
        alert(`Recompute failed to start (${res.status})`);
      }
      return;
    }
    const { runId } = await res.json();
    taskCenter.start({ taskId: 'rating.recompute_curve', runId, label: 'Recomputing rating curve' });
    subscribeToRecompute(runId, originalText);
  } catch (e) {
    console.error('Recompute ratings start failed', e);
    btn.textContent = originalText;
    btn.disabled = taskCenter.isRunning();
  }
}

function subscribeToRecompute(runId, originalBtnText) {
  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  es.addEventListener('task.ended', async e => {
    const ev = JSON.parse(e.data);
    taskCenter.finish({ status: ev.status, summary: ev.summary });
    const btn = recomputeRatingsBtn();
    if (btn) {
      const succeeded = ev.status === 'ok';
      btn.textContent = succeeded ? 'Done ✓' : 'Failed ✗';
      btn.classList.toggle('hl-scan--error', !succeeded);
      setTimeout(() => {
        btn.textContent = originalBtnText;
        btn.classList.remove('hl-scan--error');
        btn.disabled = taskCenter.isRunning();
      }, 3000);
    }
    await loadRatingCurveStatus();
    es.close();
  });
  es.onerror = () => { es.close(); };
}

// ── Reconcile ─────────────────────────────────────────────────────────────────

async function loadLatestReconcile() {
  try {
    const res = await fetch('/api/reconcile/recent?limit=1');
    if (!res.ok) { lastReconcile = null; return; }
    const list = await res.json();
    lastReconcile = (Array.isArray(list) && list.length > 0) ? list[0] : null;
  } catch (e) {
    console.error('Failed to load latest reconcile', e);
    lastReconcile = null;
  }
  renderReconcile();
}

function renderReconcile() {
  const r = lastReconcile;
  setReconcileNum(rcDupEl(),      r?.duplicateLiveLocations,  1);
  setReconcileNum(rcPendingEl(),  r?.pendingGrace,            1);
  setReconcileNum(rcPastEl(),     r?.pastGraceStragglers,     1);
  setReconcileNum(rcMismatchEl(), r?.actressFolderMismatches, 1);
  const age = rcAgeEl();
  if (!age) return;
  if (!r || !r.generatedAt) {
    age.textContent = 'never run';
  } else {
    const t = new Date(r.generatedAt);
    age.textContent = formatAge(t);
    age.title = t.toLocaleString();
  }
}

function setReconcileNum(el, value, warnAt) {
  if (!el) return;
  if (value == null) { el.textContent = '—'; el.className = 'n'; return; }
  el.textContent = String(value);
  el.className   = 'n' + (value >= warnAt ? ' warn' : '');
}

function wireReconcileButtons() {
  const run = rcRunBtn();
  if (run && !run.dataset.wired) {
    run.dataset.wired = '1';
    run.onclick = () => runReconcile(false);
  }
  const sweep = rcSweepBtn();
  if (sweep && !sweep.dataset.wired) {
    sweep.dataset.wired = '1';
    sweep.onclick = () => {
      const past = lastReconcile?.pastGraceStragglers ?? 0;
      const msg  = past > 0
        ? `Run reconcile and sweep ${past} past-grace stale row(s)? This deletes location rows older than the grace window.`
        : 'Run reconcile and sweep past-grace stale rows? (None currently — sweep will be a no-op.)';
      if (!confirm(msg)) return;
      runReconcile(true);
    };
  }
}

async function runReconcile(sweep) {
  const run      = rcRunBtn();
  const sweepBtn = rcSweepBtn();
  if (!run || !sweepBtn) return;
  const origRun   = run.textContent;
  const origSweep = sweepBtn.textContent;
  run.disabled = true; sweepBtn.disabled = true;
  run.textContent = sweep ? 'Sweeping…' : 'Running…';
  try {
    const res = await fetch('/api/reconcile/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ verbose: false, sweep: !!sweep }),
    });
    if (!res.ok) { alert(`Reconcile failed (${res.status})`); return; }
    const body = await res.json();
    lastReconcile = body;
    renderReconcile();
    if (sweep) {
      const result = body.sweepResult || `Swept ${body.sweptCount ?? 0} row(s)`;
      alert(result);
    }
  } catch (e) {
    console.error('Reconcile run failed', e);
    alert('Reconcile failed: ' + e.message);
  } finally {
    run.textContent     = origRun;
    sweepBtn.textContent = origSweep;
    run.disabled     = false;
    sweepBtn.disabled = false;
  }
}
