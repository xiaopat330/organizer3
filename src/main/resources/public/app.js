// ── App name + config ─────────────────────────────────────────────────────
let MAX_TOTAL           = 500;
let MAX_RANDOM_TITLES   = 500;
let MAX_RANDOM_ACTRESSES = 500;
let EXHIBITION_VOLUMES = '';
let ARCHIVE_VOLUMES    = '';
let THUMBNAIL_COLUMNS  = 5;

fetch('/api/config')
  .then(r => r.json())
  .then(cfg => {
    const name = cfg.appName || 'organizer3';
    document.getElementById('app-name').textContent = name.toLowerCase();
    document.title = name;
    if (cfg.maxBrowseTitles)    MAX_TOTAL            = cfg.maxBrowseTitles;
    if (cfg.maxRandomTitles)    MAX_RANDOM_TITLES    = cfg.maxRandomTitles;
    if (cfg.maxRandomActresses) MAX_RANDOM_ACTRESSES = cfg.maxRandomActresses;
    if (cfg.exhibitionVolumes)  EXHIBITION_VOLUMES   = cfg.exhibitionVolumes.join(',');
    if (cfg.archiveVolumes)     ARCHIVE_VOLUMES      = cfg.archiveVolumes.join(',');
    if (cfg.thumbnailColumns)   THUMBNAIL_COLUMNS    = cfg.thumbnailColumns;
  })
  .catch(() => {});

document.getElementById('app-name').addEventListener('click', showTitlesView);

// ── Utilities ─────────────────────────────────────────────────────────────
function esc(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function isStale(dateStr) {
  if (!dateStr) return false;
  const oneYearAgo = new Date();
  oneYearAgo.setFullYear(oneYearAgo.getFullYear() - 1);
  return new Date(dateStr) < oneYearAgo;
}

function splitName(name) {
  const i = name.indexOf(' ');
  return i >= 0 ? { first: name.slice(0, i), last: name.slice(i + 1) } : { first: name, last: '' };
}

// Renders a "first → last" active date range. Returns '' if both are absent.
function renderDateRange(first, last, cls = 'actress-active-dates') {
  if (!first && !last) return '';
  const firstHtml = first ? `<span class="date-first">${esc(fmtDate(first))}</span>` : '';
  const lastHtml  = last
    ? `<span class="${isStale(last) ? 'date-last-stale' : 'date-last'}">${esc(fmtDate(last))}</span>` : '';
  const sep = firstHtml && lastHtml ? ' → ' : '';
  return `<div class="${cls}">${firstHtml}${sep}${lastHtml}</div>`;
}

function fmtDate(dateStr) {
  if (!dateStr) return '';
  const d = new Date(dateStr + 'T00:00:00');
  if (isNaN(d)) return dateStr;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

function setStatus(msg) {
  const el = document.getElementById('status');
  if (el) el.textContent = msg;
}

function ensureSentinel() {
  let s = document.getElementById('sentinel');
  // If the sentinel is inside a non-body container (e.g., actress-detail-right), relocate it
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

function ensureActressDetailSentinel() {
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

function makeLabel(text) {
  return Object.assign(document.createElement('div'), {
    className: 'dropdown-section-label',
    textContent: text,
  });
}

// ── Breadcrumb ───────────────────────────────────────────────────────────
// segments: [{ label, action? }] — last segment has no action (current page)
function updateBreadcrumb(segments) {
  const el = document.getElementById('breadcrumb');
  el.innerHTML = '';
  if (segments.length === 0) {
    el.classList.remove('visible');
    updateDetailPanelTop();
    return;
  }
  el.classList.add('visible');

  // Prepend Home crumb
  const home = document.createElement('span');
  home.className = 'crumb crumb-home';
  home.innerHTML = '&#x1F3E0; HOME';
  home.addEventListener('click', showTitlesView);
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
  updateDetailPanelTop();
}

function updateDetailPanelTop() {
  // no-op: actress-detail is now a fixed-viewport layout, not sticky
}

// ── View management ───────────────────────────────────────────────────────
// Each key maps to the IDs that should be visible in that view.
// Home content grids are managed separately via activateHomeTab().
const VIEWS = {
  titles:           ['home-tabs'],
  actresses:        ['actress-landing', 'actress-grid'],
  'actress-detail': ['actress-detail'],
  'title-detail':   ['title-detail'],
  queue:            ['queue-header', 'queue-grid'],
  pool:             ['pool-header', 'pool-grid'],
  collections:      ['collections-grid'],
  'titles-browse':  ['title-landing', 'titles-browse-grid'],
};
const HOME_GRID_IDS = ['grid', 'random-titles-grid', 'random-actress-home-grid'];
const ALL_PANEL_IDS = [...Object.values(VIEWS).flat(), ...HOME_GRID_IDS];
let mode = 'titles';

// Views where the body must not scroll (they fill the viewport themselves)
const FIXED_VIEWPORT_VIEWS = new Set(['title-detail', 'actress-detail']);

function showView(name) {
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
  // Hide status/sentinel for full-viewport views so the body doesn't scroll
  const fixedViewport = FIXED_VIEWPORT_VIEWS.has(name);
  const statusEl = document.getElementById('status');
  const sentinelEl = document.getElementById('sentinel');
  if (statusEl)  statusEl.style.display  = fixedViewport ? 'none' : '';
  if (sentinelEl) sentinelEl.style.display = fixedViewport ? 'none' : '';
  // Deactivate nav buttons when switching to any other view
  if (name !== 'titles-browse') {
    document.getElementById('titles-browse-btn')?.classList.remove('active');
  }
}

// ── ScrollingGrid ─────────────────────────────────────────────────────────
// Manages an infinite-scroll grid: tracks offset/exhaustion, fetches pages,
// and appends rendered cards. The URL builder receives (offset, limit) each
// call, so closures over mutable state (e.g. queueVolumeId) work naturally.
const PAGE_SIZE = 24;

class ScrollingGrid {
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

    try {
      const res = await fetch(this.urlFn(this.offset, limit));
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
let activeGrid = null;

const sentinel = document.getElementById('sentinel');
const observer = new IntersectionObserver(entries => {
  if (entries[0].isIntersecting && activeGrid) activeGrid.loadMore();
}, { rootMargin: '300px' });
observer.observe(sentinel);

// ── Card renderers ────────────────────────────────────────────────────────
function renderLocation(path) {
  const sep = path.lastIndexOf('/');
  if (sep < 0) return `<span class="title-location-folder">${esc(path)}</span>`;
  return `<span class="title-location-prefix">${esc(path.slice(0, sep + 1))}</span><span class="title-location-folder">${esc(path.slice(sep + 1))}</span>`;
}

function gradeBadgeHtml(grade) {
  if (!grade) return '';
  return `<span class="grade-badge" data-grade="${esc(grade)}">${esc(grade)}</span>`;
}

// Tag → consistent color derived from tag string
function tagHue(tag) {
  let h = 0;
  for (let i = 0; i < tag.length; i++) h = (h * 31 + tag.charCodeAt(i)) & 0xffff;
  return h % 360;
}

function tagBadgeHtml(tag) {
  const hue = tagHue(tag);
  const style = `color:hsl(${hue},65%,65%);background:hsl(${hue},40%,12%);border:1px solid hsl(${hue},50%,38%)`;
  return `<span class="tag-badge" style="${style}">${esc(tag)}</span>`;
}

function updateCardIndicators(code, favorite, bookmark) {
  const card = document.querySelector(`.card[data-code="${CSS.escape(code)}"]`);
  if (!card) return;
  const codeEl = card.querySelector('.title-code');
  if (!codeEl) return;
  const favIcon = favorite ? '<svg class="card-fav-icon" viewBox="0 0 24 24" width="12" height="12"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>' : '';
  const bmIcon = bookmark ? '<svg class="card-bm-icon" viewBox="0 0 24 24" width="12" height="12"><path d="M6 2h12a1 1 0 0 1 1 1v18.5a.5.5 0 0 1-.8.4L12 17.5 5.8 21.9a.5.5 0 0 1-.8-.4V3a1 1 0 0 1 1-1z"/></svg>' : '';
  const grade = codeEl.querySelector('.grade-badge');
  const gradeHtml = grade ? grade.outerHTML : '';
  codeEl.className = favorite && bookmark ? 'title-code title-code-fav title-code-bold'
    : favorite ? 'title-code title-code-fav'
    : bookmark ? 'title-code title-code-bm title-code-bold'
    : 'title-code';
  codeEl.innerHTML = `${favIcon}${bmIcon}${esc(code)}${gradeHtml}`;
}

function makeTitleCard(t) {
  const card = document.createElement('div');
  card.className = 'card';
  card.dataset.code = t.code;

  const coverHtml = t.coverUrl
    ? `<div class="cover-wrap"><img class="cover-img" src="${esc(t.coverUrl)}" alt="${esc(t.code)}" loading="lazy"></div>`
    : `<div class="cover-wrap"><div class="cover-placeholder">${esc(t.code)}</div></div>`;

  let actressHtml;
  if (t.actresses && t.actresses.length > 1) {
    // Multi-actress ticker
    const names = t.actresses.map(a => {
      const { first: fn, last: ln } = splitName(a.name);
      const nameHtml = ln
        ? `<span class="ticker-first">${esc(fn)}</span> <span class="ticker-last">${esc(ln)}</span>`
        : `<span class="ticker-first">${esc(fn)}</span>`;
      return `<a class="actress-link ticker-name" href="#" data-actress-id="${a.id}">${nameHtml}</a>`;
    });
    const tickerContent = names.join('<span class="ticker-sep">, </span>');
    // Duplicate content for seamless loop
    actressHtml = `<div class="actress-name ticker-wrap"><div class="ticker-track">`
      + `<span class="ticker-segment">${tickerContent}</span>`
      + `<span class="ticker-segment">${tickerContent}</span>`
      + `</div></div>`;
  } else if (t.actressName) {
    const { first: fn, last: ln } = splitName(t.actressName);
    const tierHtml   = t.actressTier
      ? ` <span class="tier-badge tier-${esc(t.actressTier)}">${esc(t.actressTier.toLowerCase())}</span>` : '';
    const nameInner  = `<span class="card-actress-first">${esc(fn)}</span>${ln ? `<span class="card-actress-last"> ${esc(ln)}</span>` : ''}`;
    actressHtml = t.actressId
      ? `<div class="actress-name"><a class="actress-link" href="#" data-actress-id="${t.actressId}">${nameInner}</a>${tierHtml}</div>`
      : `<div class="actress-name">${nameInner}${tierHtml}</div>`;
  } else {
    actressHtml = `<div class="actress-name unknown">—</div>`;
  }

  let labelLineHtml = '';
  if (t.companyName || t.labelName) {
    const parts = [];
    if (t.companyName) parts.push(esc(t.companyName));
    if (t.labelName)   parts.push(`(${esc(t.labelName)})`);
    labelLineHtml = `<div class="title-label-line">${parts.join(' ')}</div>`;
  }

  // Prefer releaseDate (curated production date) over addedDate
  const displayDate = t.releaseDate || t.addedDate;
  const dateHtml = displayDate ? `<div class="added-date">${esc(displayDate)}</div>` : '';

  const titleEnHtml = t.titleEnglish ? `<div class="title-en">${esc(t.titleEnglish)}</div>` : '';
  const titleJaHtml = t.titleOriginal ? `<div class="title-ja">${esc(t.titleOriginal)}</div>` : '';

  const locs = (t.locations && t.locations.length > 0) ? t.locations : (t.location ? [t.location] : []);
  const locationHtml = locs.length > 0
    ? `<div class="title-locations">${locs.map(p => `<div class="title-location">${renderLocation(p)}</div>`).join('')}</div>`
    : '';

  const tags = t.tags || [];
  const tagsHtml = tags.length > 0
    ? `<div class="title-tags">${tags.map(tagBadgeHtml).join('')}</div>`
    : '';

  // Favorite/bookmark indicators on the title code line
  const favIcon = t.favorite ? '<svg class="card-fav-icon" viewBox="0 0 24 24" width="12" height="12"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>' : '';
  const bmIcon = t.bookmark ? '<svg class="card-bm-icon" viewBox="0 0 24 24" width="12" height="12"><path d="M6 2h12a1 1 0 0 1 1 1v18.5a.5.5 0 0 1-.8.4L12 17.5 5.8 21.9a.5.5 0 0 1-.8-.4V3a1 1 0 0 1 1-1z"/></svg>' : '';
  const codeClass = t.favorite && t.bookmark ? 'title-code title-code-fav title-code-bold'
    : t.favorite ? 'title-code title-code-fav'
    : t.bookmark ? 'title-code title-code-bm title-code-bold'
    : 'title-code';
  const titleCodeHtml = `<div class="${codeClass}">${favIcon}${bmIcon}${esc(t.code)}${gradeBadgeHtml(t.grade)}</div>`;

  const watchedHtml = t.lastWatchedAt ? `<div class="card-watched">watched ${timeAgo(t.lastWatchedAt)}${t.watchCount > 1 ? ` (${t.watchCount}x)` : ''}</div>` : '';

  card.innerHTML = `${coverHtml}<div class="card-info">${actressHtml}${titleCodeHtml}${titleEnHtml}${titleJaHtml}${labelLineHtml}${dateHtml}${locationHtml}${tagsHtml}${watchedHtml}</div>`;

  card.querySelectorAll('.actress-link').forEach(link => {
    link.addEventListener('click', e => {
      e.preventDefault();
      e.stopPropagation();
      openActressDetail(Number(link.dataset.actressId));
    });
  });
  card.addEventListener('click', () => openTitleDetail(t));
  return card;
}

const ROTATE_INTERVALS = [7000, 11000, 17000];
const activeIntervals = new Set();
const activeObservers = new Set();

function clearCardIntervals() {
  for (const id of activeIntervals) clearInterval(id);
  activeIntervals.clear();
  for (const obs of activeObservers) obs.disconnect();
  activeObservers.clear();
}

function actressFlagIconsHtml(a) {
  const parts = [];
  if (a.rejected) {
    parts.push('<svg class="card-rej-icon" viewBox="0 0 24 24" width="12" height="12"><path d="M6 6 L18 18 M18 6 L6 18"/></svg>');
  } else {
    if (a.favorite) parts.push('<svg class="card-fav-icon" viewBox="0 0 24 24" width="12" height="12"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>');
    if (a.bookmark) parts.push('<svg class="card-bm-icon" viewBox="0 0 24 24" width="12" height="12"><path d="M6 2h12a1 1 0 0 1 1 1v18.5a.5.5 0 0 1-.8.4L12 17.5 5.8 21.9a.5.5 0 0 1-.8-.4V3a1 1 0 0 1 1-1z"/></svg>');
  }
  return parts.join('');
}

function actressNameClass(a) {
  if (a.rejected) return 'actress-card-name';
  if (a.favorite) return 'actress-card-name actress-name-fav';
  if (a.bookmark) return 'actress-card-name actress-name-bm';
  return 'actress-card-name';
}

function updateActressCardIndicators(id, favorite, bookmark, rejected) {
  const card = document.querySelector(`.actress-card[data-actress-id="${id}"]`);
  if (!card) return;
  const nameEl = card.querySelector('.actress-card-name');
  if (!nameEl) return;
  const firstSpan = nameEl.querySelector('.actress-first-name');
  const lastSpan  = nameEl.querySelector('.actress-last-name');
  const firstHtml = firstSpan ? firstSpan.outerHTML : '';
  const lastHtml  = lastSpan ? lastSpan.outerHTML : '';
  nameEl.className = actressNameClass({ favorite, bookmark, rejected });
  nameEl.innerHTML = actressFlagIconsHtml({ favorite, bookmark, rejected }) + firstHtml + lastHtml;
}

function makeActressCard(a) {
  const card = document.createElement('div');
  card.className = 'actress-card';
  if (a.rejected) card.classList.add('actress-card-rejected');
  card.dataset.actressId = a.id;

  const covers = a.coverUrls || [];
  const coverWrap = document.createElement('div');
  coverWrap.className = 'cover-wrap';
  if (covers.length === 0) {
    coverWrap.innerHTML = `<div class="cover-placeholder">—</div>`;
  } else if (a.rejected || covers.length < 2) {
    // Rejected actresses and single-cover actresses get a static (non-animated) image.
    // Rejected cards are additionally desaturated via CSS on .actress-card-rejected.
    const img = document.createElement('img');
    img.className = 'cover-img';
    img.src = covers[0];
    img.alt = a.canonicalName;
    img.loading = 'lazy';
    coverWrap.appendChild(img);
  } else {
    // Multi-tile right-to-left marquee with per-tile hot-swap.
    // Track = UNIQUE unique tiles + the same UNIQUE tiles duplicated for a seamless loop.
    // Animation scrolls 0 → -50% so position -50% shows the duplicate of tile 0, matching
    // the initial state. When a unique tile exits the viewport we swap both it and its
    // paired duplicate with a fresh random pick from the cover pool, so by the next cycle
    // the user sees different images without the loop ever visibly seaming.
    const UNIQUE = Math.min(3, covers.length);
    const track = document.createElement('div');
    track.className = 'cover-marquee-track';
    // Randomize per-card pace and starting phase so adjacent cards don't march in lockstep.
    const perTileSec = 8 + Math.random() * 6;  // 8s–14s per tile
    const durationSec = UNIQUE * perTileSec;
    track.style.animationDuration = `${durationSec}s`;
    track.style.animationDelay = `-${(Math.random() * durationSec).toFixed(2)}s`;

    // Initial unique picks (sampled without replacement so the first cycle has no repeats).
    const pool = [...covers];
    const initialPicks = [];
    for (let i = 0; i < UNIQUE; i++) {
      const pickIdx = Math.floor(Math.random() * pool.length);
      initialPicks.push(pool.splice(pickIdx, 1)[0]);
    }
    // Build unique tiles + duplicate tiles.
    for (let i = 0; i < UNIQUE * 2; i++) {
      const tile = document.createElement('div');
      tile.className = 'cover-marquee-tile';
      const img = document.createElement('img');
      img.className = 'cover-img';
      img.src = initialPicks[i % UNIQUE];
      img.alt = a.canonicalName;
      img.loading = 'lazy';
      tile.appendChild(img);
      track.appendChild(tile);
    }
    coverWrap.appendChild(track);

    // Hot-swap: observe the unique tiles only. On exit, swap BOTH the unique tile and its
    // paired duplicate in lockstep — keeping them identical is what preserves the seamless
    // loop at -50%. `seen` guards against the initial "not-intersecting" callback that
    // fires for tiles 1..N-1 (which start off-screen right).
    const seen = new Set();
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const idx = Number(entry.target.dataset.tileIdx);
        if (entry.isIntersecting) {
          seen.add(idx);
          continue;
        }
        if (!seen.has(idx)) continue;
        const dup = track.children[idx + UNIQUE];
        const newSrc = covers[Math.floor(Math.random() * covers.length)];
        const uniqImg = entry.target.querySelector('img');
        const dupImg = dup ? dup.querySelector('img') : null;
        if (uniqImg) uniqImg.src = newSrc;
        if (dupImg)  dupImg.src  = newSrc;
      }
    }, { root: coverWrap, threshold: 0 });
    for (let i = 0; i < UNIQUE; i++) {
      track.children[i].dataset.tileIdx = String(i);
      observer.observe(track.children[i]);
    }
    activeObservers.add(observer);
  }
  card.appendChild(coverWrap);

  const { first: firstName, last: lastName } = splitName(a.canonicalName);
  const body = document.createElement('div');
  body.className = 'actress-card-body';
  body.innerHTML = `
    <div class="${actressNameClass(a)}">
      ${actressFlagIconsHtml(a)}<span class="actress-first-name">${esc(firstName)}</span>${lastName ? `<span class="actress-last-name">${esc(lastName)}</span>` : ''}
    </div>
    <div class="actress-card-meta">
      <span class="tier-badge tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>
    </div>
    ${renderDateRange(a.firstAddedDate, a.lastAddedDate)}
    <div class="actress-title-count">Titles: ${a.titleCount}</div>
    ${(a.folderPaths || []).length > 0
      ? `<div class="actress-folder-paths">${a.folderPaths.map(p => `<div class="actress-folder-path">${esc(p)}</div>`).join('')}</div>`
      : ''}
  `;
  card.appendChild(body);

  card.addEventListener('click', () => openActressDetail(a.id));
  return card;
}

// ── Grid instances ────────────────────────────────────────────────────────
const titlesGrid = new ScrollingGrid(
  document.getElementById('grid'),
  (o, l) => `/api/titles?offset=${o}&limit=${l}`,
  makeTitleCard,
  'no titles',
  { getMax: () => MAX_TOTAL }
);

let queueVolumeId = null;
let queueSmbPath  = null;

const queueGrid = new ScrollingGrid(
  document.getElementById('queue-grid'),
  (o, l) => `/api/queues/${encodeURIComponent(queueVolumeId)}/titles?offset=${o}&limit=${l}`,
  t => {
    if (queueSmbPath) {
      if (t.location) t.location = queueSmbPath + t.location;
      if (t.locations) t.locations = t.locations.map(p => queueSmbPath + p);
    }
    return makeTitleCard(t);
  },
  'no titles in queue',
  { getMax: () => MAX_TOTAL }
);

let poolVolumeId = null;
let poolSmbPath  = null;

const poolGrid = new ScrollingGrid(
  document.getElementById('pool-grid'),
  (o, l) => `/api/pool/${encodeURIComponent(poolVolumeId)}/titles?offset=${o}&limit=${l}`,
  t => {
    if (poolSmbPath) {
      if (t.location) t.location = poolSmbPath + t.location;
      if (t.locations) t.locations = t.locations.map(p => poolSmbPath + p);
    }
    return makeTitleCard(t);
  },
  'no titles in pool',
  { getMax: () => MAX_TOTAL }
);

const collectionsGrid = new ScrollingGrid(
  document.getElementById('collections-grid'),
  (o, l) => `/api/collections/titles?offset=${o}&limit=${l}`,
  makeTitleCard,
  'no titles in collections',
  { getMax: () => MAX_TOTAL }
);

// Title browse state:
//   null         → recent titles (default)
//   'search'     → use titleSearchTerm (product-number prefix search)
//   'favorites'  → favorited titles
//   'bookmarks'  → bookmarked titles
let titleBrowseMode = null;
let titleSearchTerm = '';

const allTitlesGrid = new ScrollingGrid(
  document.getElementById('titles-browse-grid'),
  (o, l) => {
    if (titleBrowseMode === 'search') {
      return `/api/titles?search=${encodeURIComponent(titleSearchTerm)}&offset=${o}&limit=${l}`;
    }
    if (titleBrowseMode === 'favorites')    return `/api/titles?favorites=true&offset=${o}&limit=${l}`;
    if (titleBrowseMode === 'bookmarks')   return `/api/titles?bookmarks=true&offset=${o}&limit=${l}`;
    if (titleBrowseMode === 'collections') return `/api/collections/titles?offset=${o}&limit=${l}`;
    return `/api/titles?offset=${o}&limit=${l}`;
  },
  makeTitleCard,
  'no titles',
  { getMax: () => MAX_TOTAL }
);

let detailActressId = null;
let detailCompanyFilter = null;

const actressDetailGrid = new ScrollingGrid(
  document.getElementById('detail-title-grid'),
  (o, l) => {
    let url = `/api/actresses/${detailActressId}/titles?offset=${o}&limit=${l}`;
    if (detailCompanyFilter) url += `&company=${encodeURIComponent(detailCompanyFilter)}`;
    return url;
  },
  makeTitleCard,
  'no titles'
);

// ── Random title/actress grids ────────────────────────────────────────────
const randomTitlesGrid = new ScrollingGrid(
  document.getElementById('random-titles-grid'),
  (_o, l) => `/api/titles/random?limit=${l}`,
  makeTitleCard,
  'no titles',
  { getMax: () => MAX_RANDOM_TITLES }
);

const randomActressHomeGrid = new ScrollingGrid(
  document.getElementById('random-actress-home-grid'),
  (_o, l) => `/api/actresses/random?limit=${l}`,
  makeActressCard,
  'no actresses',
  { getMax: () => MAX_RANDOM_ACTRESSES }
);

// ── Home tab management ────────────────────────────────────────────────────
let homeTab = 'latest';

function activateHomeTab(tab) {
  homeTab = tab;

  // Update tab button states
  document.querySelectorAll('.home-tab').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.tab === tab);
  });

  // Hide all home content grids, then show the right one
  for (const id of HOME_GRID_IDS) {
    const el = document.getElementById(id);
    if (el) el.style.display = 'none';
  }

  if (tab === 'latest') {
    document.getElementById('grid').style.display = 'grid';
    activeGrid = titlesGrid;
    ensureSentinel();
    if (titlesGrid.offset === 0 && !titlesGrid.exhausted) titlesGrid.loadMore();
  } else if (tab === 'random-titles') {
    document.getElementById('random-titles-grid').style.display = 'grid';
    activeGrid = randomTitlesGrid;
    ensureSentinel();
    if (randomTitlesGrid.offset === 0 && !randomTitlesGrid.exhausted) randomTitlesGrid.loadMore();
  } else if (tab === 'random-actresses') {
    document.getElementById('random-actress-home-grid').style.display = 'grid';
    activeGrid = randomActressHomeGrid;
    ensureSentinel();
    if (randomActressHomeGrid.offset === 0 && !randomActressHomeGrid.exhausted) randomActressHomeGrid.loadMore();
  }
}

document.querySelectorAll('.home-tab').forEach(btn => {
  btn.addEventListener('click', () => activateHomeTab(btn.dataset.tab));
});

// ── Title browse ──────────────────────────────────────────────────────────
function showTitlesView() {
  showView('titles');
  actressesBtn.classList.remove('active');
  collectionsBtn.classList.remove('active');
  document.getElementById('titles-browse-btn')?.classList.remove('active');
  closeQueuesDropdown();
  closeArchivesDropdown();
  // Reset actress landing state
  if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
  actressBrowseMode = null;
  actressSearchTerm = '';
  if (actressSearchInput) {
    actressSearchInput.value = '';
    actressSearchInput.classList.remove('invalid');
  }
  updateActressLandingSelection();
  updateBreadcrumb([]);
  activateHomeTab(homeTab);
}

// ── Archives browse ───────────────────────────────────────────────────────
const archivesBtn      = document.getElementById('archives-btn');
const archivesDropdown = document.getElementById('archives-dropdown');

archivesBtn.addEventListener('click', e => {
  e.stopPropagation();
  closeQueuesDropdown();
  const isOpen = archivesDropdown.classList.contains('open');
  if (isOpen) { closeArchivesDropdown(); return; }
  if (archivesDropdown.childElementCount === 0) populateArchivesDropdown();
  archivesDropdown.classList.add('open');
  archivesBtn.classList.add('active');
});

archivesDropdown.addEventListener('click', e => e.stopPropagation());

function closeArchivesDropdown() {
  archivesDropdown.classList.remove('open');
  archivesBtn.classList.remove('active');
}

function populateArchivesDropdown() {
  archivesDropdown.innerHTML = '';
  const col = document.createElement('div');
  col.className = 'dropdown-tier-col';

  const starsChip = document.createElement('div');
  starsChip.className = 'prefix-chip';
  starsChip.textContent = 'stars';
  starsChip.dataset.archives = 'stars';
  starsChip.addEventListener('click', () => { closeArchivesDropdown(); selectActressBrowseMode('archive-volumes'); });
  col.appendChild(starsChip);

  const poolChip = document.createElement('div');
  poolChip.className = 'prefix-chip';
  poolChip.textContent = 'pool';
  poolChip.dataset.archives = 'pool';
  col.appendChild(poolChip);

  archivesDropdown.appendChild(col);
}

// ── Actress browse ────────────────────────────────────────────────────────
const actressesBtn          = document.getElementById('actresses-btn');
const actressLandingEl      = document.getElementById('actress-landing');
const actressSearchInput    = document.getElementById('actress-search-input');
const actressSearchClearBtn = document.getElementById('actress-search-clear');
const actressFavoritesBtn   = document.getElementById('actress-favorites-btn');
const actressBookmarksBtn   = document.getElementById('actress-bookmarks-btn');
const actressTierRow        = document.getElementById('actress-landing-tier-row');

const ACTRESS_TIERS = ['LIBRARY', 'MINOR', 'POPULAR', 'SUPERSTAR', 'GODDESS'];
const ACTRESS_SEARCH_DELAY_MS = 350;
const ACTRESS_SEARCH_MIN_CHARS = 2;

// Current browse mode:
//   null            → empty grid, nothing selected
//   'search'        → use actressSearchTerm
//   'favorites'
//   'bookmarks'
//   'tier-<TIER>'
let actressBrowseMode = null;
let actressSearchTerm = '';
let actressSearchTimer = null;

function buildActressTierChips() {
  actressTierRow.innerHTML = '';
  for (const tier of ACTRESS_TIERS) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `actress-landing-tier tier-chip-${tier}`;
    btn.dataset.tier = tier;
    btn.textContent = tier.toLowerCase();
    btn.addEventListener('click', () => selectActressBrowseMode(`tier-${tier}`));
    actressTierRow.appendChild(btn);
  }
}
buildActressTierChips();

function updateActressLandingSelection() {
  actressFavoritesBtn.classList.toggle('selected', actressBrowseMode === 'favorites');
  actressBookmarksBtn.classList.toggle('selected', actressBrowseMode === 'bookmarks');
  actressTierRow.querySelectorAll('.actress-landing-tier').forEach(btn => {
    btn.classList.toggle('selected', actressBrowseMode === `tier-${btn.dataset.tier}`);
  });
}

// ── Actress scrolling grid ──
const actressGridEl = document.getElementById('actress-grid');
const actressScrollGrid = new ScrollingGrid(
  actressGridEl,
  (o, l) => {
    if (actressBrowseMode === 'search') {
      return `/api/actresses?search=${encodeURIComponent(actressSearchTerm)}&offset=${o}&limit=${l}`;
    }
    if (actressBrowseMode === 'favorites') return `/api/actresses?favorites=true&offset=${o}&limit=${l}`;
    if (actressBrowseMode === 'bookmarks') return `/api/actresses?bookmarks=true&offset=${o}&limit=${l}`;
    if (actressBrowseMode === 'archive-volumes')
      return `/api/actresses?volumes=${encodeURIComponent(ARCHIVE_VOLUMES)}&offset=${o}&limit=${l}`;
    if (actressBrowseMode && actressBrowseMode.startsWith('tier-')) {
      const tier = actressBrowseMode.slice(5);
      return `/api/actresses?tier=${encodeURIComponent(tier)}&offset=${o}&limit=${l}`;
    }
    return null; // no mode → empty grid
  },
  makeActressCard,
  'no actresses'
);

// Patch loadMore to short-circuit when urlFn returns null (no active mode)
const originalActressLoadMore = actressScrollGrid.loadMore.bind(actressScrollGrid);
actressScrollGrid.loadMore = async function() {
  const probeUrl = this.urlFn(this.offset, 1);
  if (probeUrl == null) {
    this.exhausted = true;
    setStatus('');
    return;
  }
  return originalActressLoadMore();
};

function clearActressGrid() {
  actressScrollGrid.reset();
  setStatus('');
}

function actressBrowseLabel(modeKey) {
  if (!modeKey) return '';
  if (modeKey === 'favorites') return 'Favorites';
  if (modeKey === 'bookmarks') return 'Bookmarks';
  if (modeKey === 'archive-volumes') return 'Archive';
  if (modeKey === 'search')    return `search: "${actressSearchTerm}"`;
  if (modeKey.startsWith('tier-')) return modeKey.slice(5).toLowerCase();
  return modeKey;
}

function updateActressBreadcrumb() {
  const crumbs = [{ label: 'Actresses', action: () => showActressLanding() }];
  if (actressBrowseMode) crumbs.push({ label: actressBrowseLabel(actressBrowseMode) });
  updateBreadcrumb(crumbs);
}

async function selectActressBrowseMode(modeKey) {
  actressBrowseMode = modeKey;
  // Selecting a chip clears any pending search and empties the search box
  if (modeKey !== 'search') {
    if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
    actressSearchTerm = '';
    if (actressSearchInput.value !== '') actressSearchInput.value = '';
    actressSearchInput.classList.remove('invalid');
  }
  updateActressLandingSelection();
  updateActressBreadcrumb();
  actressesBtn.classList.add('active');
  showView('actresses');
  activeGrid = actressScrollGrid;
  actressScrollGrid.reset();
  ensureSentinel();
  await actressScrollGrid.loadMore();
}

function showActressLanding() {
  closeQueuesDropdown();
  closeArchivesDropdown();
  actressesBtn.classList.add('active');
  if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
  actressBrowseMode = null;
  actressSearchTerm = '';
  actressSearchInput.value = '';
  actressSearchInput.classList.remove('invalid');
  updateActressLandingSelection();
  updateBreadcrumb([{ label: 'Actresses' }]);
  showView('actresses');
  requestAnimationFrame(() => {
    const header = document.querySelector('header');
    if (header) actressLandingEl.style.top = header.offsetHeight + 'px';
  });
  activeGrid = actressScrollGrid;
  clearActressGrid();
}

actressesBtn.addEventListener('click', e => {
  e.stopPropagation();
  selectActressBrowseMode('favorites');
});

// Toggle red "invalid" styling while input is below the 2-char minimum
// (but not empty — empty is a neutral reset state, not an error).
function updateActressSearchValidity() {
  const raw = actressSearchInput.value.trim();
  const invalid = raw.length > 0 && raw.length < ACTRESS_SEARCH_MIN_CHARS;
  actressSearchInput.classList.toggle('invalid', invalid);
}

// ── Search input: 5s debounce + 2-char minimum ──
function scheduleActressSearch() {
  if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
  updateActressSearchValidity();
  const raw = actressSearchInput.value.trim();
  if (raw.length < ACTRESS_SEARCH_MIN_CHARS) {
    // Below threshold → empty page and reset mode if we were in search mode
    if (actressBrowseMode === 'search') {
      actressBrowseMode = null;
      updateActressLandingSelection();
      updateActressBreadcrumb();
      clearActressGrid();
    }
    return;
  }
  actressSearchTimer = setTimeout(() => {
    actressSearchTimer = null;
    actressSearchTerm = raw;
    actressBrowseMode = 'search';
    updateActressLandingSelection();
    updateActressBreadcrumb();
    actressesBtn.classList.add('active');
    activeGrid = actressScrollGrid;
    actressScrollGrid.reset();
    ensureSentinel();
    actressScrollGrid.loadMore();
  }, ACTRESS_SEARCH_DELAY_MS);
}

actressSearchInput.addEventListener('input', scheduleActressSearch);
// Enter bypasses the debounce timer
actressSearchInput.addEventListener('keydown', e => {
  if (e.key !== 'Enter') return;
  e.preventDefault();
  if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
  const raw = actressSearchInput.value.trim();
  if (raw.length < ACTRESS_SEARCH_MIN_CHARS) { clearActressGrid(); return; }
  actressSearchTerm = raw;
  actressBrowseMode = 'search';
  updateActressLandingSelection();
  updateActressBreadcrumb();
  actressesBtn.classList.add('active');
  activeGrid = actressScrollGrid;
  actressScrollGrid.reset();
  ensureSentinel();
  actressScrollGrid.loadMore();
});

actressSearchClearBtn.addEventListener('click', () => {
  if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
  actressSearchInput.value = '';
  actressSearchInput.classList.remove('invalid');
  actressSearchTerm = '';
  if (actressBrowseMode === 'search') {
    actressBrowseMode = null;
    updateActressLandingSelection();
    updateActressBreadcrumb();
    clearActressGrid();
  }
  actressSearchInput.focus();
});

actressFavoritesBtn.addEventListener('click', () => selectActressBrowseMode('favorites'));
actressBookmarksBtn.addEventListener('click', () => selectActressBrowseMode('bookmarks'));

// ── Actress detail ────────────────────────────────────────────────────────
async function openActressDetail(actressId) {
  // Capture navigation context before switching views
  const sourceMode    = mode;
  const sourceHomeTab = homeTab;

  detailActressId = actressId;
  detailCompanyFilter = null;
  showView('actress-detail');
  activeGrid = actressDetailGrid;
  document.getElementById('sentinel')?.remove();
  actressDetailGrid.reset();
  document.getElementById('detail-cover').innerHTML = '';
  document.getElementById('detail-info').innerHTML  = '';
  document.getElementById('detail-profile').innerHTML = '';
  document.getElementById('detail-bio').innerHTML = '';
  document.getElementById('detail-nav-bar').innerHTML = '';
  ensureActressDetailSentinel();
  setStatus('loading');

  // Build breadcrumbs — include browse-mode context if we came from a browse grid
  let crumbs;
  if (sourceMode === 'titles' && sourceHomeTab === 'random-actresses') {
    // Came from home random-actresses tab — HOME crumb restores it via showTitlesView()
    crumbs = [];
  } else if (actressBrowseMode) {
    const modeKey = actressBrowseMode;
    crumbs = [
      { label: 'Actresses', action: () => showActressLanding() },
      { label: actressBrowseLabel(modeKey), action: () => selectActressBrowseMode(modeKey) },
    ];
  } else {
    crumbs = [{ label: 'Actresses', action: () => showActressLanding() }];
  }
  // Actress name added after fetch below; placeholder for now
  crumbs.push({ label: '...' });
  updateBreadcrumb(crumbs);

  try {
    const res = await fetch(`/api/actresses/${actressId}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    renderDetailPanel(data);
    // Update breadcrumb with actual name
    crumbs[crumbs.length - 1] = { label: data.canonicalName };
    updateBreadcrumb(crumbs);
  } catch (err) {
    setStatus('error loading actress');
    console.error(err);
    return;
  }

  await actressDetailGrid.loadMore();
}

async function searchStageName(actressId) {
  const btn = document.getElementById('btn-search-stage-name');
  if (!btn) return;
  btn.disabled = true;
  btn.textContent = 'Searching…';
  btn.classList.add('loading');
  try {
    const res = await fetch(`/api/actresses/${actressId}/stage-name/search`, { method: 'POST' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    if (data.stageName) {
      // Reload the detail panel to show the retrieved stage name
      openActressDetail(actressId);
    } else {
      btn.disabled = false;
      btn.textContent = 'Search for Stage Name';
      btn.classList.remove('loading');
      setStatus('stage name not found');
    }
  } catch (err) {
    console.error('Stage name search failed:', err);
    btn.disabled = false;
    btn.textContent = 'Search for Stage Name';
    btn.classList.remove('loading');
    setStatus('search failed');
  }
}

function renderDetailPanel(a) {
  // ── Column 1: Cover image ──
  const coverCol = document.getElementById('detail-cover');
  const covers = a.coverUrls || [];
  if (covers.length > 0) {
    const idx = Math.floor(Math.random() * covers.length);
    coverCol.innerHTML = `<img src="${esc(covers[idx])}" alt="${esc(a.canonicalName)}" loading="lazy">`;
  } else {
    coverCol.innerHTML = `<div class="detail-cover-placeholder">—</div>`;
  }

  // ── Column 1 continued: Info (below/beside cover) ──
  const { first: firstName, last: lastName } = splitName(a.canonicalName);

  const aliases = a.aliases || [];
  let aliasHtml = '';
  if (a.primaryName) {
    const { first: pFirst, last: pLast } = splitName(a.primaryName);
    const pNameHtml = pLast ? `${esc(pFirst)} ${esc(pLast)}` : esc(pFirst);
    aliasHtml = `<div class="detail-alias-row">
      <span class="detail-alias-label">Primarily known as</span>
      <span class="primary-badge" data-actress-id="${a.primaryId || ''}">${pNameHtml}</span>
    </div>`;
  } else if (aliases.length > 0) {
    aliasHtml = `<div class="detail-alias-row">
      <span class="detail-alias-label">Also known as</span>
      ${aliases.map(al => `<span class="alias-badge">${esc(al)}</span>`).join('')}
    </div>`;
  }

  const stageNameHtml = a.stageName
    ? `<div class="detail-stage-name">${esc(a.stageName)}</div>`
    : `<button class="btn-search-stage-name" id="btn-search-stage-name">Search for Stage Name</button>`;

  // Career span: prefer activeFrom/activeTo, fall back to title dates
  const careerStart = a.activeFrom || a.firstAddedDate;
  const careerEnd   = a.activeTo   || a.lastAddedDate;
  let careerHtml = '';
  if (careerStart || careerEnd) {
    const startHtml = careerStart ? `<span class="date-first">${esc(fmtDate(careerStart))}</span>` : '';
    const endHtml   = careerEnd   ? `<span class="${isStale(careerEnd) ? 'date-last-stale' : 'date-last'}">${esc(fmtDate(careerEnd))}</span>` : '';
    const sep = startHtml && endHtml ? ' → ' : '';
    careerHtml = `<div class="detail-career">${startHtml}${sep}${endHtml}</div>`;
  }

  document.getElementById('detail-info').innerHTML = `
    <div class="detail-name">
      <span class="detail-first-name">${esc(firstName)}</span>
      ${lastName ? `<span class="detail-last-name">${esc(lastName)}</span>` : ''}
    </div>
    ${stageNameHtml}
    <div class="detail-meta-row">
      <span class="tier-badge tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>
      ${a.grade ? `<span class="detail-grade">${esc(a.grade)}</span>` : ''}
    </div>
    <div class="actress-detail-actions">
      <button class="title-action-btn${a.favorite ? ' active' : ''}" id="actress-fav-btn" title="Favorite">
        <svg viewBox="0 0 24 24" width="22" height="22"><polygon class="star-icon" points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
      </button>
      <button class="title-action-btn${a.bookmark ? ' active' : ''}" id="actress-bm-btn" title="Bookmark">
        <svg viewBox="0 0 24 24" width="22" height="22"><path class="bookmark-icon" d="M6 2h12a1 1 0 0 1 1 1v18.5a.5.5 0 0 1-.8.4L12 17.5 5.8 21.9a.5.5 0 0 1-.8-.4V3a1 1 0 0 1 1-1z"/></svg>
      </button>
      <button class="title-action-btn reject-btn${a.rejected ? ' active' : ''}" id="actress-rej-btn" title="Reject">
        <svg viewBox="0 0 24 24" width="22" height="22"><path class="reject-icon" d="M6 6 L18 18 M18 6 L6 18"/></svg>
      </button>
    </div>
    ${aliasHtml}
    ${careerHtml}
  `;

  const btn = document.getElementById('btn-search-stage-name');
  if (btn) btn.addEventListener('click', () => searchStageName(a.id));

  // Sync all three toggle buttons and the open card (if any) from a server response.
  function applyActressFlags(data) {
    a.favorite = data.favorite;
    a.bookmark = data.bookmark;
    a.rejected = data.rejected;
    document.getElementById('actress-fav-btn').classList.toggle('active', data.favorite);
    document.getElementById('actress-bm-btn').classList.toggle('active', data.bookmark);
    document.getElementById('actress-rej-btn').classList.toggle('active', data.rejected);
    updateActressCardIndicators(a.id, data.favorite, data.bookmark, data.rejected);
  }

  document.getElementById('actress-fav-btn').addEventListener('click', () => {
    fetch(`/api/actresses/${a.id}/favorite`, { method: 'POST' })
      .then(r => r.json()).then(applyActressFlags);
  });
  document.getElementById('actress-bm-btn').addEventListener('click', () => {
    fetch(`/api/actresses/${a.id}/bookmark`, { method: 'POST' })
      .then(r => r.json()).then(applyActressFlags);
  });
  document.getElementById('actress-rej-btn').addEventListener('click', () => {
    fetch(`/api/actresses/${a.id}/reject`, { method: 'POST' })
      .then(r => r.json()).then(applyActressFlags);
  });

  // ── Column 2: Profile data ──
  const profileLines = [];
  if (a.dateOfBirth)  profileLines.push(['Born', esc(fmtDate(a.dateOfBirth))]);
  if (a.birthplace)   profileLines.push(['Birthplace', esc(a.birthplace)]);
  if (a.bloodType)    profileLines.push(['Blood Type', esc(a.bloodType)]);
  if (a.heightCm)     profileLines.push(['Height', `${a.heightCm} cm`]);
  if (a.bust || a.waist || a.hip) {
    const bwh = [a.bust || '?', a.waist || '?', a.hip || '?'].join(' / ');
    profileLines.push(['Measurements', bwh + (a.cup ? ` (${esc(a.cup)})` : '')]);
  }
  if (a.titleCount)   profileLines.push(['Titles', `${a.titleCount}`]);

  const profileEl = document.getElementById('detail-profile');
  if (profileLines.length > 0) {
    profileEl.innerHTML = profileLines.map(([label, value]) =>
      `<div class="detail-profile-row"><span class="detail-profile-label">${label}</span><span class="detail-profile-value">${value}</span></div>`
    ).join('');
  } else {
    profileEl.innerHTML = '';
  }

  // ── Column 3: Biography ──
  const bioEl = document.getElementById('detail-bio');
  if (a.biography) {
    bioEl.innerHTML = `<div class="detail-bio-text">${esc(a.biography)}</div>`;
  } else {
    bioEl.innerHTML = '';
  }

  // ── Navigation bar: ALL MOVIES + companies ──
  const companies = a.companies || [];
  const navBar = document.getElementById('detail-nav-bar');
  if (companies.length > 0) {
    navBar.innerHTML =
      `<div class="detail-nav-item selected" id="detail-all-movies">ALL MOVIES</div>` +
      companies.map(c => `<div class="detail-nav-item detail-company-item" data-company="${esc(c)}">${esc(c)}</div>`).join('');
    document.getElementById('detail-all-movies').addEventListener('click', () => setDetailCompanyFilter(null));
    navBar.querySelectorAll('.detail-company-item').forEach(el =>
      el.addEventListener('click', () => setDetailCompanyFilter(el.dataset.company))
    );
  } else {
    navBar.innerHTML = '';
  }
}

function setDetailCompanyFilter(company) {
  detailCompanyFilter = company;
  const allMoviesEl = document.getElementById('detail-all-movies');
  if (allMoviesEl) allMoviesEl.classList.toggle('selected', company === null);
  document.querySelectorAll('.detail-nav-item.detail-company-item').forEach(el =>
    el.classList.toggle('selected', el.dataset.company === company)
  );
  document.getElementById('sentinel')?.remove();
  actressDetailGrid.reset();
  ensureActressDetailSentinel();
  actressDetailGrid.loadMore();
}

// ── Queues browse ─────────────────────────────────────────────────────────
const queuesBtn      = document.getElementById('queues-btn');
const queuesDropdown = document.getElementById('queues-dropdown');

queuesBtn.addEventListener('click', async e => {
  e.stopPropagation();
  closeArchivesDropdown();
  const isOpen = queuesDropdown.classList.contains('open');
  if (isOpen) { closeQueuesDropdown(); return; }
  if (queuesDropdown.childElementCount === 0) await populateQueuesDropdown();
  queuesDropdown.classList.add('open');
  queuesBtn.classList.add('active');
});

queuesDropdown.addEventListener('click', e => e.stopPropagation());

// Single global listener closes all dropdowns
document.addEventListener('click', () => {
  closeArchivesDropdown();
  closeQueuesDropdown();
});

function closeQueuesDropdown() {
  queuesDropdown.classList.remove('open');
  queuesBtn.classList.remove('active');
}

async function populateQueuesDropdown() {
  queuesDropdown.innerHTML = '';

  let data;
  try {
    const res = await fetch('/api/queues/volumes');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    data = await res.json();
  } catch (err) {
    console.error('Failed to load queues', err);
    return;
  }

  const poolCol = document.createElement('div');
  poolCol.className = 'dropdown-tier-col';
  poolCol.appendChild(makeLabel('pool'));
  if (data.sortPool) {
    const unsortedChip = document.createElement('div');
    unsortedChip.className = 'prefix-chip';
    unsortedChip.textContent = 'unsorted';
    unsortedChip.addEventListener('click', () => openPoolView(data.sortPool.id, data.sortPool.smbPath));
    poolCol.appendChild(unsortedChip);
  }
  queuesDropdown.appendChild(poolCol);
  queuesDropdown.appendChild(Object.assign(document.createElement('div'), { className: 'dropdown-col-divider' }));

  const volCol = document.createElement('div');
  volCol.className = 'dropdown-prefix-col';
  volCol.appendChild(makeLabel('volumes'));
  for (const v of (data.volumes || [])) {
    const chip = document.createElement('div');
    chip.className = 'prefix-chip';
    chip.textContent = v.id;
    chip.addEventListener('click', () => openQueueView(v.id, v.smbPath));
    volCol.appendChild(chip);
  }
  queuesDropdown.appendChild(volCol);
}

async function openQueueView(volumeId, smbPath) {
  queueVolumeId = volumeId;
  queueSmbPath  = smbPath || null;
  closeQueuesDropdown();
  queuesBtn.classList.add('active');
  showView('queue');
  document.getElementById('queue-header').textContent =
    queueSmbPath ? `${queueSmbPath}/queue` : `${volumeId}/queue`;
  updateBreadcrumb([
    { label: 'Queues', action: () => queuesBtn.click() },
    { label: volumeId },
  ]);
  activeGrid = queueGrid;
  queueGrid.reset();
  ensureSentinel();
  await queueGrid.loadMore();
}

// ── Pool browse ──────────────────────────────────────────────────────────
async function openPoolView(volumeId, smbPath) {
  poolVolumeId = volumeId;
  poolSmbPath  = smbPath || null;
  closeQueuesDropdown();
  queuesBtn.classList.add('active');
  showView('pool');
  document.getElementById('pool-header').textContent =
    poolSmbPath ? poolSmbPath : volumeId;
  updateBreadcrumb([
    { label: 'Queues', action: () => queuesBtn.click() },
    { label: 'unsorted' },
  ]);
  activeGrid = poolGrid;
  poolGrid.reset();
  ensureSentinel();
  await poolGrid.loadMore();
}

// ── Collections browse ───────────────────────────────────────────────────
const collectionsBtn = document.getElementById('title-collections-btn');

collectionsBtn.addEventListener('click', () => selectTitleBrowseMode('collections'));

// ── Title detail ──────────────────────────────────────────────────────────
function openTitleDetail(t) {
  const sourceMode          = mode;
  const sourceHomeTab       = homeTab;
  const sourceTitleBrowseMode = titleBrowseMode;

  showView('title-detail');
  document.getElementById('title-detail-cover').innerHTML = '';
  document.getElementById('title-detail-info').innerHTML  = '';
  document.getElementById('title-detail-right').innerHTML =
    '<div id="title-video-container"></div><div id="title-more-container"></div>';
  renderTitleDetail(t);
  loadLastWatched(t.code);
  loadTitleVideos(t.code);
  loadMoreFromActress(t);

  // Breadcrumb: include source context
  let crumbs = [];
  if (sourceMode === 'actresses' || sourceMode === 'actress-detail') {
    crumbs = [{ label: 'Actresses', action: () => showActressLanding() }];
    if (actressBrowseMode) {
      const modeKey = actressBrowseMode;
      crumbs.push({ label: actressBrowseLabel(modeKey), action: () => selectActressBrowseMode(modeKey) });
    }
  } else if (sourceMode === 'titles-browse' && sourceTitleBrowseMode === 'collections') {
    crumbs = [{ label: 'Collections', action: () => collectionsBtn.click() }];
  } else if (sourceMode === 'titles-browse') {
    crumbs = [{ label: 'Titles', action: () => titlesBrowseBtn.click() }];
  }
  crumbs.push({ label: t.code });
  updateBreadcrumb(crumbs);
}

function renderTitleDetail(t) {
  const coverEl = document.getElementById('title-detail-cover');
  const infoEl  = document.getElementById('title-detail-info');

  // Cover
  if (t.coverUrl) {
    coverEl.innerHTML = `<img src="${esc(t.coverUrl)}" alt="${esc(t.code)}" loading="lazy">`;
  } else {
    coverEl.innerHTML = `<div class="title-detail-cover-placeholder">${esc(t.code)}</div>`;
  }

  // Titles — Japanese first (large bold white), then English
  const hasJa = !!t.titleOriginal;
  const hasEn = !!t.titleEnglish;
  const jaTitleHtml = hasJa
    ? `<div class="title-detail-title-ja">${esc(t.titleOriginal)}</div>`
    : '';
  const enClass = hasJa ? 'title-detail-title-en title-detail-title-en--secondary' : 'title-detail-title-en';
  const enTitleHtml = hasEn
    ? `<div class="${enClass}">${esc(t.titleEnglish)}</div>`
    : '';

  // Actresses — prefer multi-actress list, fall back to single actressId
  const actresses = (t.actresses && t.actresses.length > 0)
    ? t.actresses
    : (t.actressId ? [{ id: t.actressId, name: t.actressName, tier: t.actressTier }] : []);

  let actressesHtml = '';
  if (actresses.length > 0) {
    const lines = actresses
      .filter(a => a.name)
      .map(a => {
        const spaceIdx = a.name.indexOf(' ');
        let nameHtml;
        if (spaceIdx === -1) {
          nameHtml = `<span class="title-detail-actress-first">${esc(a.name)}</span>`;
        } else {
          const first = a.name.slice(0, spaceIdx);
          const last  = a.name.slice(spaceIdx + 1);
          nameHtml = `<span class="title-detail-actress-first">${esc(first)}</span> <span class="title-detail-actress-last">${esc(last)}</span>`;
        }
        const tierHtml = a.tier
          ? ` <span class="tier-badge tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>`
          : '';
        return `<div class="title-detail-actress-row"><a class="actress-link title-detail-actress-link" href="#" data-actress-id="${a.id}">${nameHtml}${tierHtml}</a></div>`;
      })
      .join('');
    actressesHtml = `<div class="title-detail-row">
      <span class="title-detail-label">Cast</span>
      <span class="title-detail-value title-detail-cast">${lines}</span>
    </div>`;
  }

  // Label / company — company bold yellow, label name muted
  let labelHtml = '';
  if (t.companyName || t.labelName) {
    let text = '';
    if (t.companyName) text += `<span class="title-detail-company">${esc(t.companyName)}</span>`;
    if (t.labelName)   text += ` <span class="title-detail-label-name">(${esc(t.labelName)})</span>`;
    labelHtml = `<div class="title-detail-row">
      <span class="title-detail-label">Label</span>
      <span class="title-detail-value">${text}</span>
    </div>`;
  }

  // Date
  const displayDate = t.releaseDate || t.addedDate;
  const dateLabel   = t.releaseDate ? 'Released' : 'Added';
  const dateHtml = displayDate ? `<div class="title-detail-row">
    <span class="title-detail-label">${dateLabel}</span>
    <span class="title-detail-value">${esc(fmtDate(displayDate))}</span>
  </div>` : '';

  // Tags
  const tags = t.tags || [];
  const tagsHtml = tags.length > 0 ? `<div class="title-detail-row">
    <span class="title-detail-label">Tags</span>
    <span class="title-detail-value title-detail-tags">${tags.map(tagBadgeHtml).join('')}</span>
  </div>` : '';

  // Grade — larger, prominent row
  const gradeHtml = t.grade ? `<div class="title-detail-row title-detail-grade-row">
    <span class="title-detail-label title-detail-grade-label">Grade</span>
    <span class="title-detail-value">${gradeBadgeHtml(t.grade)}</span>
  </div>` : '';

  // NAS paths
  const paths = t.nasPaths || [];
  const nasHtml = paths.length > 0 ? `<div class="title-detail-row title-detail-nas-row">
    <span class="title-detail-label title-detail-location-label">Location</span>
    <span class="title-detail-value title-detail-nas-paths">${paths.map(p => `<div class="title-detail-nas-path">${esc(p)}</div>`).join('')}</span>
  </div>` : '';

  infoEl.innerHTML = `
    ${jaTitleHtml}
    ${enTitleHtml}
    <div class="title-detail-code">${esc(t.code)}</div>
    <div class="title-detail-actions">
      <button class="title-action-btn${t.favorite ? ' active' : ''}" id="title-fav-btn" title="Favorite">
        <svg viewBox="0 0 24 24" width="22" height="22"><polygon class="star-icon" points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
      </button>
      <button class="title-action-btn${t.bookmark ? ' active' : ''}" id="title-bm-btn" title="Bookmark">
        <svg viewBox="0 0 24 24" width="22" height="22"><path class="bookmark-icon" d="M6 2h12a1 1 0 0 1 1 1v18.5a.5.5 0 0 1-.8.4L12 17.5 5.8 21.9a.5.5 0 0 1-.8-.4V3a1 1 0 0 1 1-1z"/></svg>
      </button>
    </div>
    <div class="title-detail-meta">
      ${actressesHtml}
      ${labelHtml}
      ${dateHtml}
      ${gradeHtml}
      <div class="title-detail-row title-detail-watched-row" id="title-detail-watched" style="display:none">
        <span class="title-detail-label">Watched</span>
        <span class="title-detail-value title-detail-watched-value" id="title-detail-watched-value"></span>
      </div>
      ${nasHtml}
      ${tagsHtml}
    </div>
  `;

  // Toggle favorite
  document.getElementById('title-fav-btn').addEventListener('click', () => {
    fetch(`/api/titles/${encodeURIComponent(t.code)}/favorite`, { method: 'POST' })
      .then(r => r.json())
      .then(data => {
        t.favorite = data.favorite;
        const btn = document.getElementById('title-fav-btn');
        btn.classList.toggle('active', data.favorite);
        updateCardIndicators(t.code, t.favorite, t.bookmark);
      });
  });

  // Toggle bookmark
  document.getElementById('title-bm-btn').addEventListener('click', () => {
    fetch(`/api/titles/${encodeURIComponent(t.code)}/bookmark`, { method: 'POST' })
      .then(r => r.json())
      .then(data => {
        t.bookmark = data.bookmark;
        const btn = document.getElementById('title-bm-btn');
        btn.classList.toggle('active', data.bookmark);
        updateCardIndicators(t.code, t.favorite, t.bookmark);
      });
  });

  infoEl.querySelectorAll('.actress-link').forEach(link => {
    link.addEventListener('click', e => {
      e.preventDefault();
      openActressDetail(Number(link.dataset.actressId));
    });
  });
}

// ── Title videos ─────────────────────────────────────────────────────────
function loadTitleVideos(titleCode) {
  const rightCol = document.getElementById('title-video-container');
  rightCol.innerHTML = '<div class="video-loading">Discovering videos\u2026</div>';

  fetch(`/api/titles/${encodeURIComponent(titleCode)}/videos`)
    .then(r => r.json())
    .then(videos => {
      if (!videos || videos.length === 0) {
        rightCol.innerHTML = '<div class="video-empty">No video files found</div>';
        return;
      }
      rightCol.innerHTML = '';
      videos.forEach((v, idx) => {
        if (idx > 0) {
          rightCol.appendChild(Object.assign(document.createElement('hr'), {
            className: 'video-divider'
          }));
        }
        rightCol.appendChild(renderVideoSection(v, titleCode));
      });
    })
    .catch(() => {
      rightCol.innerHTML = '<div class="video-empty">Could not load videos</div>';
    });
}

async function loadMoreFromActress(t) {
  const actressId   = t.actressId || (t.actresses && t.actresses.length === 1 ? t.actresses[0].id : null);
  const actressName = t.actressName || (t.actresses && t.actresses.length === 1 ? t.actresses[0].name : null);
  const container   = document.getElementById('title-more-container');
  if (!actressId || !container) return;

  container.innerHTML = '<div class="more-from-loading">Loading more titles\u2026</div>';

  try {
    const res    = await fetch(`/api/actresses/${actressId}/titles?limit=13`);
    const titles = await res.json();
    const others = titles.filter(x => x.code !== t.code).slice(0, 12);
    if (others.length === 0) { container.innerHTML = ''; return; }

    const section = document.createElement('div');
    section.className = 'more-from-section';
    section.innerHTML = `<div class="more-from-heading">More from <span class="more-from-name">${esc(actressName || '')}</span></div>`;

    const row = document.createElement('div');
    row.className = 'more-from-row';
    others.forEach(other => row.appendChild(makeTitleCard(other)));
    section.appendChild(row);
    container.innerHTML = '';
    container.appendChild(section);
  } catch (e) {
    container.innerHTML = '';
  }
}

function renderVideoSection(v, titleCode) {
  const section = document.createElement('div');
  section.className = 'video-section';

  // Header: filename + size
  const sizeStr = v.fileSize != null ? formatFileSize(v.fileSize) : '';
  section.innerHTML = `
    <div class="video-header">
      <span class="video-filename">${esc(v.filename)}</span>
      ${sizeStr ? `<span class="video-size">${esc(sizeStr)}</span>` : ''}
      <span class="video-meta" id="video-meta-${v.id}"></span>
    </div>
    <div class="video-thumbs" id="video-thumbs-${v.id}">
      <div class="video-thumbs-loading">Loading previews\u2026</div>
    </div>
    <div class="video-player-wrap" id="video-wrap-${v.id}">
      <video class="video-player" id="video-player-${v.id}" controls preload="none"
             src="/api/stream/${v.id}"
             type="${esc(v.mimeType)}">
      </video>
      <button class="theater-btn" onclick="toggleTheater(${v.id})">Theater</button>
    </div>
  `;

  // Load thumbnails and metadata asynchronously
  loadVideoThumbnails(v.id);
  loadVideoMetadata(v.id);

  // Resume playback: save position periodically, restore on play
  const player = section.querySelector(`#video-player-${v.id}`);
  if (player) initResumePlayback(player, v.id, titleCode);

  return section;
}

function loadVideoThumbnails(videoId, attempt = 0) {
  const MAX_ATTEMPTS = 60; // poll for up to ~120s
  fetch(`/api/videos/${videoId}/thumbnails`)
    .then(r => r.json())
    .then(data => {
      const container = document.getElementById(`video-thumbs-${videoId}`);
      if (!container) return;

      const urls = data.urls || [];
      const total = data.total || 10;
      const generating = data.generating;

      // Render whatever thumbnails exist so far
      if (urls.length > 0) {
        container.style.gridTemplateColumns = `repeat(${THUMBNAIL_COLUMNS}, 1fr)`;
        container.innerHTML = urls.map((url, i) => {
          const fraction = total > 1 ? 0.03 + (0.94 * i / (total - 1)) : 0.5;
          return `<div class="thumb-wrapper" onclick="seekVideoTo(${videoId}, ${fraction})">
            <img class="video-thumb" src="${esc(url)}" loading="lazy" data-fraction="${fraction}">
            <span class="thumb-time" data-video-id="${videoId}" data-fraction="${fraction}">--:--</span>
          </div>`;
        }).join('');
      }

      // Show progress bar if still generating
      if (urls.length < total && (generating || attempt < 3)) {
        let progressEl = document.getElementById(`video-thumb-progress-${videoId}`);
        if (!progressEl) {
          progressEl = document.createElement('div');
          progressEl.id = `video-thumb-progress-${videoId}`;
          progressEl.className = 'thumb-progress';
          container.parentNode.insertBefore(progressEl, container.nextSibling);
        }
        const pct = Math.round((urls.length / total) * 100);
        progressEl.innerHTML = `<div class="thumb-progress-bar"><div class="thumb-progress-fill" style="width:${pct}%"></div></div>`
          + `<span class="thumb-progress-text">${urls.length}/${total} previews</span>`;

        if (attempt < MAX_ATTEMPTS) {
          setTimeout(() => loadVideoThumbnails(videoId, attempt + 1), 2000);
        }
      } else {
        // Done — remove progress bar if present
        const progressEl = document.getElementById(`video-thumb-progress-${videoId}`);
        if (progressEl) progressEl.remove();

        if (urls.length === 0) {
          container.innerHTML = '<span class="video-thumbs-loading">no preview available</span>';
        }
      }
    })
    .catch(() => {
      const container = document.getElementById(`video-thumbs-${videoId}`);
      if (container) container.innerHTML = '';
    });
}

function loadVideoMetadata(videoId) {
  fetch(`/api/videos/${videoId}/info`)
    .then(r => r.json())
    .then(info => {
      const el = document.getElementById(`video-meta-${videoId}`);
      if (!el || !info || !info.duration) return;
      const parts = [info.duration];
      if (info.resolution) parts.push(info.resolution);
      if (info.videoCodec) parts.push(info.videoCodec);
      if (info.bitrate) parts.push(info.bitrate);
      el.textContent = parts.join(' \u00b7 ');
      if (info.durationSeconds) {
        updateThumbTimestamps(videoId, info.durationSeconds);
      }
    })
    .catch(() => {});
}

// ── Thumbnail time labels ────────────────────────────────────────────────
function formatTimestamp(totalSeconds) {
  const s = Math.round(totalSeconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
  return `${m}:${String(sec).padStart(2, '0')}`;
}

function updateThumbTimestamps(videoId, durationSeconds) {
  document.querySelectorAll(`.thumb-time[data-video-id="${videoId}"]`).forEach(el => {
    const fraction = parseFloat(el.dataset.fraction);
    el.textContent = formatTimestamp(fraction * durationSeconds);
  });
}

// ── Thumbnail seek ──────────────────────────────────────────────────────
function seekVideoTo(videoId, fraction) {
  const player = document.getElementById(`video-player-${videoId}`);
  if (!player) return;

  function doSeek() {
    if (player.duration && isFinite(player.duration)) {
      player.currentTime = player.duration * fraction;
      if (player.paused) player.play();
    }
  }

  if (player.readyState >= 1) {
    doSeek();
  } else {
    // Metadata not loaded yet — load it first, then seek
    player.preload = 'metadata';
    player.addEventListener('loadedmetadata', doSeek, { once: true });
    player.load();
  }
}

// ── Theater mode ────────────────────────────────────────────────────────
function toggleTheater(videoId) {
  const wrap = document.getElementById(`video-wrap-${videoId}`);
  if (!wrap) return;
  const isActive = wrap.classList.toggle('theater-mode');
  const btn = wrap.querySelector('.theater-btn');
  if (btn) btn.textContent = isActive ? 'Exit Theater' : 'Theater';
  // Dim the left panel when theater is active
  const leftPanel = document.querySelector('.title-detail-left');
  if (leftPanel) leftPanel.classList.toggle('theater-dimmed', isActive);
}

// ── Resume playback ─────────────────────────────────────────────────────
function initResumePlayback(player, videoId, titleCode) {
  const key = `resume_${videoId}`;

  // Record watch history on first play
  let watchRecorded = false;
  player.addEventListener('play', () => {
    if (!watchRecorded && titleCode) {
      watchRecorded = true;
      fetch(`/api/watch-history/${encodeURIComponent(titleCode)}`, { method: 'POST' })
        .then(() => loadLastWatched(titleCode))
        .catch(() => {});
    }
  });

  // Save position every 5 seconds during playback
  let saveInterval = null;
  player.addEventListener('play', () => {
    saveInterval = setInterval(() => {
      if (player.currentTime > 5) {
        localStorage.setItem(key, JSON.stringify({
          time: player.currentTime,
          duration: player.duration,
          ts: Date.now()
        }));
      }
    }, 5000);
  });

  player.addEventListener('pause', () => { clearInterval(saveInterval); });
  player.addEventListener('ended', () => {
    clearInterval(saveInterval);
    localStorage.removeItem(key);
  });

  // Restore position on first play
  let resumed = false;
  player.addEventListener('loadedmetadata', () => {
    if (resumed) return;
    resumed = true;
    const saved = localStorage.getItem(key);
    if (!saved) return;
    try {
      const data = JSON.parse(saved);
      const pct = data.time / data.duration;
      // Only resume if between 5% and 90% — otherwise treat as fresh
      if (pct > 0.05 && pct < 0.90 && data.time > 10) {
        player.currentTime = data.time;
        showResumeToast(player, data.time);
      }
    } catch (e) { /* ignore corrupt data */ }
  });
}

function showResumeToast(player, time) {
  const wrap = player.closest('.video-player-wrap');
  if (!wrap) return;
  const toast = document.createElement('div');
  toast.className = 'resume-toast';
  toast.textContent = 'Resuming from ' + formatTimestamp(time);
  wrap.appendChild(toast);
  setTimeout(() => toast.remove(), 3000);
}

function formatTimestamp(seconds) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
  return `${m}:${String(s).padStart(2,'0')}`;
}

// ── Watch history ───────────────────────────────────────────────────────
function timeAgo(isoString) {
  const then = new Date(isoString);
  const now = new Date();
  const seconds = Math.floor((now - then) / 1000);
  if (seconds < 60)    return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60)    return minutes === 1 ? '1 minute ago' : `${minutes} minutes ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24)      return hours === 1 ? '1 hour ago' : `${hours} hours ago`;
  const days = Math.floor(hours / 24);
  if (days < 14)       return days === 1 ? '1 day ago' : `${days} days ago`;
  const weeks = Math.floor(days / 7);
  if (weeks < 9)       return weeks === 1 ? '1 week ago' : `${weeks} weeks ago`;
  const months = Math.floor(days / 30);
  if (months <= 3)     return months === 1 ? '1 month ago' : `${months} months ago`;
  return 'more than 3 months ago';
}

function loadLastWatched(titleCode) {
  fetch(`/api/watch-history/${encodeURIComponent(titleCode)}`)
    .then(r => r.json())
    .then(history => {
      const row = document.getElementById('title-detail-watched');
      const val = document.getElementById('title-detail-watched-value');
      if (!row || !val) return;
      if (history.length === 0) {
        row.style.display = 'none';
        return;
      }
      const latest = history[0];
      val.textContent = timeAgo(latest.watchedAt);
      if (history.length > 1) {
        val.textContent += ` (${history.length} times)`;
      }
      row.style.display = '';
    })
    .catch(() => {});
}

function formatFileSize(bytes) {
  if (bytes < 0) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

// ── Titles browse ─────────────────────────────────────────────────────────
const titlesBrowseBtn     = document.getElementById('titles-browse-btn');
const titleLandingEl      = document.getElementById('title-landing');
const titleSearchInput    = document.getElementById('title-search-input');
const titleSearchClearBtn = document.getElementById('title-search-clear');
const titleFavoritesBtn   = document.getElementById('title-favorites-btn');
const titleBookmarksBtn   = document.getElementById('title-bookmarks-btn');
const titleStudioBtn        = document.getElementById('title-studio-btn');
const titleStudioDivider    = document.getElementById('title-studio-divider');
const titleStudioGroupRow   = document.getElementById('title-studio-group-row');
const titleStudioLabelsEl   = document.getElementById('title-studio-labels');
const titleLabelDropdown    = document.getElementById('title-label-dropdown');

const TITLE_SEARCH_DELAY_MS  = 350;
const TITLE_SEARCH_MIN_CHARS = 1;

let titleSearchTimer = null;

// Label reference cache for tab-completion (loaded once on first use)
let titleLabelCache = null;
let titleLabelCachePromise = null;
let labelDropdownItems = [];    // current filtered list rendered in dropdown
let labelDropdownIndex = -1;    // currently highlighted item

async function ensureTitleLabels() {
  if (titleLabelCache) return titleLabelCache;
  if (titleLabelCachePromise) return titleLabelCachePromise;
  titleLabelCachePromise = (async () => {
    const res = await fetch('/api/titles/labels');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    titleLabelCache = Array.isArray(data) ? data : [];
    return titleLabelCache;
  })().catch(err => {
    console.error('Failed to load label catalog:', err);
    titleLabelCache = [];
    return titleLabelCache;
  });
  return titleLabelCachePromise;
}

// Studio group cache
let studioGroupsCache = null;
let studioGroupsCachePromise = null;
let selectedStudioSlug = null;

async function ensureStudioGroups() {
  if (studioGroupsCache) return studioGroupsCache;
  if (studioGroupsCachePromise) return studioGroupsCachePromise;
  studioGroupsCachePromise = (async () => {
    const res = await fetch('/api/titles/studios');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    studioGroupsCache = await res.json();
    return studioGroupsCache;
  })().catch(err => {
    console.error('Failed to load studio groups:', err);
    studioGroupsCache = [];
    return studioGroupsCache;
  });
  return studioGroupsCachePromise;
}

function renderStudioGroupRow(groups) {
  titleStudioGroupRow.innerHTML = '';
  groups.forEach(g => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'studio-group-btn' + (g.slug === selectedStudioSlug ? ' selected' : '');
    btn.textContent = g.name;
    btn.dataset.slug = g.slug;
    btn.addEventListener('click', () => selectStudioGroup(g.slug));
    titleStudioGroupRow.appendChild(btn);
  });
}

async function selectStudioGroup(slug) {
  selectedStudioSlug = slug;

  // Update button highlights
  titleStudioGroupRow.querySelectorAll('.studio-group-btn').forEach(btn => {
    btn.classList.toggle('selected', btn.dataset.slug === slug);
  });

  // Get the group's company list
  const groups = await ensureStudioGroups();
  const group = groups.find(g => g.slug === slug);
  if (!group) return;

  // Get cached labels and filter to this group's companies
  const allLabels = await ensureTitleLabels();
  const companySet = new Set(group.companies);

  // Group labels by company (preserve company order from studios.yaml)
  const byCompany = new Map();
  group.companies.forEach(c => byCompany.set(c, []));
  allLabels.forEach(lbl => {
    if (companySet.has(lbl.company)) {
      byCompany.get(lbl.company).push(lbl);
    }
  });

  renderStudioLabels(byCompany);
}

function renderStudioLabels(byCompany) {
  titleStudioLabelsEl.innerHTML = '';

  // Build left panel — one item per company
  const listEl = document.createElement('div');
  listEl.className = 'studio-label-list';

  let firstCompany = null;
  byCompany.forEach((labels, company) => {
    if (labels.length === 0) return;
    if (!firstCompany) firstCompany = company;
    const item = document.createElement('div');
    item.className = 'studio-label-item';
    item.dataset.company = company;

    const nameEl = document.createElement('span');
    nameEl.className = 'studio-label-item-name studio-label-item-company';
    nameEl.textContent = company;
    item.appendChild(nameEl);

    item.addEventListener('click', () => selectStudioCompany(company, byCompany));
    listEl.appendChild(item);
  });

  // Build right panel — detail area
  const detailEl = document.createElement('div');
  detailEl.className = 'studio-label-detail';
  detailEl.id = 'studio-label-detail';

  titleStudioLabelsEl.appendChild(listEl);
  titleStudioLabelsEl.appendChild(detailEl);
  titleStudioLabelsEl.style.display = 'flex';
  document.getElementById('titles-browse-grid').style.display = 'none';

  // Auto-select first company
  if (firstCompany) selectStudioCompany(firstCompany, byCompany);
}

function selectStudioCompany(company, byCompany) {
  // Highlight selected item in the left list
  titleStudioLabelsEl.querySelectorAll('.studio-label-item').forEach(el => {
    el.classList.toggle('selected', el.dataset.company === company);
  });

  const detailEl = document.getElementById('studio-label-detail');
  if (!detailEl) return;

  const labels = byCompany.get(company) || [];
  const labelCodeSet = new Set(labels.map(l => l.code.toUpperCase()));

  // 1. Heading
  const companyDesc = labels.length > 0 && labels[0].companyDescription ? labels[0].companyDescription : null;
  let html = `<div class="studio-detail-heading">${esc(company)}</div>`;

  // 2. Description
  if (companyDesc) html += `<div class="studio-detail-company-desc">${esc(companyDesc)}</div>`;

  // 3. Top 10 + Newest Actresses placeholders
  html += `<div class="studio-detail-section-label">${esc(company)}'s Top 10</div>
           <div class="studio-top-actress-grid" id="studio-top-actresses"><span class="studio-detail-loading">loading…</span></div>`;
  html += `<div class="studio-detail-section-label">Newest Actresses</div>
           <div class="studio-top-actress-grid" id="studio-newest-actresses"><span class="studio-detail-loading">loading…</span></div>`;

  // 4. Label list
  const byLabel = new Map();
  labels.forEach(lbl => {
    const key = lbl.labelName || lbl.code;
    if (!byLabel.has(key)) byLabel.set(key, []);
    byLabel.get(key).push(lbl);
  });
  html += '<div class="studio-detail-section-label" style="margin-top:32px">labels</div>';
  html += '<div class="studio-detail-label-list">';
  byLabel.forEach((codes, labelName) => {
    html += `<div class="studio-detail-label-group">
      <div class="studio-detail-label-name">${esc(labelName)}</div>
      <div class="studio-detail-code-rows">`;
    codes.forEach(lbl => {
      html += `<div class="studio-detail-code-row">
        <span class="studio-detail-code-badge">${esc(lbl.code)}</span>
        ${lbl.description ? `<span class="studio-detail-label-desc">${esc(lbl.description)}</span>` : ''}
      </div>`;
    });
    html += `</div></div>`;
  });
  html += '</div>';

  detailEl.innerHTML = html;

  const labelCodes = labels.map(l => l.code).join(',');

  function renderActressGrid(containerId, apiUrl) {
    fetch(apiUrl)
      .then(r => r.ok ? r.json() : Promise.reject(r.status))
      .then(ranked => {
        const el = document.getElementById(containerId);
        if (!el) return;
        if (ranked.length === 0) {
          el.innerHTML = '<span class="studio-detail-loading">none in library</span>';
          return;
        }
        const ids = ranked.map(a => a.id).join(',');
        return fetch(`/api/actresses?ids=${encodeURIComponent(ids)}`)
          .then(r => r.ok ? r.json() : Promise.reject(r.status))
          .then(summaries => {
            const el2 = document.getElementById(containerId);
            if (!el2) return;
            el2.innerHTML = '';
            summaries.forEach(a => {
              const allCovers = a.coverUrls || [];
              const filtered = allCovers.filter(url => {
                const seg = url.split('/')[2];
                return seg && labelCodeSet.has(seg.toUpperCase());
              });
              const card = makeActressCard({ ...a, coverUrls: filtered.length > 0 ? filtered : allCovers });
              card.addEventListener('click', () => showActressDetail(a.id));
              el2.appendChild(card);
            });
          });
      })
      .catch(() => {
        const el = document.getElementById(containerId);
        if (el) el.innerHTML = '<span class="studio-detail-loading">failed to load</span>';
      });
  }

  renderActressGrid('studio-top-actresses',    `/api/titles/top-actresses?labels=${encodeURIComponent(labelCodes)}&limit=10`);
  renderActressGrid('studio-newest-actresses', `/api/titles/newest-actresses?labels=${encodeURIComponent(labelCodes)}&limit=10`);
}

function showStudioGroupRow() {
  titleStudioDivider.style.display = '';
  titleStudioGroupRow.style.display = '';
}

function hideStudioGroupRow() {
  titleStudioDivider.style.display = 'none';
  titleStudioGroupRow.style.display = 'none';
  titleStudioLabelsEl.style.display = 'none';
  selectedStudioSlug = null;
}

function extractAlphaPrefix(raw) {
  if (!raw) return '';
  // Take leading alpha characters, ignore anything after first non-alpha
  const m = raw.trim().toUpperCase().match(/^([A-Z][A-Z0-9]*)/);
  return m ? m[1] : '';
}

function closeLabelDropdown() {
  titleLabelDropdown.style.display = 'none';
  titleLabelDropdown.innerHTML = '';
  labelDropdownItems = [];
  labelDropdownIndex = -1;
}

function renderLabelDropdown(matches, prefix) {
  titleLabelDropdown.innerHTML = '';
  labelDropdownItems = matches;
  labelDropdownIndex = matches.length > 0 ? 0 : -1;

  if (matches.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'title-label-dropdown-empty';
    empty.textContent = `no labels match "${prefix}"`;
    titleLabelDropdown.appendChild(empty);
    titleLabelDropdown.style.display = 'block';
    return;
  }

  matches.forEach((lbl, i) => {
    const item = document.createElement('div');
    item.className = 'title-label-dropdown-item' + (i === 0 ? ' highlighted' : '');
    item.dataset.index = String(i);
    const metaParts = [];
    if (lbl.labelName) metaParts.push(lbl.labelName);
    if (lbl.company)   metaParts.push(lbl.company);
    const metaHtml = metaParts.length
      ? `<span class="title-label-dropdown-meta">${esc(metaParts.join(' · '))}</span>`
      : '';
    item.innerHTML = `<span class="title-label-dropdown-code">${esc(lbl.code)}</span>${metaHtml}`;
    item.addEventListener('mouseenter', () => highlightLabelDropdownItem(i));
    item.addEventListener('mousedown', e => {
      // mousedown (not click) so we beat the input blur
      e.preventDefault();
      selectLabelDropdownItem(i);
    });
    titleLabelDropdown.appendChild(item);
  });
  titleLabelDropdown.style.display = 'block';
}

function highlightLabelDropdownItem(i) {
  const nodes = titleLabelDropdown.querySelectorAll('.title-label-dropdown-item');
  nodes.forEach((n, idx) => n.classList.toggle('highlighted', idx === i));
  labelDropdownIndex = i;
  const n = nodes[i];
  if (n) n.scrollIntoView({ block: 'nearest' });
}

function selectLabelDropdownItem(i) {
  const lbl = labelDropdownItems[i];
  if (!lbl) return;
  titleSearchInput.value = lbl.code + '-';
  closeLabelDropdown();
  titleSearchInput.focus();
  // Place caret at end
  const v = titleSearchInput.value;
  titleSearchInput.setSelectionRange(v.length, v.length);
  // Trigger a search for the chosen label
  scheduleTitleSearch(0);
}

async function openLabelDropdown() {
  const prefix = extractAlphaPrefix(titleSearchInput.value);
  if (!prefix) {
    closeLabelDropdown();
    return;
  }
  const all = await ensureTitleLabels();
  const matches = all.filter(lbl => lbl.code && lbl.code.startsWith(prefix)).slice(0, 50);
  renderLabelDropdown(matches, prefix);
}

function updateTitleLandingSelection() {
  titleFavoritesBtn.classList.toggle('selected', titleBrowseMode === 'favorites');
  titleBookmarksBtn.classList.toggle('selected', titleBrowseMode === 'bookmarks');
  titleStudioBtn.classList.toggle('selected',    titleBrowseMode === 'studio');
  collectionsBtn.classList.toggle('selected',    titleBrowseMode === 'collections');
}

function updateTitleBreadcrumb() {
  const crumbs = [{ label: 'Titles', action: () => showTitlesBrowse() }];
  if (titleBrowseMode === 'favorites')     crumbs.push({ label: 'Favorites' });
  else if (titleBrowseMode === 'bookmarks') crumbs.push({ label: 'Bookmarks' });
  else if (titleBrowseMode === 'studio')       crumbs.push({ label: 'Studio' });
  else if (titleBrowseMode === 'collections')  crumbs.push({ label: 'Collections' });
  else if (titleBrowseMode === 'search')       crumbs.push({ label: `search: "${titleSearchTerm}"` });
  updateBreadcrumb(crumbs);
}

function runTitleBrowseQuery() {
  document.getElementById('titles-browse-grid').style.display = 'grid';
  activeGrid = allTitlesGrid;
  allTitlesGrid.reset();
  ensureSentinel();
  allTitlesGrid.loadMore();
}

function selectTitleBrowseMode(modeKey) {
  titleBrowseMode = modeKey;
  if (modeKey !== 'search') {
    if (titleSearchTimer) { clearTimeout(titleSearchTimer); titleSearchTimer = null; }
    titleSearchTerm = '';
    if (titleSearchInput.value !== '') titleSearchInput.value = '';
    closeLabelDropdown();
  }
  updateTitleLandingSelection();
  updateTitleBreadcrumb();
  titlesBrowseBtn.classList.add('active');
  if (modeKey === 'studio') {
    document.getElementById('titles-browse-grid').style.display = 'none';
    titleStudioLabelsEl.style.display = 'none';
    ensureStudioGroups().then(groups => {
      renderStudioGroupRow(groups);
      showStudioGroupRow();
      if (groups.length > 0 && !selectedStudioSlug) {
        selectStudioGroup(groups[0].slug);
      }
    });
    return;
  }
  hideStudioGroupRow();
  runTitleBrowseQuery();
}

function scheduleTitleSearch(delayOverride) {
  if (titleSearchTimer) { clearTimeout(titleSearchTimer); titleSearchTimer = null; }
  const raw = titleSearchInput.value.trim();
  if (raw.length < TITLE_SEARCH_MIN_CHARS) {
    // Reset to default recent-titles view if we were searching
    if (titleBrowseMode === 'search') {
      titleBrowseMode = null;
      updateTitleLandingSelection();
      updateTitleBreadcrumb();
      runTitleBrowseQuery();
    }
    return;
  }
  const delay = delayOverride != null ? delayOverride : TITLE_SEARCH_DELAY_MS;
  titleSearchTimer = setTimeout(() => {
    titleSearchTimer = null;
    titleSearchTerm = raw;
    titleBrowseMode = 'search';
    updateTitleLandingSelection();
    updateTitleBreadcrumb();
    runTitleBrowseQuery();
  }, delay);
}

titleSearchInput.addEventListener('input', () => {
  closeLabelDropdown();
  scheduleTitleSearch();
});

titleSearchInput.addEventListener('keydown', e => {
  const dropdownOpen = titleLabelDropdown.style.display !== 'none' && labelDropdownItems.length > 0;

  if (e.key === 'Tab' && !e.shiftKey) {
    e.preventDefault();
    if (dropdownOpen) {
      // Accept the current highlight
      selectLabelDropdownItem(labelDropdownIndex >= 0 ? labelDropdownIndex : 0);
    } else if (extractAlphaPrefix(titleSearchInput.value)) {
      openLabelDropdown();
    }
    return;
  }

  if (e.key === 'Escape') {
    if (dropdownOpen) {
      e.preventDefault();
      closeLabelDropdown();
    }
    return;
  }

  if (dropdownOpen && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
    e.preventDefault();
    const delta = e.key === 'ArrowDown' ? 1 : -1;
    const next = (labelDropdownIndex + delta + labelDropdownItems.length) % labelDropdownItems.length;
    highlightLabelDropdownItem(next);
    return;
  }

  if (e.key === 'Enter') {
    e.preventDefault();
    if (dropdownOpen) {
      selectLabelDropdownItem(labelDropdownIndex >= 0 ? labelDropdownIndex : 0);
      return;
    }
    // Enter bypasses debounce
    if (titleSearchTimer) { clearTimeout(titleSearchTimer); titleSearchTimer = null; }
    const raw = titleSearchInput.value.trim();
    if (raw.length < TITLE_SEARCH_MIN_CHARS) return;
    titleSearchTerm = raw;
    titleBrowseMode = 'search';
    updateTitleLandingSelection();
    updateTitleBreadcrumb();
    titlesBrowseBtn.classList.add('active');
    runTitleBrowseQuery();
  }
});

titleSearchInput.addEventListener('blur', () => {
  // Small delay so mousedown on dropdown items still fires
  setTimeout(closeLabelDropdown, 150);
});

titleSearchClearBtn.addEventListener('click', () => {
  if (titleSearchTimer) { clearTimeout(titleSearchTimer); titleSearchTimer = null; }
  titleSearchInput.value = '';
  titleSearchTerm = '';
  closeLabelDropdown();
  if (titleBrowseMode === 'search') {
    titleBrowseMode = null;
    updateTitleLandingSelection();
    updateTitleBreadcrumb();
    runTitleBrowseQuery();
  }
  titleSearchInput.focus();
});

titleFavoritesBtn.addEventListener('click', () => selectTitleBrowseMode('favorites'));
titleBookmarksBtn.addEventListener('click', () => selectTitleBrowseMode('bookmarks'));
titleStudioBtn.addEventListener('click',    () => selectTitleBrowseMode('studio'));

function showTitlesBrowse() {
  closeQueuesDropdown();
  closeArchivesDropdown();
  titlesBrowseBtn.classList.add('active');
  actressesBtn.classList.remove('active');
  collectionsBtn.classList.remove('active');
  if (actressSearchTimer) { clearTimeout(actressSearchTimer); actressSearchTimer = null; }
  actressBrowseMode = null;
  actressSearchTerm = '';
  if (actressSearchInput) {
    actressSearchInput.value = '';
    actressSearchInput.classList.remove('invalid');
  }
  updateActressLandingSelection();
  if (titleSearchTimer) { clearTimeout(titleSearchTimer); titleSearchTimer = null; }
  titleBrowseMode = null;
  titleSearchTerm = '';
  titleSearchInput.value = '';
  closeLabelDropdown();
  hideStudioGroupRow();
  updateTitleLandingSelection();
  showView('titles-browse');
  requestAnimationFrame(() => {
    const header = document.querySelector('header');
    if (header) titleLandingEl.style.top = header.offsetHeight + 'px';
  });
  updateTitleBreadcrumb();
  runTitleBrowseQuery();
  // Preload label catalog in background for fast tab-completion later
  ensureTitleLabels();
}

titlesBrowseBtn.addEventListener('click', showTitlesBrowse);

// ── Initial load ──────────────────────────────────────────────────────────
showView('titles');
activateHomeTab('latest');
