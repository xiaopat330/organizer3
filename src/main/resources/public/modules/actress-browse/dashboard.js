// Actress dashboard render.
//
// Exports:
//   renderActressDashboard(actressDashboardEl)
//   actressSpotlightRotator

import { makeActressCard, makeCompactActressCard } from '../cards.js';
import {
  renderTopGroupsLeaderboard,
  renderActressLibraryStats,
  renderResearchGapsList,
} from '../dashboard-renderers.js';
import {
  renderDashboardStrip,
  renderDashboardSection,
  renderSideBySidePanel,
  createSpotlightRotator,
} from '../dashboard-panels.js';

// ── Spotlight rotator ─────────────────────────────────────────────────────

const ACTRESS_SPOTLIGHT_INTERVAL_MS = 30_000;
export const actressSpotlightRotator = createSpotlightRotator({
  endpoint: '/api/actresses/spotlight',
  excludeAttr: 'actressId',
  cardSelector: '.actress-card',
  makeCard: a => {
    const card = makeActressCard(a);
    card.classList.add('card-spotlight');
    return card;
  },
  intervalMs: ACTRESS_SPOTLIGHT_INTERVAL_MS,
});

// ── Local helper ──────────────────────────────────────────────────────────

// Dynamic import avoids a circular static dep with actress-detail.js
function _openActressDetail(id) {
  import('../actress-detail.js').then(m => m.openActressDetail(id));
}

// ── Panel builders ────────────────────────────────────────────────────────

function renderTopGroupsSection(topGroups, onGroupClick) {
  return renderTopGroupsLeaderboard(topGroups, {
    onRowClick: (g) => onGroupClick(g.slug),
  });
}

function renderActressTopInfoPanel(spotlight, topGroups, libraryStats, birthdaysToday, researchGaps, onGroupClick) {
  const panel = document.createElement('div');
  panel.className = 'dashboard-top-panel';

  if (spotlight) {
    const left = document.createElement('div');
    left.className = 'dashboard-top-panel-left';
    const header = document.createElement('div');
    header.className = 'dashboard-section-title';
    header.textContent = 'Spotlight';
    left.appendChild(header);
    const card = makeActressCard(spotlight);
    card.classList.add('card-spotlight');
    left.appendChild(card);
    panel.appendChild(left);
    setTimeout(() => actressSpotlightRotator.start(left), ACTRESS_SPOTLIGHT_INTERVAL_MS);
  }

  const hasRight = topGroups.length > 0 || libraryStats || birthdaysToday.length > 0 || researchGaps.length > 0;
  if (hasRight) {
    const right = document.createElement('div');
    right.className = 'dashboard-top-panel-right';

    if (topGroups.length > 0 || libraryStats || researchGaps.length > 0) {
      const upper = document.createElement('div');
      upper.className = 'dashboard-top-right-upper';
      if (topGroups.length > 0) upper.appendChild(renderTopGroupsSection(topGroups, onGroupClick));

      if (libraryStats || researchGaps.length > 0) {
        const stack = document.createElement('div');
        stack.className = 'dashboard-top-right-stack';
        if (libraryStats) stack.appendChild(renderActressLibraryStats(libraryStats));
        if (researchGaps.length > 0) {
          stack.appendChild(renderDashboardSection({
            title: 'Research Gaps',
            badge: `${researchGaps.length}`,
            body: renderResearchGapsList(researchGaps, { onRowClick: (a) => _openActressDetail(a.id) }),
          }));
        }
        upper.appendChild(stack);
      }

      right.appendChild(upper);
    }

    if (birthdaysToday.length > 0) {
      const shown = birthdaysToday.slice(0, 3);
      const strip = renderDashboardStrip(shown, { id: 'dash-birthdays-today', cardFactory: makeActressCard });
      right.appendChild(renderDashboardSection({
        title: 'Birthdays Today',
        badge: `${birthdaysToday.length} 🎂`,
        body: strip,
      }));
    }

    panel.appendChild(right);
  }

  return panel;
}

// ── Main render ───────────────────────────────────────────────────────────

export async function renderActressDashboard(actressDashboardEl, onGroupClick) {
  actressDashboardEl.innerHTML = '<div class="dashboard-loading">loading…</div>';
  try {
    const res = await fetch('/api/actresses/dashboard');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();

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
      actressDashboardEl.innerHTML = '<div class="dashboard-empty">No actresses yet — sync a volume to get started.</div>';
      return;
    }

    actressDashboardEl.innerHTML = '';

    if (spotlight || topGroups.length > 0 || libraryStats || birthdaysToday.length > 0 || researchGaps.length > 0) {
      actressDashboardEl.appendChild(
        renderActressTopInfoPanel(spotlight, topGroups, libraryStats, birthdaysToday, researchGaps, onGroupClick));
    }

    if (recentlyViewed.length > 0 || newFaces.length > 0) {
      const rvSection = recentlyViewed.length > 0
        ? renderDashboardSection({
            title: 'Recently Viewed',
            body: (() => {
              const strip = renderDashboardStrip(recentlyViewed, { id: 'dash-actress-recently-viewed', cardFactory: makeCompactActressCard });
              strip.classList.add('dashboard-card-grid-compact');
              return strip;
            })(),
          })
        : null;
      const nfSection = newFaces.length > 0
        ? renderDashboardSection({
            title: 'New Faces',
            body: renderDashboardStrip(newFaces, { id: 'dash-actress-new-faces', cardFactory: makeActressCard }),
          })
        : null;
      actressDashboardEl.appendChild(renderSideBySidePanel('dashboard-panel-2', rvSection, nfSection));
    }

    if (bookmarks.length > 0) {
      actressDashboardEl.appendChild(renderDashboardSection({
        title: 'Bookmarked Actresses',
        accent: true,
        bordered: true,
        body: renderDashboardStrip(bookmarks, { id: 'dash-actress-bookmarks', cardFactory: makeCompactActressCard }),
      }));
    }

    if (undiscoveredElites.length > 0) {
      actressDashboardEl.appendChild(renderDashboardSection({
        title: 'Undiscovered Elites',
        bordered: true,
        body: renderDashboardStrip(undiscoveredElites, { id: 'dash-actress-undiscovered', cardFactory: makeActressCard }),
      }));
    }

    if (forgottenGems.length > 0) {
      actressDashboardEl.appendChild(renderDashboardSection({
        title: 'Forgotten Gems',
        body: renderDashboardStrip(forgottenGems, { id: 'dash-actress-forgotten-gems', cardFactory: makeActressCard }),
      }));
    }

  } catch (err) {
    actressDashboardEl.innerHTML = '<div class="dashboard-empty">Error loading dashboard.</div>';
    console.error(err);
  }
}
