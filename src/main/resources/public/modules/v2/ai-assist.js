/* ─────────────────────────────────────────────────────────────────────
   AI Assist dashboard — single-page, no tabs.
   Layout: top widget row (Queue + Throughput + Outcome mix) → queue preview → recent feed.
   Polling: stats+queue every 5s, recent feed every 2s (with since= HWM).
   API contract:
     GET /api/enrichment/assist/dashboard
     GET /api/enrichment/assist/queue-preview?limit=15
     GET /api/enrichment/assist/recent?limit=50&since=<ISO>
   ───────────────────────────────────────────────────────────────────── */

const QUEUE_LIMIT        = 15;
const STATS_POLL_MS      = 5000;
const QUEUE_POLL_MS      = 2000;
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

/* ── Outcome category defs (shared by donut + legend) ──────────────── */
const OUTCOME_DEFS = [
  { key: 'conclusive',   label: 'Conclusive',         color: 'var(--ok, #22c55e)'        },
  { key: 'leaning',      label: 'Leaning (1 model)',  color: 'var(--accent, #60a5fa)'    },
  { key: 'split',        label: 'Split (conflict)',   color: 'var(--warn, #f59e0b)'      },
  { key: 'no-decision',  label: 'No decision',        color: 'var(--text-faint, #888)'   },
];

/* ── Outcome donut (donut ring + inline legend) ──────────────────────
   Reused for the compact in-row tile. Geometry: viewBox 0 0 42 42,
   radius chosen so circumference ≈ 100; sequential segments, skip-zero,
   -90deg rotation applied via CSS.
   ─────────────────────────────────────────────────────────────────── */
function renderOutcomeDonut(stats, { compact = false } = {}) {
  const processedTotal = N(stats && stats.processedTotal);
  const buckets        = bucketCounts(stats && stats.outcomeCounts);

  const R = 15.91549430918954; // circumference = 2πR = 100
  const CIRC = 100;
  const STROKE = 5;

  let acc = 0;
  const segments = [];
  if (processedTotal > 0) {
    for (const def of OUTCOME_DEFS) {
      const count = buckets[def.key] || 0;
      if (count <= 0) continue;
      const len = (count / processedTotal) * CIRC;
      const offset = (CIRC - acc) % CIRC;
      segments.push(`
        <circle class="aia-donut-seg" cx="21" cy="21" r="${R}" fill="none"
          stroke="${def.color}" stroke-width="${STROKE}"
          stroke-dasharray="${len.toFixed(3)} ${(CIRC - len).toFixed(3)}"
          stroke-dashoffset="${offset.toFixed(3)}"></circle>
      `);
      acc += len;
    }
  }

  const svg = `
    <svg class="aia-donut-svg" viewBox="0 0 42 42" role="img" aria-label="Outcome breakdown donut">
      <circle cx="21" cy="21" r="${R}" fill="none"
        stroke="var(--surface-2, rgba(255,255,255,0.08))" stroke-width="${STROKE}"></circle>
      ${segments.join('')}
    </svg>
  `;

  const center = processedTotal === 0
    ? `<div class="aia-donut-center"><div class="aia-donut-note">No outcomes yet</div></div>`
    : `<div class="aia-donut-center">
         <div class="aia-donut-total">${fmt(processedTotal)}</div>
       </div>`;

  const legendRows = OUTCOME_DEFS.map(def => {
    const count = buckets[def.key] || 0;
    return `
      <div class="aia-legend-row">
        <span class="aia-legend-swatch" style="background:${def.color}"></span>
        <span class="aia-legend-label">${escapeHtml(def.label)}</span>
        <span class="aia-legend-count">${fmt(count)}</span>
      </div>
    `;
  }).join('');

  const wrapClass = compact ? 'aia-donut-wrap aia-donut-wrap-compact' : 'aia-donut-wrap';
  const chartClass = compact ? 'aia-donut-chart aia-donut-chart-compact' : 'aia-donut-chart';

  return `
    <div class="${wrapClass}">
      <div class="${chartClass}">
        ${svg}
        ${center}
      </div>
      <div class="aia-legend aia-legend-compact">
        ${legendRows}
      </div>
    </div>
  `;
}

/* ── Stat cards ─────────────────────────────────────────────────────
   Top widget row: Queue + Throughput + wide Outcome-mix tile.
   ─────────────────────────────────────────────────────────────────── */
/* ── Throughput stacked meter (Processed tile) ──────────────────── */
function renderThroughputMeter(stats) {
  const processedTotal = N(stats.processedTotal);
  const autoApplied    = N(stats.autoApplied);
  const awaitingAi     = N(stats.awaitingAi);
  const openAmbiguous  = N(stats.openAmbiguous);
  const soak           = Math.max(0, openAmbiguous - awaitingAi);
  const settled        = Math.max(0, processedTotal - autoApplied - soak);

  if (processedTotal === 0) {
    return `
      <div class="aia-meter-track"></div>
      <div class="aia-meter-note">Nothing processed yet</div>
    `;
  }

  const segs = [
    { label: 'Auto-applied',  count: autoApplied, color: 'var(--ok, #22c55e)' },
    { label: 'Awaiting soak', count: soak,        color: 'var(--warn, #f59e0b)' },
    { label: 'Settled',       count: settled,     color: 'var(--text-faint, #888)' },
  ].filter(s => s.count > 0);

  const bar = segs.map(s => {
    const w = (s.count / processedTotal) * 100;
    const tip = `${s.label}: ${fmt(s.count)} (${pct(s.count, processedTotal)})`;
    return `<div class="aia-meter-seg" style="width:${w}%;background:${s.color}" title="${escapeHtml(tip)}"></div>`;
  }).join('');

  const legend = segs.map(s => `
    <div class="aia-meter-key">
      <span class="aia-meter-swatch" style="background:${s.color}"></span>
      <span class="aia-meter-key-label">${escapeHtml(s.label)}</span>
      <span class="aia-meter-key-count">${fmt(s.count)}</span>
    </div>
  `).join('');

  return `
    <div class="aia-meter-track">${bar}</div>
    <div class="aia-meter-legend">${legend}</div>
  `;
}

function renderStatCards(stats) {
  if (!stats) return `<div class="shelf-loading">Stats unavailable.</div>`;

  const awaitingAi     = N(stats.awaitingAi);
  const inFlight       = N(stats.inFlight);
  const processedTotal = N(stats.processedTotal);
  const autoApplied    = N(stats.autoApplied);
  const openAmbiguous  = N(stats.openAmbiguous);
  const openReviewTotal = N(stats.openReviewTotal);

  const runningLine = inFlight > 0
    ? `<div class="trans-card-sub">${fmt(inFlight)} running</div>`
    : '';

  return `
    <div class="aia-cards">

      <div class="trans-card">
        <div class="trans-card-title">Queue</div>
        <div class="trans-card-headline">${fmt(awaitingAi)}</div>
        <div class="aia-card-label">assistable</div>
        <div class="trans-card-sub">of ${fmt(openReviewTotal)} open review items</div>
        ${runningLine}
      </div>

      <div class="trans-card">
        <div class="trans-card-title">Processed</div>
        <div class="trans-card-headline">${fmt(processedTotal)}</div>
        ${renderThroughputMeter(stats)}
      </div>

      <div class="trans-card aia-card-wide">
        <div class="trans-card-title">Outcome mix</div>
        ${renderOutcomeDonut(stats, { compact: true })}
        <div class="aia-outcome-caption">Conclusive = both models agreed · applied after a ~10-min soak. Applied: ${fmt(N(stats.autoApplied))}.</div>
      </div>

    </div>
  `;
}

/* ── Queue preview rows ───────────────────────────────────────────
   Per-row status is only meaningful while the sweeper is active:
   - HEAD row (index 0) with inFlight > 0 → "in-flight" (amber, pulsing)
   - any other row while sweeper active → "queued" (muted)
   - sweeper inactive → no status badge at all
   ─────────────────────────────────────────────────────────────────── */
function renderQueueRow(r, index) {
  const codeLink = r.code
    ? `<a href="/v2-title-detail.html?code=${encodeURIComponent(r.code)}" style="color:var(--accent-fg);text-decoration:none">${escapeHtml(r.code)}</a>`
    : '<span style="color:var(--text-faint)">—</span>';

  const sweeperActive = !!(dashState.sweeper && dashState.sweeper.active);
  let status = '';
  if (sweeperActive) {
    const inFlight = N(dashState.stats && dashState.stats.inFlight);
    if (index === 0 && inFlight > 0) {
      status = `<span class="aia-q-status inflight">in-flight</span>`;
    } else {
      status = `<span class="aia-q-status queued">queued</span>`;
    }
  }

  return `
    <li class="aia-q-row">
      <span class="aia-q-code">${codeLink}</span>
      <span class="aia-q-statuscell">${status}</span>
      <span class="aia-q-time">${escapeHtml(timeAgo(r.createdAt))}</span>
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
  // Cell 4: apply-state badge (three-state).
  //  - autoApplied             → green "applied" (auto-apply fired post-soak)
  //  - resolved (other means)  → neutral "resolved" (manual pick / cascade resolve — DONE)
  //  - conclusive, not applied → amber "pending apply" deep-link (genuinely actionable)
  //  - otherwise               → empty (keeps column alignment)
  let badgeContent = '';
  if (r.autoApplied) {
    badgeContent = `<span class="aia-applied-badge">applied</span>`;
  } else if (r.resolved) {
    badgeContent = `<span class="aia-resolved-badge">resolved</span>`;
  } else if (classifyOutcome(r.outcome) === 'conclusive') {
    badgeContent = `<a class="aia-pending-badge" href="/v2-enrichment.html?focus=${encodeURIComponent(r.reviewQueueId)}" title="Open in Enrichment Review">pending apply</a>`;
  }
  const isNew = dashState.lastNewIds
    && r.reviewQueueId != null
    && dashState.lastNewIds.has(r.reviewQueueId);
  const newCls = isNew ? ' aia-recent-new' : '';
  return `
    <li class="aia-recent-row${newCls}">
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
      /* ── Top widget row grid (Queue + Throughput + wide Outcome) ──── */
      .aia-cards {
        display: grid;
        gap: 14px;
        grid-template-columns: repeat(4, 1fr);
        margin-bottom: 8px;
      }
      .aia-card-wide { grid-column: span 2; }
      .aia-outcome-caption {
        font-size: 0.76em;
        color: var(--text-faint);
        margin-top: 6px;
        line-height: 1.3;
      }
      .aia-card-label {
        font-size: 0.78em;
        color: var(--text-faint);
        margin-top: -4px;
      }
      /* Narrow widths: collapse to a single column so the wide tile wraps. */
      @media (max-width: 760px) {
        .aia-cards { grid-template-columns: 1fr; }
        .aia-card-wide { grid-column: auto; }
      }

      /* ── Throughput stacked meter (Processed tile) ──────────────── */
      .aia-meter-track {
        display: flex;
        width: 100%;
        height: 15px;
        margin-top: 10px;
        border-radius: 7px;
        overflow: hidden;
        background: var(--surface-2, rgba(255,255,255,0.08));
      }
      .aia-meter-seg {
        height: 100%;
        transition: width 0.3s ease;
      }
      .aia-meter-note {
        font-size: 0.78em;
        color: var(--text-faint);
        margin-top: 6px;
      }
      .aia-meter-legend {
        display: flex;
        flex-direction: column;
        gap: 3px;
        margin-top: 8px;
      }
      .aia-meter-key {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 0.78em;
      }
      .aia-meter-swatch {
        width: 9px;
        height: 9px;
        border-radius: 2px;
        flex: none;
      }
      .aia-meter-key-label { color: var(--text-faint); }
      .aia-meter-key-count { margin-left: auto; font-variant-numeric: tabular-nums; }

      /* ── Outcome breakdown donut + legend ───────────────────────── */
      .aia-donut-wrap {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: center;
        gap: 32px;
        padding: 16px;
      }
      .aia-donut-wrap-compact { gap: 18px; padding: 4px 0 0; justify-content: flex-start; }
      .aia-donut-chart { position: relative; width: 160px; height: 160px; flex: 0 0 auto; }
      .aia-donut-svg { width: 160px; height: 160px; transform: rotate(-90deg); }
      .aia-donut-chart-compact { width: 90px; height: 90px; }
      .aia-donut-chart-compact .aia-donut-svg { width: 90px; height: 90px; }
      .aia-donut-seg { transition: stroke-dasharray 0.3s, stroke-dashoffset 0.3s; }
      .aia-donut-center {
        position: absolute; inset: 0;
        display: flex; flex-direction: column;
        align-items: center; justify-content: center;
        pointer-events: none; text-align: center;
      }
      .aia-donut-total    { font-size: 1.9em; font-weight: 700; line-height: 1; color: var(--text); }
      .aia-donut-chart-compact .aia-donut-total { font-size: 1.2em; }
      .aia-donut-totlabel { font-size: 0.72em; color: var(--text-faint); margin-top: 3px; }
      .aia-donut-note     { font-size: 0.82em; color: var(--text-faint); }
      .aia-donut-chart-compact .aia-donut-note { font-size: 0.66em; }

      .aia-legend { display: flex; flex-direction: column; gap: 8px; min-width: 220px; }
      .aia-legend-compact { gap: 4px; min-width: 0; }
      .aia-legend-row {
        display: grid;
        grid-template-columns: 14px 1fr auto;
        align-items: center;
        column-gap: 10px;
      }
      .aia-legend-swatch { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
      .aia-legend-label  { font-size: 0.85em; color: var(--text); white-space: nowrap; }
      .aia-legend-compact .aia-legend-label { font-size: 0.78em; }
      .aia-legend-count  { font-size: 0.85em; color: var(--text); font-variant-numeric: tabular-nums; text-align: right; }
      .aia-legend-compact .aia-legend-count { font-size: 0.78em; }

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
      .aia-pending-badge {
        background: transparent;
        color: var(--warn, #f59e0b);
        border: 1px solid var(--warn, #f59e0b);
        font-size: 0.72em;
        padding: 1px 5px;
        border-radius: 3px;
        white-space: nowrap;
      }
      a.aia-pending-badge {
        text-decoration: none;
        cursor: pointer;
        display: inline-block;
      }
      .aia-resolved-badge {
        background: transparent;
        color: var(--text-faint, #888);
        border: 1px solid var(--border, rgba(128,128,128,0.3));
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
      /* ── Queue rows (code | status | time) ───────────────────────── */
      .aia-q-row {
        display: grid;
        grid-template-columns: 1fr auto auto;
        align-items: center;
        gap: 10px;
        padding: 4px 12px;
        border-bottom: 1px solid var(--border, rgba(255,255,255,0.06));
        min-width: 0;
      }
      .aia-q-row:last-child { border-bottom: none; }
      .aia-q-code {
        font-family: var(--font-mono, monospace);
        font-size: 0.85em;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        min-width: 0;
      }
      .aia-q-statuscell { white-space: nowrap; }
      .aia-q-time {
        font-family: var(--font-mono, monospace);
        font-size: 0.80em;
        color: var(--text-faint);
        text-align: right;
        white-space: nowrap;
      }
      .aia-q-status {
        font-size: 0.7em;
        text-transform: uppercase;
        letter-spacing: 0.03em;
        padding: 1px 6px;
        border-radius: 8px;
        border: 1px solid transparent;
        white-space: nowrap;
      }
      .aia-q-status.queued {
        color: var(--text-faint, #888);
        border-color: var(--text-faint, #888);
      }
      .aia-q-status.inflight {
        color: var(--warn, #f59e0b);
        border-color: var(--warn, #f59e0b);
        animation: aia-pulse 1.8s ease-out infinite;
      }

      /* ── Reusable live dot (queue + activity headers when active) ── */
      .aia-live-dot {
        display: inline-block;
        width: 8px;
        height: 8px;
        border-radius: 50%;
        margin-right: 6px;
        vertical-align: middle;
        background: var(--ok, #22c55e);
        animation: aia-pulse 1.8s ease-out infinite;
      }

      /* ── New-row flash in Recently processed ─────────────────────── */
      .aia-recent-new { animation: aia-flash 1.2s ease-out 1; }
      @keyframes aia-flash {
        0%   { background: rgba(96,165,250,0.22); }
        100% { background: transparent; }
      }

      /* ── Sweeper on/off bar ──────────────────────────────────────── */
      .aia-sweeper-bar {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 10px 14px;
        margin-bottom: 16px;
        border-radius: 8px;
        background: var(--surface-2, rgba(255,255,255,0.05));
      }
      .aia-sweeper-dot {
        width: 10px;
        height: 10px;
        border-radius: 50%;
        flex: none;
        background: var(--text-faint, #888);
      }
      .aia-sweeper-bar[data-active="true"] .aia-sweeper-dot {
        background: var(--ok, #22c55e);
        animation: aia-pulse 1.8s ease-out infinite;
      }
      @keyframes aia-pulse {
        0%   { box-shadow: 0 0 0 0 rgba(34,197,94,0.55); }
        70%  { box-shadow: 0 0 0 7px rgba(34,197,94,0); }
        100% { box-shadow: 0 0 0 0 rgba(34,197,94,0); }
      }
      .aia-sweeper-label { font-size: 0.9em; color: var(--text); }
      .aia-sweeper-msg {
        font-size: 0.82em;
        color: var(--warn, #f59e0b);
        margin-left: 8px;
      }
      .aia-sweeper-btn { margin-left: auto; }
    </style>

    <div id="aia-sweeper-bar"></div>

    <div id="dash-cards"><div class="shelf-loading">Loading…</div></div>

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

/* ── Sweeper bar ─────────────────────────────────────────────────── */
function renderSweeperBar() {
  if (!dashState.panel) return;
  const bar = dashState.panel.querySelector('#aia-sweeper-bar');
  if (!bar) return;
  const active = !!(dashState.sweeper && dashState.sweeper.active);
  bar.className = 'aia-sweeper-bar';
  bar.dataset.active = String(active);
  const label = active ? 'Active — draining queue' : 'Inactive';
  const btnLabel = active ? 'Turn off' : 'Turn on';
  const msg = dashState.sweeperMsg
    ? `<span class="aia-sweeper-msg">${escapeHtml(dashState.sweeperMsg)}</span>`
    : '';
  bar.innerHTML = `
    <span class="aia-sweeper-dot"></span>
    <span class="aia-sweeper-label">${escapeHtml(label)}</span>
    ${msg}
    <button class="btn sm aia-sweeper-btn" id="aia-sweeper-btn"${dashState.sweeperBusy ? ' disabled' : ''}>${escapeHtml(btnLabel)}</button>
  `;
  const btn = bar.querySelector('#aia-sweeper-btn');
  if (btn) btn.addEventListener('click', toggleSweeper);
}

async function loadSweeper() {
  const s = await fetchJson('/api/enrichment/assist/sweeper', null);
  if (s) dashState.sweeper = { active: !!s.active, runId: s.runId ?? null };
  renderSweeperBar();
}

async function toggleSweeper() {
  if (dashState.sweeperBusy) return;
  const wasActive = !!(dashState.sweeper && dashState.sweeper.active);
  dashState.sweeperBusy = true;
  dashState.sweeperMsg = null;
  renderSweeperBar();
  try {
    const url = wasActive
      ? '/api/enrichment/assist/sweeper/stop'
      : '/api/enrichment/assist/sweeper/start';
    const res = await fetch(url, { method: 'POST', cache: 'no-cache' });
    if (res.status === 409) {
      const data = await res.json().catch(() => ({}));
      const who = data.runningTaskId ? ` (${data.runningTaskId})` : '';
      dashState.sweeperMsg = `Can't start — another utility task is running${who}`;
      setTimeout(() => { dashState.sweeperMsg = null; renderSweeperBar(); }, 5000);
    }
  } catch (e) {
    console.warn('[ai-assist] sweeper toggle failed:', e);
  } finally {
    dashState.sweeperBusy = false;
    await loadSweeper();
  }
}

/* ── Render helpers ─────────────────────────────────────────────── */
function renderDashCards() {
  if (!dashState.panel) return;
  const cards = dashState.panel.querySelector('#dash-cards');
  if (!cards) return;
  cards.innerHTML = renderStatCards(dashState.stats);
}

function renderDashQueue() {
  if (!dashState.panel) return;
  const queueEl = dashState.panel.querySelector('#dash-queue');
  const meta    = dashState.panel.querySelector('#dash-queue-meta');
  if (!queueEl) return;
  const count = (dashState.queue || []).length;
  if (count === 0) {
    queueEl.innerHTML = `<li class="trans-empty dis-empty">Queue is empty.</li>`;
  } else {
    queueEl.innerHTML = dashState.queue.map((r, i) => renderQueueRow(r, i)).join('');
  }
  if (meta) {
    const active = !!(dashState.sweeper && dashState.sweeper.active);
    if (active) {
      meta.innerHTML = `<span class="aia-live-dot"></span>live · ${count} shown`;
    } else {
      meta.innerHTML = `<span style="color:var(--text-faint)">${count} pending · sweeper off</span>`;
    }
  }
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
  if (meta) {
    const active = !!(dashState.sweeper && dashState.sweeper.active);
    const dot = (active && !dashState.paused) ? `<span class="aia-live-dot"></span>` : '';
    const tail = dashState.paused ? ' · paused' : (active ? ' · live' : ' · polling every 2s');
    meta.innerHTML = `${dot}${dashState.activity.length} events${tail}`;
  }
}

/* ── State ───────────────────────────────────────────────────────── */
const dashState = {
  panel: null,
  stats: null,
  queue: [],
  activity: [],         // newest first
  activitySince: null,  // high-water-mark: tracks newest `at` value seen
  lastNewIds: new Set(), // reviewQueueIds prepended this poll → flash once
  paused: false,
  sweeper: { active: false, runId: null },
  sweeperBusy: false,   // true while a start/stop request is in flight
  sweeperMsg: null,     // transient inline message (e.g. 409 conflict)
  timers: { stats: null, queue: null, activity: null },
};

/* ── Poll functions ─────────────────────────────────────────────── */
async function loadStats() {
  const stats = await fetchJson('/api/enrichment/assist/dashboard', null);
  dashState.stats = stats;
  renderDashCards();
  // Piggyback the sweeper status poll on the 5s stats timer.
  await loadSweeper();
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
    // No new rows this cycle → nothing flashes.
    dashState.lastNewIds = new Set();
    renderDashActivity();
    return;
  }
  // Sort newest-first by `at`
  events.sort((a, b) => (b.at || '').localeCompare(a.at || ''));
  // Advance high-water mark to the newest `at` seen
  dashState.activitySince = events[0].at;
  // Record the freshly-prepended ids so only these rows flash this render.
  dashState.lastNewIds = new Set(
    events.map(e => e.reviewQueueId).filter(id => id != null)
  );
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
  renderSweeperBar();
  renderDashCards();
  renderDashQueue();
  renderDashActivity();

  // Start polling timers only if not already running
  if (!dashState.timers.stats) {
    await Promise.all([loadStats(), loadQueue(), loadActivity(), loadSweeper()]);
    dashState.timers.stats    = setInterval(loadStats,    STATS_POLL_MS);
    dashState.timers.queue    = setInterval(loadQueue,    QUEUE_POLL_MS);
    dashState.timers.activity = setInterval(loadActivity, ACTIVITY_POLL_MS);
  }
}
