// actresses/dashboard.js — Actress Dashboard mode.
//
// Fetches /api/actresses/dashboard, renders:
//   - Hero band (spotlight actress, 30s rotator) + KPI stat strip
//   - Top Groups count list + Research Gaps (side-by-side)
//   - Birthdays Today (if any)
//   - Recently Viewed strip + New Faces strip (side-by-side)
//   - Bookmarked Actresses strip
//   - Undiscovered Elites strip
//   - Forgotten Gems strip
//
// All DOM nodes use .act-* CSS classes (no legacy CSS dependencies).

import { renderActressCard } from '../cards/actress-card.js';
import { renderHeroBand }    from '../dashboard/hero-band.js';
import { renderKpiStrip }    from '../dashboard/kpi-strip.js';
import { renderTopList }     from '../dashboard/top-list.js';

const SPOTLIGHT_INTERVAL_MS = 30_000;

// ── Utils ─────────────────────────────────────────────────────────────────

function esc(s) {
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
    console.warn('[actresses/dashboard] fetch failed:', url, e);
    return fallback;
  }
}

// ── Card factory ──────────────────────────────────────────────────────────

function makeActressCard(a) {
  const el = renderActressCard(a, { variant: 'standard' });
  if (a.id) el.dataset.actressId = a.id;
  return el;
}

// ── Spotlight image resolution ────────────────────────────────────────────

/**
 * Resolve the spotlight actress's profile-pic URL (or null).
 */
function profileImageUrl(a) {
  if (a.localAvatarUrl)   return a.localAvatarUrl;
  if (a.profileImagePath) return `/api/actress-image/${encodeURIComponent(a.slug || a.id)}`;
  return null;
}

// ── Hero band (spotlight rotator) ─────────────────────────────────────────

let spotlightIntervalId = null;
let heroContainer       = null;

function stopSpotlightRotator() {
  if (spotlightIntervalId !== null) {
    clearInterval(spotlightIntervalId);
    spotlightIntervalId = null;
  }
  heroContainer = null;
}

function buildHeroElement(a) {
  const name  = a.displayName || a.canonicalName || a.stageName || a.name || a.slug || '';
  const tier  = (a.tier || '').toLowerCase();
  const count = a.titleCount != null ? `${a.titleCount} titles` : '';

  // New design: always prefer a cover as the hero image; overlay the profile
  // pic (if any) as a small crisp thumbnail. Only when there are zero covers do
  // we fall back to the tall profile-pic portrait (or a monogram).
  const profile = profileImageUrl(a);
  const covers  = a.coverUrls || [];
  const hasCovers = covers.length > 0;

  const el = renderHeroBand({
    kind:          'actress',
    eyebrow:       tier,
    eyebrowClass:  tier ? `act-tier-${tier}` : '',
    name,
    primaryImage:  hasCovers ? null    : profile,
    fallbackImages: hasCovers ? covers : [],
    overlayImage:  hasCovers ? profile : null,
    count,
    openHref:      `/v2-actress-detail.html?id=${encodeURIComponent(a.id)}`,
    dataActressId: String(a.id || ''),
  });
  return el;
}

async function rotateHero() {
  if (!heroContainer) return;
  const currentHero = heroContainer.querySelector('.dash-hero');
  const excludeId   = currentHero ? currentHero.dataset.actressId : null;
  const url = '/api/actresses/spotlight' + (excludeId ? `?exclude=${encodeURIComponent(excludeId)}` : '');
  const item = await fetchJson(url);
  if (!item) return;

  // Fade out old, fade in new
  if (currentHero) {
    currentHero.classList.add('act-spotlight-exit');
    currentHero.addEventListener('animationend', () => currentHero.remove(), { once: true });
  }
  const newHero = buildHeroElement(item);
  newHero.classList.add('act-spotlight-enter');
  heroContainer.appendChild(newHero);
  void newHero.offsetWidth;
  newHero.classList.remove('act-spotlight-enter');
}

function startSpotlightRotator(containerEl) {
  stopSpotlightRotator();
  heroContainer = containerEl;
  spotlightIntervalId = setInterval(rotateHero, SPOTLIGHT_INTERVAL_MS);
}

// ── KPI stat strip ────────────────────────────────────────────────────────

function buildKpiItems(stats) {
  if (!stats) return null;
  const researchPct = stats.researchTotal > 0
    ? Math.round((stats.researchCovered / stats.researchTotal) * 100)
    : 0;
  return [
    { value: (stats.totalActresses ?? 0).toLocaleString(), label: 'actresses' },
    { value: (stats.favorites ?? 0).toLocaleString(),      label: 'favorites' },
    { value: (stats.graded ?? 0).toLocaleString(),         label: 'graded' },
    { value: (stats.elites ?? 0).toLocaleString(),         label: 'elites' },
    { value: (stats.newThisMonth ?? 0).toLocaleString(),   label: 'new this month' },
    { value: `${researchPct}%`,                            label: 'researched' },
  ];
}

// ── Section builder ───────────────────────────────────────────────────────

function makeSection({ title, badge = null, accent = false, bordered = false }) {
  const section = document.createElement('section');
  section.className = 'act-dash-section dash-shelf'
    + (accent   ? ' act-dash-section-accent'   : '')
    + (bordered ? ' act-dash-section-bordered' : '');
  const head = document.createElement('div');
  head.className = 'act-dash-section-title';
  head.textContent = title;
  if (badge != null) {
    const b = document.createElement('span');
    b.className = 'act-dash-section-badge';
    b.textContent = badge;
    head.appendChild(b);
  }
  section.appendChild(head);
  return section;
}

function makeStrip(items, id) {
  const strip = document.createElement('div');
  strip.className = 'act-dash-strip';
  if (id) strip.id = id;
  items.forEach(a => strip.appendChild(makeActressCard(a)));
  return strip;
}

// ── Top Groups count list ─────────────────────────────────────────────────

function renderTopGroupsList(topGroups, onGroupClick) {
  const section = makeSection({ title: 'Top Groups' });
  const list = renderTopList({
    items: topGroups.map(g => ({
      name:  g.name,
      count: g.actressCount ?? 0,
      slug:  g.slug,
      // suppress sub-label for actress groups (slug shown elsewhere)
      subLabel: '',
    })),
    onClick: (item) => onGroupClick(item.slug),
  });
  section.appendChild(list);
  return section;
}

// ── Research Gaps ─────────────────────────────────────────────────────────

function renderResearchGaps(researchGaps, onActressClick) {
  const section = makeSection({ title: 'Research Gaps', badge: `${researchGaps.length}` });

  // Legend row
  const legend = document.createElement('div');
  legend.className = 'act-rg-legend';
  legend.innerHTML = `
    <span class="act-rg-legend-dot act-rg-dot-filled"></span> Profile
    <span class="act-rg-legend-dot act-rg-dot-filled"></span> Physical
    <span class="act-rg-legend-dot act-rg-dot-filled"></span> Bio
    <span class="act-rg-legend-dot act-rg-dot-filled"></span> Portfolio
  `;
  section.appendChild(legend);

  const list = document.createElement('div');
  list.className = 'act-dash-research-gaps';
  researchGaps.forEach(entry => {
    const a = entry.actress;
    const dots = [
      { filled: entry.profileFilled,    label: 'profile'   },
      { filled: entry.physicalFilled,   label: 'physical'  },
      { filled: entry.biographyFilled,  label: 'biography' },
      { filled: entry.portfolioCovered, label: 'portfolio' },
    ];
    const dotsHtml = dots.map(d =>
      `<span class="act-rg-dot ${d.filled ? 'act-rg-dot-filled' : 'act-rg-dot-empty'}" title="${d.label}: ${d.filled ? 'filled' : 'missing'}"></span>`
    ).join('');
    const row = document.createElement('div');
    row.className = 'act-rg-row';
    row.innerHTML = `
      <span class="act-rg-name">${esc(a.canonicalName)}</span>
      <span class="act-rg-tier act-tier-${esc((a.tier || '').toLowerCase())}">${esc((a.tier || '').toLowerCase())}</span>
      <span class="act-rg-dots">${dotsHtml}</span>
    `;
    row.addEventListener('click', () => onActressClick(a.id));
    list.appendChild(row);
  });
  section.appendChild(list);
  return section;
}

// ── Side-by-side panel ────────────────────────────────────────────────────

function renderSideBySide(leftEl, rightEl) {
  const panel = document.createElement('div');
  panel.className = 'act-dash-side-panel';
  if (leftEl)  { const c = document.createElement('div'); c.className = 'act-dash-side-cell'; c.appendChild(leftEl);  panel.appendChild(c); }
  if (rightEl) { const c = document.createElement('div'); c.className = 'act-dash-side-cell'; c.appendChild(rightEl); panel.appendChild(c); }
  return panel;
}

// ── Main render ───────────────────────────────────────────────────────────

/**
 * Render the actress dashboard into containerEl.
 * @param {HTMLElement} containerEl
 * @param {function(slug: string): void} onGroupClick — called when a studio group row is clicked
 */
export async function renderActressDashboard(containerEl, onGroupClick) {
  stopSpotlightRotator();
  containerEl.innerHTML = '<div class="act-dash-loading">Loading…</div>';

  const data = await fetchJson('/api/actresses/dashboard');
  if (!data) {
    containerEl.innerHTML = '<div class="act-dash-empty">Error loading dashboard.</div>';
    return;
  }

  const spotlight          = data.spotlight          || null;
  const birthdaysToday     = data.birthdaysToday     || [];
  const newFaces           = data.newFaces           || [];
  const bookmarks          = data.bookmarks          || [];
  const recentlyViewed     = data.recentlyViewed     || [];
  const undiscoveredElites = data.undiscoveredElites || [];
  const forgottenGems      = data.forgottenGems      || [];
  const topGroups          = data.topGroups          || [];
  const researchGaps       = data.researchGaps       || [];
  const libraryStats       = data.libraryStats       || null;

  const hasAny = spotlight || birthdaysToday.length || newFaces.length || bookmarks.length
              || recentlyViewed.length || undiscoveredElites.length || forgottenGems.length
              || topGroups.length || researchGaps.length;

  if (!hasAny) {
    containerEl.innerHTML = '<div class="act-dash-empty">No actresses yet — sync a volume to get started.</div>';
    return;
  }

  containerEl.innerHTML = '';

  const onActressClick = (id) => {
    window.location.href = `/v2-actress-detail.html?id=${encodeURIComponent(id)}`;
  };

  // ── Hero band (spotlight) ────────────────────────────────────────────────
  if (spotlight) {
    const heroWrap = document.createElement('div');
    heroWrap.className = 'act-dash-hero-wrap';
    heroWrap.appendChild(buildHeroElement(spotlight));
    containerEl.appendChild(heroWrap);
    setTimeout(() => startSpotlightRotator(heroWrap), SPOTLIGHT_INTERVAL_MS);
  }

  // ── KPI stat strip ────────────────────────────────────────────────────────
  if (libraryStats) {
    const items = buildKpiItems(libraryStats);
    const kpi = items ? renderKpiStrip(items) : null;
    if (kpi) containerEl.appendChild(kpi);
  }

  // ── Top Groups + Research Gaps (side-by-side when both present) ────────────
  const hasGroups = topGroups.length > 0;
  const hasGaps   = researchGaps.length > 0;
  if (hasGroups || hasGaps) {
    const leftEl  = hasGroups ? renderTopGroupsList(topGroups, onGroupClick) : null;
    const rightEl = hasGaps   ? renderResearchGaps(researchGaps, onActressClick) : null;
    if (leftEl && rightEl) {
      containerEl.appendChild(renderSideBySide(leftEl, rightEl));
    } else {
      if (leftEl)  containerEl.appendChild(leftEl);
      if (rightEl) containerEl.appendChild(rightEl);
    }
  }

  // ── Birthdays Today (hide if empty) ──────────────────────────────────────
  if (birthdaysToday.length > 0) {
    const s = makeSection({ title: 'Birthdays Today', badge: `${birthdaysToday.length}` });
    // Show all if 4+; cap at 3 only for 1–3 (no empty slots)
    const shown = birthdaysToday.length >= 4 ? birthdaysToday : birthdaysToday.slice(0, 3);
    s.appendChild(makeStrip(shown, 'act-dash-birthdays'));
    containerEl.appendChild(s);
  }

  // ── Recently Viewed + New Faces (side-by-side) ────────────────────────────
  if (recentlyViewed.length > 0 || newFaces.length > 0) {
    const rvSection = recentlyViewed.length > 0 ? (() => {
      const s = makeSection({ title: 'Recently Viewed' });
      s.appendChild(makeStrip(recentlyViewed, 'act-dash-recently-viewed'));
      return s;
    })() : null;
    const nfSection = newFaces.length > 0 ? (() => {
      const s = makeSection({ title: 'New Faces' });
      s.appendChild(makeStrip(newFaces, 'act-dash-new-faces'));
      return s;
    })() : null;
    containerEl.appendChild(renderSideBySide(rvSection, nfSection));
  }

  // ── Bookmarked ────────────────────────────────────────────────────────────
  if (bookmarks.length > 0) {
    const s = makeSection({ title: 'Bookmarked Actresses', accent: true, bordered: true });
    s.appendChild(makeStrip(bookmarks, 'act-dash-bookmarks'));
    containerEl.appendChild(s);
  }

  // ── Undiscovered Elites ───────────────────────────────────────────────────
  if (undiscoveredElites.length > 0) {
    const s = makeSection({ title: 'Undiscovered Elites', bordered: true });
    s.appendChild(makeStrip(undiscoveredElites, 'act-dash-undiscovered'));
    containerEl.appendChild(s);
  }

  // ── Forgotten Gems ────────────────────────────────────────────────────────
  if (forgottenGems.length > 0) {
    const s = makeSection({ title: 'Forgotten Gems' });
    s.appendChild(makeStrip(forgottenGems, 'act-dash-forgotten-gems'));
    containerEl.appendChild(s);
  }
}
