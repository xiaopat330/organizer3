/* ─────────────────────────────────────────────────────────────────────
   Wave 4 — Translation (workbench mode, full port)
   Spec: spec/DESIGN_SYSTEM_PAGES.md
   Tabs (matching legacy): Dashboard · Failures · Names
     - Dashboard: 4 stat cards (Throughput / Cache health / Curation
                  backlog / Models) + Queue preview + Live Activity feed
     - Failures: recent failures table
     - Names: stage-name map (kanji ↔ English)
   URL hash tracks current tab. Polling intervals match legacy:
   stats 5s, queue 5s, activity 2s.
   ───────────────────────────────────────────────────────────────────── */

import { humanizeEnumLabel } from './enrichment/utils.js';

const QUEUE_LIMIT       = 15;
const FAILURE_LIMIT     = 50;
const STATS_POLL_MS     = 5000;
const QUEUE_POLL_MS     = 5000;
const ACTIVITY_POLL_MS  = 2000;
const ACTIVITY_MAX_ROWS = 200;

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

function fmt(n) {
  if (n == null) return '—';
  return Number(n).toLocaleString();
}
function N(v) { return v == null ? 0 : Number(v); }

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

/* ── Backlog ETA classification (matches legacy heuristic) ─────────── */
function classifyEta(backlog, throughput) {
  if (!backlog || backlog === 0)   return { tier: 'ok',    label: 'caught up' };
  if (!throughput || throughput === 0) return { tier: 'warn', label: 'stalled' };
  const hours = backlog / throughput;
  if (hours < 1)   return { tier: 'ok',    label: `~${Math.ceil(hours * 60)}m` };
  if (hours < 6)   return { tier: 'ok',    label: `~${hours.toFixed(1)}h` };
  if (hours < 24)  return { tier: 'warn',  label: `~${Math.round(hours)}h` };
  return                  { tier: 'error', label: `~${Math.round(hours / 24)}d` };
}

/* ── Donut for cache success/fail ──────────────────────────────────── */
function renderDonut(ok, fail) {
  const total = ok + fail;
  if (total === 0) return `<div class="trans-donut empty">no data</div>`;
  const r = 30, c = 2 * Math.PI * r;
  const okFrac = ok / total;
  const okLen = c * okFrac;
  return `
    <svg class="trans-donut" viewBox="0 0 80 80" width="80" height="80">
      <circle cx="40" cy="40" r="${r}" fill="none" stroke="rgba(248,113,113,0.25)" stroke-width="10"/>
      <circle cx="40" cy="40" r="${r}" fill="none" stroke="var(--ok)" stroke-width="10"
              stroke-dasharray="${okLen} ${c - okLen}" stroke-dashoffset="${c / 4}"/>
      <text x="40" y="44" text-anchor="middle" font-family="var(--font-mono)" font-size="13" fill="var(--text)">${Math.round(okFrac * 100)}%</text>
    </svg>
  `;
}

/* ── Failure breakdown rows + Re-queue ─────────────────────────────── */
function failureRow(label, count, reasonKey, hint) {
  return `
    <div class="trans-failure-row">
      <span class="trans-failure-label" title="${escapeHtml(hint)}">${escapeHtml(label)}</span>
      <span class="trans-failure-count">${fmt(count)}</span>
      <button class="btn sm" data-requeue="${escapeHtml(reasonKey)}" ${count === 0 ? 'disabled' : ''}>Re-queue</button>
    </div>
  `;
}

/* ── Dashboard stat cards ──────────────────────────────────────────── */
function renderStatCards(stats, sweeper, strategies, health, expanded) {
  if (!stats) return `<div class="shelf-loading">Stats unavailable.</div>`;
  const pending  = N(stats.queuePending);
  const inFlight = N(stats.queueInFlight);
  const tier2    = N(stats.queueTier2Pending);
  const thr      = N(stats.throughputLastHour);
  const eta      = classifyEta(pending + inFlight, thr);

  const cacheTotal = N(stats.cacheTotal);
  const cacheOk    = N(stats.cacheSuccessful);
  const cacheFail  = N(stats.cacheFailed);

  const lookupHits   = N(stats.cacheLookupHits);
  const lookupMisses = N(stats.cacheLookupMisses);
  const lookupTotal  = lookupHits + lookupMisses;
  const hitRatePct   = lookupTotal > 0 ? Math.round((lookupHits / lookupTotal) * 1000) / 10 : null;

  const sweeperPaused = sweeper && sweeper.enabled === false;
  const titlesPending = sweeper ? fmt(sweeper.pending) : '—';
  const titlesLabel   = sweeperPaused ? 'Titles awaiting (paused)' : 'Titles awaiting';

  const stageSuggestions = N(stats.stageNameSuggestionsUnreviewed);
  const stageLookup      = N(stats.stageNameLookupSize);

  const failuresPanel = expanded ? `
    <div class="trans-failure-panel">
      ${failureRow('Sanitized',          N(stats.cacheFailedSanitized),            'sanitized',
        'Tier-1 produced sanitized output. Re-queue after substitution-map updates.')}
      ${failureRow('Sanitized (tier-2)', N(stats.cacheFailedSanitizedBothTiers),  'sanitized_both_tiers',
        'Both tiers produced sanitized output. Re-queue to retry on next sweeper tick.')}
      ${failureRow('Unreachable',        N(stats.cacheFailedUnreachable),         'unreachable',
        'AI service was unreachable when these were attempted. Safe to re-queue aggressively.')}
      ${failureRow('Refused',            N(stats.cacheFailedRefused),             'refused',
        'Model refused to translate. Re-queue may help after substitution updates.')}
    </div>
  ` : '';

  // Models card content
  const modelsCard = (() => {
    const ollamaUp = health?.ollamaReachable === true || health?.healthy === true;
    const ollamaPill = `<span class="pill ${ollamaUp ? 'ok' : 'error'}">${ollamaUp ? 'Ollama online' : 'Ollama offline'}</span>`;
    const current = stats.currentModelId || null;

    let primaryRow = `<div class="trans-model-row"><span class="trans-model-role-label">Primary</span><span class="trans-model-missing">loading…</span></div>`;
    let fallbackRow = `<div class="trans-model-row"><span class="trans-model-role-label">Fallback</span><span class="trans-model-missing">loading…</span></div>`;

    if (strategies) {
      // strategies shape varies; try a few common keys
      const primary  = strategies.primary?.modelId || strategies.primaryModelId || strategies.tier1?.modelId || null;
      const fallback = strategies.fallback?.modelId || strategies.fallbackModelId || strategies.tier2?.modelId || null;
      const row = (label, modelId) => {
        const isActive = modelId && current && String(modelId).split(',').map(s => s.trim()).includes(current);
        return `<div class="trans-model-row ${isActive ? 'is-active' : ''}">
          <span class="trans-model-role-label">${label}</span>
          <span class="${modelId ? 'trans-model-name' : 'trans-model-missing'}">${escapeHtml(modelId || 'not configured')}${isActive ? ' <span class="trans-model-active-dot" title="In flight"></span>' : ''}</span>
        </div>`;
      };
      primaryRow  = row('Primary',  primary);
      fallbackRow = row('Fallback', fallback);
    }

    const loaded = (health?.loadedModels || []).map(m =>
      `<div class="trans-model-loaded">${escapeHtml(typeof m === 'string' ? m : (m.name || m.model || ''))}</div>`
    ).join('');

    const currentModel = stats.currentModelId
      ? `<div class="trans-model-row trans-model-current"><span class="trans-model-role-label">Model</span><span class="trans-model-name">${escapeHtml(stats.currentModelId)}</span></div>`
      : '';

    return `
      <div class="trans-card trans-card-models">
        <div class="trans-card-title">AI</div>
        ${ollamaPill}
        ${primaryRow}
        ${fallbackRow}
        ${currentModel}
        ${loaded ? `<div class="trans-loaded-list">${loaded}</div>` : ''}
      </div>
    `;
  })();

  return `
    <div class="trans-cards">

      <div class="trans-card trans-card-throughput">
        <div class="trans-card-title">Throughput</div>
        <div class="trans-card-headline">${fmt(thr)}<span class="trans-card-unit">/hr</span></div>
        <div class="trans-eta trans-eta-${eta.tier}">
          <span class="trans-eta-dot"></span>
          Backlog ETA: <strong>${eta.label}</strong>
        </div>
        <div class="trans-card-sub">
          Pending: <strong>${fmt(pending)}</strong>
          · In flight: <strong>${fmt(inFlight)}</strong>
          · Tier-2: <strong>${fmt(tier2)}</strong>
        </div>
        <div class="trans-card-sub" title="Explicit term substitutions applied since startup. Map size shown in parentheses.">
          Substitutions: <strong>${fmt(N(stats.explicitSubsApplied))}</strong>
          across <strong>${fmt(N(stats.explicitSubsRowsTouched))}</strong> titles
          <span class="trans-subs-mapsize">(map: ${fmt(N(stats.explicitSubsMapSize))})</span>
        </div>
      </div>

      <div class="trans-card trans-card-cache">
        <div class="trans-card-title">Cache health</div>
        <div class="trans-cache-body">
          ${renderDonut(cacheOk, cacheFail)}
          <div class="trans-cache-legend">
            <div class="trans-legend-row">
              <span class="trans-legend-dot ok"></span>
              <strong>${fmt(cacheOk)}</strong> ok
            </div>
            <button class="trans-legend-row trans-failed-toggle ${expanded ? 'expanded' : ''}"
                    type="button" id="failures-toggle" ${cacheFail === 0 ? 'disabled' : ''}>
              <span class="trans-legend-dot fail"></span>
              <strong>${fmt(cacheFail)}</strong> failed
              ${cacheFail > 0 ? `<span class="trans-failed-caret">${expanded ? '▾' : '▸'}</span>` : ''}
            </button>
          </div>
        </div>
        <div class="trans-card-sub" title="Cache lookups serviced since process startup.">
          Hit rate: <strong>${hitRatePct === null ? '—' : hitRatePct + '%'}</strong>
          <span class="trans-subs-mapsize">(${fmt(lookupHits)} / ${fmt(lookupTotal)} lookups)</span>
        </div>
        ${failuresPanel}
      </div>

      <div class="trans-card trans-card-curation">
        <div class="trans-card-title">Curation backlog</div>
        <div class="trans-cta ${stageSuggestions > 0 ? 'has-work' : ''}">
          <div class="trans-cta-num">${fmt(stageSuggestions)}</div>
          <div class="trans-cta-label">Stage-name suggestion${stageSuggestions === 1 ? '' : 's'} awaiting review</div>
        </div>
        <div class="trans-card-sub trans-titles-row">
          <span>${titlesLabel}: <strong>${titlesPending}</strong></span>
          <button class="btn sm" id="sweep-now-btn"
                  title="Forces an immediate sweep. Same dedup as the scheduled sweeper.">
            Sweep
          </button>
        </div>
        <div class="trans-card-sub">
          Stage-name lookup: <strong>${fmt(stageLookup)}</strong>
        </div>
      </div>

      ${modelsCard}

    </div>
  `;
}

/* ── Queue preview row + Activity row ──────────────────────────────── */
function renderQueueRow(r) {
  const inFlight = r.status === 'IN_FLIGHT';
  return `
    <li class="trans-queue-row ${inFlight ? 'inflight' : 'pending'}">
      <span class="pill ${inFlight ? 'warn' : ''}">${escapeHtml(humanizeEnumLabel(r.status || ''))}</span>
      <span class="trans-queue-code">${r.titleCode
        ? `<a href="/v2-title-detail.html?code=${encodeURIComponent(r.titleCode)}" style="color:var(--accent-fg);text-decoration:none">${escapeHtml(r.titleCode)}</a>`
        : '<span style="color:var(--text-faint)">—</span>'}</span>
      <span class="trans-queue-src" title="${escapeHtml(r.sourceText)}">${escapeHtml(truncate(r.sourceText, 100))}</span>
      <span class="trans-queue-time">${escapeHtml(timeAgo(r.submittedAt))}</span>
    </li>
  `;
}

function renderActivityRow(r) {
  const failed = !!r.failureReason;
  const status = failed
    ? `<span class="pill error" title="${escapeHtml(r.failureReason)}">${escapeHtml(truncate(humanizeEnumLabel(r.failureReason), 14))}</span>`
    : `<span class="pill ok">ok</span>`;
  const titleCell = r.titleCode
    ? `<a href="/v2-title-detail.html?code=${encodeURIComponent(r.titleCode)}" style="color:var(--accent-fg);text-decoration:none">${escapeHtml(r.titleCode)}</a>`
    : '<span style="color:var(--text-faint)">—</span>';
  return `
    <li class="trans-activity-row">
      ${status}
      <span class="trans-activity-code">${titleCell}</span>
      <span class="trans-activity-src" title="${escapeHtml(r.sourceText)}">${escapeHtml(truncate(r.sourceText, 60))}</span>
      <span class="trans-activity-arrow">→</span>
      <span class="trans-activity-en" title="${escapeHtml(r.englishText || '')}">${escapeHtml(truncate(r.englishText || '', 60))}</span>
      <span class="trans-activity-meta">${r.latencyMs != null ? r.latencyMs + 'ms · ' : ''}${escapeHtml(timeAgo(r.cachedAt))}</span>
    </li>
  `;
}

/* ── Dashboard tab ─────────────────────────────────────────────────── */
const dashState = {
  panel: null,
  expanded: false,            // failure-breakdown panel toggle
  stats: null,
  sweeper: null,
  strategies: null,
  health: null,
  queue: [],
  activity: [],               // newest first
  activitySince: null,        // high-water-mark cachedAt
  paused: false,
  timers: { stats: null, queue: null, activity: null },
};

async function loadStats() {
  const [stats, sweeper, strategies, health] = await Promise.all([
    fetchJson('/api/translation/stats',                null),
    fetchJson('/api/translation/title-sweeper-status', null),
    fetchJson('/api/translation/strategies',           null),
    fetchJson('/api/translation/health',               null),
  ]);
  dashState.stats = stats;
  dashState.sweeper = sweeper;
  dashState.strategies = strategies;
  dashState.health = health;
  renderDashCards();
  updateBadgesFromStats();
}

async function loadQueue() {
  dashState.queue = (await fetchJson(`/api/translation/queue-preview?limit=${QUEUE_LIMIT}`, [])) || [];
  renderDashQueue();
}

async function loadActivity() {
  if (dashState.paused) return;
  const url = dashState.activitySince
    ? `/api/translation/recent-events?limit=50&since=${encodeURIComponent(dashState.activitySince)}`
    : `/api/translation/recent-events?limit=50`;
  const events = await fetchJson(url, []);
  if (!events || events.length === 0) {
    renderDashActivity();
    return;
  }
  events.sort((a, b) => (b.cachedAt || '').localeCompare(a.cachedAt || ''));
  dashState.activitySince = events[0].cachedAt;
  dashState.activity = [...events, ...dashState.activity].slice(0, ACTIVITY_MAX_ROWS);
  renderDashActivity();
}

function renderDashCards() {
  if (!dashState.panel) return;
  const cards = dashState.panel.querySelector('#dash-cards');
  if (!cards) return;
  cards.innerHTML = renderStatCards(dashState.stats, dashState.sweeper, dashState.strategies, dashState.health, dashState.expanded);

  // Wire failures-toggle
  const toggle = cards.querySelector('#failures-toggle');
  if (toggle) toggle.addEventListener('click', () => {
    dashState.expanded = !dashState.expanded;
    renderDashCards();
  });

  // Wire Sweep button
  const sweepBtn = cards.querySelector('#sweep-now-btn');
  if (sweepBtn) sweepBtn.addEventListener('click', async () => {
    sweepBtn.disabled = true;
    const orig = sweepBtn.textContent;
    sweepBtn.textContent = '…';
    try {
      const r = await fetch('/api/translation/sweep-title-backlog-now', { method: 'POST' });
      sweepBtn.textContent = r.ok ? 'queued' : 'failed';
    } catch (e) {
      sweepBtn.textContent = 'failed';
    }
    setTimeout(() => { sweepBtn.textContent = orig; sweepBtn.disabled = false; loadStats(); }, 1500);
  });

  // Wire failure re-queue buttons
  cards.querySelectorAll('button[data-requeue]').forEach(btn => {
    btn.addEventListener('click', async () => {
      const reason = btn.dataset.requeue;
      btn.disabled = true;
      const orig = btn.textContent;
      btn.textContent = '…';
      try {
        const url = reason === 'sanitized_both_tiers'
          ? '/api/translation/requeue-sanitized-both-tiers'
          : `/api/translation/requeue-by-reason?reason=${encodeURIComponent(reason)}`;
        const r = await fetch(url, { method: 'POST' });
        btn.textContent = r.ok ? 'queued' : 'failed';
      } catch (e) {
        btn.textContent = 'failed';
      }
      setTimeout(() => { btn.textContent = orig; loadStats(); }, 1500);
    });
  });
}

function renderDashQueue() {
  if (!dashState.panel) return;
  const queueEl = dashState.panel.querySelector('#dash-queue');
  const meta    = dashState.panel.querySelector('#dash-queue-meta');
  if (!queueEl) return;
  if (dashState.queue.length === 0) {
    queueEl.innerHTML = `<li class="trans-empty dis-empty">Queue is empty.</li>`;
  } else {
    queueEl.innerHTML = dashState.queue.map(renderQueueRow).join('');
  }
  if (meta) meta.textContent = `${dashState.queue.length} of ${dashState.stats?.queuePending ?? '?'} pending`;
}

function renderDashActivity() {
  if (!dashState.panel) return;
  const list = dashState.panel.querySelector('#dash-activity');
  const meta = dashState.panel.querySelector('#dash-activity-meta');
  if (!list) return;
  if (dashState.activity.length === 0) {
    list.innerHTML = `<li class="trans-empty dis-empty">Waiting for first event…</li>`;
  } else {
    list.innerHTML = dashState.activity.map(renderActivityRow).join('');
  }
  if (meta) meta.textContent = `${dashState.activity.length} events${dashState.paused ? ' · paused' : ''}`;
}

function renderDashShell(panel) {
  panel.innerHTML = `
    <div id="dash-cards"><div class="shelf-loading">Loading…</div></div>

    <section class="shelf" style="margin-top:24px">
      <div class="shelf-head">
        <span class="shelf-title">Queue</span>
        <span class="shelf-meta" id="dash-queue-meta"></span>
      </div>
      <ul class="trans-queue" id="dash-queue"><li class="trans-empty">Loading…</li></ul>
    </section>

    <section class="shelf" style="margin-top:24px">
      <div class="shelf-head">
        <span class="shelf-title">Live activity</span>
        <span class="shelf-meta" id="dash-activity-meta">polling every 2s</span>
        <button class="btn sm" id="dash-pause" style="margin-left:auto">Pause</button>
        <button class="btn sm" id="dash-clear">Clear</button>
      </div>
      <ul class="trans-activity" id="dash-activity"><li class="trans-empty">Connecting…</li></ul>
    </section>
  `;
  panel.querySelector('#dash-pause').addEventListener('click', () => {
    dashState.paused = !dashState.paused;
    panel.querySelector('#dash-pause').textContent = dashState.paused ? 'Resume' : 'Pause';
    renderDashActivity();
  });
  panel.querySelector('#dash-clear').addEventListener('click', () => {
    dashState.activity = [];
    renderDashActivity();
  });
}

async function loadDashboardTab(panel) {
  dashState.panel = panel;
  if (!panel.querySelector('#dash-cards')) {
    renderDashShell(panel);
    if (dashState.paused) panel.querySelector('#dash-pause').textContent = 'Resume';
  }
  // Render whatever we have cached (instant when re-opening tab)
  renderDashCards();
  renderDashQueue();
  renderDashActivity();

  // Start polling timers if not running
  if (!dashState.timers.stats) {
    await Promise.all([loadStats(), loadQueue(), loadActivity()]);
    dashState.timers.stats    = setInterval(loadStats,    STATS_POLL_MS);
    dashState.timers.queue    = setInterval(loadQueue,    QUEUE_POLL_MS);
    dashState.timers.activity = setInterval(loadActivity, ACTIVITY_POLL_MS);
  }
  return dashState.stats;
}

/* ── Failures tab ──────────────────────────────────────────────────── */
async function loadFailuresTab(panel) {
  panel.innerHTML = `<div class="shelf-loading">Loading…</div>`;
  const rows = await fetchJson(`/api/translation/recent-failures?limit=${FAILURE_LIMIT}`, []);
  if (!rows || rows.length === 0) {
    panel.innerHTML = `<div class="dis-empty">No recent failures<br>The translation pipeline is clean.</div>`;
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
              <td><span class="pill error">${escapeHtml(truncate(humanizeEnumLabel(r.failureReason || 'unknown'), 18))}</span></td>
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

/* ── Names tab ─────────────────────────────────────────────────────── */
async function loadNamesTab(panel) {
  panel.innerHTML = `<div class="shelf-loading">Loading…</div>`;
  const map = await fetchJson('/api/translation/stage-name-map', {});
  const entries = Object.entries(map || {});
  if (entries.length === 0) {
    panel.innerHTML = `<div class="dis-empty">No stage-name mappings<br>No kanji → English actress name mappings have been registered.</div>`;
    return entries;
  }
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

/* ── Bootstrap ─────────────────────────────────────────────────────── */
const TABS = [
  { id: 'dashboard', label: 'Dashboard', load: loadDashboardTab },
  { id: 'failures',  label: 'Failures',  load: loadFailuresTab,  badge: () => dashState.stats?.queueFailed },
  { id: 'names',     label: 'Names',     load: loadNamesTab,     badge: () => dashState.stats?.stageNameLookupSize },
];

function updateBadgesFromStats() {
  for (const t of TABS) {
    const el = document.querySelector(`[data-badge="${t.id}"]`);
    if (!el) continue;
    const v = t.badge ? t.badge() : null;
    el.textContent = (v == null || v === 0) ? '' : String(v);
  }
  // Update the KPI strip below the page title
  const strip = document.getElementById('trans-kpi-strip');
  if (strip && dashState.stats) {
    const s = dashState.stats;
    const thr      = N(s.throughputLastHour);
    const pending  = N(s.queuePending);
    const inFlight = N(s.queueInFlight);
    const tier2    = N(s.queueTier2Pending);
    strip.textContent = `${fmt(thr)}/hr · ${fmt(pending)} pending · ${fmt(inFlight)} in flight · ${fmt(tier2)} tier-2`;
  }
}

export async function mountTranslation(rootEl) {
  rootEl.innerHTML = `
    <div class="wb-page">
      <h1 class="wb-page-title">Translation</h1>
      <div class="dis-kpi-strip" id="trans-kpi-strip">—/hr · — pending · — in flight · — tier-2</div>

      <div class="filter-bar">
        <button class="btn sm" id="btn-refresh">Refresh now</button>
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

  const tabsEl = rootEl.querySelector('.tabs');
  const panels = Object.fromEntries(TABS.map(t => [t.id, rootEl.querySelector(`[data-panel="${t.id}"]`)]));

  const loaded = {};
  const activate = async (id) => {
    rootEl.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b.dataset.tab === id));
    rootEl.querySelectorAll('.tab-panel').forEach(p => p.classList.toggle('active', p.dataset.panel === id));
    location.hash = id;
    if (!loaded[id]) {
      const tab = TABS.find(t => t.id === id);
      await tab.load(panels[id]);
      loaded[id] = true;
    }
  };

  tabsEl.addEventListener('click', (e) => {
    const btn = e.target.closest('.tab');
    if (!btn) return;
    activate(btn.dataset.tab);
  });

  rootEl.querySelector('#btn-refresh').addEventListener('click', () => {
    // Force-reload the current tab
    const current = rootEl.querySelector('.tab.active')?.dataset.tab || 'dashboard';
    if (current === 'dashboard') {
      loadStats(); loadQueue(); loadActivity();
    } else {
      loaded[current] = false;
      activate(current);
    }
  });

  const initial = location.hash.replace('#', '');
  const startTab = TABS.find(t => t.id === initial)?.id || 'dashboard';
  activate(startTab);
}
