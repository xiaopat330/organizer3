// v2/discovery/enrich-panels.js — Shared Titles + Profile panel mounts.
//
// Extracted from enrich.js so the same rendering logic can be used in
// both the Discover page (actress-driven list) and the Actress Detail page
// (fixed actress, no sidebar).
//
// Public API:
//   mountTitlesPanel(containerEl, { actressId, hooks?, showActionBar? })
//   mountProfilePanel(containerEl, { actressId, hooks? })
//
// Each mount injects its own DOM structure into containerEl and wires all
// handlers.  There are no global document.getElementById() calls — every DOM
// lookup is scoped to containerEl or a scoped modal injected alongside it.
//
// hooks (all optional, default no-op):
//   refreshQueue()      — called after a successful re-enrich/enqueue action
//   loadActresses()     — called after a bulk enqueue
//   switchToProfile()   — called when a "no_slug" failure badge is clicked;
//                         default behaviour emits navigate-to-discovery-actress-profile
//
// showActionBar (default true): when false, the Enrich/Stop buttons are not
//   rendered.  Discover passes false because its action bar lives outside the
//   subview div and is managed directly by initEnrich.

import { esc } from '../../utils.js';
import { fmtDate, parseCast, showJdCoverModal } from './shared.js';

const QUEUE_FAIL_META = {
  ambiguous:               { label: 'ambiguous',           icon: '⚠', cls: 'jd-qi-failed-resolvable' },
  cast_anomaly:            { label: 'cast anomaly',        icon: '⚠', cls: 'jd-qi-failed-resolvable' },
  sentinel_actress:        { label: 'needs actress',       icon: '⚠', cls: 'jd-qi-failed-resolvable' },
  not_found:               { label: 'not on javdb',        icon: '⊘', cls: 'jd-qi-failed-deadend'    },
  no_match_in_filmography: { label: 'not in filmography',  icon: '⊘', cls: 'jd-qi-failed-deadend'    },
  title_not_in_db:         { label: 'orphaned job',        icon: '⊘', cls: 'jd-qi-failed-deadend'    },
  unknown_job_type:        { label: 'internal error',      icon: '⊘', cls: 'jd-qi-failed-deadend'    },
  fetch_failed:            { label: 'fetch failed',        icon: '↻', cls: 'jd-qi-failed'            },
  no_slug:                 { label: 'no slug',             icon: '↻', cls: 'jd-qi-failed'            },
  slug_conflict:           { label: 'slug conflict',       icon: '⚠', cls: 'jd-qi-failed-resolvable' },
};

// ── Titles panel ─────────────────────────────────────────────────────────────

export function mountTitlesPanel(containerEl, { actressId, hooks = {}, showActionBar = true }) {
  // Abort any prior document-level listener from a previous mount on this container.
  if (containerEl._epAbortCtrl) { containerEl._epAbortCtrl.abort(); }

  const noop = () => Promise.resolve();
  const refreshQueue  = hooks.refreshQueue  ?? noop;
  const loadActresses = hooks.loadActresses ?? noop;

  // Per-instance filter state.
  let titleFilter = { tags: [], minRatingAvg: null, minRatingCount: null };

  // Inject a scoped modal overlay sibling to containerEl (appended to its
  // parent so it can be full-screen without fighting containerEl's overflow).
  // A wrapper div groups modal + optional action bar with the titles content.
  const wrapperEl = document.createElement('div');
  wrapperEl.className = 'jd-ep-titles-wrapper';
  if (showActionBar) {
    wrapperEl.innerHTML = `
      <div class="jd-titles-action-bar jd-ep-action-bar">
        <button type="button" class="jd-ep-enrich-btn jd-action-btn jd-enrich-btn">▶ Enrich New Titles</button>
        <button type="button" class="jd-ep-cancel-btn jd-action-btn jd-muted-btn">⏹ Stop Enrichment</button>
      </div>`;
  }
  const titlesView = document.createElement('div');
  titlesView.className = 'jd-ep-titles-view jd-subview';
  wrapperEl.appendChild(titlesView);

  const modalOverlayEl = document.createElement('div');
  modalOverlayEl.className = 'jd-ep-modal-overlay jd-enrich-modal-overlay';
  modalOverlayEl.style.display = 'none';
  modalOverlayEl.innerHTML = `
    <div class="jd-enrich-modal">
      <div class="jd-enrich-modal-header">
        <div class="jd-ep-modal-heading jd-enrich-modal-heading"></div>
        <button type="button" class="jd-ep-modal-close jd-enrich-modal-close">×</button>
      </div>
      <div class="jd-ep-modal-body jd-enrich-modal-body"></div>
    </div>`;
  wrapperEl.appendChild(modalOverlayEl);

  containerEl.innerHTML = '';
  containerEl.appendChild(wrapperEl);

  const enrichBtn    = wrapperEl.querySelector('.jd-ep-enrich-btn');
  const cancelBtn    = wrapperEl.querySelector('.jd-ep-cancel-btn');
  const modalOverlay = modalOverlayEl;
  const modalHeading = modalOverlayEl.querySelector('.jd-ep-modal-heading');
  const modalBody    = modalOverlayEl.querySelector('.jd-ep-modal-body');
  const modalClose   = modalOverlayEl.querySelector('.jd-ep-modal-close');

  // ── Modal ───────────────────────────────────────────────────────────────

  function closeModal() {
    modalOverlay.style.display = 'none';
    modalBody.innerHTML = '';
  }

  async function openModal(titleId) {
    modalHeading.innerHTML = '<span class="jd-enrich-modal-code">Loading…</span>';
    modalBody.innerHTML = '<div class="jd-loading">Loading…</div>';
    modalOverlay.style.display = 'flex';
    try {
      const res = await fetch(`/api/javdb/discovery/titles/${titleId}/enrichment`);
      if (!res.ok) { modalBody.innerHTML = '<div class="jd-error">Failed to load enrichment data.</div>'; return; }
      renderModal(await res.json());
    } catch (_) {
      modalBody.innerHTML = '<div class="jd-error">Network error.</div>';
    }
  }

  function renderModal(d) {
    modalHeading.innerHTML = `
      <span class="jd-enrich-modal-code">${esc(d.code)}</span>
      ${d.titleOriginal ? `<span class="jd-enrich-modal-title">${esc(d.titleOriginal)}</span>` : ''}
    `;
    const metaRows = [];
    if (d.releaseDate)     metaRows.push(['Release', fmtDate(d.releaseDate)]);
    if (d.durationMinutes) metaRows.push(['Duration', `${d.durationMinutes} min`]);
    if (d.maker)           metaRows.push(['Maker', esc(d.maker)]);
    if (d.publisher && d.publisher !== d.maker) metaRows.push(['Publisher', esc(d.publisher)]);
    if (d.series)          metaRows.push(['Series', esc(d.series)]);
    if (d.ratingAvg != null) {
      const votes = d.ratingCount != null ? ` · ${d.ratingCount} votes` : '';
      metaRows.push(['Rating', `${d.ratingAvg.toFixed(2)} / 5${votes}`]);
    }
    const metaHtml = metaRows.length > 0
      ? `<div><div class="jd-enrich-section-label">Details</div><div class="jd-enrich-meta-grid">
          ${metaRows.map(([k, v]) => `<span class="jd-enrich-meta-label">${k}</span><span class="jd-enrich-meta-value">${v}</span>`).join('')}
         </div></div>` : '';
    const cast = parseCast(d.castJson);
    const castHtml = cast.length > 0
      ? `<div><div class="jd-enrich-section-label">Cast</div><div class="jd-enrich-cast-list">
          ${cast.map(e => `<span class="jd-enrich-cast-name">${esc(e.name)}</span>`).join('')}
         </div></div>` : '';
    const tagsHtml = d.tags && d.tags.length > 0
      ? `<div><div class="jd-enrich-section-label">Tags from javdb</div><div class="jd-enrich-tag-list">
          ${d.tags.map(t => `<span class="jd-enrich-tag">${esc(t)}</span>`).join('')}
         </div></div>` : '';
    const javdbUrl = d.javdbSlug ? `https://javdb.com/v/${esc(d.javdbSlug)}` : null;
    const footerHtml = `<div class="jd-enrich-footer">
      ${javdbUrl ? `<a class="jd-enrich-source-link" href="${javdbUrl}" target="_blank" rel="noopener">View on javdb ↗</a>` : '<span></span>'}
      ${d.fetchedAt ? `<span class="jd-enrich-fetched-at">Fetched ${fmtDate(d.fetchedAt.slice(0, 10))}</span>` : ''}
    </div>`;
    modalBody.innerHTML = metaHtml + castHtml + tagsHtml + footerHtml;
  }

  // AbortController scoped to this mount.  Stored on the container so a
  // subsequent mountTitlesPanel call can abort the prior listener before
  // replacing the DOM.
  const escAc = new AbortController();
  containerEl._epAbortCtrl = escAc;

  modalClose.addEventListener('click', closeModal);
  modalOverlay.addEventListener('click', e => { if (e.target === modalOverlay) closeModal(); });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && modalOverlay.style.display !== 'none') closeModal();
  }, { signal: escAc.signal });

  // ── Titles rendering ────────────────────────────────────────────────────

  function buildFilterQs() {
    const f = titleFilter;
    const parts = [];
    if (f.tags.length > 0)         parts.push(`tags=${encodeURIComponent(f.tags.join(','))}`);
    if (f.minRatingAvg !== null)    parts.push(`minRatingAvg=${f.minRatingAvg}`);
    if (f.minRatingCount !== null)  parts.push(`minRatingCount=${f.minRatingCount}`);
    return parts.length > 0 ? '?' + parts.join('&') : '';
  }

  function isFilterActive() {
    return titleFilter.tags.length > 0 || titleFilter.minRatingAvg !== null || titleFilter.minRatingCount !== null;
  }

  async function fetchAndRender() {
    try {
      const qs = buildFilterQs();
      const [titlesRes, facetsRes] = await Promise.all([
        fetch(`/api/javdb/discovery/actresses/${actressId}/titles${qs}`),
        fetch(`/api/javdb/discovery/actresses/${actressId}/tag-facets${qs}`),
      ]);
      if (!titlesRes.ok) { titlesView.innerHTML = '<div class="jd-error">Failed to load titles.</div>'; return; }
      const titles = await titlesRes.json();
      const facets = facetsRes.ok ? await facetsRes.json() : [];
      titlesView.innerHTML = filterBarHtml(facets, titles.length) + titlesTableHtml(titles);
      wireFilterBar();
      wireReenrichButtons();
      wireDetailTriggers();
      wireFailureBadges();
      wireCoverLinks(titlesView);
    } catch (_) {
      titlesView.innerHTML = '<div class="jd-error">Network error.</div>';
    }
  }

  function filterBarHtml(facets, matchCount) {
    const f = titleFilter;
    const selectedSet = new Set(f.tags);
    const ordered = [
      ...facets.filter(x => selectedSet.has(x.name)),
      ...facets.filter(x => !selectedSet.has(x.name)),
    ];
    const tagChips = ordered.slice(0, 30).map(x => {
      const sel = selectedSet.has(x.name);
      return `<button type="button" class="jd-tag-chip${sel ? ' selected' : ''}" data-tag="${esc(x.name)}">
        ${esc(x.name)} <span class="jd-tag-count">${x.count}</span>
      </button>`;
    }).join('');
    const summary = isFilterActive() ? `<span class="jd-filter-summary">${matchCount} matching</span>` : '';
    const clearBtn = isFilterActive()
      ? `<button type="button" class="jd-ep-filter-clear jd-filter-clear">Clear filters</button>` : '';
    return `
      <div class="jd-filter-bar">
        <div class="jd-filter-row">
          <label class="jd-filter-label">Min rating</label>
          <input class="jd-ep-min-avg jd-filter-num" type="number" step="0.1" min="0" max="5"
                 placeholder="e.g. 4.2" value="${f.minRatingAvg ?? ''}">
          <label class="jd-filter-label">Min votes</label>
          <input class="jd-ep-min-cnt jd-filter-num" type="number" step="1" min="0"
                 placeholder="e.g. 50" value="${f.minRatingCount ?? ''}">
          ${summary}${clearBtn}
        </div>
        <div class="jd-filter-row jd-tag-chips">
          ${tagChips || '<span class="jd-filter-hint">No tags on this actress\'s enriched titles.</span>'}
        </div>
      </div>`;
  }

  function titlesTableHtml(titles) {
    if (titles.length === 0) {
      return '<div class="jd-empty-tab">No titles match the current filter.</div>';
    }
    return `<table class="jd-titles-table">
      <thead><tr>
        <th>Code</th><th>Status</th><th>Original Title</th><th>Release</th><th>Maker</th><th>Rating</th><th></th>
      </tr></thead>
      <tbody>${titles.map(titleRow).join('')}</tbody>
    </table>`;
  }

  function titleEffectiveStatus(t) {
    if (t.queueStatus === 'in_flight') return { key: 'in_flight', label: '⟳ In Progress' };
    if (t.queueStatus === 'pending')   return { key: 'pending',   label: '◌ Queued' };
    if (t.status === 'fetched')        return { key: 'fetched',   label: '✓ Enriched' };
    if (t.queueStatus === 'failed') {
      const meta  = QUEUE_FAIL_META[t.lastError];
      const icon  = meta?.icon  ?? '✗';
      const label = meta?.label ?? (t.lastError ? t.lastError.replace(/_/g, ' ') : 'failed');
      const cls   = meta?.cls   ?? 'jd-qi-failed';
      return { key: 'failed', label: `${icon} ${label}`, cls, lastError: t.lastError, reviewQueueId: t.reviewQueueId };
    }
    if (t.status === 'slug_only') return { key: 'slug_only', label: '⌁ Slug Only' };
    if (t.queueStatus === 'done') return { key: 'done',      label: '✓ Done' };
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
      ? `<button class="jd-enrich-detail-btn" data-title-id="${t.titleId}" title="View enrichment details">ⓘ</button>` : '';
    const reenrichBtn = canReenrich
      ? `<button class="jd-reenrich-btn" data-title-id="${t.titleId}" title="Force re-enrich">↺</button>` : '';
    const rating = t.ratingAvg != null
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

  function wireFilterBar() {
    const minAvg = titlesView.querySelector('.jd-ep-min-avg');
    const minCnt = titlesView.querySelector('.jd-ep-min-cnt');
    if (minAvg) minAvg.addEventListener('change', () => {
      const v = minAvg.value.trim();
      titleFilter.minRatingAvg = v === '' ? null : parseFloat(v);
      fetchAndRender();
    });
    if (minCnt) minCnt.addEventListener('change', () => {
      const v = minCnt.value.trim();
      titleFilter.minRatingCount = v === '' ? null : parseInt(v, 10);
      fetchAndRender();
    });
    titlesView.querySelectorAll('.jd-tag-chip').forEach(chip => {
      chip.addEventListener('click', () => {
        const tag = chip.dataset.tag;
        const idx = titleFilter.tags.indexOf(tag);
        if (idx >= 0) titleFilter.tags.splice(idx, 1);
        else titleFilter.tags.push(tag);
        fetchAndRender();
      });
    });
    const clearBtn = titlesView.querySelector('.jd-ep-filter-clear');
    if (clearBtn) clearBtn.addEventListener('click', () => {
      titleFilter = { tags: [], minRatingAvg: null, minRatingCount: null };
      fetchAndRender();
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
          const r = await fetch(`/api/javdb/discovery/actresses/${actressId}/titles/${titleId}/reenrich`, { method: 'POST' });
          if (!r.ok) throw new Error('http ' + r.status);
          btn.classList.remove('jd-btn-busy');
          btn.classList.add('jd-btn-success');
          btn.textContent = '✓';
          btn.title = 'Queued';
          setTimeout(() => fetchAndRender(), 800);
          await refreshQueue();
        } catch (_) {
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

  function wireDetailTriggers() {
    titlesView.querySelectorAll('.jd-enrich-detail-btn, .jd-status-clickable').forEach(el => {
      el.addEventListener('click', e => {
        e.stopPropagation();
        openModal(Number(el.dataset.titleId));
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
        if (hooks.switchToProfile) {
          hooks.switchToProfile();
        } else {
          document.dispatchEvent(new CustomEvent('navigate-to-discovery-actress-profile', {
            detail: { actressId }
          }));
        }
      });
    });
  }

  function wireCoverLinks(container) {
    container.querySelectorAll('.jd-cover-link[data-cover-url]').forEach(btn => {
      btn.addEventListener('click', () => showJdCoverModal(btn.dataset.coverUrl, btn.dataset.code));
    });
  }

  // ── Action bar ──────────────────────────────────────────────────────────

  if (enrichBtn) {
    enrichBtn.addEventListener('click', async () => {
      const original = enrichBtn.textContent;
      enrichBtn.disabled = true;
      enrichBtn.textContent = 'Enqueueing…';
      try {
        const res = await fetch(`/api/javdb/discovery/actresses/${actressId}/enqueue`, { method: 'POST' });
        if (res.ok) {
          const { enqueued } = await res.json();
          enrichBtn.textContent = `Enqueued ${enqueued} ✓`;
          await Promise.all([loadActresses(), refreshQueue()]);
          fetchAndRender();
          setTimeout(() => { enrichBtn.textContent = original; enrichBtn.disabled = false; }, 1500);
          return;
        }
      } catch (_) { /* fall through */ }
      enrichBtn.textContent = original;
      enrichBtn.disabled = false;
    });
  }

  if (cancelBtn) {
    cancelBtn.addEventListener('click', async () => {
      await fetch(`/api/javdb/discovery/actresses/${actressId}/queue`, { method: 'DELETE' });
      await refreshQueue();
    });
  }

  // Initial load.
  titlesView.innerHTML = '<div class="jd-loading">Loading…</div>';
  fetchAndRender();

  // Return a handle so callers can refresh the panel without remounting.
  return { refresh: fetchAndRender };
}

// ── Profile panel ─────────────────────────────────────────────────────────────

export function mountProfilePanel(containerEl, { actressId, hooks = {} }) {
  const noop = () => Promise.resolve();
  const refreshQueue = hooks.refreshQueue ?? noop;

  containerEl.innerHTML = '<div class="jd-loading">Loading…</div>';

  async function render() {
    try {
      const res = await fetch(`/api/javdb/discovery/actresses/${actressId}/profile`);
      if (res.status === 404) {
        await render404();
        return;
      }
      if (!res.ok) { containerEl.innerHTML = '<div class="jd-error">Failed to load profile.</div>'; return; }
      renderProfile(await res.json());
    } catch (_) {
      containerEl.innerHTML = '<div class="jd-error">Network error.</div>';
    }
  }

  async function render404() {
    // Fetch enriched-title count from actress API rather than relying on a
    // caller-supplied count (which does not exist in actress-detail context).
    let enrichedTitles = 0;
    try {
      const r = await fetch(`/api/javdb/discovery/actresses/${actressId}/titles`);
      if (r.ok) {
        const titles = await r.json();
        enrichedTitles = titles.filter(t => t.status === 'fetched').length;
      }
    } catch (_) { /* show "no data" path */ }
    const canDerive = enrichedTitles > 0;
    containerEl.innerHTML = `
      <div class="jd-empty-tab">
        <div>No staging profile yet.</div>
        ${canDerive ? `
          <div class="jd-derive-help">
            ${enrichedTitles} title(s) enriched but no slug recorded. Derive the slug from her
            co-stars' cast lists and queue a profile fetch:
          </div>
          <div class="jd-profile-actions">
            <button class="jd-ep-derive-btn jd-action-btn jd-muted-btn">⚡ Find Slug from Titles</button>
          </div>
          <div class="jd-ep-derive-result"></div>
        ` : `
          <div class="jd-derive-help">No enriched titles yet — no cast data to derive a slug from.</div>
        `}
      </div>`;
    const dBtn = containerEl.querySelector('.jd-ep-derive-btn');
    if (dBtn) dBtn.addEventListener('click', () => deriveSlug(dBtn));
  }

  function renderProfile(p) {
    containerEl.innerHTML = `
      <div class="jd-profile">
        ${(p.localAvatarUrl || p.avatarUrl)
          ? `<img class="jd-avatar" src="${esc(p.localAvatarUrl || p.avatarUrl)}" alt="avatar">`
          : ''}
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
          <button class="jd-ep-refetch-btn jd-action-btn jd-muted-btn">↺ Re-fetch Profile</button>
          ${p.avatarUrl && !p.localAvatarUrl
            ? `<button class="jd-ep-download-avatar-btn jd-action-btn jd-muted-btn">⬇ Download Avatar</button>`
            : ''}
        </div>
      </div>`;

    containerEl.querySelector('.jd-ep-refetch-btn').addEventListener('click', async () => {
      const btn = containerEl.querySelector('.jd-ep-refetch-btn');
      const original = btn.textContent;
      btn.textContent = '⌛ Enqueuing…';
      btn.disabled = true;
      btn.classList.add('jd-btn-busy');
      try {
        const r = await fetch(`/api/javdb/discovery/actresses/${actressId}/profile/reenrich`, { method: 'POST' });
        if (!r.ok) throw new Error('http ' + r.status);
        btn.classList.remove('jd-btn-busy');
        btn.classList.add('jd-btn-success');
        btn.textContent = '✓ Queued';
        await refreshQueue();
        setTimeout(() => {
          btn.classList.remove('jd-btn-success');
          btn.textContent = original;
          btn.disabled = false;
        }, 2500);
      } catch (_) {
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

    const dlBtn = containerEl.querySelector('.jd-ep-download-avatar-btn');
    if (dlBtn) {
      dlBtn.addEventListener('click', async () => {
        const original = dlBtn.textContent;
        dlBtn.textContent = '⌛ Downloading…';
        dlBtn.disabled = true;
        dlBtn.classList.add('jd-btn-busy');
        try {
          const r = await fetch(`/api/javdb/discovery/actresses/${actressId}/avatar/download`, { method: 'POST' });
          const body = await r.json().catch(() => ({}));
          if (!r.ok) {
            const reason = body.status === 'no_url'     ? 'no avatar URL on profile'
                         : body.status === 'failed'     ? 'CDN download failed'
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
          setTimeout(() => render(), 700);
        } catch (_) {
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
  }

  async function deriveSlug(btn) {
    const original = btn.textContent;
    btn.textContent = '⌛ Deriving…';
    btn.disabled = true;
    btn.classList.add('jd-btn-busy');
    const out = containerEl.querySelector('.jd-ep-derive-result');
    try {
      const r = await fetch(`/api/javdb/discovery/actresses/${actressId}/profile/derive-slug`, { method: 'POST' });
      const body = await r.json().catch(() => ({}));
      if (r.ok) {
        btn.classList.remove('jd-btn-busy');
        btn.classList.add('jd-btn-success');
        btn.textContent = body.status === 'already_resolved'
          ? '✓ Already had slug — re-queued' : '✓ Slug found — queued';
        if (out) {
          out.innerHTML = `<div class="jd-derive-success">
            Picked slug <code>${esc(body.chosenSlug)}</code>${body.chosenName ? ` (${esc(body.chosenName)})` : ''}
            ${body.chosenTitleCount ? ` — appears in ${body.chosenTitleCount} of her ${body.totalEnrichedTitles} enriched titles.` : ''}
            See Queue tab for fetch progress.
          </div>`;
        }
        setTimeout(() => render(), 1500);
        return;
      }
      btn.classList.remove('jd-btn-busy');
      btn.classList.add('jd-btn-error');
      if (body.status === 'ambiguous' && out) {
        const rows = (body.candidates || []).slice(0, 5).map(c =>
          `<tr><td><code>${esc(c.slug)}</code></td><td>${esc(c.name || '—')}</td><td>${c.titleCount}</td></tr>`).join('');
        btn.textContent = '✗ Ambiguous';
        out.innerHTML = `<div class="jd-derive-error">
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
    } catch (_) {
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

  render();
}
