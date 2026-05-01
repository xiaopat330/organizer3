// Utilities → AV Stars screen.
// Two-pane: filtered/sorted actress list on the left, selected actress
// detail + actions on the right. IAFD resolver picker lives in the right
// pane as a third mode (empty / detail / picker).
// See spec/UTILITIES_AV_STARS.md.

import { esc } from './utils.js';
import * as taskCenter from './task-center.js';
import { mount as mountScreenshotControls, unmount as unmountScreenshotControls } from './av-screenshot-controls.js';

const SELECTION_KEY = 'utilities.avstars.selection';
const FILTER_KEY    = 'utilities.avstars.filter';
const SORT_KEY      = 'utilities.avstars.sort';

const viewEl    = () => document.getElementById('tools-av-stars-view');
const listEl    = () => document.getElementById('as-list');
const countsEl  = () => document.getElementById('as-counts');
const filterEl  = () => document.getElementById('as-filter');
const sortEl    = () => document.getElementById('as-sort');
const emptyEl   = () => document.getElementById('as-empty');
const detailEl  = () => document.getElementById('as-detail');
const pickerEl  = () => document.getElementById('as-picker');

let rows = [];
let counts = { total: 0, resolved: 0, favorites: 0 };
let selectedId = null;
let currentDetail = null;  // { detail: {...}, techSummary: {...} }

export async function showAvStarsView() {
  viewEl().style.display = 'flex';
  filterEl().value = localStorage.getItem(FILTER_KEY) || 'ALL';
  sortEl().value   = localStorage.getItem(SORT_KEY)   || 'VIDEO_COUNT_DESC';
  filterEl().onchange = onFilterOrSortChange;
  sortEl().onchange   = onFilterOrSortChange;

  selectedId = localStorage.getItem(SELECTION_KEY);
  await refreshList();

  if (selectedId) showDetail(parseInt(selectedId, 10));
  else showEmpty();
}

export function hideAvStarsView() {
  unmountScreenshotControls();
  viewEl().style.display = 'none';
}

async function onFilterOrSortChange() {
  localStorage.setItem(FILTER_KEY, filterEl().value);
  localStorage.setItem(SORT_KEY,   sortEl().value);
  await refreshList();
}

async function refreshList() {
  try {
    const params = new URLSearchParams({
      filter: filterEl().value,
      sort:   sortEl().value,
    });
    const res = await fetch(`/api/utilities/avstars/actresses?${params}`);
    const body = await res.json();
    rows = body.rows || [];
    counts = body.counts || { total: 0, resolved: 0, favorites: 0 };
  } catch (e) {
    console.error('Failed to load av-stars list', e);
    rows = [];
  }
  renderCounts();
  renderList();
}

function renderCounts() {
  countsEl().textContent =
    `${counts.total} actresses · ${counts.resolved} resolved · ${counts.favorites} favorites`;
}

function renderList() {
  const ul = listEl();
  ul.innerHTML = '';
  if (rows.length === 0) {
    const li = document.createElement('li');
    li.className = 'as-empty-list';
    li.style.padding = '24px 16px';
    li.style.textAlign = 'center';
    li.style.color = '#64748b';
    li.style.fontSize = '12px';
    li.textContent = 'No actresses match this filter.';
    ul.appendChild(li);
    return;
  }
  for (const r of rows) {
    const li = document.createElement('li');
    li.className = 'as-row' + (r.id === selectedId ? ' selected' : '');
    const headshotStyle = r.headshotUrl
      ? `background-image: url(${esc(r.headshotUrl)})`
      : '';
    const badges = [];
    if (r.favorite) badges.push(`<span class="as-badge fav">★</span>`);
    if (r.bookmark) badges.push(`<span class="as-badge book">☆</span>`);
    badges.push(r.resolved
      ? `<span class="as-badge resolved">✓</span>`
      : `<span class="as-badge unres">?</span>`);
    badges.push(`<span class="as-badge">${r.videoCount}</span>`);
    li.innerHTML = `
      <div class="as-row-headshot" style="${headshotStyle}"></div>
      <div class="as-row-name">
        <div class="as-row-stage">${esc(r.stageName || r.folderName)}</div>
        ${r.stageName && r.stageName !== r.folderName
          ? `<div class="as-row-folder">${esc(r.folderName)}</div>` : ''}
      </div>
      <div class="as-row-meta">${badges.join('')}</div>
    `;
    li.addEventListener('click', () => {
      selectedId = r.id;
      localStorage.setItem(SELECTION_KEY, String(r.id));
      renderList();
      showDetail(r.id);
    });
    ul.appendChild(li);
  }
}

function hideAllRightPanes() {
  emptyEl().style.display = 'none';
  detailEl().style.display = 'none';
  pickerEl().style.display = 'none';
}

function showEmpty() {
  hideAllRightPanes();
  emptyEl().style.display = '';
}

async function showDetail(id) {
  unmountScreenshotControls();
  hideAllRightPanes();
  detailEl().style.display = '';
  detailEl().innerHTML = `<div class="as-detail-head">Loading…</div>`;
  try {
    const res = await fetch(`/api/utilities/avstars/actresses/${id}`);
    if (!res.ok) {
      detailEl().innerHTML = `<div>Detail unavailable.</div>`;
      return;
    }
    currentDetail = await res.json();
    renderDetail();
  } catch (e) {
    console.error('Failed to load actress detail', e);
    detailEl().innerHTML = `<div>Detail unavailable.</div>`;
  }
}

function renderDetail() {
  const d = currentDetail.detail;
  const t = currentDetail.techSummary;

  const headshotStyle = d.headshotUrl ? `background-image: url(${esc(d.headshotUrl)})` : '';
  const iafdLink = d.iafdId
    ? `IAFD: <a href="https://www.iafd.com/person.rme/id=${esc(d.iafdId)}" target="_blank" rel="noopener">${esc(d.iafdId)}</a>`
    : `IAFD: unresolved`;

  const stats = [];
  if (t) {
    stats.push(stat('Videos', String(t.videoCount)));
    stats.push(stat('Size',   formatBytes(t.totalBytes)));
    const topCodec = topEntry(t.byCodec);
    if (topCodec) stats.push(stat('Codec', `${topCodec[0]} (${topCodec[1]})`));
    const topRes = topEntry(t.byResolution);
    if (topRes) stats.push(stat('Resolution', `${topRes[0]} (${topRes[1]})`));
  }
  if (d.dateOfBirth) stats.push(stat('Born', d.dateOfBirth));
  if (d.nationality) stats.push(stat('Nationality', d.nationality));
  if (d.heightCm) stats.push(stat('Height', `${d.heightCm} cm`));
  if (d.activeFrom) stats.push(stat('Active', `${d.activeFrom}–${d.activeTo || 'present'}`));

  detailEl().innerHTML = `
    <div class="as-detail-head">
      <div class="as-detail-headshot" style="${headshotStyle}"></div>
      <div class="as-detail-ident">
        <div class="as-detail-stage">${esc(d.stageName || d.folderName)}</div>
        <div class="as-detail-folder">Folder: ${esc(d.folderName)} · Volume: —</div>
        <div class="as-detail-iafd">${iafdLink}</div>
        <div class="as-toggle-row">
          <span class="as-toggle ${d.favorite ? 'on' : ''}" data-toggle="favorite">${d.favorite ? '★' : '☆'} Favorite</span>
          <span class="as-toggle ${d.bookmark ? 'on' : ''}" data-toggle="bookmark">${d.bookmark ? '📑' : '📖'} Bookmark</span>
        </div>
        <div id="as-ss-controls"></div>
      </div>
    </div>
    <div class="as-detail-grid">${stats.join('')}</div>
    <div class="as-actions">
      <button type="button" class="as-action-btn" data-act="resolve">${d.resolved ? 'Re-resolve IAFD' : 'Resolve IAFD'}</button>
      <button type="button" class="as-action-btn" data-act="rename">Rename</button>
      <button type="button" class="as-action-btn" data-act="parse">Parse filenames</button>
      <button type="button" class="as-action-btn destructive" data-act="delete">Delete</button>
    </div>
  `;

  detailEl().querySelectorAll('[data-act]').forEach(btn =>
    btn.addEventListener('click', () => handleAction(btn.dataset.act, d)));
  detailEl().querySelectorAll('[data-toggle]').forEach(el =>
    el.addEventListener('click', () => toggleCuration(el.dataset.toggle, d)));

  mountScreenshotControls(detailEl().querySelector('#as-ss-controls'), d.id, null);
}

function stat(label, value) {
  return `<div class="as-stat">
    <div class="as-stat-label">${esc(label)}</div>
    <div class="as-stat-value">${esc(value)}</div>
  </div>`;
}

function topEntry(map) {
  if (!map) return null;
  const entries = Object.entries(map);
  if (entries.length === 0) return null;
  entries.sort((a, b) => b[1] - a[1]);
  return entries[0];
}

async function toggleCuration(kind, d) {
  const current = d[kind];
  const res = await fetch(
    `/api/av/actresses/${d.id}/${kind}?value=${!current}`, { method: 'POST' });
  if (!res.ok) { alert(`Toggle failed (${res.status})`); return; }
  // Reload list + detail so counters + row badges stay in sync.
  await refreshList();
  await showDetail(d.id);
}

function handleAction(action, d) {
  switch (action) {
    case 'resolve': startResolveFlow(d); break;
    case 'rename':  startRename(d); break;
    case 'parse':   startParse(d); break;
    case 'delete':  startDelete(d); break;
  }
}

// ── IAFD resolve picker ─────────────────────────────────────────────

async function startResolveFlow(d) {
  hideAllRightPanes();
  pickerEl().style.display = '';
  pickerEl().innerHTML = `
    <div class="as-picker-head">
      <input type="text" id="as-picker-input" class="as-picker-input" value="${esc(d.stageName || d.folderName)}" />
      <button type="button" class="as-action-btn" id="as-picker-search">Search</button>
      <button type="button" class="as-action-btn" id="as-picker-cancel">Cancel</button>
    </div>
    <div id="as-picker-cands" class="as-picker-cands"></div>
  `;
  document.getElementById('as-picker-search').addEventListener('click', () => runSearch(d.id));
  document.getElementById('as-picker-cancel').addEventListener('click', () => showDetail(d.id));
  document.getElementById('as-picker-input').addEventListener('keydown', e => {
    if (e.key === 'Enter') runSearch(d.id);
  });
  // Fire initial search with default name.
  runSearch(d.id);
}

async function runSearch(actressId) {
  const candsEl = document.getElementById('as-picker-cands');
  candsEl.innerHTML = '<div class="as-picker-empty">Searching IAFD…</div>';
  const name = document.getElementById('as-picker-input').value;
  try {
    const res = await fetch(`/api/utilities/avstars/actresses/${actressId}/iafd/search`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    });
    if (!res.ok) {
      candsEl.innerHTML = `<div class="as-picker-empty">Search failed (${res.status}).</div>`;
      return;
    }
    const { candidates } = await res.json();
    if (!candidates || candidates.length === 0) {
      candsEl.innerHTML = `<div class="as-picker-empty">No matches on IAFD.</div>`;
      return;
    }
    candsEl.innerHTML = '';
    for (const c of candidates) {
      const node = document.createElement('div');
      node.className = 'as-cand';
      const years = `${c.activeFrom || '?'}–${c.activeTo || '?'}`;
      const akas = (c.akas && c.akas.length)
        ? `aka ${c.akas.join(', ')}`
        : '';
      const thumb = c.headshotUrl ? `background-image: url(${esc(c.headshotUrl)})` : '';
      node.innerHTML = `
        <div class="as-cand-thumb" style="${thumb}"></div>
        <div class="as-cand-body">
          <div class="as-cand-name">${esc(c.name)}</div>
          <div class="as-cand-meta">${years} · ${c.titleCount || '?'} titles</div>
          ${akas ? `<div class="as-cand-akas">${esc(akas)}</div>` : ''}
        </div>
      `;
      node.addEventListener('click', () => applyPick(actressId, c.uuid));
      candsEl.appendChild(node);
    }
  } catch (e) {
    console.error('IAFD search error', e);
    candsEl.innerHTML = `<div class="as-picker-empty">Search failed — see console.</div>`;
  }
}

async function applyPick(actressId, iafdId) {
  if (taskCenter.isRunning()) { alert('Another task is running.'); return; }
  try {
    const res = await fetch(`/api/utilities/tasks/avstars.resolve_iafd/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ actressId: String(actressId), iafdId }),
    });
    if (!res.ok) {
      alert(`Apply failed (${res.status})`);
      return;
    }
    const { runId } = await res.json();
    taskCenter.start({ taskId: 'avstars.resolve_iafd', runId, label: 'Resolving IAFD' });
    subscribeToRun(runId, () => {
      refreshList().then(() => showDetail(actressId));
    });
  } catch (e) {
    console.error('Apply failed', e);
  }
}

// ── Rename / Delete / Parse filenames ───────────────────────────────

async function startRename(d) {
  const newName = prompt(`Rename "${d.stageName || d.folderName}" to:`, d.stageName || d.folderName);
  if (!newName || newName.trim() === '' || newName === d.stageName) return;
  await runTask('avstars.rename', { actressId: String(d.id), newName: newName.trim() },
    `Renaming to ${newName}`, () => refreshList().then(() => showDetail(d.id)));
}

async function startParse(d) {
  await runTask('avstars.parse_filenames', { actressId: String(d.id) },
    `Parsing filenames for ${d.stageName || d.folderName}`,
    () => showDetail(d.id));
}

async function startDelete(d) {
  const ok = confirm(
    `Permanently delete "${d.stageName || d.folderName}" — all her videos, screenshots, headshot?\n\nThis cannot be undone.`);
  if (!ok) return;
  await runTask('avstars.delete', { actressId: String(d.id) },
    `Deleting ${d.stageName || d.folderName}`, () => {
      selectedId = null;
      localStorage.removeItem(SELECTION_KEY);
      refreshList().then(() => showEmpty());
    });
}

async function runTask(taskId, inputs, label, onFinish) {
  if (taskCenter.isRunning()) { alert('Another task is running.'); return; }
  try {
    const res = await fetch(`/api/utilities/tasks/${taskId}/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(inputs),
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
    taskCenter.start({ taskId, runId, label });
    subscribeToRun(runId, onFinish);
  } catch (e) {
    console.error(`${taskId} failed`, e);
  }
}

function subscribeToRun(runId, onFinish) {
  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  es.addEventListener('phase.started', e => {
    const ev = JSON.parse(e.data);
    taskCenter.updateProgress({ phaseLabel: ev.label });
  });
  es.addEventListener('phase.progress', e => {
    const ev = JSON.parse(e.data);
    if (ev.total > 0) {
      taskCenter.updateProgress({ overallPct: Math.round((ev.current / ev.total) * 100) });
    }
  });
  es.addEventListener('task.ended', e => {
    const ev = JSON.parse(e.data);
    taskCenter.finish({ status: ev.status, summary: ev.summary });
    es.close();
    if (onFinish) onFinish();
  });
  es.onerror = () => {};
}

function formatBytes(bytes) {
  if (bytes == null || bytes < 0) return '?';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}
