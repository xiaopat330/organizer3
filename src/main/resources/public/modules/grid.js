import { setStatus } from './utils.js';

// ── View management ───────────────────────────────────────────────────────
export const VIEWS = {
  titles:           ['home-portal'],
  actresses:        ['actress-landing', 'actress-grid'],
  'actress-detail': ['actress-landing', 'actress-detail'],
  'title-detail':   ['title-landing', 'title-detail'],
  collections:      ['collections-grid'],
  'titles-browse':  ['title-landing', 'titles-browse-grid'],
  'av':             ['av-landing'],
  'av-index':       ['av-landing', 'av-grid'],
  'av-actress-detail': ['av-landing', 'av-actress-detail'],
  'action':         ['action-landing'],
};
export const HOME_GRID_IDS   = [];
export const EXTRA_PANEL_IDS = ['title-studio-labels', 'title-tags-panel', 'title-browse-filter-bar', 'title-browse-tags-panel', 'actress-studio-labels', 'actress-dashboard', 'title-dashboard', 'actress-browse-filter-bar', 'av-dashboard', 'av-index-filter-bar', 'tools-duplicates-view', 'tools-duplicates-filters', 'tools-aliases-view', 'tools-queue-view', 'tools-logs-view', 'tools-volumes-view', 'tools-actress-data-view', 'tools-backup-view', 'tools-library-health-view', 'tools-av-stars-view', 'tools-dup-triage-view', 'tools-dup-subnav', 'tools-merge-candidates-view', 'tools-javdb-discovery-view', 'tools-tag-health-view', 'tools-health-subnav', 'tools-utilities-subnav'];
export const ALL_PANEL_IDS   = [...Object.values(VIEWS).flat(), ...HOME_GRID_IDS, ...EXTRA_PANEL_IDS];

// Views where the body must not scroll (they fill the viewport themselves).
// 'action' covers the Tools landing and every tool sub-view (Review Queue, Logs,
// JD Discovery, etc.) — none of them use the grid pagination's #status/#sentinel.
const FIXED_VIEWPORT_VIEWS = new Set(['title-detail', 'actress-detail', 'av-actress-detail', 'action']);

export let mode = 'titles';

export function showView(name) {
  mode = name;
  clearCardIntervals();
  for (const id of ALL_PANEL_IDS) {
    const el = document.getElementById(id);
    if (el) el.style.display = 'none';
  }
  for (const id of (VIEWS[name] || [])) {
    const el = document.getElementById(id);
    if (el.classList.contains('grid')) el.style.display = 'grid';
    else if (el.classList.contains('actress-sub-nav')) el.style.display = 'flex';
    else if (el.classList.contains('actress-landing')) el.style.display = 'flex';
    else if (el.classList.contains('action-landing'))  el.style.display = 'flex';
    else if (el.id === 'title-detail') el.style.display = 'flex';
    else if (el.id === 'actress-detail') el.style.display = 'flex';
    else if (el.id === 'av-actress-detail') el.style.display = 'flex';
    else el.style.display = 'block';
  }
  const fixedViewport = FIXED_VIEWPORT_VIEWS.has(name);
  const statusEl  = document.getElementById('status');
  const sentinelEl = document.getElementById('sentinel');
  if (statusEl)   statusEl.style.display   = fixedViewport ? 'none' : '';
  if (sentinelEl) sentinelEl.style.display = fixedViewport ? 'none' : '';
  if (name !== 'titles-browse') {
    document.getElementById('titles-browse-btn')?.classList.remove('active');
  }

  // Update sticky positions for sub-nav bar and landing panels
  requestAnimationFrame(() => {
    const header    = document.querySelector('header');
    const subNavBar = document.getElementById('sub-nav-search-bar');
    const headerH   = header ? header.offsetHeight : 0;

    if (subNavBar) {
      const isHome = name === 'titles' || name === 'action';
      subNavBar.style.display = isHome ? 'none' : '';
      subNavBar.style.top = headerH + 'px';
    }

    const subNavH = (subNavBar && subNavBar.style.display !== 'none') ? subNavBar.offsetHeight : 0;
    const landingTop = (headerH + subNavH) + 'px';

    const actressLanding = document.getElementById('actress-landing');
    const titleLanding   = document.getElementById('title-landing');
    const avLanding      = document.getElementById('av-landing');
    const actionLanding  = document.getElementById('action-landing');
    if (actressLanding && actressLanding.style.display !== 'none') actressLanding.style.top = landingTop;
    if (titleLanding   && titleLanding.style.display   !== 'none') titleLanding.style.top   = landingTop;
    if (avLanding      && avLanding.style.display      !== 'none') avLanding.style.top      = landingTop;
    if (actionLanding  && actionLanding.style.display  !== 'none') actionLanding.style.top  = headerH + 'px';
  });
}

// ── Breadcrumb ────────────────────────────────────────────────────────────
// Registered by app.js after all modules are loaded.
let _homeClickHandler = () => {};
export function setHomeClickHandler(fn) { _homeClickHandler = fn; }

export function updateBreadcrumb(segments) {
  const el = document.getElementById('breadcrumb');
  el.innerHTML = '';

  const home = document.createElement('span');
  home.className = 'crumb crumb-home';
  home.innerHTML = '&#x1F3E0; HOME';
  if (segments.length > 0) home.addEventListener('click', _homeClickHandler);
  else home.style.cursor = 'default';
  el.appendChild(home);

  if (segments.length === 0) return;

  const homeSep = document.createElement('span');
  homeSep.className = 'crumb-sep';
  homeSep.textContent = '›';
  el.appendChild(homeSep);

  segments.forEach((seg, i) => {
    const isLast = i === segments.length - 1;
    const span = document.createElement('span');
    if (isLast) {
      span.className = 'crumb-current';
      span.textContent = seg.label;
    } else {
      span.className = 'crumb';
      span.textContent = seg.label;
      if (seg.action) span.addEventListener('click', seg.action);
    }
    el.appendChild(span);
    if (!isLast) {
      const sep = document.createElement('span');
      sep.className = 'crumb-sep';
      sep.textContent = '›';
      el.appendChild(sep);
    }
  });
}

// ── ScrollingGrid ─────────────────────────────────────────────────────────
export const PAGE_SIZE = 24;

export class ScrollingGrid {
  constructor(gridEl, urlFn, makeCard, emptyMsg, { getMax = () => Infinity, pageSize = PAGE_SIZE } = {}) {
    this.gridEl    = gridEl;
    this.urlFn     = urlFn;
    this.makeCard  = makeCard;
    this.emptyMsg  = emptyMsg;
    this.getMax    = getMax;
    this.pageSize  = pageSize;
    this.offset    = 0;
    this.loading   = false;
    this.exhausted = false;
  }

  reset() {
    this.offset    = 0;
    this.loading   = false;
    this.exhausted = false;
    this.gridEl.innerHTML = '';
  }

  async loadMore() {
    if (this.loading || this.exhausted) return;
    this.loading = true;
    setStatus('loading');

    const limit = Math.min(this.pageSize, this.getMax() - this.offset);
    if (limit <= 0) {
      this.exhausted = true;
      setStatus('');
      this.loading = false;
      return;
    }

    const url = this.urlFn(this.offset, limit);
    if (url == null) {
      this.exhausted = true;
      setStatus('');
      this.loading = false;
      return;
    }

    try {
      const res = await fetch(url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const items = await res.json();
      for (const item of items) this.gridEl.appendChild(this.makeCard(item));
      this.offset += items.length;
      if (items.length < limit || this.offset >= this.getMax()) {
        this.exhausted = true;
        document.getElementById('sentinel')?.remove();
        setStatus(this.offset === 0 ? this.emptyMsg : '');
      } else {
        setStatus('');
      }
    } catch (err) {
      setStatus('error loading');
      console.error(err);
    }
    this.loading = false;
  }
}

// ── Intersection observer ─────────────────────────────────────────────────
export let activeGrid = null;
export function setActiveGrid(g) { activeGrid = g; }

const sentinel = document.getElementById('sentinel');
export const observer = new IntersectionObserver(entries => {
  if (entries[0].isIntersecting && activeGrid) activeGrid.loadMore();
}, { rootMargin: '300px' });
observer.observe(sentinel);

export function ensureSentinel() {
  let s = document.getElementById('sentinel');
  if (s && s.parentElement !== document.body) {
    s.remove();
    s = null;
  }
  if (!s) {
    s = document.createElement('div');
    s.id = 'sentinel';
    s.style.height = '1px';
    document.body.appendChild(s);
    observer.observe(s);
  }
  return s;
}

export function ensureActressDetailSentinel() {
  let s = document.getElementById('sentinel');
  if (!s) {
    s = document.createElement('div');
    s.id = 'sentinel';
    s.style.height = '1px';
    document.getElementById('actress-detail-right').appendChild(s);
    observer.observe(s);
  }
  return s;
}

// ── Card animation cleanup ────────────────────────────────────────────────
export const ROTATE_INTERVALS = [7000, 11000, 17000];
export const activeIntervals  = new Set();
export const activeObservers  = new Set();

export function clearCardIntervals() {
  for (const id of activeIntervals) clearInterval(id);
  activeIntervals.clear();
  for (const obs of activeObservers) obs.disconnect();
  activeObservers.clear();
}
