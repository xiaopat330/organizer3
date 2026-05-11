// v2/discovery/index.js — JavDB Discovery workbench page entry point.
//
// Port of modules/utilities-javdb-discovery/index.js + the HTML structure
// previously embedded in index.html, adapted to the v2 mountDiscovery(rootEl)
// convention.
//
// Public API:
//   mountDiscovery(rootEl)     — inject HTML + wire all modules; call once
//   unmountDiscovery()         — stop polling; safe to call on page leave
//   navigateToActressProfile(actressId)
//
// NOTE: The Review tab formerly embedded the legacy utilities-enrichment-review
// module, which has top-level DOM queries (e.g. getElementById('cover-lightbox'))
// that throw on any page missing those IDs — crashing module load entirely.
// Under Option C, Enrichment Review is its own dedicated page (/v2-enrichment.html).
// The Review sub-tab has been removed; a header link points users there instead.

import { esc } from '../../utils.js';

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

let state    = null;
let enrichApi = null;
let titlesApi = null;
let collectionsApi = null;
let queueApi = null;

// ── HTML scaffold ─────────────────────────────────────────────────────────

function buildHTML() {
  return `
<div class="wb-page dis-wb">

  <!-- ── Discovery header ──────────────────────────────────────────────── -->
  <div class="jd-header dis-header">
    <div class="dis-title-group">
      <span class="wb-page-title dis-page-title">Discovery</span>
      <span class="dis-kpi-strip">Source: javdb</span>
    </div>
    <div class="jd-tabs">
      <button type="button" class="jd-tab jd-tab-enrich selected" data-jd-tab="enrich" title="Actress-driven enrichment">
        <svg class="jd-tab-icon" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="8" r="4"/><path d="M4 20v-1a8 8 0 0 1 16 0v1"/></svg>
        Enrich
      </button>
      <button type="button" class="jd-tab jd-tab-titles" data-jd-tab="titles">
        <svg class="jd-tab-icon" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="20" rx="2"/><line x1="2" y1="12" x2="22" y2="12"/></svg>
        Titles
      </button>
      <button type="button" class="jd-tab jd-tab-collections" data-jd-tab="collections">
        <svg class="jd-tab-icon" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="13" height="13" rx="2"/><rect x="8" y="8" width="13" height="13" rx="2"/></svg>
        Collections
      </button>
      <button type="button" class="jd-tab jd-tab-queue" data-jd-tab="queue">
        <svg class="jd-tab-icon" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><circle cx="3" cy="6" r="1" fill="currentColor"/><circle cx="3" cy="12" r="1" fill="currentColor"/><circle cx="3" cy="18" r="1" fill="currentColor"/></svg>
        Queue
      </button>
    </div>
    <a class="hl-sync-link" href="/v2-enrichment.html">Enrichment Review →</a>
    <div class="jd-header-actions dis-header-actions">
      <button type="button" id="jd-pause-btn" class="jd-action-btn jd-pause-btn">Pause</button>
      <button type="button" id="jd-cancel-all-btn" class="jd-action-btn jd-danger-outline-btn">⏹ Stop All Enrichment</button>
    </div>
  </div>

  <div id="jd-rate-limit-banner" class="jd-rate-limit-banner" style="display:none"></div>

  <!-- ── Enrich tab body ───────────────────────────────────────────────── -->
  <div class="jd-body">
    <aside class="jd-sidebar" id="jd-sidebar">
      <div class="jd-sidebar-header">Actresses</div>
      <div id="jd-filter-bar" class="jd-filter-bar"></div>
      <div id="jd-sort-bar" class="jd-sort-bar"></div>
      <div class="jd-alpha-header">
        <span class="jd-alpha-label">A–Z filter</span>
        <button type="button" id="jd-controls-toggle" class="jd-controls-toggle collapsed" title="Toggle A–Z filter">▾</button>
      </div>
      <div id="jd-controls" class="jd-controls collapsed">
        <div id="jd-alpha-bar" class="jd-alpha-bar"></div>
      </div>
      <ul class="jd-actress-list" id="jd-actress-list"></ul>
    </aside>

    <section class="jd-detail" id="jd-detail">
      <div class="jd-empty dis-empty" id="jd-empty">◌<br>Select an actress to view enrichment status.</div>
      <div class="jd-actress-panel" id="jd-actress-panel" style="display:none">
        <div class="jd-subnav">
          <button type="button" class="jd-subtab selected" data-tab="titles">
            <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="20" rx="2"/><line x1="2" y1="12" x2="22" y2="12"/></svg>
            Titles
          </button>
          <button type="button" class="jd-subtab" data-tab="profile">
            <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="8" r="4"/><path d="M4 20v-1a8 8 0 0 1 16 0v1"/></svg>
            Profile
          </button>
          <button type="button" class="jd-subtab" data-tab="conflicts">
            <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
            Conflicts
          </button>
          <button type="button" class="jd-subtab" data-tab="errors">
            <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
            Errors
          </button>
        </div>
        <div class="jd-titles-action-bar" id="jd-titles-action-bar">
          <button type="button" id="jd-enrich-btn" class="jd-action-btn jd-enrich-btn">▶ Enrich New Titles</button>
          <button type="button" id="jd-cancel-actress-btn" class="jd-action-btn jd-muted-btn">⏹ Stop Enrichment</button>
        </div>
        <div class="jd-subview" id="jd-subview-titles"></div>
        <div class="jd-subview" id="jd-subview-profile" style="display:none"></div>
        <div class="jd-subview" id="jd-subview-conflicts" style="display:none"></div>
        <div class="jd-subview" id="jd-subview-errors" style="display:none"></div>
      </div>
    </section>
  </div>

  <!-- ── Titles tab body ───────────────────────────────────────────────── -->
  <div class="jd-titles-body" id="jd-titles-body" style="display:none">
    <div class="jd-titles-toolbar">
      <div class="jd-filter-wrap">
        <input type="text" id="jd-titles-filter-input" class="jd-filter-input"
               placeholder="Filter by code or label prefix…" autocomplete="off" spellcheck="false">
        <button type="button" id="jd-titles-filter-clear" class="jd-filter-clear" title="Clear filter" style="display:none">×</button>
        <div class="jd-filter-autocomplete" id="jd-titles-filter-autocomplete"></div>
      </div>
    </div>
    <div class="jd-titles-chips" id="jd-titles-chips"></div>
    <div class="dis-cast-legend">
      <span class="dis-cast-legend-item"><span class="jd-titles-elig-dot jd-titles-elig-yes"></span>Will chain a profile fetch</span>
      <span class="dis-cast-legend-item"><span class="jd-titles-elig-dot jd-titles-elig-no"></span>Title-only fetch (no profile chain)</span>
    </div>
    <div class="jd-titles-empty dis-empty" id="jd-titles-empty" style="display:none">No unenriched titles for this source.</div>
    <div class="jd-titles-table-wrap" id="jd-titles-table-wrap" style="display:none">
      <table class="jd-titles-table" id="jd-titles-table">
        <thead>
          <tr>
            <th class="jd-titles-cb-col"></th>
            <th>Code</th>
            <th>Title</th>
            <th>Actress</th>
            <th>Volume</th>
            <th>Added</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody id="jd-titles-table-body"></tbody>
      </table>
      <div class="jd-titles-pager" id="jd-titles-pager"></div>
    </div>
    <div class="jd-titles-footer" id="jd-titles-footer" style="display:none">
      <span class="jd-titles-footer-count" id="jd-titles-footer-count"></span>
      <button type="button" class="jd-action-btn jd-titles-enqueue-btn" id="jd-titles-enqueue-btn">Enqueue Selected</button>
      <button type="button" class="jd-action-btn jd-titles-clear-btn" id="jd-titles-clear-btn" title="Clear all selected">Clear</button>
    </div>
  </div>

  <!-- ── Collections tab body ──────────────────────────────────────────── -->
  <div class="jd-collections-body" id="jd-collections-body" style="display:none">
    <div class="jd-titles-toolbar">
      <div class="jd-filter-wrap">
        <input type="text" id="jd-collections-filter-input" class="jd-filter-input"
               placeholder="Filter by code or label prefix…" autocomplete="off" spellcheck="false">
        <button type="button" id="jd-collections-filter-clear" class="jd-filter-clear" title="Clear filter" style="display:none">×</button>
        <div class="jd-filter-autocomplete" id="jd-collections-filter-autocomplete"></div>
      </div>
    </div>
    <div class="dis-cast-legend">
      <span class="jd-cast-chip jd-cast-chip-elig"><span class="jd-cast-chip-icon">✓</span>Will chain a profile fetch</span>
      <span class="jd-cast-chip jd-cast-chip-sentinel"><span class="jd-cast-chip-icon">✗</span>Sentinel actress (no chain)</span>
      <span class="jd-cast-chip jd-cast-chip-below"><span class="jd-cast-chip-icon">◌</span>Below threshold (no chain)</span>
    </div>
    <div class="jd-collections-empty dis-empty" id="jd-collections-empty" style="display:none">No unenriched multi-cast titles.</div>
    <div class="jd-collections-table-wrap" id="jd-collections-table-wrap" style="display:none">
      <table class="jd-titles-table jd-collections-table" id="jd-collections-table">
        <thead>
          <tr>
            <th class="jd-titles-cb-col"></th>
            <th>Code</th>
            <th>Title</th>
            <th>Cast</th>
            <th>Volume</th>
            <th>Added</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody id="jd-collections-table-body"></tbody>
      </table>
      <div class="jd-titles-pager" id="jd-collections-pager"></div>
    </div>
    <div class="jd-titles-footer" id="jd-collections-footer" style="display:none">
      <span class="jd-titles-footer-count" id="jd-collections-footer-count"></span>
      <button type="button" class="jd-action-btn jd-titles-enqueue-btn" id="jd-collections-enqueue-btn">Enqueue Selected</button>
      <button type="button" class="jd-action-btn jd-titles-clear-btn" id="jd-collections-clear-btn" title="Clear all selected">Clear</button>
    </div>
  </div>

  <!-- ── Queue tab body ────────────────────────────────────────────────── -->
  <div class="jd-queue-body" id="jd-queue-body" style="display:none">
    <div class="jd-queue-empty dis-empty" id="jd-queue-empty" style="display:none">No active jobs in the enrichment queue.</div>
    <div class="jd-queue-table-wrap" id="jd-queue-table-wrap" style="display:none">
      <table class="jd-queue-table" id="jd-queue-table">
        <thead>
          <tr>
            <th>Actress</th>
            <th>Code</th>
            <th>Type</th>
            <th>Priority</th>
            <th>Status</th>
            <th>Attempts</th>
            <th>ETA</th>
            <th>Age</th>
            <th></th>
          </tr>
        </thead>
        <tbody id="jd-queue-table-body"></tbody>
      </table>
    </div>
  </div>

</div>

<!-- ── Enrichment detail modal (document-level) ──────────────────────────── -->
<div id="jd-enrich-modal-overlay" class="jd-enrich-modal-overlay" style="display:none">
  <div class="jd-enrich-modal" role="dialog" aria-modal="true">
    <div class="jd-enrich-modal-header">
      <div id="jd-enrich-modal-heading" class="jd-enrich-modal-heading"></div>
      <button class="jd-enrich-modal-close" id="jd-enrich-modal-close" aria-label="Close">✕</button>
    </div>
    <div id="jd-enrich-modal-body" class="jd-enrich-modal-body"></div>
  </div>
</div>
  `;
}

// ── Mount / unmount ───────────────────────────────────────────────────────

export async function mountDiscovery(rootEl) {
  // Inject HTML.
  rootEl.innerHTML = buildHTML();

  // Reset state.
  state = createState();

  // DOM refs (resolved after innerHTML set).
  const rateLimitBanner = document.getElementById('jd-rate-limit-banner');
  const pauseBtn        = document.getElementById('jd-pause-btn');
  const cancelAllBtn    = document.getElementById('jd-cancel-all-btn');
  const controlsToggle  = document.getElementById('jd-controls-toggle');
  const controlsPanel   = document.getElementById('jd-controls');

  const enrichTab      = rootEl.querySelector('[data-jd-tab="enrich"]');
  const titlesTab      = rootEl.querySelector('[data-jd-tab="titles"]');
  const collectionsTab = rootEl.querySelector('[data-jd-tab="collections"]');
  const queueTab       = rootEl.querySelector('[data-jd-tab="queue"]');
  const jdBody         = rootEl.querySelector('.jd-body');
  const queueBody      = document.getElementById('jd-queue-body');
  const titlesBody     = document.getElementById('jd-titles-body');
  const collectionsBody = document.getElementById('jd-collections-body');

  // ── Hook bag for inter-module callbacks ───────────────────────────────

  const hooks = {
    loadActresses,
    refreshQueue,
    switchTopTab: switchJdTab,
    navigateToActress: (id) => enrichApi.navigateToActress(id),
  };

  // ── Init subtab modules ───────────────────────────────────────────────

  enrichApi      = initEnrich(state, hooks);
  titlesApi      = initTitles(state);
  collectionsApi = initCollections(state);
  queueApi       = initQueue(state, hooks);

  // ── Tab switching ─────────────────────────────────────────────────────

  function switchJdTab(tab) {
    enrichTab.classList.toggle('selected',      tab === 'enrich');
    titlesTab.classList.toggle('selected',      tab === 'titles');
    collectionsTab.classList.toggle('selected', tab === 'collections');
    queueTab.classList.toggle('selected',       tab === 'queue');
    jdBody.style.display          = tab === 'enrich'      ? '' : 'none';
    titlesBody.style.display      = tab === 'titles'      ? '' : 'none';
    collectionsBody.style.display = tab === 'collections' ? '' : 'none';
    queueBody.style.display       = tab === 'queue'       ? '' : 'none';
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

  // ── Data loading ──────────────────────────────────────────────────────

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
      const {
        pending, inFlight, failed, pausedItems, paused,
        rateLimitPausedUntil, rateLimitPauseReason,
        consecutiveRateLimitHits, pauseType,
      } = await res.json();
      state.paused = paused;
      state.rateLimitPausedUntil = rateLimitPausedUntil || null;
      const activeTotal = pending + inFlight;
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

  // ── Polling ───────────────────────────────────────────────────────────

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

  // ── Header / global controls ──────────────────────────────────────────

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

  // ── navigate-to-* custom event handlers ──────────────────────────────
  // Scoped to this page; installed while mounted, removed on unmount.

  async function onNavigateToActressProfile(e) {
    switchJdTab('enrich');
    await enrichApi.navigateToActressProfile(e.detail.actressId);
  }

  document.addEventListener('navigate-to-discovery-actress-profile', onNavigateToActressProfile);

  // Store cleanup refs on rootEl for unmount.
  rootEl._disCleanup = () => {
    stopQueuePoll();
    queueApi.stopQueueItemsPoll();
    document.removeEventListener('navigate-to-discovery-actress-profile', onNavigateToActressProfile);
  };

  // ── Initial load ──────────────────────────────────────────────────────

  controlsPanel.classList.add('collapsed');
  controlsToggle.classList.add('collapsed');
  switchJdTab('enrich');
  await Promise.all([loadActresses(), refreshQueue()]);
  startQueuePoll();
}

export function unmountDiscovery(rootEl) {
  if (rootEl?._disCleanup) {
    rootEl._disCleanup();
    rootEl._disCleanup = null;
  }
  state    = null;
  enrichApi = null;
  titlesApi = null;
  collectionsApi = null;
  queueApi = null;
}

// ── Deep-link navigation API (called from outside, e.g. palette) ──────────

export async function navigateToActressProfile(actressId) {
  if (!enrichApi) return;
  await enrichApi.navigateToActressProfile(actressId);
}
