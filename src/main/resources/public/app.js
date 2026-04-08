// ── App name + config ─────────────────────────────────────────────────────
let MAX_TOTAL           = 500;
let MAX_RANDOM_TITLES   = 500;
let MAX_RANDOM_ACTRESSES = 500;
let EXHIBITION_VOLUMES = '';
let ARCHIVE_VOLUMES    = '';

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
  if (!s) {
    s = document.createElement('div');
    s.id = 'sentinel';
    s.style.height = '1px';
    document.body.appendChild(s);
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
  requestAnimationFrame(() => {
    const header = document.querySelector('header');
    if (header) {
      document.querySelector('.actress-detail-panel').style.top = header.offsetHeight + 'px';
    }
  });
}

// ── View management ───────────────────────────────────────────────────────
// Each key maps to the IDs that should be visible in that view.
// Home content grids are managed separately via activateHomeTab().
const VIEWS = {
  titles:           ['home-tabs'],
  actresses:        ['actress-sub-nav', 'actress-grid'],
  'actress-detail': ['actress-detail'],
  queue:            ['queue-header', 'queue-grid'],
  pool:             ['pool-header', 'pool-grid'],
  collections:      ['collections-grid'],
};
const HOME_GRID_IDS = ['grid', 'random-titles-grid', 'random-actress-home-grid'];
const ALL_PANEL_IDS = [...Object.values(VIEWS).flat(), ...HOME_GRID_IDS];
let mode = 'titles';

function showView(name) {
  mode = name;
  for (const id of ALL_PANEL_IDS)
    document.getElementById(id).style.display = 'none';
  for (const id of (VIEWS[name] || [])) {
    const el = document.getElementById(id);
    if (el.classList.contains('grid')) el.style.display = 'grid';
    else if (el.classList.contains('actress-sub-nav')) el.style.display = 'flex';
    else el.style.display = 'block';
  }
  // Deactivate collections button when switching to any other view
  if (name !== 'collections') {
    document.getElementById('collections-btn')?.classList.remove('active');
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

// Grade → display style (colors match tier palette scale)
const GRADE_STYLE = {
  'SSS': 'color:#f0a0d8;background:#200818;box-shadow:0 0 6px rgba(192,80,160,0.25)',
  'SS':  'color:#e8c050;background:#201a08',
  'S':   'color:#b0d870;background:#141e06',
  'A+':  'color:#50d880;background:#082010',
  'A':   'color:#40c870;background:#071a0c',
  'A-':  'color:#60b888;background:#091410',
  'B+':  'color:#50b0d0;background:#081820',
  'B':   'color:#4090b8;background:#081018',
  'B-':  'color:#5888a0;background:#080e14',
  'C+':  'color:#607888;background:#0c1010',
  'C':   'color:#587070;background:#0c0e0e',
  'C-':  'color:#4a6060;background:#0a0c0c',
  'D':   'color:#505050;background:#0c0c0c',
  'F':   'color:#885050;background:#100a0a',
};

function gradeBadgeHtml(grade) {
  if (!grade) return '';
  const style = GRADE_STYLE[grade] || 'color:#888;background:#1a1a1a';
  return `<span class="grade-badge" style="${style}">${esc(grade)}</span>`;
}

// Tag → consistent color derived from tag string
function tagHue(tag) {
  let h = 0;
  for (let i = 0; i < tag.length; i++) h = (h * 31 + tag.charCodeAt(i)) & 0xffff;
  return h % 360;
}

function tagBadgeHtml(tag) {
  const hue = tagHue(tag);
  const style = `color:hsl(${hue},55%,62%);background:hsl(${hue},40%,10%)`;
  return `<span class="tag-badge" style="${style}">${esc(tag)}</span>`;
}

function makeTitleCard(t) {
  const card = document.createElement('div');
  card.className = 'card';

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

  const titleCodeHtml = `<div class="title-code">${esc(t.code)}${gradeBadgeHtml(t.grade)}</div>`;

  card.innerHTML = `${coverHtml}<div class="card-info">${actressHtml}${titleCodeHtml}${titleEnHtml}${titleJaHtml}${labelLineHtml}${dateHtml}${locationHtml}${tagsHtml}</div>`;

  card.querySelectorAll('.actress-link').forEach(link => {
    link.addEventListener('click', e => {
      e.preventDefault();
      openActressDetail(Number(link.dataset.actressId));
    });
  });
  return card;
}

const ROTATE_INTERVALS = [7000, 11000, 17000];

function makeActressCard(a) {
  const card = document.createElement('div');
  card.className = 'actress-card';

  const covers = a.coverUrls || [];
  const coverWrap = document.createElement('div');
  coverWrap.className = 'cover-wrap';
  if (covers.length > 0) {
    let idx = Math.floor(Math.random() * covers.length);
    const img = document.createElement('img');
    img.className = 'cover-img';
    img.src = covers[idx];
    img.alt = a.canonicalName;
    img.loading = 'lazy';
    coverWrap.appendChild(img);
    if (covers.length > 1) {
      const ms = ROTATE_INTERVALS[Math.floor(Math.random() * ROTATE_INTERVALS.length)];
      setInterval(() => {
        idx = (idx + 1) % covers.length;
        img.style.opacity = '0';
        setTimeout(() => { img.src = covers[idx]; img.style.opacity = '1'; }, 400);
      }, ms);
    }
  } else {
    coverWrap.innerHTML = `<div class="cover-placeholder">—</div>`;
  }
  card.appendChild(coverWrap);

  const { first: firstName, last: lastName } = splitName(a.canonicalName);
  const body = document.createElement('div');
  body.className = 'actress-card-body';
  body.innerHTML = `
    <div class="actress-card-name">
      <span class="actress-first-name">${esc(firstName)}</span>${lastName ? `<span class="actress-last-name">${esc(lastName)}</span>` : ''}
    </div>
    <div class="actress-card-meta">
      <span class="tier-badge tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>
      ${a.favorite ? '<div class="fav-dot"></div>' : ''}
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
  closeQueuesDropdown();
  closeArchivesDropdown();
  selectedPrefix  = null;
  selectedTier    = null;
  selectedSpecial = null;
  prefixDropdown.querySelectorAll('.prefix-chip').forEach(c => c.classList.remove('selected'));
  actressSubNav.innerHTML = '';
  updateBreadcrumb([]);
  activateHomeTab(homeTab);
}

// ── Archives browse ───────────────────────────────────────────────────────
const archivesBtn      = document.getElementById('archives-btn');
const archivesDropdown = document.getElementById('archives-dropdown');

archivesBtn.addEventListener('click', e => {
  e.stopPropagation();
  closeDropdown();
  closeQueuesDropdown();
  const isOpen = archivesDropdown.classList.contains('open');
  if (isOpen) { closeArchivesDropdown(); return; }
  if (archivesDropdown.childElementCount === 0) populateArchivesDropdown();
  archivesDropdown.classList.add('open');
  archivesBtn.classList.add('active');
});

document.addEventListener('click', () => closeArchivesDropdown());
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
  starsChip.addEventListener('click', () => { closeArchivesDropdown(); selectSpecial('archive', starsChip); });
  col.appendChild(starsChip);

  const poolChip = document.createElement('div');
  poolChip.className = 'prefix-chip';
  poolChip.textContent = 'pool';
  poolChip.dataset.archives = 'pool';
  // TODO: wire up when pool view is implemented
  col.appendChild(poolChip);

  archivesDropdown.appendChild(col);
}

// ── Actress browse ────────────────────────────────────────────────────────
const actressesBtn   = document.getElementById('actresses-btn');
const prefixDropdown = document.getElementById('prefix-dropdown');
const actressSubNav  = document.getElementById('actress-sub-nav');
let selectedPrefix  = null;
let selectedTier    = null;
// Special mode: null | 'favorites' | 'exhibition' | 'archive' | 'tier-GODDESS' | ... | 'all'
let selectedSpecial = null;

actressesBtn.addEventListener('click', async e => {
  e.stopPropagation();
  closeQueuesDropdown();
  closeArchivesDropdown();
  const isOpen = prefixDropdown.classList.contains('open');
  if (isOpen) { closeDropdown(); return; }
  if (prefixDropdown.childElementCount === 0) await populatePrefixDropdown();
  prefixDropdown.classList.add('open');
  actressesBtn.classList.add('active');
});

document.addEventListener('click', () => closeDropdown());
prefixDropdown.addEventListener('click', e => e.stopPropagation());

function closeDropdown() {
  prefixDropdown.classList.remove('open');
  if (selectedPrefix === null && selectedSpecial === null && mode !== 'actress-detail')
    actressesBtn.classList.remove('active');
}

const DROPDOWN_TIERS = ['LIBRARY', 'MINOR', 'POPULAR', 'SUPERSTAR', 'GODDESS'];

async function populatePrefixDropdown() {
  prefixDropdown.innerHTML = '';
  prefixDropdown.style.alignItems = 'flex-start';

  // ── Left column: special queries ──
  const specialCol = document.createElement('div');
  specialCol.className = 'dropdown-tier-col';
  specialCol.appendChild(makeLabel('browse'));

  const specials = [
    { key: 'favorites',   label: 'FAVORITES',   cls: 'special-chip-dim' },
    { key: 'exhibition',  label: 'EXHIBITION',  cls: '' },
    { key: 'archive',     label: 'ARCHIVE',     cls: '' },
  ];
  for (const { key, label } of specials) {
    const chip = document.createElement('div');
    chip.className = 'prefix-chip special-chip';
    chip.textContent = label;
    chip.dataset.special = key;
    chip.addEventListener('click', () => selectSpecial(key, chip));
    specialCol.appendChild(chip);
  }

  // Tier divider label
  const tierLabel = makeLabel('tiers');
  tierLabel.style.marginTop = '8px';
  specialCol.appendChild(tierLabel);

  for (const tier of DROPDOWN_TIERS) {
    const chip = document.createElement('div');
    chip.className = `prefix-chip special-chip tier-chip-${tier}`;
    chip.textContent = tier.toLowerCase();
    chip.dataset.special = `tier-${tier}`;
    chip.addEventListener('click', () => selectSpecial(`tier-${tier}`, chip));
    specialCol.appendChild(chip);
  }

  // ALL
  const allChip = document.createElement('div');
  allChip.className = 'prefix-chip special-chip';
  allChip.textContent = 'ALL';
  allChip.dataset.special = 'all';
  const allLabel = makeLabel('library');
  allLabel.style.marginTop = '8px';
  specialCol.appendChild(allLabel);
  allChip.addEventListener('click', () => selectSpecial('all', allChip));
  specialCol.appendChild(allChip);

  prefixDropdown.appendChild(specialCol);

  // ── Divider ──
  prefixDropdown.appendChild(Object.assign(document.createElement('div'), { className: 'dropdown-col-divider' }));

  // ── Right column: letter picker ──
  const prefixCol = document.createElement('div');
  prefixCol.className = 'dropdown-prefix-col';
  prefixCol.appendChild(makeLabel('name'));
  try {
    const res = await fetch('/api/actresses/index');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    for (const p of await res.json()) {
      const chip = document.createElement('div');
      chip.className = 'prefix-chip';
      chip.textContent = p;
      chip.dataset.prefix = p;
      chip.addEventListener('click', () => selectPrefix(p, chip));
      prefixCol.appendChild(chip);
    }
  } catch (err) {
    console.error('Failed to load actress index', err);
  }
  prefixDropdown.appendChild(prefixCol);

  // Restore selection highlight
  if (selectedSpecial) {
    prefixDropdown.querySelector(`.prefix-chip[data-special="${selectedSpecial}"]`)?.classList.add('selected');
  } else if (selectedPrefix) {
    prefixDropdown.querySelector(`.prefix-chip[data-prefix="${selectedPrefix}"]`)?.classList.add('selected');
  }
}

async function reSelectPrefix(prefix) {
  if (prefixDropdown.childElementCount === 0) await populatePrefixDropdown();
  const chip = prefixDropdown.querySelector(`.prefix-chip[data-prefix="${prefix}"]`);
  if (chip) selectPrefix(prefix, chip);
}

function clearDropdownSelection() {
  prefixDropdown.querySelectorAll('.prefix-chip').forEach(c => c.classList.remove('selected'));
}

// ── Actress scrolling grid ──
const actressScrollGrid = new ScrollingGrid(
  document.getElementById('actress-grid'),
  (o, l) => {
    if (selectedSpecial === 'favorites')  return `/api/actresses?favorites=true&offset=${o}&limit=${l}`;
    if (selectedSpecial === 'exhibition') return `/api/actresses?volumes=${encodeURIComponent(EXHIBITION_VOLUMES)}&offset=${o}&limit=${l}`;
    if (selectedSpecial === 'archive')    return `/api/actresses?volumes=${encodeURIComponent(ARCHIVE_VOLUMES)}&offset=${o}&limit=${l}`;
    if (selectedSpecial === 'all')        return `/api/actresses?all=true&offset=${o}&limit=${l}`;
    if (selectedSpecial && selectedSpecial.startsWith('tier-')) {
      const tier = selectedSpecial.slice(5);
      return `/api/actresses?tier=${encodeURIComponent(tier)}&offset=${o}&limit=${l}`;
    }
    let url = `/api/actresses?prefix=${encodeURIComponent(selectedPrefix)}&offset=${o}&limit=${l}`;
    if (selectedTier) url += `&tier=${encodeURIComponent(selectedTier)}`;
    return url;
  },
  makeActressCard,
  'no actresses'
);

async function buildActressSubNav() {
  actressSubNav.innerHTML = '';
  const TIERS = ['LIBRARY', 'MINOR', 'POPULAR', 'SUPERSTAR', 'GODDESS'];

  let tierCounts = {};
  try {
    const res = await fetch(`/api/actresses/tier-counts?prefix=${encodeURIComponent(selectedPrefix)}`);
    if (res.ok) tierCounts = await res.json();
  } catch (err) {
    console.error('Failed to load tier counts', err);
  }

  const total = Object.values(tierCounts).reduce((s, n) => s + n, 0);

  const allBtn = document.createElement('div');
  allBtn.className = 'actress-sub-nav-item selected';
  allBtn.textContent = total > 0 ? `ALL (${total})` : 'ALL';
  allBtn.addEventListener('click', () => selectActressTier(null));
  actressSubNav.appendChild(allBtn);

  for (const tier of TIERS) {
    const btn = document.createElement('div');
    btn.className = `actress-sub-nav-item tier-${tier}`;
    const count = tierCounts[tier];
    btn.textContent = count != null ? `${tier.toLowerCase()} (${count})` : tier.toLowerCase();
    btn.dataset.tier = tier;
    btn.addEventListener('click', () => selectActressTier(tier));
    actressSubNav.appendChild(btn);
  }
}

function selectActressTier(tier) {
  selectedTier = tier;
  actressSubNav.querySelectorAll('.actress-sub-nav-item').forEach(el => {
    const isTierBtn = el.dataset.tier;
    if (!isTierBtn) {
      el.classList.toggle('selected', tier === null);
    } else {
      el.classList.toggle('selected', el.dataset.tier === tier);
    }
  });
  const crumbs = [
    { label: 'Actresses', action: () => actressesBtn.click() },
    { label: selectedPrefix },
  ];
  if (tier) crumbs.push({ label: tier.toLowerCase() });
  updateBreadcrumb(crumbs);
  actressScrollGrid.reset();
  ensureSentinel();
  actressScrollGrid.loadMore();
}

// Labels for special modes in breadcrumbs
const SPECIAL_LABELS = {
  favorites:  'Favorites',
  exhibition: 'Exhibition',
  archive:    'Archive',
  all:        'All Actresses',
};

async function selectSpecial(key, chip) {
  selectedSpecial = key;
  selectedPrefix  = null;
  selectedTier    = null;
  clearDropdownSelection();
  chip.classList.add('selected');
  closeDropdown();
  actressesBtn.classList.add('active');

  const label = key.startsWith('tier-')
    ? key.slice(5).toLowerCase()
    : (SPECIAL_LABELS[key] || key);

  updateBreadcrumb([
    { label: 'Actresses', action: () => actressesBtn.click() },
    { label },
  ]);
  actressSubNav.style.display = 'none';
  showView('actresses');
  activeGrid = actressScrollGrid;
  actressScrollGrid.reset();
  ensureSentinel();
  await actressScrollGrid.loadMore();
}

async function selectPrefix(prefix, chip) {
  selectedPrefix  = prefix;
  selectedTier    = null;
  selectedSpecial = null;
  closeDropdown();
  actressesBtn.classList.add('active');
  clearDropdownSelection();
  chip.classList.add('selected');
  updateBreadcrumb([
    { label: 'Actresses', action: () => actressesBtn.click() },
    { label: prefix },
  ]);
  await buildActressSubNav();
  showView('actresses');
  requestAnimationFrame(() => {
    const header = document.querySelector('header');
    if (header) actressSubNav.style.top = header.offsetHeight + 'px';
  });
  activeGrid = actressScrollGrid;
  actressScrollGrid.reset();
  ensureSentinel();
  await actressScrollGrid.loadMore();
}

// ── Actress detail ────────────────────────────────────────────────────────
async function openActressDetail(actressId) {
  // Capture navigation context before switching views
  const sourceMode    = mode;
  const sourceHomeTab = homeTab;

  detailActressId = actressId;
  detailCompanyFilter = null;
  showView('actress-detail');
  activeGrid = actressDetailGrid;
  actressDetailGrid.reset();
  document.getElementById('detail-cover').innerHTML = '';
  document.getElementById('detail-info').innerHTML  = '';
  document.getElementById('detail-profile').innerHTML = '';
  document.getElementById('detail-bio').innerHTML = '';
  document.getElementById('detail-nav-bar').innerHTML = '';
  ensureSentinel();
  setStatus('loading');

  // Build breadcrumbs — include prefix/tier/special context if we came from a browse grid
  let crumbs;
  if (sourceMode === 'titles' && sourceHomeTab === 'random-actresses') {
    // Came from home random-actresses tab — HOME crumb restores it via showTitlesView()
    crumbs = [];
  } else if (selectedSpecial) {
    const sp = selectedSpecial;
    const label = sp.startsWith('tier-') ? sp.slice(5).toLowerCase() : (SPECIAL_LABELS[sp] || sp);
    crumbs = [
      { label: 'Actresses', action: () => actressesBtn.click() },
      { label, action: () => selectSpecial(sp, prefixDropdown.querySelector(`[data-special="${sp}"]`) || document.createElement('div')) },
    ];
  } else {
    crumbs = [{ label: 'Actresses', action: () => actressesBtn.click() }];
    if (selectedPrefix) {
      const p = selectedPrefix;
      const t = selectedTier;
      crumbs.push({ label: p, action: () => reSelectPrefix(p) });
      if (t) crumbs.push({ label: t.toLowerCase() });
    }
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
      ${a.favorite ? '<div class="fav-dot"></div>' : ''}
    </div>
    ${aliasHtml}
    ${careerHtml}
  `;

  const btn = document.getElementById('btn-search-stage-name');
  if (btn) btn.addEventListener('click', () => searchStageName(a.id));

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
  actressDetailGrid.reset();
  ensureSentinel();
  actressDetailGrid.loadMore();
}

// ── Queues browse ─────────────────────────────────────────────────────────
const queuesBtn      = document.getElementById('queues-btn');
const queuesDropdown = document.getElementById('queues-dropdown');

queuesBtn.addEventListener('click', async e => {
  e.stopPropagation();
  closeDropdown();
  closeArchivesDropdown();
  const isOpen = queuesDropdown.classList.contains('open');
  if (isOpen) { closeQueuesDropdown(); return; }
  if (queuesDropdown.childElementCount === 0) await populateQueuesDropdown();
  queuesDropdown.classList.add('open');
  queuesBtn.classList.add('active');
});

document.addEventListener('click', () => closeQueuesDropdown());
queuesDropdown.addEventListener('click', e => e.stopPropagation());

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
const collectionsBtn = document.getElementById('collections-btn');

collectionsBtn.addEventListener('click', () => {
  closeDropdown();
  closeQueuesDropdown();
  closeArchivesDropdown();
  collectionsBtn.classList.add('active');
  actressesBtn.classList.remove('active');
  selectedPrefix  = null;
  selectedTier    = null;
  selectedSpecial = null;
  showView('collections');
  updateBreadcrumb([{ label: 'Collections' }]);
  activeGrid = collectionsGrid;
  collectionsGrid.reset();
  ensureSentinel();
  collectionsGrid.loadMore();
});

// ── Action! (placeholder) ────────────────────────────────────────────────
const actionBtn = document.getElementById('action-btn');

actionBtn.addEventListener('click', () => {
  // Placeholder — will be wired up later
});

// ── Initial load ──────────────────────────────────────────────────────────
showView('titles');
activateHomeTab('latest');
