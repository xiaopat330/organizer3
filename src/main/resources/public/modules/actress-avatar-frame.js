import { esc } from './utils.js';
import { gradeBadgeHtml } from './icons.js';

// Heroicons user-circle outline (24×24 viewBox).
const USER_CIRCLE_SVG = `<svg class="avatar-placeholder-icon" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1" stroke="currentColor" aria-hidden="true">
  <path stroke-linecap="round" stroke-linejoin="round" d="M17.982 18.725A7.488 7.488 0 0 0 12 15.75a7.488 7.488 0 0 0-5.982 2.975m11.963 0a9 9 0 1 0-11.963 0m11.963 0A8.966 8.966 0 0 1 12 21a8.966 8.966 0 0 1-5.982-2.275M15 9.75a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" />
</svg>`;

/**
 * Returns an HTML string for the actress avatar frame.
 *
 * @param {object} opts
 * @param {number} opts.actressId
 * @param {string|null} opts.localAvatarUrl   Resolved avatar URL (custom > enriched), or null.
 * @param {boolean}     opts.hasCustomAvatar  True when a user-curated custom avatar is set.
 * @param {string|null} opts.derivedGrade     Grade string for the overlay badge, or null.
 * @param {'always'|'empty-only'|'never'} opts.clickable  Click behaviour.
 */
export function renderAvatarFrame({ actressId, localAvatarUrl, hasCustomAvatar, derivedGrade, clickable }) {
  const isEmpty = !localAvatarUrl;
  const isClickable = clickable === 'always' || (clickable === 'empty-only' && isEmpty);

  const classes = [
    'actress-avatar-frame',
    isEmpty ? 'actress-avatar-frame--empty' : '',
    isClickable ? 'actress-avatar-frame--clickable' : '',
  ].filter(Boolean).join(' ');

  const inner = isEmpty
    ? `<div class="avatar-placeholder-tile">
         ${USER_CIRCLE_SVG}
         <span class="avatar-placeholder-caption">add profile image</span>
       </div>`
    : `<img class="detail-actress-avatar" src="${esc(localAvatarUrl)}" alt="avatar" loading="lazy">
       ${derivedGrade ? `<div class="cover-grade">${gradeBadgeHtml(derivedGrade)}</div>` : ''}`;

  return `<div class="${classes}"
    data-actress-id="${actressId}"
    data-has-custom-avatar="${hasCustomAvatar ? '1' : '0'}"
    data-clickable="${esc(clickable)}">${inner}</div>`;
}

/**
 * Attaches click listeners to all `.actress-avatar-frame--clickable` elements
 * inside `container`.
 *
 * @param {Element} container
 * @param {function(actressId: number, hasCustomAvatar: boolean): void} onOpen
 */
export function attachAvatarFrameListeners(container, onOpen) {
  container.querySelectorAll('.actress-avatar-frame--clickable').forEach(el => {
    el.addEventListener('click', () => {
      const actressId      = Number(el.dataset.actressId);
      const hasCustomAvatar = el.dataset.hasCustomAvatar === '1';
      onOpen(actressId, hasCustomAvatar);
    });
  });
}
