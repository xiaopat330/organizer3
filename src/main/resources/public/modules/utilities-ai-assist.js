// v1 Enrichment hub → AI Assist subtab (Phase 0 prototype slice).
//
// Scope: fetch the assist dashboard ONCE on show and render ONLY the top
// stat-card row — Queue, Processed meter, Outcome mix donut. No polling,
// no sweeper toggle, no apply-agreed, no queue/activity feeds (those are
// Phase 1). All work stops on hide.
//
// Visual spec forked from modules/v2/ai-assist.js (renderStatCards /
// renderThroughputMeter / renderOutcomeDonut), re-skinned to v1 tokens with
// `.aia1-` class prefixes (distinct from the v2 `.aia-` classes that live in
// the same /css/ directory).

const ENDPOINT = '/api/enrichment/assist/dashboard';

let body = null;            // resolved lazily so module import order is irrelevant
let active = false;         // guards a late fetch resolving after hide()

// ── Utilities (mirrored from v2 ai-assist.js) ──────────────────────────────
function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
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

async function fetchJson(url, fallback = null) {
  try {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) return fallback;
    return await r.json();
  } catch (e) {
    console.warn('[v1-ai-assist] fetch failed:', url, e);
    return fallback;
  }
}

// ── Outcome classification (mirrored from v2) ──────────────────────────────
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

const OUTCOME_DEFS = [
  { key: 'conclusive',  label: 'Conclusive',        color: 'var(--ok)'         },
  { key: 'leaning',     label: 'Leaning (1 model)', color: 'var(--accent)'     },
  { key: 'split',       label: 'Split (conflict)',  color: 'var(--warn)'       },
  { key: 'no-decision', label: 'No decision',       color: 'var(--text-faint)' },
];

function bucketCounts(outcomeCounts) {
  const counts = outcomeCounts || {};
  const buckets = { conclusive: 0, leaning: 0, split: 0, 'no-decision': 0 };
  for (const [key, val] of Object.entries(counts)) {
    buckets[classifyOutcome(key)] += N(val);
  }
  return buckets;
}

// ── Outcome donut (CSS conic-gradient ring + inline legend) ─────────────────
function renderOutcomeDonut(stats) {
  const processedTotal = N(stats && stats.processedTotal);
  const buckets        = bucketCounts(stats && stats.outcomeCounts);

  let ring;
  if (processedTotal > 0) {
    let acc = 0;
    const stops = [];
    for (const def of OUTCOME_DEFS) {
      const count = buckets[def.key] || 0;
      if (count <= 0) continue;
      const start = (acc / processedTotal) * 360;
      acc += count;
      const end = (acc / processedTotal) * 360;
      stops.push(`${def.color} ${start.toFixed(2)}deg ${end.toFixed(2)}deg`);
    }
    ring = `conic-gradient(${stops.join(', ')})`;
  } else {
    ring = 'var(--bg-hover)';
  }

  const center = processedTotal === 0
    ? `<div class="aia1-donut-center"><span class="aia1-donut-note">No outcomes yet</span></div>`
    : `<div class="aia1-donut-center"><span class="aia1-donut-total">${fmt(processedTotal)}</span></div>`;

  const legendRows = OUTCOME_DEFS.map(def => {
    const count = buckets[def.key] || 0;
    return `
      <div class="aia1-legend-row">
        <span class="aia1-legend-swatch" style="background:${def.color}"></span>
        <span class="aia1-legend-label">${escapeHtml(def.label)}</span>
        <span class="aia1-legend-count">${fmt(count)}</span>
      </div>`;
  }).join('');

  return `
    <div class="aia1-donut-wrap">
      <div class="aia1-donut-chart" style="background:${ring}">
        ${center}
      </div>
      <div class="aia1-legend">${legendRows}</div>
    </div>`;
}

// ── Throughput stacked meter (Processed tile) ───────────────────────────────
function renderThroughputMeter(stats) {
  const processedTotal = N(stats.processedTotal);
  const autoApplied    = N(stats.autoApplied);
  const awaitingAi     = N(stats.awaitingAi);
  const openAmbiguous  = N(stats.openAmbiguous);
  const soak           = Math.max(0, openAmbiguous - awaitingAi);
  const settled        = Math.max(0, processedTotal - autoApplied - soak);

  if (processedTotal === 0) {
    return `
      <div class="aia1-meter-track"></div>
      <div class="aia1-meter-note">Nothing processed yet</div>`;
  }

  const segs = [
    { label: 'Auto-applied', count: autoApplied, color: 'var(--ok)'        },
    { label: 'In soak',      count: soak,        color: 'var(--warn)'      },
    { label: 'Settled',      count: settled,     color: 'var(--text-dim)'  },
  ];

  const bar = segs.map(s => {
    const w = (s.count / processedTotal) * 100;
    const tip = `${s.label}: ${fmt(s.count)} (${pct(s.count, processedTotal)})`;
    return `<div class="aia1-meter-seg" style="width:${w}%;background:${s.color}" title="${escapeHtml(tip)}"></div>`;
  }).join('');

  const legend = segs.map(s => `
    <div class="aia1-meter-key">
      <span class="aia1-meter-swatch" style="background:${s.color}"></span>
      <span class="aia1-meter-key-label">${escapeHtml(s.label)}</span>
      <span class="aia1-meter-key-count">${fmt(s.count)}</span>
    </div>`).join('');

  return `
    <div class="aia1-meter-track">${bar}</div>
    <div class="aia1-meter-legend">${legend}</div>`;
}

// ── Stat-card row ───────────────────────────────────────────────────────────
function renderStatCards(stats) {
  if (!stats) {
    return `<div class="aia1-loading">Stats unavailable.</div>`;
  }

  const awaitingAi      = N(stats.awaitingAi);
  const inFlight        = N(stats.inFlight);
  const processedTotal  = N(stats.processedTotal);
  const autoApplied     = N(stats.autoApplied);
  const openReviewTotal = N(stats.openReviewTotal);

  const runningLine = inFlight > 0
    ? `<div class="aia1-card-sub">${fmt(inFlight)} running</div>`
    : '';
  const sharePct = openReviewTotal > 0
    ? `<div class="aia1-card-sub">${pct(awaitingAi, openReviewTotal)} of open review</div>`
    : '';

  return `
    <div class="aia1-cards">

      <div class="aia1-card">
        <div class="aia1-card-title">Queue</div>
        <div class="aia1-card-headline">${fmt(awaitingAi)}</div>
        <div class="aia1-card-label">assistable</div>
        <div class="aia1-card-sub">of ${fmt(openReviewTotal)} open review items</div>
        ${sharePct}
        ${runningLine}
      </div>

      <div class="aia1-card">
        <div class="aia1-card-title">Processed</div>
        <div class="aia1-card-headline">${fmt(processedTotal)}</div>
        <div class="aia1-card-label">${fmt(autoApplied)} auto-applied</div>
        ${renderThroughputMeter(stats)}
      </div>

      <div class="aia1-card aia1-card-wide">
        <div class="aia1-card-title">Outcome mix</div>
        ${renderOutcomeDonut(stats)}
      </div>

    </div>`;
}

// ── Lifecycle ───────────────────────────────────────────────────────────────
export async function showAiAssistView() {
  body = body || document.getElementById('ehub-ai-assist-subview');
  if (!body) return;
  active = true;
  body.style.display = '';
  body.innerHTML = `<div class="aia1-loading">Loading AI Assist…</div>`;

  const stats = await fetchJson(ENDPOINT, null);
  if (!active) return;   // hidden while the fetch was in flight
  body.innerHTML = renderStatCards(stats);
}

export function hideAiAssistView() {
  active = false;
  if (body) body.style.display = 'none';
}
