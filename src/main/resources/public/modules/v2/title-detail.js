/* ─────────────────────────────────────────────────────────────────────
   Wave 2 — Title detail (library mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md §3.1
   Hero band (cover + identity + metadata + actions) + Cast shelf.
   Deferred to follow-up: video list + thumbnails, folder contents,
   tags, related titles, watch history, theater/streaming.
   ───────────────────────────────────────────────────────────────────── */

function escapeHtml(s) {
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
    console.warn('[title-detail] fetch failed:', url, e);
    return fallback;
  }
}

function renderActressCard(a) {
  const name = a.name || a.displayName || '';
  const portrait = a.profileImagePath
    ? `/api/actress-image/${encodeURIComponent(a.slug || a.id)}`
    : null;
  return `
    <a class="card-actress" href="/v2-actress-detail.html?id=${encodeURIComponent(a.id)}">
      <div class="card-actress-portrait" style="${portrait ? `background-image:url('${portrait}');background-size:cover;background-position:center top` : ''}"></div>
      <div class="card-actress-name">${escapeHtml(name)}</div>
    </a>
  `;
}

async function loadAndRenderHero(rootEl, code) {
  const list = await fetchJson(`/api/titles?code=${encodeURIComponent(code)}&limit=1`, []);
  const t = Array.isArray(list) && list.length ? list[0] : null;
  const heroEl = rootEl.querySelector('#hero');
  const titleEl = document.querySelector('#crumb-name');

  if (!t) {
    heroEl.innerHTML = `
      <div class="empty-state">
        <div class="empty-state-title">Title not found</div>
        <div class="empty-state-body">No title with code <code>${escapeHtml(code)}</code>.</div>
      </div>`;
    return null;
  }

  const titleCode = t.code || t.productCode || code;
  const name      = t.normalizedTitle || t.titleEn || t.titleJa || t.title || titleCode;
  const cover     = t.coverPath
    ? `/covers/${encodeURIComponent(t.coverPath)}`
    : `/api/cover/${encodeURIComponent(titleCode)}`;
  const year      = t.releaseDate ? String(t.releaseDate).slice(0, 4) : '';
  const releaseDate = t.releaseDate || '';
  const label     = t.label || t.labelCode || '';
  const company   = t.company || t.companyName || '';
  const cast      = Array.isArray(t.actresses) ? t.actresses : [];

  if (titleEl) titleEl.textContent = titleCode;
  document.title = `${titleCode} — Organizer3 v2`;

  heroEl.innerHTML = `
    <div class="hero-band hero-band-title">
      <div class="hero-cover" style="background-image:url('${cover}')"></div>
      <div class="hero-content">
        <div class="hero-eyebrow">Title</div>
        <h1 class="hero-name">${escapeHtml(name)}</h1>
        <div class="hero-code">${escapeHtml(titleCode)}</div>
        <div class="hero-stats">
          ${cast.length ? `<span><b>${escapeHtml(cast.map(a => a.name).join(' · '))}</b></span>` : ''}
          ${label ? `<span><b>${escapeHtml(label)}</b> label</span>` : ''}
          ${company && company !== label ? `<span><b>${escapeHtml(company)}</b></span>` : ''}
          ${year ? `<span><b>${escapeHtml(year)}</b></span>` : ''}
        </div>
        <div class="hero-actions">
          <button class="btn primary" id="btn-favorite">
            <svg viewBox="0 0 24 24"><polygon points="12 2 15 9 22 9 17 14 18 21 12 17 6 21 7 14 2 9 9 9"/></svg>
            ${t.favorite ? 'Favorited' : 'Favorite'}
          </button>
          <button class="btn" id="btn-bookmark">
            <svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V3a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
            ${t.bookmark ? 'Bookmarked' : 'Bookmark'}
          </button>
          <button class="btn danger" id="btn-trash">
            <svg viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
            Trash
          </button>
        </div>
        ${releaseDate ? `<div class="hero-meta-line"><span class="k">released</span> <span class="v mono">${escapeHtml(releaseDate)}</span></div>` : ''}
      </div>
    </div>
  `;

  rootEl.querySelector('#btn-favorite')?.addEventListener('click', async () => {
    const r = await fetch(`/api/titles/${encodeURIComponent(titleCode)}/favorite`, { method: 'POST' });
    if (r.ok) loadAndRenderHero(rootEl, code);
  });
  rootEl.querySelector('#btn-bookmark')?.addEventListener('click', async () => {
    const r = await fetch(`/api/titles/${encodeURIComponent(titleCode)}/bookmark`, { method: 'POST' });
    if (r.ok) loadAndRenderHero(rootEl, code);
  });
  rootEl.querySelector('#btn-trash')?.addEventListener('click', () => {
    alert('Trash action wired in a follow-up — destructive op needs the confirmation modal primitive.');
  });

  // Render Cast shelf
  const castEl = rootEl.querySelector('#cast');
  if (cast.length === 0) {
    castEl.innerHTML = `<div class="shelf-empty">No cast indexed for this title.</div>`;
  } else {
    castEl.innerHTML = `<div class="shelf-grid shelf-grid-actress">${cast.map(renderActressCard).join('')}</div>`;
  }

  // Mark as visited
  fetch(`/api/titles/${encodeURIComponent(titleCode)}/visit`, { method: 'POST' }).catch(() => {});

  return t;
}

export function mountTitleDetail(rootEl, code) {
  if (!code) {
    rootEl.innerHTML = `
      <div class="lib-page">
        <div class="empty-state">
          <div class="empty-state-title">Missing title code</div>
          <div class="empty-state-body">Append <code>?code=CODE</code> to the URL.</div>
        </div>
      </div>`;
    return;
  }

  rootEl.innerHTML = `
    <div class="lib-page">
      <div id="hero"><div class="shelf-loading">Loading…</div></div>

      <section class="shelf" style="margin-top:32px">
        <div class="shelf-head">
          <span class="shelf-title">Cast</span>
        </div>
        <div id="cast"></div>
      </section>
    </div>
  `;

  loadAndRenderHero(rootEl, code);
}
