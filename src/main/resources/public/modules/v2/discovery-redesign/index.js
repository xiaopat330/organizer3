/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/index.js — Discovery Workbench entry point.

   Phase B:
     - Per-pivot content modules mounted into tableInnerEl.
     - Inspector driven by pivot selection.
     - Consolidated poll loop (poller.js).
     - Global strip wired to real endpoints.
     - Queue dock wired to items + summary.
     - URL deep-link params (B9).
     - ESC handler precedence (B10):
         lightbox (handled by its own AbortController, capture=true)
         → queue dock expanded → inspector content → clear selection
         → clear filter → no-op
     - Auto-collapse dock at <1200px viewport when inspector showing content.
   ───────────────────────────────────────────────────────────────────── */

import { createState, saveInspectorWidth, INSPECTOR_WIDTH_BOUNDS } from './state.js';
import { readUrlParams, writeUrlParams }   from './url.js';
import { renderLayout, setInspectorWidth } from './layout.js';
import { mountGlobalStrip }  from './global-strip.js';
import { mountPivotStrip }   from './pivot-strip.js';
import { mountInspector }    from './inspector.js';
import { mountQueueDock }    from './queue-dock.js';
import { createPoller }      from './poller.js';
import { mountActresses }    from './pivots/actresses.js';
import { mountTitles }       from './pivots/titles.js';
import { mountCollections }  from './pivots/collections.js';

/**
 * Mount the Discovery Workbench into rootEl.
 * @param {HTMLElement} rootEl
 */
export function mountDiscoveryRedesign(rootEl) {
  // ── State ─────────────────────────────────────────────────────────
  const state = createState();

  // Apply URL params to initial state.
  const urlParams = readUrlParams();
  state.currentPivot        = urlParams.pivot;
  state.queueDockExpanded   = urlParams.queueDockExpanded;
  state.initialActressId    = urlParams.actressId;
  state.initialPanel        = urlParams.panel;
  state.initialCode         = urlParams.code;
  state.initialPool         = urlParams.pool;
  state.initialFilter       = urlParams.filter;

  if (state.initialFilter) {
    state.titles.filter      = state.initialFilter;
    state.collections.filter = state.initialFilter;
  }

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

  // ── Inspector ─────────────────────────────────────────────────────
  const inspectorHandle = mountInspector(inspectorEl, {
    onClose() {
      state.inspectorOpen = false;
      state.selection.clear();
      state.titles.selected.clear();
      state.collections.selected.clear();
      inspectorHandle.showEmpty();
      _currentPivotHandle?.load?.();
    },
  });

  // ── Queue dock ────────────────────────────────────────────────────
  const queueDockHandle = mountQueueDock(queueDockEl, {
    expanded: state.queueDockExpanded,
    onExpandChange(expanded) {
      state.queueDockExpanded = expanded;
      writeUrlParams({ pivot: state.currentPivot, queueDockExpanded: state.queueDockExpanded });
      poller.notifyDockState(expanded);
      // Auto-collapse dock on small viewports when inspector is showing content.
      if (expanded && window.innerWidth < 1200 && state.inspectorOpen) {
        queueDockHandle.setExpanded(false);
        state.queueDockExpanded = false;
        writeUrlParams({ pivot: state.currentPivot, queueDockExpanded: false });
        poller.notifyDockState(false);
        return;
      }
    },
    onNavigateToActress(actressId) {
      // Switch to actresses pivot and navigate.
      if (state.currentPivot !== 'actresses') {
        state.currentPivot = 'actresses';
        pivotStrip.setPivot('actresses');
        writeUrlParams({ pivot: 'actresses', queueDockExpanded: state.queueDockExpanded, push: true });
        mountCurrentPivot();
      }
      _currentPivotHandle?.navigateToActress?.(actressId);
    },
  });

  // ── Global strip ──────────────────────────────────────────────────
  const globalStrip = mountGlobalStrip(globalStripEl, {
    onPauseChange(paused) {
      if (state.queueStatus) state.queueStatus.paused = paused;
    },
  });

  // ── Polling (B8) ──────────────────────────────────────────────────
  const poller = createPoller({
    onSummary(status) {
      state.queueStatus = status;
      globalStrip.updateStatus(status);
      queueDockHandle.updateSummary(status);
    },
    onItems(items) {
      queueDockHandle.updateItems(items, state.queueStatus);
    },
  });

  poller.notifyDockState(state.queueDockExpanded);

  // ── Auto-collapse dock on small viewport ──────────────────────────
  const _mq = window.matchMedia('(max-width: 1199px)');
  function _handleMqChange(e) {
    if (e.matches && state.queueDockExpanded) {
      state.queueDockExpanded = false;
      queueDockHandle.setExpanded(false);
      writeUrlParams({ pivot: state.currentPivot, queueDockExpanded: false });
      poller.notifyDockState(false);
    }
  }
  _mq.addEventListener('change', _handleMqChange);

  // ── Pivot modules ─────────────────────────────────────────────────

  let _currentPivotHandle = null;

  function mountCurrentPivot() {
    tableInnerEl.innerHTML = '';
    _currentPivotHandle = null;

    switch (state.currentPivot) {
      case 'actresses':
        _currentPivotHandle = mountActresses(tableInnerEl, {
          pivotState:       state.actresses,
          selection:        state.selection,
          onSelectionChange(_ids) {
            // URL write is deferred to inspector interactions.
          },
          inspectorHandle,
          refreshQueue:     () => poller.forceSummary(),
          initialActressId: state.initialActressId,
          initialPanel:     state.initialPanel,
        });
        _currentPivotHandle.load();
        break;

      case 'titles':
        _currentPivotHandle = mountTitles(tableInnerEl, {
          pivotState:    state.titles,
          inspectorHandle,
          initialPool:   state.initialPool,
          initialFilter: state.initialFilter,
        });
        _currentPivotHandle.load();
        break;

      case 'collections':
        _currentPivotHandle = mountCollections(tableInnerEl, {
          pivotState:    state.collections,
          inspectorHandle,
          initialFilter: state.initialFilter,
        });
        _currentPivotHandle.load();
        break;
    }
  }

  // ── Pivot strip ───────────────────────────────────────────────────
  const pivotStrip = mountPivotStrip(pivotStripEl, {
    currentPivot: state.currentPivot,
    onPivotChange(pivot) {
      const prev = state.currentPivot;
      state.currentPivot = pivot;
      // Clear per-pivot selection (not cross-pivot).
      if (pivot === 'actresses') state.selection.clear();
      if (pivot === 'titles')    state.titles.selected.clear();
      if (pivot === 'collections') state.collections.selected.clear();
      inspectorHandle.showEmpty();
      writeUrlParams({ pivot: state.currentPivot, queueDockExpanded: state.queueDockExpanded, push: prev !== pivot });
      mountCurrentPivot();
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

  // ── ESC handler (B10) — single page-level handler ────────────────
  //
  // Priority (lowest to highest — each guard returns to prevent fall-through):
  //   1. Cover lightbox — handled by its own AbortController with capture:true
  //      (fires first; we just check for its presence to skip remaining steps).
  //   2. Queue dock expanded → collapse it.
  //   3. Inspector has content (selection or explicit content) → show empty.
  //   4. Filter is active → clear filter (pivot-specific).
  //   5. No-op.
  //
  // Lightbox ESC is registered with capture:true so it fires before this
  // handler and calls stopPropagation(), meaning this handler never sees it.

  document.addEventListener('keydown', e => {
    if (e.key !== 'Escape') return;
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

    // If lightbox is open, it handles ESC itself with capture=true.
    if (document.querySelector('.dr-cover-overlay')) return;

    if (state.queueDockExpanded) {
      state.queueDockExpanded = false;
      queueDockHandle.setExpanded(false);
      writeUrlParams({ pivot: state.currentPivot, queueDockExpanded: false });
      poller.notifyDockState(false);
      return;
    }

    // Clear current-pivot selection.
    if (state.currentPivot === 'actresses' && state.selection.size > 0) {
      state.selection.clear();
      inspectorHandle.showEmpty();
      // Deselect list items visually — actresses pivot listens to selection Set.
      tableInnerEl.querySelectorAll('.dr-actress-item.selected').forEach(li => li.classList.remove('selected'));
      return;
    }
    if (state.currentPivot === 'titles' && state.titles.selected.size > 0) {
      state.titles.selected.clear();
      inspectorHandle.showEmpty();
      tableInnerEl.querySelectorAll('.dr-titles-cb:checked').forEach(cb => { cb.checked = false; });
      return;
    }
    if (state.currentPivot === 'collections' && state.collections.selected.size > 0) {
      state.collections.selected.clear();
      inspectorHandle.showEmpty();
      tableInnerEl.querySelectorAll('.dr-coll-cb:checked').forEach(cb => { cb.checked = false; });
      return;
    }
  });

  // ── Initial pivot mount ───────────────────────────────────────────
  mountCurrentPivot();
}
