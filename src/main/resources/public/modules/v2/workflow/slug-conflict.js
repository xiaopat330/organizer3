// v2/workflow/slug-conflict.js — inline detail panel for slug_conflict rows.
//
// The workflow DTO includes slugConflictContext ({claimant, incumbent}) and the
// raw detail JSON (slug, claimant_actress_id, incumbent_actress_id, source_title_code)
// when reason === 'slug_conflict'.  These are populated by WorkflowRoutes.queryWorkflowRows.

import { esc } from './utils.js';
import { handleResolve } from './actions.js';

/**
 * Renders the slug-conflict inline panel into the given container element.
 *
 * @param {HTMLElement} container  the wf-row-candidates td
 * @param {object}      row        the workflow row object
 * @param {Function}    reload     called on successful resolution
 */
export function renderSlugConflictPanel(container, row, reload) {
  container.innerHTML = '';

  let detail = null;
  try { detail = row.detail ? JSON.parse(row.detail) : null; } catch {}

  const slug      = detail ? (detail.slug || row.slug || '') : (row.slug || '');
  const ctx       = row.slugConflictContext || null;
  const claimant  = ctx ? ctx.claimant  : null;
  const incumbent = ctx ? ctx.incumbent : null;

  const claimantHtml = claimant
    ? `<a href="/actress/${claimant.id}">${esc(claimant.canonicalName)}</a> <span class="wf-actress-id">#${claimant.id}</span>`
    : detail ? `actress #${detail.claimant_actress_id || '?'}` : '?';

  const incumbentHtml = incumbent
    ? `<a href="/actress/${incumbent.id}">${esc(incumbent.canonicalName)}</a> <span class="er-actress-id">#${incumbent.id}</span>`
    : detail ? `actress #${detail.incumbent_actress_id || '?'}` : '?';

  const sourceCode = detail ? (detail.source_title_code || '') : '';

  const panel = document.createElement('div');
  panel.className = 'wf-slug-conflict-panel';
  panel.innerHTML = `
    <div class="wf-slug-conflict-row">Claimant: ${claimantHtml}</div>
    <div class="wf-slug-conflict-row">Incumbent: ${incumbentHtml}</div>
    ${sourceCode ? `<div class="wf-slug-conflict-row">Source title: <a href="/title/${esc(sourceCode)}">${esc(sourceCode)}</a></div>` : ''}
    <div class="wf-slug-conflict-hint">Slug <b>${esc(slug)}</b> is already owned by the incumbent.
      Review which actress is the real owner, then rename/merge or correct stage_name via the merge tools.</div>
  `;
  container.appendChild(panel);

  const resolveBtn = document.createElement('button');
  resolveBtn.type = 'button';
  resolveBtn.className = 'wf-cast-alias-btn';
  resolveBtn.textContent = 'Mark resolved';
  resolveBtn.addEventListener('click', async () => {
    resolveBtn.disabled = true;
    try {
      await handleResolve(row.queueId, 'marked_resolved', reload);
    } catch (err) {
      console.error('[workflow] slug-conflict resolve failed', err);
      alert(`Mark resolved failed: ${err.message}`);
      resolveBtn.disabled = false;
    }
  });
  container.appendChild(resolveBtn);
}
