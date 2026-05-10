/* ─────────────────────────────────────────────────────────────────────
   Wave 2 — AV Star detail (library mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md §3.3
   Hero band + tech summary tiles. IAFD bio / videos list deferred.
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
    console.warn('[avstar-detail] fetch failed:', url, e);
    return fallback;
  }
}

function fmtBytes(b) {
  if (!b) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
  return `${b.toFixed(b >= 100 ? 0 : 1)} ${u[i]}`;
}

export async function mountAvStarDetail(rootEl, id) {
  if (!id) {
    rootEl.innerHTML = `
      <div class="lib-page">
        <div class="empty-state">
          <div class="empty-state-title">Missing AV star ID</div>
          <div class="empty-state-body">Append <code>?id=NUMBER</code> to the URL.</div>
        </div>
      </div>`;
    return;
  }

  rootEl.innerHTML = `<div class="lib-page"><div id="hero"><div class="shelf-loading">Loading…</div></div></div>`;

  const data = await fetchJson(`/api/utilities/avstars/actresses/${encodeURIComponent(id)}`, null);
  if (!data || !data.detail) {
    rootEl.querySelector('#hero').innerHTML = `
      <div class="empty-state">
        <div class="empty-state-title">AV star not found</div>
        <div class="empty-state-body">No AV star with ID ${escapeHtml(id)}.</div>
      </div>`;
    return;
  }

  const d = data.detail;
  const tech = data.techSummary || {};
  const name = d.stageName || d.folderName || `#${id}`;
  const portrait = d.headshotUrl || null;
  const grade = d.grade ? d.grade.toUpperCase() : '';

  const crumb = document.querySelector('#crumb-name');
  if (crumb) crumb.textContent = name;
  document.title = `${name} — Organizer3 v2`;

  const codecs = tech.byCodec ? Object.entries(tech.byCodec).map(([c, n]) => `${c}: ${n}`).join(' · ') : '';
  const resolutions = tech.byResolution ? Object.entries(tech.byResolution).map(([r, n]) => `${r}: ${n}`).join(' · ') : '';

  rootEl.innerHTML = `
    <div class="lib-page">
      <div class="hero-band">
        <div class="hero-portrait" style="${portrait ? `background-image:url('${portrait}');background-size:cover;background-position:center top` : ''}"></div>
        <div class="hero-content">
          <div class="hero-eyebrow">${escapeHtml(d.resolved ? 'Resolved' : 'Unresolved')} · AV Star${grade ? ` · ${escapeHtml(grade)}` : ''}</div>
          <h1 class="hero-name">${escapeHtml(name)}</h1>
          ${d.folderName && d.folderName !== name ? `<div class="hero-aliases">folder: ${escapeHtml(d.folderName)}</div>` : ''}
          <div class="hero-stats">
            <span><b>${escapeHtml(String(d.videoCount ?? tech.videoCount ?? '?'))}</b> videos</span>
            ${tech.totalBytes ? `<span><b>${escapeHtml(fmtBytes(tech.totalBytes))}</b></span>` : ''}
            ${d.volumeId ? `<span><b>${escapeHtml(d.volumeId)}</b> volume</span>` : ''}
          </div>
          <div class="hero-actions">
            <button class="btn primary" id="btn-favorite">
              <svg viewBox="0 0 24 24"><polygon points="12 2 15 9 22 9 17 14 18 21 12 17 6 21 7 14 2 9 9 9"/></svg>
              ${d.favorite ? 'Favorited' : 'Favorite'}
            </button>
            <button class="btn" id="btn-bookmark">
              <svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V3a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
              ${d.bookmark ? 'Bookmarked' : 'Bookmark'}
            </button>
          </div>
        </div>
      </div>

      <section class="shelf" style="margin-top:32px">
        <div class="shelf-head">
          <span class="shelf-title">Tech summary</span>
        </div>
        <div class="shelf-grid shelf-grid-tiles">
          <div class="kpi-tile">
            <div class="kpi-tile-head">Videos</div>
            <div class="kpi-tile-value">${escapeHtml(String(tech.videoCount ?? 0))}</div>
            <div class="kpi-tile-meta">${escapeHtml(fmtBytes(tech.totalBytes ?? 0))} total</div>
          </div>
          <div class="kpi-tile">
            <div class="kpi-tile-head">Codecs</div>
            <div class="kpi-tile-value" style="font-size:14px">${escapeHtml(codecs || '—')}</div>
          </div>
          <div class="kpi-tile">
            <div class="kpi-tile-head">Resolutions</div>
            <div class="kpi-tile-value" style="font-size:14px">${escapeHtml(resolutions || '—')}</div>
          </div>
        </div>
      </section>
    </div>
  `;

  // Wire favorite/bookmark — AV uses /api/av/actresses/{id}/favorite|bookmark
  rootEl.querySelector('#btn-favorite')?.addEventListener('click', async () => {
    await fetch(`/api/av/actresses/${encodeURIComponent(id)}/favorite?value=${!d.favorite}`, { method: 'POST' });
    mountAvStarDetail(rootEl, id);
  });
  rootEl.querySelector('#btn-bookmark')?.addEventListener('click', async () => {
    await fetch(`/api/av/actresses/${encodeURIComponent(id)}/bookmark?value=${!d.bookmark}`, { method: 'POST' });
    mountAvStarDetail(rootEl, id);
  });
}
