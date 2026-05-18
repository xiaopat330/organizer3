// v2/discovery/enrich.js — Enrich subtab (actress-driven enrichment flow).
//
// Direct port from modules/utilities-javdb-discovery/enrich.js.
// Titles and Profile subview rendering is now delegated to enrich-panels.js
// (the shared module also used by the Actress Detail page Enrichment tab).

import { esc } from '../../utils.js';
import {
  formatRelative,
  parseCast,
  showJdCoverModal,
} from './shared.js';
import { mountTitlesPanel, mountProfilePanel } from './enrich-panels.js';

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
  slug_conflict:             'Slug conflict',
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
  // Handle returned by mountTitlesPanel; reset on actress switch so the
  // panel mounts fresh for the new actress.
  let titlesHandle = null;
  // Computed once per data load.
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
    // Force a fresh panel mount for the new actress.
    if (titlesView) titlesView.innerHTML = '';
    titlesHandle = null;
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
    titlesView.style.display      = '';
    titlesActionBar.style.display = '';
    profileView.style.display     = 'none';
    conflictsView.style.display   = 'none';
    errorsView.style.display      = 'none';
    // Mount once per actress selection; subsequent subtab switches only toggle
    // display.  titlesHandle is cleared in selectActress() on actress change.
    if (!titlesHandle) {
      titlesHandle = mountTitlesPanel(titlesView, {
        actressId: state.selectedId,
        showActionBar: false,
        hooks: {
          refreshQueue:  hooks.refreshQueue,
          loadActresses: hooks.loadActresses,
          switchToProfile: () => {
            subtabBtns.forEach(b => b.classList.toggle('selected', b.dataset.tab === 'profile'));
            state.activeTab = 'profile';
            renderActiveTab();
          },
        },
      });
    }
  }

  async function renderTitlesTabSilent() {
    if (titlesView.style.display === 'none') return;
    if (titlesHandle) {
      // Panel already mounted for this actress — just refresh data.
      titlesHandle.refresh();
    } else {
      // Actress was just selected and titles tab is visible; mount fresh.
      titlesHandle = mountTitlesPanel(titlesView, {
        actressId: state.selectedId,
        showActionBar: false,
        hooks: {
          refreshQueue:  hooks.refreshQueue,
          loadActresses: hooks.loadActresses,
        },
      });
    }
  }

  // ── Profile tab ──────────────────────────────────────────────────────────

  async function renderProfileTab() {
    profileView.style.display     = '';
    titlesView.style.display      = 'none';
    titlesActionBar.style.display = 'none';
    conflictsView.style.display   = 'none';
    errorsView.style.display      = 'none';
    mountProfilePanel(profileView, {
      actressId: state.selectedId,
      hooks: { refreshQueue: hooks.refreshQueue },
    });
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

    const aiBanner = buildAiSuggestionBanner(job, panelEl, detail, li, pickerLi);
    if (aiBanner) panelEl.appendChild(aiBanner);

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

  function buildAiSuggestionBanner(job, panelEl, detail, li, pickerLi) {
    const conf = job.aiSuggestionConfidence;
    const at = job.aiSuggestionAt;
    // Pending state — no row at all yet.
    if (!at && !conf) {
      const banner = document.createElement('div');
      banner.className = 'er-picker-ai-banner er-picker-ai-banner-pending';
      const text = document.createElement('span');
      text.className = 'er-picker-ai-banner-text';
      text.textContent = 'AI assist pending';
      banner.appendChild(text);

      const refresh = document.createElement('button');
      refresh.type = 'button';
      refresh.className = 'er-picker-ai-refresh';
      refresh.textContent = 'Refresh';
      refresh.addEventListener('click', async () => {
        refresh.disabled = true;
        try {
          const res = await fetch(`/api/utilities/review-queue/${job.reviewQueueId}/ai-suggestion`);
          if (!res.ok) { refresh.disabled = false; return; }
          const data = await res.json();
          if (data && data.at) {
            job.aiSuggestionSlug = data.slug;
            job.aiSuggestionConfidence = data.confidence;
            job.aiSuggestionReason = data.reason;
            job.aiSuggestionAt = data.at;
            // Re-render the picker content in place.
            renderErrorPickerContent(panelEl, job, detail, li, pickerLi);
          } else {
            refresh.disabled = false;
          }
        } catch (err) {
          console.error('AI suggestion refresh failed', err);
          refresh.disabled = false;
        }
      });
      banner.appendChild(refresh);
      banner.appendChild(buildAiDismissBtn(banner));
      return banner;
    }
    // Suggestion is "error" outcome — render nothing.
    if (conf === 'error') return null;
    // Anything else requires a confidence value.
    if (!conf) return null;

    let modifier;
    let textContent;
    const slug = job.aiSuggestionSlug;
    const reason = job.aiSuggestionReason || '';
    switch (conf) {
      case 'agreed':
        modifier = 'er-picker-ai-banner-agreed';
        textContent = `AI suggests: ${slug} (both models agreed) — ${reason}`;
        break;
      case 'phi4_only':
        modifier = 'er-picker-ai-banner-single';
        textContent = `AI suggests: ${slug} (phi4 only) — ${reason}`;
        break;
      case 'gemma_only':
        modifier = 'er-picker-ai-banner-single';
        textContent = `AI suggests: ${slug} (gemma only) — ${reason}`;
        break;
      case 'conflict':
        modifier = 'er-picker-ai-banner-neutral';
        textContent = `AI couldn't pick — phi4 and gemma3 disagreed`;
        break;
      case 'both_abstain':
        modifier = 'er-picker-ai-banner-neutral';
        textContent = `AI abstained — both models couldn't pick`;
        break;
      default:
        return null;
    }

    const banner = document.createElement('div');
    banner.className = `er-picker-ai-banner ${modifier}`;
    const text = document.createElement('span');
    text.className = 'er-picker-ai-banner-text';
    text.textContent = textContent;
    banner.appendChild(text);
    banner.appendChild(buildAiDismissBtn(banner));
    return banner;
  }

  function buildAiDismissBtn(banner) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'er-picker-ai-dismiss';
    btn.setAttribute('aria-label', 'Dismiss AI suggestion');
    btn.textContent = '×';
    btn.addEventListener('click', () => { banner.style.display = 'none'; });
    return btn;
  }

  function buildErrorCandidateCard(job, candidate, linkedSlugs, li, pickerLi) {
    const card = document.createElement('div');
    card.className = 'er-candidate-card';
    const isAiPick = job.aiSuggestionSlug
        && candidate.slug === job.aiSuggestionSlug
        && (job.aiSuggestionConfidence === 'agreed'
            || job.aiSuggestionConfidence === 'phi4_only'
            || job.aiSuggestionConfidence === 'gemma_only');
    if (isAiPick) {
      card.classList.add('er-candidate-card-ai-pick');
      const pill = document.createElement('span');
      pill.className = 'er-ai-pick-pill';
      pill.textContent = job.aiSuggestionConfidence === 'agreed' ? 'AI pick ✓' : 'AI pick';
      if (job.aiSuggestionReason) pill.setAttribute('title', job.aiSuggestionReason);
      card.appendChild(pill);
    }

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

  // ── Header actions ───────────────────────────────────────────────────────

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
