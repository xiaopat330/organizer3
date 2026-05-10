/* ─────────────────────────────────────────────────────────────────────
   Wave 2 — Actresses browse (library mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md §1.2
   Card grid + scope chips (All/Favorites/Bookmarks) + tier chips +
   infinite scroll.
   Deferred: company sub-filter, Exhibition/Archives/Studio scopes
   (those become rail items in a later pass).
   ───────────────────────────────────────────────────────────────────── */

const PAGE_LIMIT = 48;
const TIERS = [
  ['LIBRARY',   'Library'],
  ['MINOR',     'Minor'],
  ['POPULAR',   'Popular'],
  ['SUPERSTAR', 'Superstar'],
  ['GODDESS',   'Goddess'],
];

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[c]));
}

async function fetchJson(url, fallback = null) {
  try {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) return fallback;
    return await r.json();
  } catch (e) {
    console.warn('[actresses] fetch failed:', url, e);
    return fallback;
  }
}

function buildUrl(state, offset, limit) {
  if (state.scope === 'favorites') return `/api/actresses?favorites=true&offset=${offset}&limit=${limit}`;
  if (state.scope === 'bookmarks') return `/api/actresses?bookmarks=true&offset=${offset}&limit=${limit}`;
  if (state.tier)                  return `/api/actresses?tier=${encodeURIComponent(state.tier)}&offset=${offset}&limit=${limit}`;
  return `/api/actresses?all=true&offset=${offset}&limit=${limit}`;
}

function renderCard(a) {
  const name     = a.displayName || a.name || a.slug || '';
  const tier     = (a.tier || '').toLowerCase();
  const portrait = a.profileImagePath
    ? `/api/actress-image/${encodeURIComponent(a.slug || a.id)}`
    : null;
  const titleCount = a.titleCount != null ? `${a.titleCount} titles` : '';
  return `
    <a class="card-actress" href="#actress/${encodeURIComponent(a.slug || a.id)}">
      <div class="card-actress-portrait" style="${portrait ? `background-image:url('${portrait}');background-size:cover;background-position:center top` : ''}">
        ${tier ? `<span class="card-actress-tier">${escapeHtml(tier)}</span>` : ''}
      </div>
      <div class="card-actress-name">${escapeHtml(name)}</div>
      ${titleCount ? `<div class="card-actress-meta">${escapeHtml(titleCount)}</div>` : ''}
    </a>
  `;
}

function createState() {
  return {
    scope: 'all',     // 'all' | 'favorites' | 'bookmarks'
    tier:  null,      // null | one of TIERS keys
    offset: 0,
    loading: false,
    exhausted: false,
    items: [],
  };
}

export function mountActresses(rootEl) {
  rootEl.innerHTML = `
    <div class="lib-page">
      <div class="lib-page-header">
        <h1 class="lib-page-title">Actresses</h1>
      </div>

      <div class="filter-bar">
        <div class="filter-group" id="scope-chips">
          <span class="chip on" data-scope="all">All</span>
          <span class="chip" data-scope="favorites">Favorites</span>
          <span class="chip" data-scope="bookmarks">Bookmarks</span>
        </div>
        <div class="filter-divider"></div>
        <div class="filter-group" id="tier-chips">
          <span class="filter-label">Tier:</span>
          <span class="chip on" data-tier="">All</span>
          ${TIERS.map(([k, label]) => `<span class="chip" data-tier="${k}">${label}</span>`).join('')}
        </div>
        <div class="filter-spacer"></div>
        <div class="filter-meta" id="result-meta"></div>
      </div>

      <div class="shelf-grid shelf-grid-actress" id="grid"></div>

      <div class="grid-status" id="grid-status">
        <div class="shelf-loading">Loading…</div>
      </div>
      <div id="sentinel" style="height:1px"></div>
    </div>
  `;

  const state    = createState();
  const grid     = rootEl.querySelector('#grid');
  const status   = rootEl.querySelector('#grid-status');
  const meta     = rootEl.querySelector('#result-meta');
  const sentinel = rootEl.querySelector('#sentinel');

  const setStatus = (html) => { status.innerHTML = html; };

  const reset = () => {
    state.offset = 0;
    state.exhausted = false;
    state.items = [];
    grid.innerHTML = '';
    meta.textContent = '';
  };

  const loadMore = async () => {
    if (state.loading || state.exhausted) return;
    state.loading = true;
    setStatus('<div class="shelf-loading">Loading…</div>');

    const url = buildUrl(state, state.offset, PAGE_LIMIT);
    const data = await fetchJson(url, []);
    const list = Array.isArray(data) ? data : (data?.items ?? []);

    if (list.length === 0 && state.items.length === 0) {
      setStatus(`
        <div class="empty-state">
          <div class="empty-state-title">No actresses match these filters</div>
          <div class="empty-state-body">Try a different scope or tier.</div>
        </div>`);
      state.exhausted = true;
      state.loading = false;
      return;
    }

    state.items.push(...list);
    grid.insertAdjacentHTML('beforeend', list.map(renderCard).join(''));
    state.offset += list.length;
    meta.textContent = `${state.items.length} loaded`;

    if (list.length < PAGE_LIMIT) {
      state.exhausted = true;
      setStatus(`<div class="shelf-loading">End of results.</div>`);
    } else {
      setStatus('');
    }
    state.loading = false;
  };

  // Filter chip wiring
  rootEl.querySelector('#scope-chips').addEventListener('click', (e) => {
    const chip = e.target.closest('.chip');
    if (!chip) return;
    rootEl.querySelectorAll('#scope-chips .chip').forEach(c => c.classList.remove('on'));
    chip.classList.add('on');
    state.scope = chip.dataset.scope;
    reset(); loadMore();
  });

  rootEl.querySelector('#tier-chips').addEventListener('click', (e) => {
    const chip = e.target.closest('.chip');
    if (!chip) return;
    rootEl.querySelectorAll('#tier-chips .chip').forEach(c => c.classList.remove('on'));
    chip.classList.add('on');
    state.tier = chip.dataset.tier || null;
    reset(); loadMore();
  });

  // Infinite scroll
  const io = new IntersectionObserver((entries) => {
    if (entries.some(e => e.isIntersecting)) loadMore();
  }, { rootMargin: '400px' });
  io.observe(sentinel);

  // Initial load
  loadMore();
}
