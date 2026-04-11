import { showView, setActiveGrid, ensureSentinel, updateBreadcrumb, ScrollingGrid } from './grid.js';
import { pushNav } from './nav.js';
import { makeTitleCard, makeActressCard } from './cards.js';
import { resetActressState } from './actress-browse.js';
import { MAX_TOTAL, MAX_RANDOM_TITLES, MAX_RANDOM_ACTRESSES } from './config.js';

// ── Home grids ────────────────────────────────────────────────────────────
export const titlesGrid = new ScrollingGrid(
  document.getElementById('grid'),
  (o, l) => `/api/titles?offset=${o}&limit=${l}`,
  makeTitleCard,
  'no titles',
  { getMax: () => MAX_TOTAL }
);

export const randomTitlesGrid = new ScrollingGrid(
  document.getElementById('random-titles-grid'),
  (_o, l) => `/api/titles/random?limit=${l}`,
  makeTitleCard,
  'no titles',
  { getMax: () => MAX_RANDOM_TITLES }
);

export const randomActressHomeGrid = new ScrollingGrid(
  document.getElementById('random-actress-home-grid'),
  (_o, l) => `/api/actresses/random?limit=${l}`,
  makeActressCard,
  'no actresses',
  { getMax: () => MAX_RANDOM_ACTRESSES }
);

// ── Home tab management ───────────────────────────────────────────────────
export let homeTab = 'latest';

export function activateHomeTab(tab) {
  homeTab = tab;
  window._homeTab = tab; // shared with actress-detail.js for breadcrumb context

  document.querySelectorAll('.home-tab').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.tab === tab);
  });

  const HOME_GRID_IDS = ['grid', 'random-titles-grid', 'random-actress-home-grid'];
  for (const id of HOME_GRID_IDS) {
    const el = document.getElementById(id);
    if (el) el.style.display = 'none';
  }

  if (tab === 'latest') {
    document.getElementById('grid').style.display = 'grid';
    setActiveGrid(titlesGrid);
    ensureSentinel();
    if (titlesGrid.offset === 0 && !titlesGrid.exhausted) titlesGrid.loadMore();
  } else if (tab === 'random-titles') {
    document.getElementById('random-titles-grid').style.display = 'grid';
    setActiveGrid(randomTitlesGrid);
    ensureSentinel();
    if (randomTitlesGrid.offset === 0 && !randomTitlesGrid.exhausted) randomTitlesGrid.loadMore();
  } else if (tab === 'random-actresses') {
    document.getElementById('random-actress-home-grid').style.display = 'grid';
    setActiveGrid(randomActressHomeGrid);
    ensureSentinel();
    if (randomActressHomeGrid.offset === 0 && !randomActressHomeGrid.exhausted) randomActressHomeGrid.loadMore();
  }
}

document.querySelectorAll('.home-tab').forEach(btn => {
  btn.addEventListener('click', () => activateHomeTab(btn.dataset.tab));
});

// ── showTitlesView ────────────────────────────────────────────────────────
export function showTitlesView() {
  pushNav({ view: 'titles' }, 'home');
  showView('titles');
  document.getElementById('actresses-btn')?.classList.remove('active');
  document.getElementById('titles-browse-btn')?.classList.remove('active');
  document.getElementById('title-collections-btn')?.classList.remove('active');
  resetActressState();
  updateBreadcrumb([]);
  activateHomeTab(homeTab);
}
