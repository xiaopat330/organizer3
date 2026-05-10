/* ─────────────────────────────────────────────────────────────────────
   Wave 2 — Actress detail (library mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md §3.2
   Hero band (portrait + identity + key stats + actions)
   + portfolio grid (titles).
   Workbench mode is reserved for the topbar mode-switch — wired in a
   later pass when the admin slot is built out.
   ───────────────────────────────────────────────────────────────────── */

const PAGE_LIMIT = 24;

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
    console.warn('[actress-detail] fetch failed:', url, e);
    return fallback;
  }
}

function tierClass(t) {
  return `tier-${(t || '').toLowerCase()}`;
}

function renderTitleCard(t) {
  const code   = t.code || t.productCode || t.titleCode || '';
  const name   = t.normalizedTitle || t.titleEn || t.titleJa || t.title || code;
  const cover  = t.coverPath
    ? `/covers/${encodeURIComponent(t.coverPath)}`
    : (code ? `/api/cover/${encodeURIComponent(code)}` : null);
  const year   = t.releaseDate ? String(t.releaseDate).slice(0, 4) : '';
  return `
    <a class="card-title" href="/v2-title-detail.html?code=${encodeURIComponent(code)}">
      <div class="card-title-cover" style="${cover ? `background-image:url('${cover}');background-size:cover;background-position:center` : ''}"></div>
      <div class="card-title-code">${escapeHtml(code)}</div>
      <div class="card-title-name">${escapeHtml(name)}</div>
      <div class="card-title-meta">
        ${year ? `<span class="year">${escapeHtml(year)}</span>` : ''}
      </div>
    </a>
  `;
}

async function loadAndRenderHero(rootEl, id) {
  const a = await fetchJson(`/api/actresses/${encodeURIComponent(id)}`, null);
  const heroEl = rootEl.querySelector('#hero');
  const titleEl = document.querySelector('#crumb-name');
  if (!a) {
    heroEl.innerHTML = `
      <div class="empty-state">
        <div class="empty-state-title">Actress not found</div>
        <div class="empty-state-body">No actress with ID ${escapeHtml(id)}.</div>
      </div>`;
    return null;
  }

  const name     = a.displayName || a.name || a.slug || '';
  const tier     = (a.tier || '').toLowerCase();
  const portrait = a.profileImagePath
    ? `/api/actress-image/${encodeURIComponent(a.slug || a.id)}`
    : null;
  const titleCount = a.titleCount != null ? a.titleCount : '?';
  const company    = a.primaryCompany || a.company || null;
  const aliases    = (a.aliases && a.aliases.length) ? a.aliases.join(' · ') : '';

  if (titleEl) titleEl.textContent = name;
  document.title = `${name} — Organizer3 v2`;

  heroEl.innerHTML = `
    <div class="hero-band">
      <div class="hero-portrait" style="${portrait ? `background-image:url('${portrait}');background-size:cover;background-position:center top` : ''}"></div>
      <div class="hero-content">
        ${tier ? `<div class="hero-eyebrow ${tierClass(a.tier)}">${escapeHtml(tier)} tier · Actress</div>` : `<div class="hero-eyebrow">Actress</div>`}
        <h1 class="hero-name">${escapeHtml(name)}</h1>
        ${aliases ? `<div class="hero-aliases">${escapeHtml(aliases)}</div>` : ''}
        <div class="hero-stats">
          <span><b>${escapeHtml(String(titleCount))}</b> titles</span>
          ${company ? `<span><b>${escapeHtml(company)}</b> primary label</span>` : ''}
        </div>
        <div class="hero-actions">
          <button class="btn primary" id="btn-favorite">
            <svg viewBox="0 0 24 24"><polygon points="12 2 15 9 22 9 17 14 18 21 12 17 6 21 7 14 2 9 9 9"/></svg>
            ${a.favorite ? 'Favorited' : 'Favorite'}
          </button>
          <button class="btn" id="btn-bookmark">
            <svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V3a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
            ${a.bookmark ? 'Bookmarked' : 'Bookmark'}
          </button>
        </div>
      </div>
    </div>
  `;

  // Wire favorite/bookmark toggles
  rootEl.querySelector('#btn-favorite')?.addEventListener('click', async () => {
    const r = await fetch(`/api/actresses/${encodeURIComponent(id)}/favorite`, { method: 'POST' });
    if (r.ok) loadAndRenderHero(rootEl, id);  // reload to show new state
  });
  rootEl.querySelector('#btn-bookmark')?.addEventListener('click', async () => {
    const r = await fetch(`/api/actresses/${encodeURIComponent(id)}/bookmark`, { method: 'POST' });
    if (r.ok) loadAndRenderHero(rootEl, id);
  });

  return a;
}

function createPortfolioState() {
  return { offset: 0, loading: false, exhausted: false, items: [] };
}

async function loadPortfolio(id, gridEl, statusEl, metaEl, state) {
  if (state.loading || state.exhausted) return;
  state.loading = true;
  statusEl.innerHTML = `<div class="shelf-loading">Loading…</div>`;

  const url  = `/api/actresses/${encodeURIComponent(id)}/titles?offset=${state.offset}&limit=${PAGE_LIMIT}`;
  const data = await fetchJson(url, []);
  const list = Array.isArray(data) ? data : (data?.items ?? data?.titles ?? []);

  if (list.length === 0 && state.items.length === 0) {
    statusEl.innerHTML = `
      <div class="empty-state">
        <div class="empty-state-title">No titles in portfolio</div>
        <div class="empty-state-body">This actress has no titles indexed yet.</div>
      </div>`;
    state.exhausted = true;
    state.loading = false;
    return;
  }

  state.items.push(...list);
  gridEl.insertAdjacentHTML('beforeend', list.map(renderTitleCard).join(''));
  state.offset += list.length;
  metaEl.textContent = `${state.items.length} loaded`;

  if (list.length < PAGE_LIMIT) {
    state.exhausted = true;
    statusEl.innerHTML = `<div class="shelf-loading">End of portfolio.</div>`;
  } else {
    statusEl.innerHTML = '';
  }
  state.loading = false;
}

export function mountActressDetail(rootEl, id) {
  if (!id) {
    rootEl.innerHTML = `
      <div class="lib-page">
        <div class="empty-state">
          <div class="empty-state-title">Missing actress ID</div>
          <div class="empty-state-body">Append <code>?id=NUMBER</code> to the URL.</div>
        </div>
      </div>`;
    return;
  }

  rootEl.innerHTML = `
    <div class="lib-page">
      <div id="hero"><div class="shelf-loading">Loading…</div></div>

      <section class="shelf" id="portfolio-section" style="margin-top:32px">
        <div class="shelf-head">
          <span class="shelf-title">Portfolio</span>
          <span class="shelf-meta" id="portfolio-meta"></span>
        </div>
        <div class="shelf-grid shelf-grid-titles" id="portfolio-grid"></div>
        <div class="grid-status" id="portfolio-status"></div>
        <div id="sentinel" style="height:1px"></div>
      </section>
    </div>
  `;

  loadAndRenderHero(rootEl, id);

  const grid     = rootEl.querySelector('#portfolio-grid');
  const status   = rootEl.querySelector('#portfolio-status');
  const meta     = rootEl.querySelector('#portfolio-meta');
  const sentinel = rootEl.querySelector('#sentinel');
  const state    = createPortfolioState();

  const io = new IntersectionObserver((entries) => {
    if (entries.some(e => e.isIntersecting)) loadPortfolio(id, grid, status, meta, state);
  }, { rootMargin: '400px' });
  io.observe(sentinel);

  loadPortfolio(id, grid, status, meta, state);
}
