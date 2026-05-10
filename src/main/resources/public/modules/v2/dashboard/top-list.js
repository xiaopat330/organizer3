/* ─────────────────────────────────────────────────────────────────────
   Shared primitive: renderTopList(opts) → HTMLElement

   Renders a tight 3-column ranked list used on Actresses (Top Groups)
   and Titles (Top Labels) dashboards.

   Options:
     items   : Array<{ name: string, count: number|string, slug?: string, subLabel?: string }>
                 name     — primary display name
                 count    — right-aligned numeric count
                 slug?    — passed to onClick; also shown as a small
                            sub-label under the name when subLabel is absent
                 subLabel?— explicit small text under name (overrides slug display)
     onClick : function({ name, count, slug }) — called when a row is clicked

   Returns a div.dash-top-list.
   ───────────────────────────────────────────────────────────────────── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

/**
 * @param {object} opts
 * @param {Array<{name:string, count:number|string, slug?:string, subLabel?:string}>} opts.items
 * @param {function} [opts.onClick]
 * @returns {HTMLElement}
 */
export function renderTopList({ items = [], onClick = null } = {}) {
  const list = document.createElement('div');
  list.className = 'dash-top-list';

  items.forEach((item, i) => {
    const row = document.createElement('div');
    row.className = 'dash-top-list-row';
    if (onClick) {
      row.style.cursor = 'pointer';
      row.title = `Open ${item.name}`;
    }

    const sub = item.subLabel != null ? item.subLabel : (item.slug || null);
    const nameHtml = sub
      ? `<span class="dash-top-list-name">${esc(item.name)}</span><span class="dash-top-list-sub">${esc(sub)}</span>`
      : `<span class="dash-top-list-name">${esc(item.name)}</span>`;

    const countVal = typeof item.count === 'number'
      ? item.count.toLocaleString()
      : String(item.count ?? '');

    row.innerHTML = `
      <span class="dash-top-list-rank">${i + 1}</span>
      <span class="dash-top-list-name-cell">${nameHtml}</span>
      <span class="dash-top-list-count">${esc(countVal)}</span>
    `;

    if (onClick) {
      row.addEventListener('click', () => onClick(item));
    }

    list.appendChild(row);
  });

  return list;
}
