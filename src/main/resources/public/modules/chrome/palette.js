/* ─────────────────────────────────────────────────────────────────────
   ⌘K command palette — see spec/DESIGN_SYSTEM.md §3
   Wave-1 implementation: mock dataset, no real navigation/search.
   Surfaces what we want the API to look like; future waves wire it
   to the search service + a navigation registry.
   ───────────────────────────────────────────────────────────────────── */

const ICONS = {
  go:        '<svg viewBox="0 0 24 24"><path d="M5 12h14"/><path d="M13 6l6 6-6 6"/></svg>',
  search:    '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>',
  cmd:       '<svg viewBox="0 0 24 24"><path d="M9 6V4a2 2 0 0 0-4 0v0a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v0a2 2 0 0 0-4 0v2"/><path d="M9 18v2a2 2 0 0 1-4 0v0a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v0a2 2 0 0 1-4 0v-2"/><rect x="9" y="6" width="6" height="12"/></svg>',
  star:      '<svg viewBox="0 0 24 24"><polygon points="12 2 15 9 22 9 17 14 18 21 12 17 6 21 7 14 2 9 9 9"/></svg>',
  user:      '<svg viewBox="0 0 24 24"><circle cx="12" cy="7" r="4"/><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/></svg>',
};

// Mock dataset — three categories: actions, navigation destinations, sample entities.
const MOCK_ITEMS = [
  // Actions
  { id: 'act-sync',     label: 'Run sync',                    section: 'Actions',    icon: 'cmd', meta: 'shell' },
  { id: 'act-trash',    label: 'Trash sweep',                 section: 'Actions',    icon: 'cmd', meta: 'shell' },
  { id: 'act-pal',      label: 'Toggle workbench mode',       section: 'Actions',    icon: 'cmd', meta: 'm' },
  // Navigation
  { id: 'nav-home',     label: 'Home',                        section: 'Jump to',    icon: 'go',  meta: 'g h' },
  { id: 'nav-actress',  label: 'Actresses',                   section: 'Jump to',    icon: 'go',  meta: 'g a' },
  { id: 'nav-titles',   label: 'Titles',                      section: 'Jump to',    icon: 'go',  meta: 'g t' },
  { id: 'nav-dups',     label: 'Duplicates',                  section: 'Jump to',    icon: 'go',  meta: 'g d' },
  { id: 'nav-trans',    label: 'Translation',                 section: 'Jump to',    icon: 'go',  meta: 'g r' },
  { id: 'nav-trash',    label: 'Trash',                       section: 'Jump to',    icon: 'go',  meta: '' },
  { id: 'nav-logs',     label: 'Logs',                        section: 'Jump to',    icon: 'go',  meta: 'g l' },
  // Sample entities (would be fed by SearchService in Wave 2+)
  { id: 'ent-yuma',     label: 'Yuma Asami',                  section: 'Actresses',  icon: 'star', meta: '165 titles' },
  { id: 'ent-sora',     label: 'Sora Aoi',                    section: 'Actresses',  icon: 'star', meta: '78 titles' },
  { id: 'ent-soe-803',  label: 'SOE-803 — A Beautiful Woman…', section: 'Titles',    icon: 'search', meta: 'SOE-803' },
  { id: 'ent-snis-441', label: 'SNIS-441 — Reunion After 5…', section: 'Titles',     icon: 'search', meta: 'SNIS-441' },
];

function score(item, query) {
  if (!query) return 1;
  const q = query.toLowerCase();
  const label = item.label.toLowerCase();
  if (label === q) return 100;
  if (label.startsWith(q)) return 80;
  const wordHit = label.split(/\W+/).some(w => w.startsWith(q));
  if (wordHit) return 60;
  if (label.includes(q)) return 40;
  if ((item.meta || '').toLowerCase().includes(q)) return 20;
  return 0;
}

export function createPalette({ rootEl, items = MOCK_ITEMS, onSelect } = {}) {
  if (!rootEl) throw new Error('createPalette: rootEl required');

  rootEl.innerHTML = `
    <div class="palette-backdrop" hidden role="dialog" aria-modal="true" aria-label="Command palette">
      <div class="palette">
        <div class="palette-input-wrap">
          ${ICONS.search}
          <input class="palette-input" type="text" placeholder="Search or jump to…" autocomplete="off" spellcheck="false">
          <span class="palette-input-hint"><span class="kbd">esc</span></span>
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

  const backdrop = rootEl.querySelector('.palette-backdrop');
  const input    = rootEl.querySelector('.palette-input');
  const listEl   = rootEl.querySelector('.palette-list');

  let cursor = 0;
  let visible = false;
  let filtered = items.slice();

  const render = () => {
    if (filtered.length === 0) {
      listEl.innerHTML = `<div class="palette-empty">No matches.</div>`;
      return;
    }
    let html = '';
    let lastSection = null;
    filtered.forEach((it, i) => {
      if (it.section !== lastSection) {
        html += `<div class="palette-section-label">${it.section}</div>`;
        lastSection = it.section;
      }
      const ic = ICONS[it.icon] || ICONS.go;
      html += `<div class="palette-item${i === cursor ? ' cursor' : ''}" data-i="${i}" role="option">`
           +  `  <span class="palette-item-icon">${ic}</span>`
           +  `  <span class="palette-item-label">${escapeHtml(it.label)}</span>`
           +  (it.meta ? `<span class="palette-item-meta">${escapeHtml(it.meta)}</span>` : '')
           +  `</div>`;
    });
    listEl.innerHTML = html;
  };

  const filter = () => {
    const q = input.value.trim();
    filtered = items
      .map(it => ({ it, s: score(it, q) }))
      .filter(x => x.s > 0)
      .sort((a, b) => b.s - a.s)
      .map(x => x.it);
    cursor = 0;
    render();
  };

  const select = (i) => {
    const it = filtered[i];
    if (!it) return;
    close();
    if (onSelect) onSelect(it);
    else console.log('[palette] selected:', it);
  };

  const open = () => {
    visible = true;
    backdrop.hidden = false;
    input.value = '';
    filtered = items.slice();
    cursor = 0;
    render();
    requestAnimationFrame(() => input.focus());
  };

  const close = () => {
    visible = false;
    backdrop.hidden = true;
  };

  // Wire events
  input.addEventListener('input', filter);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Escape')          { e.preventDefault(); close(); }
    else if (e.key === 'ArrowDown')  { e.preventDefault(); cursor = Math.min(cursor + 1, filtered.length - 1); render(); scrollIntoCursor(); }
    else if (e.key === 'ArrowUp')    { e.preventDefault(); cursor = Math.max(cursor - 1, 0); render(); scrollIntoCursor(); }
    else if (e.key === 'Enter')      { e.preventDefault(); select(cursor); }
  });

  listEl.addEventListener('click', (e) => {
    const item = e.target.closest('.palette-item');
    if (!item) return;
    select(parseInt(item.dataset.i, 10));
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

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[c]));
}
