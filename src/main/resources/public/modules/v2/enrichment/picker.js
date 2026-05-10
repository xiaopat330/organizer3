// v2/enrichment/picker.js — ambiguous picker panel (side-by-side candidate cards).
//
// Exported:
//   togglePicker(row, tr, tableBody, reload) — open/close the picker panel for a row

import { esc, formatRelative, openLightbox } from './utils.js';
import { doPickCandidate, doRefreshCandidates } from './actions.js';

// ── Public API ────────────────────────────────────────────────────────────────

export function togglePicker(row, tr, tableBody, reload) {
  const next = tr.nextElementSibling;
  if (next && next.classList.contains('er-picker-row')) {
    next.remove();
    tr.querySelector('.er-picker-btn').classList.remove('er-picker-btn-active');
    return;
  }
  // Close any other open panels.
  tableBody.querySelectorAll('.er-picker-row').forEach(r => r.remove());
  tableBody.querySelectorAll('.er-cast-anomaly-row').forEach(r => r.remove());
  tableBody.querySelectorAll('.er-picker-btn-active').forEach(b => b.classList.remove('er-picker-btn-active'));

  tr.querySelector('.er-picker-btn').classList.add('er-picker-btn-active');
  const pickerTr = buildPickerRow(row, tr, reload);
  tr.insertAdjacentElement('afterend', pickerTr);
}

// ── Private builders ──────────────────────────────────────────────────────────

function buildPickerRow(row, parentTr, reload) {
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

  renderPanel(panel, row, detail, parentTr, reload);
  return pickerTr;
}

// Central renderer — used by the initial build AND by refresh.
function renderPanel(panel, row, detail, parentTr, reload) {
  panel.innerHTML = '';
  if (!detail || !detail.candidates || detail.candidates.length === 0) {
    renderSnapshotMissing(panel, row, parentTr, reload);
  } else {
    renderPickerContent(panel, row, detail, parentTr, reload);
  }
}

function renderSnapshotMissing(panel, row, parentTr, reload) {
  panel.innerHTML = `
    <div class="er-picker-missing">
      <span>Snapshot missing — candidates not yet loaded.</span>
      <button type="button" class="er-picker-load-btn">Load candidates</button>
    </div>
  `;
  panel.querySelector('.er-picker-load-btn').addEventListener('click', async () => {
    await doRefreshCandidates(row, panel, (p, r, freshDetail) =>
      renderPanel(p, r, freshDetail, parentTr, reload));
  });
}

function renderPickerContent(panel, row, detail, parentTr, reload) {
  const linkedSlugs = new Set(detail.linked_slugs || []);
  const age = formatRelative(detail.fetched_at);

  const header = document.createElement('div');
  header.className = 'er-picker-header';
  header.innerHTML = `
    <span class="er-picker-age">Candidates fetched ${esc(age)}</span>
    <button type="button" class="er-picker-refresh-btn">Refresh candidates</button>
  `;
  panel.appendChild(header);
  header.querySelector('.er-picker-refresh-btn').addEventListener('click', async () => {
    await doRefreshCandidates(row, panel, (p, r, freshDetail) =>
      renderPanel(p, r, freshDetail, parentTr, reload));
  });

  const cards = document.createElement('div');
  cards.className = 'er-candidate-cards';
  panel.appendChild(cards);

  if (row.coverUrl) {
    cards.appendChild(buildReferenceCard(row.coverUrl));
  }

  detail.candidates.forEach(c => {
    cards.appendChild(buildCandidateCard(row, c, linkedSlugs, reload));
  });

  const footer = document.createElement('div');
  footer.className = 'er-picker-footer';
  const noneBtn = document.createElement('button');
  noneBtn.type = 'button';
  noneBtn.className = 'er-picker-none-btn';
  noneBtn.textContent = 'None of these (accept as gap)';
  noneBtn.addEventListener('click', async () => {
    // Resolve as accepted_gap via resolveRow — import lazily to avoid circular.
    const { resolveRow } = await import('./actions.js');
    await resolveRow(row.id, 'accepted_gap', parentTr, reload);
  });
  footer.appendChild(noneBtn);
  panel.appendChild(footer);
}

function buildReferenceCard(coverUrl) {
  const card = document.createElement('div');
  card.className = 'er-candidate-card er-reference-card';

  const cover = document.createElement('div');
  cover.className = 'er-candidate-cover';
  cover.style.cursor = 'zoom-in';
  const img = document.createElement('img');
  img.src = coverUrl;
  img.alt = '';
  img.loading = 'lazy';
  img.className = 'er-candidate-img';
  cover.appendChild(img);
  cover.addEventListener('click', () => openLightbox(coverUrl));
  card.appendChild(cover);

  const info = document.createElement('div');
  info.className = 'er-candidate-info';

  const title = document.createElement('div');
  title.className = 'er-candidate-title';
  title.textContent = 'Local cover';
  info.appendChild(title);

  const meta = document.createElement('div');
  meta.className = 'er-candidate-meta';
  meta.textContent = 'Match candidates against this';
  info.appendChild(meta);

  card.appendChild(info);
  return card;
}

function buildCandidateCard(row, candidate, linkedSlugs, reload) {
  const card = document.createElement('div');
  card.className = 'er-candidate-card';

  const cover = document.createElement('div');
  cover.className = 'er-candidate-cover';
  if (candidate.cover_url) {
    const img = document.createElement('img');
    img.src = candidate.cover_url;
    img.alt = '';
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
    span.className = 'er-cast-name' + (linkedSlugs.has(ce.slug) ? ' er-cast-linked' : '');
    span.textContent = ce.name || ce.slug || '?';
    castEl.appendChild(span);
  });
  info.appendChild(castEl);

  const pickBtn = document.createElement('button');
  pickBtn.type = 'button';
  pickBtn.className = 'er-pick-btn';
  pickBtn.textContent = 'Pick this';
  pickBtn.addEventListener('click', async () => {
    await doPickCandidate(row.id, candidate.slug, pickBtn, reload);
  });
  info.appendChild(pickBtn);

  card.appendChild(info);
  return card;
}
