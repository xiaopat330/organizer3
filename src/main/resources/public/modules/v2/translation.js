/* ─────────────────────────────────────────────────────────────────────
   Wave 3 — Translation (workbench mode, multi-tab)
   Spec: spec/DESIGN_SYSTEM_PAGES.md (workbench surfaces sweep)
   Tabs: Queue · Failures · Names · Sweeper
   Each tab loads on activation. URL hash tracks current tab.
   ───────────────────────────────────────────────────────────────────── */

const QUEUE_LIMIT     = 25;
const FAILURE_LIMIT   = 50;
const DASH_POLL_MS    = 2000;
const DASH_MAX_ROWS   = 200;

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

/* ── Dashboard tab — live activity feed ────────────────────────────── */
// Shared state for the dashboard tab; survives tab switches so polling
// continues to add rows in the background.
const dashState = {
  rows: [],          // newest first
  since: null,       // high-water-mark cachedAt
  timer: null,
  paused: false,
  panel: null,
};

function renderDashRow(r) {
  const failed = !!r.failureReason;
  const status = failed
    ? `<span class="pill error" title="${escapeHtml(r.failureReason)}">${escapeHtml(truncate(r.failureReason, 14))}</span>`
    : `<span class="pill ok">ok</span>`;
  const titleCell = r.titleCode
    ? `<a href="/v2-title-detail.html?code=${encodeURIComponent(r.titleCode)}" style="color:var(--accent-fg);text-decoration:none">${escapeHtml(r.titleCode)}</a>`
    : '<span style="color:var(--text-faint)">—</span>';
  return `
    <tr>
      <td>${status}</td>
      <td class="mono">${titleCell}</td>
      <td title="${escapeHtml(r.sourceText)}">${escapeHtml(truncate(r.sourceText, 80))}</td>
      <td title="${escapeHtml(r.englishText || '')}">${escapeHtml(truncate(r.englishText || '', 80))}</td>
      <td class="num">${r.latencyMs != null ? escapeHtml(String(r.latencyMs) + 'ms') : '—'}</td>
      <td class="mono">${escapeHtml(timeAgo(r.cachedAt))}</td>
    </tr>
  `;
}

function renderDashShell(panel) {
  panel.innerHTML = `
    <div class="filter-bar">
      <button class="btn sm" id="dash-pause">Pause</button>
      <button class="btn sm" id="dash-clear">Clear</button>
      <div class="filter-spacer"></div>
      <div class="filter-meta" id="dash-meta">connecting…</div>
    </div>
    <div class="wb-table-wrap">
      <table class="wb-table">
        <thead><tr>
          <th style="width:90px">Status</th>
          <th style="width:110px">Title</th>
          <th>Source</th>
          <th>English</th>
          <th style="width:70px" class="num">Latency</th>
          <th style="width:90px">When</th>
        </tr></thead>
        <tbody id="dash-rows"></tbody>
      </table>
    </div>
  `;
  panel.querySelector('#dash-pause').addEventListener('click', () => {
    dashState.paused = !dashState.paused;
    panel.querySelector('#dash-pause').textContent = dashState.paused ? 'Resume' : 'Pause';
  });
  panel.querySelector('#dash-clear').addEventListener('click', () => {
    dashState.rows = [];
    panel.querySelector('#dash-rows').innerHTML = '';
  });
}

async function pollDash() {
  if (dashState.paused) return;
  const url = dashState.since
    ? `/api/translation/recent-events?limit=50&since=${encodeURIComponent(dashState.since)}`
    : `/api/translation/recent-events?limit=50`;
  const events = await fetchJson(url, []);
  if (!events) return;

  if (events.length > 0) {
    // Server returns newest first OR oldest first depending on impl; sort so newest first
    events.sort((a, b) => (b.cachedAt || '').localeCompare(a.cachedAt || ''));
    // Update high-water-mark with the newest cachedAt seen
    dashState.since = events[0].cachedAt;
    // Prepend new events; cap total
    dashState.rows = [...events, ...dashState.rows].slice(0, DASH_MAX_ROWS);
  }

  // Re-render only if the tab is currently visible (cheap optimization)
  const panel = dashState.panel;
  if (panel && panel.classList.contains('active')) {
    const tbody = panel.querySelector('#dash-rows');
    if (tbody) {
      tbody.innerHTML = dashState.rows.map(renderDashRow).join('');
      const meta = panel.querySelector('#dash-meta');
      if (meta) {
        meta.textContent = `${dashState.rows.length} events · last update ${timeAgo(dashState.since)}${dashState.paused ? ' · paused' : ''}`;
      }
    }
  }
}

async function loadDashboardTab(panel) {
  dashState.panel = panel;
  if (!panel.querySelector('#dash-rows')) {
    renderDashShell(panel);
    // Restore current pause state UI
    if (dashState.paused) panel.querySelector('#dash-pause').textContent = 'Resume';
    // Initial render of any cached rows
    panel.querySelector('#dash-rows').innerHTML = dashState.rows.map(renderDashRow).join('');
  }
  if (!dashState.timer) {
    await pollDash();
    dashState.timer = setInterval(pollDash, DASH_POLL_MS);
  } else {
    // Force a render of cached rows when reopening
    pollDash();
  }
}

/* ── Queue tab ─────────────────────────────────────────────────────── */
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

async function loadQueueTab(panel) {
  panel.innerHTML = `
    <section class="shelf"><div class="shelf-head"><span class="shelf-title">Health</span></div>
      <div id="kpis"><div class="shelf-loading">Loading…</div></div></section>
    <section class="shelf" style="margin-top:24px"><div class="shelf-head">
      <span class="shelf-title">Queue preview</span>
      <span class="shelf-meta" id="queue-meta">next ${QUEUE_LIMIT}</span>
    </div><div id="queue"><div class="shelf-loading">Loading…</div></div></section>
  `;
  const [stats, queue] = await Promise.all([
    fetchJson('/api/translation/stats', null),
    fetchJson(`/api/translation/queue-preview?limit=${QUEUE_LIMIT}`, []),
  ]);
  panel.querySelector('#kpis').innerHTML = renderKpis(stats);
  panel.querySelector('#queue').innerHTML = renderQueueTable(queue);
  panel.querySelector('#queue-meta').textContent = `${queue?.length || 0} of ${stats?.queuePending ?? '?'} pending`;
  return stats;
}

/* ── Failures tab ──────────────────────────────────────────────────── */
async function loadFailuresTab(panel) {
  panel.innerHTML = `<div class="shelf-loading">Loading…</div>`;
  const rows = await fetchJson(`/api/translation/recent-failures?limit=${FAILURE_LIMIT}`, []);
  if (!rows || rows.length === 0) {
    panel.innerHTML = `<div class="empty-state">
      <div class="empty-state-title">No recent failures</div>
      <div class="empty-state-body">The translation pipeline is clean.</div></div>`;
    return rows;
  }
  panel.innerHTML = `
    <div class="wb-table-wrap">
      <table class="wb-table">
        <thead><tr>
          <th style="width:140px">Reason</th>
          <th style="width:110px">Title</th>
          <th>Source text</th>
          <th style="width:70px" class="num">Latency</th>
          <th style="width:90px">When</th>
        </tr></thead>
        <tbody>
          ${rows.map(r => `
            <tr title="${escapeHtml(r.failureReason || '')}">
              <td><span class="pill error">${escapeHtml(truncate(r.failureReason || 'unknown', 18))}</span></td>
              <td class="mono">${r.titleCode
                ? `<a href="/v2-title-detail.html?code=${encodeURIComponent(r.titleCode)}" style="color:var(--accent-fg);text-decoration:none">${escapeHtml(r.titleCode)}</a>`
                : '<span style="color:var(--text-faint)">—</span>'}</td>
              <td title="${escapeHtml(r.sourceText)}">${escapeHtml(truncate(r.sourceText, 100))}</td>
              <td class="num">${escapeHtml(r.latencyMs != null ? String(r.latencyMs) + 'ms' : '—')}</td>
              <td class="mono">${escapeHtml(timeAgo(r.cachedAt))}</td>
            </tr>
          `).join('')}
        </tbody>
      </table>
    </div>
  `;
  return rows;
}

/* ── Names tab (stage-name map) ────────────────────────────────────── */
async function loadNamesTab(panel) {
  panel.innerHTML = `<div class="shelf-loading">Loading…</div>`;
  const map = await fetchJson('/api/translation/stage-name-map', {});
  const entries = Object.entries(map || {});
  if (entries.length === 0) {
    panel.innerHTML = `<div class="empty-state">
      <div class="empty-state-title">No stage-name mappings</div>
      <div class="empty-state-body">No kanji → English actress name mappings have been registered.</div></div>`;
    return entries;
  }
  // Sort by english name
  entries.sort((a, b) => String(a[1]).localeCompare(String(b[1])));
  panel.innerHTML = `
    <div class="filter-bar">
      <input class="form-input" id="name-search" placeholder="Filter by kanji or English name…" style="max-width:320px">
      <div class="filter-spacer"></div>
      <div class="filter-meta">${entries.length} mappings</div>
    </div>
    <div class="wb-table-wrap">
      <table class="wb-table">
        <thead><tr>
          <th style="width:140px">Kanji / source</th>
          <th>English (canonical)</th>
        </tr></thead>
        <tbody id="name-rows">
          ${entries.map(([k, v]) => `
            <tr data-k="${escapeHtml(k.toLowerCase())}" data-v="${escapeHtml(String(v).toLowerCase())}">
              <td class="mono">${escapeHtml(k)}</td>
              <td>${escapeHtml(String(v))}</td>
            </tr>
          `).join('')}
        </tbody>
      </table>
    </div>
  `;
  // Wire local filter
  const input = panel.querySelector('#name-search');
  const rows  = panel.querySelectorAll('#name-rows tr');
  input.addEventListener('input', () => {
    const q = input.value.trim().toLowerCase();
    rows.forEach(tr => {
      const hit = !q || tr.dataset.k.includes(q) || tr.dataset.v.includes(q);
      tr.style.display = hit ? '' : 'none';
    });
  });
  return entries;
}

/* ── Sweeper tab ───────────────────────────────────────────────────── */
async function loadSweeperTab(panel) {
  panel.innerHTML = `<div class="shelf-loading">Loading…</div>`;
  const status = await fetchJson('/api/translation/title-sweeper-status', null);
  if (!status) {
    panel.innerHTML = `<div class="empty-state">
      <div class="empty-state-title">Sweeper status unavailable</div></div>`;
    return null;
  }
  const enabledCls = status.enabled ? 'ok' : 'warn';
  panel.innerHTML = `
    <section class="shelf"><div class="shelf-head"><span class="shelf-title">Title sweeper</span></div>
      <div class="shelf-grid shelf-grid-tiles">
        <div class="kpi-tile">
          <div class="kpi-tile-head">Awaiting translation</div>
          <div class="kpi-tile-value ${status.pending > 0 ? 'warn' : 'ok'}">${status.pending ?? 0}</div>
          <div class="kpi-tile-meta">titles with untranslated original text</div>
        </div>
        <div class="kpi-tile">
          <div class="kpi-tile-head">Sweeper enabled</div>
          <div class="kpi-tile-value ${enabledCls}">${status.enabled ? 'yes' : 'no'}</div>
          <div class="kpi-tile-meta">scheduled background sweep</div>
        </div>
        ${Object.entries(status).filter(([k]) => !['pending','enabled'].includes(k)).map(([k, v]) => `
          <div class="kpi-tile">
            <div class="kpi-tile-head">${escapeHtml(k)}</div>
            <div class="kpi-tile-value" style="font-size:14px">${escapeHtml(String(v))}</div>
          </div>
        `).join('')}
      </div>
    </section>
  `;
  return status;
}

/* ── Bootstrap ─────────────────────────────────────────────────────── */
const TABS = [
  { id: 'dashboard', label: 'Dashboard', load: loadDashboardTab },
  { id: 'queue',     label: 'Queue',     load: loadQueueTab,    badge: (s) => s?.queuePending },
  { id: 'failures',  label: 'Failures',  load: loadFailuresTab, badge: (s) => s?.queueFailed },
  { id: 'names',     label: 'Names',     load: loadNamesTab,    badge: (s) => s?.stageNameLookupSize },
  { id: 'sweeper',   label: 'Sweeper',   load: loadSweeperTab },
];

export async function mountTranslation(rootEl) {
  rootEl.innerHTML = `
    <div class="wb-page">
      <h1 class="wb-page-title">Translation</h1>
      <div class="wb-page-subtitle">Local-LLM translation pipeline.</div>

      <div class="filter-bar">
        <button class="btn sm" id="btn-refresh">
          <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
          </svg>
          Refresh
        </button>
        <div class="filter-spacer"></div>
        <div class="filter-meta" id="model-meta"></div>
      </div>

      <div class="tabs" role="tablist">
        ${TABS.map((t, i) => `
          <button class="tab${i === 0 ? ' active' : ''}" role="tab" data-tab="${t.id}">
            ${escapeHtml(t.label)} <span class="badge" data-badge="${t.id}"></span>
          </button>`).join('')}
      </div>

      ${TABS.map((t, i) => `
        <div class="tab-panel${i === 0 ? ' active' : ''}" data-panel="${t.id}"></div>
      `).join('')}
    </div>
  `;

  const tabsEl   = rootEl.querySelector('.tabs');
  const panels   = Object.fromEntries(TABS.map(t => [t.id, rootEl.querySelector(`[data-panel="${t.id}"]`)]));
  const badges   = Object.fromEntries(TABS.map(t => [t.id, rootEl.querySelector(`[data-badge="${t.id}"]`)]));
  const modelMeta = rootEl.querySelector('#model-meta');

  // Track loaded state so re-clicks don't refetch unless Refresh is pressed
  const loaded = {};
  let lastStats = null;

  const updateBadges = () => {
    for (const t of TABS) {
      const v = t.badge ? t.badge(lastStats) : null;
      badges[t.id].textContent = (v == null || v === 0) ? '' : String(v);
    }
  };

  const activate = async (id, force = false) => {
    rootEl.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b.dataset.tab === id));
    rootEl.querySelectorAll('.tab-panel').forEach(p => p.classList.toggle('active', p.dataset.panel === id));
    location.hash = id;

    if (!loaded[id] || force) {
      const tab = TABS.find(t => t.id === id);
      const result = await tab.load(panels[id]);
      loaded[id] = true;
      // The Queue tab returns full stats — capture for badges + model.
      if (id === 'queue' && result) {
        lastStats = result;
        modelMeta.textContent = result.currentModelId ? `model · ${result.currentModelId}` : '';
        updateBadges();
      } else if (id === 'failures' || id === 'names') {
        // Refresh badge from stats (already cached) — these tabs don't return stats
        updateBadges();
      }
    }
  };

  tabsEl.addEventListener('click', (e) => {
    const btn = e.target.closest('.tab');
    if (!btn) return;
    activate(btn.dataset.tab);
  });

  rootEl.querySelector('#btn-refresh').addEventListener('click', () => {
    // Force-reload current tab + invalidate everything else
    const current = rootEl.querySelector('.tab.active')?.dataset.tab || 'queue';
    Object.keys(loaded).forEach(k => { loaded[k] = false; });
    activate(current, true);
  });

  // Initial — honor URL hash if it's a known tab
  const initial = location.hash.replace('#', '');
  const startTab = TABS.find(t => t.id === initial)?.id || 'queue';
  activate(startTab);
}
