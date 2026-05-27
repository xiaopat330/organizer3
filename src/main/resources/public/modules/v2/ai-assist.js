/* ─────────────────────────────────────────────────────────────────────
   AI Assist dashboard — single-page, no tabs.
   Layout: stat cards → outcome breakdown → queue preview → recent feed.
   Polling: stats+queue every 5s, recent feed every 2s (with since= HWM).
   API contract:
     GET /api/enrichment/assist/dashboard
     GET /api/enrichment/assist/queue-preview?limit=15
     GET /api/enrichment/assist/recent?limit=50&since=<ISO>
   ───────────────────────────────────────────────────────────────────── */

const QUEUE_LIMIT        = 15;
const STATS_POLL_MS      = 5000;
const QUEUE_POLL_MS      = 5000;
const ACTIVITY_POLL_MS   = 2000;
const ACTIVITY_MAX_ROWS  = 200;

/* ── Utilities ─────────────────────────────────────────────────────── */
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
    console.warn('[ai-assist] fetch failed:', url, e);
    return fallback;
  }
}

function fmt(n) {
  if (n == null) return '—';
  return Number(n).toLocaleString();
}

function N(v) { return (v == null || isNaN(Number(v))) ? 0 : Number(v); }

function pct(num, den) {
  if (!den) return '—';
  return Math.round((num / den) * 100) + '%';
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

/* ── Outcome classification ─────────────────────────────────────────
   Maps a raw outcome string (from API) to one of 4 display categories.
   All unknown keys fall into "no-decision".
   ─────────────────────────────────────────────────────────────────── */
const OUTCOME_CATEGORY = {
  agreed:               'conclusive',
  agreed_with_override: 'conclusive',
  phi4_only:            'leaning',
  gemma_only:           'leaning',
  conflict:             'split',
  both_abstain:         'no-decision',
  error:                'no-decision',
};

function classifyOutcome(outcome) {
  return OUTCOME_CATEGORY[outcome] || 'no-decision';
}

/** Aggregate outcomeCounts object into 4 display buckets.
 *  Any key not in the explicit map lands in no-decision. */
function bucketCounts(outcomeCounts) {
  const counts = outcomeCounts || {};
  const buckets = { conclusive: 0, leaning: 0, split: 0, 'no-decision': 0 };
  for (const [key, val] of Object.entries(counts)) {
    const cat = classifyOutcome(key);
    buckets[cat] += N(val);
  }
  return buckets;
}

/* ── Outcome chip (used in the recent-feed rows) ─────────────────── */
function outcomeChip(outcome) {
  const cat = classifyOutcome(outcome);
  const MAP = {
    conclusive:    { symbol: '✓', label: 'conclusive', color: 'var(--ok, #22c55e)'       },
    leaning:       { symbol: '~', label: 'leaning',    color: 'var(--accent, #60a5fa)'   },
    split:         { symbol: '⚠', label: 'split',      color: 'var(--warn, #f59e0b)'     },
    'no-decision': { symbol: '—', label: 'no decision', color: 'var(--text-faint, #888)' },
  };
  const m = MAP[cat] || MAP['no-decision'];
  return `<span class="aia-chip aia-chip-${cat}" title="${escapeHtml(outcome || cat)}" style="color:${m.color};font-weight:600;font-size:0.8em;white-space:nowrap">${m.symbol} ${escapeHtml(m.label)}</span>`;
}

/* ── Stat cards ─────────────────────────────────────────────────────
   4 cards mirroring translation's trans-cards layout.
   ─────────────────────────────────────────────────────────────────── */
function renderStatCards(stats) {
  if (!stats) return `<div class="shelf-loading">Stats unavailable.</div>`;

  const awaitingAi    = N(stats.awaitingAi);
  const inFlight      = N(stats.inFlight);
  const processedTotal = N(stats.processedTotal);
  const autoApplied   = N(stats.autoApplied);
  const buckets       = bucketCounts(stats.outcomeCounts);
  const conclusivePct = pct(buckets.conclusive, processedTotal);
  const splitPct      = pct(buckets.split, processedTotal);

  return `
    <div class="trans-cards">

      <div class="trans-card">
        <div class="trans-card-title">Queue</div>
        <div class="trans-card-headline">${fmt(awaitingAi)}</div>
        <div class="trans-card-sub">${fmt(inFlight)} running</div>
      </div>

      <div class="trans-card">
        <div class="trans-card-title">Processed</div>
        <div class="trans-card-headline">${fmt(processedTotal)}</div>
        <div class="trans-card-sub">all-time</div>
      </div>

      <div class="trans-card">
        <div class="trans-card-title">Auto-applied</div>
        <div class="trans-card-headline">${fmt(autoApplied)}</div>
        <div class="trans-card-sub">applied</div>
      </div>

      <div class="trans-card">
        <div class="trans-card-title">Decision mix</div>
        <div class="trans-card-headline">${conclusivePct}</div>
        <div class="trans-card-sub">conclusive · ${splitPct} split</div>
      </div>

    </div>
  `;
}

/* ── Outcome breakdown bars ─────────────────────────────────────────
   Full-width shelf with one horizontal bar per category.
   Bar fill = count / max(category counts).
   ─────────────────────────────────────────────────────────────────── */
function renderOutcomeBreakdown(stats) {
  const processedTotal = N(stats && stats.processedTotal);
  const autoApplied    = N(stats && stats.autoApplied);
  const buckets        = bucketCounts(stats && stats.outcomeCounts);
  const maxCount       = Math.max(1, ...Object.values(buckets));

  const BAR_DEFS = [
    { key: 'conclusive',   label: 'Conclusive',       color: 'var(--ok, #22c55e)',       subLine: `${fmt(autoApplied)} auto-applied` },
    { key: 'leaning',      label: 'Leaning (1 model)', color: 'var(--accent, #60a5fa)',  subLine: null },
    { key: 'split',        label: 'Split (conflict)',  color: 'var(--warn, #f59e0b)',     subLine: null },
    { key: 'no-decision',  label: 'No decision',       color: 'var(--text-faint, #888)', subLine: null },
  ];

  const rows = BAR_DEFS.map(def => {
    const count    = buckets[def.key] || 0;
    const fillPct  = processedTotal === 0 ? 0 : Math.round((count / maxCount) * 100);
    const sharePct = processedTotal === 0 ? '—' : Math.round((count / processedTotal) * 100) + '%';
    const sub      = def.subLine ? `<div class="aia-bar-sub">${escapeHtml(def.subLine)}</div>` : '';
    return `
      <div class="aia-bar-row">
        <div class="aia-bar-label">${escapeHtml(def.label)}</div>
        <div class="aia-bar-track">
          <div class="aia-bar-fill" style="width:${fillPct}%;background:${def.color}"></div>
        </div>
        <div class="aia-bar-count">${fmt(count)} <span class="aia-bar-pct">(${sharePct})</span></div>
        ${sub}
      </div>
    `;
  }).join('');

  return `
    <section class="shelf" style="margin-top:24px">
      <div class="shelf-head">
        <span class="shelf-title">Outcome breakdown</span>
        <span class="shelf-meta">${fmt(processedTotal)} processed total</span>
      </div>
      <div class="aia-breakdown">
        ${rows}
      </div>
    </section>
  `;
}

/* ── Queue preview rows ─────────────────────────────────────────── */
function renderQueueRow(r) {
  const codeLink = r.code
    ? `<a href="/v2-title-detail.html?code=${encodeURIComponent(r.code)}" style="color:var(--accent-fg);text-decoration:none">${escapeHtml(r.code)}</a>`
    : '<span style="color:var(--text-faint)">—</span>';
  return `
    <li class="trans-queue-row">
      ${codeLink}
      <span class="trans-queue-time">${escapeHtml(timeAgo(r.createdAt))}</span>
    </li>
  `;
}

/* ── Recent feed rows ─────────────────────────────────────────────── */
function renderRecentRow(r) {
  const codeLink = r.code
    ? `<a href="/v2-title-detail.html?code=${encodeURIComponent(r.code)}" style="color:var(--accent-fg);text-decoration:none">${escapeHtml(r.code)}</a>`
    : '<span style="color:var(--text-faint)">—</span>';
  // Always emit all 5 cells so grid placement is invariant regardless of data.
  // Cell 3: reason — empty span when absent (keeps column alignment).
  const reasonContent = r.reason
    ? `${escapeHtml(truncate(r.reason, 70))}`
    : '';
  const reasonTitle = r.reason ? ` title="${escapeHtml(r.reason)}"` : '';
  // Cell 4: auto badge — empty span when not auto-applied (keeps column alignment).
  const badgeContent = r.autoApplied
    ? `<span class="aia-applied-badge">auto</span>`
    : '';
  return `
    <li class="aia-recent-row">
      <span class="aia-recent-code">${codeLink}</span>
      <span class="aia-recent-status">${outcomeChip(r.outcome)}</span>
      <span class="aia-recent-reason"${reasonTitle}>${reasonContent}</span>
      <span class="aia-recent-badge">${badgeContent}</span>
      <span class="aia-recent-time">${escapeHtml(timeAgo(r.at))}</span>
    </li>
  `;
}

/* ── Dashboard shell ─────────────────────────────────────────────── */
function renderDashShell(panel) {
  panel.innerHTML = `
    <style>
      .aia-breakdown { padding: 12px 16px; }
      .aia-bar-row { display: grid; grid-template-columns: 160px 1fr 120px; align-items: center; gap: 10px; padding: 6px 0; }
      .aia-bar-label { font-size: 0.85em; color: var(--text); white-space: nowrap; }
      .aia-bar-track { height: 10px; background: var(--surface-2, rgba(255,255,255,0.06)); border-radius: 5px; overflow: hidden; }
      .aia-bar-fill  { height: 100%; border-radius: 5px; transition: width 0.3s; min-width: 2px; }
      .aia-bar-count { font-size: 0.82em; color: var(--text); text-align: right; }
      .aia-bar-pct   { color: var(--text-faint); }
      .aia-bar-sub   { grid-column: 2 / 4; font-size: 0.78em; color: var(--text-faint); margin-top: -4px; padding-left: 0; }

      /* ── Recently processed feed rows ─────────────────────────────── */
      .aia-recent-row {
        display: grid;
        grid-template-columns: 110px 150px 1fr auto 90px;
        align-items: center;
        gap: 10px;
        padding: 4px 12px;
        border-bottom: 1px solid var(--border, rgba(255,255,255,0.06));
        min-width: 0;
      }
      .aia-recent-row:last-child { border-bottom: none; }
      .aia-recent-code {
        font-family: var(--font-mono, monospace);
        font-size: 0.82em;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .aia-recent-status {
        white-space: nowrap;
        overflow: hidden;
      }
      .aia-recent-reason {
        font-size: 0.83em;
        color: var(--text-faint);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        min-width: 0;
      }
      .aia-recent-badge {
        display: flex;
        align-items: center;
        justify-content: flex-end;
        white-space: nowrap;
      }
      .aia-applied-badge {
        background: var(--ok, #22c55e);
        color: #000;
        font-size: 0.72em;
        padding: 1px 5px;
        border-radius: 3px;
        white-space: nowrap;
      }
      .aia-recent-time {
        font-family: var(--font-mono, monospace);
        font-size: 0.80em;
        color: var(--text-faint);
        text-align: right;
        white-space: nowrap;
      }
    </style>

    <div id="dash-cards"><div class="shelf-loading">Loading…</div></div>

    <div id="dash-outcome"></div>

    <section class="shelf" style="margin-top:24px">
      <div class="shelf-head">
        <span class="shelf-title">Queue (awaiting AI)</span>
        <span class="shelf-meta" id="dash-queue-meta"></span>
      </div>
      <ul class="trans-queue" id="dash-queue"><li class="trans-empty">Loading…</li></ul>
    </section>

    <section class="shelf" style="margin-top:24px">
      <div class="shelf-head">
        <span class="shelf-title">Recently processed</span>
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

/* ── Render helpers ─────────────────────────────────────────────── */
function renderDashCards() {
  if (!dashState.panel) return;
  const cards = dashState.panel.querySelector('#dash-cards');
  if (!cards) return;
  cards.innerHTML = renderStatCards(dashState.stats);
}

function renderDashOutcome() {
  if (!dashState.panel) return;
  const el = dashState.panel.querySelector('#dash-outcome');
  if (!el) return;
  el.innerHTML = renderOutcomeBreakdown(dashState.stats);
}

function renderDashQueue() {
  if (!dashState.panel) return;
  const queueEl = dashState.panel.querySelector('#dash-queue');
  const meta    = dashState.panel.querySelector('#dash-queue-meta');
  if (!queueEl) return;
  if (!dashState.queue || dashState.queue.length === 0) {
    queueEl.innerHTML = `<li class="trans-empty dis-empty">Queue is empty.</li>`;
  } else {
    queueEl.innerHTML = dashState.queue.map(renderQueueRow).join('');
  }
  if (meta) meta.textContent = `${(dashState.queue || []).length} shown`;
}

function renderDashActivity() {
  if (!dashState.panel) return;
  const list = dashState.panel.querySelector('#dash-activity');
  const meta = dashState.panel.querySelector('#dash-activity-meta');
  if (!list) return;
  if (dashState.activity.length === 0) {
    list.innerHTML = `<li class="trans-empty dis-empty">Waiting for first event…</li>`;
  } else {
    list.innerHTML = dashState.activity.map(renderRecentRow).join('');
  }
  if (meta) meta.textContent = `${dashState.activity.length} events${dashState.paused ? ' · paused' : ' · polling every 2s'}`;
}

/* ── State ───────────────────────────────────────────────────────── */
const dashState = {
  panel: null,
  stats: null,
  queue: [],
  activity: [],         // newest first
  activitySince: null,  // high-water-mark: tracks newest `at` value seen
  paused: false,
  timers: { stats: null, queue: null, activity: null },
};

/* ── Poll functions ─────────────────────────────────────────────── */
async function loadStats() {
  const stats = await fetchJson('/api/enrichment/assist/dashboard', null);
  dashState.stats = stats;
  renderDashCards();
  renderDashOutcome();
}

async function loadQueue() {
  dashState.queue = (await fetchJson(`/api/enrichment/assist/queue-preview?limit=${QUEUE_LIMIT}`, [])) || [];
  renderDashQueue();
}

async function loadActivity() {
  if (dashState.paused) return;
  const url = dashState.activitySince
    ? `/api/enrichment/assist/recent?limit=50&since=${encodeURIComponent(dashState.activitySince)}`
    : `/api/enrichment/assist/recent?limit=50`;
  const events = await fetchJson(url, []);
  if (!events || events.length === 0) {
    renderDashActivity();
    return;
  }
  // Sort newest-first by `at`
  events.sort((a, b) => (b.at || '').localeCompare(a.at || ''));
  // Advance high-water mark to the newest `at` seen
  dashState.activitySince = events[0].at;
  dashState.activity = [...events, ...dashState.activity].slice(0, ACTIVITY_MAX_ROWS);
  renderDashActivity();
}

/* ── Mount ───────────────────────────────────────────────────────── */
export async function mountAiAssist(rootEl) {
  rootEl.innerHTML = `
    <div class="wb-page">
      <h1 class="wb-page-title">AI Assist</h1>
      <div id="aia-panel"></div>
    </div>
  `;

  const panel = rootEl.querySelector('#aia-panel');
  dashState.panel = panel;
  renderDashShell(panel);

  // Restore pause state button label if already paused
  if (dashState.paused) {
    const pauseBtn = panel.querySelector('#dash-pause');
    if (pauseBtn) pauseBtn.textContent = 'Resume';
  }

  // Render whatever we have cached (instant on re-mount)
  renderDashCards();
  renderDashOutcome();
  renderDashQueue();
  renderDashActivity();

  // Start polling timers only if not already running
  if (!dashState.timers.stats) {
    await Promise.all([loadStats(), loadQueue(), loadActivity()]);
    dashState.timers.stats    = setInterval(loadStats,    STATS_POLL_MS);
    dashState.timers.queue    = setInterval(loadQueue,    QUEUE_POLL_MS);
    dashState.timers.activity = setInterval(loadActivity, ACTIVITY_POLL_MS);
  }
}
