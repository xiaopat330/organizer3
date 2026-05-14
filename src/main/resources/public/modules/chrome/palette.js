/* ─────────────────────────────────────────────────────────────────────
   ⌘K command palette — see spec/DESIGN_SYSTEM.md §3
   Wired to /api/search (federated): actresses + titles + labels +
   companies + AV actresses. Plus always-visible navigation entries
   so jumping between pages is keyboard-only.
   Per-category include toggles persist in localStorage.
   ───────────────────────────────────────────────────────────────────── */

const ICONS = {
  go:        '<svg viewBox="0 0 24 24"><path d="M5 12h14"/><path d="M13 6l6 6-6 6"/></svg>',
  search:    '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>',
  star:      '<svg viewBox="0 0 24 24"><polygon points="12 2 15 9 22 9 17 14 18 21 12 17 6 21 7 14 2 9 9 9"/></svg>',
  user:      '<svg viewBox="0 0 24 24"><circle cx="12" cy="7" r="4"/><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/></svg>',
  film:      '<svg viewBox="0 0 24 24"><rect x="2" y="2" width="20" height="20" rx="2"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="12" y1="2" x2="12" y2="22"/></svg>',
  tag:       '<svg viewBox="0 0 24 24"><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/></svg>',
  building:  '<svg viewBox="0 0 24 24"><path d="M3 21h18"/><path d="M5 21V7l8-4v18"/><path d="M19 21V11l-6-4"/></svg>',
};

const FILTER_STORAGE_KEY = 'v2.palette.filters';
const DEFAULT_FILTERS = {
  actresses:    true,
  titles:       true,
  labels:       true,
  companies:    true,
  avActresses:  true,
};

// Always-on navigation entries — shown when query is empty + when query matches their label.
const NAV_ITEMS = [
  { label: 'Home',          href: '/v2.html',              icon: 'go' },
  { label: 'Actresses',     href: '/v2-actresses.html',    icon: 'user' },
  { label: 'Titles',        href: '/v2-titles.html',       icon: 'film' },
  { label: 'AV Stars',      href: '/v2-avstars.html',      icon: 'star' },
  { label: 'Duplicates',    href: '/v2-duplicates.html',   icon: 'go' },
  { label: 'Trash',         href: '/v2-trash.html',        icon: 'go' },
  { label: 'Translation',   href: '/v2-translation.html',  icon: 'go' },
  { label: 'Pending Kanji', href: '/v2-pending-kanji.html', icon: 'go' },
  { label: 'Volumes',       href: '/v2-volumes.html',      icon: 'go' },
  { label: 'Logs',          href: '/v2-logs.html',         icon: 'go' },
  { label: 'Design system', href: '/design.html',          icon: 'go' },
];

function loadFilters(pageDefaults) {
  // pageDefaults: array of keys that should be ON by default on this page (first visit only).
  // If the user has ever saved filters, their saved value wins.
  const firstVisitDefaults = pageDefaults
    ? Object.fromEntries(Object.keys(DEFAULT_FILTERS).map(k => [k, pageDefaults.includes(k)]))
    : { ...DEFAULT_FILTERS };
  try {
    const raw = localStorage.getItem(FILTER_STORAGE_KEY);
    if (!raw) return firstVisitDefaults;
    return { ...DEFAULT_FILTERS, ...JSON.parse(raw) };
  } catch (e) { return firstVisitDefaults; }
}
function saveFilters(f) {
  try { localStorage.setItem(FILTER_STORAGE_KEY, JSON.stringify(f)); } catch (e) {}
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[c]));
}

// Convert /api/search response into flat list of palette items.
function flattenSearchResult(data, filters) {
  const items = [];
  if (filters.actresses && Array.isArray(data?.actresses)) {
    for (const a of data.actresses) {
      items.push({
        section: 'Actresses',
        icon: 'user',
        label: a.displayName || a.name || a.canonicalName || a.slug || `#${a.id}`,
        meta: a.titleCount != null ? `${a.titleCount} titles` : '',
        href: `/v2-actress-detail.html?id=${encodeURIComponent(a.id)}`,
      });
    }
  }
  if (filters.titles && Array.isArray(data?.titles)) {
    for (const t of data.titles) {
      items.push({
        section: 'Titles',
        icon: 'film',
        thumbUrl: t.coverUrl || null,
        label: `${t.code} · ${t.normalizedTitle || t.titleEn || t.titleJa || t.title || ''}`.trim(),
        meta: t.code || '',
        href: `/v2-title-detail.html?code=${encodeURIComponent(t.code)}`,
      });
    }
  }
  if (filters.labels && Array.isArray(data?.labels)) {
    for (const l of data.labels) {
      const code = l.code || l.label || '';
      items.push({
        section: 'Labels',
        icon: 'tag',
        label: l.name || code,
        meta: code,
        href: `/v2-titles.html?code=${encodeURIComponent(code)}`,
      });
    }
  }
  if (filters.companies && Array.isArray(data?.companies)) {
    for (const c of data.companies) {
      items.push({
        section: 'Companies',
        icon: 'building',
        label: c.name || c.id || '',
        meta: c.titleCount != null ? `${c.titleCount} titles` : '',
        href: `/v2-titles.html?company=${encodeURIComponent(c.name || c.id)}`,
      });
    }
  }
  if (filters.avActresses && Array.isArray(data?.avActresses)) {
    for (const a of data.avActresses) {
      items.push({
        section: 'AV Actresses',
        icon: 'star',
        label: a.stageName || a.folderName || `#${a.id}`,
        meta: a.videoCount != null ? `${a.videoCount} videos` : '',
        href: `/v2-avstar-detail.html?id=${encodeURIComponent(a.id)}`,
      });
    }
  }
  return items;
}

// Filter NAV_ITEMS by query (substring match on label).
function navItemsForQuery(q) {
  if (!q) {
    return NAV_ITEMS.map(n => ({ ...n, section: 'Jump to' }));
  }
  const ql = q.toLowerCase();
  return NAV_ITEMS
    .filter(n => n.label.toLowerCase().includes(ql))
    .map(n => ({ ...n, section: 'Jump to' }));
}

export function createPalette({ rootEl, onSelect, defaultFilters } = {}) {
  if (!rootEl) throw new Error('createPalette: rootEl required');

  const filters = loadFilters(defaultFilters);

  const filterChip = (key, label) => `
    <span class="palette-filter-chip${filters[key] ? ' on' : ''}" data-fkey="${key}">${escapeHtml(label)}</span>
  `;

  rootEl.innerHTML = `
    <div class="palette-backdrop" hidden role="dialog" aria-modal="true" aria-label="Command palette">
      <div class="palette">
        <div class="palette-input-wrap">
          ${ICONS.search}
          <input class="palette-input" type="text" placeholder="Search titles, actresses, labels, studios…" autocomplete="off" spellcheck="false">
          <span class="palette-input-hint"><span class="kbd">esc</span></span>
        </div>
        <div class="palette-filters">
          ${filterChip('actresses',   'Actresses')}
          ${filterChip('titles',      'Titles')}
          ${filterChip('labels',      'Labels')}
          ${filterChip('companies',   'Studios')}
          ${filterChip('avActresses', 'AV Actresses')}
        </div>
        <div class="palette-list" role="listbox"></div>
        <div class="palette-footer">
          <span class="palette-footer-hint"><span class="kbd">↑↓</span> navigate</span>
          <span class="palette-footer-hint"><span class="kbd">↵</span> open</span>
          <span class="palette-footer-hint"><span class="kbd">esc</span> close</span>
        </div>
      </div>
    </div>
  `;

  const backdrop  = rootEl.querySelector('.palette-backdrop');
  const input     = rootEl.querySelector('.palette-input');
  const listEl    = rootEl.querySelector('.palette-list');
  const filterBar = rootEl.querySelector('.palette-filters');

  let cursor = 0;
  let visible = false;
  let items = [];          // current rendered items (nav + search hits)
  let lastQuery = '';
  let inFlight = null;     // AbortController for in-flight fetch

  const render = () => {
    if (items.length === 0) {
      listEl.innerHTML = `<div class="palette-empty">No matches.</div>`;
      return;
    }
    let html = '';
    let lastSection = null;
    items.forEach((it, i) => {
      if (it.section !== lastSection) {
        html += `<div class="palette-section-label">${escapeHtml(it.section)}</div>`;
        lastSection = it.section;
      }
      const ic = ICONS[it.icon] || ICONS.go;
      const iconHtml = it.thumbUrl
        ? `<span class="palette-item-icon palette-item-thumb"><img src="${escapeHtml(it.thumbUrl)}" alt="" loading="lazy"></span>`
        : `<span class="palette-item-icon">${ic}</span>`;
      html += `<div class="palette-item${i === cursor ? ' cursor' : ''}" data-i="${i}" role="option">`
           +  `  ${iconHtml}`
           +  `  <span class="palette-item-label">${escapeHtml(it.label)}</span>`
           +  (it.meta ? `<span class="palette-item-meta">${escapeHtml(it.meta)}</span>` : '')
           +  `</div>`;
    });
    listEl.innerHTML = html;
  };

  const computeItems = (q, searchData) => {
    const nav = navItemsForQuery(q);
    const hits = searchData ? flattenSearchResult(searchData, filters) : [];
    items = [...hits, ...nav];
    if (cursor >= items.length) cursor = Math.max(0, items.length - 1);
    render();
  };

  const debounceTimer = { id: null };

  const runSearch = (q) => {
    if (inFlight) inFlight.abort();
    if (!q) {
      computeItems('', null);
      return;
    }
    const ctrl = new AbortController();
    inFlight = ctrl;
    const includeAv = filters.avActresses ? '&includeAv=true' : '';
    fetch(`/api/search?q=${encodeURIComponent(q)}${includeAv}`, { signal: ctrl.signal })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (q !== lastQuery) return;
        computeItems(q, data);
      })
      .catch(e => { /* abort or network error */ });
  };

  const onInput = () => {
    const q = input.value.trim();
    lastQuery = q;
    clearTimeout(debounceTimer.id);
    debounceTimer.id = setTimeout(() => runSearch(q), 120);
    // Show nav matches synchronously while server search is in flight
    computeItems(q, null);
  };

  const select = (i) => {
    const it = items[i];
    if (!it) return;
    close();
    if (onSelect) {
      onSelect(it);
      return;
    }
    // Default: navigate
    if (it.href) {
      window.location.assign(it.href);
    }
  };

  const open = () => {
    visible = true;
    backdrop.hidden = false;
    input.value = '';
    lastQuery = '';
    cursor = 0;
    computeItems('', null);
    requestAnimationFrame(() => input.focus());
  };

  const close = () => {
    visible = false;
    backdrop.hidden = true;
    if (inFlight) { inFlight.abort(); inFlight = null; }
  };

  // Wire events
  input.addEventListener('input', onInput);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Escape')          { e.preventDefault(); close(); }
    else if (e.key === 'ArrowDown')  { e.preventDefault(); cursor = Math.min(cursor + 1, items.length - 1); render(); scrollIntoCursor(); }
    else if (e.key === 'ArrowUp')    { e.preventDefault(); cursor = Math.max(cursor - 1, 0); render(); scrollIntoCursor(); }
    else if (e.key === 'Enter')      { e.preventDefault(); select(cursor); }
  });

  listEl.addEventListener('click', (e) => {
    const item = e.target.closest('.palette-item');
    if (!item) return;
    select(parseInt(item.dataset.i, 10));
  });

  filterBar.addEventListener('click', (e) => {
    const chip = e.target.closest('.palette-filter-chip');
    if (!chip) return;
    const key = chip.dataset.fkey;
    filters[key] = !filters[key];
    chip.classList.toggle('on', filters[key]);
    saveFilters(filters);
    runSearch(lastQuery);
  });

  backdrop.addEventListener('click', (e) => {
    if (e.target === backdrop) close();
  });

  // Global ⌘K / Ctrl-K binding
  document.addEventListener('keydown', (e) => {
    const isMod = e.metaKey || e.ctrlKey;
    if (isMod && (e.key === 'k' || e.key === 'K')) {
      e.preventDefault();
      visible ? close() : open();
    }
  });

  function scrollIntoCursor() {
    const el = listEl.querySelector('.palette-item.cursor');
    if (el) el.scrollIntoView({ block: 'nearest' });
  }

  return { open, close, isOpen: () => visible };
}
