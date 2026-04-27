// Utilities → Library Health screen.
// Two-pane: diagnostic checks on the left, selected-check detail on the right.
// "Scan library" runs every check as a single atomic task. Findings route back
// to the owning screens (Volumes, Actress Data) where fixes already exist.
// See spec/UTILITIES_LIBRARY_HEALTH.md.

import { esc } from './utils.js';
import * as taskCenter from './task-center.js';

const SELECTION_KEY = 'utilities.libraryHealth.selection';

const viewEl     = () => document.getElementById('tools-library-health-view');
const listEl     = () => document.getElementById('lh-list');
const emptyEl    = () => document.getElementById('lh-empty');
const detailEl   = () => document.getElementById('lh-detail');
const scanBtn           = () => document.getElementById('lh-scan');
const recomputeRatingsBtn = () => document.getElementById('lh-recompute-ratings');
const scanAgeEl       = () => document.getElementById('lh-scan-age');
const ratingStatusEl  = () => document.getElementById('lh-rating-status');
const emptyTitle = () => document.getElementById('lh-empty-title');
const emptySub   = () => document.getElementById('lh-empty-sub');

let checks = [];           // [{id, label, description, fixRouting}]
let report = null;         // latest report summary { runId, scannedAt, checks: [{id,total,...}] }
let selectedId = null;
let isScanning = false;

export async function showLibraryHealthView() {
  viewEl().style.display = 'flex';
  selectedId = localStorage.getItem(SELECTION_KEY);
  await Promise.all([loadChecks(), loadLatestReport(), loadRatingCurveStatus()]);
  renderScanAge();
  renderList();
  if (selectedId) showDetail(selectedId);
  else showEmpty();
  wireScanButton();
  wireRecomputeRatingsButton();
}

export function hideLibraryHealthView() {
  viewEl().style.display = 'none';
}

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

function renderList() {
  const ul = listEl();
  ul.innerHTML = '';
  if (checks.length === 0) {
    const li = document.createElement('li');
    li.className = 'lh-empty-list';
    li.textContent = 'No checks registered.';
    ul.appendChild(li);
    return;
  }
  for (const c of checks) {
    const li = document.createElement('li');
    const count = countFor(c.id);
    const hasFindings = count != null && count > 0;
    const cls = ['lh-row'];
    if (c.id === selectedId) cls.push('selected');
    if (hasFindings) cls.push('has-findings');
    else if (count === 0) cls.push('clean');
    li.className = cls.join(' ');
    const countHtml = count == null
        ? '<span class="lh-row-count unscanned">—</span>'
        : `<span class="lh-row-count">${count}</span>`;
    li.innerHTML = `
      <span class="lh-row-dot"></span>
      <span class="lh-row-label">${esc(c.label)}</span>
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

function renderScanAge() {
  const el = scanAgeEl();
  if (!report || !report.scannedAt) {
    el.style.display = 'none';
    return;
  }
  const scanned = new Date(report.scannedAt);
  el.textContent = 'Last scan: ' + formatAge(scanned);
  el.title = scanned.toLocaleString();
  el.style.display = '';
}

function formatAge(date) {
  const secs = Math.floor((Date.now() - date.getTime()) / 1000);
  if (secs < 60)  return 'just now';
  if (secs < 3600) return Math.floor(secs / 60) + 'm ago';
  if (secs < 86400) return Math.floor(secs / 3600) + 'h ago';
  const days = Math.floor(secs / 86400);
  if (days === 1)  return 'yesterday';
  if (days < 30)   return days + ' days ago';
  return date.toLocaleDateString();
}

function showEmpty() {
  detailEl().style.display = 'none';
  emptyEl().style.display = '';
  if (report) {
    emptyTitle().textContent = 'Select a check to see its findings';
    emptySub().textContent = 'Results are from the last scan. Rescan to refresh.';
  } else {
    emptyTitle().textContent = 'Run a scan to see health findings';
    emptySub().textContent = 'Each check reports a count; click a check to see details.';
  }
}

async function showDetail(checkId) {
  emptyEl().style.display = 'none';
  detailEl().style.display = '';
  const meta = checks.find(c => c.id === checkId);
  if (!meta) { detailEl().innerHTML = `<div class="lh-detail-body">No such check.</div>`; return; }
  if (!report) {
    detailEl().innerHTML = `
      <div class="lh-detail-head">
        <div class="lh-detail-label">${esc(meta.label)}</div>
        <div class="lh-detail-desc">${esc(meta.description)}</div>
      </div>
      <div class="lh-detail-body lh-detail-unscanned">
        Run a scan to populate this check's findings.
      </div>
    `;
    return;
  }
  try {
    const res = await fetch(`/api/utilities/health/report/latest/${encodeURIComponent(checkId)}`);
    if (!res.ok) {
      detailEl().innerHTML = `<div class="lh-detail-body">Detail unavailable.</div>`;
      return;
    }
    const entry = await res.json();
    renderDetail(meta, entry);
  } catch (e) {
    console.error('Failed to load check detail', e);
    detailEl().innerHTML = `<div class="lh-detail-body">Detail unavailable.</div>`;
  }
}

function renderDetail(meta, entry) {
  const total = entry.result.total;
  const rows = entry.result.rows || [];
  const statusHTML = total === 0
      ? `<div class="lh-detail-healthy">✓ No findings — this check is clean.</div>`
      : `<div class="lh-detail-count"><strong>${total}</strong> finding${total === 1 ? '' : 's'}</div>`;

  const sampleHTML = rows.length === 0 ? '' : `
    <div class="lh-findings">
      ${rows.map(r => `
        <div class="lh-finding">
          <div class="lh-finding-label">${esc(r.label)}</div>
          <div class="lh-finding-detail">${esc(r.detail || '')}</div>
        </div>
      `).join('')}
      ${total > rows.length ? `<div class="lh-findings-more">… and ${total - rows.length} more</div>` : ''}
    </div>
  `;

  const fixHTML = renderFixCTA(entry);

  detailEl().innerHTML = `
    <div class="lh-detail-head">
      <div class="lh-detail-label">${esc(meta.label)}</div>
      <div class="lh-detail-desc">${esc(meta.description)}</div>
    </div>
    <div class="lh-detail-body">
      ${statusHTML}
      ${sampleHTML}
      ${fixHTML}
    </div>
  `;

  const fixBtn = detailEl().querySelector('.lh-fix-cta');
  if (fixBtn) fixBtn.addEventListener('click', () => handleFixRoute(entry, entry.fixRouting));
}

function renderFixCTA(entry) {
  if (entry.result.total === 0) return '';
  switch (entry.fixRouting) {
    case 'INLINE':
      return inlineCtaFor(entry);
    case 'VOLUMES_SCREEN':
      return `<button type="button" class="lh-fix-cta">Open Volumes screen →</button>`;
    case 'ACTRESS_DATA_SCREEN':
      return `<button type="button" class="lh-fix-cta">Open Actress data →</button>`;
    case 'SURFACE_ONLY':
      return `<div class="lh-fix-surface-only">No automatic fix — resolve manually.</div>`;
    default:
      return '';
  }
}

/** Per-check inline CTA copy. Only orphaned_covers ships with an inline task today. */
function inlineCtaFor(entry) {
  if (entry.id === 'orphaned_covers') {
    return `<button type="button" class="lh-fix-cta" data-inline="orphaned_covers">Clean all (delete local files) →</button>`;
  }
  return '';
}

function handleFixRoute(entry, routing) {
  switch (routing) {
    case 'INLINE':
      if (entry.id === 'orphaned_covers') startOrphanedCoversFlow();
      break;
    case 'VOLUMES_SCREEN':
      document.getElementById('tools-volumes-btn')?.click();
      break;
    case 'ACTRESS_DATA_SCREEN':
      document.getElementById('tools-actress-data-btn')?.click();
      break;
  }
}

// ── Inline cleanup: orphaned covers ──────────────────────────────────────

async function startOrphanedCoversFlow() {
  if (taskCenter.isRunning()) { alert('Another task is running.'); return; }
  // Fetch the server-side preview (not the cached sample) so the user sees the full set.
  let preview;
  try {
    const res = await fetch('/api/utilities/tasks/covers.clean_orphaned/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}'
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
  const rows = preview.rows || [];
  const total = preview.count || 0;
  const size = formatSize(preview.totalBytes || 0);
  const sampleHTML = rows.slice(0, 100).map(r => `
    <div class="lh-finding">
      <div class="lh-finding-label">${esc(r.label)}/${esc(r.filename)}</div>
      <div class="lh-finding-detail">${esc(r.absolutePath)} · ${formatSize(r.sizeBytes)}</div>
    </div>
  `).join('');
  const moreHTML = rows.length > 100 ? `<div class="lh-findings-more">… and ${rows.length - 100} more in preview</div>` : '';
  const truncatedHTML = preview.truncated ? `<div class="lh-findings-more">Preview capped at 200 rows — delete will process all ${total}.</div>` : '';

  detailEl().innerHTML = `
    <div class="lh-detail-head">
      <div class="lh-detail-label">Clean orphaned covers</div>
      <div class="lh-detail-desc">Delete ${total} local cover file${total === 1 ? '' : 's'} · ${size} to free</div>
    </div>
    <div class="lh-detail-body">
      <div class="lh-findings">
        ${sampleHTML}
        ${moreHTML}
        ${truncatedHTML}
      </div>
      <div class="lh-visualize-actions">
        <button type="button" class="lh-fix-cta" id="lh-orphans-proceed">Proceed — delete ${total}</button>
        <button type="button" class="lh-visualize-cancel" id="lh-orphans-cancel">Cancel</button>
      </div>
    </div>
  `;
  document.getElementById('lh-orphans-proceed').addEventListener('click', runOrphanedCoversDelete);
  document.getElementById('lh-orphans-cancel').addEventListener('click', () => showDetail('orphaned_covers'));
}

async function runOrphanedCoversDelete() {
  try {
    const res = await fetch('/api/utilities/tasks/covers.clean_orphaned/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}'
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
    // Refresh the report so the count on the Library Health list updates to reflect the delete.
    await loadLatestReport();
    renderList();
    showDetail('orphaned_covers');
    es.close();
  });
  es.onerror = () => {};
}

function formatSize(bytes) {
  if (bytes == null || bytes < 0) return '?';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

// ── Scan ──────────────────────────────────────────────────────────────────

function wireScanButton() {
  const btn = scanBtn();
  btn.onclick = startScan;
  btn.disabled = taskCenter.isRunning();
}

async function startScan() {
  if (taskCenter.isRunning()) return;
  try {
    const res = await fetch('/api/utilities/tasks/library.scan/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}'
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
      overallPct: Math.round((phasesDone / totalPhases) * 100)
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
    // EventSource auto-reconnects; log once and let the finish event close it cleanly.
    if (!isScanning) es.close();
  };
}

// ── Rating curve status ───────────────────────────────────────────────────

async function loadRatingCurveStatus() {
  try {
    const res = await fetch('/api/utilities/rating-curve/status');
    if (!res.ok) return;
    const data = await res.json();
    renderRatingStatus(data);
  } catch (e) {
    // Non-fatal — status line just stays empty
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

function formatRelativeAge(date) {
  const diffMs = Date.now() - date.getTime();
  const diffMin = Math.round(diffMs / 60000);
  if (diffMin < 1) return 'just now';
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffH = Math.round(diffMin / 60);
  if (diffH < 24) return `${diffH}h ago`;
  return `${Math.round(diffH / 24)}d ago`;
}

// ── Recompute ratings ─────────────────────────────────────────────────────

function wireRecomputeRatingsButton() {
  const btn = recomputeRatingsBtn();
  btn.onclick = startRecomputeRatings;
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
      body: '{}'
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
      btn.classList.toggle('lh-scan--error', !succeeded);
      setTimeout(() => {
        btn.textContent = originalBtnText;
        btn.classList.remove('lh-scan--error');
        btn.disabled = taskCenter.isRunning();
      }, 3000);
    }

    // Refresh status line to show updated population + timestamp.
    await loadRatingCurveStatus();
    es.close();
  });
  es.onerror = () => { es.close(); };
}

// Keep both buttons' disabled state in sync with any other running task.
taskCenter.subscribe(() => {
  const scan = scanBtn();
  if (scan) scan.disabled = taskCenter.isRunning();
  const recompute = recomputeRatingsBtn();
  if (recompute && recompute.textContent !== 'Recomputing…') {
    recompute.disabled = taskCenter.isRunning();
  }
});
