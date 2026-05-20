/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/layout.js — page-level DOM skeleton.

   Renders the static shell regions and returns DOM refs.
   Wires nothing — calling modules handle events.
   ───────────────────────────────────────────────────────────────────── */

/**
 * Render the workbench skeleton into rootEl.
 * Returns DOM refs for all regions and key elements.
 *
 * @param {HTMLElement} rootEl
 * @param {number} inspectorWidth — initial inspector width in pixels
 * @returns {{
 *   globalStripEl: HTMLElement,
 *   pivotStripEl:  HTMLElement,
 *   mainAreaEl:    HTMLElement,
 *   tablePaneEl:   HTMLElement,
 *   resizeHandleEl: HTMLElement,
 *   inspectorEl:   HTMLElement,
 *   queueDockEl:   HTMLElement,
 * }}
 */
export function renderLayout(rootEl, inspectorWidth) {
  rootEl.innerHTML = `
    <div class="dr-page">

      <!-- Global controls strip -->
      <div class="dr-global-strip" id="dr-global-strip">
      </div>

      <!-- Pivot strip -->
      <div class="dr-pivot-strip" id="dr-pivot-strip">
        <div class="dr-pivot-strip-spacer"></div>
      </div>

      <!-- Main area: table | resize handle | inspector -->
      <div class="dr-main-area" id="dr-main-area" style="--dr-inspector-width: ${inspectorWidth}px">

        <div class="dr-table-pane" id="dr-table-pane">
          <div class="dr-table-pane-inner" id="dr-table-inner">
          </div>
        </div>

        <div class="dr-resize-handle" id="dr-resize-handle" role="separator" aria-orientation="vertical" aria-label="Resize inspector"></div>

        <div class="dr-inspector" id="dr-inspector">
          <!-- inspector header + body — rendered by inspector.js -->
        </div>

      </div>

      <!-- Queue dock -->
      <div class="dr-queue-dock" id="dr-queue-dock" data-expanded="false">
        <!-- ticker + body — rendered by queue-dock.js -->
      </div>

    </div>
  `;

  return {
    globalStripEl:   rootEl.querySelector('#dr-global-strip'),
    pivotStripEl:    rootEl.querySelector('#dr-pivot-strip'),
    mainAreaEl:      rootEl.querySelector('#dr-main-area'),
    tablePaneEl:     rootEl.querySelector('#dr-table-pane'),
    tableInnerEl:    rootEl.querySelector('#dr-table-inner'),
    resizeHandleEl:  rootEl.querySelector('#dr-resize-handle'),
    inspectorEl:     rootEl.querySelector('#dr-inspector'),
    queueDockEl:     rootEl.querySelector('#dr-queue-dock'),
  };
}

/**
 * Update the CSS custom property that drives the inspector column width.
 * @param {HTMLElement} mainAreaEl
 * @param {number} width
 */
export function setInspectorWidth(mainAreaEl, width) {
  mainAreaEl.style.setProperty('--dr-inspector-width', width + 'px');
}
