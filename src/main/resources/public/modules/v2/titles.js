/* ─────────────────────────────────────────────────────────────────────
   Wave 2 — Titles browse (library mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md §1.3
   Card grid + scope chips (All/Favorites/Bookmarks) + infinite scroll.
   Deferred: tag filters, label/year/company dropdowns, sort options,
   collections/unsorted/archive scopes (each gets its own follow-up).
   ───────────────────────────────────────────────────────────────────── */

const PAGE_LIMIT = 36;

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
    console.warn('[titles] fetch failed:', url, e);
    return fallback;
  }
}

function buildUrl(state, offset, limit) {
  if (state.scope === 'favorites') return `/api/titles?favorites=true&offset=${offset}&limit=${limit}`;
  if (state.scope === 'bookmarks') return `/api/titles?bookmarks=true&offset=${offset}&limit=${limit}`;
  // 'all' = recent (TitleRoutes default branch when no params)
  return `/api/titles?offset=${offset}&limit=${limit}`;
}

function renderCard(t) {
  const code   = t.code || t.productCode || t.titleCode || '';
  const name   = t.normalizedTitle || t.titleEn || t.titleJa || t.title || code;
  const cover  = t.coverPath
    ? `/covers/${encodeURIComponent(t.coverPath)}`
    : (code ? `/api/cover/${encodeURIComponent(code)}` : null);
  const cast   = (t.actresses && t.actresses.length) ? t.actresses[0].name : '';
  const year   = t.releaseDate ? String(t.releaseDate).slice(0, 4) : '';
  return `
    <a class="card-title" href="#title/${encodeURIComponent(code)}">
      <div class="card-title-cover" style="${cover ? `background-image:url('${cover}');background-size:cover;background-position:center` : ''}"></div>
      <div class="card-title-code">${escapeHtml(code)}</div>
      <div class="card-title-name">${escapeHtml(name)}</div>
      <div class="card-title-meta">
        ${cast ? `<span>${escapeHtml(cast)}</span>` : ''}
        ${cast && year ? '<span class="dot"></span>' : ''}
        ${year ? `<span class="year">${escapeHtml(year)}</span>` : ''}
      </div>
    </a>
  `;
}

function createState() {
  return {
    scope: 'all',
    offset: 0,
    loading: false,
    exhausted: false,
    items: [],
  };
}

export function mountTitles(rootEl) {
  rootEl.innerHTML = `
    <div class="lib-page">
      <div class="lib-page-header">
        <h1 class="lib-page-title">Titles</h1>
      </div>

      <div class="filter-bar">
        <div class="filter-group" id="scope-chips">
          <span class="chip on" data-scope="all">All</span>
          <span class="chip" data-scope="favorites">Favorites</span>
          <span class="chip" data-scope="bookmarks">Bookmarks</span>
        </div>
        <div class="filter-spacer"></div>
        <div class="filter-meta" id="result-meta"></div>
      </div>

      <div class="shelf-grid shelf-grid-titles" id="grid"></div>

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

    const url  = buildUrl(state, state.offset, PAGE_LIMIT);
    const data = await fetchJson(url, []);
    const list = Array.isArray(data) ? data : (data?.items ?? []);

    if (list.length === 0 && state.items.length === 0) {
      setStatus(`
        <div class="empty-state">
          <div class="empty-state-title">No titles match these filters</div>
          <div class="empty-state-body">Try a different scope.</div>
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

  rootEl.querySelector('#scope-chips').addEventListener('click', (e) => {
    const chip = e.target.closest('.chip');
    if (!chip) return;
    rootEl.querySelectorAll('#scope-chips .chip').forEach(c => c.classList.remove('on'));
    chip.classList.add('on');
    state.scope = chip.dataset.scope;
    reset(); loadMore();
  });

  const io = new IntersectionObserver((entries) => {
    if (entries.some(e => e.isIntersecting)) loadMore();
  }, { rootMargin: '400px' });
  io.observe(sentinel);

  loadMore();
}
