import { esc } from './utils.js';

function formatRelative(isoStr) {
  if (!isoStr) return '—';
  try {
    const diff = Date.now() - new Date(isoStr).getTime();
    const days = Math.floor(diff / 86400000);
    if (days === 0) return 'Today';
    if (days === 1) return 'Yesterday';
    if (days < 30)  return `${days}d ago`;
    if (days < 365) return `${Math.floor(days / 30)}mo ago`;
    return `${Math.floor(days / 365)}y ago`;
  } catch { return isoStr; }
}

function fmtDate(iso) {
  if (!iso) return '—';
  const [y, m, d] = iso.split('-');
  const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
  return `${months[+m - 1]} ${+d}, ${y}`;
}

// ── State ─────────────────────────────────────────────────────────────────

function createState() {
  return {
    actresses: [],
    selectedId: null,
    activeTab: 'titles',
    queuePollTimer: null,
    queueItemsPollTimer: null,
    paused: false,
    lastActiveTotal: 0,   // pending+inFlight from previous poll, for transition detection
    rateLimitPausedUntil: null,   // ISO string or null; used to compute queue ETAs
    alphaFilter: 'All',
    tierFilter: new Set(),
    favoritesOnly: true,
    bookmarkedOnly: false,
    sortField: 'name',   // 'name' | 'titles'
    sortDir: 'asc',
    // Phase 3 surfacing filter — per-actress, applied to the Titles tab.
    // Cleared whenever the selected actress changes.
    titleFilter: { tags: [], minRatingAvg: null, minRatingCount: null },
    // Titles tab (M2) — title-driven enrichment surface.
    titles: {
      source: 'recent',          // 'recent' | 'pool'
      poolVolumeId: null,        // when source==='pool'
      pools: [],                 // [{volumeId, unenrichedCount}]
      page: 0,
      pageSize: 50,
      totalPages: 0,
      filter: '',                // code-prefix filter (case-insensitive); empty = no filter
      filterDebounce: null,
      rows: [],
      hasMore: false,
      selected: new Set(),       // selected titleIds (numbers)
      loading: false,
    },
    // Collections tab (M3) — multi-cast titles.
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

// ── DOM refs ──────────────────────────────────────────────────────────────

const view              = document.getElementById('tools-javdb-discovery-view');
const queueBadge        = document.getElementById('jd-queue-badge');
const rateLimitBanner   = document.getElementById('jd-rate-limit-banner');
const pauseBtn          = document.getElementById('jd-pause-btn');
const cancelAllBtn      = document.getElementById('jd-cancel-all-btn');
const controlsToggle    = document.getElementById('jd-controls-toggle');
const controlsPanel     = document.getElementById('jd-controls');
const alphaBar          = document.getElementById('jd-alpha-bar');
const filterBar         = document.getElementById('jd-filter-bar');
const sortBar           = document.getElementById('jd-sort-bar');
const actressList       = document.getElementById('jd-actress-list');
const emptyMsg          = document.getElementById('jd-empty');
const panel             = document.getElementById('jd-actress-panel');
const enrichBtn         = document.getElementById('jd-enrich-btn');
const cancelActressBtn  = document.getElementById('jd-cancel-actress-btn');
const subtabBtns        = panel?.querySelectorAll('.jd-subtab') ?? [];
const titlesView        = document.getElementById('jd-subview-titles');
const profileView       = document.getElementById('jd-subview-profile');
const conflictsView     = document.getElementById('jd-subview-conflicts');
const errorsView        = document.getElementById('jd-subview-errors');

// ── Queue tab DOM refs ─────────────────────────────────────────────────────

const enrichTab      = view.querySelector('[data-jd-tab="enrich"]');
const titlesTab      = view.querySelector('[data-jd-tab="titles"]');
const collectionsTab = view.querySelector('[data-jd-tab="collections"]');
const queueTab       = view.querySelector('[data-jd-tab="queue"]');
const jdBody         = view.querySelector('.jd-body');
const queueBody      = document.getElementById('jd-queue-body');
const queueEmpty     = document.getElementById('jd-queue-empty');
const queueTableWrap = document.getElementById('jd-queue-table-wrap');
const queueTableBody = document.getElementById('jd-queue-table-body');

const titlesBody       = document.getElementById('jd-titles-body');
const titlesChips      = document.getElementById('jd-titles-chips');
const titlesEmpty      = document.getElementById('jd-titles-empty');
const titlesTableWrap  = document.getElementById('jd-titles-table-wrap');
const titlesTableBody  = document.getElementById('jd-titles-table-body');
const titlesPager      = document.getElementById('jd-titles-pager');
const titlesFooter     = document.getElementById('jd-titles-footer');
const titlesFooterCnt  = document.getElementById('jd-titles-footer-count');
const titlesEnqueueBtn = document.getElementById('jd-titles-enqueue-btn');
const titlesClearBtn   = document.getElementById('jd-titles-clear-btn');
const titlesFilterInput = document.getElementById('jd-titles-filter-input');
const titlesFilterClearBtn = document.getElementById('jd-titles-filter-clear');
const titlesFilterAutocomplete = document.getElementById('jd-titles-filter-autocomplete');

const collectionsBody       = document.getElementById('jd-collections-body');
const collectionsEmpty      = document.getElementById('jd-collections-empty');
const collectionsTableWrap  = document.getElementById('jd-collections-table-wrap');
const collectionsTableBody  = document.getElementById('jd-collections-table-body');
const collectionsPager      = document.getElementById('jd-collections-pager');
const collectionsFooter     = document.getElementById('jd-collections-footer');
const collectionsFooterCnt  = document.getElementById('jd-collections-footer-count');
const collectionsEnqueueBtn = document.getElementById('jd-collections-enqueue-btn');
const collectionsClearBtn   = document.getElementById('jd-collections-clear-btn');
const collectionsFilterInput    = document.getElementById('jd-collections-filter-input');
const collectionsFilterClearBtn = document.getElementById('jd-collections-filter-clear');
const collectionsFilterAutocomplete = document.getElementById('jd-collections-filter-autocomplete');

// ── Public API ─────────────────────────────────────────────────────────────

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
  stopQueueItemsPoll();
}

// ── Data loading ───────────────────────────────────────────────────────────

async function loadActresses() {
  try {
    const res = await fetch('/api/javdb/discovery/actresses');
    if (!res.ok) return;
    state.actresses = await res.json();
    computeAlphaBuckets();
    renderAlphaBar();
    renderFilterBar();
    renderSortBar();
    renderActressList();
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
      if (failed > 0) parts.push(`${failed} failed`);
      queueBadge.textContent = parts.join(' · ');
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
          `<button type="button" class="jd-banner-resume-btn" id="jd-force-resume-btn">Resume Now</button>`;
      } else {
        const reason = rateLimitPauseReason || 'Rate limited';
        const hitNote = consecutiveRateLimitHits > 1
          ? ` (${consecutiveRateLimitHits} consecutive hits — pause doubled each time)`
          : '';
        rateLimitBanner.className = 'jd-rate-limit-banner jd-banner-rate-limit';
        rateLimitBanner.innerHTML =
          `⚠ Rate limited — ${esc(reason)}${esc(hitNote)}. Resuming at <strong class="jd-banner-time">${esc(resumeStr)}</strong>. ` +
          `<span class="jd-banner-hint">Switch VPN then </span>` +
          `<button type="button" class="jd-banner-resume-btn" id="jd-force-resume-btn">Resume Now</button>`;
      }
      rateLimitBanner.style.display = '';
      document.getElementById('jd-force-resume-btn')?.addEventListener('click', forceResume);
    } else {
      rateLimitBanner.style.display = 'none';
    }

    // Refresh actress dots and title statuses while jobs are running, and once
    // more on the tick they all clear (so yellow dots transition to green/grey).
    if (activeTotal > 0 || state.lastActiveTotal > 0) {
      await loadActresses();
      if (state.selectedId !== null && state.activeTab === 'titles') {
        await renderTitlesTabSilent();
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

function startQueueItemsPoll() {
  stopQueueItemsPoll();
  state.queueItemsPollTimer = setInterval(loadQueueItems, 5_000);
}

function stopQueueItemsPoll() {
  if (state.queueItemsPollTimer !== null) {
    clearInterval(state.queueItemsPollTimer);
    state.queueItemsPollTimer = null;
  }
}

// ── Adaptive alpha buckets ─────────────────────────────────────────────────

const BUCKET_THRESHOLD = 30;

// Computed once per data load; each entry: { label, key, test(canonicalName) }
let alphaBuckets = [{ label: 'All', key: 'All', test: () => true }];

function computeAlphaBuckets() {
  const byLetter = new Map();
  let hasNonAlpha = false;

  for (const a of state.actresses) {
    const name = a.canonicalName || '';
    const ch = name.charAt(0).toUpperCase();
    if (ch >= 'A' && ch <= 'Z') {
      if (!byLetter.has(ch)) byLetter.set(ch, []);
      byLetter.get(ch).push(name);
    } else {
      hasNonAlpha = true;
    }
  }

  const buckets = [{ label: 'All', key: 'All', test: () => true }];

  for (const [letter, names] of [...byLetter.entries()].sort()) {
    names.sort();
    if (names.length <= BUCKET_THRESHOLD) {
      buckets.push({
        label: letter,
        key: letter,
        test: n => n.charAt(0).toUpperCase() === letter,
      });
    } else {
      buckets.push(...splitLetter(letter, names));
    }
  }

  if (hasNonAlpha) {
    buckets.push({
      label: '#',
      key: '#',
      test: n => { const ch = n.charAt(0).toUpperCase(); return ch < 'A' || ch > 'Z'; },
    });
  }

  alphaBuckets = buckets;

  // Invalidate stale filter
  if (!alphaBuckets.some(b => b.key === state.alphaFilter)) {
    state.alphaFilter = 'All';
  }
}

function splitLetter(letter, sortedNames) {
  // Count entries per second character
  const bySecond = new Map();
  for (const name of sortedNames) {
    const s = (name.charAt(1) || ' ').toLowerCase();
    bySecond.set(s, (bySecond.get(s) || 0) + 1);
  }
  const seconds = [...bySecond.keys()].sort();

  const buckets = [];
  let rangeStart = seconds[0];
  let count = 0;

  for (let i = 0; i < seconds.length; i++) {
    const s = seconds[i];
    const c = bySecond.get(s);
    if (count > 0 && count + c > BUCKET_THRESHOLD) {
      buckets.push(makeBucket(letter, rangeStart, seconds[i - 1]));
      rangeStart = s;
      count = c;
    } else {
      count += c;
    }
  }
  buckets.push(makeBucket(letter, rangeStart, seconds[seconds.length - 1]));
  return buckets;
}

function makeBucket(letter, fromSecond, toSecond) {
  const lo = fromSecond.toUpperCase();
  const hi = toSecond.toUpperCase();
  const label = lo === hi
    ? `${letter}${lo}`
    : `${letter}${lo}–${letter}${hi}`;
  const key = `${letter}:${fromSecond}-${toSecond}`;
  return {
    label,
    key,
    test: n => {
      if (n.charAt(0).toUpperCase() !== letter) return false;
      const s = (n.charAt(1) || ' ').toLowerCase();
      return s >= fromSecond && s <= toSecond;
    },
  };
}

// ── Filtering & sorting helpers ────────────────────────────────────────────

function computeTier(totalTitles) {
  if (totalTitles >= 100) return 'goddess';
  if (totalTitles >= 50)  return 'superstar';
  if (totalTitles >= 20)  return 'popular';
  return null;
}

function filteredActresses() {
  let list = state.actresses;

  if (state.alphaFilter !== 'All') {
    const bucket = alphaBuckets.find(b => b.key === state.alphaFilter);
    if (bucket) list = list.filter(a => bucket.test(a.canonicalName || ''));
  }

  if (state.tierFilter.size > 0) {
    list = list.filter(a => state.tierFilter.has(computeTier(a.totalTitles)));
  }

  if (state.favoritesOnly)  list = list.filter(a => a.favorite);
  if (state.bookmarkedOnly) list = list.filter(a => a.bookmark);

  return [...list].sort((a, b) => {
    let cmp;
    if (state.sortField === 'titles') {
      cmp = a.totalTitles - b.totalTitles;
      if (cmp === 0) cmp = a.canonicalName.localeCompare(b.canonicalName);
    } else {
      cmp = a.canonicalName.localeCompare(b.canonicalName);
    }
    return state.sortDir === 'asc' ? cmp : -cmp;
  });
}

async function applyFilterChange() {
  const visible = filteredActresses();
  const stillVisible = visible.some(a => a.id === state.selectedId);
  if (!stillVisible) {
    const first = visible[0] ?? null;
    if (first) {
      state.selectedId = first.id;
      emptyMsg.style.display = 'none';
      panel.style.display = '';
      await renderActiveTab();
    } else {
      state.selectedId = null;
      emptyMsg.style.display = '';
      panel.style.display = 'none';
    }
  }
  renderActressList();
}

// ── Alpha bar ──────────────────────────────────────────────────────────────

function renderAlphaBar() {
  alphaBar.innerHTML = '';
  for (const bucket of alphaBuckets) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = bucket.label;
    btn.className = 'jd-alpha-btn' + (bucket.key === state.alphaFilter ? ' active' : '');
    btn.addEventListener('click', async () => {
      if (bucket.key === state.alphaFilter) return;
      state.alphaFilter = bucket.key;
      renderAlphaBar();
      await applyFilterChange();
    });
    alphaBar.appendChild(btn);
  }
}

// ── Filter bar ─────────────────────────────────────────────────────────────

function renderFilterBar() {
  filterBar.innerHTML = '';

  const chips = [
    { key: 'fav',       label: '♥ Favorites',  variant: 'jd-filter-fav',       active: state.favoritesOnly },
    { key: 'bkm',       label: '◉ Bookmarked', variant: 'jd-filter-bkm',       active: state.bookmarkedOnly },
    { key: 'goddess',   label: 'Goddess',      variant: 'jd-filter-goddess',   active: state.tierFilter.has('goddess') },
    { key: 'superstar', label: 'Superstar',    variant: 'jd-filter-superstar', active: state.tierFilter.has('superstar') },
    { key: 'popular',   label: 'Popular',      variant: 'jd-filter-popular',   active: state.tierFilter.has('popular') },
  ];

  for (const chip of chips) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = chip.label;
    btn.className = `jd-filter-btn ${chip.variant}` + (chip.active ? ' active' : '');
    btn.addEventListener('click', async () => {
      if (chip.key === 'fav') {
        state.favoritesOnly = !state.favoritesOnly;
      } else if (chip.key === 'bkm') {
        state.bookmarkedOnly = !state.bookmarkedOnly;
      } else {
        if (state.tierFilter.has(chip.key)) state.tierFilter.delete(chip.key);
        else state.tierFilter.add(chip.key);
      }
      renderFilterBar();
      await applyFilterChange();
    });
    filterBar.appendChild(btn);
  }
}

// ── Sort bar ───────────────────────────────────────────────────────────────

function renderSortBar() {
  sortBar.innerHTML = '';

  for (const { id, label } of [{ id: 'name', label: 'Name' }, { id: 'titles', label: 'Titles' }]) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = label;
    btn.className = 'jd-sort-btn' + (id === state.sortField ? ' active' : '');
    btn.addEventListener('click', async () => {
      if (state.sortField === id) return;
      state.sortField = id;
      renderSortBar();
      renderActressList();
    });
    sortBar.appendChild(btn);
  }

  const dirBtn = document.createElement('button');
  dirBtn.type = 'button';
  dirBtn.className = 'jd-sort-btn jd-sort-dir';
  dirBtn.title = state.sortDir === 'asc' ? 'Ascending' : 'Descending';
  dirBtn.textContent = state.sortDir === 'asc' ? '↑' : '↓';
  dirBtn.addEventListener('click', async () => {
    state.sortDir = state.sortDir === 'asc' ? 'desc' : 'asc';
    renderSortBar();
    renderActressList();
  });
  sortBar.appendChild(dirBtn);
}

// ── Actress list rendering ─────────────────────────────────────────────────

function renderActressList() {
  actressList.innerHTML = '';
  for (const a of filteredActresses()) {
    const li = document.createElement('li');
    li.className = 'jd-actress-item';
    li.dataset.id = a.id;

    const enrichedPct = a.totalTitles > 0
      ? Math.round((a.enrichedTitles / a.totalTitles) * 100)
      : 0;

    const statusDot = actressStatusDot(a);

    li.innerHTML = `
      <span class="jd-actress-name">${statusDot}${esc(a.canonicalName)}</span>
      <span class="jd-actress-counts">${a.enrichedTitles}/${a.totalTitles} (${enrichedPct}%)</span>
    `;
    li.addEventListener('click', () => selectActress(a.id));
    actressList.appendChild(li);
  }

  if (state.selectedId !== null) {
    highlightSelected(state.selectedId);
  }
}

function actressStatusDot(a) {
  if (a.activeJobs > 0) {
    return `<span class="jd-dot jd-dot-queued" title="${a.activeJobs} job${a.activeJobs !== 1 ? 's' : ''} in queue"></span>`;
  }
  if (a.enrichedTitles === a.totalTitles && a.totalTitles > 0) {
    return '<span class="jd-dot jd-dot-done" title="All titles enriched"></span>';
  }
  if (a.enrichedTitles > 0) {
    return '<span class="jd-dot jd-dot-partial" title="Partially enriched"></span>';
  }
  return '<span class="jd-dot jd-dot-none" title="Not started"></span>';
}

function highlightSelected(id) {
  actressList.querySelectorAll('.jd-actress-item').forEach(li => {
    li.classList.toggle('selected', Number(li.dataset.id) === id);
  });
}

// ── Actress selection ──────────────────────────────────────────────────────

async function selectActress(id) {
  state.selectedId = id;
  // Filter is per-actress — reset whenever a different actress is selected.
  state.titleFilter = { tags: [], minRatingAvg: null, minRatingCount: null };
  highlightSelected(id);
  emptyMsg.style.display = 'none';
  panel.style.display = '';
  await renderActiveTab();
}

async function renderActiveTab() {
  if (state.activeTab === 'titles') {
    await renderTitlesTab();
  } else if (state.activeTab === 'profile') {
    await renderProfileTab();
  } else if (state.activeTab === 'conflicts') {
    await renderConflictsTab();
  } else {
    await renderErrorsTab();
  }
}

// ── Titles tab ─────────────────────────────────────────────────────────────

async function renderTitlesTab() {
  titlesView.style.display    = '';
  profileView.style.display   = 'none';
  conflictsView.style.display = 'none';
  errorsView.style.display    = 'none';
  titlesView.innerHTML = '<div class="jd-loading">Loading…</div>';
  await fetchAndRenderTitles();
}

// Refreshes title rows without replacing the view with a loading spinner.
// Used by the queue poll so statuses update in place.
async function renderTitlesTabSilent() {
  if (titlesView.style.display === 'none') return;
  await fetchAndRenderTitles();
}

function buildFilterQueryString() {
  const f = state.titleFilter;
  const parts = [];
  if (f.tags.length > 0)              parts.push(`tags=${encodeURIComponent(f.tags.join(','))}`);
  if (f.minRatingAvg   !== null)      parts.push(`minRatingAvg=${f.minRatingAvg}`);
  if (f.minRatingCount !== null)      parts.push(`minRatingCount=${f.minRatingCount}`);
  return parts.length > 0 ? '?' + parts.join('&') : '';
}

function isFilterActive() {
  const f = state.titleFilter;
  return f.tags.length > 0 || f.minRatingAvg !== null || f.minRatingCount !== null;
}

async function fetchAndRenderTitles() {
  try {
    const qs = buildFilterQueryString();
    const [titlesRes, facetsRes] = await Promise.all([
      fetch(`/api/javdb/discovery/actresses/${state.selectedId}/titles${qs}`),
      fetch(`/api/javdb/discovery/actresses/${state.selectedId}/tag-facets${qs}`),
    ]);
    if (!titlesRes.ok) { titlesView.innerHTML = '<div class="jd-error">Failed to load titles.</div>'; return; }
    const titles = await titlesRes.json();
    const facets = facetsRes.ok ? await facetsRes.json() : [];
    titlesView.innerHTML = filterBarHtml(facets, titles.length) + titlesTableHtml(titles);
    wireFilterBar();
    wireReenrichButtons();
    wireEnrichmentDetailTriggers();
  } catch (_) {
    titlesView.innerHTML = '<div class="jd-error">Network error.</div>';
  }
}

function filterBarHtml(facets, matchCount) {
  const f = state.titleFilter;
  const selectedTagsSet = new Set(f.tags);
  // Selected tags first (so they remain visible after selection), then top facets by count.
  const facetOrder = [
    ...facets.filter(x => selectedTagsSet.has(x.name)),
    ...facets.filter(x => !selectedTagsSet.has(x.name)),
  ];
  const tagChips = facetOrder.slice(0, 30).map(x => {
    const sel = selectedTagsSet.has(x.name);
    return `<button type="button" class="jd-tag-chip${sel ? ' selected' : ''}" data-tag="${esc(x.name)}">
      ${esc(x.name)} <span class="jd-tag-count">${x.count}</span>
    </button>`;
  }).join('');
  const summary = isFilterActive()
    ? `<span class="jd-filter-summary">${matchCount} matching</span>`
    : '';
  const clearBtn = isFilterActive()
    ? `<button type="button" id="jd-filter-clear" class="jd-filter-clear">Clear filters</button>`
    : '';
  return `
    <div class="jd-filter-bar">
      <div class="jd-filter-row">
        <label class="jd-filter-label">Min rating</label>
        <input id="jd-min-avg" type="number" step="0.1" min="0" max="5" placeholder="e.g. 4.2"
               value="${f.minRatingAvg ?? ''}" class="jd-filter-num">
        <label class="jd-filter-label">Min votes</label>
        <input id="jd-min-cnt" type="number" step="1" min="0" placeholder="e.g. 50"
               value="${f.minRatingCount ?? ''}" class="jd-filter-num">
        ${summary}
        ${clearBtn}
      </div>
      <div class="jd-filter-row jd-tag-chips">${tagChips || '<span class="jd-filter-hint">No tags on this actress\'s enriched titles.</span>'}</div>
    </div>
  `;
}

function titlesTableHtml(titles) {
  if (titles.length === 0) {
    return '<div class="jd-empty-tab">No titles match the current filter.</div>';
  }
  return `
    <table class="jd-titles-table">
      <thead><tr>
        <th>Code</th><th>Status</th><th>Original Title</th><th>Release</th><th>Maker</th><th>Rating</th><th></th>
      </tr></thead>
      <tbody>${titles.map(titleRow).join('')}</tbody>
    </table>
  `;
}

function wireFilterBar() {
  const minAvg = document.getElementById('jd-min-avg');
  const minCnt = document.getElementById('jd-min-cnt');
  if (minAvg) minAvg.addEventListener('change', () => {
    const v = minAvg.value.trim();
    state.titleFilter.minRatingAvg = v === '' ? null : parseFloat(v);
    fetchAndRenderTitles();
  });
  if (minCnt) minCnt.addEventListener('change', () => {
    const v = minCnt.value.trim();
    state.titleFilter.minRatingCount = v === '' ? null : parseInt(v, 10);
    fetchAndRenderTitles();
  });
  document.querySelectorAll('.jd-tag-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      const tag = chip.dataset.tag;
      const idx = state.titleFilter.tags.indexOf(tag);
      if (idx >= 0) state.titleFilter.tags.splice(idx, 1);
      else state.titleFilter.tags.push(tag);
      fetchAndRenderTitles();
    });
  });
  const clearBtn = document.getElementById('jd-filter-clear');
  if (clearBtn) clearBtn.addEventListener('click', () => {
    state.titleFilter = { tags: [], minRatingAvg: null, minRatingCount: null };
    fetchAndRenderTitles();
  });
}

function wireReenrichButtons() {
  titlesView.querySelectorAll('.jd-reenrich-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const titleId = btn.dataset.titleId;
      const original = btn.textContent;
      btn.textContent = '⌛';
      btn.disabled = true;
      btn.classList.add('jd-btn-busy');
      try {
        const r = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/titles/${titleId}/reenrich`, { method: 'POST' });
        if (!r.ok) throw new Error('http ' + r.status);
        btn.classList.remove('jd-btn-busy');
        btn.classList.add('jd-btn-success');
        btn.textContent = '✓';
        btn.title = 'Queued — see Queue tab';
        setTimeout(() => { renderTitlesTabSilent(); }, 800);
      } catch (e) {
        btn.classList.remove('jd-btn-busy');
        btn.classList.add('jd-btn-error');
        btn.textContent = '✗';
        setTimeout(() => {
          btn.classList.remove('jd-btn-error');
          btn.textContent = original;
          btn.disabled = false;
        }, 2500);
      }
    });
  });
}

function wireEnrichmentDetailTriggers() {
  titlesView.querySelectorAll('.jd-enrich-detail-btn, .jd-status-clickable').forEach(el => {
    el.addEventListener('click', e => {
      e.stopPropagation();
      openEnrichmentModal(Number(el.dataset.titleId));
    });
  });
}

// ── Enrichment detail modal ────────────────────────────────────────────────

const enrichModalOverlay = document.getElementById('jd-enrich-modal-overlay');
const enrichModalBody    = document.getElementById('jd-enrich-modal-body');
const enrichModalHeading = document.getElementById('jd-enrich-modal-heading');
const enrichModalClose   = document.getElementById('jd-enrich-modal-close');

enrichModalClose.addEventListener('click', closeEnrichmentModal);
enrichModalOverlay.addEventListener('click', e => {
  if (e.target === enrichModalOverlay) closeEnrichmentModal();
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && enrichModalOverlay.style.display !== 'none') closeEnrichmentModal();
});

function closeEnrichmentModal() {
  enrichModalOverlay.style.display = 'none';
  enrichModalBody.innerHTML = '';
}

async function openEnrichmentModal(titleId) {
  enrichModalHeading.innerHTML = '<span class="jd-enrich-modal-code">Loading…</span>';
  enrichModalBody.innerHTML = '<div class="jd-loading">Loading…</div>';
  enrichModalOverlay.style.display = 'flex';
  try {
    const res = await fetch(`/api/javdb/discovery/titles/${titleId}/enrichment`);
    if (!res.ok) { enrichModalBody.innerHTML = '<div class="jd-error">Failed to load enrichment data.</div>'; return; }
    const d = await res.json();
    renderEnrichmentModal(d);
  } catch (_) {
    enrichModalBody.innerHTML = '<div class="jd-error">Network error.</div>';
  }
}

function renderEnrichmentModal(d) {
  enrichModalHeading.innerHTML = `
    <span class="jd-enrich-modal-code">${esc(d.code)}</span>
    ${d.titleOriginal ? `<span class="jd-enrich-modal-title">${esc(d.titleOriginal)}</span>` : ''}
  `;

  const metaRows = [];
  if (d.releaseDate)      metaRows.push(['Release', fmtDate(d.releaseDate)]);
  if (d.durationMinutes)  metaRows.push(['Duration', `${d.durationMinutes} min`]);
  if (d.maker)            metaRows.push(['Maker', esc(d.maker)]);
  if (d.publisher && d.publisher !== d.maker) metaRows.push(['Publisher', esc(d.publisher)]);
  if (d.series)           metaRows.push(['Series', esc(d.series)]);
  if (d.ratingAvg != null) {
    const votes = d.ratingCount != null ? ` · ${d.ratingCount} votes` : '';
    metaRows.push(['Rating', `${d.ratingAvg.toFixed(2)} / 5${votes}`]);
  }

  const metaHtml = metaRows.length > 0
    ? `<div>
        <div class="jd-enrich-section-label">Details</div>
        <div class="jd-enrich-meta-grid">
          ${metaRows.map(([k, v]) => `<span class="jd-enrich-meta-label">${k}</span><span class="jd-enrich-meta-value">${v}</span>`).join('')}
        </div>
      </div>`
    : '';

  const cast = parseCast(d.castJson);
  const castHtml = cast.length > 0
    ? `<div>
        <div class="jd-enrich-section-label">Cast</div>
        <div class="jd-enrich-cast-list">
          ${cast.map(e => `<span class="jd-enrich-cast-name">${esc(e.name)}</span>`).join('')}
        </div>
      </div>`
    : '';

  const tagsHtml = d.tags && d.tags.length > 0
    ? `<div>
        <div class="jd-enrich-section-label">Tags from javdb</div>
        <div class="jd-enrich-tag-list">
          ${d.tags.map(t => `<span class="jd-enrich-tag">${esc(t)}</span>`).join('')}
        </div>
      </div>`
    : '';

  const javdbUrl = d.javdbSlug ? `https://javdb.com/v/${esc(d.javdbSlug)}` : null;
  const footerHtml = `
    <div class="jd-enrich-footer">
      ${javdbUrl ? `<a class="jd-enrich-source-link" href="${javdbUrl}" target="_blank" rel="noopener">View on javdb ↗</a>` : '<span></span>'}
      ${d.fetchedAt ? `<span class="jd-enrich-fetched-at">Fetched ${fmtDate(d.fetchedAt.slice(0, 10))}</span>` : ''}
    </div>
  `;

  enrichModalBody.innerHTML = metaHtml + castHtml + tagsHtml + footerHtml;
}

function titleEffectiveStatus(t) {
  if (t.queueStatus === 'in_flight') return { key: 'in_flight', label: '⟳ In Progress' };
  if (t.queueStatus === 'pending')   return { key: 'pending',   label: '◌ Queued' };
  if (t.status === 'fetched')        return { key: 'fetched',   label: '✓ Enriched' };
  if (t.queueStatus === 'failed')    return { key: 'failed',    label: '✗ Failed' };
  if (t.status === 'slug_only')      return { key: 'slug_only', label: '⌁ Slug Only' };
  if (t.queueStatus === 'done')      return { key: 'done',      label: '✓ Done' };
  return { key: 'none', label: '— Not Started' };
}

function titleRow(t) {
  const { key, label } = titleEffectiveStatus(t);
  const isEnriched = key === 'fetched';
  const statusCell = isEnriched
    ? `<span class="jd-status jd-status-${key} jd-status-clickable" data-title-id="${t.titleId}" title="View enrichment details">${label}</span>`
    : `<span class="jd-status jd-status-${key}">${label}</span>`;
  const canReenrich = isEnriched || key === 'done' || key === 'failed' || t.status === 'not_found';
  const infoBtn = isEnriched
    ? `<button class="jd-enrich-detail-btn" data-title-id="${t.titleId}" title="View enrichment details">ⓘ</button>`
    : '';
  const reenrichBtn = canReenrich
    ? `<button class="jd-reenrich-btn" data-title-id="${t.titleId}" title="Force re-enrich">↺</button>`
    : '';
  const rating = (t.ratingAvg != null)
    ? `<span class="jd-rating">${t.ratingAvg.toFixed(2)}<span class="jd-rating-count"> · ${t.ratingCount ?? 0}</span></span>`
    : '—';
  return `<tr>
    <td class="jd-code">${esc(t.code)}</td>
    <td>${statusCell}</td>
    <td>${t.titleOriginal ? esc(t.titleOriginal) : '—'}</td>
    <td>${fmtDate(t.releaseDate)}</td>
    <td>${t.maker ? esc(t.maker) : '—'}</td>
    <td class="jd-rating-cell">${rating}</td>
    <td class="jd-action-cell">${infoBtn}${reenrichBtn}</td>
  </tr>`;
}

// ── Profile tab ────────────────────────────────────────────────────────────

async function renderProfileTab() {
  profileView.style.display   = '';
  titlesView.style.display    = 'none';
  conflictsView.style.display = 'none';
  errorsView.style.display    = 'none';
  profileView.innerHTML = '<div class="jd-loading">Loading…</div>';
  try {
    const res = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/profile`);
    if (res.status === 404) {
      const selected = state.actresses?.find(a => a.id === state.selectedId);
      const enriched = selected?.enrichedTitles ?? 0;
      const canDerive = enriched > 0;
      profileView.innerHTML = `
        <div class="jd-empty-tab">
          <div>No staging profile yet.</div>
          ${canDerive ? `
            <div class="jd-derive-help">
              ${enriched} title(s) enriched but no slug recorded. Derive the slug from her co-stars'
              cast lists and queue a profile fetch:
            </div>
            <div class="jd-profile-actions">
              <button id="jd-derive-slug-btn" class="jd-action-btn jd-muted-btn">⚡ Find Slug from Titles</button>
            </div>
            <div id="jd-derive-result"></div>
          ` : `
            <div class="jd-derive-help">No enriched titles yet — no cast data to derive a slug from.</div>
          `}
        </div>`;
      const dBtn = document.getElementById('jd-derive-slug-btn');
      if (dBtn) {
        dBtn.addEventListener('click', () => deriveSlugForSelected(dBtn));
      }
      return;
    }
    if (!res.ok) { profileView.innerHTML = '<div class="jd-error">Failed to load profile.</div>'; return; }
    const p = await res.json();
    profileView.innerHTML = `
      <div class="jd-profile">
        ${(p.localAvatarUrl || p.avatarUrl) ? `<img class="jd-avatar" src="${esc(p.localAvatarUrl || p.avatarUrl)}" alt="avatar">` : ''}
        <dl class="jd-profile-fields">
          <dt>Slug</dt><dd>${p.javdbSlug ? esc(p.javdbSlug) : '—'}</dd>
          <dt>Status</dt><dd><span class="jd-status jd-status-${esc(p.status ?? 'none')}">${esc(p.status ?? '—')}</span></dd>
          <dt>Fetched at</dt><dd>${p.rawFetchedAt ? esc(p.rawFetchedAt) : '—'}</dd>
          <dt>Title count</dt><dd>${p.titleCount != null ? p.titleCount : '—'}</dd>
          <dt>Twitter</dt><dd>${p.twitterHandle ? esc(p.twitterHandle) : '—'}</dd>
          <dt>Instagram</dt><dd>${p.instagramHandle ? esc(p.instagramHandle) : '—'}</dd>
          ${p.nameVariantsJson ? `<dt>Name variants</dt><dd class="jd-variants">${esc(p.nameVariantsJson)}</dd>` : ''}
        </dl>
        <div class="jd-profile-actions">
          <button id="jd-refetch-profile-btn" class="jd-action-btn jd-muted-btn">↺ Re-fetch Profile</button>
          ${(p.avatarUrl && !p.localAvatarUrl)
            ? `<button id="jd-download-avatar-btn" class="jd-action-btn jd-muted-btn">⬇ Download Avatar</button>`
            : ''}
        </div>
      </div>
    `;
    document.getElementById('jd-refetch-profile-btn').addEventListener('click', async () => {
      const btn = document.getElementById('jd-refetch-profile-btn');
      const original = btn.textContent;
      btn.textContent = '⌛ Enqueuing…';
      btn.disabled = true;
      btn.classList.add('jd-btn-busy');
      try {
        const r = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/profile/reenrich`, { method: 'POST' });
        if (!r.ok) throw new Error('http ' + r.status);
        btn.classList.remove('jd-btn-busy');
        btn.classList.add('jd-btn-success');
        btn.textContent = '✓ Queued — see Queue tab';
        setTimeout(() => {
          btn.classList.remove('jd-btn-success');
          btn.textContent = original;
          btn.disabled = false;
        }, 2500);
      } catch (e) {
        btn.classList.remove('jd-btn-busy');
        btn.classList.add('jd-btn-error');
        btn.textContent = '✗ Failed';
        setTimeout(() => {
          btn.classList.remove('jd-btn-error');
          btn.textContent = original;
          btn.disabled = false;
        }, 2500);
      }
    });
    const dlBtn = document.getElementById('jd-download-avatar-btn');
    if (dlBtn) {
      dlBtn.addEventListener('click', async () => {
        const original = dlBtn.textContent;
        dlBtn.textContent = '⌛ Downloading…';
        dlBtn.disabled = true;
        dlBtn.classList.add('jd-btn-busy');
        try {
          const r = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/avatar/download`, { method: 'POST' });
          const body = await r.json().catch(() => ({}));
          if (!r.ok) {
            const reason = body.status === 'no_url'  ? 'no avatar URL on profile'
                         : body.status === 'failed'  ? 'CDN download failed'
                         : body.status === 'no_profile' ? 'no profile yet'
                         : `error (${r.status})`;
            dlBtn.classList.remove('jd-btn-busy');
            dlBtn.classList.add('jd-btn-error');
            dlBtn.textContent = `✗ ${reason}`;
            setTimeout(() => {
              dlBtn.classList.remove('jd-btn-error');
              dlBtn.textContent = original;
              dlBtn.disabled = false;
            }, 2500);
            return;
          }
          dlBtn.classList.remove('jd-btn-busy');
          dlBtn.classList.add('jd-btn-success');
          dlBtn.textContent = '✓ Downloaded';
          setTimeout(() => { renderProfileTab(); }, 700);
        } catch (e) {
          dlBtn.classList.remove('jd-btn-busy');
          dlBtn.classList.add('jd-btn-error');
          dlBtn.textContent = '✗ network error';
          setTimeout(() => {
            dlBtn.classList.remove('jd-btn-error');
            dlBtn.textContent = original;
            dlBtn.disabled = false;
          }, 2500);
        }
      });
    }
  } catch (_) {
    profileView.innerHTML = '<div class="jd-error">Network error.</div>';
  }
}

async function deriveSlugForSelected(btn) {
  const original = btn.textContent;
  btn.textContent = '⌛ Deriving…';
  btn.disabled = true;
  btn.classList.add('jd-btn-busy');
  const out = document.getElementById('jd-derive-result');
  try {
    const r = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/profile/derive-slug`, { method: 'POST' });
    const body = await r.json().catch(() => ({}));
    if (r.ok) {
      btn.classList.remove('jd-btn-busy');
      btn.classList.add('jd-btn-success');
      btn.textContent = body.status === 'already_resolved' ? '✓ Already had slug — re-queued' : '✓ Slug found — queued';
      if (out) {
        out.innerHTML = `
          <div class="jd-derive-success">
            Picked slug <code>${esc(body.chosenSlug)}</code>${body.chosenName ? ` (${esc(body.chosenName)})` : ''}
            ${body.chosenTitleCount ? ` — appears in ${body.chosenTitleCount} of her ${body.totalEnrichedTitles} enriched titles.` : ''}
            See Queue tab for fetch progress.
          </div>`;
      }
      setTimeout(() => renderProfileTab(), 1500);
      return;
    }
    btn.classList.remove('jd-btn-busy');
    btn.classList.add('jd-btn-error');
    if (body.status === 'ambiguous' && out) {
      const rows = (body.candidates || []).slice(0, 5).map(c =>
        `<tr><td><code>${esc(c.slug)}</code></td><td>${esc(c.name || '—')}</td><td>${c.titleCount}</td></tr>`).join('');
      btn.textContent = '✗ Ambiguous';
      out.innerHTML = `
        <div class="jd-derive-error">
          Top candidates are tied. Manual selection needed:
          <table class="jd-derive-candidates">
            <thead><tr><th>Slug</th><th>Name</th><th>Title count</th></tr></thead>
            <tbody>${rows}</tbody>
          </table>
        </div>`;
    } else if (body.status === 'no_data') {
      btn.textContent = '✗ No cast data';
      if (out) out.innerHTML = `<div class="jd-derive-error">Enriched titles have no cast data with slugs.</div>`;
    } else {
      btn.textContent = `✗ Error (${r.status})`;
    }
    setTimeout(() => {
      btn.classList.remove('jd-btn-error');
      btn.textContent = original;
      btn.disabled = false;
    }, 3500);
  } catch (e) {
    btn.classList.remove('jd-btn-busy');
    btn.classList.add('jd-btn-error');
    btn.textContent = '✗ Network error';
    setTimeout(() => {
      btn.classList.remove('jd-btn-error');
      btn.textContent = original;
      btn.disabled = false;
    }, 2500);
  }
}

// ── Conflicts tab ──────────────────────────────────────────────────────────

async function renderConflictsTab() {
  conflictsView.style.display = '';
  titlesView.style.display    = 'none';
  profileView.style.display   = 'none';
  errorsView.style.display    = 'none';
  conflictsView.innerHTML = '<div class="jd-loading">Loading…</div>';
  try {
    const res = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/conflicts`);
    if (!res.ok) { conflictsView.innerHTML = '<div class="jd-error">Failed to load conflicts.</div>'; return; }
    const rows = await res.json();
    if (rows.length === 0) {
      conflictsView.innerHTML = '<div class="jd-empty-tab">No conflicts — javdb cast matches for all enriched titles.</div>';
      return;
    }
    const slug = rows[0].ourJavdbSlug;
    const slugNote = slug
      ? `We are looking for slug <code class="jd-inline-code">${esc(slug)}</code> in the Discovery cast.`
      : `No Discovery profile slug on record — profile fetch may still be pending.`;
    conflictsView.innerHTML = `
      <div class="jd-conflict-explainer">
        <strong>${rows[0].ourActressName}</strong> is attributed to these titles in our library,
        but Discovery's enriched cast data does not include her slug.
        ${slugNote}
        Either the javdb cast omits her, she appears under a different slug, or the title was attributed incorrectly.
      </div>
      <table class="jd-titles-table jd-conflicts-table">
        <thead><tr>
          <th>Code</th><th>javdb Cast (name · slug)</th>
        </tr></thead>
        <tbody>${rows.map(conflictRow).join('')}</tbody>
      </table>
    `;
    conflictsView.addEventListener('click', e => {
      const btn = e.target.closest('.jd-cover-link[data-cover-url]');
      if (btn) showJdCoverModal(btn.dataset.coverUrl, btn.dataset.code);
    });
  } catch (_) {
    conflictsView.innerHTML = '<div class="jd-error">Network error.</div>';
  }
}

function conflictRow(r) {
  const cast = parseCast(r.castJson);
  const castEntries = cast.length > 0
    ? cast.map(e => `<span class="jd-cast-entry">${esc(e.name)}<span class="jd-cast-slug"> · ${esc(e.slug ?? '?')}</span></span>`).join('')
    : '<span class="jd-muted">— (empty cast)</span>';
  const codeCell = r.coverUrl
    ? `<button class="jd-cover-link" data-cover-url="${esc(r.coverUrl)}" data-code="${esc(r.code)}">${esc(r.code)}</button>`
    : esc(r.code);
  return `<tr>
    <td class="jd-code">${codeCell}</td>
    <td class="jd-conflict-cast">${castEntries}</td>
  </tr>`;
}

// ── Cover preview modal ─────────────────────────────────────────────────────

function showJdCoverModal(coverUrl, code) {
  document.querySelector('.jd-cover-overlay')?.remove();

  const overlay = document.createElement('div');
  overlay.className = 'jd-cover-overlay';

  const img = document.createElement('img');
  img.className = 'jd-cover-modal-img';
  img.src = coverUrl;
  img.alt = code;

  const label = document.createElement('div');
  label.className = 'jd-cover-modal-label';
  label.textContent = code;

  const box = document.createElement('div');
  box.className = 'jd-cover-modal-box';
  box.appendChild(img);
  box.appendChild(label);
  overlay.appendChild(box);
  document.body.appendChild(overlay);

  const ac = new AbortController();
  const close = () => { overlay.remove(); ac.abort(); };
  overlay.addEventListener('click', e => { if (e.target === overlay) close(); }, { signal: ac.signal });
  document.addEventListener('keydown', e => { if (e.key === 'Escape') close(); }, { signal: ac.signal });
}

function parseCast(castJson) {
  if (!castJson) return [];
  try { return JSON.parse(castJson); } catch (_) { return []; }
}

// ── Errors tab ─────────────────────────────────────────────────────────────

const ERROR_REASON_LABELS = {
  ambiguous:                 'Ambiguous match',
  no_match:                  'No match on JavDB',
  fetch_failed:              'Fetch failed',
  cast_anomaly:              'Cast anomaly',
  not_found:                 'Not found',
  sentinel_actress:          'Sentinel actress',
  no_match_in_filmography:   'Not in filmography',
  no_slug:                   'No slug available',
  unknown_job_type:          'Unknown job type',
  title_not_in_db:           'Title not in DB',
};

function errorReasonLabel(raw) {
  return ERROR_REASON_LABELS[raw] || raw || '(unknown)';
}

async function renderErrorsTab() {
  errorsView.style.display    = '';
  titlesView.style.display    = 'none';
  profileView.style.display   = 'none';
  conflictsView.style.display = 'none';
  errorsView.innerHTML = '<div class="jd-loading">Loading…</div>';
  try {
    const res = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/errors`);
    if (!res.ok) { errorsView.innerHTML = '<div class="jd-error">Failed to load errors.</div>'; return; }
    const jobs = await res.json();
    if (jobs.length === 0) {
      errorsView.innerHTML = '<div class="jd-empty-tab">No failed jobs.</div>';
      return;
    }
    errorsView.innerHTML = '';

    const actionsBar = document.createElement('div');
    actionsBar.className = 'jd-errors-actions';
    const retryAllBtn = document.createElement('button');
    retryAllBtn.type = 'button';
    retryAllBtn.className = 'jd-action-btn jd-retry-btn';
    retryAllBtn.textContent = `Retry All (${jobs.length})`;
    retryAllBtn.addEventListener('click', () => retryActress());
    actionsBar.appendChild(retryAllBtn);
    errorsView.appendChild(actionsBar);

    const list = document.createElement('ul');
    list.className = 'jd-errors-list';
    for (const job of jobs) {
      list.appendChild(makeErrorRow(job, list));
    }
    errorsView.appendChild(list);
  } catch (_) {
    errorsView.innerHTML = '<div class="jd-error">Network error.</div>';
  }
}

function makeErrorRow(job, list) {
  const li = document.createElement('li');
  li.className = 'jd-error-row';
  const isAmbiguous = job.lastError === 'ambiguous';

  let codeSpan;
  if (job.coverUrl) {
    codeSpan = document.createElement('button');
    codeSpan.className = 'jd-error-code jd-cover-link';
    codeSpan.addEventListener('click', () => showJdCoverModal(job.coverUrl, job.titleCode || ''));
  } else {
    codeSpan = document.createElement('span');
    codeSpan.className = 'jd-error-code';
  }
  codeSpan.textContent = job.titleCode || '(unknown title)';

  const reasonSpan = document.createElement('span');
  reasonSpan.className = `jd-error-msg jd-error-reason-${esc(job.lastError || 'unknown')}`;
  reasonSpan.textContent = errorReasonLabel(job.lastError);

  const acts = document.createElement('span');
  acts.className = 'jd-error-row-actions';

  if (job.titleId) {
    const retryBtn = document.createElement('button');
    retryBtn.type = 'button';
    retryBtn.className = 'jd-action-btn jd-retry-btn jd-error-retry-btn';
    retryBtn.textContent = 'Retry';
    retryBtn.addEventListener('click', async () => {
      retryBtn.disabled = true;
      retryBtn.textContent = 'Retrying…';
      try {
        const r = await fetch(
          `/api/javdb/discovery/actresses/${state.selectedId}/titles/${job.titleId}/reenrich`,
          { method: 'POST' }
        );
        if (r.ok) {
          li.style.opacity = '0.4';
          await Promise.all([refreshQueue(), renderErrorsTab()]);
        } else {
          retryBtn.disabled = false;
          retryBtn.textContent = 'Retry';
        }
      } catch (_) {
        retryBtn.disabled = false;
        retryBtn.textContent = 'Retry';
      }
    });
    acts.appendChild(retryBtn);
  }

  if (isAmbiguous && job.reviewQueueId) {
    const pickerBtn = document.createElement('button');
    pickerBtn.type = 'button';
    pickerBtn.className = 'jd-action-btn jd-error-picker-btn';
    pickerBtn.textContent = 'Open picker';
    pickerBtn.addEventListener('click', () => toggleErrorPicker(job, li, list, pickerBtn));
    acts.appendChild(pickerBtn);
  }

  li.appendChild(codeSpan);
  li.appendChild(reasonSpan);
  li.appendChild(acts);
  return li;
}

function toggleErrorPicker(job, li, list, btn) {
  const next = li.nextElementSibling;
  if (next && next.classList.contains('jd-error-picker-li')) {
    next.remove();
    btn.classList.remove('jd-error-picker-btn-active');
    return;
  }
  list.querySelectorAll('.jd-error-picker-li').forEach(el => el.remove());
  list.querySelectorAll('.jd-error-picker-btn-active').forEach(b => b.classList.remove('jd-error-picker-btn-active'));
  btn.classList.add('jd-error-picker-btn-active');

  const pickerLi = document.createElement('li');
  pickerLi.className = 'jd-error-picker-li';
  const panel = document.createElement('div');
  panel.className = 'er-picker-panel';
  pickerLi.appendChild(panel);
  li.insertAdjacentElement('afterend', pickerLi);

  let detail = null;
  try { detail = job.reviewDetail ? JSON.parse(job.reviewDetail) : null; } catch {}
  if (!detail || !detail.candidates || detail.candidates.length === 0) {
    renderErrorPickerMissing(panel, job, li, pickerLi);
  } else {
    renderErrorPickerContent(panel, job, detail, li, pickerLi);
  }
}

function renderErrorPickerMissing(panel, job, li, pickerLi) {
  panel.innerHTML = `
    <div class="er-picker-missing">
      <span>Candidates not yet loaded.</span>
      <button type="button" class="er-picker-load-btn">Load candidates</button>
    </div>
  `;
  panel.querySelector('.er-picker-load-btn').addEventListener('click', async () => {
    await doErrorRefreshCandidates(panel, job, li, pickerLi);
  });
}

function renderErrorPickerContent(panel, job, detail, li, pickerLi) {
  const linkedSlugs = new Set(detail.linked_slugs || []);
  const age = formatRelative(detail.fetched_at);
  panel.innerHTML = '';

  const header = document.createElement('div');
  header.className = 'er-picker-header';
  header.innerHTML = `
    <span class="er-picker-age">Candidates fetched ${esc(age)}</span>
    <button type="button" class="er-picker-refresh-btn">Refresh candidates</button>
  `;
  panel.appendChild(header);
  header.querySelector('.er-picker-refresh-btn').addEventListener('click', async () => {
    await doErrorRefreshCandidates(panel, job, li, pickerLi);
  });

  const cards = document.createElement('div');
  cards.className = 'er-candidate-cards';
  detail.candidates.forEach(c => {
    cards.appendChild(buildErrorCandidateCard(job, c, linkedSlugs, li, pickerLi));
  });
  panel.appendChild(cards);

  const footer = document.createElement('div');
  footer.className = 'er-picker-footer';
  const noneBtn = document.createElement('button');
  noneBtn.type = 'button';
  noneBtn.className = 'er-picker-none-btn';
  noneBtn.textContent = 'None of these (accept as gap)';
  noneBtn.addEventListener('click', async () => {
    await doErrorResolve(job.reviewQueueId, 'accepted_gap', li, pickerLi);
  });
  footer.appendChild(noneBtn);
  panel.appendChild(footer);
}

function buildErrorCandidateCard(job, candidate, linkedSlugs, li, pickerLi) {
  const card = document.createElement('div');
  card.className = 'er-candidate-card';

  const cover = document.createElement('div');
  cover.className = 'er-candidate-cover';
  if (candidate.cover_url) {
    const img = document.createElement('img');
    img.src = candidate.cover_url;
    img.alt = '';
    img.loading = 'lazy';
    img.className = 'er-candidate-img';
    cover.appendChild(img);
  } else {
    cover.innerHTML = '<div class="er-candidate-no-cover">No cover</div>';
  }
  card.appendChild(cover);

  const info = document.createElement('div');
  info.className = 'er-candidate-info';

  const titleEl = document.createElement('div');
  titleEl.className = 'er-candidate-title';
  titleEl.textContent = candidate.title_original || '(no title)';
  info.appendChild(titleEl);

  const meta = document.createElement('div');
  meta.className = 'er-candidate-meta';
  meta.textContent = [candidate.release_date, candidate.maker].filter(Boolean).join(' · ');
  info.appendChild(meta);

  const castEl = document.createElement('div');
  castEl.className = 'er-candidate-cast';
  (candidate.cast || []).forEach(ce => {
    const span = document.createElement('span');
    span.className = 'er-cast-name' + (linkedSlugs.has(ce.slug) ? ' er-cast-linked' : '');
    span.textContent = ce.name || ce.slug || '?';
    castEl.appendChild(span);
  });
  info.appendChild(castEl);

  const pickBtn = document.createElement('button');
  pickBtn.type = 'button';
  pickBtn.className = 'er-pick-btn';
  pickBtn.textContent = 'Pick this';
  pickBtn.addEventListener('click', async () => {
    pickBtn.disabled = true;
    pickBtn.textContent = 'Picking…';
    try {
      const res = await fetch(`/api/utilities/enrichment-review/queue/${job.reviewQueueId}/pick`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ slug: candidate.slug }),
      });
      const data = await res.json();
      if (!res.ok || !data.ok) {
        alert('Pick failed: ' + (data.error || data.message || res.statusText));
        pickBtn.disabled = false;
        pickBtn.textContent = 'Pick this';
      } else {
        pickerLi.remove();
        li.remove();
        await Promise.all([refreshQueue(), renderErrorsTab()]);
      }
    } catch (err) {
      alert('Pick failed: ' + err.message);
      pickBtn.disabled = false;
      pickBtn.textContent = 'Pick this';
    }
  });
  info.appendChild(pickBtn);
  card.appendChild(info);
  return card;
}

async function doErrorRefreshCandidates(panel, job, li, pickerLi) {
  const btn = panel.querySelector('.er-picker-refresh-btn, .er-picker-load-btn');
  if (btn) { btn.disabled = true; btn.textContent = 'Loading…'; }
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${job.reviewQueueId}/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    });
    const data = await res.json();
    if (!res.ok || !data.ok) {
      alert('Refresh failed: ' + (data.error || data.message || res.statusText));
      if (btn) { btn.disabled = false; btn.textContent = 'Refresh candidates'; }
      return;
    }
    job.reviewDetail = data.detailJson;
    let freshDetail = null;
    try { freshDetail = data.detailJson ? JSON.parse(data.detailJson) : null; } catch {}
    panel.innerHTML = '';
    if (!freshDetail || !freshDetail.candidates || freshDetail.candidates.length === 0) {
      renderErrorPickerMissing(panel, job, li, pickerLi);
    } else {
      renderErrorPickerContent(panel, job, freshDetail, li, pickerLi);
    }
  } catch (err) {
    alert('Refresh failed: ' + err.message);
    if (btn) { btn.disabled = false; btn.textContent = 'Refresh candidates'; }
  }
}

async function doErrorResolve(reviewQueueId, resolution, li, pickerLi) {
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${reviewQueueId}/resolve`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolution }),
    });
    const data = await res.json();
    if (!res.ok || !data.ok) {
      alert('Resolve failed: ' + (data.error || data.message || res.statusText));
      return;
    }
    pickerLi.remove();
    li.remove();
  } catch (err) {
    alert('Resolve failed: ' + err.message);
  }
}

// ── M3 actions ─────────────────────────────────────────────────────────────

async function enrichActress() {
  if (state.selectedId === null) return;
  enrichBtn.disabled = true;
  const originalLabel = enrichBtn.textContent;
  enrichBtn.textContent = 'Enqueueing…';
  try {
    const res = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/enqueue`, { method: 'POST' });
    if (res.ok) {
      const { enqueued } = await res.json();
      enrichBtn.textContent = `Enqueued ${enqueued} ✓`;
      await Promise.all([loadActresses(), refreshQueue()]);
      if (state.activeTab === 'titles') await renderTitlesTabSilent();
      setTimeout(() => { enrichBtn.textContent = originalLabel; enrichBtn.disabled = false; }, 1500);
      return;
    }
  } catch (_) { /* fall through */ }
  enrichBtn.textContent = originalLabel;
  enrichBtn.disabled = false;
}

async function cancelActress() {
  if (state.selectedId === null) return;
  await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/queue`, { method: 'DELETE' });
  await refreshQueue();
}

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

async function retryActress() {
  if (state.selectedId === null) return;
  await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/retry`, { method: 'POST' });
  await Promise.all([refreshQueue(), renderErrorsTab()]);
}

// ── Tab switching ──────────────────────────────────────────────────────────

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
    loadQueueItems();
    startQueueItemsPoll();
  } else {
    stopQueueItemsPoll();
  }
  if (tab === 'titles')      loadTitlesTab();
  if (tab === 'collections') loadCollectionsTab();
}

enrichTab.addEventListener('click',      () => switchJdTab('enrich'));
titlesTab.addEventListener('click',      () => switchJdTab('titles'));
collectionsTab.addEventListener('click', () => switchJdTab('collections'));
queueTab.addEventListener('click',       () => switchJdTab('queue'));

// ── Queue items loader ─────────────────────────────────────────────────────

async function loadQueueItems() {
  try {
    const res = await fetch('/api/javdb/discovery/queue/items');
    const items = await res.json();
    renderQueueItems(items);
  } catch { /* ignore */ }
}

// Average time between job completions (sleep + network). Conservative estimate used for ETAs.
const AVG_JOB_MS = 3_500;
// Show ETA only for the first N pending items — beyond that it's too imprecise.
const ETA_WINDOW = 8;

function computeEta(queuePosition) {
  if (!queuePosition) return null;
  const pauseRemainingMs = state.rateLimitPausedUntil
    ? Math.max(0, new Date(state.rateLimitPausedUntil).getTime() - Date.now())
    : 0;
  return new Date(Date.now() + pauseRemainingMs + (queuePosition - 1) * AVG_JOB_MS);
}

function formatEta(queuePosition) {
  if (!queuePosition || queuePosition > ETA_WINDOW) return '—';
  const eta = computeEta(queuePosition);
  const diffMs = eta.getTime() - Date.now();
  if (diffMs < 15_000) return '<span class="jd-qi-eta-soon">soon</span>';
  if (diffMs < 90_000) return `<span class="jd-qi-eta">~${Math.round(diffMs / 1000)}s</span>`;
  return `<span class="jd-qi-eta">${eta.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>`;
}

function renderQueueItems(items) {
  if (items.length === 0) {
    queueEmpty.style.display = '';
    queueTableWrap.style.display = 'none';
    return;
  }
  queueEmpty.style.display = 'none';
  queueTableWrap.style.display = '';

  queueTableBody.innerHTML = items.map(item => {
    const isProfile = item.jobType === 'fetch_actress_profile';
    const typeLabel = isProfile ? 'profile' : 'title';
    const titleCell = item.titleCode || '—';
    const age = formatQueueAge(item.updatedAt);
    const statusClass = item.status === 'in_flight' ? 'jd-qi-inflight'
                      : item.status === 'failed'    ? queueFailClass(item.lastError)
                      : item.status === 'paused'    ? 'jd-qi-paused' : 'jd-qi-pending';
    const statusLabel = item.status === 'in_flight' ? 'in flight'
                      : item.status === 'failed'    ? queueFailLabel(item.lastError)
                      : item.status === 'paused'    ? '⏸ paused' : item.status;
    const etaCell = item.status === 'pending' ? formatEta(item.queuePosition) : '—';
    const actions = renderQueueItemActions(item);
    const codeCell = (!isProfile && item.coverUrl)
      ? `<button class="jd-qi-cover-link" data-cover-url="${esc(item.coverUrl)}" data-code="${esc(titleCell)}">${esc(titleCell)}</button>`
      : esc(titleCell);
    const canReview = item.status === 'failed' && item.reviewQueueId != null;
    const statusCell = canReview
      ? `<button class="jd-qi-status ${statusClass} jd-qi-review-link" data-review-id="${item.reviewQueueId}" title="Click to review in Review Queue">${statusLabel}</button>`
      : `<span class="jd-qi-status ${statusClass}" title="${esc(item.lastError || '')}">${statusLabel}</span>`;
    return `<tr>
      <td><button class="jd-qi-actress-link" data-actress-id="${item.actressId}">${esc(item.actressName)}</button></td>
      <td class="jd-qi-code">${codeCell}</td>
      <td>${typeLabel}</td>
      <td>${statusCell}</td>
      <td>${item.attempts}</td>
      <td class="jd-qi-eta-cell">${etaCell}</td>
      <td class="jd-qi-age">${age}</td>
      <td class="jd-qi-actions-cell">${actions}</td>
    </tr>`;
  }).join('');
}

// Failure reason metadata — label, icon prefix, and CSS class for the queue status cell.
// Buckets: resolvable (amber ⚠), dead-end (slate ⊘), transient/fixable (red ↻).
const QUEUE_FAIL_META = {
  ambiguous:               { label: 'ambiguous',           icon: '⚠', cls: 'jd-qi-failed-resolvable' },
  cast_anomaly:            { label: 'cast anomaly',        icon: '⚠', cls: 'jd-qi-failed-resolvable' },
  sentinel_actress:        { label: 'needs actress',       icon: '⚠', cls: 'jd-qi-failed-resolvable' },
  not_found:               { label: 'not on javdb',        icon: '⊘', cls: 'jd-qi-failed-deadend'    },
  no_match_in_filmography: { label: 'not in filmography',  icon: '⊘', cls: 'jd-qi-failed-deadend'    },
  title_not_in_db:         { label: 'orphaned job',        icon: '⊘', cls: 'jd-qi-failed-deadend'    },
  unknown_job_type:        { label: 'internal error',      icon: '⊘', cls: 'jd-qi-failed-deadend'    },
  fetch_failed:            { label: 'fetch failed',        icon: '↻', cls: 'jd-qi-failed'            },
  no_slug:                 { label: 'no slug',             icon: '↻', cls: 'jd-qi-failed'            },
};

function queueFailLabel(lastError) {
  const m = QUEUE_FAIL_META[lastError];
  if (m) return `${m.icon} ${m.label}`;
  return lastError ? lastError.replace(/_/g, ' ') : 'failed';
}

function queueFailClass(lastError) {
  return QUEUE_FAIL_META[lastError]?.cls ?? 'jd-qi-failed';
}

function renderQueueItemActions(item) {
  const id = item.id;
  if (item.status === 'failed') {
    return `<span class="jd-qi-actions">` +
      `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="requeue" title="Re-queue">↺</button>` +
      `</span>`;
  }
  if (item.status !== 'pending' && item.status !== 'paused') return '';
  const pauseBtn = item.status === 'paused'
    ? `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="resume" title="Resume">▶</button>`
    : `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="pause" title="Pause">⏸</button>`;
  return `<span class="jd-qi-actions">` +
    `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="top"     title="Move to top">⇑</button>` +
    `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="promote" title="Move up">↑</button>` +
    `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="demote"  title="Move down">↓</button>` +
    `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="bottom"  title="Move to bottom">⇓</button>` +
    pauseBtn +
    `</span>`;
}

async function navigateToActress(actressId) {
  switchJdTab('enrich');
  resetFiltersToAll();
  await selectActress(actressId);
  const li = actressList.querySelector(`.jd-actress-item[data-id="${actressId}"]`);
  if (li) li.scrollIntoView({ block: 'nearest' });
}

function resetFiltersToAll() {
  state.alphaFilter = 'All';
  state.tierFilter = new Set();
  state.favoritesOnly = false;
  state.bookmarkedOnly = false;
  renderAlphaBar();
  renderFilterBar();
  renderActressList();
}

function formatQueueAge(updatedAt) {
  if (!updatedAt) return '—';
  const ms = Date.now() - new Date(updatedAt).getTime();
  const sec = Math.floor(ms / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  return `${Math.floor(min / 60)}h ago`;
}

// ── Queue table event delegation ───────────────────────────────────────────

queueTableBody.addEventListener('click', async e => {
  const coverBtn = e.target.closest('.jd-qi-cover-link[data-cover-url]');
  if (coverBtn) {
    showJdCoverModal(coverBtn.dataset.coverUrl, coverBtn.dataset.code || '');
    return;
  }

  const reviewBtn = e.target.closest('.jd-qi-review-link[data-review-id]');
  if (reviewBtn) {
    document.dispatchEvent(new CustomEvent('navigate-to-review-item', {
      detail: { reviewQueueId: parseInt(reviewBtn.dataset.reviewId, 10) }
    }));
    return;
  }

  const actressBtn = e.target.closest('.jd-qi-actress-link');
  if (actressBtn) {
    const actressId = parseInt(actressBtn.dataset.actressId, 10);
    await navigateToActress(actressId);
    return;
  }

  const actionBtn = e.target.closest('.jd-qi-action-btn');
  if (actionBtn) {
    const itemId = actionBtn.dataset.itemId;
    const action = actionBtn.dataset.action;
    await handleQueueItemAction(itemId, action);
  }
});

async function handleQueueItemAction(itemId, action) {
  if (action === 'pause' || action === 'resume' || action === 'requeue') {
    await fetch(`/api/javdb/discovery/queue/items/${itemId}/${action}`, { method: 'POST' });
  } else {
    await fetch(`/api/javdb/discovery/queue/items/${itemId}/move`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action }),
    });
  }
  await loadQueueItems();
}

// ── Titles tab (M2) ────────────────────────────────────────────────────────

async function loadTitlesTab() {
  await loadTitlesPools();
  await loadTitlesPage();
}

async function loadTitlesPools() {
  try {
    const res = await fetch('/api/javdb/discovery/titles/pools');
    if (!res.ok) return;
    state.titles.pools = await res.json();
    renderTitlesChips();
  } catch (_) { /* ignore */ }
}

async function loadTitlesPage() {
  state.titles.loading = true;
  const t = state.titles;
  const params = new URLSearchParams({
    source: t.source,
    page:   String(t.page),
    pageSize: String(t.pageSize),
  });
  if (t.source === 'pool' && t.poolVolumeId) params.set('volumeId', t.poolVolumeId);
  if (t.filter && t.filter.trim()) params.set('filter', t.filter.trim());
  try {
    const res = await fetch(`/api/javdb/discovery/titles?${params}`);
    if (!res.ok) {
      t.rows = [];
      t.hasMore = false;
      t.totalPages = 0;
    } else {
      const page = await res.json();
      t.rows = Array.isArray(page.rows) ? page.rows : [];
      t.hasMore = !!page.hasMore;
      t.totalPages = page.totalPages || 0;
    }
  } catch (_) {
    t.rows = [];
    t.hasMore = false;
    t.totalPages = 0;
  }
  state.titles.loading = false;
  renderTitlesTable();
  renderTitlesPager();
  renderTitlesFooter();
}

function renderTitlesChips() {
  const t = state.titles;
  const recentSelected = t.source === 'recent';
  const chips = [
    `<button type="button" class="jd-titles-chip ${recentSelected ? 'selected' : ''}" data-titles-chip="recent">All recent</button>`,
  ];
  for (const p of t.pools) {
    const sel = t.source === 'pool' && t.poolVolumeId === p.volumeId;
    const empty = (p.unenrichedCount || 0) === 0;
    chips.push(
      `<button type="button" class="jd-titles-chip ${sel ? 'selected' : ''} ${empty ? 'empty' : ''}" ` +
      `data-titles-chip="pool" data-volume-id="${esc(p.volumeId)}" ${empty ? 'disabled' : ''}>` +
        `Pool: ${esc(p.volumeId)}` +
        `<span class="jd-titles-chip-count">${p.unenrichedCount}</span>` +
      `</button>`
    );
  }
  titlesChips.innerHTML = chips.join('');
}

function renderTitlesTable() {
  const t = state.titles;
  if (t.rows.length === 0) {
    titlesEmpty.style.display = '';
    titlesTableWrap.style.display = 'none';
    return;
  }
  titlesEmpty.style.display = 'none';
  titlesTableWrap.style.display = '';

  titlesTableBody.innerHTML = t.rows.map(r => {
    const blocked = r.queueStatus === 'pending' || r.queueStatus === 'in_flight';
    const checked = t.selected.has(r.titleId);
    const cb = blocked
      ? '<span class="jd-titles-cb-blocked" aria-hidden="true">·</span>'
      : `<input type="checkbox" class="jd-titles-cb" data-title-id="${r.titleId}" ${checked ? 'checked' : ''}>`;
    const codeCell = `<span class="jd-titles-code" data-title-id="${r.titleId}">${esc(r.code)}</span>`;
    const actressCell = r.actress
      ? `<span class="jd-titles-actress">` +
        `<span class="jd-titles-elig-dot ${r.actress.eligibility === 'eligible' ? 'jd-titles-elig-yes' : 'jd-titles-elig-no'}" ` +
              `title="${r.actress.eligibility === 'eligible' ? 'Will chain a profile fetch' : 'Title-only fetch (no profile chain)'}"></span>` +
        `<span>${esc(r.actress.name)}</span>` +
        `</span>`
      : '<span class="jd-titles-actress" style="color:#475569">—</span>';
    const statusCell = renderTitlesStatusBadge(r);
    return `<tr>
      <td class="jd-titles-cb-col">${cb}</td>
      <td>${codeCell}</td>
      <td>${esc(r.titleEnglish || '')}</td>
      <td>${actressCell}</td>
      <td class="jd-titles-volume">${esc(r.volumeId || '')}</td>
      <td class="jd-titles-date">${fmtDate(r.addedDate)}</td>
      <td>${statusCell}</td>
    </tr>`;
  }).join('');
}

function renderTitlesStatusBadge(r) {
  if (r.queueStatus === 'in_flight') return '<span class="jd-titles-status jd-titles-status-inflight">in flight</span>';
  if (r.queueStatus === 'pending')   return '<span class="jd-titles-status jd-titles-status-pending">pending</span>';
  if (r.queueStatus === 'failed')    return '<span class="jd-titles-status jd-titles-status-failed">failed</span>';
  if (r.stagingStatus === 'slug_only') return '<span class="jd-titles-status jd-titles-status-pending">slug only</span>';
  return '<span style="color:#475569">—</span>';
}

function renderTitlesPager() {
  renderPagerInto(titlesPager, state.titles, 'titles');
}

/**
 * Shared pager renderer used by both Titles and Collections tabs.
 * Layout: «  -10  ←  [page input] of N  →  +10  »
 * Disables individual controls when they would be no-ops at page boundaries.
 */
function renderPagerInto(el, st, kind) {
  const total = Math.max(st.totalPages || 0, 1);
  const cur = (st.page || 0) + 1;   // 1-indexed for display
  const atStart = cur <= 1;
  const atEnd   = cur >= total;
  const dis = (b) => b ? 'disabled' : '';
  el.innerHTML =
    `<button type="button" class="jd-titles-pager-btn" data-${kind}-pager="first" ${dis(atStart)} title="First page">«</button>` +
    `<button type="button" class="jd-titles-pager-btn" data-${kind}-pager="back10" ${dis(atStart)} title="Back 10 pages">−10</button>` +
    `<button type="button" class="jd-titles-pager-btn" data-${kind}-pager="prev" ${dis(atStart)}>←</button>` +
    `<span class="jd-pager-input-wrap">` +
      `<input type="text" class="jd-pager-input" data-${kind}-pager-input value="${cur}" ` +
        `inputmode="numeric" pattern="[0-9]*" maxlength="6" ` +
        `aria-label="Page number">` +
      `<span class="jd-pager-of">of ${total}</span>` +
    `</span>` +
    `<button type="button" class="jd-titles-pager-btn" data-${kind}-pager="next" ${dis(atEnd)}>→</button>` +
    `<button type="button" class="jd-titles-pager-btn" data-${kind}-pager="forward10" ${dis(atEnd)} title="Forward 10 pages">+10</button>` +
    `<button type="button" class="jd-titles-pager-btn" data-${kind}-pager="last" ${dis(atEnd)} title="Last page">»</button>`;
}

/**
 * Wires up event delegation on a pager container. {@code onJump(newPage)} runs after each
 * navigation; the caller is expected to update state and reload data.
 */
function attachPagerHandlers(container, kind, getState, onJump) {
  // Button clicks.
  container.addEventListener('click', e => {
    const btn = e.target.closest(`[data-${kind}-pager]`);
    if (!btn || btn.disabled) return;
    const action = btn.dataset[`${kind}Pager`];
    const st = getState();
    const total = Math.max(st.totalPages || 0, 1);
    let target = st.page;
    switch (action) {
      case 'first':     target = 0; break;
      case 'back10':    target = Math.max(0, st.page - 10); break;
      case 'prev':      target = Math.max(0, st.page - 1); break;
      case 'next':      target = Math.min(total - 1, st.page + 1); break;
      case 'forward10': target = Math.min(total - 1, st.page + 10); break;
      case 'last':      target = total - 1; break;
    }
    if (target !== st.page) onJump(target);
  });

  // Input handling: digits only (sanitize on input), debounce 500ms before jumping,
  // Enter triggers immediate jump, invalid values flag the input red and are no-op.
  let debounce = null;
  container.addEventListener('input', e => {
    const input = e.target.closest(`[data-${kind}-pager-input]`);
    if (!input) return;
    // Sanitize: keep digits only, no leading zeros allowed beyond the first character.
    const cleaned = input.value.replace(/[^0-9]/g, '');
    if (cleaned !== input.value) input.value = cleaned;
    if (debounce) clearTimeout(debounce);
    debounce = setTimeout(() => attemptInputJump(input, kind, getState, onJump), 500);
  });
  container.addEventListener('keydown', e => {
    const input = e.target.closest(`[data-${kind}-pager-input]`);
    if (!input) return;
    if (e.key === 'Enter') {
      if (debounce) { clearTimeout(debounce); debounce = null; }
      attemptInputJump(input, kind, getState, onJump);
      input.blur();
    }
  });
  // On blur, restore the actual page number if the input is empty/invalid (don't strand the user
  // on a broken visual state).
  container.addEventListener('focusout', e => {
    const input = e.target.closest(`[data-${kind}-pager-input]`);
    if (!input) return;
    const st = getState();
    const total = Math.max(st.totalPages || 0, 1);
    const v = parseInt(input.value, 10);
    if (!Number.isFinite(v) || v < 1 || v > total) {
      input.value = String(st.page + 1);
      input.classList.remove('jd-pager-input-invalid');
    }
  });
}

function attemptInputJump(input, kind, getState, onJump) {
  const st = getState();
  const total = Math.max(st.totalPages || 0, 1);
  const raw = input.value;
  // Strict validation: digits only, in range [1, total].
  const valid = /^[0-9]+$/.test(raw);
  const v = valid ? parseInt(raw, 10) : NaN;
  if (!valid || !Number.isFinite(v) || v < 1 || v > total) {
    input.classList.add('jd-pager-input-invalid');
    return;
  }
  input.classList.remove('jd-pager-input-invalid');
  const target = v - 1;
  if (target !== st.page) onJump(target);
}

function renderTitlesFooter() {
  const n = state.titles.selected.size;
  if (n === 0) {
    titlesFooter.style.display = 'none';
    return;
  }
  titlesFooter.style.display = '';
  titlesFooterCnt.textContent = `${n} selected`;
  titlesEnqueueBtn.disabled = false;
  titlesEnqueueBtn.textContent = `Enqueue ${n}`;
}

// Event delegation: chip strip
titlesChips.addEventListener('click', async e => {
  const btn = e.target.closest('[data-titles-chip]');
  if (!btn || btn.disabled) return;
  const t = state.titles;
  if (btn.dataset.titlesChip === 'recent') {
    t.source = 'recent';
    t.poolVolumeId = null;
  } else {
    t.source = 'pool';
    t.poolVolumeId = btn.dataset.volumeId;
  }
  t.page = 0;
  t.selected.clear();
  renderTitlesChips();
  await loadTitlesPage();
});

// Event delegation: row checkboxes
titlesTableBody.addEventListener('change', e => {
  const cb = e.target.closest('.jd-titles-cb');
  if (!cb) return;
  const id = parseInt(cb.dataset.titleId, 10);
  if (cb.checked) state.titles.selected.add(id);
  else            state.titles.selected.delete(id);
  renderTitlesFooter();
});

// Event delegation: click on code → open lightweight peek modal (no nav, no
// history push — keeps tab + selection state intact).
titlesTableBody.addEventListener('click', async e => {
  const codeEl = e.target.closest('.jd-titles-code');
  if (!codeEl) return;
  const titleId = parseInt(codeEl.dataset.titleId, 10);
  const row = state.titles.rows.find(r => r.titleId === titleId);
  if (!row) return;
  await openTitlePeekModal(row.code);
});

// Pager handlers: shared logic via attachPagerHandlers.
attachPagerHandlers(titlesPager, 'titles', () => state.titles, async (newPage) => {
  state.titles.page = newPage;
  await loadTitlesPage();
});

// Clear selection
titlesClearBtn.addEventListener('click', () => {
  state.titles.selected.clear();
  renderTitlesTable();
  renderTitlesFooter();
});

// Enqueue selected
titlesEnqueueBtn.addEventListener('click', async () => {
  const t = state.titles;
  const ids = Array.from(t.selected);
  if (ids.length === 0) return;
  titlesEnqueueBtn.disabled = true;
  try {
    const res = await fetch('/api/javdb/discovery/titles/enqueue', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ source: t.source, titleIds: ids }),
    });
    if (!res.ok) {
      titlesEnqueueBtn.disabled = false;
      return;
    }
    t.selected.clear();
    // Refresh chip counts (those titles drop out of unenriched once queued + done) and the
    // current page (rows now show queue badges instead of checkboxes).
    await loadTitlesPools();
    await loadTitlesPage();
  } catch (_) {
    titlesEnqueueBtn.disabled = false;
  }
});

// ── Collections tab (M3) ───────────────────────────────────────────────────

async function loadCollectionsTab() {
  const c = state.collections;
  c.loading = true;
  const params = new URLSearchParams({
    source: 'collection',
    page:   String(c.page),
    pageSize: String(c.pageSize),
  });
  if (c.filter && c.filter.trim()) params.set('filter', c.filter.trim());
  try {
    const res = await fetch(`/api/javdb/discovery/titles?${params}`);
    if (!res.ok) {
      c.rows = []; c.hasMore = false; c.totalPages = 0;
    } else {
      const page = await res.json();
      c.rows = Array.isArray(page.rows) ? page.rows : [];
      c.hasMore = !!page.hasMore;
      c.totalPages = page.totalPages || 0;
    }
  } catch (_) {
    c.rows = []; c.hasMore = false; c.totalPages = 0;
  }
  c.loading = false;
  renderCollectionsTable();
  renderCollectionsPager();
  renderCollectionsFooter();
}

function renderCollectionsTable() {
  const c = state.collections;
  if (c.rows.length === 0) {
    collectionsEmpty.style.display = '';
    collectionsTableWrap.style.display = 'none';
    return;
  }
  collectionsEmpty.style.display = 'none';
  collectionsTableWrap.style.display = '';

  collectionsTableBody.innerHTML = c.rows.map(r => {
    const blocked = r.queueStatus === 'pending' || r.queueStatus === 'in_flight';
    const checked = c.selected.has(r.titleId);
    const cb = blocked
      ? '<span class="jd-titles-cb-blocked" aria-hidden="true">·</span>'
      : `<input type="checkbox" class="jd-collections-cb" data-title-id="${r.titleId}" ${checked ? 'checked' : ''}>`;
    const codeCell = `<span class="jd-titles-code" data-title-id="${r.titleId}">${esc(r.code)}</span>`;
    const castCell = renderCastChips(r.cast || []);
    const statusCell = renderTitlesStatusBadge(r);
    return `<tr>
      <td class="jd-titles-cb-col">${cb}</td>
      <td>${codeCell}</td>
      <td>${esc(r.titleEnglish || '')}</td>
      <td>${castCell}</td>
      <td class="jd-titles-volume">${esc(r.volumeId || '')}</td>
      <td class="jd-titles-date">${fmtDate(r.addedDate)}</td>
      <td>${statusCell}</td>
    </tr>`;
  }).join('');
}

function renderCastChips(cast) {
  if (!cast.length) return '<span style="color:#475569">—</span>';
  const chips = cast.map(a => {
    const cls = a.eligibility === 'eligible'        ? 'jd-cast-chip-elig'
              : a.eligibility === 'sentinel'        ? 'jd-cast-chip-sentinel'
              :                                       'jd-cast-chip-below';
    const icon = a.eligibility === 'eligible'  ? '✓'
               : a.eligibility === 'sentinel'  ? '✗'
               :                                 '◌';
    const tip = a.eligibility === 'eligible'  ? 'Will chain a profile fetch'
              : a.eligibility === 'sentinel'  ? 'Sentinel actress (no chain)'
              :                                 'Below threshold (no chain)';
    return `<span class="jd-cast-chip ${cls}" title="${esc(tip)}">` +
           `<span class="jd-cast-chip-icon">${icon}</span>${esc(a.name)}</span>`;
  });
  return `<span class="jd-cast-strip">${chips.join('')}</span>`;
}

function renderCollectionsPager() {
  renderPagerInto(collectionsPager, state.collections, 'collections');
}

function renderCollectionsFooter() {
  const n = state.collections.selected.size;
  if (n === 0) {
    collectionsFooter.style.display = 'none';
    return;
  }
  collectionsFooter.style.display = '';
  collectionsFooterCnt.textContent = `${n} selected`;
  collectionsEnqueueBtn.disabled = false;
  collectionsEnqueueBtn.textContent = `Enqueue ${n}`;
}

// Event delegation: row checkboxes
collectionsTableBody.addEventListener('change', e => {
  const cb = e.target.closest('.jd-collections-cb');
  if (!cb) return;
  const id = parseInt(cb.dataset.titleId, 10);
  if (cb.checked) state.collections.selected.add(id);
  else            state.collections.selected.delete(id);
  renderCollectionsFooter();
});

// Event delegation: code click → open title detail (mirrors Titles tab pattern).
collectionsTableBody.addEventListener('click', async e => {
  const codeEl = e.target.closest('.jd-titles-code');
  if (!codeEl) return;
  const titleId = parseInt(codeEl.dataset.titleId, 10);
  const row = state.collections.rows.find(r => r.titleId === titleId);
  if (!row) return;
  await openTitlePeekModal(row.code);
});

// Pager: shared handler, navigates via state.collections.
attachPagerHandlers(collectionsPager, 'collections', () => state.collections, async (newPage) => {
  state.collections.page = newPage;
  await loadCollectionsTab();
});

// Clear
collectionsClearBtn.addEventListener('click', () => {
  state.collections.selected.clear();
  renderCollectionsTable();
  renderCollectionsFooter();
});

// Enqueue
collectionsEnqueueBtn.addEventListener('click', async () => {
  const c = state.collections;
  const ids = Array.from(c.selected);
  if (ids.length === 0) return;
  collectionsEnqueueBtn.disabled = true;
  try {
    const res = await fetch('/api/javdb/discovery/titles/enqueue', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ source: 'collection', titleIds: ids }),
    });
    if (!res.ok) {
      collectionsEnqueueBtn.disabled = false;
      return;
    }
    c.selected.clear();
    await loadCollectionsTab();
  } catch (_) {
    collectionsEnqueueBtn.disabled = false;
  }
});

// ── Title peek modal (M3) ──────────────────────────────────────────────────

/**
 * Lightweight read-only modal showing cover + key info for one title. Used by the
 * Titles and Collections tabs so the user can peek without losing tab state or
 * pushing history. No video, no edit affordances, no visit tracking.
 */
async function openTitlePeekModal(code) {
  // Avoid stacking duplicate modals.
  closeTitlePeekModal();
  let t = null;
  try {
    const res = await fetch(`/api/titles/by-code/${encodeURIComponent(code)}`);
    if (res.ok) t = await res.json();
  } catch (_) { /* render with what we have */ }
  if (!t) t = { code };

  const backdrop = document.createElement('div');
  backdrop.className = 'jd-peek-backdrop';
  backdrop.id = 'jd-peek-backdrop';

  const cast = (t.actresses && t.actresses.length > 0)
    ? t.actresses
    : (t.actressName ? [{ id: t.actressId, name: t.actressName, tier: t.actressTier }] : []);
  const castHtml = cast.length === 0
    ? '<span style="color:#475569">—</span>'
    : cast.map(a => `<span class="jd-peek-cast-chip">${esc(a.name || '')}</span>`).join('');

  const labelText = [t.companyName, t.labelName].filter(Boolean).join(' · ');
  const dateLabel = t.releaseDate ? 'Released' : (t.addedDate ? 'Added' : null);
  const dateValue = t.releaseDate || t.addedDate;

  let gradeHtml = '';
  if (t.grade) {
    gradeHtml = `<span class="jd-peek-grade tier-${esc(String(t.grade))}">${esc(String(t.grade))}</span>`;
    if (t.ratingAvg != null && t.ratingCount != null) {
      gradeHtml += `<span class="jd-peek-rating">${t.ratingAvg.toFixed(2)} · ${t.ratingCount.toLocaleString()} votes</span>`;
    }
  }

  const tags = (t.tags || []).filter(Boolean);
  const tagsHtml = tags.length === 0
    ? '<span style="color:#475569">—</span>'
    : tags.map(tag => `<span class="jd-peek-tag">${esc(tag)}</span>`).join('');

  const nas = t.nasPaths || [];
  const nasHtml = nas.length === 0
    ? '<span style="color:#475569">—</span>'
    : nas.map(p => `<span class="jd-peek-nas">${esc(p)}</span>`).join('');

  const coverHtml = t.coverUrl
    ? `<div class="jd-peek-cover-wrap"><img class="jd-peek-cover" src="${esc(t.coverUrl)}" alt="${esc(t.code)}"></div>`
    : '';

  const titleJaHtml = t.titleOriginal ? `<div class="jd-peek-title-ja">${esc(t.titleOriginal)}</div>` : '';
  const titleEnHtml = t.titleEnglish  ? `<div class="jd-peek-title-en">${esc(t.titleEnglish)}</div>`  : '';

  backdrop.innerHTML = `
    <div class="jd-peek-modal" role="dialog" aria-label="Title preview" tabindex="-1">
      <button type="button" class="jd-peek-close" id="jd-peek-close" title="Close (Esc)">×</button>
      ${coverHtml}
      <div class="jd-peek-code">${esc(t.code)}</div>
      ${titleJaHtml}
      ${titleEnHtml}
      <div class="jd-peek-rows">
        <div class="jd-peek-row"><span class="jd-peek-row-label">Cast</span><span class="jd-peek-row-value">${castHtml}</span></div>
        ${labelText ? `<div class="jd-peek-row"><span class="jd-peek-row-label">Label</span><span class="jd-peek-row-value">${esc(labelText)}</span></div>` : ''}
        ${dateLabel ? `<div class="jd-peek-row"><span class="jd-peek-row-label">${dateLabel}</span><span class="jd-peek-row-value">${esc(fmtDate(dateValue))}</span></div>` : ''}
        ${gradeHtml ? `<div class="jd-peek-row"><span class="jd-peek-row-label">Grade</span><span class="jd-peek-row-value">${gradeHtml}</span></div>` : ''}
        <div class="jd-peek-row"><span class="jd-peek-row-label">Tags</span><span class="jd-peek-row-value">${tagsHtml}</span></div>
        <div class="jd-peek-row"><span class="jd-peek-row-label">Location</span><span class="jd-peek-row-value">${nasHtml}</span></div>
      </div>
    </div>
  `;
  document.body.appendChild(backdrop);

  // Dismiss handlers.
  backdrop.addEventListener('click', e => { if (e.target === backdrop) closeTitlePeekModal(); });
  document.getElementById('jd-peek-close').addEventListener('click', closeTitlePeekModal);
  document.addEventListener('keydown', titlePeekKeydownHandler);
}

function closeTitlePeekModal() {
  const el = document.getElementById('jd-peek-backdrop');
  if (el) el.remove();
  document.removeEventListener('keydown', titlePeekKeydownHandler);
}

function titlePeekKeydownHandler(e) {
  if (e.key === 'Escape') closeTitlePeekModal();
}

// ── Filter inputs (Titles + Collections) ───────────────────────────────────

const FILTER_DEBOUNCE_MS = 300;
const FILTER_AUTOCOMPLETE_DEBOUNCE_MS = 200;

/**
 * Wires a search input + clear button + autocomplete dropdown to a tab's state.
 * Mimics the library code-input on the Titles browse screen: prefix-only matches
 * (e.g. 'AB', 'ABP', 'ABP-') trigger an /api/labels/autocomplete fetch and open
 * a dropdown of suggested label codes. Once digits start to appear (e.g. 'ABP-001')
 * the dropdown closes — the user is past the label-prefix phase.
 */
function attachFilterHandlers(input, clearBtn, dropEl, getState, onChange) {
  // Internal state for autocomplete (closure-scoped per attachment).
  let autoTimer = null;
  let autoVisible = false;

  function closeAutocomplete() {
    autoVisible = false;
    if (autoTimer) { clearTimeout(autoTimer); autoTimer = null; }
    if (dropEl) { dropEl.innerHTML = ''; dropEl.classList.remove('open'); }
  }
  function openAutocomplete(items) {
    if (!dropEl || items.length === 0) { closeAutocomplete(); return; }
    autoVisible = true;
    dropEl.innerHTML = '';
    items.forEach((code, i) => {
      const el = document.createElement('div');
      el.className = 'jd-filter-autocomplete-item';
      el.textContent = code;
      el.dataset.idx = String(i);
      el.addEventListener('mousedown', e => {
        e.preventDefault();           // don't blur the input first
        selectAutocompleteItem(code);
      });
      dropEl.appendChild(el);
    });
    dropEl.classList.add('open');
  }
  function moveAutocompleteSelection(dir) {
    if (!dropEl || !autoVisible) return;
    const items = dropEl.querySelectorAll('.jd-filter-autocomplete-item');
    if (items.length === 0) return;
    const cur = dropEl.querySelector('.jd-filter-autocomplete-item.focused');
    let idx = cur ? parseInt(cur.dataset.idx, 10) + dir : (dir > 0 ? 0 : items.length - 1);
    idx = Math.max(0, Math.min(items.length - 1, idx));
    items.forEach(el => el.classList.remove('focused'));
    items[idx]?.classList.add('focused');
  }
  function selectAutocompleteItem(code) {
    input.value = code;
    clearBtn.style.display = code.length > 0 ? '' : 'none';
    closeAutocomplete();
    // Apply immediately — mirrors the library code-input behavior on item-select.
    const st = getState();
    if (st.filterDebounce) { clearTimeout(st.filterDebounce); st.filterDebounce = null; }
    st.filter = code;
    st.page = 0;
    st.selected.clear();
    onChange();
  }
  async function fetchAutocomplete(prefix) {
    if (!prefix || prefix.length < 1) { closeAutocomplete(); return; }
    try {
      const res = await fetch(`/api/labels/autocomplete?prefix=${encodeURIComponent(prefix)}`);
      if (!res.ok) return;
      const items = await res.json();
      // Only open if the input is still in the label-prefix phase (user hasn't moved on).
      if (document.activeElement !== input) return;
      openAutocomplete(items);
    } catch { /* ignore */ }
  }

  input.addEventListener('input', () => {
    const st = getState();
    const v = input.value;
    clearBtn.style.display = v.length > 0 ? '' : 'none';

    // Autocomplete: trigger only when the user is still typing a label prefix
    // (e.g. 'AB', 'ABP', 'ABP-'). Once digits follow ('ABP-001'), close.
    const upper = v.trim().toUpperCase().replace(/\s+/g, '');
    const isLabelPrefixOnly = upper.length > 0 && /^[A-Z][A-Z0-9]*-?$/.test(upper);
    if (isLabelPrefixOnly) {
      if (autoTimer) clearTimeout(autoTimer);
      autoTimer = setTimeout(() => {
        autoTimer = null;
        fetchAutocomplete(upper.replace(/-+$/, ''));
      }, FILTER_AUTOCOMPLETE_DEBOUNCE_MS);
    } else {
      closeAutocomplete();
    }

    // Filter debounce (independent of autocomplete) — re-query after user stops typing.
    if (st.filterDebounce) clearTimeout(st.filterDebounce);
    st.filterDebounce = setTimeout(() => {
      st.filterDebounce = null;
      st.filter = v;
      st.page = 0;
      st.selected.clear();
      onChange();
    }, FILTER_DEBOUNCE_MS);
  });

  input.addEventListener('keydown', e => {
    if (e.key === 'ArrowDown') { e.preventDefault(); moveAutocompleteSelection(1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); moveAutocompleteSelection(-1); }
    else if (e.key === 'Enter') {
      if (autoVisible) {
        const focused = dropEl.querySelector('.jd-filter-autocomplete-item.focused');
        if (focused) { e.preventDefault(); selectAutocompleteItem(focused.textContent); return; }
      }
      closeAutocomplete();
    }
    else if (e.key === 'Escape') {
      // ESC behavior: if dropdown open, just close it; otherwise clear the filter.
      if (autoVisible) { closeAutocomplete(); return; }
      input.value = '';
      clearBtn.style.display = 'none';
      const st = getState();
      if (st.filterDebounce) { clearTimeout(st.filterDebounce); st.filterDebounce = null; }
      if (st.filter) {
        st.filter = '';
        st.page = 0;
        st.selected.clear();
        onChange();
      }
    }
  });

  // Small delay so mousedown on dropdown item fires before the close.
  input.addEventListener('blur', () => { setTimeout(closeAutocomplete, 150); });

  clearBtn.addEventListener('click', () => {
    input.value = '';
    clearBtn.style.display = 'none';
    closeAutocomplete();
    const st = getState();
    if (st.filterDebounce) { clearTimeout(st.filterDebounce); st.filterDebounce = null; }
    if (st.filter) {
      st.filter = '';
      st.page = 0;
      st.selected.clear();
      onChange();
    }
    input.focus();
  });
}

attachFilterHandlers(titlesFilterInput, titlesFilterClearBtn, titlesFilterAutocomplete,
    () => state.titles, async () => {
      await loadTitlesPage();
      // Pool counts may shift if user is filtering across pools — re-fetch chips for accuracy.
      // (No-op when source !== 'pool', but cheap.)
      renderTitlesChips();
    });

attachFilterHandlers(collectionsFilterInput, collectionsFilterClearBtn, collectionsFilterAutocomplete,
    () => state.collections, async () => {
      await loadCollectionsTab();
    });

// ── Button wiring ──────────────────────────────────────────────────────────

controlsToggle.addEventListener('click', () => {
  const collapsed = controlsPanel.classList.toggle('collapsed');
  controlsToggle.classList.toggle('collapsed', collapsed);
});

pauseBtn.addEventListener('click', togglePause);
cancelAllBtn.addEventListener('click', cancelAll);
enrichBtn.addEventListener('click', enrichActress);
cancelActressBtn.addEventListener('click', cancelActress);

subtabBtns.forEach(btn => {
  btn.addEventListener('click', async () => {
    subtabBtns.forEach(b => b.classList.remove('selected'));
    btn.classList.add('selected');
    state.activeTab = btn.dataset.tab;
    if (state.selectedId !== null) await renderActiveTab();
  });
});
