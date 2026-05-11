/* ─────────────────────────────────────────────────────────────────────
   Titles v2 — Pool modes (Collections, Unsorted, Archives)
   Shared browse-filter bar: company select + company marquee,
   tags button + mode-scoped tag panel, cols slider.
   Pool entry resolves /api/queues/volumes for volumeId + smbPath.
   ───────────────────────────────────────────────────────────────────── */

const BROWSE_FILTER_DEBOUNCE_MS = 350;

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

// ── Queue/volume data ─────────────────────────────────────────────────────
export async function ensureQueuesVolumes(state) {
  if (state.queuesVolumeData) return state.queuesVolumeData;
  const res = await fetch('/api/queues/volumes');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  state.queuesVolumeData = await res.json();
  return state.queuesVolumeData;
}

// ── Pool entry points ─────────────────────────────────────────────────────
export async function enterUnsortedMode(state, selectMode) {
  if (!state.poolVolumeId) {
    try {
      const data = await ensureQueuesVolumes(state);
      if (!data.sortPool) { console.warn('[titles/pool] No sort pool available'); return; }
      state.poolVolumeId = data.sortPool.id;
      state.poolSmbPath  = data.sortPool.smbPath || null;
    } catch (err) { console.error('[titles/pool] Failed to load pool info', err); return; }
  }
  selectMode('unsorted');
}

export async function enterArchiveMode(state, selectMode) {
  if (!state.archivePoolVolumeId) {
    try {
      const data = await ensureQueuesVolumes(state);
      if (!data.classicPool) { console.warn('[titles/pool] No classic pool available'); return; }
      state.archivePoolVolumeId = data.classicPool.id;
      state.archivePoolSmbPath  = data.classicPool.smbPath || null;
    } catch (err) { console.error('[titles/pool] Failed to load archive pool info', err); return; }
  }
  selectMode('archive-pool');
}

// ── Company marquee ───────────────────────────────────────────────────────
async function ensureTitleLabels(state) {
  if (state._labelCache) return state._labelCache;
  try {
    const res = await fetch('/api/titles/labels');
    state._labelCache = res.ok ? await res.json() : [];
  } catch (_) { state._labelCache = []; }
  return state._labelCache;
}

async function updateCompanyMarquee(marqueeEl, companyName, state) {
  if (!companyName) { marqueeEl.style.display = 'none'; return; }
  const labels = await ensureTitleLabels(state);
  const co = labels.find(l => l.company === companyName &&
    (l.companyDescription || l.companySpecialty || l.companyFounded || l.companyStatus));
  if (!co) { marqueeEl.style.display = 'none'; return; }
  const parts = [];
  if (co.companyDescription) parts.push(co.companyDescription);
  const meta = [];
  if (co.companyFounded)   meta.push(`Founded ${co.companyFounded}`);
  if (co.companyStatus)    meta.push(co.companyStatus);
  if (co.companyParent)    meta.push(`Parent: ${co.companyParent}`);
  if (co.companySpecialty) meta.push(`Specialty: ${co.companySpecialty}`);
  if (meta.length) parts.push(meta.join(' · '));
  const text = parts.join('  ·  ');
  if (!text) { marqueeEl.style.display = 'none'; return; }
  const inner = marqueeEl.querySelector('.tit-pool-marquee-inner');
  if (!inner) return;
  inner.textContent = text;
  const duration = Math.max(10, Math.round((500 + text.length * 7) / 120));
  inner.style.animation = 'none';
  void inner.getBoundingClientRect();
  inner.style.animation = `tit-pool-marquee-scroll ${duration}s linear infinite`;
  marqueeEl.style.display = '';
}

// ── Tags button state ──────────────────────────────────────────────────────
function updateTagsBtn(filterBarEl, state) {
  const countEl = filterBarEl.querySelector('#tit-pool-tags-count');
  const btn = filterBarEl.querySelector('#tit-pool-tags-btn');
  if (!countEl || !btn) return;
  if (state.browseActiveTags.size > 0) {
    countEl.textContent = state.browseActiveTags.size;
    countEl.style.display = '';
  } else {
    countEl.style.display = 'none';
  }
  btn.classList.toggle('has-active', state.browseActiveTags.size > 0);
}

// ── Debounced filtered query ──────────────────────────────────────────────
export function scheduleBrowseQuery(filterBarEl, state, resetAndLoad) {
  updateTagsBtn(filterBarEl, state);
  if (state.browseFilterTimer) clearTimeout(state.browseFilterTimer);
  state.browseFilterTimer = setTimeout(() => {
    state.browseFilterTimer = null;
    resetAndLoad();
  }, BROWSE_FILTER_DEBOUNCE_MS);
}

// ── Tags panel ────────────────────────────────────────────────────────────
function renderTagsPanel(panelEl, state, filterBarEl, resetAndLoad) {
  const tags = state.browseCatalogTags || [];
  if (tags.length === 0) {
    panelEl.innerHTML = '<div class="tit-pool-tags-loading">No tags available</div>';
    return;
  }
  panelEl.innerHTML = `<div class="tit-pool-tags-inner">
    ${tags.map(t => `<button type="button" class="chip${state.browseActiveTags.has(t) ? ' on' : ''}" data-tag="${esc(t)}">${esc(t)}</button>`).join('')}
  </div>`;
  panelEl.querySelectorAll('.chip[data-tag]').forEach(btn => {
    btn.addEventListener('click', () => {
      const tag = btn.dataset.tag;
      if (state.browseActiveTags.has(tag)) { state.browseActiveTags.delete(tag); btn.classList.remove('on'); }
      else                                  { state.browseActiveTags.add(tag);    btn.classList.add('on'); }
      scheduleBrowseQuery(filterBarEl, state, resetAndLoad);
    });
  });
}

async function toggleTagsPanel(panelEl, filterBarEl, state, resetAndLoad) {
  if (panelEl.style.display !== 'none') { panelEl.style.display = 'none'; return; }

  if (!state.browseCatalogTags || state.browseTagsForMode !== state.mode) {
    panelEl.innerHTML = '<div class="tit-pool-tags-loading">Loading tags…</div>';
    panelEl.style.display = '';
    const tagsUrl = state.mode === 'collections'
      ? '/api/collections/tags'
      : `/api/pool/${encodeURIComponent(state.mode === 'unsorted' ? state.poolVolumeId : state.archivePoolVolumeId)}/tags`;
    try {
      const res = await fetch(tagsUrl);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      state.browseCatalogTags = await res.json();
      state.browseTagsForMode = state.mode;
    } catch (_) {
      panelEl.innerHTML = '<div class="tit-pool-tags-loading">Could not load tags</div>';
      return;
    }
  }

  renderTagsPanel(panelEl, state, filterBarEl, resetAndLoad);
  panelEl.style.display = '';
}

// ── Cols slider HTML ──────────────────────────────────────────────────────
const COLS_DEFAULT = 5;
function colsSliderHtml(current) {
  return `<span class="tit-cols-ctrl">
    <span class="tit-cols-caption">Cols</span>
    <input type="range" class="tit-cols-slider" id="tit-cols-slider" min="2" max="10" step="1" value="${current}">
    <span class="tit-cols-label" id="tit-cols-label">${current}</span>
  </span>`;
}

// ── Show browse filter bar ────────────────────────────────────────────────
export async function showBrowseFilterBar(filterBarEl, tagsPanel, state, onColsChange, resetAndLoad) {
  if (!state.allCompanies) {
    try {
      const res = await fetch('/api/companies');
      state.allCompanies = res.ok ? await res.json() : [];
    } catch (_) { state.allCompanies = []; }
  }

  const colsNow = state._cols || COLS_DEFAULT;

  filterBarEl.innerHTML = `
    <select class="tit-lib-select" id="tit-pool-company">
      <option value="">All Companies</option>
      ${state.allCompanies.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join('')}
    </select>
    <div class="tit-pool-marquee" id="tit-pool-marquee" style="display:none"><span class="tit-pool-marquee-inner"></span></div>
    <button type="button" id="tit-pool-tags-btn" class="btn sm">
      Tags<span class="badge" id="tit-pool-tags-count" style="display:none"></span>
    </button>
    ${colsSliderHtml(colsNow)}`;
  filterBarEl.style.display = '';

  const sel = filterBarEl.querySelector('#tit-pool-company');
  if (sel && state.browseCompanyFilter) {
    sel.value = state.browseCompanyFilter;
    const marqueeEl = filterBarEl.querySelector('#tit-pool-marquee');
    if (marqueeEl) updateCompanyMarquee(marqueeEl, state.browseCompanyFilter, state);
  }
  updateTagsBtn(filterBarEl, state);

  sel.addEventListener('change', e => {
    state.browseCompanyFilter = e.target.value || null;
    const marqueeEl = filterBarEl.querySelector('#tit-pool-marquee');
    if (marqueeEl) updateCompanyMarquee(marqueeEl, state.browseCompanyFilter, state);
    scheduleBrowseQuery(filterBarEl, state, resetAndLoad);
  });

  filterBarEl.querySelector('#tit-pool-tags-btn').addEventListener('click', () => {
    toggleTagsPanel(tagsPanel, filterBarEl, state, resetAndLoad);
  });

  // ── Cols slider ──
  const slider = filterBarEl.querySelector('#tit-cols-slider');
  const colsLabel = filterBarEl.querySelector('#tit-cols-label');
  if (slider && colsLabel) {
    slider.addEventListener('input', () => {
      const cols = parseInt(slider.value, 10);
      colsLabel.textContent = String(cols);
      state._cols = cols;
      onColsChange(cols);
    });
  }
}

// ── Hide browse filter bar ────────────────────────────────────────────────
export function hideBrowseFilterBar(filterBarEl, tagsPanel) {
  if (filterBarEl) { filterBarEl.innerHTML = ''; filterBarEl.style.display = 'none'; }
  if (tagsPanel)   { tagsPanel.innerHTML = ''; tagsPanel.style.display = 'none'; }
}

// ── Reset browse filters ──────────────────────────────────────────────────
export function resetBrowseFilters(state) {
  state.browseCompanyFilter = null;
  state.browseActiveTags    = new Set();
  state.browseCatalogTags   = null;
  state.browseTagsForMode   = null;
  if (state.browseFilterTimer) { clearTimeout(state.browseFilterTimer); state.browseFilterTimer = null; }
}
