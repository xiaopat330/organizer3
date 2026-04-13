// ── Shared column-count slider ────────────────────────────────────────────
// Used by both actress-browse.js and title-browse.js.  Both grids share the
// same value set and localStorage key so the chosen column count is preserved
// across mode switches.

import { THUMBNAIL_COLUMNS } from './config.js';

export const COLS_VALUES = [4, 5, 6, 8, 10, 12];
export const STORAGE_KEY = 'title-grid-cols';

/** Returns the currently saved col count, falling back to the closest value to THUMBNAIL_COLUMNS. */
export function effectiveCols() {
  const saved = parseInt(localStorage.getItem(STORAGE_KEY), 10);
  if (COLS_VALUES.includes(saved)) return saved;
  return COLS_VALUES.reduce((a, b) =>
    Math.abs(b - THUMBNAIL_COLUMNS) < Math.abs(a - THUMBNAIL_COLUMNS) ? b : a
  );
}

/** Renders the slider control HTML. IDs are caller-supplied so actress and title grids don't collide. */
export function colsSliderHtml(cols, controlId, sliderId, labelId) {
  const idx = COLS_VALUES.indexOf(cols);
  return `<div class="title-cols-control" id="${controlId}">
    <input type="range" class="title-cols-slider" id="${sliderId}"
      min="0" max="${COLS_VALUES.length - 1}" step="1" value="${idx >= 0 ? idx : 2}">
    <span class="title-cols-label" id="${labelId}">${cols}</span>
  </div>`;
}

/** Attaches the input listener to an already-rendered slider. applyFn receives the new column count. */
export function wireColsSlider(sliderId, labelId, applyFn) {
  const slider = document.getElementById(sliderId);
  const label  = document.getElementById(labelId);
  if (!slider) return;
  slider.addEventListener('input', () => {
    const cols = COLS_VALUES[parseInt(slider.value, 10)];
    if (label) label.textContent = cols;
    applyFn(cols);
    localStorage.setItem(STORAGE_KEY, cols);
  });
}

/**
 * Injects the slider into containerEl (removing any previous instance first),
 * then wires it up.  Used by actress-browse for modes that share a filter row.
 */
export function injectColsSlider(containerEl, controlId, sliderId, labelId, applyFn) {
  containerEl.querySelector(`#${controlId}`)?.remove();
  containerEl.insertAdjacentHTML('beforeend', colsSliderHtml(effectiveCols(), controlId, sliderId, labelId));
  wireColsSlider(sliderId, labelId, applyFn);
}
