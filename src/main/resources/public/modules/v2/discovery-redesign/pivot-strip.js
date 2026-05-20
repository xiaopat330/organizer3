/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/pivot-strip.js — pivot toggle (Actresses / Titles / Collections).
   Renders three buttons and handles pivot switch + URL update.
   ───────────────────────────────────────────────────────────────────── */

const PIVOTS = [
  { id: 'actresses',   label: 'Actresses' },
  { id: 'titles',      label: 'Titles' },
  { id: 'collections', label: 'Collections' },
];

/**
 * Mount the pivot strip into containerEl.
 *
 * @param {HTMLElement} containerEl
 * @param {{
 *   currentPivot: string,
 *   onPivotChange: (pivot: string) => void,
 * }} opts
 * @returns {{ setPivot(pivot: string): void, destroy(): void }}
 */
export function mountPivotStrip(containerEl, { currentPivot, onPivotChange }) {
  // Insert buttons before the spacer that layout.js placed
  const spacer = containerEl.querySelector('.dr-pivot-strip-spacer');

  const buttons = PIVOTS.map(p => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'dr-pivot-btn';
    btn.dataset.pivot = p.id;
    btn.setAttribute('role', 'tab');
    btn.setAttribute('aria-selected', String(p.id === currentPivot));
    btn.textContent = p.label;
    containerEl.insertBefore(btn, spacer);
    return btn;
  });

  function handleClick(e) {
    const btn = e.target.closest('.dr-pivot-btn');
    if (!btn) return;
    const pivot = btn.dataset.pivot;
    if (!pivot) return;
    setPivot(pivot);
    onPivotChange(pivot);
  }

  containerEl.addEventListener('click', handleClick);

  function setPivot(pivot) {
    buttons.forEach(b => {
      b.setAttribute('aria-selected', String(b.dataset.pivot === pivot));
    });
  }

  return {
    setPivot,
    destroy() {
      containerEl.removeEventListener('click', handleClick);
      buttons.forEach(b => b.remove());
    },
  };
}
