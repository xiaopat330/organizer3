/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/inspector.js — inspector panel.

   Receives HTML content from pivot modules via setContent().
   The title is updated via setTitle().
   showEmpty() reverts to the contextual empty-state.

   The panel never opens modals — all content renders inline here.
   Cover lightbox (showCoverLightbox) is wired via event delegation so
   covers inside inspector content open the lightbox without the pivot
   needing to wire anything separately.
   ───────────────────────────────────────────────────────────────────── */

import { showCoverLightbox } from './pivots/shared.js';

/**
 * Mount the inspector shell into containerEl.
 *
 * @param {HTMLElement} containerEl
 * @param {{
 *   onClose: () => void,
 * }} opts
 * @returns {{
 *   showEmpty(hint?: string): void,
 *   setTitle(title: string): void,
 *   setContent(html: string): void,
 *   destroy(): void,
 * }}
 */
export function mountInspector(containerEl, { onClose }) {
  containerEl.innerHTML = `
    <div class="dr-inspector-header">
      <span class="dr-inspector-title" id="dr-inspector-title">Inspector</span>
      <button class="dr-inspector-close" id="dr-inspector-close" type="button" aria-label="Close inspector">✕</button>
    </div>
    <div class="dr-inspector-body" id="dr-inspector-body">
      <div class="dr-inspector-empty">
        Select a row to view details.
      </div>
    </div>
  `;

  const closeBtn  = containerEl.querySelector('#dr-inspector-close');
  const titleEl   = containerEl.querySelector('#dr-inspector-title');
  const bodyEl    = containerEl.querySelector('#dr-inspector-body');

  closeBtn.addEventListener('click', onClose);

  // Cover lightbox: delegate clicks on .dr-peek-cover-zoom inside inspector body.
  bodyEl.addEventListener('click', e => {
    const img = e.target.closest('.dr-peek-cover-zoom[data-cover-url]');
    if (img) showCoverLightbox(img.dataset.coverUrl, img.dataset.code || '');
  });

  // ── Public API ────────────────────────────────────────────────────

  function showEmpty(hint) {
    titleEl.textContent = 'Inspector';
    bodyEl.innerHTML = `
      <div class="dr-inspector-empty">
        ${hint ? `<span>${hint}</span>` : 'Select a row to view details.'}
      </div>
    `;
  }

  function setTitle(title) {
    titleEl.textContent = title || 'Inspector';
  }

  function setContent(html) {
    bodyEl.innerHTML = html;
  }

  return {
    showEmpty,
    setTitle,
    setContent,
    destroy() {
      closeBtn.removeEventListener('click', onClose);
    },
  };
}
