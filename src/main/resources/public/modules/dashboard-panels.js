// Shared dashboard layout and rotation helpers used by both the Titles and
// Actresses dashboards. These are intentionally generic — they take card
// factories and endpoint URLs from the caller, so neither domain leaks here.

import { esc } from './utils.js';

/**
 * Render a horizontal/grid strip of cards for a dashboard module.
 * Each item is passed to {@code cardFactory} which must return a DOM node.
 */
export function renderDashboardStrip(items, { id, cardFactory }) {
  const grid = document.createElement('div');
  grid.className = 'dashboard-card-grid';
  if (id) grid.id = id;
  items.forEach(t => grid.appendChild(cardFactory(t)));
  return grid;
}

/**
 * Wrap any body element in a titled dashboard section with optional accent /
 * border / badge styling.
 */
export function renderDashboardSection({ title, accent = false, badge = null, body, bordered = false }) {
  const section = document.createElement('section');
  section.className = 'dashboard-section'
    + (accent   ? ' dashboard-section-accent'   : '')
    + (bordered ? ' dashboard-section-bordered' : '');
  const header = document.createElement('div');
  header.className = 'dashboard-section-title';
  header.textContent = title;
  if (badge) {
    const b = document.createElement('span');
    b.className = 'dashboard-section-badge';
    b.textContent = badge;
    header.appendChild(b);
  }
  section.appendChild(header);
  section.appendChild(body);
  return section;
}

/**
 * Two-column dashboard panel mosaic. Either side may be null/missing.
 */
export function renderSideBySidePanel(panelClass, leftEl, rightEl) {
  const panel = document.createElement('div');
  panel.className = `dashboard-side-panel ${panelClass}`;
  if (leftEl) {
    const left = document.createElement('div');
    left.className = 'dashboard-side-panel-cell';
    left.appendChild(leftEl);
    panel.appendChild(left);
  }
  if (rightEl) {
    const right = document.createElement('div');
    right.className = 'dashboard-side-panel-cell';
    right.appendChild(rightEl);
    panel.appendChild(right);
  }
  return panel;
}

/**
 * Render a generic Library stats tile grid. Each tile is
 *   { label, value, bar? }   // bar is a 0-100 percentage for the optional bar fill
 * The caller controls the section heading and tile order so each domain can
 * pick its own KPIs.
 */
export function renderStatsTiles({ heading, tiles, sectionClass = 'dashboard-library-stats' }) {
  const section = document.createElement('section');
  section.className = `dashboard-section ${sectionClass}`;
  const header = document.createElement('div');
  header.className = 'dashboard-section-title';
  header.textContent = heading;
  section.appendChild(header);

  const grid = document.createElement('div');
  grid.className = 'dashboard-stats-grid';
  tiles.forEach(t => {
    const tile = document.createElement('div');
    tile.className = 'stats-tile';
    let html = `<div class="stats-tile-value">${esc(String(t.value))}</div>`
             + `<div class="stats-tile-label">${esc(t.label)}</div>`;
    if (t.bar != null) {
      html += `<div class="stats-tile-bar-wrap"><div class="stats-tile-bar" style="width:${t.bar}%"></div></div>`;
    }
    tile.innerHTML = html;
    grid.appendChild(tile);
  });
  section.appendChild(grid);
  return section;
}

/**
 * Build a self-contained spotlight rotator. Each instance owns its own
 * container, interval, and fetch loop, so the Titles and Actresses dashboards
 * can run rotators in parallel without stepping on one another.
 *
 * @param {object} opts
 * @param {string}    opts.endpoint        — base URL, e.g. '/api/titles/spotlight'
 * @param {string}    opts.excludeAttr     — DOM dataset attr on the current card to read for ?exclude=
 * @param {(item)=>HTMLElement} opts.makeCard — card factory for the new spotlight payload
 * @param {string}    [opts.cardSelector='.card'] — CSS selector for the current card inside the container
 * @param {number}    [opts.intervalMs=30000]
 */
export function createSpotlightRotator({ endpoint, excludeAttr, makeCard, cardSelector = '.card', intervalMs = 30_000 }) {
  let intervalId = null;
  let container = null;

  async function rotate() {
    if (!container) return;
    const currentCard = container.querySelector(cardSelector);
    const excludeVal = currentCard ? currentCard.dataset[excludeAttr] : null;
    const url = endpoint + (excludeVal ? `?exclude=${encodeURIComponent(excludeVal)}` : '');
    try {
      const res = await fetch(url);
      if (res.status === 204 || !res.ok) return; // no candidates — keep current
      const item = await res.json();
      const newCard = makeCard(item);
      newCard.classList.add('card-spotlight', 'spotlight-enter');
      if (currentCard) {
        currentCard.classList.add('spotlight-exit');
        currentCard.addEventListener('animationend', () => currentCard.remove(), { once: true });
      }
      container.appendChild(newCard);
      // Trigger reflow so the animation plays
      void newCard.offsetWidth;
      newCard.classList.remove('spotlight-enter');
    } catch (_) { /* network error — silently skip */ }
  }

  function start(targetContainer) {
    stop();
    container = targetContainer;
    intervalId = setInterval(rotate, intervalMs);
  }

  function stop() {
    if (intervalId !== null) {
      clearInterval(intervalId);
      intervalId = null;
    }
    container = null;
  }

  return { start, stop };
}
