// ── Shared studio data + panel builder ───────────────────────────────────
// Used by both actress-browse.js and title-browse.js.

// ── Studio group cache ────────────────────────────────────────────────────
let studioGroupsCache = null;
let studioGroupsCachePromise = null;

export async function ensureStudioGroups() {
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

// ── Label catalog cache ───────────────────────────────────────────────────
let titleLabelCache = null;
let titleLabelCachePromise = null;

export async function ensureTitleLabels() {
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

// ── Shared two-column studio panel builder ────────────────────────────────
// containerEl — the flex wrapper element to populate
// detailId    — the id to give the right-panel detail div
// byCompany   — Map<company, labels[]>
// onSelect    — callback(company, byCompany) called on click and for the initial auto-select
// afterRender — called after the DOM is built (used to hide the sibling grid)
export function renderTwoColumnStudioPanel(containerEl, detailId, byCompany, onSelect, afterRender) {
  containerEl.innerHTML = '';

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
    item.addEventListener('click', () => onSelect(company, byCompany));
    listEl.appendChild(item);
  });

  const detailEl = document.createElement('div');
  detailEl.className = 'studio-label-detail';
  detailEl.id = detailId;

  containerEl.appendChild(listEl);
  containerEl.appendChild(detailEl);
  containerEl.style.display = 'flex';
  afterRender();

  if (firstCompany) onSelect(firstCompany, byCompany);
}
