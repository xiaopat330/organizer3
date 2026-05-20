/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/global-strip.js — global controls strip shell.

   Phase A: renders placeholder pills + pause-all toggle.
   Phase B: wires up real rate-limit / AI-assist endpoints.
   ───────────────────────────────────────────────────────────────────── */

/**
 * Mount the global controls strip into containerEl.
 * Returns a handle with update methods for Phase B.
 *
 * @param {HTMLElement} containerEl
 * @returns {{ destroy(): void }}
 */
export function mountGlobalStrip(containerEl) {
  containerEl.innerHTML = `
    <span class="dr-pill-label">Rate limit:</span>
    <span class="dr-pill" id="dr-rate-limit-pill" title="Loading…">—</span>

    <span class="dr-pill-label">AI assist:</span>
    <span class="dr-pill" id="dr-ai-assist-pill" title="Loading…">—</span>

    <div class="dr-global-strip-spacer"></div>

    <button class="dr-pause-toggle" id="dr-pause-toggle" type="button" aria-pressed="false">
      ⏸ Pause all
    </button>
  `;

  const pauseToggleEl = containerEl.querySelector('#dr-pause-toggle');

  function handlePauseToggle() {
    // Phase A: toggle is wired but does nothing yet — Phase B connects
    const pressed = pauseToggleEl.getAttribute('aria-pressed') === 'true';
    pauseToggleEl.setAttribute('aria-pressed', String(!pressed));
    pauseToggleEl.dataset.active = String(!pressed);
    pauseToggleEl.textContent = !pressed ? '▶ Resume all' : '⏸ Pause all';
  }

  pauseToggleEl.addEventListener('click', handlePauseToggle);

  return {
    destroy() {
      pauseToggleEl.removeEventListener('click', handlePauseToggle);
    },
  };
}
