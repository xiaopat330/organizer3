// v2/enrichment/utils.js — shared helpers for the Enrichment Review module.

export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

export function formatRelative(isoStr) {
  if (!isoStr) return '—';
  try {
    const diff = Date.now() - new Date(isoStr).getTime();
    const days = Math.floor(diff / 86400000);
    if (days === 0) return 'Today';
    if (days === 1) return 'Yesterday';
    if (days < 30)  return `${days}d ago`;
    if (days < 365) return `${Math.floor(days / 30)}mo ago`;
    return `${Math.floor(days / 365)}y ago`;
  } catch { return isoStr; }
}

const RESOLVER_SOURCE_LABELS = {
  actress_filmography:   'Actress filmography',
  code_search_fallback:  'Code search',
  sentinel_short_circuit:'Short-circuit',
};

export function resolverSourceLabel(src) {
  return RESOLVER_SOURCE_LABELS[src] || src || '—';
}

// ── Cover lightbox ────────────────────────────────────────────────────────────
// Self-contained: injects a lightbox <div> into document.body on first call.

let _lightboxEl   = null;
let _lightboxImg  = null;

function ensureLightbox() {
  if (_lightboxEl) return;
  _lightboxEl = document.createElement('div');
  _lightboxEl.className = 'er-lightbox';
  _lightboxEl.setAttribute('role', 'dialog');
  _lightboxEl.setAttribute('aria-modal', 'true');
  _lightboxEl.style.display = 'none';

  _lightboxImg = document.createElement('img');
  _lightboxImg.className = 'er-lightbox-img';
  _lightboxImg.alt = '';

  _lightboxEl.appendChild(_lightboxImg);
  document.body.appendChild(_lightboxEl);

  _lightboxEl.addEventListener('click', closeLightbox);
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && _lightboxEl.style.display !== 'none') closeLightbox();
  });
}

export function openLightbox(coverUrl) {
  ensureLightbox();
  _lightboxImg.src = coverUrl;
  _lightboxEl.style.display = '';
}

export function closeLightbox() {
  if (!_lightboxEl) return;
  _lightboxEl.style.display = 'none';
  _lightboxImg.src = '';
}
