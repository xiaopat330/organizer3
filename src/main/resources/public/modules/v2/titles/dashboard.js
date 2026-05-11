/* ─────────────────────────────────────────────────────────────────────
   Titles v2 — Dashboard mode
   Fetches /api/titles/dashboard and renders all dashboard sections.
   Spotlight rotator cycles every 30 s using /api/titles/spotlight.
   ───────────────────────────────────────────────────────────────────── */

import { renderTitleCard } from '../cards/title-card.js';
import { renderHeroBand }  from '../dashboard/hero-band.js';
import { renderKpiStrip }  from '../dashboard/kpi-strip.js';
import { renderTopList }   from '../dashboard/top-list.js';

const SPOTLIGHT_INTERVAL_MS = 30_000;

// ── Utils ─────────────────────────────────────────────────────────────────
function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

// ── Card factories ────────────────────────────────────────────────────────
function makeCard(t) {
  const el = renderTitleCard(t, { variant: 'standard', showActress: true });
  el.classList.add('tit-dash-card');
  return el;
}

function makeCompactCard(t) {
  const el = renderTitleCard(t, { variant: 'compact', showActress: true });
  el.classList.add('tit-dash-card', 'tit-dash-card--compact');
  return el;
}

function makeCardWithAging(t) {
  const el = renderTitleCard(t, { variant: 'standard', aging: true, showActress: true });
  el.classList.add('tit-dash-card');
  return el;
}

// ── Spotlight hero rotator ────────────────────────────────────────────────

function buildHeroElement(t) {
  const code  = t.code || '';
  const name  = t.titleEnglish || t.titleOriginalEn || t.titleOriginal || '';
  const cast  = t.actressName || (t.actresses && t.actresses.length ? t.actresses[0].name : '');
  const year  = t.releaseDate ? String(t.releaseDate).slice(0, 4)
              : t.addedDate   ? String(t.addedDate).slice(0, 4) : '';
  const countParts = [cast, year].filter(Boolean);

  const el = renderHeroBand({
    kind:          'title',
    eyebrow:       code,
    // suppress name when it equals the code
    name:          name !== code ? name : '',
    primaryImage:  t.coverUrl || null,
    fallbackImages: [],
    count:         countParts.join(' · '),
    openHref:      `/v2-title-detail.html?code=${encodeURIComponent(code)}`,
    dataCode:      code,
  });
  return el;
}

function createSpotlightRotator() {
  let intervalId = null;
  let containerEl = null;

  async function rotate() {
    if (!containerEl) return;
    const currentHero = containerEl.querySelector('.dash-hero');
    const excludeCode = currentHero ? (currentHero.dataset.code || null) : null;
    const url = '/api/titles/spotlight' + (excludeCode ? `?exclude=${excludeCode}` : '');
    try {
      const res = await fetch(url, { cache: 'no-cache' });
      if (!res.ok || res.status === 204) return;
      const t = await res.json();
      const newHero = buildHeroElement(t);
      newHero.classList.add('tit-spotlight-enter');
      if (currentHero) {
        currentHero.classList.add('tit-spotlight-exit');
        currentHero.addEventListener('animationend', () => currentHero.remove(), { once: true });
      }
      containerEl.appendChild(newHero);
      void newHero.offsetWidth;
      newHero.classList.remove('tit-spotlight-enter');
    } catch (_) { /* network error — skip */ }
  }

  return {
    start(el) {
      if (intervalId) clearInterval(intervalId);
      containerEl = el;
      intervalId = setInterval(rotate, SPOTLIGHT_INTERVAL_MS);
    },
    stop() {
      if (intervalId) { clearInterval(intervalId); intervalId = null; }
      containerEl = null;
    },
  };
}

export const spotlightRotator = createSpotlightRotator();

// ── Section + strip builders ──────────────────────────────────────────────
function makeSection({ title, badge = null, accent = false, bordered = false }) {
  const sec = document.createElement('section');
  sec.className = 'tit-dash-section dash-shelf'
    + (accent   ? ' tit-dash-section--accent'   : '')
    + (bordered ? ' tit-dash-section--bordered' : '');
  const hdr = document.createElement('div');
  hdr.className = 'tit-dash-section-title';
  hdr.textContent = title;
  if (badge) {
    const b = document.createElement('span');
    b.className = 'tit-dash-section-badge';
    b.textContent = badge;
    hdr.appendChild(b);
  }
  sec.appendChild(hdr);
  return sec;
}

function makeStrip(items, cardFactory, fullWidth = false) {
  const grid = document.createElement('div');
  grid.className = 'tit-dash-strip' + (fullWidth ? ' tit-dash-strip--full' : '');
  items.forEach(t => grid.appendChild(cardFactory(t)));
  return grid;
}

// ── Top Labels leaderboard — uses shared renderTopList ────────────────────
function renderTopLabels(topLabels, onLabelClick) {
  const sec = makeSection({ title: 'Top Labels' });
  const items = topLabels.slice(0, 5).map(lbl => ({
    // Show canonical label name; code as sub-label
    name:     lbl.labelName || lbl.code || '',
    count:    lbl.score || 0,
    slug:     lbl.code || '',
    subLabel: lbl.code || '',
  }));
  const list = renderTopList({
    items,
    onClick: (item) => onLabelClick(item.slug),
  });
  sec.appendChild(list);
  return sec;
}

// ── Library stats — compressed KPI strip ──────────────────────────────────
function buildLibraryKpiItems(stats) {
  const unseenPct = stats.totalTitles > 0
    ? Math.round((stats.unseen / stats.totalTitles) * 100) : 0;
  return [
    { value: stats.totalTitles.toLocaleString(),   label: 'titles' },
    { value: stats.totalLabels.toLocaleString(),   label: 'labels' },
    { value: stats.unseen.toLocaleString(),         label: 'unseen' },
    { value: `${unseenPct}%`,                       label: 'unseen %' },
    { value: stats.addedThisMonth.toLocaleString(), label: 'added this month' },
    { value: stats.addedThisYear.toLocaleString(),  label: 'added this year' },
  ];
}

// ── On This Day — year eyebrow wrapper ───────────────────────────────────
function makeOnThisDayCard(t) {
  const year = t.releaseDate ? String(t.releaseDate).slice(0, 4)
             : t.addedDate   ? String(t.addedDate).slice(0, 4) : '';
  const currentYear = new Date().getFullYear();
  const yearsAgo = year ? currentYear - parseInt(year, 10) : null;
  const agoText = yearsAgo != null && yearsAgo > 0 ? `${yearsAgo}yr ago` : '';

  const wrap = document.createElement('div');
  wrap.className = 'tit-dash-otd-card-wrap';

  if (year) {
    const eyebrow = document.createElement('div');
    eyebrow.className = 'tit-dash-otd-eyebrow';
    eyebrow.innerHTML = `${esc(year)}${agoText ? ` <span class="tit-dash-otd-ago">· ${esc(agoText)}</span>` : ''}`;
    wrap.appendChild(eyebrow);
  }

  wrap.appendChild(makeCompactCard(t));
  return wrap;
}

// ── Main render ───────────────────────────────────────────────────────────
export async function renderDashboard(containerEl, rootEl) {
  spotlightRotator.stop();
  containerEl.innerHTML = '<div class="tit-dash-loading">Loading dashboard…</div>';

  let data;
  try {
    const res = await fetch('/api/titles/dashboard', { cache: 'no-cache' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    data = await res.json();
  } catch (err) {
    containerEl.innerHTML = '<div class="tit-dash-empty">Error loading dashboard.</div>';
    console.error('[titles/dashboard]', err);
    return;
  }

  const onDeck             = data.onDeck             || [];
  const justAdded          = data.justAdded          || [];
  const fromFavoriteLabels = data.fromFavoriteLabels || [];
  const recentlyViewed     = data.recentlyViewed     || [];
  const spotlight          = data.spotlight          || null;
  const forgottenAttic     = data.forgottenAttic     || [];
  const forgottenFavorites = data.forgottenFavorites || [];
  const onThisDay          = data.onThisDay          || [];
  const topLabels          = data.topLabels          || [];
  const libraryStats       = data.libraryStats       || null;

  const hasAny = onDeck.length || justAdded.length || fromFavoriteLabels.length
              || recentlyViewed.length || spotlight || forgottenAttic.length
              || forgottenFavorites.length || onThisDay.length || topLabels.length;

  if (!hasAny) {
    containerEl.innerHTML = '<div class="tit-dash-empty">No titles yet — sync a volume to get started.</div>';
    return;
  }

  containerEl.innerHTML = '';

  // ── A. Hero band (replaces old card spotlight) ────────────────────────────
  if (spotlight) {
    const heroWrap = document.createElement('div');
    heroWrap.className = 'act-dash-hero-wrap';
    heroWrap.appendChild(buildHeroElement(spotlight));
    containerEl.appendChild(heroWrap);
    // Start rotator after initial interval
    setTimeout(() => spotlightRotator.start(heroWrap), SPOTLIGHT_INTERVAL_MS);
  }

  // ── B. KPI strip (replaces 6-tile grid) ──────────────────────────────────
  if (libraryStats) {
    const kpi = renderKpiStrip(buildLibraryKpiItems(libraryStats));
    if (kpi) containerEl.appendChild(kpi);
  }

  // ── Top panel: Top Labels + On This Day (side-by-side) ───────────────────
  if (topLabels.length > 0 || onThisDay.length > 0) {
    const topPanel = document.createElement('div');
    topPanel.className = 'tit-dash-top-panel';

    if (topLabels.length > 0) {
      const left = document.createElement('div');
      left.className = 'tit-dash-top-left';

      // C. TOP LABELS — use renderTopList (no bar chart, no name duplication)
      const onLabelClick = (code) => {
        const searchInput = rootEl.querySelector('#tit-library-code');
        if (searchInput) {
          searchInput.value = code + '-';
          searchInput.dispatchEvent(new Event('input', { bubbles: true }));
          searchInput.focus();
        }
      };
      left.appendChild(renderTopLabels(topLabels, onLabelClick));
      topPanel.appendChild(left);
    }

    if (onThisDay.length > 0) {
      const right = document.createElement('div');
      right.className = 'tit-dash-top-right';

      // F. ON THIS DAY — year eyebrow above each card
      const shown = onThisDay.slice(0, 4);
      const sec = makeSection({
        title: 'On This Day',
        badge: `${onThisDay.length} memor${onThisDay.length === 1 ? 'y' : 'ies'}`,
      });
      const strip = document.createElement('div');
      strip.className = 'tit-dash-strip';
      shown.forEach(t => strip.appendChild(makeOnThisDayCard(t)));
      sec.appendChild(strip);
      right.appendChild(sec);
      topPanel.appendChild(right);
    }

    containerEl.appendChild(topPanel);
  }

  // ── D. RECENTLY VIEWED — full-width shelf ─────────────────────────────────
  if (recentlyViewed.length > 0) {
    const sec = makeSection({ title: 'Recently Viewed' });
    sec.appendChild(makeStrip(recentlyViewed, makeCompactCard, true));
    containerEl.appendChild(sec);
  }

  // ── D. JUST ADDED — full-width shelf ──────────────────────────────────────
  if (justAdded.length > 0) {
    const sec = makeSection({ title: 'Just Added' });
    sec.appendChild(makeStrip(justAdded, makeCardWithAging, true));
    containerEl.appendChild(sec);
  }

  // ── E. Bookmarked Selections (onDeck) — showActress: true ────────────────
  if (onDeck.length > 0) {
    const sec = makeSection({ title: 'Bookmarked Selections', accent: true, bordered: true });
    sec.appendChild(makeStrip(onDeck, makeCompactCard));
    containerEl.appendChild(sec);
  }

  // ── From Favorite Labels ──────────────────────────────────────────────────
  if (fromFavoriteLabels.length > 0) {
    const sec = makeSection({ title: 'From Favorite Labels', bordered: true });
    sec.appendChild(makeStrip(fromFavoriteLabels, makeCardWithAging));
    containerEl.appendChild(sec);
  }

  // ── Forgotten Attic ──────────────────────────────────────────────────────
  if (forgottenAttic.length > 0) {
    const sec = makeSection({ title: 'Forgotten Attic', bordered: true });
    sec.appendChild(makeStrip(forgottenAttic, makeCard));
    containerEl.appendChild(sec);
  }

  // ── Forgotten Favorites ──────────────────────────────────────────────────
  if (forgottenFavorites.length > 0) {
    const sec = makeSection({ title: 'Forgotten Favorites' });
    sec.appendChild(makeStrip(forgottenFavorites, makeCard));
    containerEl.appendChild(sec);
  }
}
