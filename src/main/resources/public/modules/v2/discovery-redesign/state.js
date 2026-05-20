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
 * Create the shared workbench state object.
 * @returns {DiscoveryRedesignState}
 */
export function createState() {
  return {
    // ── Pivot ─────────────────────────────────────────────────────────
    /** @type {'actresses'|'titles'|'collections'} */
    currentPivot: 'actresses',

    // ── Selection ─────────────────────────────────────────────────────
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
  };
}
