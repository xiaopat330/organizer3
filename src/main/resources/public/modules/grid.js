import { setStatus } from './utils.js';

// ── View management ───────────────────────────────────────────────────────
export const VIEWS = {
  titles:           ['home-tabs'],
  actresses:        ['actress-landing', 'actress-grid'],
  'actress-detail': ['actress-detail'],
  'title-detail':   ['title-detail'],
  collections:      ['collections-grid'],
  'titles-browse':  ['title-landing', 'titles-browse-grid'],
};
export const HOME_GRID_IDS   = ['grid', 'random-titles-grid', 'random-actress-home-grid'];
export const EXTRA_PANEL_IDS = ['title-studio-labels', 'title-tags-panel', 'title-browse-filter-bar', 'title-browse-tags-panel', 'actress-studio-labels', 'actress-dashboard', 'title-dashboard'];
export const ALL_PANEL_IDS   = [...Object.values(VIEWS).flat(), ...HOME_GRID_IDS, ...EXTRA_PANEL_IDS];

// Views where the body must not scroll (they fill the viewport themselves)
const FIXED_VIEWPORT_VIEWS = new Set(['title-detail', 'actress-detail']);

export let mode = 'titles';

export function showView(name) {
  mode = name;
  clearCardIntervals();
  for (const id of ALL_PANEL_IDS)
    document.getElementById(id).style.display = 'none';
  for (const id of (VIEWS[name] || [])) {
    const el = document.getElementById(id);
    if (el.classList.contains('grid')) el.style.display = 'grid';
    else if (el.classList.contains('actress-sub-nav')) el.style.display = 'flex';
    else if (el.classList.contains('actress-landing')) el.style.display = 'flex';
    else if (el.id === 'title-detail') el.style.display = 'flex';
    else if (el.id === 'actress-detail') el.style.display = 'flex';
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
}

// ── Breadcrumb ────────────────────────────────────────────────────────────
// Registered by app.js after all modules are loaded.
let _homeClickHandler = () => {};
export function setHomeClickHandler(fn) { _homeClickHandler = fn; }

export function updateBreadcrumb(segments) {
  const el = document.getElementById('breadcrumb');
  el.innerHTML = '';
  if (segments.length === 0) {
    el.classList.remove('visible');
    return;
  }
  el.classList.add('visible');

  const home = document.createElement('span');
  home.className = 'crumb crumb-home';
  home.innerHTML = '&#x1F3E0; HOME';
  home.addEventListener('click', _homeClickHandler);
  el.appendChild(home);
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
