/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/global-strip.js — global controls strip.

   Wires:
     - Rate-limit pill: shows pending/inFlight/failed counts from
       /api/javdb/discovery/queue (QueueStatus). Updated by updateStatus().
     - AI assist pill: shows pending review-queue rows if any.
       Fires ai-assist-all on click.
     - Pause-all toggle: calls POST /api/javdb/discovery/queue/pause
       with {paused: true|false}.  Reflects queue.paused from status.
   ───────────────────────────────────────────────────────────────────── */

/**
 * Mount the global controls strip into containerEl.
 *
 * @param {HTMLElement} containerEl
 * @param {{
 *   onPauseChange?: (paused: boolean) => void,
 * }} [opts]
 * @returns {{
 *   updateStatus(status: object): void,
 *   destroy(): void,
 * }}
 */
export function mountGlobalStrip(containerEl, opts = {}) {
  const { onPauseChange } = opts;

  containerEl.innerHTML = `
    <span class="dr-pill-label">Queue:</span>
    <span class="dr-pill" id="dr-rate-limit-pill" title="pending / in-flight / failed">—</span>

    <span class="dr-pill-label">AI assist:</span>
    <button class="dr-pill dr-pill-btn" id="dr-ai-assist-pill" type="button" title="Click to run AI assist on all open review-queue items">—</button>

    <div class="dr-global-strip-spacer"></div>

    <button class="dr-pause-toggle" id="dr-pause-toggle" type="button" aria-pressed="false">
      ⏸ Pause all
    </button>
  `;

  const queuePillEl  = containerEl.querySelector('#dr-rate-limit-pill');
  const aiPillEl     = containerEl.querySelector('#dr-ai-assist-pill');
  const pauseToggleEl = containerEl.querySelector('#dr-pause-toggle');

  let _paused = false;

  // ── Pause toggle ───────────────────────────────────────────────────

  async function handlePauseToggle() {
    const next = !_paused;
    pauseToggleEl.disabled = true;
    try {
      const res = await fetch('/api/javdb/discovery/queue/pause', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ paused: next }),
      });
      if (res.ok) {
        _paused = next;
        _applyPauseUi(_paused);
        onPauseChange?.(_paused);
      }
    } catch (_) { /* ignore */ }
    pauseToggleEl.disabled = false;
  }

  function _applyPauseUi(paused) {
    pauseToggleEl.setAttribute('aria-pressed', String(paused));
    pauseToggleEl.dataset.active = String(paused);
    pauseToggleEl.textContent = paused ? '▶ Resume all' : '⏸ Pause all';
  }

  pauseToggleEl.addEventListener('click', handlePauseToggle);

  // ── AI assist pill ────────────────────────────────────────────────

  async function handleAiAssist() {
    aiPillEl.disabled = true;
    const original = aiPillEl.textContent;
    aiPillEl.textContent = 'queuing…';
    try {
      await fetch('/api/enrichment/workflow/ai-assist-all', { method: 'POST' });
    } catch (_) { /* ignore */ }
    // Reset label; status update will re-paint shortly.
    aiPillEl.textContent = original;
    aiPillEl.disabled = false;
  }

  aiPillEl.addEventListener('click', handleAiAssist);

  // ── Public API ────────────────────────────────────────────────────

  /**
   * Update the strip pills from a QueueStatus object.
   * @param {object} status — { pending, inFlight, failed, paused, rateLimitPausedUntil, ... }
   */
  function updateStatus(status) {
    if (!status) return;

    // Queue pill: "3 pending · 1 in-flight" etc.
    const parts = [];
    if (status.pending  > 0) parts.push(`${status.pending} pending`);
    if (status.inFlight > 0) parts.push(`${status.inFlight} in-flight`);
    if (status.failed   > 0) parts.push(`${status.failed} failed`);

    if (parts.length === 0) {
      queuePillEl.textContent = 'idle';
      queuePillEl.dataset.state = 'idle';
    } else {
      queuePillEl.textContent = parts.join(' · ');
      queuePillEl.dataset.state = status.inFlight > 0 ? 'active'
                                 : status.failed  > 0 ? 'failed'
                                 : 'pending';
    }

    // Rate-limit pause indicator.
    if (status.rateLimitPausedUntil) {
      const remaining = Math.max(0,
        Math.ceil((new Date(status.rateLimitPausedUntil).getTime() - Date.now()) / 1000));
      if (remaining > 0) {
        queuePillEl.title = `Rate-limit pause: ${remaining}s remaining`;
        queuePillEl.dataset.state = 'paused';
      }
    }

    // AI assist pill: show open-review count if available.
    if (status.ambiguous != null) {
      const n = status.ambiguous;
      aiPillEl.textContent = n > 0 ? `${n} open` : 'none';
      aiPillEl.dataset.state = n > 0 ? 'open' : 'idle';
    }

    // Pause toggle.
    if (typeof status.paused === 'boolean' && status.paused !== _paused) {
      _paused = status.paused;
      _applyPauseUi(_paused);
    }
  }

  return {
    updateStatus,
    destroy() {
      pauseToggleEl.removeEventListener('click', handlePauseToggle);
      aiPillEl.removeEventListener('click', handleAiAssist);
    },
  };
}
