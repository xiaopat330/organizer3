// Actress Detail → Admin tab — pagination control.
//
// Purely presentational: renders the control HTML and wires up listeners.
// No state knowledge; all logic lives in index.js which passes an onNavigate
// callback. The navigate-away guard also lives in index.js (see navigateToPage).
//
// Public surface:
//   renderPagination(currentPage, totalPages)
//     → returns an HTML string. Returns '' when totalPages <= 1 (hidden).
//   attachPaginationListeners(currentPage, totalPages, onNavigate)
//     → wires up buttons + page-jump input inside the rendered control.
//     Call after view.innerHTML is set.

/**
 * Returns the HTML string for the pagination control, or '' if totalPages <= 1.
 * @param {number} currentPage  1-indexed current page.
 * @param {number} totalPages   Total number of pages.
 */
export function renderPagination(currentPage, totalPages) {
  if (totalPages <= 1) return '';

  const atFirst = currentPage === 1;
  const atLast  = currentPage === totalPages;

  const firstDis   = atFirst ? ' disabled' : '';
  const prevDis    = atFirst ? ' disabled' : '';
  const nextDis    = atLast  ? ' disabled' : '';
  const lastDis    = atLast  ? ' disabled' : '';

  return `
    <div class="admin-pagination">
      <button class="admin-pagination-btn" id="apg-first"${firstDis} title="First page">⏮ first</button>
      <button class="admin-pagination-btn" id="apg-prev"${prevDis}   title="Previous 10 pages">‹ prev 10</button>
      <span class="admin-pagination-info">
        page <input
          class="admin-pagination-input"
          id="apg-input"
          type="number"
          min="1"
          max="${totalPages}"
          value="${currentPage}"
          title="Jump to page"
        /> of ${totalPages}
      </span>
      <button class="admin-pagination-btn" id="apg-next"${nextDis}   title="Next 10 pages">next 10 ›</button>
      <button class="admin-pagination-btn" id="apg-last"${lastDis}   title="Last page">last ⏭</button>
    </div>
  `;
}

/**
 * Attaches event listeners to the rendered pagination control.
 * Must be called after view.innerHTML is set (listeners are cleared on each re-render).
 *
 * @param {number}   currentPage  1-indexed current page.
 * @param {number}   totalPages   Total number of pages.
 * @param {function} onNavigate   Callback(targetPage) invoked when a navigation is requested.
 *                                The guard logic lives in index.js; this module just calls it.
 */
export function attachPaginationListeners(currentPage, totalPages, onNavigate) {
  if (totalPages <= 1) return;

  const btnFirst = document.getElementById('apg-first');
  const btnPrev  = document.getElementById('apg-prev');
  const btnNext  = document.getElementById('apg-next');
  const btnLast  = document.getElementById('apg-last');
  const input    = document.getElementById('apg-input');

  if (btnFirst) btnFirst.addEventListener('click', () => onNavigate(1));
  if (btnPrev)  btnPrev.addEventListener('click',  () => onNavigate(Math.max(1, currentPage - 10)));
  if (btnNext)  btnNext.addEventListener('click',  () => onNavigate(Math.min(totalPages, currentPage + 10)));
  if (btnLast)  btnLast.addEventListener('click',  () => onNavigate(totalPages));

  // Page-jump input: submit on Enter key. "Go" button omitted — Enter feels
  // natural here and avoids crowding the layout.
  if (input) {
    input.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter') return;
      const parsed = parseInt(input.value, 10);
      if (isNaN(parsed)) return;          // non-numeric — no-op
      const target = Math.max(1, Math.min(totalPages, parsed));
      onNavigate(target);                 // no-op when target === currentPage (guard in index.js)
    });
  }
}
