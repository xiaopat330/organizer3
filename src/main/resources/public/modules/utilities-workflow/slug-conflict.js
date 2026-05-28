// utilities-workflow/slug-conflict.js — inline detail panel for slug_conflict rows.
// Forked from modules/v2/workflow/slug-conflict.js; reskinned to .wf1-* classes.
//
// v1 rewire: v2 used <a href="/actress/{id}"> and <a href="/title/{code}">, which
// have no v1 routes. Here we render .wf1-link spans and wire in-app navigation:
//   actress → openActressById (actress-detail overlay)
//   title   → openTitleByCode  (title-detail overlay)

import { esc, openActressById, openTitleByCode } from './utils.js';
import { handleResolve } from './actions.js';

export function renderSlugConflictPanel(container, row, reload) {
  container.innerHTML = '';

  let detail = null;
  try { detail = row.detail ? JSON.parse(row.detail) : null; } catch {}

  const slug      = detail ? (detail.slug || row.slug || '') : (row.slug || '');
  const ctx       = row.slugConflictContext || null;
  const claimant  = ctx ? ctx.claimant  : null;
  const incumbent = ctx ? ctx.incumbent : null;

  const claimantHtml = claimant
    ? `<span class="wf1-link" data-actress-id="${claimant.id}">${esc(claimant.canonicalName)}</span> <span class="wf1-actress-id">#${claimant.id}</span>`
    : detail ? `actress #${esc(detail.claimant_actress_id || '?')}` : '?';

  const incumbentHtml = incumbent
    ? `<span class="wf1-link" data-actress-id="${incumbent.id}">${esc(incumbent.canonicalName)}</span> <span class="wf1-actress-id">#${incumbent.id}</span>`
    : detail ? `actress #${esc(detail.incumbent_actress_id || '?')}` : '?';

  const sourceCode = detail ? (detail.source_title_code || '') : '';

  const panel = document.createElement('div');
  panel.className = 'wf1-slug-conflict-panel';
  panel.innerHTML = `
    <div class="wf1-slug-conflict-row">Claimant: ${claimantHtml}</div>
    <div class="wf1-slug-conflict-row">Incumbent: ${incumbentHtml}</div>
    ${sourceCode ? `<div class="wf1-slug-conflict-row">Source title: <span class="wf1-link" data-title-code="${esc(sourceCode)}">${esc(sourceCode)}</span></div>` : ''}
    <div class="wf1-slug-conflict-hint">Slug <b>${esc(slug)}</b> is already owned by the incumbent.
      Review which actress is the real owner, then rename/merge or correct stage_name via the merge tools.</div>
  `;
  container.appendChild(panel);

  // Wire in-app navigation for the rendered links.
  panel.querySelectorAll('.wf1-link[data-actress-id]').forEach(el => {
    el.addEventListener('click', () => openActressById(el.dataset.actressId));
  });
  panel.querySelectorAll('.wf1-link[data-title-code]').forEach(el => {
    el.addEventListener('click', () => openTitleByCode(el.dataset.titleCode));
  });

  const resolveBtn = document.createElement('button');
  resolveBtn.type = 'button';
  resolveBtn.className = 'wf1-cast-alias-btn';
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
