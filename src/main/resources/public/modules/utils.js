// ── Pure utilities (no DOM dependencies beyond #status) ───────────────────

export function esc(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

export function isStale(dateStr) {
  if (!dateStr) return false;
  const oneYearAgo = new Date();
  oneYearAgo.setFullYear(oneYearAgo.getFullYear() - 1);
  return new Date(dateStr) < oneYearAgo;
}

export function splitName(name) {
  const i = name.indexOf(' ');
  return i >= 0 ? { first: name.slice(0, i), last: name.slice(i + 1) } : { first: name, last: '' };
}

// Renders a "first → last" active date range. Returns '' if both are absent.
export function renderDateRange(first, last, cls = 'actress-active-dates') {
  if (!first && !last) return '';
  const firstHtml = first ? `<span class="date-first">${esc(fmtDate(first))}</span>` : '';
  const lastHtml  = last
    ? `<span class="${isStale(last) ? 'date-last-stale' : 'date-last'}">${esc(fmtDate(last))}</span>`
    : '';
  const sep = firstHtml && lastHtml ? ' → ' : '';
  return `<div class="${cls}">${firstHtml}${sep}${lastHtml}</div>`;
}

export function fmtDate(dateStr) {
  if (!dateStr) return '';
  const d = new Date(dateStr + 'T00:00:00');
  if (isNaN(d)) return dateStr;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

export function setStatus(msg) {
  const el = document.getElementById('status');
  if (el) el.textContent = msg;
}
