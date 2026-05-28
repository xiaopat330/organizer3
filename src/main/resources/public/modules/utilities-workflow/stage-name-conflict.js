// utilities-workflow/stage-name-conflict.js — inline detail panel for stage_name_conflict rows.
// Forked from modules/v2/workflow/stage-name-conflict.js; reskinned to .wf1-* classes.
// Detail shape: {"our_stage_name": "...", "javdb_variants": [...]} — info only, no actions.

import { esc } from './utils.js';

export function renderStageNameConflictPanel(container, row, _reload) {
  container.innerHTML = '';

  let detail = null;
  try { detail = row.detail ? JSON.parse(row.detail) : null; } catch {}

  if (!detail) {
    container.innerHTML = `<span class="wf1-cast-note">No conflict detail available.</span>`;
    return;
  }

  const ourName  = detail.our_stage_name || '';
  const variants = Array.isArray(detail.javdb_variants) ? detail.javdb_variants : [];

  const variantChips = variants.length > 0
    ? variants.map(v => `<span class="wf1-stage-variant-chip">${esc(v)}</span>`).join(' ')
    : '<span class="wf1-cast-note">none</span>';

  const panel = document.createElement('div');
  panel.className = 'wf1-stage-conflict-panel';
  panel.innerHTML = `
    <div class="wf1-stage-conflict-label">Our stage name vs javdb</div>
    <div class="wf1-stage-conflict-row">Ours: <b>${esc(ourName)}</b></div>
    <div class="wf1-stage-conflict-row">javdb: ${variantChips}</div>
    <div class="wf1-stage-conflict-hint">Actress identity may need manual triage.</div>
  `;
  container.appendChild(panel);
}
