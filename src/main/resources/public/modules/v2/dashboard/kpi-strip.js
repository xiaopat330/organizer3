/* ─────────────────────────────────────────────────────────────────────
   Shared primitive: renderKpiStrip(items) → HTMLElement

   Renders a compact mono-font KPI line used below hero bands on
   Home, Actresses, and Titles dashboards.

   items: Array<{ value: string, label: string }>
     value — the numeric or formatted value (e.g. "1,234", "98%")
     label — the descriptive label (e.g. "titles", "actresses")

   Items are separated by mid-dots. Returns a div.dash-kpi-strip.
   ───────────────────────────────────────────────────────────────────── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

/**
 * @param {Array<{value: string, label: string}>} items
 * @returns {HTMLElement}
 */
export function renderKpiStrip(items) {
  if (!items || items.length === 0) return null;

  const parts = items.map(({ value, label }) =>
    `${esc(String(value))} <span class="dash-kpi-label">${esc(label)}</span>`
  );

  const el = document.createElement('div');
  el.className = 'dash-kpi-strip';
  el.innerHTML = parts.join('<span class="dash-kpi-dot">·</span>');
  return el;
}
