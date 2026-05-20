/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/state.js — createState() factory.

   Central mutable state for the Discovery Workbench.
   Shared by all modules via the object reference created in index.js.
   Mutate directly; no proxy or pub/sub — caller re-renders.
   ───────────────────────────────────────────────────────────────────── */

const LS_INSPECTOR_WIDTH = 'v2-discovery-redesign:inspector-width';
const DEFAULT_INSPECTOR_WIDTH = 360;
const MIN_INSPECTOR_WIDTH = 320;
const MAX_INSPECTOR_WIDTH = 520;

/**
 * Load persisted inspector width from localStorage.
 * Falls back to DEFAULT_INSPECTOR_WIDTH if absent or out of bounds.
 * @returns {number}
 */
function loadInspectorWidth() {
  try {
    const raw = localStorage.getItem(LS_INSPECTOR_WIDTH);
    if (raw == null) return DEFAULT_INSPECTOR_WIDTH;
    const w = parseInt(raw, 10);
    if (isNaN(w)) return DEFAULT_INSPECTOR_WIDTH;
    return Math.max(MIN_INSPECTOR_WIDTH, Math.min(MAX_INSPECTOR_WIDTH, w));
  } catch {
    return DEFAULT_INSPECTOR_WIDTH;
  }
}

/**
 * Persist inspector width to localStorage.
 * @param {number} w
 */
export function saveInspectorWidth(w) {
  try {
    localStorage.setItem(LS_INSPECTOR_WIDTH, String(w));
  } catch {
    // ignore storage errors
  }
}

export const INSPECTOR_WIDTH_BOUNDS = { min: MIN_INSPECTOR_WIDTH, max: MAX_INSPECTOR_WIDTH };

/**
 * Create initial per-pivot state sub-object (used by Titles + Collections pivots).
 */
function createTitlesState() {
  return {
    rows: [],
    page: 0,
    pageSize: 50,
    filter: '',
    filterDebounce: null,
    totalPages: 0,
    hasMore: false,
    loading: false,
    /** @type {Set<number>} */
    selected: new Set(),
    // Titles-only: source chip + pools
    source: 'recent',
    poolVolumeId: null,
    pools: [],
  };
}

/**
 * Create initial per-pivot state sub-object for Collections.
 */
function createCollectionsState() {
  return {
    rows: [],
    page: 0,
    pageSize: 50,
    filter: '',
    filterDebounce: null,
    totalPages: 0,
    hasMore: false,
    loading: false,
    /** @type {Set<number>} */
    selected: new Set(),
  };
}

/**
 * Create the shared workbench state object.
 * @returns {DiscoveryRedesignState}
 */
export function createState() {
  return {
    // ── Pivot ─────────────────────────────────────────────────────────
    /** @type {'actresses'|'titles'|'collections'} */
    currentPivot: 'actresses',

    // ── Cross-pivot selection (actress IDs on Actresses pivot) ────────
    /** @type {Set<number|string>} — row IDs for the current pivot */
    selection: new Set(),

    // ── Inspector ─────────────────────────────────────────────────────
    /** Whether the inspector panel is open */
    inspectorOpen: true,
    /** Inspector width in pixels (320–520, user-draggable) */
    inspectorWidth: loadInspectorWidth(),

    // ── Queue dock ────────────────────────────────────────────────────
    /** Whether the queue dock is expanded (default: collapsed per D1) */
    queueDockExpanded: false,

    // ── Queue summary (polled every 10 s) ────────────────────────────
    /** Latest /api/javdb/discovery/queue response */
    queueStatus: null,

    // ── Per-pivot sub-states ──────────────────────────────────────────
    /**
     * Actresses pivot: list + filter + sort state.
     * Selection lives in state.selection (actress IDs).
     */
    actresses: {
      rows: [],
      loading: false,
      alphaFilter: 'All',
      tierFilter: new Set(),
      favoritesOnly: false,
      bookmarkedOnly: false,
      sortField: 'name',
      sortDir: 'asc',
    },

    /** Titles pivot: paginated table state. */
    titles: createTitlesState(),

    /** Collections pivot: paginated table state. */
    collections: createCollectionsState(),

    // ── Deep-link URL params (B9) ────────────────────────────────────
    /** Actress ID to open on load (from ?id= URL param) */
    initialActressId: null,
    /** Panel to show on load (from ?panel= URL param) */
    initialPanel: null,
    /** Title code to peek on load (from ?code= URL param) */
    initialCode: null,
    /** Pool volume ID to activate on load (from ?pool= URL param) */
    initialPool: null,
    /** Filter string to pre-fill on load (from ?filter= URL param) */
    initialFilter: null,
  };
}
