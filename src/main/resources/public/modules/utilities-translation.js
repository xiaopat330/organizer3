// Utilities → Translation screen.
// Stats panel (5s auto-refresh), strategy table, manual translate widget,
// bulk submit widget, recent failures list, health dot (30s refresh).
//
// State-factory pattern: all mutable vars inside makeState().

import { esc } from './utils.js';
import { showPendingKanjiView } from './utilities-pending-kanji.js';
import { activateClickableCodes } from './cover-modal.js';

// ── DOM refs ──────────────────────────────────────────────────────────────
const translationView   = document.getElementById('tools-translation-view');
const statsGrid         = document.getElementById('trans-stats');
const healthDot         = document.getElementById('trans-health-dot');
const failuresDiv       = document.getElementById('trans-failures');
const activityList      = document.getElementById('trans-activity');
const queueList         = document.getElementById('trans-queue');
const tabBtnDashboard   = document.getElementById('trans-tabBtn-dashboard');
const tabBtnFailures    = document.getElementById('trans-tabBtn-failures');
const tabBtnNameTrans   = document.getElementById('trans-tabBtn-name-translation');
const tabPaneDashboard  = document.getElementById('trans-tab-dashboard');
const tabPaneFailures   = document.getElementById('trans-tab-failures');
const tabPaneNameTrans  = document.getElementById('trans-tab-name-translation');

// ── Tab switching ─────────────────────────────────────────────────────────
function showTab(tab) {
  tabPaneDashboard.style.display = tab === 'dashboard'        ? '' : 'none';
  tabPaneFailures.style.display  = tab === 'failures'         ? '' : 'none';
  tabPaneNameTrans.style.display = tab === 'name-translation' ? '' : 'none';
  tabBtnDashboard.classList.toggle('active', tab === 'dashboard');
  tabBtnFailures.classList.toggle('active',  tab === 'failures');
  tabBtnNameTrans.classList.toggle('active', tab === 'name-translation');
  if (tab === 'name-translation') showPendingKanjiView();
  if (tab === 'failures')         loadRecentFailures();
}

tabBtnDashboard.addEventListener('click', () => showTab('dashboard'));
tabBtnFailures.addEventListener('click',  () => showTab('failures'));
tabBtnNameTrans.addEventListener('click', () => showTab('name-translation'));

// ── State factory ─────────────────────────────────────────────────────────
const ACTIVITY_MAX_ROWS = 50;

function makeState() {
  return {
    statsTimer: null,
    healthTimer: null,
    activityTimer: null,
    queueTimer: null,
    activityRows: [],          // newest first; capped to ACTIVITY_MAX_ROWS
    activitySince: null,       // high-water-mark cached_at from the last fetch
    visible: false,
    failuresExpanded: false,   // Card 2 progressive-disclosure toggle
    strategies: null,          // last-fetched /api/translation/strategies, used by Card 4
    explicitSubs: null,        // {jpKey: enValue} — used to highlight substituted terms in activity
    explicitJpKeysByLen: [],   // jp keys sorted by length desc (longest-match-wins highlighting)
    explicitEnValsByLen: [],   // en values sorted by length desc
    stageNames: null,          // {kanji: canonical} — used to highlight stage-name matches
    stageJpKeysByLen: [],
    stageEnValsByLen: [],
    health: null,              // last /api/translation/health payload — surfaced in Card 4 footer
  };
}

let s = makeState();

// ── Public API ────────────────────────────────────────────────────────────
export function showTranslationView() {
  translationView.style.display = 'block';
  s.visible = true;
  showTab('dashboard');
  loadStats();
  loadStrategies();
  loadExplicitSubs();
  loadStageNames();
  loadHealth();
  loadActivity();
  loadQueue();
  // Recent failures lazy-loads on tab activation
  s.statsTimer    = setInterval(loadStats,    5_000);
  s.healthTimer   = setInterval(loadHealth,  30_000);
  s.activityTimer = setInterval(loadActivity, 2_000);
  s.queueTimer    = setInterval(loadQueue,    5_000);
}

export function hideTranslationView() {
  translationView.style.display = 'none';
  s.visible = false;
  clearInterval(s.statsTimer);
  clearInterval(s.healthTimer);
  clearInterval(s.activityTimer);
  clearInterval(s.queueTimer);
  s.statsTimer    = null;
  s.healthTimer   = null;
  s.activityTimer = null;
  s.queueTimer    = null;
  // Reset feed state so the next view-show starts fresh
  s.activityRows = [];
  s.activitySince = null;
}

// ── Visibility guard ──────────────────────────────────────────────────────
// If the panel was hidden by top-nav (which bypasses hideTranslationView),
// timers would otherwise keep polling indefinitely. Detect this and tear down.
function bailIfHidden() {
  if (translationView.style.display === 'none') {
    hideTranslationView();
    return true;
  }
  return false;
}

// ── Stats ─────────────────────────────────────────────────────────────────
//
// Stats panel = three cards:
//   1. Throughput & ETA (headline; ETA = (pending + in-flight) / throughputLastHour)
//   2. Cache health (SVG donut; click failed slice to expand the four reason buttons)
//   3. Curation backlog (stage-name suggestions + lookup + titles awaiting)

const N = (v) => (v == null ? 0 : v);
const fmt = (v) => (v == null ? '—' : Number(v).toLocaleString());

/** ETA tier from hours-of-backlog. Returns {label, tier: 'green'|'amber'|'red'|'idle'|'stall'}. */
function classifyEta(pending, throughputPerHour) {
  if (pending === 0) return { hours: 0, label: 'idle', tier: 'idle' };
  if (!throughputPerHour) return { hours: Infinity, label: 'stalled', tier: 'stall' };
  const hours = pending / throughputPerHour;
  let label;
  if (hours < 1)       label = `~${Math.round(hours * 60)}m`;
  else if (hours < 24) label = `~${hours.toFixed(1)}h`;
  else                 label = `~${(hours / 24).toFixed(1)}d`;
  const tier = hours < 4 ? 'green' : hours < 24 ? 'amber' : 'red';
  return { hours, label, tier };
}

/** Hand-rolled SVG donut. Pure: success, fail (counts) → svg string. */
function renderDonut(success, fail, size = 140) {
  const total = success + fail;
  const r = size / 2 - 12;
  const cx = size / 2;
  const cy = size / 2;
  if (total === 0) {
    return `<svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" class="trans-donut">
      <circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="#2a2a2a" stroke-width="14" />
      <text x="${cx}" y="${cy}" text-anchor="middle" dominant-baseline="central" class="trans-donut-center-num">0</text>
      <text x="${cx}" y="${cy + 18}" text-anchor="middle" class="trans-donut-center-sub">no data</text>
    </svg>`;
  }
  const successPct = (success / total) * 100;
  const successLen = (successPct / 100) * 2 * Math.PI * r;
  const circumference = 2 * Math.PI * r;
  // Two stacked circles using stroke-dasharray to draw arcs.
  return `<svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" class="trans-donut">
    <circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="#7f1d1d" stroke-width="14" />
    <circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="#15803d" stroke-width="14"
            stroke-dasharray="${successLen} ${circumference - successLen}"
            transform="rotate(-90 ${cx} ${cy})" />
    <text x="${cx}" y="${cy - 6}" text-anchor="middle" class="trans-donut-center-num">${Math.round(successPct)}%</text>
    <text x="${cx}" y="${cy + 14}" text-anchor="middle" class="trans-donut-center-sub">${fmt(total)} cached</text>
  </svg>`;
}

/** Failure breakdown row HTML (used inside the expanded panel). */
function failureRow(label, count, reason, hint) {
  const id = `trans-requeue-${reason.replace(/_/g, '-')}`;
  const action = count > 0
    ? `<button id="${id}" class="trans-failure-requeue" data-reason="${reason}" data-count="${count}" title="${hint}">Re-queue</button>`
    : '<span class="trans-failure-empty">—</span>';
  return `<div class="trans-failure-row" data-reason="${reason}">
    <span class="trans-failure-label">${label}</span>
    <span class="trans-failure-count">${count}</span>
    ${action}
  </div>`;
}

/** Big status pill for Card 4 header. Pure. */
function renderOllamaPill(health) {
  if (!health) {
    return `<div class="ollama-pill ollama-pill-unknown">
      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 8v4M12 16h.01"/></svg>
      <span>Checking…</span>
    </div>`;
  }
  const p95Ms = health.latencyP95Ms;
  const p95   = (p95Ms != null && p95Ms > 0) ? ` · responding in ${(p95Ms/1000).toFixed(1)}s` : '';
  if (!health.overall) {
    return `<div class="ollama-pill ollama-pill-err">
      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><line x1="8" y1="8" x2="16" y2="16"/><line x1="16" y1="8" x2="8" y2="16"/></svg>
      <span>${esc(health.message || 'Unreachable')}</span>
    </div>`;
  }
  if (!health.latencyOk) {
    return `<div class="ollama-pill ollama-pill-warn">
      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
      <span>Slow${p95}</span>
    </div>`;
  }
  return `<div class="ollama-pill ollama-pill-ok">
    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><polyline points="5 12 10 17 19 7"/></svg>
    <span>Working${p95}</span>
  </div>`;
}

/** Pretty memory size in GB. Returns "—" for 0/null. */
function fmtGb(bytes) {
  if (!bytes) return '—';
  return `${(bytes / 1073741824).toFixed(1)} GB`;
}

/** Live TTL string from an ISO expiry. Returns null if no expiry / already past. */
function fmtTtl(iso) {
  if (!iso) return null;
  const ms = new Date(iso).getTime() - Date.now();
  if (ms <= 0) return null;
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  if (m >= 60) return `${Math.floor(m/60)}h ${m%60}m`;
  if (m > 0)   return `${m}m ${String(s).padStart(2, '0')}s`;
  return `${s}s`;
}

/** Loaded-models block (live state from /api/ps). Pure. */
function renderLoadedModels(health) {
  const list = (health && health.loadedModels) || [];
  if (!list.length) {
    return `<div class="ollama-loaded-empty">No models loaded</div>`;
  }
  // Default Ollama TTL is 5 minutes. Use that as the bar's denominator;
  // visualizes "how long until this model unloads".
  const TTL_FULL_MS = 5 * 60 * 1000;
  let totalBytes = 0;
  const rows = list.map(m => {
    totalBytes += (m.sizeBytes || 0);
    const ttlText = fmtTtl(m.expiresAtIso) || 'pinned';
    let pct = 0;
    if (m.expiresAtIso) {
      const remaining = new Date(m.expiresAtIso).getTime() - Date.now();
      pct = Math.max(0, Math.min(100, (remaining / TTL_FULL_MS) * 100));
    } else {
      pct = 100; // never expires
    }
    return `<div class="ollama-loaded-row">
      <span class="ollama-loaded-name">${esc(m.name)}</span>
      <span class="ollama-loaded-size">${esc(fmtGb(m.sizeBytes))}</span>
      <span class="ollama-loaded-ttl-bar"><span style="width:${pct.toFixed(1)}%"></span></span>
      <span class="ollama-loaded-ttl-text">${esc(ttlText)}</span>
    </div>`;
  }).join('');
  return `<div class="ollama-loaded-block">
    <div class="ollama-loaded-header">
      <span>Loaded now</span>
      <span class="ollama-loaded-total">${esc(fmtGb(totalBytes))} resident</span>
    </div>
    ${rows}
  </div>`;
}

/** Returns {primary, fallback} model ids by inspecting active strategies. Pure. */
function summarizeStrategies(strategies) {
  if (!strategies || !strategies.length) return { primary: null, fallback: null };
  // A strategy id referenced by another strategy's tier2StrategyId is a fallback;
  // anything else that's active is primary.
  const tier2Ids = new Set();
  strategies.forEach(s => { if (s.tier2StrategyId != null) tier2Ids.add(s.tier2StrategyId); });
  const primarySet  = new Set();
  const fallbackSet = new Set();
  strategies.forEach(s => {
    if (tier2Ids.has(s.id)) fallbackSet.add(s.modelId);
    else                    primarySet.add(s.modelId);
  });
  // Most installations have exactly one model in each role; pick the first.
  const join = (set) => set.size ? Array.from(set).join(', ') : null;
  return { primary: join(primarySet), fallback: join(fallbackSet) };
}

/** Renders the four-card HTML. Pure: data + sweeper + expanded + strategies + health → string. */
function renderStatsHTML(data, sweeper, expanded, strategies, health) {
  const pending  = N(data.queuePending);
  const inFlight = N(data.queueInFlight);
  const tier2    = N(data.queueTier2Pending);
  const thr      = N(data.throughputLastHour);
  const eta      = classifyEta(pending + inFlight, thr);

  const sweeperPaused = sweeper && sweeper.enabled === false;
  const titlesPending = sweeper ? fmt(sweeper.pending) : '—';
  const titlesLabel   = sweeperPaused ? 'Titles awaiting (paused)' : 'Titles awaiting';

  const cacheTotal = N(data.cacheTotal);
  const cacheOk    = N(data.cacheSuccessful);
  const cacheFail  = N(data.cacheFailed);

  // Card 2 expanded body: 4 failure breakdown rows + Re-queue buttons.
  const failuresPanel = expanded ? `
    <div class="trans-failure-panel">
      ${failureRow('Sanitized',          N(data.cacheFailedSanitized), 'sanitized',
          'Tier-1 produced sanitized output. Re-queue after substitution-map updates.')}
      ${failureRow('Sanitized (tier-2)', N(data.cacheFailedSanitizedBothTiers), 'sanitized_both_tiers',
          'Both tiers produced sanitized output. Re-queue to retry on next sweeper tick.')}
      ${failureRow('Unreachable',        N(data.cacheFailedUnreachable), 'unreachable',
          'AI service was unreachable when these were attempted. Safe to re-queue aggressively.')}
      ${failureRow('Refused',            N(data.cacheFailedRefused), 'refused',
          'Model refused to translate. Re-queue may help after substitution updates.')}
    </div>
  ` : '';

  const stageSuggestions = N(data.stageNameSuggestionsUnreviewed);
  const stageLookup      = N(data.stageNameLookupSize);

  return `
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
      <div class="trans-card-sub trans-subs-row" title="Explicit term substitutions applied since startup. Map size shown in parentheses.">
        Substitutions: <strong>${fmt(N(data.explicitSubsApplied))}</strong>
        across <strong>${fmt(N(data.explicitSubsRowsTouched))}</strong> titles
        <span class="trans-subs-mapsize">(map: ${fmt(N(data.explicitSubsMapSize))})</span>
      </div>
    </div>

    <div class="trans-card trans-card-cache">
      <div class="trans-card-title">Cache health</div>
      <div class="trans-cache-body">
        ${renderDonut(cacheOk, cacheFail)}
        <div class="trans-cache-legend">
          <div class="trans-legend-row" title="Successful">
            <svg class="trans-legend-icon icon-ok" viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 8.5 6.5 12 13 4.5"/></svg>
            <strong>${fmt(cacheOk)}</strong>
          </div>
          <button class="trans-legend-row trans-failed-toggle ${expanded ? 'expanded' : ''}"
                  type="button"
                  title="Failed${cacheFail > 0 ? ' — click to expand' : ''}"
                  ${cacheFail === 0 ? 'disabled' : ''}>
            <svg class="trans-legend-icon icon-fail" viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="4" x2="12" y2="12"/><line x1="12" y1="4" x2="4" y2="12"/></svg>
            <strong>${fmt(cacheFail)}</strong>
            ${cacheFail > 0 ? `<span class="trans-failed-caret">${expanded ? '▾' : '▸'}</span>` : ''}
          </button>
        </div>
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
        <button type="button" id="trans-sweep-now-btn" class="trans-sweep-btn"
                title="Forces an immediate sweep of titles awaiting English translation. Same dedup as the scheduled 5-minute sweeper — cannot double-enqueue.">
          Sweep
        </button>
      </div>
      <div class="trans-card-sub">
        Stage-name lookup: <strong>${fmt(stageLookup)}</strong>
      </div>
    </div>

    <div class="trans-card trans-card-models">
      <div class="trans-card-title">AI</div>
      ${renderOllamaPill(health)}
      ${(() => {
        if (!strategies) return `<div class="trans-card-sub">Loading…</div>`;
        const { primary, fallback } = summarizeStrategies(strategies);
        const current = data.currentModelId || null;
        const row = (label, modelId) => {
          const active = modelId && current && modelId.split(',').map(s => s.trim()).includes(current);
          return `<div class="trans-model-row ${active ? 'is-active' : ''}">
            <span class="trans-model-role-label">${label}</span>
            <span class="${modelId ? 'trans-model-name' : 'trans-model-missing'}">${
              modelId ? esc(modelId) : 'not configured'
            }${active ? '<span class="trans-model-active-dot" title="Currently in flight"></span>' : ''}</span>
          </div>`;
        };
        return row('Primary', primary) + row('Fallback', fallback);
      })()}
      ${renderLoadedModels(health)}
    </div>
  `;
}

async function triggerSweep(btn) {
  btn.disabled = true;
  const original = btn.textContent;
  btn.textContent = '…';
  try {
    const res = await fetch('/api/translation/sweep-title-backlog-now', { method: 'POST' });
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      alert('Sweep failed: ' + (data.error || `HTTP ${res.status}`));
      return;
    }
    // Success — next stats poll will show the updated Titles awaiting count.
    loadStats();
  } catch (err) {
    alert('Network error: ' + err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = original;
  }
}

async function loadStats() {
  if (bailIfHidden()) return;
  try {
    const [statsRes, sweeperRes] = await Promise.all([
      fetch('/api/translation/stats'),
      fetch('/api/translation/title-sweeper-status').catch(() => null),
    ]);
    const data = await statsRes.json();
    const sweeper = sweeperRes && sweeperRes.ok ? await sweeperRes.json() : null;

    statsGrid.innerHTML = renderStatsHTML(data, sweeper, s.failuresExpanded, s.strategies, s.health);

    // Sweep-now button (Card 3, next to Titles awaiting).
    const sweepBtn = statsGrid.querySelector('#trans-sweep-now-btn');
    if (sweepBtn) sweepBtn.addEventListener('click', () => triggerSweep(sweepBtn));

    // Toggle the failures panel.
    const toggle = statsGrid.querySelector('.trans-failed-toggle');
    if (toggle && !toggle.disabled) {
      toggle.addEventListener('click', () => {
        s.failuresExpanded = !s.failuresExpanded;
        loadStats();
      });
    }

    // Re-queue button handlers (only present when expanded).
    statsGrid.querySelectorAll('.trans-failure-requeue[data-reason]').forEach(btn => {
      btn.addEventListener('click', async () => {
        const reason = btn.dataset.reason;
        const n      = btn.dataset.count;
        if (!confirm(`Re-queue ${n} '${reason}' failure(s)? They will be retried on the next sweeper tick.`)) return;
        btn.disabled = true;
        btn.textContent = '...';
        try {
          const res = await fetch(`/api/translation/requeue-by-reason?reason=${encodeURIComponent(reason)}`, { method: 'POST' });
          if (!res.ok) throw new Error('HTTP ' + res.status);
          await loadStats();
        } catch (err) {
          console.error('Re-queue failed', err);
          btn.disabled = false;
          btn.textContent = 'Re-queue';
          alert('Re-queue failed: ' + err.message);
        }
      });
    });
  } catch (err) {
    console.error('Translation stats failed', err);
    statsGrid.textContent = 'Failed to load stats.';
  }
}

// ── Queue preview ─────────────────────────────────────────────────────────
async function loadQueue() {
  if (bailIfHidden()) return;
  try {
    const res  = await fetch('/api/translation/queue-preview?limit=15');
    if (!res.ok) return;
    const rows = await res.json();
    if (!rows.length) {
      queueList.innerHTML = '<li class="trans-empty">Queue is empty.</li>';
      return;
    }
    queueList.innerHTML = rows.map(r => {
      const inFlight = r.status === 'in_flight';
      const code = esc(r.titleCode || '—');
      const src  = esc((r.sourceText || '').slice(0, 100));
      return `<li class="trans-queue-row ${inFlight ? 'trans-queue-inflight' : 'trans-queue-pending'}">
        <span class="trans-queue-badge">${inFlight ? 'in-flight' : 'pending'}</span>
        <span class="trans-queue-code" data-title-code="${code}">${code}</span>
        <span class="trans-queue-src">${src}</span>
        <span class="trans-queue-time">${esc(fmtTimestamp(r.submittedAt))}</span>
      </li>`;
    }).join('');
    activateClickableCodes(queueList);
  } catch (err) {
    console.error('Queue preview poll failed', err);
  }
}

// ── Strategies ────────────────────────────────────────────────────────────
// Strategies are fetched once per view-show (they rarely change) and rendered
// as Card 4 in the stats grid by renderStatsHTML.
async function loadStrategies() {
  try {
    const res  = await fetch('/api/translation/strategies');
    s.strategies = await res.json();
    // Re-render stats so Card 4 picks up the data.
    loadStats();
  } catch (err) {
    console.error('Translation strategies failed', err);
    s.strategies = [];
  }
}

// ── Health dot ────────────────────────────────────────────────────────────
async function loadHealth() {
  if (bailIfHidden()) return;
  try {
    const res  = await fetch('/api/translation/health');
    const data = await res.json();
    s.health = data;
    healthDot.className = `trans-health-dot ${data.overall ? 'trans-health-ok' : 'trans-health-err'}`;
    healthDot.title = data.message || (data.overall ? 'Healthy' : 'Unhealthy');
    // Refresh stats so Card 4 picks up the new latency/status footer.
    loadStats();
  } catch (err) {
    s.health = null;
    healthDot.className = 'trans-health-dot trans-health-unknown';
    healthDot.title = 'Health check failed';
  }
}

// ── Explicit-substitutions map (for highlighting in activity feed) ────────
async function loadExplicitSubs() {
  try {
    const res = await fetch('/api/translation/explicit-substitutions');
    if (!res.ok) return;
    const map = await res.json();
    s.explicitSubs = map;
    // Pre-sort longest-match-first so highlighting doesn't nest spans on substring overlaps.
    const byLen = (a, b) => b.length - a.length;
    s.explicitJpKeysByLen = Object.keys(map).filter(Boolean).sort(byLen);
    s.explicitEnValsByLen = Object.values(map).filter(Boolean).sort(byLen);
  } catch (err) {
    console.error('Explicit subs load failed', err);
  }
}

async function loadStageNames() {
  try {
    const res = await fetch('/api/translation/stage-name-map');
    if (!res.ok) return;
    const map = await res.json();
    s.stageNames = map;
    const byLen = (a, b) => b.length - a.length;
    s.stageJpKeysByLen = Object.keys(map).filter(Boolean).sort(byLen);
    s.stageEnValsByLen = Object.values(map).filter(Boolean).sort(byLen);
  } catch (err) {
    console.error('Stage-name map load failed', err);
  }
}

/**
 * Returns escaped HTML for `text` with any occurrence of a term wrapped in
 * <span class="${klass}">. Terms are escape()d before search so the result is
 * always safe for innerHTML. Pure.
 */
function highlightTerms(text, termsByLen, klass) {
  if (!text) return '';
  let html = esc(text);
  if (!termsByLen || !termsByLen.length) return html;
  for (const term of termsByLen) {
    if (!term) continue;
    const eterm = esc(term);
    if (!html.includes(eterm)) continue;
    html = html.split(eterm).join(`<span class="${klass}">${eterm}</span>`);
  }
  return html;
}

// ── Live activity feed ────────────────────────────────────────────────────
function classifyEvent(e) {
  if (e.failureReason) {
    // 'sanitized' on a tier-1 strategy → escalated to tier-2 (transient)
    // 'sanitized_both_tiers' / 'refused' / others → permanent failure
    if (e.failureReason === 'sanitized') return 'escalated';
    return 'failed';
  }
  return e.englishText ? 'success' : 'pending';
}

function renderActivityRow(e) {
  const klass = classifyEvent(e);
  const code  = esc(e.titleCode || '—');
  const srcRaw = (e.sourceText || '').slice(0, 80);
  // Two-pass highlight: red explicit-substitution matches first, then green stage-name matches.
  // Stage names (more meaningful) wrap second so on rare overlap their span is outermost.
  let src = highlightTerms(srcRaw, s.explicitJpKeysByLen, 'trans-act-sub-jp');
  src     = highlightTerms_inHtml(src, s.stageJpKeysByLen, 'trans-act-stage-jp');
  let out;
  if (e.englishText) {
    const enRaw = e.englishText.slice(0, 80);
    let enHtml  = highlightTerms(enRaw, s.explicitEnValsByLen, 'trans-act-sub-en');
    enHtml      = highlightTerms_inHtml(enHtml, s.stageEnValsByLen, 'trans-act-stage-en');
    out = `→ ${enHtml}`;
  } else if (e.failureReason) {
    out = `<span class="trans-act-reason">${esc(e.failureReason)}</span>`;
  } else {
    out = '';
  }
  return `<li class="trans-act-row trans-act-${klass}">
    <span class="trans-act-time">${esc(fmtTimestamp(e.cachedAt))}</span>
    <span class="trans-act-code" data-title-code="${code}">${code}</span>
    <span class="trans-act-src">${src}</span>
    <span class="trans-act-out">${out}</span>
    <span class="trans-act-latency">${fmtLatency(e.latencyMs)}</span>
  </li>`;
}

/**
 * Like highlightTerms, but operates on already-highlighted HTML — wraps any
 * occurrence of an escaped term in `html`. Won't double-wrap an existing span
 * because terms are CJK/word-character sequences that don't contain `<` or `>`.
 */
function highlightTerms_inHtml(html, termsByLen, klass) {
  if (!termsByLen || !termsByLen.length) return html;
  for (const term of termsByLen) {
    if (!term) continue;
    const eterm = esc(term);
    if (!html.includes(eterm)) continue;
    html = html.split(eterm).join(`<span class="${klass}">${eterm}</span>`);
  }
  return html;
}

async function loadActivity() {
  if (bailIfHidden()) return;
  try {
    const url = s.activitySince
        ? `/api/translation/recent-events?limit=50&since=${encodeURIComponent(s.activitySince)}`
        : '/api/translation/recent-events?limit=50';
    const res = await fetch(url);
    if (!res.ok) return;
    const newRows = await res.json();
    if (!newRows.length) {
      // First-load with no rows at all — keep "Waiting for first event…"
      if (s.activityRows.length === 0) return;
      return; // otherwise no-op; keep existing list
    }
    // Server returns newest first; merge into our list (also newest first), drop older overflow
    s.activityRows = [...newRows, ...s.activityRows].slice(0, ACTIVITY_MAX_ROWS);
    // Bump high-water-mark to the newest cached_at we've seen
    s.activitySince = newRows[0].cachedAt;

    activityList.innerHTML = s.activityRows.map(renderActivityRow).join('');
    activateClickableCodes(activityList);
  } catch (err) {
    console.error('Live activity poll failed', err);
  }
}

// ── Recent failures ───────────────────────────────────────────────────────
function fmtLatency(ms) {
  if (ms == null) return '—';
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(2)} s`;
}

function fmtTimestamp(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d)) return iso;
  return d.toLocaleString(undefined, {
    month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    hour12: false,
  });
}

async function loadRecentFailures() {
  try {
    const res  = await fetch('/api/translation/recent-failures?limit=20');
    const rows = await res.json();
    if (!rows.length) {
      failuresDiv.innerHTML = '<p class="trans-empty">No recent failures.</p>';
      return;
    }
    failuresDiv.innerHTML = `<table class="trans-table">
      <thead><tr>
        <th>Code</th><th>Source text</th><th>Failure reason</th><th>Latency</th><th>Cached at</th>
      </tr></thead>
      <tbody>${rows.map(r => {
        const code = esc(r.titleCode || '—');
        return `
        <tr>
          <td class="trans-code-cell"><span data-title-code="${code}">${code}</span></td>
          <td class="trans-source-cell">${esc(r.sourceText || '')}</td>
          <td>${esc(r.failureReason || '')}</td>
          <td class="trans-latency-cell">${fmtLatency(r.latencyMs)}</td>
          <td class="trans-time-cell" title="${esc(r.cachedAt || '')}">${esc(fmtTimestamp(r.cachedAt))}</td>
        </tr>
      `;
      }).join('')}</tbody>
    </table>`;
    activateClickableCodes(failuresDiv);
  } catch (err) {
    console.error('Recent failures failed', err);
    failuresDiv.textContent = 'Failed to load recent failures.';
  }
}

