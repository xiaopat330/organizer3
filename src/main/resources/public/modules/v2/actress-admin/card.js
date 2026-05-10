// v2 Admin tab — Edit Card renderer.
//
// Mirrors legacy actress-detail-admin/card.js verbatim.
// Renders header + flags row + optional no-content banner
// + §4.3 duplicate-triage section (locationEntries.length > 1)
// + §4.4 folder-contents section (locationEntries.length === 1)
// + Commit/Cancel footer.
//
// Re-render strategy: renderCardInPlace(code) replaces a single card's
// outerHTML in-place, then re-attaches event listeners.

import { esc, ageAtDate, agePillTier, fmtDate } from '../../utils.js';
import { ICON_FAV_LG, ICON_BM_LG, ICON_REJ_LG, gradeBadgeHtml, tagBadgeHtml } from '../../icons.js';
import * as state from './state.js';
import { commitCard, cancelCard } from './commit.js';
import { renderFolderContents, ensureFolderContents, attachFolderListeners, isFolderContentsError, folderContentsErrorMsg } from './folder-contents.js';
import { openNormalizeModal } from './normalize-modal.js';
import { renderDupSection, attachDupCardListeners } from './dup-cards.js';

// ── Helpers ─────────────────────────────────────────────────────────────────

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

function effectiveFlagValue(code, kind, serverValue) {
  const stage = state.findPendingStage(code, kind);
  return stage ? stage.payload.target : serverValue;
}

// ── §4.4.1 Normalize folder button ───────────────────────────────────────────

const ICON_NORMALIZE = '<svg class="admin-icon-normalize" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4l6 6-10 10H4v-6z"/><line x1="14" y1="4" x2="20" y2="10"/></svg>';
const ICON_PERSON    = '<svg class="admin-icon-person" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>';
const ICON_CAL       = '<svg class="admin-icon-cal" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8"  y1="2" x2="8"  y2="6"/><line x1="3"  y1="10" x2="21" y2="10"/></svg>';

function renderNormalizeButton(code) {
  const pendingStage = state.findPendingStage(code, 'normalize-folder');
  if (pendingStage) {
    return `
      <div class="admin-card-normalize-row">
        <span class="admin-card-normalize-staged">${ICON_NORMALIZE} Normalize staged*</span>
        <button class="admin-card-normalize-undo-btn" data-normalize-action="undo-normalize">Undo</button>
      </div>`;
  }
  return `
    <div class="admin-card-normalize-row">
      <button class="admin-card-normalize-btn" data-normalize-action="open-modal">${ICON_NORMALIZE} Normalize folder…</button>
    </div>`;
}

// ── Card HTML ────────────────────────────────────────────────────────────────

export function renderCard(t) {
  const code = t.code;
  const mode = deriveMode(t);

  // Header: cover
  const coverHtml = t.coverUrl
    ? `<div class="admin-card-cover"><img src="${esc(t.coverUrl)}" alt="${esc(code)}" loading="lazy"></div>`
    : `<div class="admin-card-cover"><div class="cover-placeholder">${esc(code)}</div></div>`;

  // Header: reject badge
  const rejectedBadgeHtml = t.rejected
    ? '<span class="admin-card-reject-badge">⨯ REJECTED</span>'
    : '';

  // Header: titles
  const enText = t.titleEnglish || t.titleOriginalEn || '';
  const titleEnHtml = enText
    ? `<div class="admin-card-title">${esc(enText)}</div>`
    : (t.titleOriginal ? `<div class="admin-card-title">${esc(t.titleOriginal)}</div>` : '');
  const titleJaHtml = (enText && t.titleOriginal)
    ? `<div class="admin-card-title-ja">${esc(t.titleOriginal)}</div>`
    : '';

  // Header: studio / label meta line
  const metaParts = [];
  if (t.companyName || t.labelName) {
    const lp = [];
    if (t.companyName) lp.push(esc(t.companyName));
    if (t.labelName)   lp.push(`(${esc(t.labelName)})`);
    metaParts.push(lp.join(' '));
  }
  const metaLineHtml = metaParts.length > 0
    ? `<div class="admin-card-meta-line">${metaParts.join(' · ')}</div>`
    : '';

  // Date row
  let dateRowHtml = '';
  if (t.releaseDate) {
    dateRowHtml = `<div class="admin-card-date-row">${ICON_CAL}<span class="admin-card-date-label">Released</span><span class="admin-card-date-value">${esc(fmtDate(t.releaseDate))}</span></div>`;
  } else if (t.addedDate) {
    dateRowHtml = `<div class="admin-card-date-row">${ICON_CAL}<span class="admin-card-date-label">Added to library</span><span class="admin-card-date-value">${esc(fmtDate(t.addedDate))}</span></div>`;
  }

  // Header: cast
  let castHtml = '';
  if (t.actresses && t.actresses.length > 0) {
    const names = t.actresses.map(a => esc(a.name)).join(', ');
    castHtml = `<div class="admin-card-cast">${ICON_PERSON}<span class="admin-card-cast-names">${names}</span></div>`;
  } else if (t.actressName) {
    castHtml = `<div class="admin-card-cast">${ICON_PERSON}<span class="admin-card-cast-names">${esc(t.actressName)}</span></div>`;
  }

  // Header: tags
  const tags = t.tags || [];
  const tagsHtml = tags.length > 0
    ? `<div class="admin-card-tags">${tags.map(tagBadgeHtml).join('')}</div>`
    : '';

  // Header: grade + age pill
  const gradeHtml = t.grade ? gradeBadgeHtml(t.grade) : '';
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

  // Header: pending badge
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
        ${dateRowHtml}
        ${castHtml}
        ${tagsHtml}
        ${gradeAgeHtml}
      </div>
      ${pendingBadgeHtml}
    </div>`;

  // Flags row (§4.2)
  const effFav = effectiveFlagValue(code, 'flag-favorite', t.favorite);
  const effBm  = effectiveFlagValue(code, 'flag-bookmark', t.bookmark);
  const effRej = effectiveFlagValue(code, 'flag-reject',   t.rejected);

  const hasFavStage = !!state.findPendingStage(code, 'flag-favorite');
  const hasBmStage  = !!state.findPendingStage(code, 'flag-bookmark');
  const hasRejStage = !!state.findPendingStage(code, 'flag-reject');

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
    favBmDisabled = true;
    favBmTitle = 'title is rejected; clear reject first';
  }

  const favDisabled = favBmDisabled ? ' disabled' : '';
  const bmDisabled  = favBmDisabled ? ' disabled' : '';

  const favClasses = `admin-card-flag-btn fav-btn${effFav ? ' active' : ''}${hasFavStage ? ' staged' : ''}${favBmDisabled ? ' disabled' : ''}`;
  const bmClasses  = `admin-card-flag-btn bm-btn${effBm  ? ' active' : ''}${hasBmStage  ? ' staged' : ''}${favBmDisabled ? ' disabled' : ''}`;
  const rejClasses = `admin-card-flag-btn rej-btn${effRej ? ' active' : ''}${hasRejStage ? ' staged' : ''}`;

  const favTitle = favBmTitle || 'Favorite';
  const bmTitle  = favBmTitle || 'Bookmark';

  const flagsHtml = `
    <div class="admin-card-flags-row">
      <button class="${favClasses}" data-flag="favorite" title="${esc(favTitle)}"${favDisabled}>${ICON_FAV_LG} Favorite</button>
      <button class="${bmClasses}"  data-flag="bookmark" title="${esc(bmTitle)}"${bmDisabled}>${ICON_BM_LG} Bookmark</button>
      <button class="${rejClasses}" data-flag="reject"   title="Reject">${ICON_REJ_LG} Reject</button>
    </div>`;

  // No-content / loading / error banners (§4.6)
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

  // Section §4.3 / §4.4
  const locationEntries = t.locationEntries || [];
  const locationCount = locationEntries.length;
  let sectionHtml = '';
  if (mode === 'normal') {
    if (locationCount > 1) {
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
    const cachedContents = Object.prototype.hasOwnProperty.call(t, '_folderContents')
      ? t._folderContents
      : undefined;
    sectionHtml = renderFolderContents(code, cachedContents) + renderNormalizeButton(code);
  }

  // Error bar (failed stage)
  const failedStage = state.getStages(code).find(s => s.status === 'failed');
  const errorHtml = failedStage
    ? `<div class="admin-card-error-text">Error: ${esc(failedStage.error || 'Unknown error')}</div>`
    : '';

  // Commit / Cancel footer (§4.5)
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

  // §4.4 Lazy-fetch folder contents for single-location titles
  const locationEntries = titleData.locationEntries || [];
  const mode = deriveMode(titleData);
  if (!titleData.rejected && locationEntries.length === 1) {
    ensureFolderContents(code, titleData);
    if (mode === 'normal' || mode === 'cover-only') {
      attachFolderListeners(code, card, titleData);
    }
  }

  // §4.3 Lazy-fetch duplicate decisions + per-location videos in parallel
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

  // §4.3 Rich duplicate cards listeners
  if (locationEntries.length > 1) {
    attachDupCardListeners(code, card, titleData, renderCardInPlace);
  }

  // §4.4.1 Normalize folder button / undo
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
      const flag = btn.dataset.flag;
      const kind = `flag-${flag}`;

      const effFav = effectiveFlagValue(code, 'flag-favorite', titleData.favorite);
      const effBm  = effectiveFlagValue(code, 'flag-bookmark',  titleData.bookmark);
      const effRej = effectiveFlagValue(code, 'flag-reject',    titleData.rejected);

      const serverRejected = titleData.rejected;
      const cardMode = deriveMode(titleData);
      const isNoContent = cardMode === 'empty-folder' || cardMode === 'cover-only';
      if ((flag === 'favorite' || flag === 'bookmark') && (effRej || serverRejected || isNoContent)) return;

      const serverValue = flag === 'favorite' ? titleData.favorite
                        : flag === 'bookmark' ? titleData.bookmark
                        : titleData.rejected;

      const currentEff = flag === 'favorite' ? effFav
                       : flag === 'bookmark'  ? effBm
                       : effRej;

      const target = !currentEff;

      if (target === serverValue) {
        state.removePendingStage(code, kind);
      } else {
        state.addStage(code, kind, { target });

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
      await commitCard(code);
      renderCardInPlace(code);
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
