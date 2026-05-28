// v1 Enrichment hub → AI Assist subtab (Phase 1, full dashboard).
//
// Functional parity with the v2 AI Assist screen (modules/v2/ai-assist.js),
// re-skinned to v1 design language with `.aia1-` class prefixes (distinct from
// the v2 `.aia-` classes that live in the same /css/ directory). All styling
// lives in css/enrichment-hub.css — NO inline <style> blocks.
//
// Layout (stable shell, rendered once on show; loaders write only their slot):
//   sweeper control bar → stat cards (Queue / Processed meter / Outcome donut)
//   → Queue preview → Recently-processed activity feed.
//
// Polling cadence (mirrors v2):
//   stats + sweeper + apply-status  every 5s
//   queue preview                   every 2s
//   recently-processed (since= HWM) every 2s
//   batch-progress                  every 1s
//   apply-agreed progress           every 1.5s (only while a run is active)
//
// Teardown: hideAiAssistView clears EVERY timer and flips `active=false`; every
// async loader re-checks `active` after its await before touching the DOM, so a
// late fetch resolving after hide can't repaint a hidden view. showAiAssistView
// tears down first too, so re-showing the tab is idempotent.

const QUEUE_LIMIT       = 15;
const STATS_POLL_MS     = 5000;
const QUEUE_POLL_MS     = 2000;
const ACTIVITY_POLL_MS  = 2000;
const BATCH_POLL_MS     = 1000;
const APPLY_POLL_MS     = 1500;
const ACTIVITY_MAX_ROWS = 200;

// ── State ───────────────────────────────────────────────────────────────────
const S = {
  body: null,           // resolved lazily; the #ehub-ai-assist-subview container
  active: false,        // guards a late fetch resolving after hide()
  stats: null,
  queue: [],
  activity: [],         // newest first
  activitySince: null,  // high-water-mark: newest `at` value seen
  lastNewIds: new Set(),// reviewQueueIds prepended this poll → flash once
  paused: false,
  sweeper: { active: false, runId: null },
  sweeperBusy: false,   // true while a start/stop request is in flight
  sweeperMsg: null,     // transient inline message (e.g. 409 conflict)
  applyAgreed: { running: false, total: 0, applied: 0, failed: 0 },
  applyBusy: false,     // true while an apply-agreed POST is in flight
  applyPollTimer: null, // fast progress poll while a run is active
  batchProgress: { active: false, chunkRowIds: [], pass: 0, currentRowId: null, currentCode: null, currentModel: null },
  passModels: {},       // { 1: '<pass-1 model>', 2: '<pass-2 model>' } — cached per pass
  timers: { stats: null, queue: null, activity: null, batch: null },
};

// ── Utilities ────────────────────────────────────────────────────────────────
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

function truncate(s, n = 70) {
  if (!s) return '';
  return s.length <= n ? s : s.slice(0, n - 1) + '…';
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

function el(id) { return S.body ? S.body.querySelector('#' + id) : null; }

// ── Code-link → open the title in-app (v1 has no /v2-title-detail.html) ───────
// Pattern mirrors modules/search.js: fetch the title by code, fall back to a
// bare {code} on 404/error, then dynamic-import openTitleDetail.
async function openByCode(code) {
  if (!code) return;
  let titleData = { code };
  try {
    const res = await fetch(`/api/titles/by-code/${encodeURIComponent(code)}`);
    if (res.ok) titleData = await res.json();
  } catch { /* use bare code fallback */ }
  const { openTitleDetail } = await import('./title-detail.js');
  await openTitleDetail(titleData);
}

// Delegated click handler: any [data-aia1-code] inside the subview opens the title.
function onBodyClick(ev) {
  // C-glue: pending-apply badge → switch hub to Workflow + focus this row.
  // Dynamic import avoids a static circular import (the hub statically imports
  // this module). focusWorkflow(queueId) is async; fire-and-forget is fine here.
  const focus = ev.target.closest('[data-aia1-focus-id]');
  if (focus && S.body && S.body.contains(focus)) {
    ev.preventDefault();
    ev.stopPropagation();
    const raw = focus.dataset.aia1FocusId;
    const queueId = Number(raw);
    import('./utilities-enrichment-hub.js')
      .then(m => m.focusWorkflow(Number.isNaN(queueId) ? raw : queueId))
      .catch(e => console.warn('[v1-ai-assist] focusWorkflow failed:', e));
    return;
  }
  const link = ev.target.closest('[data-aia1-code]');
  if (link && S.body && S.body.contains(link)) {
    ev.preventDefault();
    openByCode(link.dataset.aia1Code);
  }
}

// Delegated keydown handler: Enter/Space on the role=button pending-apply badge
// activates the same focusWorkflow path as a click (a11y parity with role=button
// /tabindex=0). preventDefault on Space avoids the page scrolling.
function onBodyKeydown(ev) {
  if (ev.key !== 'Enter' && ev.key !== ' ' && ev.key !== 'Spacebar') return;
  const focus = ev.target.closest('[data-aia1-focus-id]');
  if (!focus || !S.body || !S.body.contains(focus)) return;
  ev.preventDefault();
  ev.stopPropagation();
  const raw = focus.dataset.aia1FocusId;
  const queueId = Number(raw);
  import('./utilities-enrichment-hub.js')
    .then(m => m.focusWorkflow(Number.isNaN(queueId) ? raw : queueId))
    .catch(e => console.warn('[v1-ai-assist] focusWorkflow failed:', e));
}

function codeLinkHtml(code) {
  if (!code) return '<span class="aia1-code-empty">—</span>';
  return `<a href="#" class="aia1-code-link" data-aia1-code="${escapeHtml(code)}">${escapeHtml(code)}</a>`;
}

// ── Outcome classification ───────────────────────────────────────────────────
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

// Outcome chip (recent-feed rows): 4-state symbol + label.
function outcomeChip(outcome) {
  const cat = classifyOutcome(outcome);
  const MAP = {
    conclusive:    { symbol: '✓', label: 'conclusive' },
    leaning:       { symbol: '~', label: 'leaning' },
    split:         { symbol: '⚠', label: 'split' },
    'no-decision': { symbol: '—', label: 'no decision' },
  };
  const m = MAP[cat] || MAP['no-decision'];
  return `<span class="aia1-chip aia1-chip-${cat}" title="${escapeHtml(outcome || cat)}">${m.symbol} ${escapeHtml(m.label)}</span>`;
}

// ── Outcome donut (CSS conic-gradient ring + inline legend) ──────────────────
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

// ── Throughput stacked meter (Processed tile) ────────────────────────────────
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
  ].filter(s => s.count > 0);

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

// ── Stat-card row ─────────────────────────────────────────────────────────────
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
    ? `<div class="trans-card-sub">${fmt(inFlight)} running</div>`
    : '';
  const sharePct = openReviewTotal > 0
    ? `<div class="trans-card-sub">${pct(awaitingAi, openReviewTotal)} of open review</div>`
    : '';

  return `
    <div class="trans-stats-grid">

      <div class="trans-card">
        <div class="trans-card-title">Queue</div>
        <div class="trans-card-headline">${fmt(awaitingAi)}<span class="trans-card-unit">assistable</span></div>
        <div class="trans-card-sub">of ${fmt(openReviewTotal)} open review items</div>
        ${sharePct}
        ${runningLine}
      </div>

      <div class="trans-card">
        <div class="trans-card-title">Processed</div>
        <div class="trans-card-headline">${fmt(processedTotal)}<span class="trans-card-unit">${fmt(autoApplied)} auto-applied</span></div>
        ${renderThroughputMeter(stats)}
      </div>

      <div class="trans-card aia1-outcome-card">
        <div class="trans-card-title">Outcome mix</div>
        ${renderOutcomeDonut(stats)}
        <div class="aia1-outcome-caption">Conclusive = both models agreed · applied after a ~10-min soak. Applied: ${fmt(autoApplied)}.</div>
      </div>

    </div>`;
}

// ── Queue preview rows (pass-pill states mirror v2's batched 2-pass sweeper) ──
//   - row currently being processed → ONE pass pill ("<model> · pass N"),
//     colored by pass (1=accent, 2=violet), pulsing.
//   - row that finished pass 1 but isn't current → dim "✓ <pass-1 model>" pill.
//   - row that finished BOTH passes (pass 2, before cursor), awaiting persist →
//     green "✓ done" pill.
//   - other rows in the active batch → muted "in batch" chip.
//   - sweeper active but row not in batch → muted "queued".
//   - sweeper inactive → no status chip.
function renderQueueRow(r, chunkSet) {
  const bp = S.batchProgress || {};
  const sweeperActive = !!(S.sweeper && S.sweeper.active);
  const inBatch = !!(bp.active && chunkSet && chunkSet.has(r.reviewQueueId));
  const isCurrent = bp.active && r.reviewQueueId != null && r.reviewQueueId === bp.currentRowId;

  let status = '';
  if (isCurrent && bp.pass) {
    const model = bp.currentModel ? escapeHtml(bp.currentModel) : `pass ${bp.pass}`;
    status = `<span class="aia1-q-pass aia1-q-pass-${bp.pass}">${model} · pass ${bp.pass}</span>`;
  } else if (inBatch) {
    const ids = Array.isArray(bp.chunkRowIds) ? bp.chunkRowIds : [];
    const idx = ids.indexOf(r.reviewQueueId);
    const curIdx = ids.indexOf(bp.currentRowId);
    const pass1Model = S.passModels && S.passModels[1]
      ? escapeHtml(S.passModels[1]) : 'pass 1';
    const bothDone = bp.pass === 2 && curIdx !== -1 && idx < curIdx;
    const pass1Done = (bp.pass === 1 && curIdx !== -1 && idx < curIdx)
      || (bp.pass === 2 && (curIdx === -1 || idx > curIdx));
    if (bothDone) {
      status = `<span class="aia1-q-both-done">✓ done</span>`;
    } else if (pass1Done) {
      status = `<span class="aia1-q-pass-done">✓ ${pass1Model}</span>`;
    } else {
      status = `<span class="aia1-q-status aia1-q-inbatch">in batch</span>`;
    }
  } else if (sweeperActive) {
    status = `<span class="aia1-q-status aia1-q-queued">queued</span>`;
  }

  const liCls = inBatch ? 'aia1-q-row aia1-q-active-batch' : 'aia1-q-row';
  return `
    <li class="${liCls}">
      <span class="aia1-q-code">${codeLinkHtml(r.code)}</span>
      <span class="aia1-q-statuscell">${status}</span>
      <span class="aia1-q-time">${escapeHtml(timeAgo(r.createdAt))}</span>
    </li>`;
}

// ── Recently-processed feed rows (4-state outcome chip + apply-state badge) ───
function renderRecentRow(r) {
  const reasonContent = r.reason ? escapeHtml(truncate(r.reason, 70)) : '';
  const reasonTitle = r.reason ? ` title="${escapeHtml(r.reason)}"` : '';

  // Apply-state badge (three-state):
  //  - autoApplied             → "applied" (auto-apply fired post-soak)
  //  - resolved (other means)  → "resolved" (manual pick / cascade resolve)
  //  - conclusive, not applied → "pending apply" (genuinely actionable)
  //  - otherwise               → empty (keeps column alignment)
  let badgeContent = '';
  if (r.autoApplied) {
    badgeContent = `<span class="aia1-applied-badge">applied</span>`;
  } else if (r.resolved) {
    badgeContent = `<span class="aia1-resolved-badge">resolved</span>`;
  } else if (classifyOutcome(r.outcome) === 'conclusive') {
    // C-glue: clickable → switch the hub to the Workflow subtab and focus/flash
    // this row. The handler lives in onBodyClick (delegated, because the feed
    // re-renders its innerHTML every poll). data-aia1-focus-id carries the queue
    // id; the hub's focusWorkflow(queueId) flashes the matching tr[data-id].
    const fid = r.reviewQueueId != null ? ` data-aia1-focus-id="${escapeHtml(String(r.reviewQueueId))}"` : '';
    badgeContent = `<span class="aia1-pending-badge aia1-pending-badge-link"${fid} role="button" tabindex="0" title="Conclusive, awaiting apply — open in Workflow">pending apply</span>`;
  }

  const isNew = S.lastNewIds && r.reviewQueueId != null && S.lastNewIds.has(r.reviewQueueId);
  const newCls = isNew ? ' aia1-recent-new' : '';
  return `
    <li class="aia1-recent-row${newCls}">
      <span class="aia1-recent-code">${codeLinkHtml(r.code)}</span>
      <span class="aia1-recent-status">${outcomeChip(r.outcome)}</span>
      <span class="aia1-recent-reason"${reasonTitle}>${reasonContent}</span>
      <span class="aia1-recent-badge">${badgeContent}</span>
      <span class="aia1-recent-time">${escapeHtml(timeAgo(r.at))}</span>
    </li>`;
}

// ── Stable shell (rendered once on show) ─────────────────────────────────────
function renderShell() {
  S.body.innerHTML = `
    <div id="aia1-sweeper-bar"></div>

    <div id="aia1-cards"><div class="aia1-loading">Loading…</div></div>

    <section class="aia1-shelf">
      <div class="aia1-shelf-head">
        <span class="aia1-shelf-title">Queue (awaiting AI)</span>
        <span class="aia1-shelf-meta" id="aia1-queue-meta"></span>
      </div>
      <ul class="aia1-queue" id="aia1-queue"><li class="aia1-empty">Loading…</li></ul>
    </section>

    <section class="aia1-shelf">
      <div class="aia1-shelf-head">
        <span class="aia1-shelf-title">Recently processed</span>
        <span class="aia1-shelf-meta" id="aia1-activity-meta">polling every 2s</span>
        <button class="aia1-btn" id="aia1-pause">Pause</button>
        <button class="aia1-btn" id="aia1-clear">Clear</button>
      </div>
      <ul class="aia1-activity" id="aia1-activity"><li class="aia1-empty">Connecting…</li></ul>
    </section>
  `;

  const pauseBtn = el('aia1-pause');
  if (pauseBtn) {
    pauseBtn.textContent = S.paused ? 'Resume' : 'Pause';
    pauseBtn.addEventListener('click', () => {
      S.paused = !S.paused;
      pauseBtn.textContent = S.paused ? 'Resume' : 'Pause';
      renderActivity();
    });
  }
  const clearBtn = el('aia1-clear');
  if (clearBtn) {
    clearBtn.addEventListener('click', () => {
      S.activity = [];
      renderActivity();
    });
  }
}

// ── Scoped renderers (each writes only its own slot) ─────────────────────────
function renderSweeperBar() {
  const bar = el('aia1-sweeper-bar');
  if (!bar) return;
  const active = !!(S.sweeper && S.sweeper.active);
  bar.className = 'aia1-sweeper-bar';
  bar.dataset.active = String(active);
  const label = active ? 'Active — draining queue' : 'Inactive';
  const btnLabel = active ? 'Turn off' : 'Turn on';
  const msg = S.sweeperMsg
    ? `<span class="aia1-sweeper-msg">${escapeHtml(S.sweeperMsg)}</span>`
    : '';

  // Apply-all-agreed button (sits to the right of the sweeper toggle).
  const aa = S.applyAgreed || { running: false, total: 0, applied: 0, failed: 0 };
  const agreedN = N(S.stats && S.stats.agreedPending);
  let applyLabel, applyDisabled;
  if (aa.running) {
    applyLabel = `Applying… ${N(aa.applied) + N(aa.failed)}/${N(aa.total)}`;
    applyDisabled = true;
  } else {
    applyLabel = `Apply all agreed (${agreedN})`;
    applyDisabled = agreedN === 0 || !!S.applyBusy;
  }

  bar.innerHTML = `
    <span class="aia1-sweeper-dot"></span>
    <span class="aia1-sweeper-label">${escapeHtml(label)}</span>
    ${msg}
    <button class="aia1-btn aia1-sweeper-btn" id="aia1-sweeper-btn"${S.sweeperBusy ? ' disabled' : ''}>${escapeHtml(btnLabel)}</button>
    <button class="aia1-btn aia1-btn-primary" id="aia1-apply-agreed-btn"${applyDisabled ? ' disabled' : ''}>${escapeHtml(applyLabel)}</button>
  `;
  const btn = bar.querySelector('#aia1-sweeper-btn');
  if (btn) btn.addEventListener('click', toggleSweeper);
  const applyBtn = bar.querySelector('#aia1-apply-agreed-btn');
  if (applyBtn) applyBtn.addEventListener('click', applyAllAgreed);
}

function renderCards() {
  const cards = el('aia1-cards');
  if (cards) cards.innerHTML = renderStatCards(S.stats);
}

function renderQueue() {
  const queueEl = el('aia1-queue');
  const meta    = el('aia1-queue-meta');
  if (!queueEl) return;
  const count = (S.queue || []).length;
  if (count === 0) {
    queueEl.innerHTML = `<li class="aia1-empty">Queue is empty.</li>`;
  } else {
    const bp = S.batchProgress || {};
    const chunkSet = new Set(bp.active && Array.isArray(bp.chunkRowIds) ? bp.chunkRowIds : []);
    queueEl.innerHTML = S.queue.map(r => renderQueueRow(r, chunkSet)).join('');
  }
  if (meta) {
    const active = !!(S.sweeper && S.sweeper.active);
    if (active) {
      meta.innerHTML = `<span class="aia1-live-dot"></span>live · ${count} shown`;
    } else {
      meta.innerHTML = `<span class="aia1-meta-off">${count} pending · sweeper off</span>`;
    }
  }
}

function renderActivity() {
  const list = el('aia1-activity');
  const meta = el('aia1-activity-meta');
  if (!list) return;
  if (S.activity.length === 0) {
    list.innerHTML = `<li class="aia1-empty">Waiting for first event…</li>`;
  } else {
    list.innerHTML = S.activity.map(renderRecentRow).join('');
  }
  if (meta) {
    const active = !!(S.sweeper && S.sweeper.active);
    const dot = (active && !S.paused) ? `<span class="aia1-live-dot"></span>` : '';
    const tail = S.paused ? ' · paused' : (active ? ' · live' : ' · polling every 2s');
    meta.innerHTML = `${dot}${S.activity.length} events${tail}`;
  }
}

// ── Sweeper control ───────────────────────────────────────────────────────────
async function loadSweeper() {
  const s = await fetchJson('/api/enrichment/assist/sweeper', null);
  if (!S.active) return;
  if (s) S.sweeper = { active: !!s.active, runId: s.runId ?? null };
  renderSweeperBar();
}

async function toggleSweeper() {
  if (S.sweeperBusy) return;
  const wasActive = !!(S.sweeper && S.sweeper.active);
  S.sweeperBusy = true;
  S.sweeperMsg = null;
  renderSweeperBar();
  try {
    const url = wasActive
      ? '/api/enrichment/assist/sweeper/stop'
      : '/api/enrichment/assist/sweeper/start';
    const res = await fetch(url, { method: 'POST', cache: 'no-cache' });
    if (res.status === 409) {
      const data = await res.json().catch(() => ({}));
      const who = data.runningTaskId ? ` (${data.runningTaskId})` : '';
      S.sweeperMsg = `Can't start — another utility task is running${who}`;
      setTimeout(() => {
        if (!S.active) return;
        S.sweeperMsg = null;
        renderSweeperBar();
      }, 5000);
    }
  } catch (e) {
    console.warn('[v1-ai-assist] sweeper toggle failed:', e);
  } finally {
    S.sweeperBusy = false;
    if (S.active) await loadSweeper();
  }
}

// ── Apply-all-agreed ──────────────────────────────────────────────────────────
async function loadApplyStatus() {
  const s = await fetchJson('/api/enrichment/assist/apply-agreed/status', null);
  if (!S.active) return;
  if (s) {
    S.applyAgreed = {
      running: !!s.running,
      total:   N(s.total),
      applied: N(s.applied),
      failed:  N(s.failed),
    };
  }
  renderSweeperBar();
}

async function applyAllAgreed() {
  if (S.applyBusy || (S.applyAgreed && S.applyAgreed.running)) return;
  const n = N(S.stats && S.stats.agreedPending);
  if (n === 0) return;

  const ok = window.confirm(
    `Resolve ${n} title(s) with the AI-picked slug?\n\n` +
    `This applies every pick both models agreed on. It cannot be auto-undone.`
  );
  if (!ok) return;

  S.applyBusy = true;
  renderSweeperBar();
  try {
    const res = await fetch('/api/enrichment/assist/apply-agreed', { method: 'POST', cache: 'no-cache' });
    if (res.status === 200) {
      // {total:0} — nothing to do.
      S.applyBusy = false;
      if (S.active) await loadApplyStatus();
      return;
    }
    // 202 (started here) or 409 (already running elsewhere) → poll for progress.
    if (res.status === 202 || res.status === 409) {
      beginApplyPoll();
    } else {
      console.warn('[v1-ai-assist] apply-agreed unexpected status', res.status);
      S.applyBusy = false;
      if (S.active) await loadApplyStatus();
    }
  } catch (e) {
    console.warn('[v1-ai-assist] apply-agreed failed:', e);
    S.applyBusy = false;
    if (S.active) await loadApplyStatus();
  }
}

function beginApplyPoll() {
  if (S.applyPollTimer) return;
  S.applyPollTimer = setInterval(async () => {
    await loadApplyStatus();
    if (!S.active) { clearApplyPoll(); return; }
    if (!S.applyAgreed || !S.applyAgreed.running) {
      clearApplyPoll();
      S.applyBusy = false;
      // Refresh the dashboard so queue/recent/cards reflect the applied rows.
      await Promise.all([loadStats(), loadQueue(), loadActivity()]);
    }
  }, APPLY_POLL_MS);
}

function clearApplyPoll() {
  if (S.applyPollTimer) {
    clearInterval(S.applyPollTimer);
    S.applyPollTimer = null;
  }
}

// ── Visibility self-guard ─────────────────────────────────────────────────────
// The v1 SPA navigates between top-level views (Tools/action, title-detail,
// actress-detail, …) by toggling display:none on container divs — WITHOUT calling
// hideAiAssistView. So a code-link click that opens title-detail would leave these
// poll timers firing in the background. `tick` wraps each interval callback: if the
// subview root is no longer on-screen (offsetParent === null ⇒ an ancestor is
// display:none — reliable here because the subview is a normal block element, not
// position:fixed), it tears itself down before issuing any fetch. When the user
// returns to the tab, showAiAssistView re-runs (teardown-first) and restarts a
// single set of timers, so this never double-registers.
function tick(fn) {
  return () => {
    if (!S.active || !S.body || S.body.offsetParent === null) { teardown(); return; }
    fn();
  };
}

// ── Poll functions ────────────────────────────────────────────────────────────
async function loadStats() {
  const stats = await fetchJson('/api/enrichment/assist/dashboard', null);
  if (!S.active) return;
  S.stats = stats;
  renderCards();
  // Piggyback the sweeper + apply-agreed status polls on the 5s stats timer.
  await loadSweeper();
  if (!S.active) return;
  await loadApplyStatus();
}

async function loadQueue() {
  const q = await fetchJson(`/api/enrichment/assist/queue-preview?limit=${QUEUE_LIMIT}`, []);
  if (!S.active) return;
  S.queue = q || [];
  renderQueue();
}

async function loadBatchProgress() {
  const bp = await fetchJson('/api/enrichment/assist/batch-progress', null);
  if (!S.active) return;
  S.batchProgress = bp || { active: false, chunkRowIds: [], pass: 0, currentRowId: null, currentCode: null, currentModel: null };
  // Cache the model observed for each pass so interim "done" pills can label the
  // model even after the cursor moves on. Persists across polls.
  if (bp && (bp.pass === 1 || bp.pass === 2) && bp.currentModel) {
    S.passModels[bp.pass] = bp.currentModel;
  }
  renderQueue();
}

async function loadActivity() {
  if (S.paused) return;
  const url = S.activitySince
    ? `/api/enrichment/assist/recent?limit=50&since=${encodeURIComponent(S.activitySince)}`
    : `/api/enrichment/assist/recent?limit=50`;
  const events = await fetchJson(url, []);
  if (!S.active) return;
  if (!events || events.length === 0) {
    S.lastNewIds = new Set();
    renderActivity();
    return;
  }
  // Sort newest-first by `at`.
  events.sort((a, b) => (b.at || '').localeCompare(a.at || ''));
  // Advance high-water mark to the newest `at` seen.
  S.activitySince = events[0].at;
  // Record the freshly-prepended ids so only these rows flash this render.
  S.lastNewIds = new Set(events.map(e => e.reviewQueueId).filter(id => id != null));
  S.activity = [...events, ...S.activity].slice(0, ACTIVITY_MAX_ROWS);
  renderActivity();
}

// ── Teardown ──────────────────────────────────────────────────────────────────
function teardown() {
  S.active = false;
  for (const key of Object.keys(S.timers)) {
    if (S.timers[key]) {
      clearInterval(S.timers[key]);
      S.timers[key] = null;
    }
  }
  clearApplyPoll();
  S.sweeperBusy = false;
  S.applyBusy = false;
  if (S.body) {
    S.body.removeEventListener('click', onBodyClick);
    S.body.removeEventListener('keydown', onBodyKeydown);
  }
}

// ── Lifecycle ──────────────────────────────────────────────────────────────────
export async function showAiAssistView() {
  // Idempotent: tear down any prior run first (the hub re-calls show on every
  // AI Assist tab selection).
  teardown();

  S.body = S.body || document.getElementById('ehub-ai-assist-subview');
  if (!S.body) return;
  S.active = true;
  S.body.style.display = '';

  renderShell();
  S.body.addEventListener('click', onBodyClick);
  S.body.addEventListener('keydown', onBodyKeydown);

  // Render whatever we have cached (instant on re-show).
  renderSweeperBar();
  renderCards();
  renderQueue();
  renderActivity();

  // Kick off the initial loads, then start the polling timers.
  await Promise.all([
    loadStats(), loadQueue(), loadActivity(),
    loadSweeper(), loadApplyStatus(), loadBatchProgress(),
  ]);
  if (!S.active) return;   // hidden while the initial fetch fan-out was in flight

  S.timers.stats    = setInterval(tick(loadStats),         STATS_POLL_MS);
  S.timers.queue    = setInterval(tick(loadQueue),         QUEUE_POLL_MS);
  S.timers.activity = setInterval(tick(loadActivity),      ACTIVITY_POLL_MS);
  S.timers.batch    = setInterval(tick(loadBatchProgress), BATCH_POLL_MS);
}

export function hideAiAssistView() {
  teardown();
  if (S.body) S.body.style.display = 'none';
}
