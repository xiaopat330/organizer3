// modules/chrome/drag-guard.js
// Window-level guard against accidental file/URL drops.
//
// The browser default for a dropped file or image URL is to navigate to it —
// so missing a real drop zone (cover panels) by even a few pixels would replace
// the app with the dropped image. This guard makes the whole window reject that
// default, turning off-zone drops into harmless no-ops.
//
// Real drop zones still work: they sit *inside* the window and handle their own
// dragover/drop with preventDefault during the BUBBLE phase, firing before the
// event bubbles up to these window-level handlers. We listen in the bubble phase
// (not capture) and only call preventDefault — never stopPropagation — so we
// never interfere with descendant drop zones.

let installed = false;

export function installDragGuard() {
  if (installed) return;
  installed = true;

  const handler = (e) => { e.preventDefault(); };

  window.addEventListener('dragover', handler);
  window.addEventListener('drop',     handler);
}
