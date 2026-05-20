/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/url.js — URL ↔ state synchronisation.

   Query params handled in Phase A:
     pivot = actresses | titles | collections   (default: actresses)
     queue = open | closed                      (default: closed, per D1)

   Phase A uses history.replaceState for all changes to avoid polluting
   the back-button stack with every dock toggle or pivot flip.
   We'll revisit pushState for pivot changes in Phase C.
   ───────────────────────────────────────────────────────────────────── */

const VALID_PIVOTS = new Set(['actresses', 'titles', 'collections']);
const DEFAULT_PIVOT = 'actresses';

/**
 * Read the current URL params and return the initial state fragment.
 * @returns {{ pivot: string, queueDockExpanded: boolean }}
 */
export function readUrlParams() {
  const params = new URLSearchParams(window.location.search);

  let pivot = params.get('pivot') ?? DEFAULT_PIVOT;
  if (!VALID_PIVOTS.has(pivot)) pivot = DEFAULT_PIVOT;

  const queue = params.get('queue');
  const queueDockExpanded = queue === 'open';

  return { pivot, queueDockExpanded };
}

/**
 * Write current state back to the URL via history.replaceState.
 * Keeps only the params we own; does not touch others.
 * @param {{ pivot: string, queueDockExpanded: boolean }} state
 */
export function writeUrlParams({ pivot, queueDockExpanded }) {
  const params = new URLSearchParams(window.location.search);

  if (pivot && VALID_PIVOTS.has(pivot)) {
    params.set('pivot', pivot);
  } else {
    params.delete('pivot');
  }

  if (queueDockExpanded) {
    params.set('queue', 'open');
  } else {
    params.delete('queue');
  }

  const search = params.toString();
  const url = window.location.pathname + (search ? '?' + search : '');
  history.replaceState(null, '', url);
}
