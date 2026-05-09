// Edit Card renderer — Phase 5: adds Normalize folder button + modal trigger.
//
// Renders header (§4.1) + flags row (§4.2) + optional no-content banner
// + §4.3 duplicate-triage section (when locationEntries.length > 1)
// + §4.4 folder-contents section (when locationEntries.length === 1)
// + Commit/Cancel footer (§4.5).
//
// Mode is derived from server and FS state (not staged state):
//   'rejected'     — t.rejected === true
//   'loading'      — single-location, folder-contents fetch in flight (_folderContents === null)
//   'fetch-error'  — single-location, fetch failed (sticky error sentinel)
//   'empty-folder' — single-location, videos+covers+otherFiles all empty
//   'cover-only'   — single-location, no videos but covers or otherFiles present
//   'normal'       — multi-location, or single-location with ≥1 video
//
// Re-render strategy: renderCardInPlace(code) replaces a single card's
// outerHTML in-place, then re-attaches event listeners via attachCardListeners.
// renderPage in index.js calls attachCardListeners after building innerHTML.
//
// §4.3 lazy-fetch: decisions are fetched in attachCardListeners when the card
// first needs them, cached on titleData._dupDecisions, and used on re-renders.

import { esc, ageAtDate, agePillTier } from '../utils.js';
import { ICON_FAV_LG, ICON_BM_LG, ICON_REJ_LG, gradeBadgeHtml, tagBadgeHtml } from '../icons.js';
import * as state from './state.js';
import { commitCard, cancelCard } from './commit.js';
import { renderFolderContents, ensureFolderContents, attachFolderListeners, isFolderContentsError, folderContentsErrorMsg } from './folder-contents.js';
import { openNormalizeModal } from './normalize-modal.js';
import { renderDupSection, attachDupCardListeners } from './dup-cards.js';

// ── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Derive the card mode from title data and the cached FS listing.
 * For single-location titles the mode is based on the FS listing.
 * Multi-location titles are always 'normal' (dup-triage section handles them).
 * Rejected titles are always 'rejected' regardless of FS state (§4.7 precedence).
 *
 * @param {object} t  Title data object (may have _folderContents attached)
 * @returns {'rejected'|'loading'|'fetch-error'|'empty-folder'|'cover-only'|'normal'}
 */
function deriveMode(t) {
  if (t.rejected) return 'rejected';

  const locationEntries = t.locationEntries || [];
  if (locationEntries.length !== 1) return 'normal';

  const fc = Object.prototype.hasOwnProperty.call(t, '_folderContents') ? t._folderContents : undefined;
  if (fc === undefined || fc === null) return 'loading';
  if (isFolderContentsError(fc)) return 'fetch-error';

  const hasVideos = fc.videos && fc.videos.length > 0;
  if (hasVideos) return 'normal';

  const hasContent = (fc.covers && fc.covers.length > 0) || (fc.otherFiles && fc.otherFiles.length > 0);
  return hasContent ? 'cover-only' : 'empty-folder';
}

/**
 * Current effective value for a flag on a card.
 * If a pending stage for this kind exists, its target overrides the server value.
 * @param {string} code
 * @param {string} kind  'flag-favorite' | 'flag-bookmark' | 'flag-reject'
 * @param {boolean} serverValue
 * @returns {boolean}
 */
function effectiveFlagValue(code, kind, serverValue) {
  const stage = state.findPendingStage(code, kind);
  return stage ? stage.payload.target : serverValue;
}

// ── §4.3 Duplicate-triage section — delegated to dup-cards.js ───────────────
// renderDupSection and dupKey are imported from ./dup-cards.js above.

// ── §4.4.1 Normalize folder button ───────────────────────────────────────────

/**
 * Render the "Normalize folder" action button (or "staged" indicator) for a
 * single-location title. Always shown when the folder section is visible.
 *
 * When a 'normalize-folder' stage is pending, shows "Normalize staged — Undo?" instead.
 * @param {string} code
 * @returns {string} HTML
 */
function renderNormalizeButton(code) {
  const pendingStage = state.findPendingStage(code, 'normalize-folder');
  if (pendingStage) {
    return `
      <div class="admin-card-normalize-row">
        <span class="admin-card-normalize-staged">Normalize staged*</span>
        <button class="admin-card-normalize-undo-btn" data-normalize-action="undo-normalize">Undo</button>
      </div>`;
  }
  return `
    <div class="admin-card-normalize-row">
      <button class="admin-card-normalize-btn" data-normalize-action="open-modal">Normalize folder…</button>
    </div>`;
}

// ── Card HTML ────────────────────────────────────────────────────────────────

export function renderCard(t) {
  const code = t.code;

  // ── Card mode — FS-based for single-location titles (Phase 4d) ───────
  const mode = deriveMode(t);

  // ── Header: cover ──────────────────────────────────────────────────────
  const coverHtml = t.coverUrl
    ? `<div class="admin-card-cover"><img src="${esc(t.coverUrl)}" alt="${esc(code)}" loading="lazy"></div>`
    : `<div class="admin-card-cover"><div class="cover-placeholder">${esc(code)}</div></div>`;

  // ── Header: reject badge ───────────────────────────────────────────────
  const rejectedBadgeHtml = t.rejected
    ? '<span class="admin-card-reject-badge">⨯ REJECTED</span>'
    : '';

  // ── Header: titles ────────────────────────────────────────────────────
  const enText = t.titleEnglish || t.titleOriginalEn || '';
  const titleEnHtml = enText
    ? `<div class="admin-card-title">${esc(enText)}</div>`
    : (t.titleOriginal ? `<div class="admin-card-title">${esc(t.titleOriginal)}</div>` : '');
  // Show Japanese original alongside when English is present
  const titleJaHtml = (enText && t.titleOriginal)
    ? `<div class="admin-card-title-ja">${esc(t.titleOriginal)}</div>`
    : '';

  // ── Header: studio / label / date meta line ───────────────────────────
  const metaParts = [];
  if (t.companyName || t.labelName) {
    const lp = [];
    if (t.companyName) lp.push(esc(t.companyName));
    if (t.labelName)   lp.push(`(${esc(t.labelName)})`);
    metaParts.push(lp.join(' '));
  }
  const displayDate = t.releaseDate || t.addedDate;
  if (displayDate) metaParts.push(esc(displayDate));
  const metaLineHtml = metaParts.length > 0
    ? `<div class="admin-card-meta-line">${metaParts.join(' · ')}</div>`
    : '';

  // ── Header: cast ──────────────────────────────────────────────────────
  let castHtml = '';
  if (t.actresses && t.actresses.length > 0) {
    const names = t.actresses.map(a => esc(a.name)).join(', ');
    castHtml = `<div class="admin-card-cast">${names}</div>`;
  } else if (t.actressName) {
    castHtml = `<div class="admin-card-cast">${esc(t.actressName)}</div>`;
  }

  // ── Header: tags ──────────────────────────────────────────────────────
  const tags = t.tags || [];
  const tagsHtml = tags.length > 0
    ? `<div class="admin-card-tags">${tags.map(tagBadgeHtml).join('')}</div>`
    : '';

  // ── Header: grade + age pill ──────────────────────────────────────────
  const gradeHtml = t.grade ? gradeBadgeHtml(t.grade) : '';
  // Age at release: prefer first actress with dateOfBirth, fall back to primary
  let ageHtml = '';
  if (t.actresses && t.actresses.length === 1 && t.actresses[0].dateOfBirth && t.releaseDate) {
    const age = ageAtDate(t.actresses[0].dateOfBirth, t.releaseDate);
    if (age != null) {
      ageHtml = `<span class="age-pill" data-age-tier="${agePillTier(age)}">${age}</span>`;
    }
  } else if (t.actressDateOfBirth && t.releaseDate) {
    const age = ageAtDate(t.actressDateOfBirth, t.releaseDate);
    if (age != null) {
      ageHtml = `<span class="age-pill" data-age-tier="${agePillTier(age)}">${age}</span>`;
    }
  }
  const gradeAgeHtml = (gradeHtml || ageHtml)
    ? `<div class="admin-card-grade-age">${gradeHtml}${ageHtml}</div>`
    : '';

  // ── Header: pending badge ────────────────────────────────────────────
  const pendingCount = state.getPendingCount(code);
  const pendingBadgeHtml = pendingCount > 0
    ? `<div class="admin-card-pending-badge">⚠ pending: ${pendingCount} edit${pendingCount !== 1 ? 's' : ''}</div>`
    : '';

  const headerHtml = `
    <div class="admin-card-header">
      ${coverHtml}
      <div class="admin-card-meta">
        <div class="admin-card-code-row">
          <span class="admin-card-code">${esc(code)}</span>
          ${rejectedBadgeHtml}
        </div>
        ${titleEnHtml}
        ${titleJaHtml}
        ${metaLineHtml}
        ${castHtml}
        ${tagsHtml}
        ${gradeAgeHtml}
      </div>
      ${pendingBadgeHtml}
    </div>`;

  // ── Flags row (§4.2) ──────────────────────────────────────────────────
  const effFav = effectiveFlagValue(code, 'flag-favorite', t.favorite);
  const effBm  = effectiveFlagValue(code, 'flag-bookmark', t.bookmark);
  const effRej = effectiveFlagValue(code, 'flag-reject',   t.rejected);

  const hasFavStage = !!state.findPendingStage(code, 'flag-favorite');
  const hasBmStage  = !!state.findPendingStage(code, 'flag-bookmark');
  const hasRejStage = !!state.findPendingStage(code, 'flag-reject');

  // Fav and BM are disabled when effectively rejected or in no-content/loading/error mode.
  // Tooltip text differs by mode.
  const isNoContentMode = mode === 'empty-folder' || mode === 'cover-only';
  let favBmDisabled = false;
  let favBmTitle = '';
  if (mode === 'rejected') {
    favBmDisabled = true;
    favBmTitle = 'title is rejected; clear reject first';
  } else if (isNoContentMode) {
    favBmDisabled = true;
    favBmTitle = 'title has no content';
  } else if (effRej) {
    // staged reject — mutex still applies in normal mode
    favBmDisabled = true;
    favBmTitle = 'title is rejected; clear reject first';
  }

  const favDisabled = favBmDisabled ? ' disabled' : '';
  const bmDisabled  = favBmDisabled ? ' disabled' : '';

  const favClasses = `admin-card-flag-btn fav-btn${effFav ? ' active' : ''}${hasFavStage ? ' staged' : ''}${favDisabled}`;
  const bmClasses  = `admin-card-flag-btn bm-btn${effBm  ? ' active' : ''}${hasBmStage  ? ' staged' : ''}${bmDisabled}`;
  const rejClasses = `admin-card-flag-btn rej-btn${effRej ? ' active' : ''}${hasRejStage ? ' staged' : ''}`;

  const favTitle = favBmTitle || 'Favorite';
  const bmTitle  = favBmTitle || 'Bookmark';

  const flagsHtml = `
    <div class="admin-card-flags-row">
      <button class="${favClasses}" data-flag="favorite" title="${esc(favTitle)}">${ICON_FAV_LG} Favorite</button>
      <button class="${bmClasses}"  data-flag="bookmark" title="${esc(bmTitle)}">${ICON_BM_LG} Bookmark</button>
      <button class="${rejClasses}" data-flag="reject"   title="Reject">${ICON_REJ_LG} Reject</button>
    </div>`;

  // ── No-content / loading / error banners (§4.6) ─────────────────────
  let noContentBannerHtml = '';
  if (mode === 'loading') {
    noContentBannerHtml = '<div class="admin-card-folder-loading">Loading folder contents…</div>';
  } else if (mode === 'fetch-error') {
    const errMsg = folderContentsErrorMsg(t._folderContents);
    noContentBannerHtml = `
      <div class="admin-card-no-content-banner admin-card-no-content-fetch-error">
        ⚠ FOLDER STATE UNKNOWN — could not read from disk (${esc(errMsg)})
        <button type="button" class="admin-card-whats-this-btn" aria-expanded="false">[ what's this? ]</button>
        <div class="admin-card-whats-this-body" hidden>
          The folder contents could not be fetched. The volume may not be mounted,
          or there was a network error. Reload the card or remount the volume and try again.
        </div>
      </div>`;
  } else if (mode === 'empty-folder') {
    noContentBannerHtml = `
      <div class="admin-card-no-content-banner admin-card-no-content-empty">
        ⚠ NO CONTENT — folder is empty on disk
        <button type="button" class="admin-card-whats-this-btn" aria-expanded="false">[ what's this? ]</button>
        <div class="admin-card-whats-this-body" hidden>
          This title's folder is empty. The Admin tab can't fix this —
          a future Tools feature will let you review and clean up no-content
          titles across the library. Use the Reject button to flag this title
          for that future tool to find.
        </div>
      </div>`;
  } else if (mode === 'cover-only') {
    noContentBannerHtml = `
      <div class="admin-card-no-content-banner admin-card-no-content-cover-only">
        ⚠ NO CONTENT — no video files (cover/other files only)
        <button type="button" class="admin-card-whats-this-btn" aria-expanded="false">[ what's this? ]</button>
        <div class="admin-card-whats-this-body" hidden>
          This title has no video files on disk — only cover images or other files.
          The title may have been moved or never had videos. You can trash the
          remaining files below, then use the Reject button to flag this title
          for the future no-content cleanup tool.
        </div>
      </div>`;
  }

  // ── Section §4.3 / §4.4 — suppressed in rejected/no-content/loading/error modes
  const locationEntries = t.locationEntries || [];
  const locationCount = locationEntries.length;
  let sectionHtml = '';
  if (mode === 'normal') {
    if (locationCount > 1) {
      // §4.3: embedded duplicate-triage. Decisions cached on titleData._dupDecisions.
      // null = still loading (show skeleton); array = loaded (may be empty).
      const cachedDec = Object.prototype.hasOwnProperty.call(t, '_dupDecisions')
        ? t._dupDecisions
        : null;
      sectionHtml = renderDupSection(code, locationEntries, cachedDec);
    } else if (locationCount === 1) {
      const cachedContents = Object.prototype.hasOwnProperty.call(t, '_folderContents')
        ? t._folderContents
        : undefined;
      sectionHtml = renderFolderContents(code, cachedContents) + renderNormalizeButton(code);
    }
  } else if (mode === 'cover-only' && locationCount === 1) {
    // Cover-only: show the cover list so the user can trash them (§4.6).
    // Videos list is omitted since there are none; covers section renders as usual.
    const cachedContents = Object.prototype.hasOwnProperty.call(t, '_folderContents')
      ? t._folderContents
      : undefined;
    sectionHtml = renderFolderContents(code, cachedContents) + renderNormalizeButton(code);
  }

  // ── Error bar (failed stage) ──────────────────────────────────────────
  const failedStage = state.getStages(code).find(s => s.status === 'failed');
  const errorHtml = failedStage
    ? `<div class="admin-card-error-text">Error: ${esc(failedStage.error || 'Unknown error')}</div>`
    : '';

  // ── Commit / Cancel footer (§4.5) ────────────────────────────────────
  const hasFailures = !!failedStage;
  let footerHtml = '';
  if (pendingCount > 0) {
    const commitLabel = hasFailures
      ? 'Retry remaining'
      : `Commit ${pendingCount} change${pendingCount !== 1 ? 's' : ''}`;
    footerHtml = `
      <div class="admin-card-footer">
        <button class="admin-card-commit-btn" data-action="commit">${esc(commitLabel)}</button>
        <button class="admin-card-cancel-btn" data-action="cancel">Cancel</button>
      </div>`;
  }

  const modeClass = mode === 'rejected'    ? ' admin-edit-card-rejected'
                  : mode === 'empty-folder' ? ' admin-edit-card-no-content'
                  : mode === 'cover-only'   ? ' admin-edit-card-no-content'
                  : mode === 'fetch-error'  ? ' admin-edit-card-fetch-error'
                  : '';

  return `
    <div class="admin-edit-card${modeClass}" data-code="${esc(code)}">
      ${headerHtml}
      ${noContentBannerHtml}
      ${flagsHtml}
      ${sectionHtml}
      ${errorHtml}
      ${footerHtml}
    </div>`;
}

// ── Re-render a single card in place ────────────────────────────────────────

export function renderCardInPlace(code) {
  const card = document.querySelector(`.admin-edit-card[data-code="${CSS.escape(code)}"]`);
  if (!card) return;
  const titleData = state.getCardData(code);
  if (!titleData) return;
  const tmp = document.createElement('div');
  tmp.innerHTML = renderCard(titleData);
  const newCard = tmp.firstElementChild;
  card.replaceWith(newCard);
  attachCardListeners(code);
}

// ── Event listener attachment ────────────────────────────────────────────────

export function attachCardListeners(code) {
  const card = document.querySelector(`.admin-edit-card[data-code="${CSS.escape(code)}"]`);
  if (!card) return;

  const titleData = state.getCardData(code);
  if (!titleData) return;

  // "What's this?" toggle for no-content banner
  const whatsThisBtn = card.querySelector('.admin-card-whats-this-btn');
  if (whatsThisBtn) {
    whatsThisBtn.addEventListener('click', () => {
      const body = card.querySelector('.admin-card-whats-this-body');
      if (!body) return;
      const expanded = whatsThisBtn.getAttribute('aria-expanded') === 'true';
      whatsThisBtn.setAttribute('aria-expanded', String(!expanded));
      body.hidden = expanded;
    });
  }

  // §4.4 Lazy-fetch folder contents for single-location titles.
  // Fetch for all non-rejected single-location titles — mode is derived from the result.
  const locationEntries = titleData.locationEntries || [];
  const mode = deriveMode(titleData);
  if (!titleData.rejected && locationEntries.length === 1) {
    ensureFolderContents(code, titleData);
    if (mode === 'normal' || mode === 'cover-only') {
      attachFolderListeners(code, card, titleData);
    }
  }

  // §4.3 Lazy-fetch duplicate decisions + per-location videos in parallel for
  // multi-location titles. Both gate `null = in-flight`, missing prop = not
  // started, value = loaded. We re-render once when both settle so the user
  // sees one transition (loading → loaded) instead of three (loading → decisions →
  // videos).
  if (locationEntries.length > 1 && !Object.prototype.hasOwnProperty.call(titleData, '_dupDecisions')) {
    titleData._dupDecisions = null;
    titleData._locVideos    = null;

    const decFetch = fetch(`/api/titles/${encodeURIComponent(code)}/duplicate-decisions`)
      .then(res => res.ok ? res.json() : Promise.reject(new Error(`HTTP ${res.status}`)))
      .then(decisions => { titleData._dupDecisions = decisions; })
      .catch(() => { delete titleData._dupDecisions; });

    const vidFetch = Promise.all(
      locationEntries.map(async loc => {
        const key = `${loc.volumeId}::${loc.nasPath}`;
        let url = `/api/titles/${encodeURIComponent(code)}/videos?volumeId=${encodeURIComponent(loc.volumeId)}`;
        if (loc.locPath) url += `&locPath=${encodeURIComponent(loc.locPath)}`;
        try {
          const res = await fetch(url);
          return [key, res.ok ? await res.json() : []];
        } catch {
          return [key, []];
        }
      })
    ).then(pairs => { titleData._locVideos = new Map(pairs); });

    Promise.allSettled([decFetch, vidFetch]).then(() => renderCardInPlace(code));
  }

  // §4.3 Rich duplicate cards — listeners for buttons, inspect, and auto-keep.
  if (locationEntries.length > 1) {
    attachDupCardListeners(code, card, titleData, renderCardInPlace);
  }

  // §4.4.1 Normalize folder button / undo.
  card.querySelectorAll('[data-normalize-action]').forEach(btn => {
    btn.addEventListener('click', () => {
      const action = btn.dataset.normalizeAction;
      if (action === 'open-modal') {
        const folderContents = titleData._folderContents || null;
        openNormalizeModal(code, folderContents);
      } else if (action === 'undo-normalize') {
        state.removePendingStage(code, 'normalize-folder', null);
        renderCardInPlace(code);
      }
    });
  });

  // Flag buttons
  card.querySelectorAll('.admin-card-flag-btn[data-flag]').forEach(btn => {
    btn.addEventListener('click', () => {
      const flag = btn.dataset.flag; // 'favorite' | 'bookmark' | 'reject'
      const kind = `flag-${flag}`;   // 'flag-favorite' | 'flag-bookmark' | 'flag-reject'

      // Current effective values
      const effFav = effectiveFlagValue(code, 'flag-favorite', titleData.favorite);
      const effBm  = effectiveFlagValue(code, 'flag-bookmark',  titleData.bookmark);
      const effRej = effectiveFlagValue(code, 'flag-reject',    titleData.rejected);

      // Mutex: clicking Favorite or Bookmark while effectively rejected,
      // server-rejected, or in no-content mode → no-op
      const serverRejected = titleData.rejected;
      const cardMode = deriveMode(titleData);
      const isNoContent = cardMode === 'empty-folder' || cardMode === 'cover-only';
      if ((flag === 'favorite' || flag === 'bookmark') && (effRej || serverRejected || isNoContent)) return;

      // Compute which server value drives this flag
      const serverValue = flag === 'favorite' ? titleData.favorite
                        : flag === 'bookmark' ? titleData.bookmark
                        : titleData.rejected;

      // Current effective value for this flag
      const currentEff = flag === 'favorite' ? effFav
                       : flag === 'bookmark'  ? effBm
                       : effRej;

      // Target is the toggle of the current effective value
      const target = !currentEff;

      if (target === serverValue) {
        // Clicking back to server state: un-stage (no-op commit)
        state.removePendingStage(code, kind);
      } else {
        state.addStage(code, kind, { target });

        // Reject mutex: if staging reject=true, also un-stage fav/bm if they
        // are currently staged (so they don't linger as pending no-ops when
        // they'll be server-cleared by the reject call)
        if (flag === 'reject' && target === true) {
          state.removePendingStage(code, 'flag-favorite');
          state.removePendingStage(code, 'flag-bookmark');
        }
      }

      renderCardInPlace(code);
    });
  });

  // Commit button
  const commitBtn = card.querySelector('.admin-card-commit-btn[data-action="commit"]');
  if (commitBtn) {
    commitBtn.addEventListener('click', async () => {
      commitBtn.disabled = true;
      const result = await commitCard(code);
      // commit.js has already updated titleData in state on success
      renderCardInPlace(code);
      // Re-attach after render (renderCardInPlace does this — but the click
      // handler here is on the old node which is gone; the new listeners are
      // wired by the renderCardInPlace call above)
    });
  }

  // Cancel button
  const cancelBtn = card.querySelector('.admin-card-cancel-btn[data-action="cancel"]');
  if (cancelBtn) {
    cancelBtn.addEventListener('click', () => {
      cancelCard(code);
      renderCardInPlace(code);
    });
  }
}
