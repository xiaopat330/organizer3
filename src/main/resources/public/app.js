import { initConfig } from './modules/config.js';
import { showView, setHomeClickHandler } from './modules/grid.js';
import { initCardCallbacks } from './modules/cards.js';
import { openActressDetail } from './modules/actress-detail.js';
import { openTitleDetail } from './modules/title-detail.js';
import { showTitlesView, activateHomeTab } from './modules/home.js';
import { selectActressBrowseMode } from './modules/actress-browse.js';
import { selectTitleBrowseMode, enterUnsortedMode, enterArchiveMode } from './modules/title-browse.js';
import { setRestoring, replaceNav } from './modules/nav.js';

// ── Wire cross-module callbacks ───────────────────────────────────────────
initCardCallbacks(openTitleDetail, openActressDetail);
setHomeClickHandler(showTitlesView);

// ── Config (app name + runtime limits, also updates DOM) ──────────────────
initConfig();

// ── App name click → home ─────────────────────────────────────────────────
document.getElementById('app-name').addEventListener('click', showTitlesView);

// ── Initial load ──────────────────────────────────────────────────────────
replaceNav({ view: 'titles' }, 'home');
showView('titles');
activateHomeTab('latest');

// ── Back/forward navigation ───────────────────────────────────────────────
window.addEventListener('popstate', async (e) => {
  const state = e.state;
  if (!state) return;
  setRestoring(true);
  try {
    switch (state.view) {
      case 'titles':
        showTitlesView();
        break;
      case 'actresses':
        await selectActressBrowseMode(state.mode || 'dashboard');
        break;
      case 'actress-detail':
        await openActressDetail(state.actressId);
        break;
      case 'title-detail':
        await openTitleDetail(state.title);
        break;
      case 'titles-browse':
        if (state.mode === 'unsorted')      await enterUnsortedMode();
        else if (state.mode === 'archive-pool') await enterArchiveMode();
        else selectTitleBrowseMode(state.mode);
        break;
    }
  } finally {
    setRestoring(false);
  }
});
