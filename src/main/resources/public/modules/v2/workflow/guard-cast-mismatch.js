// v2/workflow/guard-cast-mismatch.js — inline detail panel for guard_cast_mismatch rows.
//
// The review-queue row carries reason='guard_cast_mismatch' and a detail JSON string:
//   { actressId, actressName, stageName, resolvedVia, nfem, castNames: [...] }
//
// The triager fixes the underlying data externally (reassign_title_credit, add alias,
// etc.) then marks the row resolved here. No inline "approve bind" action.

import { esc } from './utils.js';
import { handleResolve } from './actions.js';

/**
 * Renders the guard-cast-mismatch inline panel into the given container element.
 *
 * @param {HTMLElement} container  the wf-row-candidates td
 * @param {object}      row        the workflow row object
 * @param {Function}    reload     called after successful mark-resolved
 */
export function renderGuardCastMismatchPanel(container, row, reload) {
  container.innerHTML = '';

  let detail = null;
  try { detail = row.detail ? JSON.parse(row.detail) : null; } catch {}

  const panel = document.createElement('div');
  panel.className = 'wf-guard-mismatch-panel';

  if (!detail) {
    panel.innerHTML = `<span class="wf-cast-note">No detail available.</span>`;
    container.appendChild(panel);
    appendResolveButton(container, row, reload);
    return;
  }

  const actressName = detail.actressName || '';
  const stageName   = detail.stageName   || '';
  const resolvedVia = detail.resolvedVia || '';
  const nfem        = detail.nfem != null ? String(detail.nfem) : '?';
  const castNames   = Array.isArray(detail.castNames) ? detail.castNames : [];

  const castChips = castNames.length > 0
    ? castNames.map(n => `<span class="wf-guard-cast-chip">${esc(n)}</span>`).join(' ')
    : '<span class="wf-cast-note">none listed</span>';

  panel.innerHTML = `
    <div class="wf-guard-mismatch-label">Cast mismatch (guard)</div>
    <div class="wf-guard-mismatch-row">
      <span class="wf-guard-field">Actress:</span>
      <b>${esc(actressName)}</b>${stageName ? ` <span class="wf-guard-stage">${esc(stageName)}</span>` : ''}
    </div>
    <div class="wf-guard-mismatch-row">
      <span class="wf-guard-field">Resolved via:</span>
      <span class="wf-guard-via">${esc(resolvedVia)}</span>
    </div>
    <div class="wf-guard-mismatch-row">
      <span class="wf-guard-field">javdb female cast (${esc(nfem)}):</span>
    </div>
    <div class="wf-guard-cast-list">${castChips}</div>
    <div class="wf-guard-mismatch-hint">
      Fix externally (reassign credit, add alias, etc.), then mark resolved.
    </div>
  `;
  container.appendChild(panel);

  appendResolveButton(container, row, reload);
}

function appendResolveButton(container, row, reload) {
  const errorEl = document.createElement('div');
  errorEl.className = 'wf-cast-error';
  errorEl.style.display = 'none';
  container.appendChild(errorEl);

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'wf-cast-alias-btn wf-cast-resolve-btn';
  btn.textContent = 'Mark resolved';
  btn.addEventListener('click', async () => {
    btn.disabled = true;
    try {
      await handleResolve(row.queueId, 'marked_resolved', reload);
    } catch (err) {
      errorEl.textContent = `Mark resolved failed: ${err.message}`;
      errorEl.style.display = '';
      btn.disabled = false;
    }
  });
  container.appendChild(btn);
}
