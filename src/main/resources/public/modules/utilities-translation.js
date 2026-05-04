// Utilities → Translation screen.
// Stats panel (5s auto-refresh), strategy table, manual translate widget,
// bulk submit widget, recent failures list, health dot (30s refresh).
//
// State-factory pattern: all mutable vars inside makeState().

import { esc } from './utils.js';

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

// ── State factory ─────────────────────────────────────────────────────────
function makeState() {
  return {
    statsTimer: null,
    healthTimer: null,
    visible: false,
  };
}

let s = makeState();

// ── Public API ────────────────────────────────────────────────────────────
export function showTranslationView() {
  translationView.style.display = 'block';
  s.visible = true;
  loadStats();
  loadStrategies();
  loadHealth();
  loadRecentFailures();
  s.statsTimer  = setInterval(loadStats,  5_000);
  s.healthTimer = setInterval(loadHealth, 30_000);
}

export function hideTranslationView() {
  translationView.style.display = 'none';
  s.visible = false;
  clearInterval(s.statsTimer);
  clearInterval(s.healthTimer);
  s.statsTimer  = null;
  s.healthTimer = null;
}

// ── Stats ─────────────────────────────────────────────────────────────────
async function loadStats() {
  try {
    const res  = await fetch('/api/translation/stats');
    const data = await res.json();
    statsGrid.innerHTML = `
      <div class="trans-stat"><span class="trans-stat-label">Cache total</span><span class="trans-stat-val">${data.cacheTotal}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Successful</span><span class="trans-stat-val">${data.cacheSuccessful}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Failed</span><span class="trans-stat-val">${data.cacheFailed}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Queue pending</span><span class="trans-stat-val">${data.queuePending}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">In flight</span><span class="trans-stat-val">${data.queueInFlight}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Tier-2 pending</span><span class="trans-stat-val">${data.queueTier2Pending}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Done</span><span class="trans-stat-val">${data.queueDone}</span></div>
      <div class="trans-stat"><span class="trans-stat-label">Throughput (1h)</span><span class="trans-stat-val">${data.throughputLastHour}</span></div>
    `;
  } catch (err) {
    console.error('Translation stats failed', err);
    statsGrid.textContent = 'Failed to load stats.';
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

// ── Recent failures ───────────────────────────────────────────────────────
async function loadRecentFailures() {
  try {
    const res  = await fetch('/api/translation/recent-failures?limit=20');
    const rows = await res.json();
    if (!rows.length) {
      failuresDiv.innerHTML = '<p class="trans-empty">No recent failures.</p>';
      return;
    }
    failuresDiv.innerHTML = `<table class="trans-table">
      <thead><tr><th>Source text</th><th>Failure reason</th><th>Latency ms</th><th>Cached at</th></tr></thead>
      <tbody>${rows.map(r => `
        <tr>
          <td class="trans-source-cell">${esc(r.sourceText || '')}</td>
          <td>${esc(r.failureReason || '')}</td>
          <td>${r.latencyMs != null ? r.latencyMs : '—'}</td>
          <td>${esc(r.cachedAt || '')}</td>
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

// ── Bulk submit ───────────────────────────────────────────────────────────
bulkBtn.addEventListener('click', async () => {
  bulkBtn.disabled = true;
  bulkBtn.textContent = 'Fetching titles…';
  bulkResult.style.display = 'none';

  try {
    // Fetch titles that have title_original but no title_original_en
    const titlesRes = await fetch('/api/translation/bulk-candidates');
    if (!titlesRes.ok) {
      const err = await titlesRes.json().catch(() => ({}));
      showBulkResult('error', 'Failed to fetch candidates: ' + (err.error || titlesRes.statusText));
      return;
    }
    const candidates = await titlesRes.json();  // [{ titleId, titleOriginal }]

    if (!candidates.length) {
      showBulkResult('ok', 'No pending candidates — all title_original values are already translated or queued.');
      return;
    }

    bulkBtn.textContent = `Submitting ${candidates.length} items…`;

    const items = candidates.map(c => ({
      sourceText:   c.titleOriginal,
      callbackKind: 'title_javdb_enrichment.title_original_en',
      callbackId:   c.titleId,
      contextHint:  'label_basic',
    }));

    const res = await fetch('/api/translation/bulk', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ items }),
    });
    const data = await res.json();
    if (res.ok) {
      showBulkResult('ok', `Enqueued: ${data.enqueued}, skipped: ${data.skipped}.`
        + (data.errors.length ? ' Errors: ' + data.errors.map(esc).join('; ') : ''));
      loadStats();
    } else {
      showBulkResult('error', esc(data.error || 'Bulk submit failed.'));
    }
  } catch (err) {
    showBulkResult('error', 'Network error: ' + err.message);
  } finally {
    bulkBtn.disabled = false;
    bulkBtn.textContent = 'Submit title_original batch';
  }
});

function showBulkResult(type, text) {
  bulkResult.style.display = 'block';
  bulkResult.className = `trans-bulk-result trans-bulk-result-${type}`;
  bulkResult.textContent = text;
}
