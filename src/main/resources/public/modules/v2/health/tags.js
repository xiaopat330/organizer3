// health/tags.js — Tag Health tab.
// Ports utilities-tag-health.js verbatim into the v2 workbench pattern.
// Lists every enrichment_tag_definitions row with title_count, curatedAlias,
// surface flag. Filter controls, "unmapped only" + "show suppressed" toggles,
// per-row surface toggle that PATCHes the server and updates in-memory model.
// Endpoint: GET /api/javdb/discovery/tag-health
//           POST /api/javdb/discovery/tag-health/{id}/surface

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

// ── Module state ──────────────────────────────────────────────────────────────

let rootEl      = null;
let definitions = [];
let summary     = null;

// ── DOM helpers ───────────────────────────────────────────────────────────────

const $id = (id) => rootEl?.querySelector(`#ht-${id}`);
const summaryEl  = () => $id('summary');
const filterIn   = () => $id('filter');
const showUnmap  = () => $id('show-unmapped-only');
const showSupp   = () => $id('show-suppressed');
const stampEl    = () => $id('loaded-stamp');
const tableBody  = () => rootEl?.querySelector('#ht-table tbody');

// ── Entry ─────────────────────────────────────────────────────────────────────

export async function mountTags(root) {
  rootEl = root;
  root.innerHTML = renderShell();

  // Wire filter controls (re-render in-memory, no refetch).
  filterIn()?.addEventListener('input', () => renderTable());
  showUnmap()?.addEventListener('change', () => renderTable());
  showSupp()?.addEventListener('change', () => renderTable());

  await reload();
}

// ── Shell HTML ────────────────────────────────────────────────────────────────

function renderShell() {
  return `
    <div class="ht-wrap">
      <div class="ht-header">
        <div id="ht-summary" class="ht-summary">Loading…</div>
        <span id="ht-loaded-stamp" class="ht-stamp"></span>
      </div>

      <div class="ht-controls">
        <input type="text" id="ht-filter" class="form-input" placeholder="Filter by tag name…" style="max-width:240px">
        <label class="ht-show-toggle">
          <input type="checkbox" id="ht-show-unmapped-only">
          Unmapped only
        </label>
        <label class="ht-show-toggle">
          <input type="checkbox" id="ht-show-suppressed">
          Show suppressed
        </label>
      </div>

      <div class="wb-table-wrap">
        <table class="wb-table" id="ht-table">
          <thead>
            <tr>
              <th>Tag name</th>
              <th class="num" style="width:80px">Titles</th>
              <th class="num" style="width:80px">% library</th>
              <th style="width:160px">Curated alias</th>
              <th style="width:70px" class="ht-surface-col">Surface</th>
            </tr>
          </thead>
          <tbody></tbody>
        </table>
      </div>
    </div>
  `;
}

// ── Data + render ─────────────────────────────────────────────────────────────

async function reload() {
  const sum = summaryEl();
  if (sum) sum.textContent = 'Loading…';
  try {
    const res = await fetch('/api/javdb/discovery/tag-health');
    if (!res.ok) {
      if (sum) sum.textContent = 'Failed to load tag-health report.';
      const tb = tableBody();
      if (tb) tb.innerHTML = '';
      return;
    }
    const report = await res.json();
    definitions  = report.definitions;
    summary      = report.summary;
    renderSummary();
    renderTable();
    const stamp = stampEl();
    if (stamp) stamp.textContent = '· loaded ' + new Date().toLocaleTimeString();
  } catch (e) {
    const sum = summaryEl();
    if (sum) sum.textContent = 'Network error.';
  }
}

function renderSummary() {
  const el = summaryEl();
  if (!el) return;
  const mapped      = definitions.filter(d => d.curatedAlias).length;
  const suppressed  = definitions.filter(d => !d.surface).length;
  const unmappedHi  = definitions.filter(d => !d.curatedAlias && d.titleCount >= 3).length;
  const nearUniv    = definitions.filter(d => d.libraryPct >= 0.7).length;
  el.innerHTML = `
    <span class="ht-stat"><b>${summary.totalEnrichmentRows}</b> enriched titles</span>
    <span class="ht-stat"><b>${definitions.length}</b> tag definitions
      (<span class="ht-good">${mapped} mapped</span>,
      <span class="ht-warn">${definitions.length - mapped} unmapped</span>)</span>
    <span class="ht-stat"><b>${suppressed}</b> suppressed</span>
    <span class="ht-stat-sep">·</span>
    <span class="ht-stat ${unmappedHi > 0 ? 'ht-warn' : ''}">${unmappedHi} unmapped tags with ≥ 3 titles</span>
    <span class="ht-stat ${nearUniv > 0 ? 'ht-warn' : ''}">${nearUniv} near-universal (≥ 70% of library)</span>
  `;
}

function renderTable() {
  const tbody = tableBody();
  if (!tbody) return;
  const filterText      = filterIn()?.value.trim().toLowerCase() || '';
  const onlyUnmapped    = showUnmap()?.checked ?? false;
  const includeSuppressed = showSupp()?.checked ?? false;

  const visible = definitions.filter(d => {
    if (onlyUnmapped && d.curatedAlias) return false;
    if (!includeSuppressed && !d.surface) return false;
    if (filterText && !d.name.toLowerCase().includes(filterText)) return false;
    return true;
  });

  if (visible.length === 0) {
    tbody.innerHTML = `<tr><td colspan="5" class="ht-empty">No tag definitions match the current filter.</td></tr>`;
    return;
  }

  tbody.innerHTML = visible.map(rowHtml).join('');

  tbody.querySelectorAll('.ht-surface-toggle').forEach(input => {
    input.addEventListener('change', async (ev) => {
      const id   = parseInt(input.dataset.id, 10);
      const want = input.checked;
      input.disabled = true;
      const res = await fetch(`/api/javdb/discovery/tag-health/${id}/surface`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ surface: want }),
      });
      input.disabled = false;
      if (!res.ok) {
        // Revert visual state.
        input.checked = !want;
      } else {
        const def = definitions.find(d => d.id === id);
        if (def) def.surface = want;
        renderSummary();
        renderTable();
      }
    });
  });
}

function rowHtml(d) {
  const pct      = (d.libraryPct * 100).toFixed(1);
  const aliasCell = d.curatedAlias
    ? `<span class="ht-alias">${esc(d.curatedAlias)}</span>`
    : `<span class="ht-alias-missing">—</span>`;
  const nearUniv  = d.libraryPct >= 0.7;
  const pctCls    = nearUniv ? 'num ht-warn' : 'num';
  return `<tr class="${d.surface ? '' : 'ht-row-suppressed'}">
    <td class="ht-name">${esc(d.name)}</td>
    <td class="num">${d.titleCount}</td>
    <td class="${pctCls}">${pct}%</td>
    <td>${aliasCell}</td>
    <td class="ht-surface-cell">
      <label class="ht-switch">
        <input type="checkbox" class="ht-surface-toggle"
               data-id="${d.id}" ${d.surface ? 'checked' : ''}>
        <span class="ht-switch-slider"></span>
      </label>
    </td>
  </tr>`;
}
