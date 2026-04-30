// Utilities → Enrichment Review Queue triage page.
// Lists open enrichment_review_queue rows with bucket pills for per-reason filtering.
// Ambiguous rows open a visual picker panel with side-by-side candidate cards.

import { esc } from './utils.js';

const view      = document.getElementById('tools-enrichment-review-view');
const headerEl  = document.getElementById('er-header');
const pillsEl   = document.getElementById('er-pills');
const tableBody = document.getElementById('er-table-body');
const emptyEl   = document.getElementById('er-empty');

const ALL_REASONS = ['cast_anomaly', 'ambiguous', 'no_match', 'fetch_failed'];

let state = {
  activeReason: null,   // null = All
  counts: {},
  rows: [],
};

export async function showEnrichmentReviewView() {
  view.style.display = '';
  await reload();
}

export function hideEnrichmentReviewView() {
  view.style.display = 'none';
}

async function reload() {
  headerEl.textContent = 'Enrichment Review Queue — loading…';
  try {
    const params = new URLSearchParams({ limit: 500 });
    if (state.activeReason) params.set('reason', state.activeReason);
    const res = await fetch(`/api/utilities/enrichment-review/queue?${params}`);
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    state.counts = data.counts || {};
    state.rows   = data.rows   || [];
    render();
  } catch (err) {
    headerEl.textContent = 'Enrichment Review Queue — failed to load';
    console.error('EnrichmentReview: load failed', err);
  }
}

function totalOpen() {
  return Object.values(state.counts).reduce((a, b) => a + b, 0);
}

function render() {
  const total = totalOpen();
  headerEl.textContent = `Enrichment Review Queue (${total} open)`;
  renderPills();
  renderTable();
}

function renderPills() {
  pillsEl.innerHTML = '';

  const allBtn = makePill('All', state.activeReason === null, () => {
    state.activeReason = null;
    reload();
  });
  pillsEl.appendChild(allBtn);

  ALL_REASONS.forEach(r => {
    const count = state.counts[r] || 0;
    const btn = makePill(`${r}: ${count}`, state.activeReason === r, () => {
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

function renderTable() {
  if (state.rows.length === 0) {
    tableBody.innerHTML = '';
    emptyEl.style.display = '';
    return;
  }
  emptyEl.style.display = 'none';
  tableBody.innerHTML = '';
  state.rows.forEach(row => tableBody.appendChild(makeRow(row)));
}

function makeRow(row) {
  const tr = document.createElement('tr');
  tr.className = 'er-row';
  const isAmbiguous = row.reason === 'ambiguous';
  tr.innerHTML = `
    <td class="er-col-code">${esc(row.titleCode || '')}</td>
    <td class="er-col-slug">${esc(row.slug || '—')}</td>
    <td class="er-col-reason"><span class="er-reason er-reason-${esc(row.reason || '')}">${esc(row.reason || '')}</span></td>
    <td class="er-col-source">${esc(row.resolverSource || '—')}</td>
    <td class="er-col-created">${formatRelative(row.createdAt)}</td>
    <td class="er-col-actions">
      <button type="button" class="er-action-btn er-gap-btn" data-id="${row.id}" data-res="accepted_gap">Accept as gap</button>
      ${isAmbiguous
        ? `<button type="button" class="er-action-btn er-picker-btn" data-id="${row.id}">Open picker</button>`
        : `<button type="button" class="er-action-btn er-override-btn" data-id="${row.id}">Override slug…</button>`}
      <button type="button" class="er-action-btn er-resolve-btn" data-id="${row.id}" data-res="marked_resolved">Mark resolved</button>
    </td>
  `;
  tr.querySelectorAll('.er-action-btn[data-res]').forEach(btn => {
    btn.addEventListener('click', () => resolveRow(Number(btn.dataset.id), btn.dataset.res, tr));
  });
  if (isAmbiguous) {
    tr.querySelector('.er-picker-btn').addEventListener('click', () => togglePicker(row, tr));
  } else {
    tr.querySelector('.er-override-btn').addEventListener('click', () => showSlugForm(row.id, tr));
  }
  return tr;
}

// ── Picker panel ──────────────────────────────────────────────────────────────

function togglePicker(row, tr) {
  // If a picker row already follows this row, close it.
  const next = tr.nextElementSibling;
  if (next && next.classList.contains('er-picker-row')) {
    next.remove();
    tr.querySelector('.er-picker-btn').classList.remove('er-picker-btn-active');
    return;
  }
  // Close any other open pickers.
  tableBody.querySelectorAll('.er-picker-row').forEach(r => r.remove());
  tableBody.querySelectorAll('.er-picker-btn-active').forEach(b => b.classList.remove('er-picker-btn-active'));

  tr.querySelector('.er-picker-btn').classList.add('er-picker-btn-active');
  const pickerTr = buildPickerRow(row, tr);
  tr.insertAdjacentElement('afterend', pickerTr);
}

function buildPickerRow(row, parentTr) {
  const pickerTr = document.createElement('tr');
  pickerTr.className = 'er-picker-row';
  const td = document.createElement('td');
  td.colSpan = 6;
  pickerTr.appendChild(td);

  const panel = document.createElement('div');
  panel.className = 'er-picker-panel';
  td.appendChild(panel);

  let detail = null;
  try { detail = row.detail ? JSON.parse(row.detail) : null; } catch {}

  if (!detail || !detail.candidates || detail.candidates.length === 0) {
    renderSnapshotMissing(panel, row, parentTr, pickerTr);
  } else {
    renderPickerContent(panel, row, detail, parentTr, pickerTr);
  }

  return pickerTr;
}

function renderSnapshotMissing(panel, row, parentTr, pickerTr) {
  panel.innerHTML = `
    <div class="er-picker-missing">
      <span>Snapshot missing — candidates not yet loaded.</span>
      <button type="button" class="er-picker-load-btn">Load candidates</button>
    </div>
  `;
  panel.querySelector('.er-picker-load-btn').addEventListener('click', async () => {
    await doRefreshCandidates(row, parentTr, pickerTr, panel);
  });
}

function renderPickerContent(panel, row, detail, parentTr, pickerTr) {
  const linkedSlugs = new Set(detail.linked_slugs || []);
  const age = formatRelative(detail.fetched_at);

  panel.innerHTML = '';

  const header = document.createElement('div');
  header.className = 'er-picker-header';
  header.innerHTML = `
    <span class="er-picker-age">Candidates fetched ${esc(age)}</span>
    <button type="button" class="er-picker-refresh-btn">Refresh candidates</button>
  `;
  panel.appendChild(header);
  header.querySelector('.er-picker-refresh-btn').addEventListener('click', async () => {
    await doRefreshCandidates(row, parentTr, pickerTr, panel);
  });

  const cards = document.createElement('div');
  cards.className = 'er-candidate-cards';
  panel.appendChild(cards);

  detail.candidates.forEach(c => {
    cards.appendChild(buildCandidateCard(row, c, linkedSlugs, parentTr));
  });

  const footer = document.createElement('div');
  footer.className = 'er-picker-footer';
  const noneBtn = document.createElement('button');
  noneBtn.type = 'button';
  noneBtn.className = 'er-picker-none-btn';
  noneBtn.textContent = 'None of these (accept as gap)';
  noneBtn.addEventListener('click', () => resolveRow(row.id, 'accepted_gap', parentTr));
  footer.appendChild(noneBtn);
  panel.appendChild(footer);
}

function buildCandidateCard(row, candidate, linkedSlugs, parentTr) {
  const card = document.createElement('div');
  card.className = 'er-candidate-card';

  const cover = document.createElement('div');
  cover.className = 'er-candidate-cover';
  if (candidate.cover_url) {
    const img = document.createElement('img');
    img.src    = candidate.cover_url;
    img.alt    = '';
    img.loading = 'lazy';
    img.className = 'er-candidate-img';
    cover.appendChild(img);
  } else {
    cover.innerHTML = '<div class="er-candidate-no-cover">No cover</div>';
  }
  card.appendChild(cover);

  const info = document.createElement('div');
  info.className = 'er-candidate-info';

  const title = document.createElement('div');
  title.className = 'er-candidate-title';
  title.textContent = candidate.title_original || '(no title)';
  info.appendChild(title);

  const meta = document.createElement('div');
  meta.className = 'er-candidate-meta';
  meta.textContent = [candidate.release_date, candidate.maker].filter(Boolean).join(' · ');
  info.appendChild(meta);

  const castEl = document.createElement('div');
  castEl.className = 'er-candidate-cast';
  (candidate.cast || []).forEach(ce => {
    const span = document.createElement('span');
    span.className = 'er-cast-name'
      + (linkedSlugs.has(ce.slug) ? ' er-cast-linked' : '');
    span.textContent = ce.name || ce.slug || '?';
    castEl.appendChild(span);
  });
  info.appendChild(castEl);

  const pickBtn = document.createElement('button');
  pickBtn.type = 'button';
  pickBtn.className = 'er-pick-btn';
  pickBtn.textContent = 'Pick this';
  pickBtn.addEventListener('click', async () => {
    await doPickCandidate(row.id, candidate.slug, parentTr, pickBtn);
  });
  info.appendChild(pickBtn);

  card.appendChild(info);
  return card;
}

async function doPickCandidate(queueRowId, slug, parentTr, pickBtn) {
  pickBtn.disabled = true;
  pickBtn.textContent = 'Picking…';
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${queueRowId}/pick`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
    });
    const data = await res.json();
    if (!res.ok || !data.ok) {
      alert('Pick failed: ' + (data.error || data.message || res.statusText));
      pickBtn.disabled = false;
      pickBtn.textContent = 'Pick this';
    } else {
      await reload();
    }
  } catch (err) {
    console.error('EnrichmentReview: pick failed', err);
    alert('Pick failed: ' + err.message);
    pickBtn.disabled = false;
    pickBtn.textContent = 'Pick this';
  }
}

async function doRefreshCandidates(row, parentTr, pickerTr, panel) {
  const btn = panel.querySelector('.er-picker-refresh-btn, .er-picker-load-btn');
  if (btn) { btn.disabled = true; btn.textContent = 'Loading…'; }
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${row.id}/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    });
    const data = await res.json();
    if (!res.ok || !data.ok) {
      alert('Refresh failed: ' + (data.error || data.message || res.statusText));
      if (btn) { btn.disabled = false; btn.textContent = 'Refresh candidates'; }
      return;
    }
    // Update the row's detail in state and re-render the picker
    const updatedRow = state.rows.find(r => r.id === row.id);
    if (updatedRow) updatedRow.detail = data.detailJson;
    row.detail = data.detailJson;

    let freshDetail = null;
    try { freshDetail = data.detailJson ? JSON.parse(data.detailJson) : null; } catch {}
    panel.innerHTML = '';
    if (!freshDetail || !freshDetail.candidates || freshDetail.candidates.length === 0) {
      renderSnapshotMissing(panel, row, parentTr, pickerTr);
    } else {
      renderPickerContent(panel, row, freshDetail, parentTr, pickerTr);
    }
  } catch (err) {
    console.error('EnrichmentReview: refresh failed', err);
    alert('Refresh failed: ' + err.message);
    if (btn) { btn.disabled = false; btn.textContent = 'Refresh candidates'; }
  }
}

// ── Override slug form (non-ambiguous rows) ────────────────────────────────────

function showSlugForm(queueRowId, tr) {
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
    tr.querySelectorAll('.er-action-btn, .er-slug-submit, .er-slug-cancel').forEach(b => { b.disabled = true; });
    try {
      const res = await fetch(`/api/utilities/enrichment-review/queue/${queueRowId}/force-enrich`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ slug }),
      });
      const data = await res.json();
      if (!res.ok || !data.ok) {
        alert('Force enrich failed: ' + (data.error || data.message || res.statusText));
        tr.querySelectorAll('.er-action-btn, .er-slug-submit, .er-slug-cancel').forEach(b => { b.disabled = false; });
      } else {
        await reload();
      }
    } catch (err) {
      console.error('EnrichmentReview: force enrich failed', err);
      alert('Force enrich failed: ' + err.message);
      tr.querySelectorAll('.er-action-btn, .er-slug-submit, .er-slug-cancel').forEach(b => { b.disabled = false; });
    }
  };

  submit.addEventListener('click', doSubmit);
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter') doSubmit();
    if (e.key === 'Escape') form.remove();
  });
}

// ── Resolve ───────────────────────────────────────────────────────────────────

async function resolveRow(id, resolution, tr) {
  tr.querySelectorAll('.er-action-btn').forEach(b => { b.disabled = true; });
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${id}/resolve`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolution }),
    });
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    if (data.ok) {
      await reload();
    } else {
      alert(data.message);
      tr.querySelectorAll('.er-action-btn').forEach(b => { b.disabled = false; });
    }
  } catch (err) {
    console.error('EnrichmentReview: resolve failed', err);
    alert('Failed to resolve row: ' + err.message);
    tr.querySelectorAll('.er-action-btn').forEach(b => { b.disabled = false; });
  }
}

// ── Utilities ─────────────────────────────────────────────────────────────────

function formatRelative(isoStr) {
  if (!isoStr) return '—';
  try {
    const diff = Date.now() - new Date(isoStr).getTime();
    const days = Math.floor(diff / 86400000);
    if (days === 0) return 'Today';
    if (days === 1) return 'Yesterday';
    if (days < 30)  return `${days}d ago`;
    if (days < 365) return `${Math.floor(days / 30)}mo ago`;
    return `${Math.floor(days / 365)}y ago`;
  } catch { return isoStr; }
}
