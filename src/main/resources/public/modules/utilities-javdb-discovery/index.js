// JavDB Discovery tool — top-level orchestrator.
//
// Owns:
//   - shared `state` object (created here, passed to per-subtab modules)
//   - top-level subtab dispatch (enrich / titles / collections / queue / review)
//   - queue header refresh (badge, pause button, rate-limit banner) — used by all subtabs
//   - global controls (pause, cancel-all, A–Z controls toggle)
//
// Per-subtab modules under this directory each export an `init(state, hooks)`
// returning the small API index.js needs to call (e.g. loadTitlesTab, renderTitlesTabSilent).
// Sibling subtab modules never import each other — they import only `shared.js`.

import { esc } from '../utils.js';
import { focusReviewItem, showEnrichmentReviewView } from '../utilities-enrichment-review.js';

import { initEnrich }      from './enrich.js';
import { initTitles }      from './titles.js';
import { initCollections } from './collections.js';
import { initQueue }       from './queue.js';

// ── State ─────────────────────────────────────────────────────────────────

function createState() {
  return {
    actresses: [],
    selectedId: null,
    activeTab: 'titles',           // actress-panel sub-tab (titles/profile/conflicts/errors)
    queuePollTimer: null,
    paused: false,
    lastActiveTotal: 0,
    rateLimitPausedUntil: null,
    alphaFilter: 'All',
    tierFilter: new Set(),
    favoritesOnly: true,
    bookmarkedOnly: false,
    sortField: 'name',
    sortDir: 'asc',
    titleFilter: { tags: [], minRatingAvg: null, minRatingCount: null },
    titles: {
      source: 'recent',
      poolVolumeId: null,
      pools: [],
      page: 0,
      pageSize: 50,
      totalPages: 0,
      filter: '',
      filterDebounce: null,
      rows: [],
      hasMore: false,
      selected: new Set(),
      loading: false,
    },
    collections: {
      page: 0,
      pageSize: 50,
      totalPages: 0,
      filter: '',
      filterDebounce: null,
      rows: [],
      hasMore: false,
      selected: new Set(),
      loading: false,
    },
  };
}

const state = createState();

// ── DOM refs (top-level chrome) ───────────────────────────────────────────

const view              = document.getElementById('tools-javdb-discovery-view');
const queueBadge        = document.getElementById('jd-queue-badge');
const rateLimitBanner   = document.getElementById('jd-rate-limit-banner');
const pauseBtn          = document.getElementById('jd-pause-btn');
const cancelAllBtn      = document.getElementById('jd-cancel-all-btn');
const controlsToggle    = document.getElementById('jd-controls-toggle');
const controlsPanel     = document.getElementById('jd-controls');
const reviewBody        = document.getElementById('tools-enrichment-review-view');

const enrichTab      = view.querySelector('[data-jd-tab="enrich"]');
const titlesTab      = view.querySelector('[data-jd-tab="titles"]');
const collectionsTab = view.querySelector('[data-jd-tab="collections"]');
const queueTab       = view.querySelector('[data-jd-tab="queue"]');
const reviewTab      = view.querySelector('[data-jd-tab="review"]');
const jdBody         = view.querySelector('.jd-body');
const queueBody      = document.getElementById('jd-queue-body');
const titlesBody     = document.getElementById('jd-titles-body');
const collectionsBody = document.getElementById('jd-collections-body');

// ── Subtab module init ────────────────────────────────────────────────────
// Hook bag: per-subtab modules call back into here for cross-cutting work
// (refreshQueue runs from enrich's retry/pick handlers; navigateToActress runs
// from queue's actress-link click).

const hooks = {
  loadActresses,
  refreshQueue,
  switchTopTab: switchJdTab,
  navigateToActress: (id) => enrichApi.navigateToActress(id),
};

const enrichApi      = initEnrich(state, hooks);
const titlesApi      = initTitles(state);
const collectionsApi = initCollections(state);
const queueApi       = initQueue(state, hooks);

// ── Public API ────────────────────────────────────────────────────────────

export async function showJavdbDiscoveryView() {
  view.style.display = '';
  controlsPanel.classList.add('collapsed');
  controlsToggle.classList.add('collapsed');
  switchJdTab('enrich');
  await Promise.all([loadActresses(), refreshQueue()]);
  startQueuePoll();
}

export function hideJavdbDiscoveryView() {
  view.style.display = 'none';
  stopQueuePoll();
  queueApi.stopQueueItemsPoll();
}

export async function navigateToActressProfile(actressId) {
  await enrichApi.navigateToActressProfile(actressId);
}

export async function navigateToReviewItem(id) {
  switchJdTab('review');
  await showEnrichmentReviewView();
  focusReviewItem(id);
}

// ── Data loading (header / actresses) ─────────────────────────────────────

async function loadActresses() {
  try {
    const res = await fetch('/api/javdb/discovery/actresses');
    if (!res.ok) return;
    state.actresses = await res.json();
    enrichApi.computeAlphaBuckets();
    enrichApi.renderAlphaBar();
    enrichApi.renderFilterBar();
    enrichApi.renderSortBar();
    enrichApi.renderActressList();
  } catch (_) { /* network error — ignore */ }
}

async function refreshQueue() {
  try {
    const res = await fetch('/api/javdb/discovery/queue');
    if (!res.ok) return;
    const { pending, inFlight, failed, pausedItems, paused, rateLimitPausedUntil, rateLimitPauseReason, consecutiveRateLimitHits, pauseType } = await res.json();
    state.paused = paused;
    state.rateLimitPausedUntil = rateLimitPausedUntil || null;
    const activeTotal = pending + inFlight;
    const total = activeTotal + failed + (pausedItems || 0);
    if (total === 0) {
      queueBadge.style.display = 'none';
    } else {
      const parts = [];
      if (activeTotal > 0) parts.push(`${activeTotal} pending`);
      if (pausedItems > 0) parts.push(`${pausedItems} paused`);
      if (failed > 0) parts.push(`<span class="jd-badge-failed">${failed} failed</span>`);
      queueBadge.innerHTML = parts.join(' · ');
      queueBadge.style.display = '';
    }
    pauseBtn.textContent = paused ? 'Resume' : 'Pause';
    pauseBtn.classList.toggle('jd-paused', paused);

    if (rateLimitPausedUntil) {
      const resumeTime = new Date(rateLimitPausedUntil);
      const resumeStr = resumeTime.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      if (pauseType === 'burst') {
        rateLimitBanner.className = 'jd-rate-limit-banner jd-banner-burst';
        rateLimitBanner.innerHTML =
          `↻ Taking a burst break — resuming at <span class="jd-banner-time">${esc(resumeStr)}</span>. ` +
          `<button type="button" class="jd-banner-resume-btn" id="jd-force-resume-btn">▶ Resume Now</button>`;
      } else {
        const reason = rateLimitPauseReason || 'Rate limited';
        const hitNote = consecutiveRateLimitHits > 1
          ? ` (${consecutiveRateLimitHits} consecutive hits — pause doubled each time)`
          : '';
        rateLimitBanner.className = 'jd-rate-limit-banner jd-banner-rate-limit';
        rateLimitBanner.innerHTML =
          `⚠ Rate limited — ${esc(reason)}${esc(hitNote)}. Resuming at <strong class="jd-banner-time">${esc(resumeStr)}</strong>. ` +
          `<span class="jd-banner-hint">Switch VPN then </span>` +
          `<button type="button" class="jd-banner-resume-btn" id="jd-force-resume-btn">▶ Resume Now</button>`;
      }
      rateLimitBanner.style.display = '';
      document.getElementById('jd-force-resume-btn')?.addEventListener('click', forceResume);
    } else {
      rateLimitBanner.style.display = 'none';
    }

    if (activeTotal > 0 || state.lastActiveTotal > 0) {
      await loadActresses();
      if (state.selectedId !== null && state.activeTab === 'titles') {
        await enrichApi.renderTitlesTabSilent();
      }
    }
    state.lastActiveTotal = activeTotal;
  } catch (_) { /* ignore */ }
}

function startQueuePoll() {
  stopQueuePoll();
  state.queuePollTimer = setInterval(refreshQueue, 10_000);
}

function stopQueuePoll() {
  if (state.queuePollTimer !== null) {
    clearInterval(state.queuePollTimer);
    state.queuePollTimer = null;
  }
}

// ── Top-tab switching ─────────────────────────────────────────────────────

function switchJdTab(tab) {
  enrichTab.classList.toggle('selected',      tab === 'enrich');
  titlesTab.classList.toggle('selected',      tab === 'titles');
  collectionsTab.classList.toggle('selected', tab === 'collections');
  queueTab.classList.toggle('selected',       tab === 'queue');
  reviewTab.classList.toggle('selected',      tab === 'review');
  jdBody.style.display          = tab === 'enrich'      ? '' : 'none';
  titlesBody.style.display      = tab === 'titles'      ? '' : 'none';
  collectionsBody.style.display = tab === 'collections' ? '' : 'none';
  queueBody.style.display       = tab === 'queue'       ? '' : 'none';
  reviewBody.style.display      = tab === 'review'      ? '' : 'none';
  if (tab === 'queue') {
    queueApi.loadQueueItems();
    queueApi.startQueueItemsPoll();
  } else {
    queueApi.stopQueueItemsPoll();
  }
  if (tab === 'titles')      titlesApi.loadTitlesTab();
  if (tab === 'collections') collectionsApi.loadCollectionsTab();
}

enrichTab.addEventListener('click',      () => switchJdTab('enrich'));
titlesTab.addEventListener('click',      () => switchJdTab('titles'));
collectionsTab.addEventListener('click', () => switchJdTab('collections'));
queueTab.addEventListener('click',       () => switchJdTab('queue'));
reviewTab.addEventListener('click',      () => { switchJdTab('review'); showEnrichmentReviewView(); });

// ── Header / global controls ──────────────────────────────────────────────

async function cancelAll() {
  if (!window.confirm('Stop all pending Discovery enrichment jobs?')) return;
  await fetch('/api/javdb/discovery/queue', { method: 'DELETE' });
  await refreshQueue();
}

async function togglePause() {
  const newPaused = !state.paused;
  await fetch('/api/javdb/discovery/queue/pause', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ paused: newPaused }),
  });
  await refreshQueue();
}

async function forceResume() {
  const btn = document.getElementById('jd-force-resume-btn');
  if (btn) { btn.disabled = true; btn.textContent = '…'; }
  await fetch('/api/javdb/discovery/queue/resume', { method: 'POST' });
  await refreshQueue();
}

controlsToggle.addEventListener('click', () => {
  const collapsed = controlsPanel.classList.toggle('collapsed');
  controlsToggle.classList.toggle('collapsed', collapsed);
});

pauseBtn.addEventListener('click', togglePause);
cancelAllBtn.addEventListener('click', cancelAll);
