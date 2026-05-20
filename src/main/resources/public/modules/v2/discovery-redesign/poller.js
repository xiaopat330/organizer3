/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/poller.js — Consolidated poll loop (B8).

   Two polling tiers:
     - summary  — 10 s always; fires onSummary(status) with QueueStatus.
     - items    — 5 s when dock is expanded OR inFlight > 0;
                  fires onItems(items[]) with QueueItem array.

   Callers call start() once; call notifyDockState(expanded) when the
   dock expand state changes.  stop() cleans up both intervals.
   ───────────────────────────────────────────────────────────────────── */

const SUMMARY_INTERVAL_MS = 10_000;
const ITEMS_INTERVAL_MS   = 5_000;

/**
 * Create and immediately start the consolidated poll loop.
 *
 * @param {{
 *   onSummary: (status: object) => void,
 *   onItems:   (items: object[]) => void,
 * }} callbacks
 * @returns {{
 *   notifyDockState(expanded: boolean): void,
 *   forceSummary(): Promise<void>,
 *   forceItems():   Promise<void>,
 *   stop():         void,
 * }}
 */
export function createPoller({ onSummary, onItems }) {
  let _dockExpanded  = false;
  let _inFlight      = 0;
  let _summaryTimer  = null;
  let _itemsTimer    = null;
  let _destroyed     = false;

  // ── Fetch helpers ─────────────────────────────────────────────────

  async function fetchSummary() {
    if (_destroyed) return;
    try {
      const res = await fetch('/api/javdb/discovery/queue');
      if (!res.ok) return;
      const data = await res.json();
      _inFlight = data.inFlight ?? 0;
      onSummary(data);
      // Re-evaluate items-poll need after summary updates inFlight count.
      syncItemsPoll();
    } catch (_) { /* ignore transient errors */ }
  }

  async function fetchItems() {
    if (_destroyed) return;
    try {
      const res = await fetch('/api/javdb/discovery/queue/items');
      if (!res.ok) return;
      const items = await res.json();
      onItems(items);
    } catch (_) { /* ignore */ }
  }

  // ── Timer management ──────────────────────────────────────────────

  function startSummaryPoll() {
    if (_summaryTimer !== null) return;
    _summaryTimer = setInterval(fetchSummary, SUMMARY_INTERVAL_MS);
  }

  function startItemsPoll() {
    if (_itemsTimer !== null) return;
    _itemsTimer = setInterval(fetchItems, ITEMS_INTERVAL_MS);
  }

  function stopItemsPoll() {
    if (_itemsTimer !== null) {
      clearInterval(_itemsTimer);
      _itemsTimer = null;
    }
  }

  /**
   * Start or stop the items poll depending on dock state + inFlight count.
   */
  function syncItemsPoll() {
    if (_dockExpanded || _inFlight > 0) {
      startItemsPoll();
    } else {
      stopItemsPoll();
    }
  }

  // ── Public API ────────────────────────────────────────────────────

  /**
   * Notify the poller that the dock expand state changed.
   * @param {boolean} expanded
   */
  function notifyDockState(expanded) {
    _dockExpanded = expanded;
    syncItemsPoll();
    if (expanded) {
      // Fetch items immediately when dock opens.
      fetchItems();
    }
  }

  /**
   * Force an immediate summary fetch (used after an action that may change the queue).
   */
  async function forceSummary() {
    await fetchSummary();
  }

  /**
   * Force an immediate items fetch.
   */
  async function forceItems() {
    await fetchItems();
  }

  /**
   * Stop all polling. Call when the workbench is unmounted.
   */
  function stop() {
    _destroyed = true;
    if (_summaryTimer !== null) { clearInterval(_summaryTimer); _summaryTimer = null; }
    stopItemsPoll();
  }

  // Kick off immediately.
  fetchSummary();
  startSummaryPoll();
  syncItemsPoll();

  return { notifyDockState, forceSummary, forceItems, stop };
}
