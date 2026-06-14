/* ─────────────────────────────────────────────────────────────────────
   Actress card primitive — renderActressCard(a, opts)

   Accepted opts:
     variant : 'standard' | 'compact'   (default: 'standard')

   Field-shape contract (all optional; fallbacks applied):
     a.id, a.slug
     a.localAvatarUrl | a.profileImagePath → portrait image
     a.displayName | a.canonicalName | a.stageName | a.name | a.slug → name
     a.tier                               → tier badge (standard only)
     a.titleCount                         → count meta (standard only)

   The returned element carries:
     el.dataset.actressId = a.id
   Callers may add modifier classes to the returned element themselves.

   CSS note: tier is set via data-tier attribute on the card element.
   The `.acv2-card[data-tier]` selectors in cards.css provide tier colouring.
   The tier badge text is lowercased (e.g. 'goddess').
   ───────────────────────────────────────────────────────────────────── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function avatarUrl(a) {
  if (a.localAvatarUrl) return a.localAvatarUrl;
  if (a.profileImagePath) return `/api/actress-image/${encodeURIComponent(a.slug || a.id)}`;
  return null;
}

function displayName(a) {
  return a.displayName || a.canonicalName || a.stageName || a.name || a.slug || '';
}

function monogram(name) {
  // First letter of first two words, uppercased
  const parts = String(name).trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0][0].toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

/**
 * Create and return an actress card <a> element.
 * @param {object} a — actress data object
 * @param {object} [opts]
 * @param {string} [opts.variant='standard'] — 'standard' | 'compact'
 * @returns {HTMLAnchorElement}
 */
export function renderActressCard(a, opts = {}) {
  const { variant = 'standard' } = opts;
  const compact = variant === 'compact';

  const name    = displayName(a);
  const tier    = (a.tier || '').toLowerCase();
  const count   = !compact && a.titleCount != null ? `${a.titleCount} titles` : '';

  // New design: always prefer a random title cover as the main image. If a
  // profile picture exists, overlay it as a small crisp thumbnail (bottom-right)
  // rather than upscaling it to fill the portrait.
  const profileSrc = avatarUrl(a);
  const covers     = Array.isArray(a.coverUrls) ? a.coverUrls : [];
  const cover      = covers.length > 0
    ? covers[Math.floor(Math.random() * covers.length)]
    : null;

  const el = document.createElement('a');
  el.className = 'acv2-card';
  if (compact) el.classList.add('acv2-card--compact');
  el.href = `/v2-actress-detail.html?id=${encodeURIComponent(a.id)}`;
  if (a.id) el.dataset.actressId = a.id;
  if (tier) el.dataset.tier = tier;

  let portraitStyle  = '';
  let portraitClass  = 'acv2-portrait';
  let monogramHtml   = '';
  let overlayHtml    = '';

  if (cover) {
    // Cover as main image, cropped right-center (actress on the right).
    portraitStyle = `background-image:url('${esc(cover)}');background-size:cover;background-position:right center`;
    if (profileSrc) {
      // Profile pic overlaid as a crisp thumbnail — no monogram, no scrim.
      portraitClass += ' acv2-portrait--cover';
      overlayHtml = `<img class="acv2-portrait-overlay" src="${esc(profileSrc)}" alt="" loading="lazy">`;
    } else {
      // No profile pic — monogram over the cover, with readability scrim.
      portraitClass += ' acv2-portrait--cover-fallback';
      monogramHtml = `<span class="acv2-monogram">${esc(monogram(name))}</span>`;
    }
  } else if (profileSrc) {
    // No covers at all — last-resort full-background profile pic (upscaled).
    portraitStyle = `background-image:url('${esc(profileSrc)}');background-size:cover;background-position:center top`;
  } else {
    // Neither — gradient + monogram.
    monogramHtml = `<span class="acv2-monogram">${esc(monogram(name))}</span>`;
  }

  if (compact) {
    el.innerHTML = `
      <div class="${portraitClass}" ${portraitStyle ? `style="${portraitStyle}"` : ''}>
        ${monogramHtml}
        ${overlayHtml}
      </div>
      <div class="acv2-name">${esc(name)}</div>
    `;
  } else {
    const tierHtml = tier
      ? `<span class="acv2-tier-badge acv2-tier-${tier}">${esc(tier)}</span>`
      : '';
    el.innerHTML = `
      <div class="${portraitClass}" ${portraitStyle ? `style="${portraitStyle}"` : ''}>
        ${monogramHtml}
        ${tierHtml}
        ${overlayHtml}
      </div>
      <div class="acv2-name">${esc(name)}</div>
      ${count ? `<div class="acv2-meta">${esc(count)}</div>` : ''}
    `;
  }

  return el;
}
