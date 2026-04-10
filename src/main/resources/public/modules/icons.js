import { esc } from './utils.js';

// ── SVG icon constants ────────────────────────────────────────────────────
export const ICON_FAV_SM = '<svg class="card-fav-icon" viewBox="0 0 24 24" width="12" height="12"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>';
export const ICON_BM_SM     = '<svg class="card-bm-icon" viewBox="0 0 24 24" width="12" height="12"><path d="M6 2h12a1 1 0 0 1 1 1v18.5a.5.5 0 0 1-.8.4L12 17.5 5.8 21.9a.5.5 0 0 1-.8-.4V3a1 1 0 0 1 1-1z"/></svg>';
export const ICON_BM_SM_OFF = '<svg class="card-bm-icon card-bm-off" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 2h12a1 1 0 0 1 1 1v18.5a.5.5 0 0 1-.8.4L12 17.5 5.8 21.9a.5.5 0 0 1-.8-.4V3a1 1 0 0 1 1-1z"/></svg>';
export const ICON_FAV_LG = '<svg viewBox="0 0 24 24" width="22" height="22"><polygon class="star-icon" points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>';
export const ICON_BM_LG  = '<svg viewBox="0 0 24 24" width="22" height="22"><path class="bookmark-icon" d="M6 2h12a1 1 0 0 1 1 1v18.5a.5.5 0 0 1-.8.4L12 17.5 5.8 21.9a.5.5 0 0 1-.8-.4V3a1 1 0 0 1 1-1z"/></svg>';
export const ICON_REJ_LG = '<svg viewBox="0 0 24 24" width="22" height="22"><path class="reject-icon" d="M6 6 L18 18 M18 6 L6 18"/></svg>';

export function titleCodeClass(favorite, bookmark) {
  if (favorite && bookmark) return 'title-code title-code-fav title-code-bold';
  if (favorite) return 'title-code title-code-fav';
  if (bookmark) return 'title-code title-code-bm title-code-bold';
  return 'title-code';
}

export function gradeBadgeHtml(grade) {
  if (!grade) return '';
  return `<span class="grade-badge" data-grade="${esc(grade)}">${esc(grade)}</span>`;
}

export function tagHue(tag) {
  let h = 0;
  for (let i = 0; i < tag.length; i++) h = (h * 31 + tag.charCodeAt(i)) & 0xffff;
  return h % 360;
}

export function tagBadgeHtml(tag) {
  const hue = tagHue(tag);
  const style = `color:hsl(${hue},65%,65%);background:hsl(${hue},40%,12%);border:1px solid hsl(${hue},50%,38%)`;
  return `<span class="tag-badge" style="${style}">${esc(tag)}</span>`;
}
