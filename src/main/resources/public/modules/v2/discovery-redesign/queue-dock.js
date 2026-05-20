/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/queue-dock.js — bottom queue dock shell.

   Phase A: collapsed/expanded toggle with placeholder content.
   Collapsed (32 px): ticker text + expand button.
   Expanded (~280 px): placeholder body + collapse button.
   Phase B: live queue table, 5 s polling (only when expanded or
   in-flight count > 0 — fixing the "polling stops when hidden" bug
   from the legacy Discovery page).
   ───────────────────────────────────────────────────────────────────── */

/**
 * Mount the queue dock into containerEl.
 *
 * @param {HTMLElement} containerEl
 * @param {{
 *   expanded: boolean,
 *   onExpandChange: (expanded: boolean) => void,
 * }} opts
 * @returns {{ setExpanded(v: boolean): void, destroy(): void }}
 */
export function mountQueueDock(containerEl, { expanded, onExpandChange }) {
  containerEl.innerHTML = `
    <div class="dr-queue-dock-ticker">
      <button class="dr-queue-dock-expand-btn" id="dr-queue-dock-toggle" type="button"
              aria-expanded="${expanded}" aria-controls="dr-queue-dock-body">
        <span id="dr-queue-dock-chevron">${expanded ? '▾' : '▸'}</span>
        Queue
      </button>
      <span class="dr-queue-dock-ticker-text" id="dr-queue-dock-ticker-text">
        — jobs —
      </span>
    </div>
    <div class="dr-queue-dock-body" id="dr-queue-dock-body">
      Queue content goes here (Phase B).
    </div>
  `;

  containerEl.dataset.expanded = String(expanded);

  const toggleBtn  = containerEl.querySelector('#dr-queue-dock-toggle');
  const chevronEl  = containerEl.querySelector('#dr-queue-dock-chevron');

  function setExpanded(v) {
    containerEl.dataset.expanded = String(v);
    toggleBtn.setAttribute('aria-expanded', String(v));
    chevronEl.textContent = v ? '▾' : '▸';
  }

  function handleToggle() {
    const next = containerEl.dataset.expanded !== 'true';
    setExpanded(next);
    onExpandChange(next);
  }

  toggleBtn.addEventListener('click', handleToggle);

  return {
    setExpanded,
    destroy() {
      toggleBtn.removeEventListener('click', handleToggle);
    },
  };
}
