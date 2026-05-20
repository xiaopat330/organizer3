/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/pivots/shared.js — Shared helpers for pivot modules.

   Re-exports utilities from legacy discovery/shared.js (read-only) and
   provides Discovery Workbench–specific helpers:
     - showCoverLightbox()   — cover lightbox (stays a true modal; B5)
     - fetchTitlePeek()      — fetches /api/titles/by-code and returns data
                               (caller renders into inspector body, not modal)
     - buildTitlePeekHtml()  — renders title-peek content as HTML string
   ───────────────────────────────────────────────────────────────────── */

import { esc } from '../../../utils.js';
import {
  fmtDate,
  parseCast,
  attachFilterHandlers,
  attachPagerHandlers,
  renderPagerInto,
} from '../../discovery/shared.js';

// Re-export helpers legacy pivot consumers need.
export { fmtDate, parseCast, attachFilterHandlers, attachPagerHandlers, renderPagerInto };

// ── Cover lightbox — stays a true modal (B5) ──────────────────────────────

/**
 * Show the cover lightbox. This is the only true modal in the redesign.
 * Has its own AbortController so ESC closes it without bubbling to
 * the page-level ESC handler.
 *
 * @param {string} coverUrl
 * @param {string} code
 */
export function showCoverLightbox(coverUrl, code) {
  // Remove any existing lightbox first.
  document.querySelector('.dr-cover-overlay')?.remove();

  const overlay = document.createElement('div');
  overlay.className = 'dr-cover-overlay';

  const box = document.createElement('div');
  box.className = 'dr-cover-modal-box';

  const img = document.createElement('img');
  img.className = 'dr-cover-modal-img';
  img.src = coverUrl;
  img.alt = code;

  const label = document.createElement('div');
  label.className = 'dr-cover-modal-label';
  label.textContent = code;

  box.appendChild(img);
  box.appendChild(label);
  overlay.appendChild(box);
  document.body.appendChild(overlay);

  const ac = new AbortController();
  const close = () => { overlay.remove(); ac.abort(); };

  overlay.addEventListener('click', e => { if (e.target === overlay) close(); }, { signal: ac.signal });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') { e.stopPropagation(); close(); }
  }, { signal: ac.signal, capture: true });
}

// ── Title peek — rendered into inspector body (not modal) ────────────────

/**
 * Fetch title data for a code. Returns null on failure.
 * @param {string} code
 * @returns {Promise<object|null>}
 */
export async function fetchTitlePeek(code) {
  try {
    const res = await fetch(`/api/titles/by-code/${encodeURIComponent(code)}`);
    if (res.ok) return await res.json();
  } catch (_) { /* fall through */ }
  return null;
}

/**
 * Build the HTML string for title-peek content in the inspector.
 * @param {object} t — title data (may be partial if fetch failed)
 * @param {string} [code] — code to show if t is null/partial
 * @returns {string}
 */
export function buildTitlePeekHtml(t, code) {
  if (!t) t = { code: code || '?' };

  const cast = (t.actresses && t.actresses.length > 0)
    ? t.actresses
    : (t.actressName ? [{ id: t.actressId, name: t.actressName, tier: t.actressTier }] : []);
  const castHtml = cast.length === 0
    ? '<span class="dr-peek-empty">—</span>'
    : cast.map(a => `<span class="dr-peek-cast-chip">${esc(a.name || '')}</span>`).join('');

  const labelText = [t.companyName, t.labelName].filter(Boolean).join(' · ');
  const dateLabel = t.releaseDate ? 'Released' : (t.addedDate ? 'Added' : null);
  const dateValue = t.releaseDate || t.addedDate;

  let gradeHtml = '';
  if (t.grade) {
    gradeHtml = `<span class="dr-peek-grade tier-${esc(String(t.grade))}">${esc(String(t.grade))}</span>`;
    if (t.ratingAvg != null && t.ratingCount != null) {
      gradeHtml += `<span class="dr-peek-rating">${t.ratingAvg.toFixed(2)} · ${t.ratingCount.toLocaleString()} votes</span>`;
    }
  }

  const tags = (t.tags || []).filter(Boolean);
  const tagsHtml = tags.length === 0
    ? '<span class="dr-peek-empty">—</span>'
    : tags.map(tag => `<span class="dr-peek-tag">${esc(tag)}</span>`).join('');

  const nas = t.nasPaths || [];
  const nasHtml = nas.length === 0
    ? '<span class="dr-peek-empty">—</span>'
    : nas.map(p => `<span class="dr-peek-nas">${esc(p)}</span>`).join('');

  const coverHtml = t.coverUrl
    ? `<div class="dr-peek-cover-wrap">
         <img class="dr-peek-cover dr-peek-cover-zoom"
              src="${esc(t.coverUrl)}"
              data-cover-url="${esc(t.coverUrl)}"
              data-code="${esc(t.code || '')}"
              alt="${esc(t.code || '')}">
       </div>`
    : '';

  const titleJaHtml = t.titleOriginal ? `<div class="dr-peek-title-ja">${esc(t.titleOriginal)}</div>` : '';
  const titleEnHtml = t.titleEnglish  ? `<div class="dr-peek-title-en">${esc(t.titleEnglish)}</div>`  : '';

  return `
    <div class="dr-peek-content">
      ${coverHtml}
      <div class="dr-peek-code">${esc(t.code || '?')}</div>
      ${titleJaHtml}
      ${titleEnHtml}
      <div class="dr-peek-rows">
        <div class="dr-peek-row"><span class="dr-peek-row-label">Cast</span><span class="dr-peek-row-value">${castHtml}</span></div>
        ${labelText ? `<div class="dr-peek-row"><span class="dr-peek-row-label">Label</span><span class="dr-peek-row-value">${esc(labelText)}</span></div>` : ''}
        ${dateLabel ? `<div class="dr-peek-row"><span class="dr-peek-row-label">${dateLabel}</span><span class="dr-peek-row-value">${esc(fmtDate(dateValue))}</span></div>` : ''}
        ${gradeHtml ? `<div class="dr-peek-row"><span class="dr-peek-row-label">Grade</span><span class="dr-peek-row-value">${gradeHtml}</span></div>` : ''}
        <div class="dr-peek-row"><span class="dr-peek-row-label">Tags</span><span class="dr-peek-row-value">${tagsHtml}</span></div>
        <div class="dr-peek-row"><span class="dr-peek-row-label">Location</span><span class="dr-peek-row-value">${nasHtml}</span></div>
      </div>
    </div>
  `;
}
