// actresses/dashboard.js — Actress Dashboard mode.
//
// Fetches /api/actresses/dashboard, renders:
//   - Spotlight (with 30s rotator) + Top Groups leaderboard + Library Stats + Research Gaps + Birthdays
//   - Recently Viewed strip + New Faces strip (side-by-side)
//   - Bookmarked Actresses strip
//   - Undiscovered Elites strip
//   - Forgotten Gems strip
//
// All DOM nodes use .act-* CSS classes (no legacy CSS dependencies).

const SPOTLIGHT_INTERVAL_MS = 30_000;

// ── Utils ─────────────────────────────────────────────────────────────────

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[c]));
}

async function fetchJson(url, fallback = null) {
  try {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) return fallback;
    return await r.json();
  } catch (e) {
    console.warn('[actresses/dashboard] fetch failed:', url, e);
    return fallback;
  }
}

// ── Card factories ────────────────────────────────────────────────────────

function makeActressCard(a) {
  const name    = a.displayName || a.canonicalName || a.name || a.slug || '';
  const tier    = (a.tier || '').toLowerCase();
  const imgSrc  = a.profileImagePath
    ? `/api/actress-image/${encodeURIComponent(a.slug || a.id)}`
    : null;
  const count   = a.titleCount != null ? `${a.titleCount}` : '';
  const el = document.createElement('a');
  el.className = `act-card act-card-${tier || 'library'}`;
  el.href = `/v2-actress-detail.html?id=${encodeURIComponent(a.id)}`;
  el.innerHTML = `
    <div class="act-card-portrait" ${imgSrc ? `style="background-image:url('${esc(imgSrc)}');background-size:cover;background-position:center top"` : ''}>
      ${tier ? `<span class="act-card-tier act-tier-${tier}">${esc(tier)}</span>` : ''}
    </div>
    <div class="act-card-name">${esc(name)}</div>
    ${count ? `<div class="act-card-meta">${esc(count)} titles</div>` : ''}
  `;
  return el;
}

function makeCompactActressCard(a) {
  const name   = a.displayName || a.canonicalName || a.name || a.slug || '';
  const tier   = (a.tier || '').toLowerCase();
  const imgSrc = a.profileImagePath
    ? `/api/actress-image/${encodeURIComponent(a.slug || a.id)}`
    : null;
  const el = document.createElement('a');
  el.className = `act-card-compact act-card-compact-${tier || 'library'}`;
  el.href = `/v2-actress-detail.html?id=${encodeURIComponent(a.id)}`;
  el.innerHTML = `
    <div class="act-card-compact-portrait" ${imgSrc ? `style="background-image:url('${esc(imgSrc)}');background-size:cover;background-position:center top"` : ''}></div>
    <div class="act-card-compact-name">${esc(name)}</div>
  `;
  return el;
}

// ── Spotlight rotator ─────────────────────────────────────────────────────

let spotlightIntervalId = null;
let spotlightContainer  = null;

function stopSpotlightRotator() {
  if (spotlightIntervalId !== null) {
    clearInterval(spotlightIntervalId);
    spotlightIntervalId = null;
  }
  spotlightContainer = null;
}

async function rotateSpotlight() {
  if (!spotlightContainer) return;
  const current = spotlightContainer.querySelector('.act-card');
  const excludeId = current ? current.dataset.actressId : null;
  const url = '/api/actresses/spotlight' + (excludeId ? `?exclude=${encodeURIComponent(excludeId)}` : '');
  const item = await fetchJson(url);
  if (!item) return;
  const newCard = makeActressCard(item);
  newCard.classList.add('act-spotlight-enter');
  if (current) {
    current.classList.add('act-spotlight-exit');
    current.addEventListener('animationend', () => current.remove(), { once: true });
  }
  spotlightContainer.appendChild(newCard);
  void newCard.offsetWidth;
  newCard.classList.remove('act-spotlight-enter');
}

function startSpotlightRotator(containerEl) {
  stopSpotlightRotator();
  spotlightContainer = containerEl;
  spotlightIntervalId = setInterval(rotateSpotlight, SPOTLIGHT_INTERVAL_MS);
}

// ── Section builder ───────────────────────────────────────────────────────

function makeSection({ title, badge = null, accent = false, bordered = false }) {
  const section = document.createElement('section');
  section.className = 'act-dash-section'
    + (accent   ? ' act-dash-section-accent'   : '')
    + (bordered ? ' act-dash-section-bordered' : '');
  const head = document.createElement('div');
  head.className = 'act-dash-section-title';
  head.textContent = title;
  if (badge != null) {
    const b = document.createElement('span');
    b.className = 'act-dash-section-badge';
    b.textContent = badge;
    head.appendChild(b);
  }
  section.appendChild(head);
  return section;
}

function makeStrip(items, cardFactory, id) {
  const strip = document.createElement('div');
  strip.className = 'act-dash-strip';
  if (id) strip.id = id;
  items.forEach(a => strip.appendChild(cardFactory(a)));
  return strip;
}

// ── Top Groups leaderboard ────────────────────────────────────────────────

function renderTopGroupsLeaderboard(topGroups, onGroupClick) {
  const section = makeSection({ title: 'Top Groups' });
  const list = document.createElement('div');
  list.className = 'act-dash-leaderboard';
  const maxScore = topGroups.reduce((m, g) => Math.max(m, g.score || 0), 0) || 1;
  topGroups.forEach((g, i) => {
    const countLabel = `${g.actressCount} ${g.actressCount === 1 ? 'actress' : 'actresses'}`;
    const barPct = Math.round(((g.score || 0) / maxScore) * 100);
    const row = document.createElement('div');
    row.className = 'act-dash-lb-row';
    row.title = `Open ${g.name} in Studio browser`;
    row.innerHTML = `
      <span class="act-dash-lb-rank">${i + 1}</span>
      <span class="act-dash-lb-name-cell">
        <span class="act-dash-lb-name">${esc(g.name)}</span>
        <span class="act-dash-lb-meta">${esc(countLabel)}</span>
      </span>
      <span class="act-dash-lb-bar-wrap"><span class="act-dash-lb-bar" style="width:${barPct}%"></span></span>
    `;
    row.addEventListener('click', () => onGroupClick(g.slug));
    list.appendChild(row);
  });
  section.appendChild(list);
  return section;
}

// ── Library stats ─────────────────────────────────────────────────────────

function renderLibraryStats(stats) {
  const researchPct = stats.researchTotal > 0
    ? Math.round((stats.researchCovered / stats.researchTotal) * 100)
    : 0;
  const tiles = [
    { label: 'Actresses',      value: stats.totalActresses?.toLocaleString() ?? '—' },
    { label: 'Favorites',      value: stats.favorites?.toLocaleString()      ?? '—' },
    { label: 'Graded',         value: stats.graded?.toLocaleString()         ?? '—' },
    { label: 'Elites',         value: stats.elites?.toLocaleString()         ?? '—' },
    { label: 'New this month', value: stats.newThisMonth?.toLocaleString()   ?? '—' },
    { label: 'Researched',     value: `${researchPct}%`, bar: researchPct },
  ];
  const section = makeSection({ title: 'Library' });
  const grid = document.createElement('div');
  grid.className = 'act-dash-stats-grid';
  tiles.forEach(t => {
    const tile = document.createElement('div');
    tile.className = 'act-dash-stat-tile';
    tile.innerHTML =
      `<div class="act-dash-stat-value">${esc(String(t.value))}</div>` +
      `<div class="act-dash-stat-label">${esc(t.label)}</div>` +
      (t.bar != null
        ? `<div class="act-dash-stat-bar-wrap"><div class="act-dash-stat-bar" style="width:${t.bar}%"></div></div>`
        : '');
    grid.appendChild(tile);
  });
  section.appendChild(grid);
  return section;
}

// ── Research Gaps ─────────────────────────────────────────────────────────

function renderResearchGaps(researchGaps, onActressClick) {
  const section = makeSection({ title: 'Research Gaps', badge: `${researchGaps.length}` });
  const list = document.createElement('div');
  list.className = 'act-dash-research-gaps';
  researchGaps.forEach(entry => {
    const a = entry.actress;
    const dots = [
      { filled: entry.profileFilled,    label: 'profile'   },
      { filled: entry.physicalFilled,   label: 'physical'  },
      { filled: entry.biographyFilled,  label: 'biography' },
      { filled: entry.portfolioCovered, label: 'portfolio' },
    ];
    const dotsHtml = dots.map(d =>
      `<span class="act-rg-dot ${d.filled ? 'act-rg-dot-filled' : 'act-rg-dot-empty'}" title="${d.label}: ${d.filled ? 'filled' : 'missing'}"></span>`
    ).join('');
    const row = document.createElement('div');
    row.className = 'act-rg-row';
    row.innerHTML = `
      <span class="act-rg-name">${esc(a.canonicalName)}</span>
      <span class="act-rg-tier act-tier-${esc((a.tier || '').toLowerCase())}">${esc((a.tier || '').toLowerCase())}</span>
      <span class="act-rg-dots">${dotsHtml}</span>
    `;
    row.addEventListener('click', () => onActressClick(a.id));
    list.appendChild(row);
  });
  section.appendChild(list);
  return section;
}

// ── Top info panel ────────────────────────────────────────────────────────

function renderTopInfoPanel(spotlight, topGroups, libraryStats, birthdaysToday, researchGaps, onGroupClick, onActressClick) {
  const panel = document.createElement('div');
  panel.className = 'act-dash-top-panel';

  // Left: spotlight card + rotator
  if (spotlight) {
    const left = document.createElement('div');
    left.className = 'act-dash-top-left';
    const title = document.createElement('div');
    title.className = 'act-dash-section-title';
    title.textContent = 'Spotlight';
    left.appendChild(title);
    const card = makeActressCard(spotlight);
    card.dataset.actressId = spotlight.id;
    left.appendChild(card);
    panel.appendChild(left);
    setTimeout(() => startSpotlightRotator(left), SPOTLIGHT_INTERVAL_MS);
  }

  const hasRight = topGroups.length > 0 || libraryStats || birthdaysToday.length > 0 || researchGaps.length > 0;
  if (hasRight) {
    const right = document.createElement('div');
    right.className = 'act-dash-top-right';

    if (topGroups.length > 0 || libraryStats || researchGaps.length > 0) {
      const upper = document.createElement('div');
      upper.className = 'act-dash-top-right-upper';
      if (topGroups.length > 0) upper.appendChild(renderTopGroupsLeaderboard(topGroups, onGroupClick));
      if (libraryStats || researchGaps.length > 0) {
        const stack = document.createElement('div');
        stack.className = 'act-dash-top-right-stack';
        if (libraryStats)           stack.appendChild(renderLibraryStats(libraryStats));
        if (researchGaps.length > 0) stack.appendChild(renderResearchGaps(researchGaps, onActressClick));
        upper.appendChild(stack);
      }
      right.appendChild(upper);
    }

    if (birthdaysToday.length > 0) {
      const bSection = makeSection({ title: 'Birthdays Today', badge: `${birthdaysToday.length}` });
      const strip = makeStrip(birthdaysToday.slice(0, 3), makeActressCard, 'act-dash-birthdays');
      bSection.appendChild(strip);
      right.appendChild(bSection);
    }

    panel.appendChild(right);
  }

  return panel;
}

// ── Side-by-side panel ────────────────────────────────────────────────────

function renderSideBySide(leftEl, rightEl) {
  const panel = document.createElement('div');
  panel.className = 'act-dash-side-panel';
  if (leftEl)  { const c = document.createElement('div'); c.className = 'act-dash-side-cell'; c.appendChild(leftEl);  panel.appendChild(c); }
  if (rightEl) { const c = document.createElement('div'); c.className = 'act-dash-side-cell'; c.appendChild(rightEl); panel.appendChild(c); }
  return panel;
}

// ── Main render ───────────────────────────────────────────────────────────

/**
 * Render the actress dashboard into containerEl.
 * @param {HTMLElement} containerEl
 * @param {function(slug: string): void} onGroupClick — called when a studio group row is clicked
 */
export async function renderActressDashboard(containerEl, onGroupClick) {
  stopSpotlightRotator();
  containerEl.innerHTML = '<div class="act-dash-loading">Loading…</div>';

  const data = await fetchJson('/api/actresses/dashboard');
  if (!data) {
    containerEl.innerHTML = '<div class="act-dash-empty">Error loading dashboard.</div>';
    return;
  }

  const spotlight          = data.spotlight          || null;
  const birthdaysToday     = data.birthdaysToday     || [];
  const newFaces           = data.newFaces           || [];
  const bookmarks          = data.bookmarks          || [];
  const recentlyViewed     = data.recentlyViewed     || [];
  const undiscoveredElites = data.undiscoveredElites || [];
  const forgottenGems      = data.forgottenGems      || [];
  const topGroups          = data.topGroups          || [];
  const researchGaps       = data.researchGaps       || [];
  const libraryStats       = data.libraryStats       || null;

  const hasAny = spotlight || birthdaysToday.length || newFaces.length || bookmarks.length
              || recentlyViewed.length || undiscoveredElites.length || forgottenGems.length
              || topGroups.length || researchGaps.length;

  if (!hasAny) {
    containerEl.innerHTML = '<div class="act-dash-empty">No actresses yet — sync a volume to get started.</div>';
    return;
  }

  containerEl.innerHTML = '';

  const onActressClick = (id) => {
    window.location.href = `/v2-actress-detail.html?id=${encodeURIComponent(id)}`;
  };

  // Top panel: spotlight + leaderboard + stats + birthdays + research gaps
  if (spotlight || topGroups.length > 0 || libraryStats || birthdaysToday.length > 0 || researchGaps.length > 0) {
    containerEl.appendChild(
      renderTopInfoPanel(spotlight, topGroups, libraryStats, birthdaysToday, researchGaps, onGroupClick, onActressClick)
    );
  }

  // Recently Viewed + New Faces (side-by-side)
  if (recentlyViewed.length > 0 || newFaces.length > 0) {
    const rvSection = recentlyViewed.length > 0 ? (() => {
      const s = makeSection({ title: 'Recently Viewed' });
      const strip = makeStrip(recentlyViewed, makeCompactActressCard, 'act-dash-recently-viewed');
      strip.classList.add('act-dash-strip-compact');
      s.appendChild(strip);
      return s;
    })() : null;
    const nfSection = newFaces.length > 0 ? (() => {
      const s = makeSection({ title: 'New Faces' });
      s.appendChild(makeStrip(newFaces, makeActressCard, 'act-dash-new-faces'));
      return s;
    })() : null;
    containerEl.appendChild(renderSideBySide(rvSection, nfSection));
  }

  // Bookmarked
  if (bookmarks.length > 0) {
    const s = makeSection({ title: 'Bookmarked Actresses', accent: true, bordered: true });
    s.appendChild(makeStrip(bookmarks, makeCompactActressCard, 'act-dash-bookmarks'));
    containerEl.appendChild(s);
  }

  // Undiscovered Elites
  if (undiscoveredElites.length > 0) {
    const s = makeSection({ title: 'Undiscovered Elites', bordered: true });
    s.appendChild(makeStrip(undiscoveredElites, makeActressCard, 'act-dash-undiscovered'));
    containerEl.appendChild(s);
  }

  // Forgotten Gems
  if (forgottenGems.length > 0) {
    const s = makeSection({ title: 'Forgotten Gems' });
    s.appendChild(makeStrip(forgottenGems, makeActressCard, 'act-dash-forgotten-gems'));
    containerEl.appendChild(s);
  }
}
