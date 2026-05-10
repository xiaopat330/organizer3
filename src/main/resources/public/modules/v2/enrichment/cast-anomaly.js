// v2/enrichment/cast-anomaly.js — cast_anomaly inline triage panel.
//
// Exported:
//   toggleCastAnomalyPanel(row, tr, tableBody, reload)

import { esc } from './utils.js';
import { doAddAlias } from './actions.js';

export function toggleCastAnomalyPanel(row, tr, tableBody, reload) {
  const next = tr.nextElementSibling;
  if (next && next.classList.contains('er-cast-anomaly-row')) {
    next.remove();
    tr.querySelector('.er-cast-anomaly-btn').classList.remove('er-picker-btn-active');
    return;
  }
  // Close any other open panels.
  tableBody.querySelectorAll('.er-cast-anomaly-row').forEach(r => r.remove());
  tableBody.querySelectorAll('.er-picker-row').forEach(r => r.remove());
  tableBody.querySelectorAll('.er-picker-btn-active').forEach(b => b.classList.remove('er-picker-btn-active'));

  tr.querySelector('.er-cast-anomaly-btn').classList.add('er-picker-btn-active');
  const panelTr = buildCastAnomalyPanelRow(row, tr, reload);
  tr.insertAdjacentElement('afterend', panelTr);
}

function buildCastAnomalyPanelRow(row, parentTr, reload) {
  const panelTr = document.createElement('tr');
  panelTr.className = 'er-cast-anomaly-row';
  const td = document.createElement('td');
  td.colSpan = 6;
  panelTr.appendChild(td);

  const panel = document.createElement('div');
  panel.className = 'er-picker-panel';
  td.appendChild(panel);

  renderCastAnomalyContent(panel, row, reload);
  return panelTr;
}

function renderCastAnomalyContent(panel, row, reload) {
  panel.innerHTML = '';

  let castEntries = [];
  if (row.castJson) {
    try { castEntries = JSON.parse(row.castJson) || []; } catch {}
  }

  const linkedActresses = row.linkedActresses || [];

  if (castEntries.length === 0) {
    panel.innerHTML = `
      <div class="er-picker-missing">
        <span>No cast JSON available for this title — cast_anomaly cannot be triaged inline.</span>
      </div>
    `;
    return;
  }

  if (linkedActresses.length === 0) {
    panel.innerHTML = `
      <div class="er-picker-missing">
        <span>No actresses are linked to this title — nothing to create an alias for.</span>
      </div>
    `;
    return;
  }

  const header = document.createElement('div');
  header.className = 'er-picker-header';
  header.innerHTML = `
    <span class="er-picker-age">
      javdb cast names not matched by the alias matcher — pick the correct actress for each.
    </span>
  `;
  panel.appendChild(header);

  const errorEl = document.createElement('div');
  errorEl.className = 'er-cast-anomaly-error';
  errorEl.style.display = 'none';
  panel.appendChild(errorEl);

  const pairings = document.createElement('div');
  pairings.className = 'er-cast-anomaly-pairings';
  panel.appendChild(pairings);

  castEntries.forEach(ce => {
    const castName = ce.name || ce.slug || '?';
    const castSlug = ce.slug || '';

    const castBlock = document.createElement('div');
    castBlock.className = 'er-cast-anomaly-cast-block';

    const castLabel = document.createElement('div');
    castLabel.className = 'er-cast-anomaly-cast-label';
    castLabel.innerHTML = `javdb cast: <b>${esc(castName)}</b>${castSlug
      ? ` <span class="er-cast-slug">(slug ${esc(castSlug)})</span>`
      : ''}`;
    castBlock.appendChild(castLabel);

    const actressList = document.createElement('div');
    actressList.className = 'er-cast-anomaly-actress-list';

    linkedActresses.forEach(actress => {
      const item = document.createElement('div');
      item.className = 'er-cast-anomaly-actress-item';

      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'er-action-btn er-cast-alias-btn';
      btn.textContent = `Add "${castName}" as alias for ${actress.canonicalName}`;
      btn.addEventListener('click', async () => {
        await doAddAlias(row.id, actress.id, castName, btn, errorEl, reload);
      });
      item.appendChild(btn);
      actressList.appendChild(item);
    });

    castBlock.appendChild(actressList);
    pairings.appendChild(castBlock);
  });
}
