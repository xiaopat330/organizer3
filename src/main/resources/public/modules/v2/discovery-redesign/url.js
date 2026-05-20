/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/url.js — URL ↔ state synchronisation.

   Params handled:
     pivot    = actresses | titles | collections   (default: actresses)
     queue    = open | closed                      (default: closed, per D1)
     id       = <actress-id>                       (B9: deep-link to actress)
     panel    = titles | profile | conflicts | errors
                                                   (B9: inspector sub-tab)
     code     = <title-code>                       (B9: deep-link to title peek)
     pool     = <volume-id>                        (B9: activate pool chip)
     filter   = <string>                           (B9: pre-fill filter input)

   pivot changes use pushState; selection/filter/dock use replaceState.
   ───────────────────────────────────────────────────────────────────── */

const VALID_PIVOTS = new Set(['actresses', 'titles', 'collections']);
const DEFAULT_PIVOT = 'actresses';

const VALID_PANELS = new Set(['titles', 'profile', 'conflicts', 'errors']);

/**
 * Read the current URL params and return the initial state fragment.
 * @returns {{
 *   pivot: string,
 *   queueDockExpanded: boolean,
 *   actressId: number|null,
 *   panel: string|null,
 *   code: string|null,
 *   pool: string|null,
 *   filter: string|null,
 * }}
 */
export function readUrlParams() {
  const params = new URLSearchParams(window.location.search);

  let pivot = params.get('pivot') ?? DEFAULT_PIVOT;
  if (!VALID_PIVOTS.has(pivot)) pivot = DEFAULT_PIVOT;

  const queue = params.get('queue');
  const queueDockExpanded = queue === 'open';

  const rawId = params.get('id');
  const actressId = rawId && /^\d+$/.test(rawId) ? parseInt(rawId, 10) : null;

  const rawPanel = params.get('panel');
  const panel = rawPanel && VALID_PANELS.has(rawPanel) ? rawPanel : null;

  const code   = params.get('code')   || null;
  const pool   = params.get('pool')   || null;
  const filter = params.get('filter') || null;

  return { pivot, queueDockExpanded, actressId, panel, code, pool, filter };
}

/**
 * Write current state back to the URL via replaceState (within-pivot churn)
 * or pushState (pivot change).
 *
 * @param {{
 *   pivot: string,
 *   queueDockExpanded: boolean,
 *   actressId?: number|null,
 *   panel?: string|null,
 *   code?: string|null,
 *   pool?: string|null,
 *   filter?: string|null,
 *   push?: boolean,  — use pushState instead of replaceState
 * }} p
 */
export function writeUrlParams({
  pivot, queueDockExpanded,
  actressId = null, panel = null, code = null, pool = null, filter = null,
  push = false,
}) {
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

  if (actressId != null) {
    params.set('id', String(actressId));
  } else {
    params.delete('id');
  }

  if (panel && VALID_PANELS.has(panel)) {
    params.set('panel', panel);
  } else {
    params.delete('panel');
  }

  if (code) {
    params.set('code', code);
  } else {
    params.delete('code');
  }

  if (pool) {
    params.set('pool', pool);
  } else {
    params.delete('pool');
  }

  if (filter) {
    params.set('filter', filter);
  } else {
    params.delete('filter');
  }

  const search = params.toString();
  const url = window.location.pathname + (search ? '?' + search : '');
  if (push) {
    history.pushState(null, '', url);
  } else {
    history.replaceState(null, '', url);
  }
}
