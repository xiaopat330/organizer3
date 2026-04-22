// Utilities → Library Health screen.
// Two-pane: diagnostic checks on the left, selected-check detail on the right.
// "Scan library" runs every check as a single atomic task. Findings route back
// to the owning screens (Volumes, Actress Data) where fixes already exist.
// See spec/UTILITIES_LIBRARY_HEALTH.md.

import { esc } from './utils.js';
import * as taskCenter from './task-center.js';

const SELECTION_KEY = 'utilities.libraryHealth.selection';

const viewEl    = () => document.getElementById('tools-library-health-view');
const listEl    = () => document.getElementById('lh-list');
const emptyEl   = () => document.getElementById('lh-empty');
const detailEl  = () => document.getElementById('lh-detail');
const scanBtn   = () => document.getElementById('lh-scan');

let checks = [];           // [{id, label, description, fixRouting}]
let report = null;         // latest report summary { runId, scannedAt, checks: [{id,total,...}] }
let selectedId = null;
let isScanning = false;

export async function showLibraryHealthView() {
  viewEl().style.display = 'flex';
  selectedId = localStorage.getItem(SELECTION_KEY);
  await Promise.all([loadChecks(), loadLatestReport()]);
  renderList();
  if (selectedId) showDetail(selectedId);
  else showEmpty();
  wireScanButton();
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

function showEmpty() {
  detailEl().style.display = 'none';
  emptyEl().style.display = '';
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
  if (fixBtn) fixBtn.addEventListener('click', () => handleFixRoute(entry.fixRouting));
}

function renderFixCTA(entry) {
  if (entry.result.total === 0) return '';
  switch (entry.fixRouting) {
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

function handleFixRoute(routing) {
  switch (routing) {
    case 'VOLUMES_SCREEN':
      document.getElementById('tools-volumes-btn')?.click();
      break;
    case 'ACTRESS_DATA_SCREEN':
      document.getElementById('tools-actress-data-btn')?.click();
      break;
  }
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
    renderList();
    if (selectedId) showDetail(selectedId);
    es.close();
  });
  es.onerror = () => {
    // EventSource auto-reconnects; log once and let the finish event close it cleanly.
    if (!isScanning) es.close();
  };
}

// Keep the scan button's disabled state in sync with any other running task.
taskCenter.subscribe(() => {
  const btn = scanBtn();
  if (!btn) return;
  btn.disabled = taskCenter.isRunning();
});
