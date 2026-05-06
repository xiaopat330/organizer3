// Utilities → Translation screen.
// Stats panel (5s auto-refresh), strategy table, manual translate widget,
// bulk submit widget, recent failures list, health dot (30s refresh).
//
// State-factory pattern: all mutable vars inside makeState().

import { esc } from './utils.js';
import { showPendingKanjiView } from './utilities-pending-kanji.js';

// ── DOM refs ──────────────────────────────────────────────────────────────
const translationView   = document.getElementById('tools-translation-view');
const statsGrid         = document.getElementById('trans-stats');
const strategiesBody    = document.querySelector('#trans-strategies-table tbody');
const healthDot         = document.getElementById('trans-health-dot');
const manualInput       = document.getElementById('trans-manual-input');
const manualHint        = document.getElementById('trans-manual-hint');
const manualBtn         = document.getElementById('trans-manual-btn');
const manualResult      = document.getElementById('trans-manual-result');
const bulkBtn           = document.getElementById('trans-bulk-btn');
const bulkResult        = document.getElementById('trans-bulk-result');
const failuresDiv       = document.getElementById('trans-failures');
const activityList      = document.getElementById('trans-activity');
const queueList         = document.getElementById('trans-queue');
const tabBtnDashboard   = document.getElementById('trans-tabBtn-dashboard');
const tabBtnNameTrans   = document.getElementById('trans-tabBtn-name-translation');
const tabPaneDashboard  = document.getElementById('trans-tab-dashboard');
const tabPaneNameTrans  = document.getElementById('trans-tab-name-translation');

// ── Tab switching ─────────────────────────────────────────────────────────
function showTab(tab) {
  const isDashboard = tab === 'dashboard';
  tabPaneDashboard.style.display  = isDashboard ? '' : 'none';
  tabPaneNameTrans.style.display  = isDashboard ? 'none' : '';
  tabBtnDashboard.classList.toggle('active',  isDashboard);
  tabBtnNameTrans.classList.toggle('active', !isDashboard);
  if (!isDashboard) showPendingKanjiView();
}

tabBtnDashboard.addEventListener('click', () => showTab('dashboard'));
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
  loadHealth();
  loadRecentFailures();
  loadActivity();
  loadQueue();
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
async function loadStats() {
  if (bailIfHidden()) return;
  try {
    const [statsRes, sweeperRes] = await Promise.all([
      fetch('/api/translation/stats'),
      fetch('/api/translation/title-sweeper-status').catch(() => null),
    ]);
    const data = await statsRes.json();
    const sweeper = sweeperRes && sweeperRes.ok ? await sweeperRes.json() : null;
    const titlesPending = sweeper ? sweeper.pending : '—';
    const sweeperLabel  = sweeper && sweeper.enabled === false
        ? 'Titles awaiting (paused)' : 'Titles awaiting';
    const failureTile = (label, count, reason, hint) => {
      const id = `trans-requeue-${reason.replace(/_/g, '-')}`;
      return `<div class="trans-stat">
        <span class="trans-stat-label">${label}</span>
        <span class="trans-stat-val">${count}</span>
        ${count > 0
          ? `<button id="${id}" class="trans-stat-action" data-reason="${reason}" data-count="${count}" title="${hint}">Re-queue</button>`
          : ''}
      </div>`;
    };
    statsGrid.innerHTML = `
      <div class="trans-stat"><span class="trans-stat-label">Cache total</span><span class="trans-stat-val">${data.cacheTotal}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Successful</span><span class="trans-stat-val">${data.cacheSuccessful}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Failed</span><span class="trans-stat-val">${data.cacheFailed}</span></div>
      ${failureTile('Failed: sanitized', data.cacheFailedSanitized ?? 0, 'sanitized',
          'Tier-1 produced sanitized output. Re-queue to retry — useful after substitution-map updates.')}
      ${failureTile('Failed: both tiers', data.cacheFailedSanitizedBothTiers ?? 0, 'sanitized_both_tiers',
          'Both tier-1 and tier-2 produced sanitized output. Re-queue to retry on the next sweeper tick.')}
      ${failureTile('Failed: unreachable', data.cacheFailedUnreachable ?? 0, 'unreachable',
          'Ollama was unreachable when these were attempted. Safe to re-queue aggressively.')}
      ${failureTile('Failed: refused', data.cacheFailedRefused ?? 0, 'refused',
          'Model refused to translate. Re-queue to retry — substitution-map updates may help.')}
      <div class="trans-stat"><span class="trans-stat-label">Queue pending</span><span class="trans-stat-val">${data.queuePending}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">In flight</span><span class="trans-stat-val">${data.queueInFlight}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Tier-2 pending</span><span class="trans-stat-val">${data.queueTier2Pending}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Done</span><span class="trans-stat-val">${data.queueDone}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Throughput (1h)</span><span class="trans-stat-val">${data.throughputLastHour}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Stage-name lookup</span><span class="trans-stat-val">${data.stageNameLookupSize ?? 0}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Suggestions unreviewed</span><span class="trans-stat-val">${data.stageNameSuggestionsUnreviewed ?? 0}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">${sweeperLabel}</span><span class="trans-stat-val">${titlesPending}</span></div>
    `;
    statsGrid.querySelectorAll('.trans-stat-action[data-reason]').forEach(btn => {
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
        <span class="trans-queue-code">${code}</span>
        <span class="trans-queue-src">${src}</span>
        <span class="trans-queue-time">${esc(fmtTimestamp(r.submittedAt))}</span>
      </li>`;
    }).join('');
  } catch (err) {
    console.error('Queue preview poll failed', err);
  }
}

// ── Strategies ────────────────────────────────────────────────────────────
async function loadStrategies() {
  try {
    const res  = await fetch('/api/translation/strategies');
    const rows = await res.json();
    strategiesBody.innerHTML = rows.map(r => `
      <tr>
        <td>${esc(r.name)}</td>
        <td>${esc(r.modelId)}</td>
        <td>${r.active ? 'yes' : 'no'}</td>
        <td>${r.tier2StrategyId != null ? r.tier2StrategyId : '—'}</td>
      </tr>
    `).join('');
  } catch (err) {
    console.error('Translation strategies failed', err);
    strategiesBody.innerHTML = '<tr><td colspan="4">Failed to load strategies.</td></tr>';
  }
}

// ── Health dot ────────────────────────────────────────────────────────────
async function loadHealth() {
  if (bailIfHidden()) return;
  try {
    const res  = await fetch('/api/translation/health');
    const data = await res.json();
    healthDot.className = `trans-health-dot ${data.overall ? 'trans-health-ok' : 'trans-health-err'}`;
    healthDot.title = data.message || (data.overall ? 'Healthy' : 'Unhealthy');
  } catch (err) {
    healthDot.className = 'trans-health-dot trans-health-unknown';
    healthDot.title = 'Health check failed';
  }
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
  const src   = esc((e.sourceText || '').slice(0, 80));
  const out   = e.englishText
      ? `→ ${esc(e.englishText.slice(0, 80))}`
      : (e.failureReason ? `<span class="trans-act-reason">${esc(e.failureReason)}</span>` : '');
  return `<li class="trans-act-row trans-act-${klass}">
    <span class="trans-act-time">${esc(fmtTimestamp(e.cachedAt))}</span>
    <span class="trans-act-code">${code}</span>
    <span class="trans-act-src">${src}</span>
    <span class="trans-act-out">${out}</span>
    <span class="trans-act-latency">${fmtLatency(e.latencyMs)}</span>
  </li>`;
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
      <tbody>${rows.map(r => `
        <tr>
          <td class="trans-code-cell">${esc(r.titleCode || '—')}</td>
          <td class="trans-source-cell">${esc(r.sourceText || '')}</td>
          <td>${esc(r.failureReason || '')}</td>
          <td class="trans-latency-cell">${fmtLatency(r.latencyMs)}</td>
          <td class="trans-time-cell" title="${esc(r.cachedAt || '')}">${esc(fmtTimestamp(r.cachedAt))}</td>
        </tr>
      `).join('')}</tbody>
    </table>`;
  } catch (err) {
    console.error('Recent failures failed', err);
    failuresDiv.textContent = 'Failed to load recent failures.';
  }
}

// ── Manual translate ──────────────────────────────────────────────────────
manualBtn.addEventListener('click', async () => {
  const text = manualInput.value.trim();
  if (!text) {
    showManualResult('error', 'Please enter some Japanese text.');
    return;
  }

  manualBtn.disabled = true;
  manualBtn.textContent = 'Translating…';
  manualResult.style.display = 'none';

  try {
    const res = await fetch('/api/translation/manual', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sourceText: text, contextHint: manualHint.value || null }),
    });
    const data = await res.json();
    if (res.ok && data.success) {
      showManualResult('ok', esc(data.englishText));
      // Refresh failures list in case a new failure was written
      loadRecentFailures();
    } else {
      showManualResult('error', esc(data.message || data.error || 'Translation failed.'));
    }
  } catch (err) {
    showManualResult('error', 'Network error: ' + err.message);
  } finally {
    manualBtn.disabled = false;
    manualBtn.textContent = 'Translate';
  }
});

function showManualResult(type, html) {
  manualResult.style.display = 'block';
  manualResult.className = `trans-manual-result trans-manual-result-${type}`;
  manualResult.innerHTML = html;
}

// ── Sweep title backlog now ───────────────────────────────────────────────
bulkBtn.addEventListener('click', async () => {
  bulkBtn.disabled = true;
  bulkBtn.textContent = 'Sweeping…';
  bulkResult.style.display = 'none';

  try {
    const res = await fetch('/api/translation/sweep-title-backlog-now', { method: 'POST' });
    const data = await res.json();
    if (res.ok) {
      const { submitted, remaining } = data;
      const msg = submitted === 0
        ? 'No work to do — all titles already translated or queued.'
        : `Submitted ${submitted} title${submitted === 1 ? '' : 's'} for translation.`
          + ` Remaining backlog: ${remaining}.`;
      showBulkResult('ok', msg);
      loadStats();
    } else {
      showBulkResult('error', esc(data.error || 'Sweep failed.'));
    }
  } catch (err) {
    showBulkResult('error', 'Network error: ' + err.message);
  } finally {
    bulkBtn.disabled = false;
    bulkBtn.textContent = 'Sweep now';
  }
});

function showBulkResult(type, text) {
  bulkResult.style.display = 'block';
  bulkResult.className = `trans-bulk-result trans-bulk-result-${type}`;
  bulkResult.textContent = text;
}
