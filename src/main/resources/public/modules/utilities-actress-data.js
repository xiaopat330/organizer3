// Utilities → Actress Data screen.
// Two-pane target + operations layout. Left: list of actress YAMLs on the
// classpath with DB-loaded indicator. Right: per-actress detail + operations.
// Atomic task lock, task pill, SSE streaming all reused from task-center.
// See spec/UTILITIES_ACTRESS_DATA.md.

import { esc } from './utils.js';
import * as taskCenter from './task-center.js';
import { showAliasEditor, hideAliasEditorView } from './alias-editor.js';

const SELECTION_KEY = 'utilities.actress-data.selection';
const SUBTAB_KEY    = 'utilities.actress-data.subtab';  // 'yamls' | 'aliases'

const listEl      = () => document.getElementById('ad-list');
const emptyEl     = () => document.getElementById('ad-empty');
const detailEl    = () => document.getElementById('ad-detail');
const visualizeEl = () => document.getElementById('ad-visualize');
const runEl       = () => document.getElementById('ad-run');
const viewEl      = () => document.getElementById('tools-actress-data-view');
const loadAllEl   = () => document.getElementById('ad-load-all');

function hideAllRightPanes() {
  emptyEl().style.display = 'none';
  detailEl().style.display = 'none';
  visualizeEl().style.display = 'none';
  runEl().style.display = 'none';
}

let entries = [];
let selectedSlug = null;
let activeRun = null;

export async function showActressDataView() {
  viewEl().style.display = 'block';
  const subtab = localStorage.getItem(SUBTAB_KEY) || 'yamls';
  selectSubtab(subtab);
}

export function hideActressDataView() {
  // Keep EventSource + activeRun alive so the task pill stays accurate while
  // the user is elsewhere in the app. Also dismiss the Aliases subview so its
  // search / modal don't leak into other screens.
  viewEl().style.display = 'none';
  hideAliasEditorView();
}

/** Switch between the two sub-tabs ('yamls' and 'aliases'). */
function selectSubtab(which) {
  localStorage.setItem(SUBTAB_KEY, which);
  const yamlsView   = document.getElementById('ad-subview-yamls');
  const aliasesView = document.getElementById('ad-subview-aliases');
  document.querySelectorAll('#ad-subnav .ad-subtab').forEach(btn =>
      btn.classList.toggle('selected', btn.dataset.subtab === which));

  if (which === 'aliases') {
    yamlsView.style.display = 'none';
    aliasesView.style.display = 'block';
    showAliasEditor();
  } else {
    hideAliasEditorView();
    aliasesView.style.display = 'none';
    yamlsView.style.display = 'flex';
    renderYamlsSubview();
  }
}

/** Populate the YAMLs subview — list + appropriate right pane. */
async function renderYamlsSubview() {
  selectedSlug = localStorage.getItem(SELECTION_KEY);
  await refreshEntries();

  if (activeRun && activeRun.taskStatus === 'running') {
    hideAllRightPanes();
    runEl().style.display = '';
    renderRun();
    return;
  }

  if (selectedSlug && entries.some(e => e.slug === selectedSlug)) {
    showDetail(selectedSlug);
  } else {
    showEmpty();
  }
}

// Sub-tab click wiring runs once at module load (buttons exist in the DOM).
document.querySelectorAll('#ad-subnav .ad-subtab').forEach(btn =>
    btn.addEventListener('click', () => selectSubtab(btn.dataset.subtab)));

async function refreshEntries() {
  try {
    const res = await fetch('/api/utilities/actress-yamls');
    entries = await res.json();
  } catch (err) {
    console.error('Failed to load actress catalog', err);
    entries = [];
  }
  renderList();
}

function renderList() {
  const ul = listEl();
  ul.innerHTML = '';
  for (const e of entries) {
    const li = document.createElement('li');
    if (e.slug === selectedSlug) li.classList.add('selected');
    li.addEventListener('click', () => {
      selectedSlug = e.slug;
      localStorage.setItem(SELECTION_KEY, e.slug);
      renderList();
      showDetail(e.slug);
    });

    li.innerHTML = `
      <div class="ad-row">
        <div class="ad-row-body">
          <div class="ad-row-title">
            <span class="ad-row-name">${esc(e.canonicalName || e.slug)}</span>
            ${e.loaded ? '<span class="ad-row-check" title="Loaded in database">✓</span>' : ''}
          </div>
          <div class="ad-row-slug">${esc(e.slug)}</div>
          <div class="ad-row-meta">${e.portfolioSize || 0} portfolio entries</div>
        </div>
      </div>
    `;
    ul.appendChild(li);
  }

  // Load All button disables while any task is running.
  loadAllEl().disabled = !!taskCenter.isRunning();
}

function showEmpty() {
  hideAllRightPanes();
  emptyEl().style.display = '';
}

function showDetail(slug) {
  const e = entries.find(x => x.slug === slug);
  if (!e) { showEmpty(); return; }

  hideAllRightPanes();
  const d = detailEl();
  d.style.display = '';

  const p = e.profile || {};
  const studios = (p.primaryStudios || []).map(s => `<span class="ad-chip">${esc(s)}</span>`).join('');

  d.innerHTML = `
    <div class="ad-detail-head">
      <div class="ad-detail-name">${esc(e.canonicalName || e.slug)}</div>
      <div class="ad-detail-slug">${esc(e.slug)}</div>
    </div>
    <div class="ad-detail-status ${e.loaded ? 'loaded' : 'unloaded'}">
      ${e.loaded
        ? '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg> Loaded in database'
        : 'Not yet loaded in database'}
    </div>
    <div class="ad-detail-grid">
      ${p.dateOfBirth ? `<div><span class="ad-stat-label">Born</span><span class="ad-stat-value">${esc(p.dateOfBirth)}</span></div>` : ''}
      ${p.heightCm ? `<div><span class="ad-stat-label">Height</span><span class="ad-stat-value">${p.heightCm} cm</span></div>` : ''}
      ${p.activeYears ? `<div><span class="ad-stat-label">Active</span><span class="ad-stat-value">${esc(p.activeYears)}</span></div>` : ''}
      <div><span class="ad-stat-label">Portfolio</span><span class="ad-stat-value">${e.portfolioSize || 0} entries</span></div>
    </div>
    ${studios ? `<div class="ad-detail-studios"><span class="ad-stat-label">Primary studios</span><div class="ad-chip-row">${studios}</div></div>` : ''}
    <div class="ad-section">
      <div class="ad-section-heading">Operations</div>
      <button type="button" class="ad-op-primary" id="ad-op-preview"${taskCenter.isRunning() ? ' disabled' : ''}>Preview changes</button>
      ${taskCenter.isRunning() ? '<div class="ad-op-blocked">Another utility task is running. Wait for it to finish.</div>' : ''}
    </div>
  `;
  document.getElementById('ad-op-preview').addEventListener('click', () => showPreview(e.slug));
}

// ── Visualize-then-confirm: preview field-level changes ───────────────────
async function showPreview(slug) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running. Wait for it to finish.');
    return;
  }
  const pane = visualizeEl();
  hideAllRightPanes();
  pane.style.display = '';
  pane.innerHTML = `<div class="ad-visualize-head">
    <div class="ad-visualize-title">Preview changes</div>
    <div class="ad-visualize-sub">Reading YAML and diffing against the database…</div>
  </div>`;

  let plan;
  try {
    const res = await fetch('/api/utilities/tasks/actress.load_one/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || `HTTP ${res.status}`);
    }
    plan = await res.json();
  } catch (err) {
    pane.innerHTML = `
      <div class="ad-visualize-head">
        <div class="ad-visualize-title">Preview changes</div>
        <div class="ad-visualize-sub">Failed to build preview: ${esc(err.message)}</div>
      </div>
      <div class="ad-visualize-actions">
        <button type="button" class="ad-visualize-cancel">Back</button>
      </div>
    `;
    pane.querySelector('.ad-visualize-cancel').addEventListener('click', () => showDetail(slug));
    return;
  }

  renderPlan(pane, slug, plan);
}

function renderPlan(pane, slug, plan) {
  const s = plan.summary || {};
  const actressLine = plan.actressChange && plan.actressChange.kind === 'create'
      ? `<span class="ad-vz-badge create">Create actress</span>`
      : (plan.actressChange && plan.actressChange.fields && plan.actressChange.fields.length > 0
          ? `<span class="ad-vz-badge update">Update actress (${plan.actressChange.fields.length} field${plan.actressChange.fields.length === 1 ? '' : 's'})</span>`
          : `<span class="ad-vz-badge noop">Actress unchanged</span>`);

  const titleLine = `
    <span class="ad-vz-badge">${s.titlesToCreate || 0} create</span>
    <span class="ad-vz-badge">${s.titlesToEnrich || 0} enrich</span>
    ${s.titlesNoop ? `<span class="ad-vz-badge noop">${s.titlesNoop} no change</span>` : ''}
  `;

  const tagLine = (s.tagsAdded || s.tagsRemoved)
      ? `<span class="ad-vz-badge tag-add">+${s.tagsAdded || 0} tags</span><span class="ad-vz-badge tag-rm">−${s.tagsRemoved || 0} tags</span>`
      : '';

  const nothingToDo = (s.actressChanged === 0)
      && ((s.titlesToCreate || 0) === 0)
      && ((s.titlesToEnrich || 0) === 0)
      && ((s.tagsAdded || 0) === 0)
      && ((s.tagsRemoved || 0) === 0);

  pane.innerHTML = `
    <div class="ad-visualize-head">
      <div class="ad-visualize-title">Preview changes · ${esc(slug)}</div>
      <div class="ad-vz-badges">${actressLine} ${titleLine} ${tagLine}</div>
    </div>
    ${renderActressBlock(plan.actressChange)}
    ${renderPortfolioBlock(plan.portfolioChanges || [])}
    <div class="ad-visualize-actions">
      <button type="button" class="ad-visualize-cancel">Cancel</button>
      <button type="button" class="ad-visualize-proceed"${nothingToDo ? ' disabled title="No changes to apply"' : ''}>
        ${nothingToDo ? 'No changes' : 'Proceed — apply'}
      </button>
    </div>
  `;
  pane.querySelector('.ad-visualize-cancel').addEventListener('click', () => showDetail(slug));
  const proceed = pane.querySelector('.ad-visualize-proceed');
  if (!nothingToDo) {
    proceed.addEventListener('click', () => startLoadOne(slug));
  }
}

function renderActressBlock(change) {
  if (!change) return '';
  if (change.kind === 'create' && change.fields && change.fields.length > 0) {
    return `
      <div class="ad-vz-section">
        <div class="ad-vz-section-head">Actress · new record</div>
        ${renderFieldList(change.fields, { allNew: true })}
      </div>
    `;
  }
  if (change.kind === 'update' && change.fields && change.fields.length > 0) {
    return `
      <div class="ad-vz-section">
        <div class="ad-vz-section-head">Actress · field updates</div>
        ${renderFieldList(change.fields, { allNew: false })}
      </div>
    `;
  }
  return '';
}

function renderPortfolioBlock(changes) {
  if (changes.length === 0) return '';
  const rows = changes.map(c => {
    if (c.kind === 'create') {
      const tagCount = (c.tags || []).length;
      return `
        <details class="ad-vz-title create">
          <summary>
            <span class="ad-vz-badge create">new</span>
            <span class="ad-vz-code">${esc(c.code)}</span>
            ${c.titleEnglish ? `<span class="ad-vz-muted">${esc(c.titleEnglish)}</span>` : ''}
            ${tagCount ? `<span class="ad-vz-chip">${tagCount} tag${tagCount === 1 ? '' : 's'}</span>` : ''}
          </summary>
          ${renderCreateTitleBody(c)}
        </details>
      `;
    }
    // enrich
    const changeCount = (c.fields || []).length;
    const added = (c.tagsAdded || []).length;
    const removed = (c.tagsRemoved || []).length;
    const isNoop = changeCount === 0 && added === 0 && removed === 0;
    return `
      <details class="ad-vz-title enrich${isNoop ? ' noop' : ''}">
        <summary>
          <span class="ad-vz-badge ${isNoop ? 'noop' : 'update'}">${isNoop ? 'no change' : 'update'}</span>
          <span class="ad-vz-code">${esc(c.code)}</span>
          ${changeCount ? `<span class="ad-vz-chip">${changeCount} field${changeCount === 1 ? '' : 's'}</span>` : ''}
          ${added ? `<span class="ad-vz-chip tag-add">+${added}</span>` : ''}
          ${removed ? `<span class="ad-vz-chip tag-rm">−${removed}</span>` : ''}
        </summary>
        ${renderEnrichTitleBody(c)}
      </details>
    `;
  }).join('');
  return `
    <div class="ad-vz-section">
      <div class="ad-vz-section-head">Portfolio · ${changes.length} entr${changes.length === 1 ? 'y' : 'ies'}</div>
      <div class="ad-vz-title-list">${rows}</div>
    </div>
  `;
}

function renderCreateTitleBody(c) {
  const rows = [];
  if (c.titleOriginal) rows.push({ field: 'titleOriginal', oldValue: null, newValue: c.titleOriginal });
  if (c.titleEnglish)  rows.push({ field: 'titleEnglish',  oldValue: null, newValue: c.titleEnglish });
  if (c.releaseDate)   rows.push({ field: 'releaseDate',   oldValue: null, newValue: c.releaseDate });
  if (c.notes)         rows.push({ field: 'notes',         oldValue: null, newValue: c.notes });
  if (c.grade)         rows.push({ field: 'grade',         oldValue: null, newValue: c.grade });

  const tagRow = (c.tags && c.tags.length > 0)
      ? `<div class="ad-vz-taglist"><span class="ad-vz-field-name">tags</span><div class="ad-vz-tags">${c.tags.map(t => `<span class="ad-vz-tag tag-add">${esc(t)}</span>`).join('')}</div></div>`
      : '';
  return `<div class="ad-vz-title-body">${renderFieldList(rows, { allNew: true })}${tagRow}</div>`;
}

function renderEnrichTitleBody(c) {
  const fields = renderFieldList(c.fields || [], { allNew: false });
  const added = (c.tagsAdded || []).map(t => `<span class="ad-vz-tag tag-add">+${esc(t)}</span>`).join('');
  const removed = (c.tagsRemoved || []).map(t => `<span class="ad-vz-tag tag-rm">−${esc(t)}</span>`).join('');
  const tagBlock = (added || removed)
      ? `<div class="ad-vz-taglist"><span class="ad-vz-field-name">tags</span><div class="ad-vz-tags">${added}${removed}</div></div>`
      : '';
  return `<div class="ad-vz-title-body">${fields}${tagBlock}</div>`;
}

function renderFieldList(fields, { allNew }) {
  if (!fields || fields.length === 0) return '';
  const rows = fields.map(f => {
    if (allNew || f.oldValue == null) {
      return `<tr>
        <td class="ad-vz-field-name">${esc(f.field)}</td>
        <td class="ad-vz-field-new" colspan="2">${renderValue(f.newValue)}</td>
      </tr>`;
    }
    return `<tr>
      <td class="ad-vz-field-name">${esc(f.field)}</td>
      <td class="ad-vz-field-old">${renderValue(f.oldValue)}</td>
      <td class="ad-vz-field-new">${renderValue(f.newValue)}</td>
    </tr>`;
  }).join('');
  const headHtml = allNew
      ? '<thead><tr><th>Field</th><th colspan="2">Value</th></tr></thead>'
      : '<thead><tr><th>Field</th><th>Current</th><th>New</th></tr></thead>';
  return `<table class="ad-vz-fields">${headHtml}<tbody>${rows}</tbody></table>`;
}

function renderValue(v) {
  if (v == null) return '<span class="ad-vz-muted">(none)</span>';
  if (Array.isArray(v)) {
    if (v.length === 0) return '<span class="ad-vz-muted">(empty)</span>';
    return v.map(x => `<div class="ad-vz-array-item">${esc(typeof x === 'string' ? x : JSON.stringify(x))}</div>`).join('');
  }
  if (typeof v === 'object') return `<pre class="ad-vz-obj">${esc(JSON.stringify(v, null, 2))}</pre>`;
  return esc(String(v));
}

async function startLoadOne(slug) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  try {
    const res = await fetch('/api/utilities/tasks/actress.load_one/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    taskCenter.start({
      taskId: 'actress.load_one',
      runId,
      label: `Loading ${slug}`,
    });
    beginRunView('actress.load_one', slug, runId);
  } catch (err) {
    alert('Failed to start load: ' + err.message);
  }
}

async function startLoadAll() {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  try {
    const res = await fetch('/api/utilities/tasks/actress.load_all/run', {
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
    taskCenter.start({
      taskId: 'actress.load_all',
      runId,
      label: 'Loading all actress YAMLs',
    });
    beginRunView('actress.load_all', null, runId);
  } catch (err) {
    alert('Failed to start load-all: ' + err.message);
  }
}

function beginRunView(taskId, slug, runId) {
  if (activeRun?.eventSource) activeRun.eventSource.close();
  activeRun = {
    runId,
    taskId,
    slug,
    eventSource: null,
    phases: new Map(),
    taskStatus: 'running',
    taskSummary: '',
  };

  hideAllRightPanes();
  runEl().style.display = '';
  renderRun();

  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  activeRun.eventSource = es;
  es.addEventListener('phase.started', e => handlePhaseStarted(JSON.parse(e.data)));
  es.addEventListener('phase.progress',e => handlePhaseProgress(JSON.parse(e.data)));
  es.addEventListener('phase.ended',   e => handlePhaseEnded(JSON.parse(e.data)));
  es.addEventListener('task.ended',    e => handleTaskEnded(JSON.parse(e.data)));
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
  });
  taskCenter.updateProgress({ phaseLabel: ev.label, overallPct: computeOverallPct() });
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
  taskCenter.updateProgress({
    phaseLabel: p.label, overallPct: computeOverallPct(), detail: p.detail,
  });
  renderRun();
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
  // Refresh list so the loaded ✓ flips for this actress.
  refreshEntries();
}

function computeOverallPct() {
  if (!activeRun) return 0;
  const total = activeRun.phases.size || 1;
  let sum = 0;
  for (const [, p] of activeRun.phases) {
    if (p.status === 'ok' || p.status === 'failed') sum += 100;
    else if (p.total > 0) sum += Math.min(100, 100 * p.current / p.total);
    else sum += 50;
  }
  return Math.min(100, sum / total);
}

function renderRun() {
  if (!activeRun) return;
  const heading = activeRun.taskId === 'actress.load_all'
      ? 'Loading all actress YAMLs'
      : `Loading ${activeRun.slug || 'actress'}`;

  const statusLabel = activeRun.taskStatus === 'running' ? 'running'
      : activeRun.taskStatus === 'ok' ? 'complete'
      : activeRun.taskStatus;

  const phasesHTML = Array.from(activeRun.phases.entries()).map(([, p]) => {
    const icon = p.status === 'running' ? '<span class="ad-run-spinner"></span>'
              : p.status === 'ok'      ? '✓'
              : p.status === 'failed'  ? '✗' : '○';
    const dur = p.durationMs != null ? formatMs(p.durationMs) : '';
    const bar = p.status === 'running'
        ? (p.total > 0
            ? `<div class="ad-phase-bar"><div class="ad-phase-bar-fill" style="width:${Math.floor(100 * p.current / p.total)}%"></div></div>`
            : `<div class="ad-phase-bar"><div class="ad-phase-bar-indet"></div></div>`)
        : '';
    return `
      <div class="ad-run-phase ${p.status}">
        <div class="ad-run-phase-icon">${icon}</div>
        <div>
          <div class="ad-run-phase-label">${esc(p.label)}</div>
          ${p.detail ? `<div class="ad-run-phase-detail">${esc(p.detail)}</div>` : ''}
          ${bar}
        </div>
        <div class="ad-run-phase-duration">${esc(dur)}</div>
      </div>
    `;
  }).join('');

  const actions = activeRun.taskStatus === 'running'
      ? ''
      : `<div class="ad-run-actions"><button type="button" id="ad-run-done">Done</button></div>`;

  runEl().innerHTML = `
    <div class="ad-run-head">
      <span>${esc(heading)}</span>
      <span class="ad-run-status ${activeRun.taskStatus}">${esc(statusLabel)}</span>
    </div>
    <div class="ad-run-phases">${phasesHTML}</div>
    ${activeRun.taskSummary ? `<div class="ad-run-summary">${esc(activeRun.taskSummary)}</div>` : ''}
    ${actions}
  `;

  if (activeRun.taskStatus !== 'running') {
    document.getElementById('ad-run-done').addEventListener('click', () => {
      activeRun = null;
      if (selectedSlug) showDetail(selectedSlug); else showEmpty();
    });
  }
}

function formatMs(ms) {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  const s = Math.floor(ms / 1000);
  return Math.floor(s / 60) + 'm ' + (s % 60) + 's';
}

// Load All button is always reachable in the header.
loadAllEl().addEventListener('click', startLoadAll);

// Re-render detail when task-center state flips, so the Load button's disabled
// state stays current even when the state change originates elsewhere.
taskCenter.subscribe(() => {
  if (viewEl().style.display !== 'none') {
    loadAllEl().disabled = !!taskCenter.isRunning();
    if (detailEl().style.display !== 'none' && selectedSlug) {
      showDetail(selectedSlug);
    }
  }
});
