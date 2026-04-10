import { initConfig } from './modules/config.js';
import { showView, setHomeClickHandler } from './modules/grid.js';
import { initCardCallbacks } from './modules/cards.js';
import { openActressDetail } from './modules/actress-detail.js';
import { openTitleDetail } from './modules/title-detail.js';
import { showTitlesView, activateHomeTab } from './modules/home.js';
import './modules/title-browse.js';

// ── Wire cross-module callbacks ───────────────────────────────────────────
initCardCallbacks(openTitleDetail, openActressDetail);
setHomeClickHandler(showTitlesView);

// ── Config (app name + runtime limits, also updates DOM) ──────────────────
initConfig();

// ── App name click → home ─────────────────────────────────────────────────
document.getElementById('app-name').addEventListener('click', showTitlesView);

// ── Initial load ──────────────────────────────────────────────────────────
showView('titles');
activateHomeTab('latest');
