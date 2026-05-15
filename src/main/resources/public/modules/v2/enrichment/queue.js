// v2/enrichment/queue.js — pills, table, and per-row rendering.
//
// Exported:
//   renderPills(state, pillsEl, reload)
//   renderTable(state, tableBody, emptyEl, reload)

import { esc, formatRelative, resolverSourceLabel, openLightbox, humanizeEnumLabel } from './utils.js';
import { resolveRow, doForceEnrich, confirmOrphanDelete, startRecodeFlow } from './actions.js';
import { togglePicker } from './picker.js';
import { toggleCastAnomalyPanel } from './cast-anomaly.js';

const ALL_REASONS = [
  'cast_anomaly',
  'ambiguous',
  'no_match',
  'fetch_failed',
  'orphan_enriched',
  'recode_candidate',
  'actress_rename_candidate',
  'slug_conflict',
];

// ── Pills ─────────────────────────────────────────────────────────────────────

export function renderPills(state, pillsEl, reload) {
  pillsEl.innerHTML = '';

  const total = Object.values(state.counts).reduce((a, b) => a + b, 0);
  const allBtn = makePill(`All · ${total}`, state.activeReason === null, () => {
    state.activeReason = null;
    reload();
  });
  pillsEl.appendChild(allBtn);

  ALL_REASONS.forEach(r => {
    const count = state.counts[r] || 0;
    if (count === 0) return; // hide zero-count chips
    const label = `${humanizeEnumLabel(r)} · ${count}`;
    const btn = makePill(label, state.activeReason === r, () => {
      state.activeReason = r;
      reload();
    });
    pillsEl.appendChild(btn);
  });
}

function makePill(label, selected, onClick) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'er-pill' + (selected ? ' er-pill-selected' : '');
  btn.textContent = label;
  btn.addEventListener('click', onClick);
  return btn;
}

// ── Table ─────────────────────────────────────────────────────────────────────

export function renderTable(state, tableBody, emptyEl, reload) {
  if (state.rows.length === 0) {
    tableBody.innerHTML = '';
    const total = Object.values(state.counts).reduce((a, b) => a + b, 0);
    const isFilterMismatch = state.activeReason !== null && total > 0;
    emptyEl.innerHTML = isFilterMismatch
      ? '◌<br>No items match this filter.'
      : '◌<br>Nothing to review.';
    emptyEl.style.display = '';
    return;
  }
  emptyEl.style.display = 'none';
  tableBody.innerHTML = '';
  state.rows.forEach(row => tableBody.appendChild(makeRow(row, tableBody, reload)));
}

// ── Row builder ───────────────────────────────────────────────────────────────

function makeRow(row, tableBody, reload) {
  const tr = document.createElement('tr');
  tr.className = 'er-row';
  tr.dataset.id = row.id;

  const isOrphan        = row.reason === 'orphan_enriched';
  const isAmbiguous     = row.reason === 'ambiguous';
  const isRecode        = row.reason === 'recode_candidate';
  const isActressRename = row.reason === 'actress_rename_candidate';
  const isCastAnomaly   = row.reason === 'cast_anomaly';
  const isSlugConflict  = row.reason === 'slug_conflict';

  let detail = null;
  try { detail = row.detail ? JSON.parse(row.detail) : null; } catch {}

  let actionsHtml;
  let detailHtml = '';

  if (isOrphan) {
    actionsHtml = `
      <button type="button" class="er-action-btn er-orphan-delete-btn" data-id="${row.id}">Confirm delete</button>
      <button type="button" class="er-action-btn er-resolve-btn" data-id="${row.id}" data-res="marked_moved">Mark as moved</button>
    `;
  } else if (isRecode) {
    const orphanCode = detail ? esc(detail.orphan_code    || '') : '';
    const newCode    = detail ? esc(detail.new_folder_code || '') : '';
    const matchType  = detail ? esc(detail.match_type     || '') : '';
    detailHtml = orphanCode
      ? `<div class="er-detail-hint">Orphan: <b>${orphanCode}</b> → New: <b>${newCode}</b> <span class="er-match-type">(${matchType})</span></div>`
      : '';
    actionsHtml = `
      <button type="button" class="er-action-btn er-recode-btn" data-id="${row.id}">Recode to ${newCode}</button>
      <button type="button" class="er-action-btn er-resolve-btn er-dismiss-btn" data-id="${row.id}" data-res="dismissed">Dismiss</button>
    `;
  } else if (isActressRename) {
    const candidateName = detail ? esc(detail.candidate_canonical_name || '') : '';
    const observedName  = detail ? esc(detail.observed_folder_name     || '') : '';
    detailHtml = candidateName
      ? `<div class="er-detail-hint">Existing: <b>${candidateName}</b> → Observed: <b>${observedName}</b></div>`
      : '';
    actionsHtml = `
      <button type="button" class="er-action-btn er-resolve-btn er-dismiss-btn" data-id="${row.id}" data-res="dismissed">Dismiss</button>
    `;
  } else if (isCastAnomaly) {
    actionsHtml = `
      <button type="button" class="er-action-btn er-cast-anomaly-btn" data-id="${row.id}">Add as alias…</button>
      <button type="button" class="er-action-btn er-resolve-btn" data-id="${row.id}" data-res="marked_resolved">Mark resolved</button>
    `;
  } else if (isSlugConflict) {
    const slug      = detail ? esc(detail.slug || '') : esc(row.slug || '');
    const claimant  = row.slugConflictContext ? row.slugConflictContext.claimant  : null;
    const incumbent = row.slugConflictContext ? row.slugConflictContext.incumbent : null;
    const claimantLink  = claimant
      ? `<a href="/actress/${claimant.id}">${esc(claimant.canonicalName)}</a> <span class="er-actress-id">#${claimant.id}</span>`
      : detail ? `actress #${detail.claimant_actress_id || '?'}` : '?';
    const incumbentLink = incumbent
      ? `<a href="/actress/${incumbent.id}">${esc(incumbent.canonicalName)}</a> <span class="er-actress-id">#${incumbent.id}</span>`
      : detail ? `actress #${detail.incumbent_actress_id || '?'}` : '?';
    const sourceCode = detail ? esc(detail.source_title_code || '') : '';
    detailHtml = `
      <div class="er-detail-hint er-slug-conflict-detail">
        <div>Claimant: ${claimantLink}</div>
        <div>Incumbent (current owner): ${incumbentLink}</div>
        ${sourceCode ? `<div>Source title: <a href="/title/${sourceCode}">${sourceCode}</a></div>` : ''}
        <div class="er-slug-conflict-hint">Slug <b>${slug}</b> is already owned by the incumbent.
          To resolve: review which actress is the real owner, then either rename/merge the wrong record
          or correct its stage_name + clear staging via the duplicate-triage / merge tools.</div>
      </div>`;
    actionsHtml = `
      <button type="button" class="er-action-btn er-resolve-btn" data-id="${row.id}" data-res="marked_resolved">Mark resolved</button>
    `;
  } else {
    // no_match, fetch_failed, and any future reasons
    actionsHtml = `
      <button type="button" class="er-action-btn er-gap-btn" data-id="${row.id}" data-res="accepted_gap">Accept as gap</button>
      ${isAmbiguous
        ? `<button type="button" class="er-action-btn er-picker-btn" data-id="${row.id}">Open picker</button>`
        : `<button type="button" class="er-action-btn er-override-btn" data-id="${row.id}">Override slug…</button>`}
      <button type="button" class="er-action-btn er-resolve-btn er-dismiss-btn" data-id="${row.id}" data-res="marked_resolved">Mark resolved</button>
    `;
  }

  const codeCell = row.coverUrl
    ? `<button type="button" class="er-code-cover-btn">${esc(row.titleCode || '')}</button>`
    : esc(row.titleCode || '');

  tr.innerHTML = `
    <td class="er-col-code">${codeCell}${detailHtml}</td>
    <td class="er-col-slug">${esc(row.slug || '—')}</td>
    <td class="er-col-reason"><span class="er-reason er-reason-${esc(row.reason || '')}">${esc(humanizeEnumLabel(row.reason))}</span></td>
    <td class="er-col-source">${esc(resolverSourceLabel(row.resolverSource))}</td>
    <td class="er-col-created">${formatRelative(row.createdAt)}</td>
    <td class="er-col-actions">${actionsHtml}</td>
  `;

  // ── Cover lightbox ──
  if (row.coverUrl) {
    tr.querySelector('.er-code-cover-btn').addEventListener('click', () => openLightbox(row.coverUrl));
  }

  // ── Generic resolve buttons (data-res attribute) ──
  tr.querySelectorAll('.er-action-btn[data-res]').forEach(btn => {
    btn.addEventListener('click', () => resolveRow(Number(btn.dataset.id), btn.dataset.res, tr, reload));
  });

  // ── Reason-specific panel/action buttons ──
  if (isOrphan) {
    tr.querySelector('.er-orphan-delete-btn').addEventListener('click', () =>
      confirmOrphanDelete(row.id, tr, reload));
  } else if (isRecode) {
    tr.querySelector('.er-recode-btn').addEventListener('click', () =>
      startRecodeFlow(row, tr, tableBody, reload));
  } else if (isAmbiguous) {
    tr.querySelector('.er-picker-btn').addEventListener('click', () =>
      togglePicker(row, tr, tableBody, reload));
  } else if (isCastAnomaly) {
    tr.querySelector('.er-cast-anomaly-btn').addEventListener('click', () =>
      toggleCastAnomalyPanel(row, tr, tableBody, reload));
  } else if (!isActressRename) {
    // no_match, fetch_failed, slug_conflict (slug_conflict has no .er-override-btn but the guard is safe)
    const overrideBtn = tr.querySelector('.er-override-btn');
    if (overrideBtn) {
      overrideBtn.addEventListener('click', () => showSlugForm(row.id, tr, reload));
    }
  }

  return tr;
}

// ── Override slug form ────────────────────────────────────────────────────────

function showSlugForm(queueRowId, tr, reload) {
  const actionsCell = tr.querySelector('.er-col-actions');
  if (actionsCell.querySelector('.er-slug-form')) return;

  const form = document.createElement('div');
  form.className = 'er-slug-form';
  form.innerHTML = `
    <input type="text" class="er-slug-input" placeholder="javdb slug" spellcheck="false" />
    <button type="button" class="er-slug-submit">Force enrich</button>
    <button type="button" class="er-slug-cancel">Cancel</button>
  `;
  actionsCell.appendChild(form);

  const input  = form.querySelector('.er-slug-input');
  const submit = form.querySelector('.er-slug-submit');
  const cancel = form.querySelector('.er-slug-cancel');

  input.focus();
  cancel.addEventListener('click', () => form.remove());

  const doSubmit = async () => {
    const slug = input.value.trim();
    if (!slug) { input.focus(); return; }
    await doForceEnrich(queueRowId, slug, tr, reload);
  };

  submit.addEventListener('click', doSubmit);
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter') doSubmit();
    if (e.key === 'Escape') form.remove();
  });
}
