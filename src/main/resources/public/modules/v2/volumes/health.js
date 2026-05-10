// volumes/health.js — Per-volume health list + visualize-then-confirm flows.
// Currently: stale-locations (preview + task run). Generic health issue display
// handles any category the server may add; action buttons are wired only for
// known categories with server-side preview/run endpoints.
// Mirrors utilities-volumes.js stale-locations logic verbatim; endpoints unchanged.

import * as taskCenter from '../../task-center.js';
import { esc } from './cards.js';

// ── Health tab HTML ───────────────────────────────────────────────────────────

export function renderHealthTab(v) {
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
      ? `<button type="button" class="btn sm vol-health-action" data-cat="${esc(h.category)}"${taskCenter.isRunning() ? ' disabled' : ''}>${esc(action.label)}</button>`
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

function healthAction(h) {
  if (h.category === 'stale_locations') return { label: 'Clean up', kind: 'visualize-task' };
  return null;
}

function healthTooltip(category) {
  if (category === 'stale_locations') {
    return 'A stale location is a DB row saying "this file is at path X on this volume," '
         + "but the file wasn't found during the last sync — it was moved, renamed, or deleted. "
         + 'Cleaning up removes the index row only; nothing on disk is touched.';
  }
  return null;
}

// ── Stale-locations visualize flow ────────────────────────────────────────────

export async function showStaleLocationsVisualize(volumeId, pane, onBack, onStartRun) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running. Wait for it to finish.');
    return;
  }
  pane.innerHTML = `
    <div class="vol-visualize-head">
      <div class="vol-visualize-title">Clean stale locations</div>
      <div class="vol-visualize-sub">Fetching preview…</div>
    </div>
  `;

  let preview;
  try {
    const res = await fetch('/api/utilities/tasks/volume.clean_stale_locations/preview', {
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
        <button type="button" class="btn vol-visualize-cancel">Back</button>
      </div>
    `;
    pane.querySelector('.vol-visualize-cancel').addEventListener('click', onBack);
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
        <button type="button" class="btn vol-visualize-cancel">Back</button>
      </div>
    `;
    pane.querySelector('.vol-visualize-cancel').addEventListener('click', onBack);
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
      <button type="button" class="btn primary vol-visualize-proceed">Proceed — remove ${rows.length}</button>
      <button type="button" class="btn vol-visualize-cancel">Cancel</button>
    </div>
  `;

  pane.querySelector('.vol-visualize-cancel').addEventListener('click', onBack);
  pane.querySelector('.vol-visualize-proceed').addEventListener('click', () =>
    startCleanStaleLocations(volumeId, onStartRun));
}

async function startCleanStaleLocations(volumeId, onStartRun) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  try {
    const res = await fetch('/api/utilities/tasks/volume.clean_stale_locations/run', {
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
    onStartRun(volumeId, runId, 'volume.clean_stale_locations');
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
