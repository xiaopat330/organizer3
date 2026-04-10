import { esc, splitName, renderDateRange } from './utils.js';
import { ICON_FAV_SM, ICON_BM_SM, ICON_BM_SM_OFF, titleCodeClass, gradeBadgeHtml, tagBadgeHtml } from './icons.js';
import { activeIntervals, activeObservers } from './grid.js';

// ── Callbacks registered by app.js after all modules load ─────────────────
// Avoids circular imports between cards ↔ title-detail and cards ↔ actress-detail.
let _openTitleDetail  = () => {};
let _openActressDetail = () => {};
export function initCardCallbacks(openTitle, openActress) {
  _openTitleDetail   = openTitle;
  _openActressDetail = openActress;
}

// ── Helpers ───────────────────────────────────────────────────────────────
export function renderLocation(path) {
  const sep = path.lastIndexOf('/');
  if (sep < 0) return `<span class="title-location-folder">${esc(path)}</span>`;
  return `<span class="title-location-prefix">${esc(path.slice(0, sep + 1))}</span><span class="title-location-folder">${esc(path.slice(sep + 1))}</span>`;
}

// ── Title card ────────────────────────────────────────────────────────────
export function makeTitleCard(t) {
  const card = document.createElement('div');
  card.className = 'card';
  card.dataset.code = t.code;

  const coverHtml = t.coverUrl
    ? `<div class="cover-wrap"><img class="cover-img" src="${esc(t.coverUrl)}" alt="${esc(t.code)}" loading="lazy"></div>`
    : `<div class="cover-wrap"><div class="cover-placeholder">${esc(t.code)}</div></div>`;

  let actressHtml;
  if (t.actresses && t.actresses.length > 1) {
    const names = t.actresses.map(a => {
      const { first: fn, last: ln } = splitName(a.name);
      const nameHtml = ln
        ? `<span class="ticker-first">${esc(fn)}</span> <span class="ticker-last">${esc(ln)}</span>`
        : `<span class="ticker-first">${esc(fn)}</span>`;
      return `<a class="actress-link ticker-name" href="#" data-actress-id="${a.id}">${nameHtml}</a>`;
    });
    const tickerContent = names.join('<span class="ticker-sep">, </span>');
    actressHtml = `<div class="actress-name actress-ticker">
      <div class="ticker-track">${tickerContent}${tickerContent}</div>
    </div>`;
  } else if (t.actressName) {
    const { first: fn, last: ln } = splitName(t.actressName);
    const tierHtml   = t.actressTier
      ? ` <span class="tier-badge tier-${esc(t.actressTier)}">${esc(t.actressTier.toLowerCase())}</span>` : '';
    const nameInner  = `<span class="card-actress-first">${esc(fn)}</span>${ln ? `<span class="card-actress-last"> ${esc(ln)}</span>` : ''}`;
    actressHtml = t.actressId
      ? `<div class="actress-name"><a class="actress-link" href="#" data-actress-id="${t.actressId}">${nameInner}</a>${tierHtml}</div>`
      : `<div class="actress-name">${nameInner}${tierHtml}</div>`;
  } else {
    actressHtml = `<div class="actress-name unknown">—</div>`;
  }

  const titleEnHtml = t.titleEnglish  ? `<div class="title-en">${esc(t.titleEnglish)}</div>`  : '';
  const titleJaHtml = t.titleOriginal ? `<div class="title-ja">${esc(t.titleOriginal)}</div>` : '';

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
    ? `<div class="title-meta-line">${metaParts.join(' · ')}</div>`
    : '';

  const tags = t.tags || [];
  const tagsHtml = tags.length > 0
    ? `<div class="title-tags">${tags.map(tagBadgeHtml).join('')}</div>`
    : '';

  const bmIconHtml = t.bookmark ? ICON_BM_SM : ICON_BM_SM_OFF;
  const favIcon    = t.favorite ? ICON_FAV_SM : '';
  const titleCodeHtml = `<div class="${titleCodeClass(t.favorite, t.bookmark)}"><button type="button" class="card-bm-btn${t.bookmark ? ' card-bm-active' : ''}">${bmIconHtml}</button>${favIcon}<span class="title-code-text">${esc(t.code)}</span>${gradeBadgeHtml(t.grade)}</div>`;

  const watchedHtml = t.lastWatchedAt
    ? `<div class="card-watched">watched ${timeAgoShort(t.lastWatchedAt)}${t.watchCount > 1 ? ` (${t.watchCount}x)` : ''}</div>`
    : '';

  const visitedHtml = t.visitCount > 0
    ? `<div class="title-card-visited">${t.visitCount === 1 ? '1 view' : `${t.visitCount} views`}${t.lastVisitedAt ? ` · ${timeAgoShort(t.lastVisitedAt)}` : ''}</div>`
    : '';

  card.innerHTML = `${coverHtml}<div class="card-info">${titleCodeHtml}${actressHtml}${titleEnHtml}${titleJaHtml}${metaLineHtml}${visitedHtml}${tagsHtml}${watchedHtml}</div>`;

  // Bookmark toggle — optimistic UI, debounced API call
  let bookmarkState = !!t.bookmark;
  let bookmarkTimer = null;
  const bmBtn = card.querySelector('.card-bm-btn');
  const codeEl = card.querySelector('.title-code');
  bmBtn.addEventListener('click', e => {
    e.preventDefault();
    e.stopPropagation();
    bookmarkState = !bookmarkState;
    bmBtn.innerHTML = bookmarkState ? ICON_BM_SM : ICON_BM_SM_OFF;
    bmBtn.classList.toggle('card-bm-active', bookmarkState);
    const isFav = !!codeEl.querySelector('.card-fav-icon');
    codeEl.className = titleCodeClass(isFav, bookmarkState);
    if (bookmarkTimer) clearTimeout(bookmarkTimer);
    bookmarkTimer = setTimeout(() => {
      bookmarkTimer = null;
      fetch(`/api/titles/${encodeURIComponent(t.code)}/bookmark?value=${bookmarkState}`, { method: 'POST' })
        .catch(err => console.error('bookmark toggle failed', err));
    }, 2000);
  });

  card.querySelectorAll('.actress-link').forEach(link => {
    link.addEventListener('click', e => {
      e.preventDefault();
      e.stopPropagation();
      _openActressDetail(Number(link.dataset.actressId));
    });
  });
  card.addEventListener('click', () => _openTitleDetail(t));
  return card;
}

/**
 * Compact title card for dashboard history modules (Recently Viewed).
 * Shows cover + title code + actress name + "visited Xh ago" aging label.
 * Omits tags, label, date, grade. Roughly 60% standard card height.
 */
export function makeCompactTitleCard(t) {
  const card = document.createElement('div');
  card.className = 'card card-compact';
  card.dataset.code = t.code;

  const coverHtml = t.coverUrl
    ? `<div class="cover-wrap"><img class="cover-img" src="${esc(t.coverUrl)}" alt="${esc(t.code)}" loading="lazy"></div>`
    : `<div class="cover-wrap"><div class="cover-placeholder">${esc(t.code)}</div></div>`;

  const favIcon = t.favorite ? ICON_FAV_SM : '';
  const titleCodeHtml = `<div class="${titleCodeClass(t.favorite, t.bookmark)}">${favIcon}<span class="title-code-text">${esc(t.code)}</span></div>`;

  let actressHtml = '';
  if (t.actressName) {
    const { first: fn, last: ln } = splitName(t.actressName);
    const nameInner = `<span class="card-actress-first">${esc(fn)}</span>${ln ? `<span class="card-actress-last"> ${esc(ln)}</span>` : ''}`;
    actressHtml = `<div class="actress-name actress-name-compact">${nameInner}</div>`;
  }

  const visitedHtml = t.lastVisitedAt
    ? `<div class="title-card-visited-compact">visited ${timeAgoShort(t.lastVisitedAt)}</div>`
    : '';

  card.innerHTML = `${coverHtml}<div class="card-info card-info-compact">${titleCodeHtml}${actressHtml}${visitedHtml}</div>`;
  card.addEventListener('click', () => _openTitleDetail(t));
  return card;
}

/**
 * Returns a short relative aging label for a date string (addedDate / releaseDate).
 * Formats: "today", "Xd ago", "Xmo ago", "Xy ago".
 */
export function agingLabel(dateStr) {
  if (!dateStr) return '';
  const d = new Date(dateStr);
  if (isNaN(d.getTime())) return '';
  const days = Math.floor((Date.now() - d.getTime()) / 86400000);
  if (days < 1) return 'today';
  if (days === 1) return '1d ago';
  if (days < 30) return `${days}d ago`;
  const months = Math.floor(days / 30);
  if (months < 12) return months === 1 ? '1mo ago' : `${months}mo ago`;
  const years = Math.floor(days / 365);
  return years === 1 ? '1y ago' : `${years}y ago`;
}

// timeAgo used on cards only needs relative display; full version lives in title-detail.js
function timeAgoShort(isoString) {
  const seconds = Math.floor((Date.now() - new Date(isoString)) / 1000);
  if (seconds < 60)  return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60)  return minutes === 1 ? '1 min ago' : `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24)    return hours === 1 ? '1 hour ago' : `${hours} hours ago`;
  const days = Math.floor(hours / 24);
  if (days < 14)     return days === 1 ? '1 day ago' : `${days} days ago`;
  const weeks = Math.floor(days / 7);
  if (weeks < 9)     return weeks === 1 ? '1 week ago' : `${weeks} weeks ago`;
  const months = Math.floor(days / 30);
  if (months <= 3)   return months === 1 ? '1 month ago' : `${months} months ago`;
  return 'more than 3 months ago';
}

export function updateCardIndicators(code, favorite, bookmark) {
  const card = document.querySelector(`.card[data-code="${CSS.escape(code)}"]`);
  if (!card) return;
  const codeEl = card.querySelector('.title-code');
  if (!codeEl) return;

  // Update bookmark button icon + state
  const bmBtn = codeEl.querySelector('.card-bm-btn');
  if (bmBtn) {
    bmBtn.innerHTML = bookmark ? ICON_BM_SM : ICON_BM_SM_OFF;
    bmBtn.classList.toggle('card-bm-active', bookmark);
  }

  // Add or remove the favorite star icon
  const existingFavIcon = codeEl.querySelector('.card-fav-icon');
  if (favorite && !existingFavIcon) {
    const tmp = document.createElement('span');
    tmp.innerHTML = ICON_FAV_SM;
    const icon = tmp.firstChild;
    bmBtn ? bmBtn.insertAdjacentElement('afterend', icon) : codeEl.prepend(icon);
  } else if (!favorite && existingFavIcon) {
    existingFavIcon.remove();
  }

  codeEl.className = titleCodeClass(favorite, bookmark);
}

// ── Actress card ──────────────────────────────────────────────────────────
export function actressFlagIconsHtml(a) {
  if (a.rejected) {
    return '<svg class="card-rej-icon" viewBox="0 0 24 24" width="12" height="12"><path d="M6 6 L18 18 M18 6 L6 18"/></svg>';
  }
  const bmIconHtml  = a.bookmark ? ICON_BM_SM : ICON_BM_SM_OFF;
  const favIconHtml = a.favorite ? ICON_FAV_SM : '';
  return `<button type="button" class="card-bm-btn${a.bookmark ? ' card-bm-active' : ''}">${bmIconHtml}</button>${favIconHtml}`;
}

function attachActressBookmarkListener(actressId, nameEl, initialBookmark) {
  const bmBtn = nameEl.querySelector('.card-bm-btn');
  if (!bmBtn) return;
  let bookmarkState = initialBookmark;
  let bookmarkTimer = null;
  bmBtn.addEventListener('click', e => {
    e.preventDefault();
    e.stopPropagation();
    bookmarkState = !bookmarkState;
    bmBtn.innerHTML = bookmarkState ? ICON_BM_SM : ICON_BM_SM_OFF;
    bmBtn.classList.toggle('card-bm-active', bookmarkState);
    const isFav = !!nameEl.querySelector('.card-fav-icon');
    nameEl.className = actressNameClass({ favorite: isFav, bookmark: bookmarkState, rejected: false });
    if (bookmarkTimer) clearTimeout(bookmarkTimer);
    bookmarkTimer = setTimeout(() => {
      bookmarkTimer = null;
      fetch(`/api/actresses/${actressId}/bookmark?value=${bookmarkState}`, { method: 'POST' })
        .catch(err => console.error('actress bookmark toggle failed', err));
    }, 2000);
  });
}

export function actressNameClass(a) {
  if (a.rejected) return 'actress-card-name';
  if (a.favorite) return 'actress-card-name actress-name-fav';
  if (a.bookmark) return 'actress-card-name actress-name-bm';
  return 'actress-card-name';
}

export function updateActressCardIndicators(id, favorite, bookmark, rejected) {
  const card = document.querySelector(`.actress-card[data-actress-id="${id}"]`);
  if (!card) return;
  const nameEl = card.querySelector('.actress-card-name');
  if (!nameEl) return;

  const bmBtn = nameEl.querySelector('.card-bm-btn');

  // Full rebuild when: transitioning to rejected, or no bmBtn exists (was previously rejected)
  if (rejected || !bmBtn) {
    const firstSpan = nameEl.querySelector('.actress-first-name');
    const lastSpan  = nameEl.querySelector('.actress-last-name');
    const firstHtml = firstSpan ? firstSpan.outerHTML : '';
    const lastHtml  = lastSpan  ? lastSpan.outerHTML  : '';
    nameEl.className = actressNameClass({ favorite, bookmark, rejected });
    nameEl.innerHTML = actressFlagIconsHtml({ favorite, bookmark, rejected }) + firstHtml + lastHtml;
    if (!rejected) attachActressBookmarkListener(id, nameEl, bookmark);
    return;
  }

  // Non-rejected, bmBtn exists — update surgically to preserve the click listener
  bmBtn.innerHTML = bookmark ? ICON_BM_SM : ICON_BM_SM_OFF;
  bmBtn.classList.toggle('card-bm-active', bookmark);

  const existingFavIcon = nameEl.querySelector('.card-fav-icon');
  if (favorite && !existingFavIcon) {
    const tmp = document.createElement('span');
    tmp.innerHTML = ICON_FAV_SM;
    bmBtn.insertAdjacentElement('afterend', tmp.firstChild);
  } else if (!favorite && existingFavIcon) {
    existingFavIcon.remove();
  }

  nameEl.className = actressNameClass({ favorite, bookmark, rejected });
}

export function makeActressCard(a) {
  const card = document.createElement('div');
  card.className = 'actress-card';
  if (a.rejected) card.classList.add('actress-card-rejected');
  card.dataset.actressId = a.id;

  const covers = a.coverUrls || [];
  const coverWrap = document.createElement('div');
  coverWrap.className = 'cover-wrap';

  if (covers.length === 0) {
    coverWrap.innerHTML = `<div class="cover-placeholder">—</div>`;
  } else if (a.rejected || covers.length < 2) {
    const img = document.createElement('img');
    img.className = 'cover-img';
    img.src = covers[0];
    img.alt = a.canonicalName;
    img.loading = 'lazy';
    coverWrap.appendChild(img);
  } else {
    // Multi-tile right-to-left marquee with per-tile hot-swap.
    const UNIQUE = Math.min(3, covers.length);
    const track = document.createElement('div');
    track.className = 'cover-marquee-track';
    const perTileSec = 8 + Math.random() * 6;
    const durationSec = UNIQUE * perTileSec;
    track.style.animationDuration = `${durationSec}s`;
    track.style.animationDelay = `-${(Math.random() * durationSec).toFixed(2)}s`;

    const pool = [...covers];
    const initialPicks = [];
    for (let i = 0; i < UNIQUE; i++) {
      const pickIdx = Math.floor(Math.random() * pool.length);
      initialPicks.push(pool.splice(pickIdx, 1)[0]);
    }
    for (let i = 0; i < UNIQUE * 2; i++) {
      const tile = document.createElement('div');
      tile.className = 'cover-marquee-tile';
      const img = document.createElement('img');
      img.className = 'cover-img';
      img.src = initialPicks[i % UNIQUE];
      img.alt = a.canonicalName;
      img.loading = 'lazy';
      tile.appendChild(img);
      track.appendChild(tile);
    }
    coverWrap.appendChild(track);

    const seen = new Set();
    const obs = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const idx = Number(entry.target.dataset.tileIdx);
        if (entry.isIntersecting) { seen.add(idx); continue; }
        if (!seen.has(idx)) continue;
        const dup = track.children[idx + UNIQUE];
        const newSrc = covers[Math.floor(Math.random() * covers.length)];
        const uniqImg = entry.target.querySelector('img');
        const dupImg  = dup ? dup.querySelector('img') : null;
        if (uniqImg) uniqImg.src = newSrc;
        if (dupImg)  dupImg.src  = newSrc;
      }
    }, { root: coverWrap, threshold: 0 });
    for (let i = 0; i < UNIQUE; i++) {
      track.children[i].dataset.tileIdx = String(i);
      obs.observe(track.children[i]);
    }
    activeObservers.add(obs);
  }
  card.appendChild(coverWrap);

  const { first: firstName, last: lastName } = splitName(a.canonicalName);
  const body = document.createElement('div');
  body.className = 'actress-card-body';
  body.innerHTML = `
    <div class="${actressNameClass(a)}">
      ${actressFlagIconsHtml(a)}<span class="actress-first-name">${esc(firstName)}</span>${lastName ? `<span class="actress-last-name">${esc(lastName)}</span>` : ''}<span class="tier-badge tier-${esc(a.tier)} actress-tier-badge">${esc(a.tier.toLowerCase())}</span>
    </div>
    <div class="actress-title-count">Titles: ${a.titleCount}</div>
    ${renderDateRange(a.firstAddedDate, a.lastAddedDate)}
    ${a.visitCount > 0 ? `<div class="actress-card-visited">${a.visitCount === 1 ? '1 view' : `${a.visitCount} views`}${a.lastVisitedAt ? ` · ${timeAgoShort(a.lastVisitedAt)}` : ''}</div>` : ''}
  `;
  card.appendChild(body);

  if (!a.rejected) {
    attachActressBookmarkListener(a.id, card.querySelector('.actress-card-name'), !!a.bookmark);
  }

  card.addEventListener('click', () => _openActressDetail(a.id));
  return card;
}

/**
 * Compact actress card — used in dashboard panels with limited horizontal room
 * (Recently Viewed, etc.). Shows only the first cover image, the actress name
 * with tier badge, and a tiny last-visited line. Drops the marquee, title
 * count and date range.
 */
export function makeCompactActressCard(a) {
  const card = document.createElement('div');
  card.className = 'actress-card actress-card-compact';
  if (a.rejected) card.classList.add('actress-card-rejected');
  card.dataset.actressId = a.id;

  const covers = a.coverUrls || [];
  const coverWrap = document.createElement('div');
  coverWrap.className = 'cover-wrap';
  if (covers.length === 0) {
    coverWrap.innerHTML = `<div class="cover-placeholder">—</div>`;
  } else {
    const img = document.createElement('img');
    img.className = 'cover-img';
    img.src = covers[0];
    img.alt = a.canonicalName;
    img.loading = 'lazy';
    coverWrap.appendChild(img);
  }
  card.appendChild(coverWrap);

  const { first: firstName, last: lastName } = splitName(a.canonicalName);
  const body = document.createElement('div');
  body.className = 'actress-card-body actress-card-body-compact';
  body.innerHTML = `
    <div class="${actressNameClass(a)}">
      ${actressFlagIconsHtml(a)}<span class="actress-first-name">${esc(firstName)}</span>${lastName ? `<span class="actress-last-name">${esc(lastName)}</span>` : ''}<span class="tier-badge tier-${esc(a.tier)} actress-tier-badge">${esc(a.tier.toLowerCase())}</span>
    </div>
    ${a.lastVisitedAt ? `<div class="actress-card-visited">${timeAgoShort(a.lastVisitedAt)}</div>` : ''}
  `;
  card.appendChild(body);

  if (!a.rejected) {
    attachActressBookmarkListener(a.id, card.querySelector('.actress-card-name'), !!a.bookmark);
  }

  card.addEventListener('click', () => _openActressDetail(a.id));
  return card;
}
