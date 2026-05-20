/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/index.js — Discovery Workbench entry point.

   Phase A — shells and routing:
     - Read URL params → initial state
     - Render layout skeleton
     - Mount global-strip / pivot-strip / inspector / queue-dock shells
     - Wire inspector resize handle (mousedown → mousemove → mouseup)
     - Wire single ESC handler
     - Write state changes back to URL

   Phase B — pivot content (not yet).
   ───────────────────────────────────────────────────────────────────── */

import { createState, saveInspectorWidth, INSPECTOR_WIDTH_BOUNDS } from './state.js';
import { readUrlParams, writeUrlParams }   from './url.js';
import { renderLayout, setInspectorWidth } from './layout.js';
import { mountGlobalStrip }  from './global-strip.js';
import { mountPivotStrip }   from './pivot-strip.js';
import { mountInspector }    from './inspector.js';
import { mountQueueDock }    from './queue-dock.js';

/**
 * Mount the Discovery Workbench into rootEl.
 * @param {HTMLElement} rootEl
 */
export function mountDiscoveryRedesign(rootEl) {
  // ── State ─────────────────────────────────────────────────────────
  const state = createState();

  // Apply URL params to initial state
  const urlParams = readUrlParams();
  state.currentPivot     = urlParams.pivot;
  state.queueDockExpanded = urlParams.queueDockExpanded;

  // ── Layout skeleton ───────────────────────────────────────────────
  const refs = renderLayout(rootEl, state.inspectorWidth);
  const {
    globalStripEl,
    pivotStripEl,
    mainAreaEl,
    tableInnerEl,
    resizeHandleEl,
    inspectorEl,
    queueDockEl,
  } = refs;

  // ── Placeholder — Phase B will replace this ───────────────────────
  tableInnerEl.innerHTML = `
    <div class="dr-placeholder">
      Pivot content goes here.
      <span class="dr-placeholder-label">Phase B</span>
    </div>
  `;

  // ── Global controls strip ──────────────────────────────────────────
  const globalStrip = mountGlobalStrip(globalStripEl);

  // ── Pivot strip ───────────────────────────────────────────────────
  const pivotStrip = mountPivotStrip(pivotStripEl, {
    currentPivot: state.currentPivot,
    onPivotChange(pivot) {
      state.currentPivot = pivot;
      // Clear selection when switching pivot
      state.selection.clear();
      inspectorHandle.showEmpty();
      // TODO Phase B: load pivot content
      writeUrlParams({ pivot: state.currentPivot, queueDockExpanded: state.queueDockExpanded });
    },
  });

  // ── Inspector ─────────────────────────────────────────────────────
  const inspectorHandle = mountInspector(inspectorEl, {
    onClose() {
      state.inspectorOpen = false;
      state.selection.clear();
      // Phase A: just show empty state — Phase B will hide the panel
      inspectorHandle.showEmpty();
    },
  });

  // ── Queue dock ────────────────────────────────────────────────────
  const queueDockHandle = mountQueueDock(queueDockEl, {
    expanded: state.queueDockExpanded,
    onExpandChange(expanded) {
      state.queueDockExpanded = expanded;
      writeUrlParams({ pivot: state.currentPivot, queueDockExpanded: state.queueDockExpanded });
    },
  });

  // ── Inspector resize handle ───────────────────────────────────────
  let _resizeDragging = false;
  let _resizeStartX   = 0;
  let _resizeStartW   = 0;

  resizeHandleEl.addEventListener('mousedown', e => {
    if (e.button !== 0) return;
    _resizeDragging = true;
    _resizeStartX   = e.clientX;
    _resizeStartW   = state.inspectorWidth;
    resizeHandleEl.classList.add('dragging');
    e.preventDefault();
  });

  document.addEventListener('mousemove', e => {
    if (!_resizeDragging) return;
    // Moving the handle left increases inspector width (inspector is on the right)
    const delta = _resizeStartX - e.clientX;
    const newW  = Math.max(
      INSPECTOR_WIDTH_BOUNDS.min,
      Math.min(INSPECTOR_WIDTH_BOUNDS.max, _resizeStartW + delta),
    );
    state.inspectorWidth = newW;
    setInspectorWidth(mainAreaEl, newW);
  });

  document.addEventListener('mouseup', () => {
    if (!_resizeDragging) return;
    _resizeDragging = false;
    resizeHandleEl.classList.remove('dragging');
    saveInspectorWidth(state.inspectorWidth);
  });

  // ── ESC handler (single, page-level) ─────────────────────────────
  // Priority:
  //   1. If queue dock expanded → collapse it
  //   2. If inspector has a selection → clear selection
  //   3. No-op
  document.addEventListener('keydown', e => {
    if (e.key !== 'Escape') return;
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

    if (state.queueDockExpanded) {
      state.queueDockExpanded = false;
      queueDockHandle.setExpanded(false);
      writeUrlParams({ pivot: state.currentPivot, queueDockExpanded: false });
      return;
    }

    if (state.selection.size > 0) {
      state.selection.clear();
      inspectorHandle.showEmpty();
      // Phase B: trigger table row deselection here
      return;
    }
  });
}
