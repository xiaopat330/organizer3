// ── App name + config ─────────────────────────────────────────────────────
let MAX_TOTAL           = 500;
let MAX_RANDOM_TITLES   = 500;
let MAX_RANDOM_ACTRESSES = 500;
fetch('/api/config')
  .then(r => r.json())
  .then(cfg => {
    const name = cfg.appName || 'organizer3';
    document.getElementById('app-name').textContent = name.toLowerCase();
    document.title = name;
    if (cfg.maxBrowseTitles)    MAX_TOTAL            = cfg.maxBrowseTitles;
    if (cfg.maxRandomTitles)    MAX_RANDOM_TITLES    = cfg.maxRandomTitles;
    if (cfg.maxRandomActresses) MAX_RANDOM_ACTRESSES = cfg.maxRandomActresses;
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
  const firstHtml = first ? `<span class="date-first">${esc(first)}</span>` : '';
  const lastHtml  = last
    ? `<span class="${isStale(last) ? 'date-last-stale' : 'date-last'}">${esc(last)}</span>` : '';
  const sep = firstHtml && lastHtml ? ' → ' : '';
  return `<div class="${cls}">${firstHtml}${sep}${lastHtml}</div>`;
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
  actresses:        ['actress-grid'],
  'actress-detail': ['actress-detail'],
  queue:            ['queue-header', 'queue-grid'],
  pool:             ['pool-header', 'pool-grid'],
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
    el.style.display = el.classList.contains('grid') ? 'grid' : 'block';
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

function makeTitleCard(t) {
  const card = document.createElement('div');
  card.className = 'card';

  const coverHtml = t.coverUrl
    ? `<div class="cover-wrap"><img class="cover-img" src="${esc(t.coverUrl)}" alt="${esc(t.code)}" loading="lazy"></div>`
    : `<div class="cover-wrap"><div class="cover-placeholder">${esc(t.code)}</div></div>`;

  let actressHtml;
  if (t.actressName) {
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

  const dateHtml = t.addedDate ? `<div class="added-date">${esc(t.addedDate)}</div>` : '';
  const locs = (t.locations && t.locations.length > 0) ? t.locations : (t.location ? [t.location] : []);
  const locationHtml = locs.length > 0
    ? `<div class="title-locations">${locs.map(p => `<div class="title-location">${renderLocation(p)}</div>`).join('')}</div>`
    : '';

  card.innerHTML = `${coverHtml}<div class="card-info">${actressHtml}<div class="title-code">${esc(t.code)}</div>${labelLineHtml}${dateHtml}${locationHtml}</div>`;

  const link = card.querySelector('.actress-link');
  if (link) {
    link.addEventListener('click', e => {
      e.preventDefault();
      openActressDetail(Number(link.dataset.actressId));
    });
  }
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
  closeQueuesDropdown();
  closeArchivesDropdown();
  selectedPrefix  = null;
  selectedTier    = null;
  selectedArchive = null;
  prefixDropdown.querySelectorAll('.prefix-chip').forEach(c => c.classList.remove('selected'));
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
  starsChip.addEventListener('click', () => selectArchive('stars'));
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
let selectedPrefix  = null;
let selectedTier    = null;
let selectedArchive = null;

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
  if (selectedPrefix === null && selectedTier === null && mode !== 'actress-detail')
    actressesBtn.classList.remove('active');
}

async function populatePrefixDropdown() {
  prefixDropdown.innerHTML = '';

  const tierCol = document.createElement('div');
  tierCol.className = 'dropdown-tier-col';
  tierCol.appendChild(makeLabel('tier'));
  for (const tier of ['LIBRARY', 'MINOR', 'POPULAR', 'SUPERSTAR', 'GODDESS']) {
    const chip = document.createElement('div');
    chip.className = `prefix-chip tier-${tier}`;
    chip.textContent = tier.toLowerCase();
    chip.dataset.tier = tier;
    chip.addEventListener('click', () => selectTier(tier, chip));
    tierCol.appendChild(chip);
  }
  prefixDropdown.appendChild(tierCol);
  prefixDropdown.appendChild(Object.assign(document.createElement('div'), { className: 'dropdown-col-divider' }));

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
}

async function reSelectPrefix(prefix) {
  if (prefixDropdown.childElementCount === 0) await populatePrefixDropdown();
  const chip = prefixDropdown.querySelector(`.prefix-chip[data-prefix="${prefix}"]`);
  if (chip) selectPrefix(prefix, chip);
}

async function reSelectTier(tier) {
  if (prefixDropdown.childElementCount === 0) await populatePrefixDropdown();
  const chip = prefixDropdown.querySelector(`.prefix-chip[data-tier="${tier}"]`);
  if (chip) selectTier(tier, chip);
}

function clearDropdownSelection() {
  prefixDropdown.querySelectorAll('.prefix-chip').forEach(c => c.classList.remove('selected'));
}

async function loadActressGrid(url) {
  showView('actresses');
  activeGrid = null;
  document.getElementById('sentinel')?.remove();
  setStatus('loading');

  const actressGrid = document.getElementById('actress-grid');
  actressGrid.innerHTML = '';
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const actresses = await res.json();
    for (const a of actresses) actressGrid.appendChild(makeActressCard(a));
    setStatus(actresses.length === 0 ? 'no actresses' : '');
  } catch (err) {
    setStatus('error loading actresses');
    console.error(err);
  }
}

async function selectPrefix(prefix, chip) {
  selectedPrefix  = prefix;
  selectedTier    = null;
  selectedArchive = null;
  closeDropdown();
  actressesBtn.classList.add('active');
  clearDropdownSelection();
  chip.classList.add('selected');
  updateBreadcrumb([
    { label: 'Actresses', action: () => actressesBtn.click() },
    { label: prefix },
  ]);
  await loadActressGrid(`/api/actresses?prefix=${encodeURIComponent(prefix)}`);
}

async function selectTier(tier, chip) {
  selectedTier    = tier;
  selectedPrefix  = null;
  selectedArchive = null;
  closeDropdown();
  actressesBtn.classList.add('active');
  clearDropdownSelection();
  chip.classList.add('selected');
  updateBreadcrumb([
    { label: 'Actresses', action: () => actressesBtn.click() },
    { label: tier.toLowerCase() },
  ]);
  await loadActressGrid(`/api/actresses?tier=${encodeURIComponent(tier)}`);
}

const ARCHIVE_POOL_VOLUMES = 'qnap_archive,classic';

async function selectArchive(archive) {
  selectedArchive = archive;
  selectedPrefix  = null;
  selectedTier    = null;
  closeArchivesDropdown();
  archivesBtn.classList.add('active');
  updateBreadcrumb([
    { label: 'Archives', action: () => archivesBtn.click() },
    { label: archive },
  ]);
  await loadActressGrid(`/api/actresses?volumes=${encodeURIComponent(ARCHIVE_POOL_VOLUMES)}`);
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
  document.getElementById('detail-companies').innerHTML = '';
  document.getElementById('detail-aliases').innerHTML = '';
  ensureSentinel();
  setStatus('loading');

  // Build breadcrumbs — include prefix/tier/archive context if we came from a browse grid
  let crumbs;
  if (selectedArchive) {
    const arc = selectedArchive;
    crumbs = [
      { label: 'Archives', action: () => archivesBtn.click() },
      { label: arc, action: () => selectArchive(arc) },
    ];
  } else if (sourceMode === 'titles' && sourceHomeTab === 'random-actresses') {
    // Came from home random-actresses tab — HOME crumb restores it via showTitlesView()
    crumbs = [];
  } else {
    crumbs = [{ label: 'Actresses', action: () => actressesBtn.click() }];
    if (selectedPrefix) {
      const p = selectedPrefix;
      crumbs.push({ label: p, action: () => reSelectPrefix(p) });
    } else if (selectedTier) {
      const t = selectedTier;
      crumbs.push({ label: t.toLowerCase(), action: () => reSelectTier(t) });
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
  const coverCol = document.getElementById('detail-cover');
  const covers = a.coverUrls || [];
  if (covers.length > 0) {
    const idx = Math.floor(Math.random() * covers.length);
    coverCol.innerHTML = `<img src="${esc(covers[idx])}" alt="${esc(a.canonicalName)}" loading="lazy">`;
  } else {
    coverCol.innerHTML = `<div class="detail-cover-placeholder">—</div>`;
  }

  const { first: firstName, last: lastName } = splitName(a.canonicalName);
  const pathsHtml = (a.folderPaths || []).length > 0
    ? `<div class="detail-paths">${a.folderPaths.map(p => `<div class="detail-path">${esc(p)}</div>`).join('')}</div>` : '';

  const aliases = a.aliases || [];
  let aliasHtml = '';
  if (a.primaryName) {
    // This actress identity is an alias — show who she's primarily known as
    const { first: pFirst, last: pLast } = splitName(a.primaryName);
    const pNameHtml = pLast ? `${esc(pFirst)} ${esc(pLast)}` : esc(pFirst);
    aliasHtml = `<div class="detail-alias-row">
      <span class="detail-alias-label">Primarily known as</span>
      <span class="primary-badge" data-actress-id="${a.primaryId || ''}">${pNameHtml}</span>
    </div>`;
  } else if (aliases.length > 0) {
    // This is the primary identity — show her aliases
    aliasHtml = `<div class="detail-alias-row">
      <span class="detail-alias-label">Also known as</span>
      ${aliases.map(al => `<span class="alias-badge">${esc(al)}</span>`).join('')}
    </div>`;
  }

  const stageNameHtml = a.stageName
    ? `<div class="detail-stage-name">(${esc(a.stageName)})</div>`
    : `<button class="btn-search-stage-name" id="btn-search-stage-name">Search for Stage Name</button>`;

  document.getElementById('detail-info').innerHTML = `
    <div class="detail-name">
      <span class="detail-first-name">${esc(firstName)}</span>
      ${lastName ? `<span class="detail-last-name">${esc(lastName)}</span>` : ''}
    </div>
    ${stageNameHtml}
    <div class="detail-meta-row">
      <span class="tier-badge tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>
      ${a.favorite ? '<div class="fav-dot"></div>' : ''}
    </div>
    ${aliasHtml}
    ${renderDateRange(a.firstAddedDate, a.lastAddedDate, 'detail-dates')}
    ${pathsHtml}
  `;

  const btn = document.getElementById('btn-search-stage-name');
  if (btn) {
    btn.addEventListener('click', () => searchStageName(a.id));
  }

  const companies = a.companies || [];
  const companiesEl = document.getElementById('detail-companies');
  if (companies.length > 0) {
    companiesEl.innerHTML =
      `<div class="detail-section-label">Companies</div>` +
      `<div class="detail-all-movies selected" id="detail-all-movies">ALL MOVIES</div>` +
      companies.map(c => `<div class="detail-company-item" data-company="${esc(c)}">${esc(c)}</div>`).join('');
    document.getElementById('detail-all-movies').addEventListener('click', () => setDetailCompanyFilter(null));
    companiesEl.querySelectorAll('.detail-company-item').forEach(el =>
      el.addEventListener('click', () => setDetailCompanyFilter(el.dataset.company))
    );
  } else {
    companiesEl.innerHTML = '';
  }

  // Aliases are now shown inline in the info section; clear the side column
  document.getElementById('detail-aliases').innerHTML = '';
}

function setDetailCompanyFilter(company) {
  detailCompanyFilter = company;
  const allMoviesEl = document.getElementById('detail-all-movies');
  if (allMoviesEl) allMoviesEl.classList.toggle('selected', company === null);
  document.querySelectorAll('.detail-company-item').forEach(el =>
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

// ── Initial load ──────────────────────────────────────────────────────────
showView('titles');
activateHomeTab('latest');
