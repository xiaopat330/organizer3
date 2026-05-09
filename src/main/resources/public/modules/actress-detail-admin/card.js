// Edit Card renderer — Phase 2f: reduced card modes (no-content + rejected).
//
// Renders header (§4.1) + flags row (§4.2) + optional no-content banner
// + section stubs (§4.3 / §4.4) only in normal mode
// + Commit/Cancel footer (§4.5).
//
// Mode is derived from server-side values (not staged state):
//   'rejected'   — t.rejected === true
//   'no-content' — videoCount === 0 (and not rejected)
//   'normal'     — everything else
//
// Re-render strategy: renderCardInPlace(code) replaces a single card's
// outerHTML in-place, then re-attaches event listeners via attachCardListeners.
// renderPage in index.js calls attachCardListeners after building innerHTML.

import { esc, ageAtDate, agePillTier } from '../utils.js';
import { ICON_FAV_LG, ICON_BM_LG, ICON_REJ_LG, gradeBadgeHtml, tagBadgeHtml } from '../icons.js';
import * as state from './state.js';
import { commitCard, cancelCard } from './commit.js';

// ── Helpers ─────────────────────────────────────────────────────────────────

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

// ── Card HTML ────────────────────────────────────────────────────────────────

export function renderCard(t) {
  const code = t.code;

  // ── Card mode (server-side; not stage-aware) ──────────────────────────
  const mode = t.rejected
    ? 'rejected'
    : (typeof t.videoCount === 'number' && t.videoCount === 0)
      ? 'no-content'
      : 'normal';

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

  // Fav and BM are disabled when effectively rejected or in no-content/rejected mode
  // Tooltip text differs by mode.
  let favBmDisabled = false;
  let favBmTitle = '';
  if (mode === 'rejected') {
    favBmDisabled = true;
    favBmTitle = 'title is rejected; clear reject first';
  } else if (mode === 'no-content') {
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

  // ── No-content banner (§4.6) — only in no-content mode ──────────────
  const noContentBannerHtml = mode === 'no-content' ? `
    <div class="admin-card-no-content-banner">
      ⚠ NO CONTENT — no video files
      <button type="button" class="admin-card-whats-this-btn" aria-expanded="false">[ what's this? ]</button>
      <div class="admin-card-whats-this-body" hidden>
        This title's folder has no video files. The Admin tab can't fix this —
        a future Tools feature will let you review and clean up no-content
        titles across the library. Use the Reject button to flag this title
        for that future tool to find.
      </div>
    </div>` : '';

  // ── Section stubs (§4.3 / §4.4) — suppressed in rejected/no-content modes
  const locationCount = (t.locations || []).length;
  let sectionStubHtml = '';
  if (mode === 'normal') {
    if (locationCount > 1) {
      sectionStubHtml = `<div class="admin-card-section-stub">Duplicate folder triage — Phase 3 (${locationCount} locations)</div>`;
    } else if (locationCount === 1) {
      sectionStubHtml = `<div class="admin-card-section-stub">Folder contents — Phase 4</div>`;
    }
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

  const modeClass = mode === 'rejected' ? ' admin-edit-card-rejected'
                  : mode === 'no-content' ? ' admin-edit-card-no-content'
                  : '';

  return `
    <div class="admin-edit-card${modeClass}" data-code="${esc(code)}">
      ${headerHtml}
      ${noContentBannerHtml}
      ${flagsHtml}
      ${sectionStubHtml}
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
      const isNoContent = typeof titleData.videoCount === 'number' && titleData.videoCount === 0;
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
