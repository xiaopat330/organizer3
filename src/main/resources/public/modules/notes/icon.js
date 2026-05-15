// icon.js — Post-It Notes SVG icon factory
//
// notesIcon({ filled, title }) → SVGElement
//
// Two variants (§5.1):
//   filled: true  — yellow square with folded corner (note present)
//   filled: false — same silhouette, gray outline, no fill (empty slot)
//
// Dimensions: 12×12 px — matches ICON_FAV_SM / ICON_BM_SM in icons.js so
// the post-it sits flush in the card's existing icon row.

/**
 * Creates an inline SVG post-it icon element.
 *
 * @param {object} opts
 * @param {boolean} opts.filled  - true = yellow (note present), false = gray outline
 * @param {string}  [opts.title] - optional tooltip text (rendered as <title> inside SVG)
 * @returns {SVGElement}
 */
export function notesIcon({ filled = false, title = '' } = {}) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 12 12');
  svg.setAttribute('width', '12');
  svg.setAttribute('height', '12');
  svg.setAttribute('aria-hidden', title ? 'false' : 'true');
  svg.classList.add('notes-icon');
  if (filled) {
    svg.classList.add('notes-icon--filled');
  } else {
    svg.classList.add('notes-icon--empty');
  }

  // Optional accessible title
  if (title) {
    const titleEl = document.createElementNS(ns, 'title');
    titleEl.textContent = title;
    svg.appendChild(titleEl);
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', title);
  }

  // Body of the sticky note: square with folded corner cut from top-right.
  // Path: start top-left, across to fold start, diagonal to fold end, down
  // the right side, across the bottom, up the left side, close.
  // Fold starts at x=9 on the top edge, corner at (12,0) is cut to (9,0)→(12,3).
  const body = document.createElementNS(ns, 'path');
  // M 1,1 → L 9,1 → L 11,3 → L 11,11 → L 1,11 → Z  (1px inset for stroke)
  body.setAttribute('d', 'M1,1 L9,1 L11,3 L11,11 L1,11 Z');

  // Folded corner triangle
  const fold = document.createElementNS(ns, 'path');
  fold.setAttribute('d', 'M9,1 L9,3 L11,3 Z');

  // Two short "lines" suggesting handwritten content — purely decorative
  const line1 = document.createElementNS(ns, 'line');
  line1.setAttribute('x1', '3'); line1.setAttribute('y1', '5');
  line1.setAttribute('x2', '9'); line1.setAttribute('y2', '5');

  const line2 = document.createElementNS(ns, 'line');
  line2.setAttribute('x1', '3'); line2.setAttribute('y1', '7.5');
  line2.setAttribute('x2', '7'); line2.setAttribute('y2', '7.5');

  if (filled) {
    body.setAttribute('fill', 'var(--postit-yellow, #FFF59D)');
    body.setAttribute('stroke', 'var(--postit-yellow-edge, #FFEB3B)');
    body.setAttribute('stroke-width', '0.75');
    fold.setAttribute('fill', 'var(--postit-yellow-edge, #FFEB3B)');
    fold.setAttribute('stroke', 'var(--postit-yellow-edge, #FFEB3B)');
    fold.setAttribute('stroke-width', '0.5');
    line1.setAttribute('stroke', 'var(--postit-ink, #1A1A1A)');
    line1.setAttribute('stroke-width', '0.75');
    line1.setAttribute('stroke-linecap', 'round');
    line2.setAttribute('stroke', 'var(--postit-ink, #1A1A1A)');
    line2.setAttribute('stroke-width', '0.75');
    line2.setAttribute('stroke-linecap', 'round');
  } else {
    body.setAttribute('fill', 'none');
    body.setAttribute('stroke', 'var(--postit-empty-outline, #BDBDBD)');
    body.setAttribute('stroke-width', '0.75');
    fold.setAttribute('fill', 'none');
    fold.setAttribute('stroke', 'var(--postit-empty-outline, #BDBDBD)');
    fold.setAttribute('stroke-width', '0.5');
    line1.setAttribute('stroke', 'var(--postit-empty-outline, #BDBDBD)');
    line1.setAttribute('stroke-width', '0.75');
    line1.setAttribute('stroke-linecap', 'round');
    line2.setAttribute('stroke', 'var(--postit-empty-outline, #BDBDBD)');
    line2.setAttribute('stroke-width', '0.75');
    line2.setAttribute('stroke-linecap', 'round');
  }

  svg.appendChild(body);
  svg.appendChild(fold);
  svg.appendChild(line1);
  svg.appendChild(line2);

  return svg;
}
