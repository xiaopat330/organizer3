// Enrichment Tag Health view — Phase 3 maintenance dashboard.
// Lists every enrichment_tag_definitions row with its title_count,
// curated_alias status, and surface flag. Lets the user toggle surface
// to suppress noise tags from the faceted picker.

import { esc } from './utils.js';

const view       = document.getElementById('tools-tag-health-view');
const summaryEl  = document.getElementById('th-summary');
const filterIn   = document.getElementById('th-filter');
const showUnmap  = document.getElementById('th-show-unmapped-only');
const showSupp   = document.getElementById('th-show-suppressed');
const stampEl    = document.getElementById('th-loaded-stamp');
const tableBody  = document.querySelector('#th-table tbody');

let definitions = [];   // last fetched
let summary     = null;

export async function showTagHealthView() {
  view.style.display = '';
  await reload();
}

export function hideTagHealthView() {
  view.style.display = 'none';
}

async function reload() {
  summaryEl.textContent = 'Loading…';
  try {
    const res = await fetch('/api/javdb/discovery/tag-health');
    if (!res.ok) {
      summaryEl.textContent = 'Failed to load tag-health report.';
      tableBody.innerHTML = '';
      return;
    }
    const report = await res.json();
    definitions = report.definitions;
    summary     = report.summary;
    renderSummary();
    renderTable();
    stampEl.textContent = '· loaded ' + new Date().toLocaleTimeString();
  } catch (e) {
    summaryEl.textContent = 'Network error.';
  }
}

function renderSummary() {
  // Recompute counts from the live in-memory model so toggles reflect immediately;
  // the totals (totalEnrichmentRows / totalDefinitions) are static for the session.
  const mapped = definitions.filter(d => d.curatedAlias).length;
  const suppressed = definitions.filter(d => !d.surface).length;
  const unmappedHi = definitions.filter(d => !d.curatedAlias && d.titleCount >= 3).length;
  const nearUniv   = definitions.filter(d => d.libraryPct >= 0.7).length;
  summaryEl.innerHTML = `
    <span class="th-stat"><b>${summary.totalEnrichmentRows}</b> enriched titles</span>
    <span class="th-stat"><b>${definitions.length}</b> tag definitions
      (<span class="th-good">${mapped} mapped</span>,
      <span class="th-warn">${definitions.length - mapped} unmapped</span>)</span>
    <span class="th-stat"><b>${suppressed}</b> suppressed</span>
    <span class="th-stat-sep">·</span>
    <span class="th-stat ${unmappedHi > 0 ? 'th-warn' : ''}">${unmappedHi} unmapped tags with ≥ 3 titles</span>
    <span class="th-stat ${nearUniv > 0 ? 'th-warn' : ''}">${nearUniv} near-universal (≥ 70% of library)</span>
  `;
}

function renderTable() {
  const filterText = filterIn.value.trim().toLowerCase();
  const onlyUnmapped = showUnmap.checked;
  const includeSuppressed = showSupp.checked;
  const visible = definitions.filter(d => {
    if (onlyUnmapped && d.curatedAlias) return false;
    if (!includeSuppressed && !d.surface) return false;
    if (filterText && !d.name.toLowerCase().includes(filterText)) return false;
    return true;
  });
  if (visible.length === 0) {
    tableBody.innerHTML = `<tr><td colspan="5" class="th-empty">No tag definitions match the current filter.</td></tr>`;
    return;
  }
  tableBody.innerHTML = visible.map(rowHtml).join('');
  tableBody.querySelectorAll('.th-surface-toggle').forEach(input => {
    input.addEventListener('change', async (ev) => {
      const id = parseInt(input.dataset.id, 10);
      const want = input.checked;
      input.disabled = true;
      const res = await fetch(`/api/javdb/discovery/tag-health/${id}/surface`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({surface: want}),
      });
      input.disabled = false;
      if (!res.ok) {
        // revert visual state
        input.checked = !want;
      } else {
        // update local model + reflect everywhere
        const def = definitions.find(d => d.id === id);
        if (def) def.surface = want;
        renderSummary();
        renderTable();
      }
    });
  });
}

function rowHtml(d) {
  const pct = (d.libraryPct * 100).toFixed(1);
  const aliasCell = d.curatedAlias
    ? `<span class="th-alias">${esc(d.curatedAlias)}</span>`
    : `<span class="th-alias-missing">—</span>`;
  const nearUniv = d.libraryPct >= 0.7;
  const pctCls = nearUniv ? 'th-pct th-warn' : 'th-pct';
  return `<tr class="${d.surface ? '' : 'th-row-suppressed'}">
    <td class="th-name">${esc(d.name)}</td>
    <td class="th-count">${d.titleCount}</td>
    <td class="${pctCls}">${pct}%</td>
    <td>${aliasCell}</td>
    <td class="th-surface-cell">
      <label class="th-switch">
        <input type="checkbox" class="th-surface-toggle"
               data-id="${d.id}" ${d.surface ? 'checked' : ''}>
        <span class="th-switch-slider"></span>
      </label>
    </td>
  </tr>`;
}

// Filter inputs trigger re-render against in-memory data (no refetch).
filterIn.addEventListener('input', () => renderTable());
showUnmap.addEventListener('change', () => renderTable());
showSupp.addEventListener('change', () => renderTable());
