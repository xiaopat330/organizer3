/* ─────────────────────────────────────────────────────────────────────
   Wave 2 — Home (library mode, 4 shelves + hero + stats)
   Spec: spec/DESIGN_SYSTEM_PAGES.md §1.1
   Shelves: Recently viewed · Recently added · Favorites · Needs attention
   ───────────────────────────────────────────────────────────────────── */

import { renderTitleCard }   from '/modules/v2/cards/title-card.js';
import { renderActressCard } from '/modules/v2/cards/actress-card.js';
import { renderHeroBand }    from '/modules/v2/dashboard/hero-band.js';
import { renderKpiStrip }    from '/modules/v2/dashboard/kpi-strip.js';

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

/* ── Shelf 1 — Recently viewed (compact thumb row) ──────────────────── */
async function renderRecentlyViewed(slot, dashItems) {
  // Use the recentlyViewed titles from the dashboard payload (includes coverUrl)
  const items = dashItems && dashItems.length ? dashItems : [];
  if (items.length === 0) {
    slot.innerHTML = `<div class="shelf-empty">Nothing watched yet — your recent titles will appear here.</div>`;
    return;
  }
  slot.innerHTML = `<div class="shelf-grid shelf-grid-chips home-thumb-row">${
    items.map(it => {
      const code  = it.code || '';
      const cover = it.coverUrl || null;
      const name  = it.titleEnglish || it.titleOriginalEn || it.titleOriginal || code;
      return `
        <a class="home-thumb-chip" href="/v2-title-detail.html?code=${encodeURIComponent(code)}"
           title="${escapeHtml(code + (name && name !== code ? ' — ' + name : ''))}">
          <div class="home-thumb-chip-cover"
               style="${cover ? `background-image:url('${escapeHtml(cover)}');background-size:cover;background-position:right center` : ''}"></div>
        </a>
      `;
    }).join('')
  }</div>`;
}

/* ── Shelf 2 — Recently added (titles) ─────────────────────────────── */
async function renderRecentlyAdded(slot, justAdded) {
  if (!justAdded || justAdded.length === 0) {
    slot.innerHTML = `<div class="shelf-empty">No titles added recently.</div>`;
    return;
  }
  const grid = document.createElement('div');
  grid.className = 'shelf-grid shelf-grid-titles';
  justAdded.forEach(t => grid.appendChild(renderTitleCard(t)));
  slot.innerHTML = '';
  slot.appendChild(grid);
}

/* ── Shelf 3 — Favorites (actresses) ───────────────────────────────── */
async function renderFavorites(slot) {
  const actresses = await fetchJson('/api/actresses?favorites=true&limit=12', []);
  if (!actresses || actresses.length === 0) {
    slot.innerHTML = `<div class="shelf-empty">No favorited actresses yet — star one to pin it here.</div>`;
    return;
  }
  const grid = document.createElement('div');
  grid.className = 'shelf-grid shelf-grid-actress';
  actresses.forEach(a => grid.appendChild(renderActressCard(a)));
  slot.innerHTML = '';
  slot.appendChild(grid);
}

/* ── Shelf 4 — Needs attention (KPI tiles, each rendered independently) */
async function renderNeedsAttention(slot) {
  // Render placeholder tiles immediately; fill each independently.
  slot.innerHTML = `
    <div class="shelf-grid shelf-grid-tiles" id="home-kpi-grid">
      <a class="kpi-tile" href="#translation" id="kpi-translation">
        <div class="kpi-tile-head">
          <svg viewBox="0 0 24 24"><path d="M5 8l6 6 6-6"/><path d="M5 16h14"/></svg>
          Translation
        </div>
        <div class="kpi-tile-value" id="kpi-trans-value">…</div>
        <div class="kpi-tile-meta" id="kpi-trans-meta">loading</div>
      </a>
      <a class="kpi-tile" href="#duplicates" id="kpi-duplicates">
        <div class="kpi-tile-head">
          <svg viewBox="0 0 24 24"><rect x="3" y="3" width="13" height="13" rx="2"/><rect x="8" y="8" width="13" height="13" rx="2"/></svg>
          Duplicates
        </div>
        <div class="kpi-tile-value" id="kpi-dup-value">…</div>
        <div class="kpi-tile-meta" id="kpi-dup-meta">loading</div>
      </a>
      <a class="kpi-tile" href="#trash" id="kpi-trash">
        <div class="kpi-tile-head">
          <svg viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          Trash
        </div>
        <div class="kpi-tile-value" id="kpi-trash-value">…</div>
        <div class="kpi-tile-meta" id="kpi-trash-meta">loading</div>
      </a>
      <a class="kpi-tile" href="#volumes" id="kpi-sync">
        <div class="kpi-tile-head">
          <svg viewBox="0 0 24 24"><path d="M3 12a9 9 0 0 1 15-6.7L21 8"/><polyline points="21 3 21 8 16 8"/></svg>
          Sync
        </div>
        <div class="kpi-tile-value" id="kpi-sync-value">idle</div>
        <div class="kpi-tile-meta" id="kpi-sync-meta">no active job</div>
      </a>
    </div>
  `;

  // Translation — fill independently
  fetchJson('/api/translation/stats', null).then(trans => {
    const pending = trans?.pending ?? trans?.queued ?? 0;
    const failed  = trans?.failed ?? 0;
    const total   = pending + failed;
    const cls     = (failed > 0 || pending > 0) ? 'warn' : 'ok';
    const el = document.getElementById('kpi-trans-value');
    const meta = document.getElementById('kpi-trans-meta');
    if (el) { el.textContent = total; el.className = `kpi-tile-value ${cls}`; }
    if (meta) meta.textContent = total === 0
      ? 'caught up'
      : `${pending} pending${failed ? ` · ${failed} failed` : ''}`;
  });

  // Duplicates — fill independently
  fetchJson('/api/tools/duplicates?limit=1&offset=0', null).then(dups => {
    const total = dups?.total ?? (Array.isArray(dups) ? dups.length : (dups?.items?.length ?? 0));
    const cls   = total > 0 ? 'warn' : 'ok';
    const el    = document.getElementById('kpi-dup-value');
    const meta  = document.getElementById('kpi-dup-meta');
    if (el) { el.textContent = total; el.className = `kpi-tile-value ${cls}`; }
    if (meta) meta.textContent = 'groups awaiting triage';
  });

  // Trash — fetch volumes list, then sum counts (each count fetched independently,
  // timeout-guarded so an offline volume doesn't stall the tile).
  fetchJson('/api/utilities/trash/volumes', []).then(async volumes => {
    if (!Array.isArray(volumes) || volumes.length === 0) {
      const el = document.getElementById('kpi-trash-value');
      if (el) { el.textContent = 0; el.className = 'kpi-tile-value ok'; }
      const meta = document.getElementById('kpi-trash-meta');
      if (meta) meta.textContent = 'items pending sweep';
      return;
    }
    // Fetch each volume count with a 5-second timeout
    const withTimeout = (p, ms) => Promise.race([
      p,
      new Promise(res => setTimeout(() => res({ count: 0 }), ms))
    ]);
    const counts = await Promise.all(
      volumes.map(v =>
        withTimeout(
          fetchJson(`/api/utilities/trash/volumes/${encodeURIComponent(v.id)}/count`, { count: 0 }),
          5000
        )
      )
    );
    const total = Math.max(0, counts.reduce((s, c) => s + (c?.count ?? 0), 0));
    const cls   = total > 0 ? 'warn' : 'ok';
    const el    = document.getElementById('kpi-trash-value');
    const meta  = document.getElementById('kpi-trash-meta');
    if (el) { el.textContent = total; el.className = `kpi-tile-value ${cls}`; }
    if (meta) meta.textContent = 'items pending sweep';
  });
}

/* ── Hero band — freshest recently-added title ──────────────────────── */
function renderHero(heroEl, t) {
  if (!t) {
    heroEl.style.display = 'none';
    return;
  }
  const code  = t.code || '';
  const name  = t.titleEnglish || t.titleOriginalEn || t.titleOriginal || code;
  const cast  = t.actressName || (t.actresses && t.actresses.length ? t.actresses[0].name : '');
  const year  = t.releaseDate ? String(t.releaseDate).slice(0, 4)
              : t.addedDate   ? String(t.addedDate).slice(0, 4) : '';
  const grade = t.grade || '';
  const gradeHtml = grade
    ? `<span class="grade-badge grade-${escapeHtml(grade.charAt(0))}">${escapeHtml(grade)}</span>`
    : '';

  // Build count line: "actress · year"
  const countParts = [cast, year].filter(Boolean);
  const countStr = countParts.join(' · ');

  const heroSection = renderHeroBand({
    kind:          'title',
    eyebrow:       code,
    name:          name !== code ? name : '',
    primaryImage:  t.coverUrl || null,
    fallbackImages: [],
    count:         countStr,
    badgeHtml:     gradeHtml,
    openHref:      `/v2-title-detail.html?code=${encodeURIComponent(code)}`,
    dataCode:      code,
  });

  heroEl.innerHTML = '';
  heroEl.appendChild(heroSection);
}

/* ── Stats line — totalTitles · totalActresses · N volumes online ──── */
function renderStats(statsEl, totalTitles, actressDash, volumesDash) {
  const titleCount   = totalTitles != null ? totalTitles.toLocaleString() : '–';
  const actressCount = actressDash?.libraryStats?.totalActresses != null
    ? actressDash.libraryStats.totalActresses.toLocaleString() : '–';
  const onlineVolumes = Array.isArray(volumesDash)
    ? volumesDash.filter(v => v.status === 'online').length : '–';

  // Replace placeholder with a styled KPI strip.
  // Remove the lib-page-subtitle outer margin so the strip's own spacing wins.
  const kpi = renderKpiStrip([
    { value: titleCount,            label: 'titles' },
    { value: actressCount,          label: 'actresses' },
    { value: String(onlineVolumes), label: 'volumes online' },
  ]);
  if (kpi) {
    statsEl.className = '';   // neutralize lib-page-subtitle spacing
    statsEl.style.margin = '0';
    statsEl.innerHTML = '';
    statsEl.appendChild(kpi);
  } else {
    statsEl.textContent = `${titleCount} titles · ${actressCount} actresses · ${onlineVolumes} volumes online`;
  }
}

/* ── Bootstrap ──────────────────────────────────────────────────────── */
export function mountHome(rootEl) {
  rootEl.innerHTML = `
    <div class="lib-page">
      <h1 class="lib-page-title">Home</h1>
      <div class="lib-page-subtitle" id="home-stats-line">Loading stats…</div>

      <div id="home-hero"></div>

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

  const heroEl       = rootEl.querySelector('#home-hero');
  const statsEl      = rootEl.querySelector('#home-stats-line');
  const recentSlot   = rootEl.querySelector('#shelf-recent');
  const addedSlot    = rootEl.querySelector('#shelf-added');
  const favSlot      = rootEl.querySelector('#shelf-favorites');
  const attentionSlot = rootEl.querySelector('#shelf-attention');

  // Titles dashboard — drives hero, recently-added shelf, recently-viewed thumb row, and totalTitles stat.
  const titleDashP = fetchJson('/api/titles/dashboard', null);
  // Actress dashboard — drives totalActresses stat.
  const actressDashP = fetchJson('/api/actresses/dashboard', null);
  // Volumes — drives volumes-online stat.
  const volumesP = fetchJson('/api/utilities/volumes', []);

  // Hero + recently-added shelf + recently-viewed (all from title dashboard)
  titleDashP.then(dash => {
    const justAdded     = dash?.justAdded     || [];
    const recentlyViewed = dash?.recentlyViewed || [];
    const totalTitles   = dash?.libraryStats?.totalTitles ?? null;

    renderHero(heroEl, justAdded[0] || null);
    renderRecentlyAdded(addedSlot, justAdded);
    renderRecentlyViewed(recentSlot, recentlyViewed);

    // Populate stats once we have actress and volume data too
    Promise.all([actressDashP, volumesP]).then(([actressDash, volumesDash]) => {
      renderStats(statsEl, totalTitles, actressDash, volumesDash);
    });
  });

  // Favorites shelf (independent)
  renderFavorites(favSlot);

  // Needs attention (independent, tiles fill as data lands)
  renderNeedsAttention(attentionSlot);
}
