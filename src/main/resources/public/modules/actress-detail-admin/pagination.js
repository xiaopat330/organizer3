// Actress Detail → Admin tab — pagination control.
//
// Purely presentational: renders the control HTML and wires up listeners.
// No state knowledge; all logic lives in index.js which passes an onNavigate
// callback. The navigate-away guard also lives in index.js (see navigateToPage).
//
// Layout: [ Top ] [ ◀◀ jump ] [ ◀ ] [ page ___ ] [ ▶ ] [ jump ▶▶ ] [ Last ]
// where jump = 2 * pageSize (pages), driven by config.actressTitleAdmin.pageSize.

function jumpStep(pageSize) {
  return Math.max(1, (pageSize | 0) * 2);
}

export function renderPagination(currentPage, totalPages, pageSize) {
  if (totalPages <= 1) return '';

  const atFirst = currentPage === 1;
  const atLast  = currentPage === totalPages;
  const jump    = jumpStep(pageSize);

  const dis = (b) => (b ? ' disabled' : '');

  return `
    <div class="admin-pagination">
      <button class="admin-pagination-btn" id="apg-first"${dis(atFirst)} title="First page">⏮ Top</button>
      <button class="admin-pagination-btn" id="apg-prev-jump"${dis(atFirst)} title="Back ${jump} pages">◀◀ ${jump}</button>
      <button class="admin-pagination-btn" id="apg-prev"${dis(atFirst)} title="Previous page">◀</button>
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
      <button class="admin-pagination-btn" id="apg-next"${dis(atLast)} title="Next page">▶</button>
      <button class="admin-pagination-btn" id="apg-next-jump"${dis(atLast)} title="Forward ${jump} pages">${jump} ▶▶</button>
      <button class="admin-pagination-btn" id="apg-last"${dis(atLast)} title="Last page">Last ⏭</button>
    </div>
  `;
}

export function attachPaginationListeners(currentPage, totalPages, pageSize, onNavigate) {
  if (totalPages <= 1) return;

  const jump = jumpStep(pageSize);

  const wire = (id, target) => {
    const el = document.getElementById(id);
    if (el) el.addEventListener('click', () => onNavigate(target));
  };

  wire('apg-first',     1);
  wire('apg-prev-jump', Math.max(1, currentPage - jump));
  wire('apg-prev',      Math.max(1, currentPage - 1));
  wire('apg-next',      Math.min(totalPages, currentPage + 1));
  wire('apg-next-jump', Math.min(totalPages, currentPage + jump));
  wire('apg-last',      totalPages);

  const input = document.getElementById('apg-input');
  if (input) {
    input.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter') return;
      const parsed = parseInt(input.value, 10);
      if (isNaN(parsed)) return;
      const target = Math.max(1, Math.min(totalPages, parsed));
      onNavigate(target);
    });
  }
}
