// ── App name + config ─────────────────────────────────────────────────────
let MAX_TOTAL = 500;
fetch('/api/config')
  .then(r => r.json())
  .then(cfg => {
    const name = cfg.appName || 'organizer3';
    document.getElementById('app-name').textContent = name.toLowerCase();
    document.title = name;
    if (cfg.maxBrowseTitles) MAX_TOTAL = cfg.maxBrowseTitles;
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

// ── View management ───────────────────────────────────────────────────────
// Each key maps to the IDs that should be visible in that view.
const VIEWS = {
  titles:           ['grid'],
  actresses:        ['actress-grid'],
  'actress-detail': ['actress-detail'],
  queue:            ['queue-header', 'queue-grid'],
};
const ALL_PANEL_IDS = Object.values(VIEWS).flat();
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

  const dateHtml     = t.addedDate ? `<div class="added-date">${esc(t.addedDate)}</div>` : '';
  const locationHtml = t.location  ? `<div class="title-location">${esc(t.location)}</div>` : '';

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
    if (queueSmbPath && t.location) t.location = queueSmbPath + t.location;
    return makeTitleCard(t);
  },
  'no titles in queue',
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

// ── Title browse ──────────────────────────────────────────────────────────
function showTitlesView() {
  showView('titles');
  activeGrid = titlesGrid;
  actressesBtn.classList.remove('active');
  closeQueuesDropdown();
  selectedPrefix = null;
  selectedTier   = null;
  prefixDropdown.querySelectorAll('.prefix-chip').forEach(c => c.classList.remove('selected'));
}

// ── Actress browse ────────────────────────────────────────────────────────
const actressesBtn   = document.getElementById('actresses-btn');
const prefixDropdown = document.getElementById('prefix-dropdown');
let selectedPrefix = null;
let selectedTier   = null;

actressesBtn.addEventListener('click', async e => {
  e.stopPropagation();
  closeQueuesDropdown();
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
  selectedPrefix = prefix;
  selectedTier   = null;
  closeDropdown();
  actressesBtn.classList.add('active');
  clearDropdownSelection();
  chip.classList.add('selected');
  await loadActressGrid(`/api/actresses?prefix=${encodeURIComponent(prefix)}`);
}

async function selectTier(tier, chip) {
  selectedTier   = tier;
  selectedPrefix = null;
  closeDropdown();
  actressesBtn.classList.add('active');
  clearDropdownSelection();
  chip.classList.add('selected');
  await loadActressGrid(`/api/actresses?tier=${encodeURIComponent(tier)}`);
}

// ── Actress detail ────────────────────────────────────────────────────────
async function openActressDetail(actressId) {
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

  try {
    const res = await fetch(`/api/actresses/${actressId}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    renderDetailPanel(await res.json());
  } catch (err) {
    setStatus('error loading actress');
    console.error(err);
    return;
  }

  await actressDetailGrid.loadMore();
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

  document.getElementById('detail-info').innerHTML = `
    <div class="detail-name">
      <span class="detail-first-name">${esc(firstName)}</span>
      ${lastName ? `<span class="detail-last-name">${esc(lastName)}</span>` : ''}
    </div>
    <div class="detail-meta-row">
      <span class="tier-badge tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>
      ${a.favorite ? '<div class="fav-dot"></div>' : ''}
    </div>
    ${renderDateRange(a.firstAddedDate, a.lastAddedDate, 'detail-dates')}
    ${pathsHtml}
  `;

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

  const aliases = a.aliases || [];
  document.getElementById('detail-aliases').innerHTML = aliases.length > 0
    ? `<div class="detail-section-label">Aliases</div>` +
      aliases.map(al => `<div class="detail-alias-item">${esc(al)}</div>`).join('')
    : '';
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
  if (data.pool) {
    const chip = document.createElement('div');
    chip.className = 'prefix-chip';
    chip.textContent = data.pool.id;
    chip.addEventListener('click', () => openQueueView(data.pool.id, data.pool.smbPath));
    poolCol.appendChild(chip);
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
  activeGrid = queueGrid;
  queueGrid.reset();
  ensureSentinel();
  await queueGrid.loadMore();
}

// ── Initial load ──────────────────────────────────────────────────────────
activeGrid = titlesGrid;
titlesGrid.loadMore();
