/* ─────────────────────────────────────────────────────────────────────
   Wave 3 — Translation (workbench mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md (workbench surfaces sweep)
   KPI tiles for queue health + table preview of next pending items.
   Deferred: recent-events polling feed, manual translate, requeue
   actions, model picker (each is its own follow-up).
   ───────────────────────────────────────────────────────────────────── */

const QUEUE_LIMIT = 25;

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
    console.warn('[translation] fetch failed:', url, e);
    return fallback;
  }
}

function timeAgo(iso) {
  if (!iso) return '';
  const then = new Date(iso).getTime();
  const diff = (Date.now() - then) / 1000;
  if (diff < 60)        return `${Math.floor(diff)}s ago`;
  if (diff < 3600)      return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400)     return `${Math.floor(diff / 3600)}h ago`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)}d ago`;
  return new Date(iso).toLocaleDateString();
}

function truncate(s, n = 80) {
  if (!s) return '';
  return s.length <= n ? s : s.slice(0, n - 1) + '…';
}

function renderKpis(stats) {
  if (!stats) return '<div class="shelf-loading">Stats unavailable.</div>';
  const pending  = stats.queuePending ?? 0;
  const inflight = stats.queueInFlight ?? 0;
  const failed   = stats.queueFailed ?? 0;
  const through  = stats.throughputLastHour ?? 0;
  const t2       = stats.queueTier2Pending ?? 0;
  const hitRate  = stats.cacheHitRate != null ? `${(stats.cacheHitRate * 100).toFixed(0)}%` : '—';
  const cls = (n) => n > 0 ? 'warn' : 'ok';
  return `
    <div class="shelf-grid shelf-grid-tiles">
      <div class="kpi-tile">
        <div class="kpi-tile-head">Pending</div>
        <div class="kpi-tile-value ${cls(pending)}">${pending}</div>
        <div class="kpi-tile-meta">${t2} on tier-2</div>
      </div>
      <div class="kpi-tile">
        <div class="kpi-tile-head">In flight</div>
        <div class="kpi-tile-value">${inflight}</div>
        <div class="kpi-tile-meta">currently translating</div>
      </div>
      <div class="kpi-tile">
        <div class="kpi-tile-head">Failed</div>
        <div class="kpi-tile-value ${failed > 0 ? 'error' : 'ok'}">${failed}</div>
        <div class="kpi-tile-meta">${stats.cacheFailedSanitizedBothTiers ?? 0} sanitized · ${stats.cacheFailedRefused ?? 0} refused</div>
      </div>
      <div class="kpi-tile">
        <div class="kpi-tile-head">Throughput · last hour</div>
        <div class="kpi-tile-value">${through}</div>
        <div class="kpi-tile-meta">cache hit rate ${hitRate}</div>
      </div>
    </div>
  `;
}

function renderQueueTable(rows) {
  if (!rows || rows.length === 0) {
    return `
      <div class="empty-state">
        <div class="empty-state-title">Queue is empty</div>
        <div class="empty-state-body">No translations pending. Newly enriched titles auto-queue.</div>
      </div>`;
  }
  return `
    <div class="wb-table-wrap">
      <table class="wb-table">
        <thead><tr>
          <th style="width:90px">Status</th>
          <th style="width:110px">Title</th>
          <th>Source text</th>
          <th style="width:60px" class="num">Pri</th>
          <th style="width:90px">Submitted</th>
        </tr></thead>
        <tbody>
          ${rows.map(r => `
            <tr>
              <td><span class="pill ${r.status === 'IN_FLIGHT' ? 'warn' : ''}">${escapeHtml(String(r.status || '').toLowerCase())}</span></td>
              <td class="mono">${r.titleCode
                ? `<a href="/v2-title-detail.html?code=${encodeURIComponent(r.titleCode)}" style="color:var(--accent-fg);text-decoration:none">${escapeHtml(r.titleCode)}</a>`
                : '<span style="color:var(--text-faint)">—</span>'}</td>
              <td title="${escapeHtml(r.sourceText)}">${escapeHtml(truncate(r.sourceText, 100))}</td>
              <td class="num">${escapeHtml(String(r.priority ?? ''))}</td>
              <td class="mono">${escapeHtml(timeAgo(r.submittedAt))}</td>
            </tr>
          `).join('')}
        </tbody>
      </table>
    </div>
  `;
}

export async function mountTranslation(rootEl) {
  rootEl.innerHTML = `
    <div class="wb-page">
      <h1 class="wb-page-title">Translation</h1>
      <div class="wb-page-subtitle">Local-LLM translation pipeline status. Refresh updates everything.</div>

      <div class="filter-bar">
        <button class="btn sm" id="btn-refresh">
          <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
          </svg>
          Refresh
        </button>
        <div class="filter-spacer"></div>
        <div class="filter-meta" id="result-meta"></div>
      </div>

      <section class="shelf">
        <div class="shelf-head"><span class="shelf-title">Health</span></div>
        <div id="kpis"><div class="shelf-loading">Loading…</div></div>
      </section>

      <section class="shelf" style="margin-top:24px">
        <div class="shelf-head">
          <span class="shelf-title">Queue preview</span>
          <span class="shelf-meta" id="queue-meta">next ${QUEUE_LIMIT}</span>
        </div>
        <div id="queue"><div class="shelf-loading">Loading…</div></div>
      </section>
    </div>
  `;

  const kpisEl   = rootEl.querySelector('#kpis');
  const queueEl  = rootEl.querySelector('#queue');
  const meta     = rootEl.querySelector('#result-meta');
  const queueMeta = rootEl.querySelector('#queue-meta');

  const load = async () => {
    meta.textContent = 'Loading…';
    const [stats, queue] = await Promise.all([
      fetchJson('/api/translation/stats', null),
      fetchJson(`/api/translation/queue-preview?limit=${QUEUE_LIMIT}`, []),
    ]);
    kpisEl.innerHTML = renderKpis(stats);
    queueEl.innerHTML = renderQueueTable(queue);
    queueMeta.textContent = `${queue?.length || 0} of ${stats?.queuePending ?? '?'} pending`;
    if (stats?.currentModelId) {
      meta.textContent = `model · ${stats.currentModelId}`;
    } else {
      meta.textContent = '';
    }
  };

  rootEl.querySelector('#btn-refresh').addEventListener('click', load);
  load();
}
