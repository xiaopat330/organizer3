// v2/workflow/utils.js — shared utilities for the Workflow surface.

export function esc(s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

export function humanizeState(state, reason) {
  switch (state) {
    case 'queued':              return 'queued';
    case 'fetching':            return 'fetching';
    case 'ambiguous':           return 'ambiguous';
    case 'queued_for_ai':       return 'queued for AI';
    case 'judging':             return 'judging…';
    case 'split_decision':      return 'split decision';
    case 'partial_vote':        return 'single voter';
    case 'no_verdict':          return 'no verdict';
    case 'other_intervention':  return reason ? reason.replace(/_/g, ' ') : 'needs intervention';
    default:                    return state || 'unknown';
  }
}

export function openLightbox(src) {
  const overlay = document.createElement('div');
  Object.assign(overlay.style, {
    position: 'fixed', inset: '0', background: 'rgba(0,0,0,0.82)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    zIndex: 1000, cursor: 'zoom-out',
  });
  const img = document.createElement('img');
  img.src = src;
  img.style.maxWidth  = '90vw';
  img.style.maxHeight = '90vh';
  img.style.objectFit = 'contain';
  overlay.appendChild(img);
  overlay.addEventListener('click', () => overlay.remove());
  document.body.appendChild(overlay);
}
