// Enrich subtab (the original actress-driven enrichment flow):
//  - sidebar: alpha bar, filter chips, sort, actress list
//  - actress panel: titles / profile / conflicts / errors sub-views
//  - per-title and per-actress action handlers
//
// state.activeTab here means the actress-panel sub-tab (titles/profile/conflicts/errors),
// distinct from the top-level subtab dispatched by index.js.

import { esc } from '../utils.js';
import {
  formatRelative,
  fmtDate,
  parseCast,
  showJdCoverModal,
} from './shared.js';

const BUCKET_THRESHOLD = 30;

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

// Mirror of queue.js QUEUE_FAIL_META used by titles-tab status rendering. Kept
// duplicated rather than imported since enrich uses only a small subset and
// duplicating these label/icon/cls rows is cheaper than another shared.js entry.
const QUEUE_FAIL_META_INLINE = {
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

function errorReasonLabel(raw) {
  return ERROR_REASON_LABELS[raw] || raw || '(unknown)';
}

function computeTier(totalTitles) {
  if (totalTitles >= 100) return 'goddess';
  if (totalTitles >= 50)  return 'superstar';
  if (totalTitles >= 20)  return 'popular';
  return null;
}

function splitLetter(letter, sortedNames) {
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

export function initEnrich(state, hooks) {
  // DOM refs (eager — module is dynamic-imported after the view is in the DOM).
  const alphaBar          = document.getElementById('jd-alpha-bar');
  const filterBar         = document.getElementById('jd-filter-bar');
  const sortBar           = document.getElementById('jd-sort-bar');
  const actressList       = document.getElementById('jd-actress-list');
  const emptyMsg          = document.getElementById('jd-empty');
  const panel             = document.getElementById('jd-actress-panel');
  const enrichBtn         = document.getElementById('jd-enrich-btn');
  const cancelActressBtn  = document.getElementById('jd-cancel-actress-btn');
  const titlesActionBar   = document.getElementById('jd-titles-action-bar');
  const subtabBtns        = panel?.querySelectorAll('.jd-subtab') ?? [];
  const titlesView        = document.getElementById('jd-subview-titles');
  const profileView       = document.getElementById('jd-subview-profile');
  const conflictsView     = document.getElementById('jd-subview-conflicts');
  const errorsView        = document.getElementById('jd-subview-errors');
  const enrichModalOverlay = document.getElementById('jd-enrich-modal-overlay');
  const enrichModalBody    = document.getElementById('jd-enrich-modal-body');
  const enrichModalHeading = document.getElementById('jd-enrich-modal-heading');
  const enrichModalClose   = document.getElementById('jd-enrich-modal-close');

  // Computed once per data load; each entry: { label, key, test(canonicalName) }.
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

    if (!alphaBuckets.some(b => b.key === state.alphaFilter)) {
      state.alphaFilter = 'All';
    }
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

  function highlightSelected(id) {
    actressList.querySelectorAll('.jd-actress-item').forEach(li => {
      li.classList.toggle('selected', Number(li.dataset.id) === id);
    });
  }

  async function selectActress(id) {
    state.selectedId = id;
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

  async function renderTitlesTab() {
    titlesView.style.display    = '';
    titlesActionBar.style.display = '';
    profileView.style.display   = 'none';
    conflictsView.style.display = 'none';
    errorsView.style.display    = 'none';
    titlesView.innerHTML = '<div class="jd-loading">Loading…</div>';
    await fetchAndRenderTitles();
  }

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
      wireFailureBadges();
      wireCoverLinks(titlesView);
    } catch (_) {
      titlesView.innerHTML = '<div class="jd-error">Network error.</div>';
    }
  }

  function filterBarHtml(facets, matchCount) {
    const f = state.titleFilter;
    const selectedTagsSet = new Set(f.tags);
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

  function wireCoverLinks(container) {
    container.querySelectorAll('.jd-cover-link[data-cover-url]').forEach(btn => {
      btn.addEventListener('click', () => showJdCoverModal(btn.dataset.coverUrl, btn.dataset.code));
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

  function wireFailureBadges() {
    titlesView.querySelectorAll('.jd-titles-review-link[data-review-id]').forEach(btn => {
      btn.addEventListener('click', () => {
        document.dispatchEvent(new CustomEvent('navigate-to-review-item', {
          detail: { reviewQueueId: parseInt(btn.dataset.reviewId, 10) }
        }));
      });
    });
    titlesView.querySelectorAll('.jd-titles-profile-link').forEach(btn => {
      btn.addEventListener('click', () => {
        document.dispatchEvent(new CustomEvent('navigate-to-discovery-actress-profile', {
          detail: { actressId: state.selectedId }
        }));
      });
    });
  }

  // ── Enrichment detail modal ──────────────────────────────────────────────

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
    if (t.queueStatus === 'failed') {
      const meta  = QUEUE_FAIL_META_INLINE[t.lastError];
      const icon  = meta?.icon  ?? '✗';
      const label = meta?.label ?? (t.lastError ? t.lastError.replace(/_/g, ' ') : 'failed');
      const cls   = meta?.cls   ?? 'jd-qi-failed';
      return { key: 'failed', label: `${icon} ${label}`, cls, lastError: t.lastError, reviewQueueId: t.reviewQueueId };
    }
    if (t.status === 'slug_only')      return { key: 'slug_only', label: '⌁ Slug Only' };
    if (t.queueStatus === 'done')      return { key: 'done',      label: '✓ Done' };
    return { key: 'none', label: '— Not Started' };
  }

  function titleRow(t) {
    const st = titleEffectiveStatus(t);
    const { key, label } = st;
    const isEnriched = key === 'fetched';

    let statusCell;
    if (isEnriched) {
      statusCell = `<span class="jd-status jd-status-${key} jd-status-clickable" data-title-id="${t.titleId}" title="View enrichment details">${label}</span>`;
    } else if (key === 'failed') {
      const cls     = st.cls;
      const tooltip = esc(st.lastError || '');
      if (st.reviewQueueId != null) {
        statusCell = `<button class="jd-status ${cls} jd-titles-review-link" data-review-id="${st.reviewQueueId}" title="${tooltip}">${label}</button>`;
      } else if (st.lastError === 'no_slug') {
        statusCell = `<button class="jd-status ${cls} jd-titles-profile-link" title="${tooltip}">${label}</button>`;
      } else {
        statusCell = `<span class="jd-status ${cls}" title="${tooltip}">${label}</span>`;
      }
    } else {
      statusCell = `<span class="jd-status jd-status-${key}">${label}</span>`;
    }

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
    const codeCell = t.localCoverUrl
      ? `<button class="jd-cover-link" data-cover-url="${esc(t.localCoverUrl)}" data-code="${esc(t.code)}">${esc(t.code)}</button>`
      : esc(t.code);
    return `<tr>
      <td class="jd-code">${codeCell}</td>
      <td>${statusCell}</td>
      <td>${t.titleOriginal ? esc(t.titleOriginal) : '—'}</td>
      <td>${fmtDate(t.releaseDate)}</td>
      <td>${t.maker ? esc(t.maker) : '—'}</td>
      <td class="jd-rating-cell">${rating}</td>
      <td class="jd-action-cell">${infoBtn}${reenrichBtn}</td>
    </tr>`;
  }

  // ── Profile tab ──────────────────────────────────────────────────────────

  async function renderProfileTab() {
    profileView.style.display   = '';
    titlesView.style.display    = 'none';
    titlesActionBar.style.display = 'none';
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

  // ── Conflicts tab ────────────────────────────────────────────────────────

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

  async function renderConflictsTab() {
    conflictsView.style.display = '';
    titlesView.style.display    = 'none';
    titlesActionBar.style.display = 'none';
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

  // ── Errors tab ───────────────────────────────────────────────────────────

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
            await Promise.all([hooks.refreshQueue(), renderErrorsTab()]);
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
    const panelEl = document.createElement('div');
    panelEl.className = 'er-picker-panel';
    pickerLi.appendChild(panelEl);
    li.insertAdjacentElement('afterend', pickerLi);

    let detail = null;
    try { detail = job.reviewDetail ? JSON.parse(job.reviewDetail) : null; } catch {}
    if (!detail || !detail.candidates || detail.candidates.length === 0) {
      renderErrorPickerMissing(panelEl, job, li, pickerLi);
    } else {
      renderErrorPickerContent(panelEl, job, detail, li, pickerLi);
    }
  }

  function renderErrorPickerMissing(panelEl, job, li, pickerLi) {
    panelEl.innerHTML = `
      <div class="er-picker-missing">
        <span>Candidates not yet loaded.</span>
        <button type="button" class="er-picker-load-btn">Load candidates</button>
      </div>
    `;
    panelEl.querySelector('.er-picker-load-btn').addEventListener('click', async () => {
      await doErrorRefreshCandidates(panelEl, job, li, pickerLi);
    });
  }

  function renderErrorPickerContent(panelEl, job, detail, li, pickerLi) {
    const linkedSlugs = new Set(detail.linked_slugs || []);
    const age = formatRelative(detail.fetched_at);
    panelEl.innerHTML = '';

    const header = document.createElement('div');
    header.className = 'er-picker-header';
    header.innerHTML = `
      <span class="er-picker-age">Candidates fetched ${esc(age)}</span>
      <button type="button" class="er-picker-refresh-btn">Refresh candidates</button>
    `;
    panelEl.appendChild(header);
    header.querySelector('.er-picker-refresh-btn').addEventListener('click', async () => {
      await doErrorRefreshCandidates(panelEl, job, li, pickerLi);
    });

    const cards = document.createElement('div');
    cards.className = 'er-candidate-cards';
    if (job.coverUrl) {
      cards.appendChild(buildErrorReferenceCard(job.coverUrl));
    }
    detail.candidates.forEach(c => {
      cards.appendChild(buildErrorCandidateCard(job, c, linkedSlugs, li, pickerLi));
    });
    panelEl.appendChild(cards);

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
    panelEl.appendChild(footer);
  }

  function buildErrorReferenceCard(coverUrl) {
    const card = document.createElement('div');
    card.className = 'er-candidate-card er-reference-card';

    const cover = document.createElement('div');
    cover.className = 'er-candidate-cover';
    const img = document.createElement('img');
    img.src = coverUrl;
    img.alt = '';
    img.loading = 'lazy';
    img.className = 'er-candidate-img';
    cover.appendChild(img);
    cover.addEventListener('click', () => showJdCoverModal(coverUrl, ''));
    cover.style.cursor = 'zoom-in';
    card.appendChild(cover);

    const info = document.createElement('div');
    info.className = 'er-candidate-info';
    const titleEl = document.createElement('div');
    titleEl.className = 'er-candidate-title';
    titleEl.textContent = 'Local cover';
    info.appendChild(titleEl);
    const meta = document.createElement('div');
    meta.className = 'er-candidate-meta';
    meta.textContent = 'Match candidates against this';
    info.appendChild(meta);
    card.appendChild(info);

    return card;
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
          const actsEl = li.querySelector('.jd-error-row-actions');
          if (actsEl) {
            actsEl.innerHTML = '';
            const pill = document.createElement('span');
            pill.className = 'jd-error-resolved-pill';
            pill.textContent = '✓ Submitted — re-enriching…';
            actsEl.appendChild(pill);
          }
          li.style.opacity = '0.5';
          await hooks.refreshQueue();
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

  async function doErrorRefreshCandidates(panelEl, job, li, pickerLi) {
    const btn = panelEl.querySelector('.er-picker-refresh-btn, .er-picker-load-btn');
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
      panelEl.innerHTML = '';
      if (!freshDetail || !freshDetail.candidates || freshDetail.candidates.length === 0) {
        renderErrorPickerMissing(panelEl, job, li, pickerLi);
      } else {
        renderErrorPickerContent(panelEl, job, freshDetail, li, pickerLi);
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

  async function renderErrorsTab() {
    errorsView.style.display    = '';
    titlesView.style.display    = 'none';
    titlesActionBar.style.display = 'none';
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

  // ── M3 actions (header buttons + actress-panel actions) ──────────────────

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
        await Promise.all([hooks.loadActresses(), hooks.refreshQueue()]);
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
    await hooks.refreshQueue();
  }

  async function retryActress() {
    if (state.selectedId === null) return;
    await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/retry`, { method: 'POST' });
    await Promise.all([hooks.refreshQueue(), renderErrorsTab()]);
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

  async function navigateToActress(actressId) {
    hooks.switchTopTab('enrich');
    resetFiltersToAll();
    await selectActress(actressId);
    const li = actressList.querySelector(`.jd-actress-item[data-id="${actressId}"]`);
    if (li) li.scrollIntoView({ block: 'nearest' });
  }

  async function navigateToActressProfile(actressId) {
    await navigateToActress(actressId);
    subtabBtns.forEach(b => b.classList.toggle('selected', b.dataset.tab === 'profile'));
    state.activeTab = 'profile';
    await renderActiveTab();
  }

  // ── Wiring ───────────────────────────────────────────────────────────────

  enrichModalClose.addEventListener('click', closeEnrichmentModal);
  enrichModalOverlay.addEventListener('click', e => {
    if (e.target === enrichModalOverlay) closeEnrichmentModal();
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && enrichModalOverlay.style.display !== 'none') closeEnrichmentModal();
  });

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

  return {
    computeAlphaBuckets,
    renderAlphaBar,
    renderFilterBar,
    renderSortBar,
    renderActressList,
    renderTitlesTabSilent,
    navigateToActress,
    navigateToActressProfile,
  };
}
