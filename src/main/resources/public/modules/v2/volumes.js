/* ─────────────────────────────────────────────────────────────────────
   Wave 3 — Volumes (workbench mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md (workbench surfaces sweep)
   Card-per-volume layout. Each card shows status, structure, last sync,
   counts, and any health issues.
   Deferred: per-volume mount/unmount actions, sync trigger, queue
   inspection (each is a follow-up against the existing card layout).
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
  } catch (e) { return fallback; }
}

function timeAgo(iso) {
  if (!iso) return 'never';
  const then = new Date(iso).getTime();
  const diff = (Date.now() - then) / 1000;
  if (diff < 60)        return `${Math.floor(diff)}s ago`;
  if (diff < 3600)      return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400)     return `${Math.floor(diff / 3600)}h ago`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)}d ago`;
  return new Date(iso).toLocaleDateString();
}

function renderHealthIssue(h) {
  const cls = h.level === 'error' ? 'error' : 'warn';
  return `
    <div class="vol-issue">
      <span class="pill ${cls}">${escapeHtml(h.level)}</span>
      <span class="desc">${escapeHtml(h.description || h.category || '')}</span>
      ${h.count > 0 ? `<span class="count">${h.count}</span>` : ''}
    </div>
  `;
}

function renderCard(v) {
  const online = v.status === 'online';
  const dotCls = online ? 'live' : 'idle';
  const statusLabel = online ? 'online' : 'offline';
  const statusColor = online ? 'var(--ok)' : 'var(--error)';
  const health = Array.isArray(v.health) ? v.health : [];
  return `
    <article class="vol-card ${online ? '' : 'offline'}">
      <header class="vol-card-head">
        <span class="status-dot ${dotCls}" style="background:${statusColor}"></span>
        <span class="vol-card-id">${escapeHtml(v.id)}</span>
        <span class="vol-card-structure">${escapeHtml(v.structureType || 'unknown')}</span>
        <span class="pill ${online ? 'ok' : 'error'}">${escapeHtml(statusLabel)}</span>
      </header>

      <div class="vol-card-path">${escapeHtml(v.smbPath || '—')}</div>

      <div class="vol-card-stats">
        <div class="vol-stat">
          <span class="k">Titles</span>
          <span class="v">${escapeHtml(String(v.titleCount ?? 0))}</span>
        </div>
        ${v.queueCount > 0 ? `
          <div class="vol-stat">
            <span class="k">Queue</span>
            <span class="v" style="color:var(--warn)">${escapeHtml(String(v.queueCount))}</span>
          </div>` : ''}
        <div class="vol-stat">
          <span class="k">Last sync</span>
          <span class="v" style="font-size:12px">${escapeHtml(timeAgo(v.lastSyncedAt))}</span>
        </div>
      </div>

      <div class="vol-health">
        <div class="vol-health-head">Health</div>
        ${health.length === 0
          ? `<div class="vol-health-empty">
              <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
              All healthy
            </div>`
          : health.map(renderHealthIssue).join('')}
      </div>
    </article>
  `;
}

export async function mountVolumes(rootEl) {
  rootEl.innerHTML = `
    <div class="wb-page">
      <h1 class="wb-page-title">Volumes</h1>
      <div class="wb-page-subtitle">SMB shares mounted by the app. Health issues surface things that need attention.</div>

      <div class="filter-bar">
        <button class="btn sm" id="btn-refresh">
          <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
          </svg>
          Refresh
        </button>
        <div class="filter-group" style="margin-left:6px" id="status-chips">
          <span class="chip on" data-s="">All</span>
          <span class="chip" data-s="online">Online</span>
          <span class="chip" data-s="offline">Offline</span>
          <span class="chip" data-s="unhealthy">Has issues</span>
        </div>
        <div class="filter-spacer"></div>
        <div class="filter-meta" id="result-meta"></div>
      </div>

      <div id="grid"><div class="shelf-loading">Loading…</div></div>
    </div>
  `;

  const grid = rootEl.querySelector('#grid');
  const meta = rootEl.querySelector('#result-meta');
  let allVols = [];
  let filter = '';

  const render = () => {
    let rows = allVols;
    if (filter === 'online')    rows = allVols.filter(v => v.status === 'online');
    if (filter === 'offline')   rows = allVols.filter(v => v.status !== 'online');
    if (filter === 'unhealthy') rows = allVols.filter(v => Array.isArray(v.health) && v.health.length > 0);

    if (rows.length === 0) {
      grid.innerHTML = `
        <div class="empty-state">
          <div class="empty-state-title">No volumes match this filter</div>
          <div class="empty-state-body">Try a different filter.</div>
        </div>`;
      meta.textContent = `0 of ${allVols.length}`;
      return;
    }

    grid.innerHTML = `<div class="vol-grid">${rows.map(renderCard).join('')}</div>`;
    const online    = allVols.filter(v => v.status === 'online').length;
    const unhealthy = allVols.filter(v => Array.isArray(v.health) && v.health.length > 0).length;
    meta.textContent = `${rows.length} shown · ${online}/${allVols.length} online · ${unhealthy} with issues`;
  };

  const load = async () => {
    grid.innerHTML = `<div class="shelf-loading">Loading…</div>`;
    meta.textContent = 'Loading…';
    allVols = (await fetchJson('/api/utilities/volumes', [])) || [];
    // Sort: online first, then by id
    allVols.sort((a, b) => {
      if ((a.status === 'online') !== (b.status === 'online')) return a.status === 'online' ? -1 : 1;
      return String(a.id).localeCompare(String(b.id));
    });
    render();
  };

  rootEl.querySelector('#btn-refresh').addEventListener('click', load);
  rootEl.querySelector('#status-chips').addEventListener('click', (e) => {
    const chip = e.target.closest('.chip');
    if (!chip) return;
    rootEl.querySelectorAll('#status-chips .chip').forEach(c => c.classList.remove('on'));
    chip.classList.add('on');
    filter = chip.dataset.s || '';
    render();
  });

  load();
}
