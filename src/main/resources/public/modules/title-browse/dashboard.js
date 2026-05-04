// Title dashboard render.
//
// Exports:
//   renderTitleDashboard(state, titleDashboardEl)

import { makeTitleCard, makeCompactTitleCard } from '../cards.js';
import { agingLabel } from '../cards.js';
import {
  renderDashboardStrip,
  renderDashboardSection,
  renderSideBySidePanel,
  createSpotlightRotator,
} from '../dashboard-panels.js';
import {
  renderTopLabelsLeaderboard,
  renderTitleLibraryStats,
} from '../dashboard-renderers.js';

// ── Spotlight rotator ─────────────────────────────────────────────────────

const SPOTLIGHT_INTERVAL_MS = 30_000;
export const titleSpotlightRotator = createSpotlightRotator({
  endpoint: '/api/titles/spotlight',
  excludeAttr: 'code',
  makeCard: t => {
    const card = makeTitleCard(t);
    card.classList.add('card-spotlight');
    return card;
  },
  intervalMs: SPOTLIGHT_INTERVAL_MS,
});

// ── Helpers ───────────────────────────────────────────────────────────────

function makeTitleCardWithAging(t) {
  const card = makeTitleCard(t);
  const label = agingLabel(t.addedDate);
  if (label) {
    const badge = document.createElement('div');
    badge.className = 'title-card-aging';
    badge.textContent = label;
    const coverWrap = card.querySelector('.cover-wrap');
    (coverWrap || card).appendChild(badge);
  }
  return card;
}

function renderTopLabelsSection(topLabels) {
  return renderTopLabelsLeaderboard(topLabels, {
    onRowClick: (lbl) => {
      const searchInput = document.getElementById('search-input');
      if (searchInput) {
        searchInput.value = lbl.code + '-';
        searchInput.dispatchEvent(new Event('input'));
        searchInput.focus();
      }
    },
  });
}

function renderTopInfoPanel(spotlight, topLabels, libraryStats, onThisDay) {
  const panel = document.createElement('div');
  panel.className = 'dashboard-top-panel';

  if (spotlight) {
    const left = document.createElement('div');
    left.className = 'dashboard-top-panel-left';
    const header = document.createElement('div');
    header.className = 'dashboard-section-title';
    header.textContent = 'Spotlight';
    left.appendChild(header);
    const card = makeTitleCard(spotlight);
    card.classList.add('card-spotlight');
    left.appendChild(card);
    panel.appendChild(left);
    setTimeout(() => titleSpotlightRotator.start(left), SPOTLIGHT_INTERVAL_MS);
  }

  if (topLabels.length > 0 || libraryStats || onThisDay.length > 0) {
    const right = document.createElement('div');
    right.className = 'dashboard-top-panel-right';

    if (topLabels.length > 0 || libraryStats) {
      const upper = document.createElement('div');
      upper.className = 'dashboard-top-right-upper';
      if (topLabels.length > 0) upper.appendChild(renderTopLabelsSection(topLabels));
      if (libraryStats)         upper.appendChild(renderTitleLibraryStats(libraryStats));
      right.appendChild(upper);
    }

    if (onThisDay.length > 0) {
      const shown = onThisDay.slice(0, 3);
      const strip = renderDashboardStrip(shown, { id: 'dash-on-this-day', cardFactory: makeCompactTitleCard });
      right.appendChild(renderDashboardSection({
        title: 'On This Day',
        badge: `${onThisDay.length} memor${onThisDay.length === 1 ? 'y' : 'ies'}`,
        body: strip,
      }));
    }

    panel.appendChild(right);
  }

  return panel;
}

// ── Main render ───────────────────────────────────────────────────────────

export async function renderTitleDashboard(titleDashboardEl) {
  titleDashboardEl.innerHTML = '<div class="dashboard-loading">loading…</div>';
  try {
    const res = await fetch('/api/titles/dashboard');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();

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
      titleDashboardEl.innerHTML = '<div class="dashboard-empty">No titles yet — sync a volume to get started.</div>';
      return;
    }

    titleDashboardEl.innerHTML = '';

    if (spotlight || topLabels.length > 0 || libraryStats || onThisDay.length > 0) {
      titleDashboardEl.appendChild(renderTopInfoPanel(spotlight, topLabels, libraryStats, onThisDay));
    }

    if (recentlyViewed.length > 0 || justAdded.length > 0) {
      const rvSection = recentlyViewed.length > 0
        ? renderDashboardSection({
            title: 'Recently Viewed',
            body: (() => {
              const strip = renderDashboardStrip(recentlyViewed, { id: 'dash-recently-viewed', cardFactory: makeCompactTitleCard });
              strip.classList.add('dashboard-card-grid-compact');
              return strip;
            })(),
          })
        : null;
      const jaSection = justAdded.length > 0
        ? renderDashboardSection({
            title: 'Just Added',
            body: renderDashboardStrip(justAdded, { id: 'dash-just-added', cardFactory: makeTitleCardWithAging }),
          })
        : null;
      titleDashboardEl.appendChild(renderSideBySidePanel('dashboard-panel-2', rvSection, jaSection));
    }

    if (onDeck.length > 0) {
      titleDashboardEl.appendChild(renderDashboardSection({
        title: 'Bookmarked Selections',
        accent: true,
        bordered: true,
        body: renderDashboardStrip(onDeck, { id: 'dash-on-deck', cardFactory: makeCompactTitleCard }),
      }));
    }

    if (fromFavoriteLabels.length > 0) {
      titleDashboardEl.appendChild(renderDashboardSection({
        title: 'From Favorite Labels',
        bordered: true,
        body: renderDashboardStrip(fromFavoriteLabels, { id: 'dash-fav-labels', cardFactory: makeTitleCardWithAging }),
      }));
    }

    if (forgottenAttic.length > 0) {
      titleDashboardEl.appendChild(renderDashboardSection({
        title: 'Forgotten Attic',
        bordered: true,
        body: renderDashboardStrip(forgottenAttic, { id: 'dash-forgotten-attic', cardFactory: makeTitleCard }),
      }));
    }

    if (forgottenFavorites.length > 0) {
      titleDashboardEl.appendChild(renderDashboardSection({
        title: 'Forgotten Favorites',
        body: renderDashboardStrip(forgottenFavorites, { id: 'dash-forgotten-favs', cardFactory: makeTitleCard }),
      }));
    }

  } catch (err) {
    titleDashboardEl.innerHTML = '<div class="dashboard-empty">Error loading dashboard.</div>';
    console.error(err);
  }
}
