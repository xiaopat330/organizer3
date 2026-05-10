/* ─────────────────────────────────────────────────────────────────────
   Wave 2 — Home (library mode, 4 shelves)
   Spec: spec/DESIGN_SYSTEM_PAGES.md §1.1
   Shelves: Recently viewed · Recently added · Favorites · Needs attention
   ───────────────────────────────────────────────────────────────────── */

const COVER_ROOT = '/covers';

async function fetchJson(url, fallback = null) {
  try {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) return fallback;
    return await r.json();
  } catch (e) {
    console.warn('[home] fetch failed:', url, e);
    return fallback;
  }
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[c]));
}

function timeAgo(iso) {
  if (!iso) return '';
  const then = new Date(iso).getTime();
  const diff = (Date.now() - then) / 1000;
  if (diff < 60)        return `${Math.floor(diff)}s ago`;
  if (diff < 3600)      return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400)     return `${Math.floor(diff / 3600)}h ago`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)}d ago`;
  return new Date(iso).toLocaleDateString();
}

/* ── Shelf 1 — Recently viewed ─────────────────────────────────────── */
async function renderRecentlyViewed(slot) {
  const items = await fetchJson('/api/watch-history?limit=12', []);
  if (!items || items.length === 0) {
    slot.innerHTML = `<div class="shelf-empty">Nothing watched yet — your recent titles will appear here.</div>`;
    return;
  }
  slot.innerHTML = `<div class="shelf-grid shelf-grid-chips">${
    items.map(it => `
      <a class="title-chip" href="#title/${encodeURIComponent(it.titleCode)}" title="${escapeHtml(it.titleCode)}">
        <span>${escapeHtml(it.titleCode)}</span>
        <span class="title-chip-when">${escapeHtml(timeAgo(it.watchedAt))}</span>
      </a>
    `).join('')
  }</div>`;
}

/* ── Shelf 2 — Recently added (titles) ─────────────────────────────── */
async function renderRecentlyAdded(slot) {
  const titles = await fetchJson('/api/titles?sort=addedDate&order=desc&limit=12', []);
  if (!titles || titles.length === 0) {
    slot.innerHTML = `<div class="shelf-empty">No titles added recently.</div>`;
    return;
  }
  slot.innerHTML = `<div class="shelf-grid shelf-grid-titles">${
    titles.map(t => renderTitleCard(t)).join('')
  }</div>`;
}

function renderTitleCard(t) {
  const code   = t.code || t.productCode || t.titleCode || '';
  const name   = t.normalizedTitle || t.titleEn || t.titleJa || t.title || code;
  const cover  = t.coverPath
    ? `${COVER_ROOT}/${encodeURIComponent(t.coverPath)}`
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

/* ── Shelf 3 — Favorites (actresses) ───────────────────────────────── */
async function renderFavorites(slot) {
  const actresses = await fetchJson('/api/actresses?favorites=true&limit=12', []);
  if (!actresses || actresses.length === 0) {
    slot.innerHTML = `<div class="shelf-empty">No favorited actresses yet — star one to pin it here.</div>`;
    return;
  }
  slot.innerHTML = `<div class="shelf-grid shelf-grid-actress">${
    actresses.map(a => renderActressCard(a)).join('')
  }</div>`;
}

function renderActressCard(a) {
  const name = a.displayName || a.name || a.slug || '';
  const tier = (a.tier || '').toLowerCase();
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

/* ── Shelf 4 — Needs attention (KPI tiles) ─────────────────────────── */
async function renderNeedsAttention(slot) {
  const [trans, dups, trash] = await Promise.all([
    fetchJson('/api/translation/stats', null),
    fetchJson('/api/tools/duplicates?limit=1&offset=0', null),
    fetchJson('/api/utilities/trash/volumes', []),
  ]);

  // Translation: stats response shape has counts per state. Pick the most useful pair.
  const transPending = trans?.pending ?? trans?.queued ?? 0;
  const transFailed  = trans?.failed ?? 0;
  const transValue   = transPending + transFailed;
  const transClass   = transFailed > 0 ? 'warn' : (transPending > 0 ? 'warn' : 'ok');

  // Duplicates: response is paged; total may be in `total` field. If not, just show the count we got.
  const dupTotal = dups?.total ?? (Array.isArray(dups) ? dups.length : (dups?.items?.length ?? 0));
  const dupClass = dupTotal > 0 ? 'warn' : 'ok';

  // Trash: sum counts across volumes.
  let trashTotal = 0;
  if (Array.isArray(trash)) {
    const counts = await Promise.all(
      trash.map(v => fetchJson(`/api/utilities/trash/volumes/${encodeURIComponent(v.id)}/count`, { count: 0 }))
    );
    trashTotal = counts.reduce((s, c) => s + (c?.count ?? 0), 0);
  }
  const trashClass = trashTotal > 0 ? 'warn' : 'ok';

  // Sync state — placeholder until a sync-status endpoint exists.
  const syncValue = 'idle';
  const syncClass = '';

  slot.innerHTML = `
    <div class="shelf-grid shelf-grid-tiles">
      <a class="kpi-tile" href="#translation">
        <div class="kpi-tile-head">
          <svg viewBox="0 0 24 24"><path d="M5 8l6 6 6-6"/><path d="M5 16h14"/></svg>
          Translation
        </div>
        <div class="kpi-tile-value ${transClass}">${transValue}</div>
        <div class="kpi-tile-meta">${transPending} pending${transFailed ? ` · ${transFailed} failed` : ''}</div>
      </a>
      <a class="kpi-tile" href="#duplicates">
        <div class="kpi-tile-head">
          <svg viewBox="0 0 24 24"><rect x="3" y="3" width="13" height="13" rx="2"/><rect x="8" y="8" width="13" height="13" rx="2"/></svg>
          Duplicates
        </div>
        <div class="kpi-tile-value ${dupClass}">${dupTotal}</div>
        <div class="kpi-tile-meta">groups awaiting triage</div>
      </a>
      <a class="kpi-tile" href="#trash">
        <div class="kpi-tile-head">
          <svg viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          Trash
        </div>
        <div class="kpi-tile-value ${trashClass}">${trashTotal}</div>
        <div class="kpi-tile-meta">items pending sweep</div>
      </a>
      <a class="kpi-tile" href="#volumes">
        <div class="kpi-tile-head">
          <svg viewBox="0 0 24 24"><path d="M3 12a9 9 0 0 1 15-6.7L21 8"/><polyline points="21 3 21 8 16 8"/></svg>
          Sync
        </div>
        <div class="kpi-tile-value ${syncClass}">${syncValue}</div>
        <div class="kpi-tile-meta">no active job</div>
      </a>
    </div>
  `;
}

/* ── Bootstrap ──────────────────────────────────────────────────────── */
export function mountHome(rootEl) {
  rootEl.innerHTML = `
    <div class="lib-page">
      <h1 class="lib-page-title">Home</h1>
      <div class="lib-page-subtitle">Your library at a glance.</div>

      <section class="shelf">
        <div class="shelf-head">
          <span class="shelf-title">Recently viewed</span>
          <span class="shelf-meta" id="shelf-recent-meta"></span>
        </div>
        <div id="shelf-recent"><div class="shelf-loading">Loading…</div></div>
      </section>

      <section class="shelf">
        <div class="shelf-head">
          <span class="shelf-title">Recently added</span>
          <a class="shelf-action" href="#titles">All titles
            <svg viewBox="0 0 24 24"><path d="M9 18l6-6-6-6"/></svg>
          </a>
        </div>
        <div id="shelf-added"><div class="shelf-loading">Loading…</div></div>
      </section>

      <section class="shelf">
        <div class="shelf-head">
          <span class="shelf-title">Favorites</span>
          <a class="shelf-action" href="#actresses">All actresses
            <svg viewBox="0 0 24 24"><path d="M9 18l6-6-6-6"/></svg>
          </a>
        </div>
        <div id="shelf-favorites"><div class="shelf-loading">Loading…</div></div>
      </section>

      <section class="shelf">
        <div class="shelf-head">
          <span class="shelf-title">Needs attention</span>
          <span class="shelf-meta">tools · backlogs</span>
        </div>
        <div id="shelf-attention"><div class="shelf-loading">Loading…</div></div>
      </section>
    </div>
  `;

  // Fire all four in parallel; each shelf renders independently as its data lands.
  renderRecentlyViewed(rootEl.querySelector('#shelf-recent'));
  renderRecentlyAdded(rootEl.querySelector('#shelf-added'));
  renderFavorites(rootEl.querySelector('#shelf-favorites'));
  renderNeedsAttention(rootEl.querySelector('#shelf-attention'));
}
