// v2/workflow/cast-anomaly.js — inline cast-pairing panel for cast_anomaly rows.
//
// The workflow DTO includes castJson (raw JSON string from title_javdb_enrichment)
// and linkedActresses ([{id, canonicalName}]) for cast_anomaly rows. These are
// populated by WorkflowRoutes.queryWorkflowRows when reason === 'cast_anomaly'.

import { esc } from './utils.js';
import { handleAddAlias } from './actions.js';

/**
 * Renders the cast-anomaly inline panel into the given container element.
 * The container replaces the standard candidate-thumbs cell content.
 *
 * @param {HTMLElement} container  the wf-row-candidates td (or a div inside it)
 * @param {object}      row        the workflow row object
 * @param {Function}    reload     called on successful alias addition
 */
export function renderCastAnomalyPanel(container, row, reload) {
  container.innerHTML = '';

  let castEntries = [];
  if (row.castJson) {
    try { castEntries = JSON.parse(row.castJson) || []; } catch {}
  }

  const linkedActresses = row.linkedActresses || [];

  if (castEntries.length === 0) {
    container.innerHTML = `<span class="wf-cast-note">No cast JSON — cannot triage inline.</span>`;
    return;
  }

  if (linkedActresses.length === 0) {
    container.innerHTML = `<span class="wf-cast-note">No linked actresses — nothing to alias.</span>`;
    return;
  }

  const errorEl = document.createElement('div');
  errorEl.className = 'wf-cast-error';
  errorEl.style.display = 'none';
  container.appendChild(errorEl);

  const pairings = document.createElement('div');
  pairings.className = 'wf-cast-pairings';
  container.appendChild(pairings);

  castEntries.forEach(ce => {
    const castName = ce.name || ce.slug || '?';
    const castSlug = ce.slug || '';

    const block = document.createElement('div');
    block.className = 'wf-cast-block';

    const label = document.createElement('div');
    label.className = 'wf-cast-label';
    label.innerHTML = `javdb: <b>${esc(castName)}</b>${castSlug
      ? ` <span class="wf-cast-slug">(${esc(castSlug)})</span>`
      : ''}`;
    block.appendChild(label);

    linkedActresses.forEach(actress => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'wf-cast-alias-btn';
      btn.textContent = `Add as alias for ${actress.canonicalName}`;
      btn.addEventListener('click', () =>
        handleAddAlias(row.queueId, actress.id, castName, btn, errorEl, reload)
      );
      block.appendChild(btn);
    });

    pairings.appendChild(block);
  });
}
