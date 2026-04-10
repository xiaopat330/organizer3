import { esc, splitName, renderDateRange } from './utils.js';
import { ICON_FAV_SM, ICON_BM_SM, titleCodeClass, gradeBadgeHtml, tagBadgeHtml } from './icons.js';
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

  let labelLineHtml = '';
  if (t.companyName || t.labelName) {
    const parts = [];
    if (t.companyName) parts.push(esc(t.companyName));
    if (t.labelName)   parts.push(`(${esc(t.labelName)})`);
    labelLineHtml = `<div class="title-label-line">${parts.join(' ')}</div>`;
  }

  const displayDate = t.releaseDate || t.addedDate;
  const dateHtml = displayDate ? `<div class="added-date">${esc(displayDate)}</div>` : '';

  const titleEnHtml = t.titleEnglish  ? `<div class="title-en">${esc(t.titleEnglish)}</div>`  : '';
  const titleJaHtml = t.titleOriginal ? `<div class="title-ja">${esc(t.titleOriginal)}</div>` : '';

  const locs = (t.locations && t.locations.length > 0) ? t.locations : (t.location ? [t.location] : []);
  const locationHtml = locs.length > 0
    ? `<div class="title-locations">${locs.map(p => `<div class="title-location">${renderLocation(p)}</div>`).join('')}</div>`
    : '';

  const tags = t.tags || [];
  const tagsHtml = tags.length > 0
    ? `<div class="title-tags">${tags.map(tagBadgeHtml).join('')}</div>`
    : '';

  const favIcon = t.favorite ? ICON_FAV_SM : '';
  const bmIcon  = t.bookmark ? ICON_BM_SM  : '';
  const titleCodeHtml = `<div class="${titleCodeClass(t.favorite, t.bookmark)}">${favIcon}${bmIcon}${esc(t.code)}${gradeBadgeHtml(t.grade)}</div>`;

  const watchedHtml = t.lastWatchedAt
    ? `<div class="card-watched">watched ${timeAgoShort(t.lastWatchedAt)}${t.watchCount > 1 ? ` (${t.watchCount}x)` : ''}</div>`
    : '';

  const visitedHtml = t.visitCount > 0
    ? `<div class="title-card-visited">${t.visitCount === 1 ? '1 view' : `${t.visitCount} views`}${t.lastVisitedAt ? ` · ${timeAgoShort(t.lastVisitedAt)}` : ''}</div>`
    : '';

  card.innerHTML = `${coverHtml}<div class="card-info">${actressHtml}${titleCodeHtml}${titleEnHtml}${titleJaHtml}${labelLineHtml}${dateHtml}${visitedHtml}${locationHtml}${tagsHtml}${watchedHtml}</div>`;

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
  const favIcon = favorite ? ICON_FAV_SM : '';
  const bmIcon  = bookmark ? ICON_BM_SM  : '';
  const grade = codeEl.querySelector('.grade-badge');
  const gradeHtml = grade ? grade.outerHTML : '';
  codeEl.className = titleCodeClass(favorite, bookmark);
  codeEl.innerHTML = `${favIcon}${bmIcon}${esc(code)}${gradeHtml}`;
}

// ── Actress card ──────────────────────────────────────────────────────────
export function actressFlagIconsHtml(a) {
  const parts = [];
  if (a.rejected) {
    parts.push('<svg class="card-rej-icon" viewBox="0 0 24 24" width="12" height="12"><path d="M6 6 L18 18 M18 6 L6 18"/></svg>');
  } else {
    if (a.favorite) parts.push(ICON_FAV_SM);
    if (a.bookmark) parts.push(ICON_BM_SM);
  }
  return parts.join('');
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
  const firstSpan = nameEl.querySelector('.actress-first-name');
  const lastSpan  = nameEl.querySelector('.actress-last-name');
  const firstHtml = firstSpan ? firstSpan.outerHTML : '';
  const lastHtml  = lastSpan  ? lastSpan.outerHTML  : '';
  nameEl.className = actressNameClass({ favorite, bookmark, rejected });
  nameEl.innerHTML = actressFlagIconsHtml({ favorite, bookmark, rejected }) + firstHtml + lastHtml;
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
      ${actressFlagIconsHtml(a)}<span class="actress-first-name">${esc(firstName)}</span>${lastName ? `<span class="actress-last-name">${esc(lastName)}</span>` : ''}
    </div>
    <div class="actress-card-meta">
      <span class="tier-badge tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>
    </div>
    ${renderDateRange(a.firstAddedDate, a.lastAddedDate)}
    ${a.visitCount > 0 ? `<div class="actress-card-visited">${a.visitCount === 1 ? '1 view' : `${a.visitCount} views`}${a.lastVisitedAt ? ` · ${timeAgoShort(a.lastVisitedAt)}` : ''}</div>` : ''}
    <div class="actress-title-count">Titles: ${a.titleCount}</div>
    ${(a.folderPaths || []).length > 0
      ? `<div class="actress-folder-paths">${a.folderPaths.map(p => `<div class="actress-folder-path">${esc(p)}</div>`).join('')}</div>`
      : ''}
  `;
  card.appendChild(body);

  card.addEventListener('click', () => _openActressDetail(a.id));
  return card;
}
