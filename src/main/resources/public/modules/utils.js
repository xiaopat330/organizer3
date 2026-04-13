// ── Pure utilities (no DOM dependencies beyond #status) ───────────────────

export function esc(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
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

// Compact relative time labels for card surfaces ("1 min ago" vs "1 minute ago").
export function timeAgoShort(isoString) {
  const seconds = Math.floor((Date.now() - new Date(isoString)) / 1000);
  if (seconds < 60)  return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60)  return minutes === 1 ? '1 min ago' : `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24)    return hours === 1 ? '1 hour ago' : `${hours} hours ago`;
  const days = Math.floor(hours / 24);
  if (days < 14)     return days === 1 ? '1 day ago' : `${days} days ago`;
  const weeks = Math.floor(days / 7);
  if (weeks < 9)     return weeks === 1 ? '1 week ago' : `${weeks} weeks ago`;
  const months = Math.floor(days / 30);
  if (months <= 3)   return months === 1 ? '1 month ago' : `${months} months ago`;
  return 'more than 3 months ago';
}

export function timeAgo(isoString) {
  const then = new Date(isoString);
  const now = new Date();
  const seconds = Math.floor((now - then) / 1000);
  if (seconds < 60)    return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60)    return minutes === 1 ? '1 minute ago' : `${minutes} minutes ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24)      return hours === 1 ? '1 hour ago' : `${hours} hours ago`;
  const days = Math.floor(hours / 24);
  if (days < 14)       return days === 1 ? '1 day ago' : `${days} days ago`;
  const weeks = Math.floor(days / 7);
  if (weeks < 9)       return weeks === 1 ? '1 week ago' : `${weeks} weeks ago`;
  const months = Math.floor(days / 30);
  if (months <= 3)     return months === 1 ? '1 month ago' : `${months} months ago`;
  return 'more than 3 months ago';
}
