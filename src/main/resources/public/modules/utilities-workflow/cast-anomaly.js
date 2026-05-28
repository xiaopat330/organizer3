// utilities-workflow/cast-anomaly.js — inline cast-pairing panel for cast_anomaly rows.
// Forked from modules/v2/workflow/cast-anomaly.js; reskinned to .wf1-* classes.

import { esc } from './utils.js';
import { handleAddAlias, handleResolve } from './actions.js';

export function renderCastAnomalyPanel(container, row, reload) {
  container.innerHTML = '';

  let castEntries = [];
  if (row.castJson) {
    try { castEntries = JSON.parse(row.castJson) || []; } catch {}
  }

  const linkedActresses = row.linkedActresses || [];

  if (castEntries.length === 0) {
    container.innerHTML = `<span class="wf1-cast-note">No cast JSON — cannot triage inline.</span>`;
    appendResolveButton(container, row, reload);
    return;
  }

  if (linkedActresses.length === 0) {
    container.innerHTML = `<span class="wf1-cast-note">No linked actresses — nothing to alias.</span>`;
    appendResolveButton(container, row, reload);
    return;
  }

  const errorEl = document.createElement('div');
  errorEl.className = 'wf1-cast-error';
  errorEl.style.display = 'none';
  container.appendChild(errorEl);

  const pairings = document.createElement('div');
  pairings.className = 'wf1-cast-pairings';
  container.appendChild(pairings);

  castEntries.forEach(ce => {
    const castName = ce.name || ce.slug || '?';
    const castSlug = ce.slug || '';

    const block = document.createElement('div');
    block.className = 'wf1-cast-block';

    const label = document.createElement('div');
    label.className = 'wf1-cast-label';
    label.innerHTML = `javdb: <b>${esc(castName)}</b>${castSlug
      ? ` <span class="wf1-cast-slug">(${esc(castSlug)})</span>`
      : ''}`;
    block.appendChild(label);

    linkedActresses.forEach(actress => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'wf1-cast-alias-btn';
      btn.textContent = `Add as alias for ${actress.canonicalName}`;
      btn.addEventListener('click', () =>
        handleAddAlias(row.queueId, actress.id, castName, btn, errorEl, reload)
      );
      block.appendChild(btn);
    });

    pairings.appendChild(block);
  });

  appendResolveButton(container, row, reload, errorEl);
}

function appendResolveButton(container, row, reload, errorEl) {
  const resolveBtn = document.createElement('button');
  resolveBtn.type = 'button';
  resolveBtn.className = 'wf1-cast-alias-btn wf1-cast-resolve-btn';
  resolveBtn.textContent = 'Mark resolved';
  resolveBtn.addEventListener('click', async () => {
    resolveBtn.disabled = true;
    try {
      await handleResolve(row.queueId, 'marked_resolved', reload);
    } catch (err) {
      if (errorEl) {
        errorEl.textContent = `Mark resolved failed: ${err.message}`;
        errorEl.style.display = '';
      }
      resolveBtn.disabled = false;
    }
  });
  container.appendChild(resolveBtn);
}
