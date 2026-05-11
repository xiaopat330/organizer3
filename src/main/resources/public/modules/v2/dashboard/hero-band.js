/* ─────────────────────────────────────────────────────────────────────
   Shared primitive: renderHeroBand(opts) → HTMLElement

   Renders a hero-band section reusable across Home, Actresses, and
   Titles dashboards.

   Options:
     kind          : 'title' | 'actress'
                     'title'   → 3:2 cover (.hero-cover inside .hero-band-title)
                     'actress' → 7:10 portrait (.hero-portrait)
     eyebrow       : string  — small label above name (e.g. tier, code)
     eyebrowClass  : string? — extra CSS class on eyebrow span (e.g. 'act-tier-goddess')
     name          : string  — primary name; suppressed when === eyebrow
     primaryImage  : string? — preferred image URL
     fallbackImages: string[] — used if primaryImage is absent/falsy
                     when a fallback is used, the aspect switches to 3:2
     count         : string? — sub-line (e.g. "12 titles", "actress · 2019")
     countLabel    : string? — extra label after count (currently unused for auto-count)
     badgeHtml     : string? — raw HTML inserted after count (e.g. grade badge)
     openHref      : string  — href for the Open button
     dataActressId : string? — sets data-actress-id on the section element
     dataCode      : string? — sets data-code on the section element

   Always returns an HTMLElement (section.hero-band).
   ───────────────────────────────────────────────────────────────────── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function monogram(name) {
  const parts = String(name).trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0][0].toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

/**
 * @param {object} opts
 * @param {'title'|'actress'} opts.kind
 * @param {string} [opts.eyebrow]
 * @param {string} [opts.eyebrowClass]
 * @param {string} [opts.name]
 * @param {string} [opts.primaryImage]
 * @param {string[]} [opts.fallbackImages]
 * @param {string} [opts.count]
 * @param {string} [opts.countLabel]
 * @param {string} [opts.badgeHtml]
 * @param {string} [opts.openHref]
 * @param {string} [opts.dataActressId]
 * @param {string} [opts.dataCode]
 * @returns {HTMLElement}
 */
export function renderHeroBand({
  kind = 'title',
  eyebrow = '',
  eyebrowClass = '',
  name = '',
  primaryImage = null,
  fallbackImages = [],
  count = '',
  countLabel = '',
  badgeHtml = '',
  openHref = '#',
  dataActressId = null,
  dataCode = null,
} = {}) {
  // Resolve image: prefer primaryImage, then first fallback
  const resolvedImage = primaryImage || (fallbackImages.length > 0 ? fallbackImages[0] : null);
  // If we fell back to a cover image (and kind=actress), switch to cover aspect
  const usingFallbackCover = !primaryImage && fallbackImages.length > 0 && kind === 'actress';

  // Portrait headshots use center-top; full DVD cover scans crop to right (front).
  const isCoverImage = kind === 'title' || usingFallbackCover;
  const bgPos = isCoverImage ? 'right center' : 'center top';
  const imgStyle = resolvedImage
    ? `background-image:url('${esc(resolvedImage)}');background-size:cover;background-position:${bgPos}`
    : '';
  const monogramHtml = !resolvedImage
    ? `<div class="dash-hero-monogram">${esc(monogram(name || eyebrow))}</div>`
    : '';

  let imageEl;
  if (kind === 'actress' && !usingFallbackCover) {
    // Tall 7:10 portrait
    imageEl = `<div class="hero-portrait" ${imgStyle ? `style="${imgStyle}"` : ''}>${monogramHtml}</div>`;
  } else {
    // 3:2 title cover (or actress fallback to cover)
    const coverClass = usingFallbackCover
      ? 'hero-cover dash-hero-cover-fallback'
      : 'hero-cover';
    imageEl = `<div class="${coverClass}" ${imgStyle ? `style="${imgStyle}"` : ''}>${monogramHtml}</div>`;
  }

  // Eyebrow
  const eyebrowHtml = eyebrow
    ? `<div class="hero-eyebrow${eyebrowClass ? ' ' + esc(eyebrowClass) : ''}">${esc(eyebrow)}</div>`
    : '';

  // Name — suppress if identical to eyebrow
  const showName = name && name !== eyebrow;
  const nameHtml = showName ? `<div class="hero-name">${esc(name)}</div>` : '';

  // Count line
  const countHtml = (count || badgeHtml)
    ? `<div class="hero-stats">${count ? `<span>${esc(count)}</span>` : ''}${badgeHtml || ''}</div>`
    : '';

  // Build section element
  const section = document.createElement('section');
  section.className = `hero-band${kind === 'title' ? ' hero-band-title' : ''} dash-hero`;
  if (dataActressId != null) section.dataset.actressId = dataActressId;
  if (dataCode != null) section.dataset.code = dataCode;

  section.innerHTML = `
    ${imageEl}
    <div class="hero-content">
      ${eyebrowHtml}
      ${nameHtml}
      ${countHtml}
      <div class="hero-actions">
        <a class="btn primary" href="${esc(openHref)}">Open</a>
      </div>
    </div>
  `;

  return section;
}
