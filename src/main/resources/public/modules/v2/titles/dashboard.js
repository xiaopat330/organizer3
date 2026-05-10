/* ─────────────────────────────────────────────────────────────────────
   Titles v2 — Dashboard mode
   Fetches /api/titles/dashboard and renders all dashboard sections.
   Spotlight rotator cycles every 30 s using /api/titles/spotlight.
   ───────────────────────────────────────────────────────────────────── */

const SPOTLIGHT_INTERVAL_MS = 30_000;

// ── Utils ─────────────────────────────────────────────────────────────────
function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

// ── Title card (dashboard-appropriate: cover + code + actress + meta) ──────
function makeCard(t) {
  const code  = t.code  || '';
  const name  = t.titleEnglish || t.titleOriginalEn || t.titleOriginal || code;
  const cover = t.coverUrl || null;
  const actress = t.actressName || (t.actresses && t.actresses.length ? t.actresses[0].name : '');
  const date  = t.releaseDate || t.addedDate || '';
  const grade = t.grade || '';

  const gradeHtml = grade
    ? `<span class="grade-badge grade-${esc(grade.charAt(0))}">${esc(grade)}</span>`
    : '';

  const el = document.createElement('a');
  el.className = 'card-title tit-dash-card';
  el.href = `/v2-title-detail.html?code=${encodeURIComponent(code)}`;
  el.dataset.code = code;
  el.innerHTML = `
    <div class="card-title-cover${cover ? '' : ' card-title-cover--empty'}"
         style="${cover ? `background-image:url('${esc(cover)}');background-size:cover;background-position:center` : ''}">
      ${gradeHtml ? `<div class="card-title-status">${gradeHtml}</div>` : ''}
    </div>
    <div class="card-title-code">${esc(code)}</div>
    <div class="card-title-name">${esc(name)}</div>
    <div class="card-title-meta">
      ${actress ? `<span>${esc(actress)}</span>` : ''}
      ${actress && date ? '<span class="dot"></span>' : ''}
      ${date ? `<span class="year">${esc(String(date).slice(0,4))}</span>` : ''}
    </div>`;
  return el;
}

function makeCompactCard(t) {
  const code  = t.code || '';
  const name  = t.titleEnglish || t.titleOriginalEn || t.titleOriginal || code;
  const cover = t.coverUrl || null;
  const date  = t.releaseDate || t.addedDate || '';

  const el = document.createElement('a');
  el.className = 'card-title tit-dash-card tit-dash-card--compact';
  el.href = `/v2-title-detail.html?code=${encodeURIComponent(code)}`;
  el.innerHTML = `
    <div class="card-title-cover${cover ? '' : ' card-title-cover--empty'}"
         style="${cover ? `background-image:url('${esc(cover)}');background-size:cover;background-position:center` : ''}">
    </div>
    <div class="card-title-code">${esc(code)}</div>
    ${date ? `<div class="card-title-meta"><span class="year">${esc(String(date).slice(0,4))}</span></div>` : ''}`;
  return el;
}

// ── Aging badge ───────────────────────────────────────────────────────────
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

function makeCardWithAging(t) {
  const card = makeCard(t);
  const label = agingLabel(t.addedDate);
  if (label) {
    const badge = document.createElement('div');
    badge.className = 'tit-dash-aging';
    badge.textContent = label;
    const cover = card.querySelector('.card-title-cover');
    (cover || card).appendChild(badge);
  }
  return card;
}

// ── Spotlight rotator ─────────────────────────────────────────────────────
function createSpotlightRotator() {
  let intervalId = null;
  let containerEl = null;

  async function rotate() {
    if (!containerEl) return;
    const current = containerEl.querySelector('.tit-dash-card');
    const excludeCode = current ? (current.dataset.code || null) : null;
    const url = '/api/titles/spotlight' + (excludeCode ? `?exclude=${excludeCode}` : '');
    try {
      const res = await fetch(url, { cache: 'no-cache' });
      if (!res.ok || res.status === 204) return;
      const t = await res.json();
      const newCard = makeCard(t);
      newCard.classList.add('tit-spotlight-enter');
      if (current) {
        current.classList.add('tit-spotlight-exit');
        current.addEventListener('animationend', () => current.remove(), { once: true });
      }
      containerEl.appendChild(newCard);
      void newCard.offsetWidth;
      newCard.classList.remove('tit-spotlight-enter');
    } catch (_) { /* network error — skip */ }
  }

  return {
    start(el) {
      if (intervalId) clearInterval(intervalId);
      containerEl = el;
      intervalId = setInterval(rotate, SPOTLIGHT_INTERVAL_MS);
    },
    stop() {
      if (intervalId) { clearInterval(intervalId); intervalId = null; }
      containerEl = null;
    },
  };
}

export const spotlightRotator = createSpotlightRotator();

// ── Section + strip builders ──────────────────────────────────────────────
function makeSection({ title, badge = null, accent = false, bordered = false }) {
  const sec = document.createElement('section');
  sec.className = 'tit-dash-section'
    + (accent   ? ' tit-dash-section--accent'   : '')
    + (bordered ? ' tit-dash-section--bordered' : '');
  const hdr = document.createElement('div');
  hdr.className = 'tit-dash-section-title';
  hdr.textContent = title;
  if (badge) {
    const b = document.createElement('span');
    b.className = 'tit-dash-section-badge';
    b.textContent = badge;
    hdr.appendChild(b);
  }
  sec.appendChild(hdr);
  return sec;
}

function makeStrip(items, cardFactory) {
  const grid = document.createElement('div');
  grid.className = 'tit-dash-strip';
  items.forEach(t => grid.appendChild(cardFactory(t)));
  return grid;
}

// ── Top labels leaderboard ────────────────────────────────────────────────
function renderTopLabels(topLabels, rootEl) {
  const sec = makeSection({ title: 'Top Labels' });
  const list = document.createElement('div');
  list.className = 'tit-dash-leaderboard';
  const max = topLabels.slice(0, 5).reduce((m, l) => Math.max(m, l.score || 0), 0) || 1;
  topLabels.slice(0, 5).forEach((lbl, i) => {
    const row = document.createElement('div');
    row.className = 'tit-dash-leaderboard-row';
    row.innerHTML = `
      <span class="tit-dash-lb-rank">${i + 1}</span>
      <span class="tit-dash-lb-code">${esc(lbl.code)}</span>
      <span class="tit-dash-lb-name">${esc(lbl.labelName || '')}${lbl.company ? `<span class="tit-dash-lb-company"> · ${esc(lbl.company)}</span>` : ''}</span>
      <span class="tit-dash-lb-bar-wrap"><span class="tit-dash-lb-bar" style="width:${Math.round((lbl.score / max) * 100)}%"></span></span>`;
    row.addEventListener('click', () => {
      // Jump to library mode with this label prefix
      const searchInput = rootEl.querySelector('#tit-library-code');
      if (searchInput) {
        searchInput.value = lbl.code + '-';
        searchInput.dispatchEvent(new Event('input', { bubbles: true }));
        searchInput.focus();
      }
    });
    list.appendChild(row);
  });
  sec.appendChild(list);
  return sec;
}

// ── Library stats tiles ───────────────────────────────────────────────────
function renderLibraryStats(stats) {
  const unseenPct = stats.totalTitles > 0
    ? Math.round((stats.unseen / stats.totalTitles) * 100) : 0;
  const tiles = [
    { label: 'Titles',           value: stats.totalTitles.toLocaleString() },
    { label: 'Labels',           value: stats.totalLabels.toLocaleString() },
    { label: 'Unseen',           value: stats.unseen.toLocaleString() },
    { label: 'Unseen %',         value: `${unseenPct}%`, bar: unseenPct },
    { label: 'Added this month', value: stats.addedThisMonth.toLocaleString() },
    { label: 'Added this year',  value: stats.addedThisYear.toLocaleString() },
  ];
  const sec = makeSection({ title: 'Library' });
  const grid = document.createElement('div');
  grid.className = 'tit-dash-stats-grid';
  tiles.forEach(t => {
    const tile = document.createElement('div');
    tile.className = 'tit-dash-stats-tile';
    tile.innerHTML = `<div class="tit-dash-stats-value">${esc(String(t.value))}</div>
      <div class="tit-dash-stats-label">${esc(t.label)}</div>
      ${t.bar != null ? `<div class="tit-dash-stats-bar-wrap"><div class="tit-dash-stats-bar" style="width:${t.bar}%"></div></div>` : ''}`;
    grid.appendChild(tile);
  });
  sec.appendChild(grid);
  return sec;
}

// ── Main render ───────────────────────────────────────────────────────────
export async function renderDashboard(containerEl, rootEl) {
  spotlightRotator.stop();
  containerEl.innerHTML = '<div class="tit-dash-loading">Loading dashboard…</div>';

  let data;
  try {
    const res = await fetch('/api/titles/dashboard', { cache: 'no-cache' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    data = await res.json();
  } catch (err) {
    containerEl.innerHTML = '<div class="tit-dash-empty">Error loading dashboard.</div>';
    console.error('[titles/dashboard]', err);
    return;
  }

  const onDeck             = data.onDeck             || [];
  const justAdded          = data.justAdded          || [];
  const fromFavoriteLabels = data.fromFavoriteLabels || [];
  const recentlyViewed     = data.recentlyViewed     || [];
  const spotlight          = data.spotlight          || null;
  const forgottenAttic     = data.forgottenAttic     || [];
  const forgottenFavorites = data.forgottenFavorites || [];
  const onThisDay          = data.onThisDay          || [];
  const topLabels          = data.topLabels          || [];
  const libraryStats       = data.libraryStats       || null;

  const hasAny = onDeck.length || justAdded.length || fromFavoriteLabels.length
              || recentlyViewed.length || spotlight || forgottenAttic.length
              || forgottenFavorites.length || onThisDay.length || topLabels.length;

  if (!hasAny) {
    containerEl.innerHTML = '<div class="tit-dash-empty">No titles yet — sync a volume to get started.</div>';
    return;
  }

  containerEl.innerHTML = '';

  // ── Top panel: Spotlight + Top Labels + Library Stats + On This Day ──
  if (spotlight || topLabels.length > 0 || libraryStats || onThisDay.length > 0) {
    const topPanel = document.createElement('div');
    topPanel.className = 'tit-dash-top-panel';

    if (spotlight) {
      const left = document.createElement('div');
      left.className = 'tit-dash-top-left';
      const hdr = document.createElement('div');
      hdr.className = 'tit-dash-section-title';
      hdr.textContent = 'Spotlight';
      left.appendChild(hdr);
      const card = makeCard(spotlight);
      left.appendChild(card);
      topPanel.appendChild(left);
      // Start rotator after initial interval
      setTimeout(() => spotlightRotator.start(left), SPOTLIGHT_INTERVAL_MS);
    }

    if (topLabels.length > 0 || libraryStats || onThisDay.length > 0) {
      const right = document.createElement('div');
      right.className = 'tit-dash-top-right';

      if (topLabels.length > 0 || libraryStats) {
        const upper = document.createElement('div');
        upper.className = 'tit-dash-top-right-upper';
        if (topLabels.length > 0) upper.appendChild(renderTopLabels(topLabels, rootEl));
        if (libraryStats)         upper.appendChild(renderLibraryStats(libraryStats));
        right.appendChild(upper);
      }

      if (onThisDay.length > 0) {
        const shown = onThisDay.slice(0, 3);
        const sec = makeSection({ title: 'On This Day', badge: `${onThisDay.length} memor${onThisDay.length === 1 ? 'y' : 'ies'}` });
        sec.appendChild(makeStrip(shown, makeCompactCard));
        right.appendChild(sec);
      }

      topPanel.appendChild(right);
    }

    containerEl.appendChild(topPanel);
  }

  // ── Recently Viewed + Just Added (side-by-side) ──
  if (recentlyViewed.length > 0 || justAdded.length > 0) {
    const sidepanel = document.createElement('div');
    sidepanel.className = 'tit-dash-side-panel';

    if (recentlyViewed.length > 0) {
      const sec = makeSection({ title: 'Recently Viewed' });
      const strip = makeStrip(recentlyViewed, makeCompactCard);
      strip.classList.add('tit-dash-strip--compact');
      sec.appendChild(strip);
      const cell = document.createElement('div');
      cell.className = 'tit-dash-side-cell';
      cell.appendChild(sec);
      sidepanel.appendChild(cell);
    }

    if (justAdded.length > 0) {
      const sec = makeSection({ title: 'Just Added' });
      sec.appendChild(makeStrip(justAdded, makeCardWithAging));
      const cell = document.createElement('div');
      cell.className = 'tit-dash-side-cell';
      cell.appendChild(sec);
      sidepanel.appendChild(cell);
    }

    containerEl.appendChild(sidepanel);
  }

  // ── Bookmarked Selections (onDeck) ──
  if (onDeck.length > 0) {
    const sec = makeSection({ title: 'Bookmarked Selections', accent: true, bordered: true });
    sec.appendChild(makeStrip(onDeck, makeCompactCard));
    containerEl.appendChild(sec);
  }

  // ── From Favorite Labels ──
  if (fromFavoriteLabels.length > 0) {
    const sec = makeSection({ title: 'From Favorite Labels', bordered: true });
    sec.appendChild(makeStrip(fromFavoriteLabels, makeCardWithAging));
    containerEl.appendChild(sec);
  }

  // ── Forgotten Attic ──
  if (forgottenAttic.length > 0) {
    const sec = makeSection({ title: 'Forgotten Attic', bordered: true });
    sec.appendChild(makeStrip(forgottenAttic, makeCard));
    containerEl.appendChild(sec);
  }

  // ── Forgotten Favorites ──
  if (forgottenFavorites.length > 0) {
    const sec = makeSection({ title: 'Forgotten Favorites' });
    sec.appendChild(makeStrip(forgottenFavorites, makeCard));
    containerEl.appendChild(sec);
  }
}
