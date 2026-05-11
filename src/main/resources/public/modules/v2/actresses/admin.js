// actresses/admin.js — Admin tab for the v2 Actresses browse page.
//
// Ported from: modules/utilities-actress-data.js + modules/alias-editor.js
// (legacy Utilities → Actress Data screen).
//
// Two sub-tabs:
//   YAMLs   — actress YAML list + per-actress detail + Load All + Sync Grades
//              + Preview→Apply workflow + SSE run view
//   Aliases — search + result list + per-actress inline alias editor
//              + merge-conflict flow + toast
//
// Key design notes:
//   • alias-editor.js registers event listeners at module-load time against
//     specific DOM IDs. We use dynamic import() AFTER injecting the alias
//     subview DOM so those getElementById() calls find their targets.
//   • task-center.js is safe for static import (all DOM access is lazy).
//   • All endpoint URLs are mirrored verbatim from the legacy module.

import * as taskCenter from '../../task-center.js';

// ── Utils ─────────────────────────────────────────────────────────────────

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

// ── Persistence keys ──────────────────────────────────────────────────────

const SUBTAB_KEY   = 'actresses-admin.subtab';     // 'yamls' | 'aliases'
const SELECTION_KEY = 'actresses-admin.selection';   // selected YAML slug

// ── Module-level state (reset on each mountAdminTab call) ────────────────

let panelEl      = null;
let entries      = [];
let selectedSlug = null;
let activeRun    = null;
let taskUnsub    = null;   // unsubscribe from task-center
let aliasModLoaded = false; // guard: import alias-editor only once per mount

// ── Public API ────────────────────────────────────────────────────────────

export function mountAdminTab(el) {
  panelEl = el;
  entries  = [];
  selectedSlug = null;
  activeRun = null;
  aliasModLoaded = false;

  panelEl.innerHTML = buildShell();

  // Sub-tab click wiring
  panelEl.querySelectorAll('.aca-subtab').forEach(btn =>
    btn.addEventListener('click', () => selectSubtab(btn.dataset.subtab)));

  // Load All + Sync Grades buttons
  panelEl.querySelector('#aca-load-all').addEventListener('click', startLoadAll);
  panelEl.querySelector('#aca-sync-grades').addEventListener('click', startSyncGrades);

  // Subscribe to task-center changes so buttons stay current
  taskUnsub = taskCenter.subscribe(() => {
    const running = !!taskCenter.isRunning();
    const loadAllBtn = panelEl?.querySelector('#aca-load-all');
    const syncBtn = panelEl?.querySelector('#aca-sync-grades');
    if (loadAllBtn) loadAllBtn.disabled = running;
    if (syncBtn)    syncBtn.disabled    = running;
    // Re-render detail pane if visible so the "Preview" button's disabled state
    // stays accurate.
    const detailEl = panelEl?.querySelector('#aca-detail');
    if (detailEl && detailEl.style.display !== 'none' && selectedSlug) {
      showDetail(selectedSlug);
    }
  });

  const subtab = localStorage.getItem(SUBTAB_KEY) || 'yamls';
  selectSubtab(subtab);
}

export function unmountAdminTab() {
  if (taskUnsub) { taskUnsub(); taskUnsub = null; }
  if (activeRun?.eventSource) { activeRun.eventSource.close(); }
  activeRun = null;
  panelEl = null;
  aliasModLoaded = false;
}

// ── Shell HTML ────────────────────────────────────────────────────────────

function buildShell() {
  return `
    <div class="aca-page">

      <!-- Sub-tab strip + global action buttons -->
      <div class="aca-subnav" id="aca-subnav">
        <div class="aca-subtab-group">
          <button class="aca-subtab" data-subtab="yamls">YAMLs</button>
          <button class="aca-subtab" data-subtab="aliases">Aliases</button>
        </div>
        <div class="aca-subnav-spacer"></div>
        <div class="aca-subnav-actions">
          <button type="button" id="aca-sync-grades" class="aca-global-btn">Sync Grades</button>
          <button type="button" id="aca-load-all"   class="aca-global-btn primary">Load All</button>
        </div>
      </div>

      <!-- YAMLs subview -->
      <div class="aca-subview" id="aca-subview-yamls" style="display:none">
        <!-- Left: actress YAML list -->
        <div class="aca-left">
          <div class="aca-left-header">
            <span>Actress YAMLs</span>
          </div>
          <ul class="aca-list" id="aca-list"></ul>
        </div>
        <!-- Right: contextual pane -->
        <div class="aca-right" id="aca-right">
          <!-- empty state -->
          <div class="aca-empty" id="aca-empty" style="display:none">
            <div class="aca-empty-icon">
              <svg viewBox="0 0 24 24" width="32" height="32" fill="none"
                   stroke="currentColor" stroke-width="1.5" stroke-linecap="round"
                   stroke-linejoin="round">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                <polyline points="14 2 14 8 20 8"/>
              </svg>
            </div>
            <div class="aca-empty-title">Select an actress</div>
            <div class="aca-empty-sub">Pick an entry from the list to view details and operations.</div>
          </div>
          <!-- detail -->
          <div class="aca-detail" id="aca-detail" style="display:none"></div>
          <!-- visualize (preview) -->
          <div class="aca-visualize" id="aca-visualize" style="display:none"></div>
          <!-- run (SSE) -->
          <div class="aca-run" id="aca-run" style="display:none"></div>
        </div>
      </div>

      <!-- Aliases subview (DOM structure required by alias-editor.js) -->
      <div class="aca-subview aca-subview-aliases" id="ad-subview-aliases" style="display:none">
        <div class="aca-left">
          <div class="aca-left-header">Search actresses</div>
          <div class="al-search-wrap">
            <input class="al-search" id="al-search-input" type="text"
                   placeholder="Type at least 2 characters…" autocomplete="off">
          </div>
          <div id="al-results" class="al-results"></div>
        </div>
        <div class="aca-right">
          <div id="al-empty" class="al-empty-state">
            <div class="aca-empty-title">Search for an actress</div>
            <div class="aca-empty-sub">Enter a name to look up and edit her aliases.</div>
          </div>
          <div id="al-detail" class="al-detail" style="display:none"></div>
        </div>
      </div>

    </div>
  `;
}

// ── Sub-tab switching ─────────────────────────────────────────────────────

async function selectSubtab(which) {
  localStorage.setItem(SUBTAB_KEY, which);
  panelEl.querySelectorAll('.aca-subtab').forEach(btn =>
    btn.classList.toggle('active', btn.dataset.subtab === which));

  const yamlsView   = panelEl.querySelector('#aca-subview-yamls');
  const aliasesView = panelEl.querySelector('#ad-subview-aliases');
  const loadAllBtn  = panelEl.querySelector('#aca-load-all');
  const syncGradesBtn = panelEl.querySelector('#aca-sync-grades');

  if (which === 'aliases') {
    yamlsView.style.display    = 'none';
    aliasesView.style.display  = 'flex';
    // Load All / Sync Grades don't apply to the alias editor
    loadAllBtn.style.display   = 'none';
    syncGradesBtn.style.display = 'none';
    await mountAliasEditor();
  } else {
    aliasesView.style.display  = 'none';
    yamlsView.style.display    = 'flex';
    loadAllBtn.style.display   = '';
    syncGradesBtn.style.display = '';
    await renderYamlsSubview();
  }
}

// ── YAMLs subview ─────────────────────────────────────────────────────────

async function renderYamlsSubview() {
  selectedSlug = localStorage.getItem(SELECTION_KEY);
  await refreshEntries();

  if (activeRun && activeRun.taskStatus === 'running') {
    hideAllRightPanes();
    showPane('aca-run');
    renderRun();
    return;
  }

  if (selectedSlug && entries.some(e => e.slug === selectedSlug)) {
    showDetail(selectedSlug);
  } else {
    showPane('aca-empty');
  }
}

async function refreshEntries() {
  try {
    const res = await fetch('/api/utilities/actress-yamls');
    entries = await res.json();
  } catch (err) {
    console.error('[actresses-admin] Failed to load actress YAMLs', err);
    entries = [];
  }
  renderList();
}

function renderList() {
  const ul = panelEl.querySelector('#aca-list');
  if (!ul) return;
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
      <div class="aca-row">
        <div class="aca-row-body">
          <div class="aca-row-title">
            <span class="aca-row-name">${esc(e.canonicalName || e.slug)}</span>
            ${e.loaded ? '<span class="aca-row-check" title="Loaded in database">✓</span>' : ''}
          </div>
          <div class="aca-row-slug">${esc(e.slug)}</div>
          <div class="aca-row-meta">${e.portfolioSize || 0} portfolio entries</div>
        </div>
      </div>
    `;
    ul.appendChild(li);
  }

  const running = !!taskCenter.isRunning();
  const loadAllBtn = panelEl?.querySelector('#aca-load-all');
  const syncBtn = panelEl?.querySelector('#aca-sync-grades');
  if (loadAllBtn) loadAllBtn.disabled = running;
  if (syncBtn) syncBtn.disabled = running;
}

// ── Right-pane show helpers ───────────────────────────────────────────────

function hideAllRightPanes() {
  ['aca-empty', 'aca-detail', 'aca-visualize', 'aca-run'].forEach(id => {
    const el = panelEl?.querySelector(`#${id}`);
    if (el) el.style.display = 'none';
  });
}

function showPane(id) {
  hideAllRightPanes();
  const el = panelEl?.querySelector(`#${id}`);
  if (el) el.style.display = '';
}

// ── Detail pane ───────────────────────────────────────────────────────────

function showDetail(slug) {
  const e = entries.find(x => x.slug === slug);
  if (!e) { showPane('aca-empty'); return; }

  showPane('aca-detail');
  const d = panelEl.querySelector('#aca-detail');

  const p = e.profile || {};
  const studios = (p.primaryStudios || []).map(s => `<span class="aca-chip">${esc(s)}</span>`).join('');
  const running = !!taskCenter.isRunning();

  d.innerHTML = `
    <div class="aca-detail-head">
      <div class="aca-detail-name">${esc(e.canonicalName || e.slug)}</div>
      <div class="aca-detail-slug">${esc(e.slug)}</div>
    </div>
    <div class="aca-detail-status ${e.loaded ? 'loaded' : 'unloaded'}">
      ${e.loaded
        ? '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg> Loaded in database'
        : 'Not yet loaded in database'}
    </div>
    <div class="aca-detail-grid">
      ${p.dateOfBirth ? `<div><span class="aca-stat-label">Born</span><span class="aca-stat-value">${esc(p.dateOfBirth)}</span></div>` : ''}
      ${p.heightCm   ? `<div><span class="aca-stat-label">Height</span><span class="aca-stat-value">${p.heightCm} cm</span></div>` : ''}
      ${p.activeYears ? `<div><span class="aca-stat-label">Active</span><span class="aca-stat-value">${esc(p.activeYears)}</span></div>` : ''}
      <div><span class="aca-stat-label">Portfolio</span><span class="aca-stat-value">${e.portfolioSize || 0} entries</span></div>
    </div>
    ${studios ? `<div class="aca-detail-studios"><span class="aca-stat-label">Primary studios</span><div class="aca-chip-row">${studios}</div></div>` : ''}
    <div class="aca-section">
      <div class="aca-section-heading">Operations</div>
      <button type="button" class="aca-op-primary" id="aca-op-preview"${running ? ' disabled' : ''}>Preview changes</button>
      ${running ? '<div class="aca-op-blocked">Another utility task is running. Wait for it to finish.</div>' : ''}
    </div>
  `;

  d.querySelector('#aca-op-preview').addEventListener('click', () => showPreview(e.slug));
}

// ── Preview / visualize pane ──────────────────────────────────────────────

async function showPreview(slug) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running. Wait for it to finish.');
    return;
  }
  showPane('aca-visualize');
  const pane = panelEl.querySelector('#aca-visualize');
  pane.innerHTML = `
    <div class="aca-visualize-head">
      <div class="aca-visualize-title">Preview changes</div>
      <div class="aca-visualize-sub">Reading YAML and diffing against the database…</div>
    </div>
  `;

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
      <div class="aca-visualize-head">
        <div class="aca-visualize-title">Preview changes</div>
        <div class="aca-visualize-sub">Failed to build preview: ${esc(err.message)}</div>
      </div>
      <div class="aca-visualize-actions">
        <button type="button" class="aca-visualize-cancel">Back</button>
      </div>
    `;
    pane.querySelector('.aca-visualize-cancel').addEventListener('click', () => showDetail(slug));
    return;
  }

  renderPlan(pane, slug, plan);
}

function renderPlan(pane, slug, plan) {
  const s = plan.summary || {};
  const actressLine = plan.actressChange && plan.actressChange.kind === 'create'
    ? `<span class="aca-vz-badge create">Create actress</span>`
    : (plan.actressChange && plan.actressChange.fields && plan.actressChange.fields.length > 0
        ? `<span class="aca-vz-badge update">Update actress (${plan.actressChange.fields.length} field${plan.actressChange.fields.length === 1 ? '' : 's'})</span>`
        : `<span class="aca-vz-badge noop">Actress unchanged</span>`);

  const titleLine = `
    <span class="aca-vz-badge">${s.titlesToCreate || 0} create</span>
    <span class="aca-vz-badge">${s.titlesToEnrich || 0} enrich</span>
    ${s.titlesNoop ? `<span class="aca-vz-badge noop">${s.titlesNoop} no change</span>` : ''}
  `;

  const tagLine = (s.tagsAdded || s.tagsRemoved)
    ? `<span class="aca-vz-badge tag-add">+${s.tagsAdded || 0} tags</span><span class="aca-vz-badge tag-rm">−${s.tagsRemoved || 0} tags</span>`
    : '';

  const nothingToDo = (s.actressChanged === 0)
    && ((s.titlesToCreate || 0) === 0)
    && ((s.titlesToEnrich || 0) === 0)
    && ((s.tagsAdded || 0) === 0)
    && ((s.tagsRemoved || 0) === 0);

  pane.innerHTML = `
    <div class="aca-visualize-head">
      <div class="aca-visualize-title">Preview changes · ${esc(slug)}</div>
      <div class="aca-vz-badges">${actressLine} ${titleLine} ${tagLine}</div>
    </div>
    ${renderActressBlock(plan.actressChange)}
    ${renderPortfolioBlock(plan.portfolioChanges || [])}
    <div class="aca-visualize-actions">
      <button type="button" class="aca-visualize-proceed"${nothingToDo ? ' disabled title="No changes to apply"' : ''}>
        ${nothingToDo ? 'No changes' : 'Proceed — apply'}
      </button>
      <button type="button" class="aca-visualize-cancel">Cancel</button>
    </div>
  `;

  pane.querySelector('.aca-visualize-cancel').addEventListener('click', () => showDetail(slug));
  const proceed = pane.querySelector('.aca-visualize-proceed');
  if (!nothingToDo) {
    proceed.addEventListener('click', () => startLoadOne(slug));
  }
}

function renderActressBlock(change) {
  if (!change) return '';
  if (change.kind === 'create' && change.fields && change.fields.length > 0) {
    return `
      <div class="aca-vz-section">
        <div class="aca-vz-section-head">Actress · new record</div>
        ${renderFieldList(change.fields, { allNew: true })}
      </div>
    `;
  }
  if (change.kind === 'update' && change.fields && change.fields.length > 0) {
    return `
      <div class="aca-vz-section">
        <div class="aca-vz-section-head">Actress · field updates</div>
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
        <details class="aca-vz-title create">
          <summary>
            <span class="aca-vz-badge create">new</span>
            <span class="aca-vz-code">${esc(c.code)}</span>
            ${c.titleEnglish ? `<span class="aca-vz-muted">${esc(c.titleEnglish)}</span>` : ''}
            ${tagCount ? `<span class="aca-vz-chip">${tagCount} tag${tagCount === 1 ? '' : 's'}</span>` : ''}
          </summary>
          ${renderCreateTitleBody(c)}
        </details>
      `;
    }
    const changeCount = (c.fields || []).length;
    const added   = (c.tagsAdded   || []).length;
    const removed = (c.tagsRemoved || []).length;
    const isNoop  = changeCount === 0 && added === 0 && removed === 0;
    return `
      <details class="aca-vz-title enrich${isNoop ? ' noop' : ''}">
        <summary>
          <span class="aca-vz-badge ${isNoop ? 'noop' : 'update'}">${isNoop ? 'no change' : 'update'}</span>
          <span class="aca-vz-code">${esc(c.code)}</span>
          ${changeCount ? `<span class="aca-vz-chip">${changeCount} field${changeCount === 1 ? '' : 's'}</span>` : ''}
          ${added   ? `<span class="aca-vz-chip tag-add">+${added}</span>` : ''}
          ${removed ? `<span class="aca-vz-chip tag-rm">−${removed}</span>` : ''}
        </summary>
        ${renderEnrichTitleBody(c)}
      </details>
    `;
  }).join('');
  return `
    <div class="aca-vz-section">
      <div class="aca-vz-section-head">Portfolio · ${changes.length} entr${changes.length === 1 ? 'y' : 'ies'}</div>
      <div class="aca-vz-title-list">${rows}</div>
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
    ? `<div class="aca-vz-taglist"><span class="aca-vz-field-name">tags</span><div class="aca-vz-tags">${c.tags.map(t => `<span class="aca-vz-tag tag-add">${esc(t)}</span>`).join('')}</div></div>`
    : '';
  return `<div class="aca-vz-title-body">${renderFieldList(rows, { allNew: true })}${tagRow}</div>`;
}

function renderEnrichTitleBody(c) {
  const fields  = renderFieldList(c.fields || [], { allNew: false });
  const added   = (c.tagsAdded   || []).map(t => `<span class="aca-vz-tag tag-add">+${esc(t)}</span>`).join('');
  const removed = (c.tagsRemoved || []).map(t => `<span class="aca-vz-tag tag-rm">−${esc(t)}</span>`).join('');
  const tagBlock = (added || removed)
    ? `<div class="aca-vz-taglist"><span class="aca-vz-field-name">tags</span><div class="aca-vz-tags">${added}${removed}</div></div>`
    : '';
  return `<div class="aca-vz-title-body">${fields}${tagBlock}</div>`;
}

function renderFieldList(fields, { allNew }) {
  if (!fields || fields.length === 0) return '';
  const rows = fields.map(f => {
    if (allNew || f.oldValue == null) {
      return `<tr>
        <td class="aca-vz-field-name">${esc(f.field)}</td>
        <td class="aca-vz-field-new" colspan="2">${renderValue(f.newValue)}</td>
      </tr>`;
    }
    return `<tr>
      <td class="aca-vz-field-name">${esc(f.field)}</td>
      <td class="aca-vz-field-old">${renderValue(f.oldValue)}</td>
      <td class="aca-vz-field-new">${renderValue(f.newValue)}</td>
    </tr>`;
  }).join('');
  const headHtml = allNew
    ? '<thead><tr><th>Field</th><th colspan="2">Value</th></tr></thead>'
    : '<thead><tr><th>Field</th><th>Current</th><th>New</th></tr></thead>';
  return `<table class="aca-vz-fields">${headHtml}<tbody>${rows}</tbody></table>`;
}

function renderValue(v) {
  if (v == null) return '<span class="aca-vz-muted">(none)</span>';
  if (Array.isArray(v)) {
    if (v.length === 0) return '<span class="aca-vz-muted">(empty)</span>';
    return v.map(x => `<div class="aca-vz-array-item">${esc(typeof x === 'string' ? x : JSON.stringify(x))}</div>`).join('');
  }
  if (typeof v === 'object') return `<pre class="aca-vz-obj">${esc(JSON.stringify(v, null, 2))}</pre>`;
  return esc(String(v));
}

// ── Task start helpers ────────────────────────────────────────────────────

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
    taskCenter.start({ taskId: 'actress.load_one', runId, label: `Loading ${slug}` });
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
    taskCenter.start({ taskId: 'actress.load_all', runId, label: 'Loading all actress YAMLs' });
    beginRunView('actress.load_all', null, runId);
  } catch (err) {
    alert('Failed to start load-all: ' + err.message);
  }
}

async function startSyncGrades() {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  try {
    const res = await fetch('/api/utilities/tasks/actress.sync_yaml_grades/run', {
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
    taskCenter.start({ taskId: 'actress.sync_yaml_grades', runId, label: 'Syncing YAML grades' });
    beginRunView('actress.sync_yaml_grades', null, runId);
  } catch (err) {
    alert('Failed to start sync-grades: ' + err.message);
  }
}

// ── Run (SSE) view ────────────────────────────────────────────────────────

function beginRunView(taskId, slug, runId) {
  if (activeRun?.eventSource) activeRun.eventSource.close();
  activeRun = {
    runId, taskId, slug,
    eventSource: null,
    phases: new Map(),
    taskStatus: 'running',
    taskSummary: '',
  };

  // Switch to YAMLs subtab if we're in aliases (task was started from YAMLs)
  const yamlsView = panelEl?.querySelector('#aca-subview-yamls');
  if (yamlsView) yamlsView.style.display = 'flex';

  showPane('aca-run');
  renderRun();

  const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  activeRun.eventSource = es;
  es.addEventListener('phase.started',  e => handlePhaseStarted(JSON.parse(e.data)));
  es.addEventListener('phase.progress', e => handlePhaseProgress(JSON.parse(e.data)));
  es.addEventListener('phase.ended',    e => handlePhaseEnded(JSON.parse(e.data)));
  es.addEventListener('task.ended',     e => handleTaskEnded(JSON.parse(e.data)));
}

function handlePhaseStarted(ev) {
  if (!activeRun) return;
  activeRun.phases.set(ev.phaseId, {
    label: ev.label, status: 'running', detail: '',
    durationMs: null, current: 0, total: -1,
  });
  taskCenter.updateProgress({ phaseLabel: ev.label, overallPct: computeOverallPct() });
  renderRun();
}

function handlePhaseProgress(ev) {
  if (!activeRun) return;
  const p = activeRun.phases.get(ev.phaseId);
  if (!p) return;
  p.current = ev.current;
  p.total   = ev.total;
  if (ev.total > 0) {
    p.detail = `${ev.current} / ${ev.total}${ev.detail ? ' — ' + ev.detail : ''}`;
  } else if (ev.detail) {
    p.detail = ev.detail;
  }
  taskCenter.updateProgress({ phaseLabel: p.label, overallPct: computeOverallPct(), detail: p.detail });
  renderRun();
}

function handlePhaseEnded(ev) {
  if (!activeRun) return;
  const p = activeRun.phases.get(ev.phaseId);
  if (!p) return;
  p.status     = ev.status;
  p.durationMs = ev.durationMs;
  if (ev.summary) p.detail = ev.summary;
  taskCenter.updateProgress({ overallPct: computeOverallPct() });
  renderRun();
}

function handleTaskEnded(ev) {
  if (!activeRun) return;
  activeRun.taskStatus  = ev.status;
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
  const runEl = panelEl?.querySelector('#aca-run');
  if (!runEl) return;

  const heading = activeRun.taskId === 'actress.load_all'
    ? 'Loading all actress YAMLs'
    : activeRun.taskId === 'actress.sync_yaml_grades'
      ? 'Syncing YAML grades'
      : `Loading ${activeRun.slug || 'actress'}`;

  const statusLabel = activeRun.taskStatus === 'running' ? 'running'
    : activeRun.taskStatus === 'ok' ? 'complete'
    : activeRun.taskStatus;

  const phasesHTML = Array.from(activeRun.phases.entries()).map(([, p]) => {
    const icon = p.status === 'running' ? '<span class="aca-run-spinner"></span>'
               : p.status === 'ok'      ? '✓'
               : p.status === 'failed'  ? '✗' : '○';
    const dur = p.durationMs != null ? formatMs(p.durationMs) : '';
    const bar = p.status === 'running'
      ? (p.total > 0
          ? `<div class="aca-phase-bar"><div class="aca-phase-bar-fill" style="width:${Math.floor(100 * p.current / p.total)}%"></div></div>`
          : `<div class="aca-phase-bar"><div class="aca-phase-bar-indet"></div></div>`)
      : '';
    return `
      <div class="aca-run-phase ${p.status}">
        <div class="aca-run-phase-icon">${icon}</div>
        <div>
          <div class="aca-run-phase-label">${esc(p.label)}</div>
          ${p.detail ? `<div class="aca-run-phase-detail">${esc(p.detail)}</div>` : ''}
          ${bar}
        </div>
        <div class="aca-run-phase-duration">${esc(dur)}</div>
      </div>
    `;
  }).join('');

  const actions = activeRun.taskStatus === 'running'
    ? ''
    : `<div class="aca-run-actions"><button type="button" id="aca-run-done">Done</button></div>`;

  runEl.innerHTML = `
    <div class="aca-run-head">
      <span>${esc(heading)}</span>
      <span class="aca-run-status ${activeRun.taskStatus}">${esc(statusLabel)}</span>
    </div>
    <div class="aca-run-phases">${phasesHTML}</div>
    ${activeRun.taskSummary ? `<div class="aca-run-summary">${esc(activeRun.taskSummary)}</div>` : ''}
    ${actions}
  `;

  if (activeRun.taskStatus !== 'running') {
    runEl.querySelector('#aca-run-done').addEventListener('click', () => {
      activeRun = null;
      if (selectedSlug) showDetail(selectedSlug); else showPane('aca-empty');
    });
  }
}

function formatMs(ms) {
  if (ms < 1000)  return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  const s = Math.floor(ms / 1000);
  return Math.floor(s / 60) + 'm ' + (s % 60) + 's';
}

// ── Alias editor (dynamic import) ─────────────────────────────────────────
//
// alias-editor.js registers addEventListener calls at module-load time, so it
// must be imported AFTER the alias subview DOM (with its required IDs) has
// been injected into the page. Since buildShell() writes those IDs before
// selectSubtab() is called, by the time the user clicks Aliases the DOM is
// ready. Guard with aliasModLoaded so we import exactly once per mount.

async function mountAliasEditor() {
  if (aliasModLoaded) {
    // Module already imported and wired — just show its view.
    const { showAliasEditor } = await import('../../alias-editor.js');
    showAliasEditor();
    return;
  }
  // First visit: import (which will wire the event listeners) then show.
  try {
    const mod = await import('../../alias-editor.js');
    aliasModLoaded = true;
    mod.showAliasEditor();
  } catch (err) {
    console.error('[actresses-admin] Failed to load alias editor', err);
  }
}
