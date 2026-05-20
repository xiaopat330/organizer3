/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/inspector.js — inspector panel shell.

   Phase A: renders empty-state ("Select a row to view details") and
   close button. Resize handle is handled by index.js (needs access to
   the main-area element for the CSS custom property).
   Phase B: onSelect callback drives real content rendering.
   ───────────────────────────────────────────────────────────────────── */

/**
 * Mount the inspector shell into containerEl.
 *
 * @param {HTMLElement} containerEl
 * @param {{ onClose: () => void }} opts
 * @returns {{ showEmpty(): void, destroy(): void }}
 */
export function mountInspector(containerEl, { onClose }) {
  containerEl.innerHTML = `
    <div class="dr-inspector-header">
      <span class="dr-inspector-title">Inspector</span>
      <button class="dr-inspector-close" id="dr-inspector-close" type="button" aria-label="Close inspector">✕</button>
    </div>
    <div class="dr-inspector-body" id="dr-inspector-body">
      <div class="dr-inspector-empty">
        Select a row to view details.
      </div>
    </div>
  `;

  const closeBtn = containerEl.querySelector('#dr-inspector-close');
  const bodyEl   = containerEl.querySelector('#dr-inspector-body');

  closeBtn.addEventListener('click', onClose);

  function showEmpty() {
    bodyEl.innerHTML = `
      <div class="dr-inspector-empty">
        Select a row to view details.
      </div>
    `;
  }

  return {
    showEmpty,
    destroy() {
      closeBtn.removeEventListener('click', onClose);
    },
  };
}
