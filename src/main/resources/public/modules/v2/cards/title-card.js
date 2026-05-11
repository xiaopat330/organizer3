/* ─────────────────────────────────────────────────────────────────────
   Title card primitive — renderTitleCard(t, opts)

   Accepted opts:
     variant  : 'standard' | 'compact'   (default: 'standard')
     watched  : bool — show watched checkmark (from t.lastWatchedAt)
     aging    : bool — append aging badge onto the cover div

   Field-shape contract (all optional; fallbacks applied):
     t.code
     t.coverUrl | t.coverPath         → cover image
     t.titleEnglish | t.titleOriginalEn | t.titleOriginal | t.normalizedTitle
       | t.titleEn | t.titleJa | t.title  → display name
     t.actressName | t.actresses[0].name  → actress name (standard only)
     t.releaseDate | t.addedDate          → date (year extracted)
     t.grade                              → grade badge (standard only)
     t.lastWatchedAt                      → watched indicator if opts.watched
     t.addedDate                          → aging badge if opts.aging

   The returned element always carries:
     el.dataset.code = t.code
   Callers may add context-specific modifier classes (e.g. 'tit-dash-card')
   to the returned element themselves.
   ───────────────────────────────────────────────────────────────────── */

const COVER_ROOT = '/covers';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function coverUrl(t) {
  const code = t.code || '';
  return t.coverUrl
    || (t.coverPath ? `${COVER_ROOT}/${encodeURIComponent(t.coverPath)}` : null)
    || `/api/cover/${encodeURIComponent(code)}`;
}

function displayName(t) {
  return t.titleEnglish
    || t.titleOriginalEn
    || t.titleOriginal
    || t.normalizedTitle
    || t.titleEn
    || t.titleJa
    || t.title
    || t.code
    || '';
}

function agingLabel(addedDate) {
  if (!addedDate) return null;
  const d = new Date(addedDate + (addedDate.length === 10 ? 'T00:00:00' : ''));
  if (isNaN(d)) return null;
  const days = Math.floor((Date.now() - d.getTime()) / 86400000);
  if (days <= 1)  return 'New today';
  if (days <= 3)  return 'New this week';
  if (days <= 7)  return '< 1 week';
  if (days <= 14) return '< 2 weeks';
  if (days <= 30) return '< 1 month';
  return null;
}

/**
 * Create and return a title card <a> element.
 * @param {object} t — title data object
 * @param {object} [opts]
 * @param {string} [opts.variant='standard'] — 'standard' | 'compact'
 * @param {boolean} [opts.watched=false]
 * @param {boolean} [opts.aging=false]
 * @param {boolean} [opts.showActress=false] — show actress line even in compact variant
 * @returns {HTMLAnchorElement}
 */
export function renderTitleCard(t, opts = {}) {
  const { variant = 'standard', watched = false, aging = false, showActress = false } = opts;
  const compact = variant === 'compact';

  const code    = t.code || '';
  const cover   = coverUrl(t);
  const name    = displayName(t);
  // Show actress in standard variant always; in compact only if showActress=true
  const actress = (!compact || showActress)
    ? (t.actressName || (t.actresses && t.actresses.length ? t.actresses[0].name : ''))
    : '';
  const date    = t.releaseDate || t.addedDate || '';
  const grade   = !compact ? (t.grade || '') : '';
  const isWatched = watched && !!t.lastWatchedAt;

  const gradeHtml = grade
    ? `<span class="grade-badge grade-${esc(grade.charAt(0))}">${esc(grade)}</span>`
    : '';

  const watchedHtml = isWatched
    ? `<span class="tcv2-watched-mark" title="Watched">✓</span>`
    : '';

  const el = document.createElement('a');
  el.className = 'tcv2-card';
  el.href = `/v2-title-detail.html?code=${encodeURIComponent(code)}`;
  el.dataset.code = code;

  if (compact) {
    el.innerHTML = `
      <div class="tcv2-cover${cover ? '' : ' tcv2-cover--empty'}"
           style="${cover ? `background-image:url('${esc(cover)}');background-size:cover;background-position:right center` : ''}">
      </div>
      <div class="tcv2-code">${esc(code)}</div>
      <div class="tcv2-meta">
        ${actress ? `<span>${esc(actress)}</span>` : ''}
        ${actress && date ? '<span class="tcv2-dot"></span>' : ''}
        ${date ? `<span class="tcv2-year">${esc(String(date).slice(0, 4))}</span>` : ''}
      </div>
    `;
  } else {
    el.innerHTML = `
      <div class="tcv2-cover${cover ? '' : ' tcv2-cover--empty'}"
           style="${cover ? `background-image:url('${esc(cover)}');background-size:cover;background-position:right center` : ''}">
        ${gradeHtml ? `<div class="tcv2-status">${gradeHtml}</div>` : ''}
        ${watchedHtml}
      </div>
      <div class="tcv2-code">${esc(code)}</div>
      <div class="tcv2-name">${esc(name)}</div>
      <div class="tcv2-meta">
        ${actress ? `<span>${esc(actress)}</span>` : ''}
        ${actress && date ? '<span class="tcv2-dot"></span>' : ''}
        ${date ? `<span class="tcv2-year">${esc(String(date).slice(0, 4))}</span>` : ''}
      </div>
    `;
  }

  if (aging && t.addedDate) {
    const label = agingLabel(t.addedDate);
    if (label) {
      const badge = document.createElement('div');
      badge.className = 'tcv2-aging';
      badge.textContent = label;
      const coverEl = el.querySelector('.tcv2-cover');
      (coverEl || el).appendChild(badge);
    }
  }

  return el;
}
