// v2/workflow/stage-name-conflict.js — inline detail panel for stage_name_conflict rows.
//
// Detail shape: {"our_stage_name": "楓カレン", "javdb_variants": ["田中檸檬", "田中レモン"]}

import { esc } from './utils.js';

/**
 * Renders the stage-name-conflict inline panel into the given container element.
 *
 * @param {HTMLElement} container  the wf-row-candidates td
 * @param {object}      row        the workflow row object
 * @param {Function}    _reload    unused — no inline actions on this panel
 */
export function renderStageNameConflictPanel(container, row, _reload) {
  container.innerHTML = '';

  let detail = null;
  try { detail = row.detail ? JSON.parse(row.detail) : null; } catch {}

  if (!detail) {
    container.innerHTML = `<span class="wf-cast-note">No conflict detail available.</span>`;
    return;
  }

  const ourName  = detail.our_stage_name || '';
  const variants = Array.isArray(detail.javdb_variants) ? detail.javdb_variants : [];

  const variantChips = variants.length > 0
    ? variants.map(v => `<span class="wf-stage-variant-chip">${esc(v)}</span>`).join(' ')
    : '<span class="wf-cast-note">none</span>';

  const panel = document.createElement('div');
  panel.className = 'wf-stage-conflict-panel';
  panel.innerHTML = `
    <div class="wf-stage-conflict-label">Our stage name vs javdb</div>
    <div class="wf-stage-conflict-row">Ours: <b>${esc(ourName)}</b></div>
    <div class="wf-stage-conflict-row">javdb: ${variantChips}</div>
    <div class="wf-stage-conflict-hint">Actress identity may need manual triage.</div>
  `;
  container.appendChild(panel);
}
