/* ─────────────────────────────────────────────────────────────────────
   merge.js — mount entry point for v2-merge.html.
   Exported as mountMerge(rootEl).

   What's here vs legacy (utilities-merge-candidates.js):
     PORTED  — Fetch candidates from /api/tools/merge-candidates,
               headline with count, per-card Merge→A / Merge→B / Dismiss,
               decision PUT to /api/tools/merge-candidates/:id/decision
               (MERGE body includes winnerCode; DISMISS omits it),
               badge for confidence ('code-normalization' vs 'variant suffix'),
               Detect task (duplicates.detect_merge_candidates) trigger,
               Execute Merges task (duplicates.execute_merge) trigger,
               SSE subscription (PhaseStarted / PhaseProgress / TaskEnded),
               task-center pill integration (start / updateProgress / finish),
               task-running guard on Detect + Execute buttons,
               task-center subscribe to disable buttons reactively,
               filter-and-rerender on dismiss/merge (optimistic local update),
               empty state and loading state messages.
     DEFERRED — Active-run probe (reattach SSE after page reload). Not in
               legacy; would match volumes/index.js probeActiveRun() pattern
               but adds scope. Revisit when task-reattach is standardised.
   ───────────────────────────────────────────────────────────────────── */

import * as taskCenter from '../task-center.js';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

export async function mountMerge(rootEl) {
  rootEl.innerHTML = `
    <div class="mc-wb">
      <div class="mc-wb-head">
        <div class="mc-wb-title-row">
          <h1 class="wb-page-title">Merge</h1>
          <div class="mc-header-divider"></div>
          <div class="mc-actions">
            <button type="button" class="btn sm" id="mc-detect-btn">Detect</button>
            <button type="button" class="btn sm" id="mc-execute-btn">Execute Merges</button>
          </div>
        </div>
        <div class="dis-kpi-strip" id="mc-headline">Loading…</div>
        <div class="wb-page-subtitle">
          Title-code pairs that share a base code. Pick a winner for each pair or dismiss.
          Nothing is destroyed until you Execute.
        </div>
      </div>
      <div id="mc-list"    class="mc-list"></div>
    </div>
  `;

  const headlineEl = rootEl.querySelector('#mc-headline');
  const listEl     = rootEl.querySelector('#mc-list');
  const detectBtn  = rootEl.querySelector('#mc-detect-btn');
  const executeBtn = rootEl.querySelector('#mc-execute-btn');

  let candidates = [];

  // ── Button state sync ─────────────────────────────────────────────

  function syncButtonState() {
    const blocked = taskCenter.isRunning();
    detectBtn.disabled  = blocked;
    executeBtn.disabled = blocked;
  }

  const unsub = taskCenter.subscribe(syncButtonState);
  rootEl._mcUnsubscribe = unsub;

  // ── Task runner ───────────────────────────────────────────────────

  async function runTask(taskId, label) {
    if (taskCenter.isRunning()) return;
    let res;
    try {
      res = await fetch(`/api/utilities/tasks/${taskId}/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      });
    } catch (err) {
      alert('Failed to start task: ' + err.message);
      return;
    }
    const data = await res.json();
    if (!res.ok) {
      alert(data.error || 'Could not start task — another task may be running');
      return;
    }
    const runId = data.runId;
    taskCenter.start({ taskId, runId, label });
    subscribeToRun(runId);
  }

  function subscribeToRun(runId) {
    const src = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);

    src.onmessage = e => {
      let ev;
      try { ev = JSON.parse(e.data); } catch { return; }

      if (ev.type === 'PhaseStarted') {
        taskCenter.updateProgress({ phaseLabel: ev.label });
      } else if (ev.type === 'PhaseProgress' && ev.total > 0) {
        taskCenter.updateProgress({ overallPct: Math.round((ev.current / ev.total) * 100) });
      } else if (ev.type === 'TaskEnded') {
        taskCenter.finish({ status: ev.status, summary: ev.summary });
        src.close();
        reload();
      }
    };

    src.onerror = () => {
      src.close();
      taskCenter.finish({ status: 'failed', summary: 'Connection lost' });
    };
  }

  // ── Data loading ──────────────────────────────────────────────────

  async function reload() {
    headlineEl.textContent = 'Loading…';
    listEl.innerHTML = '<div class="mc-loading dis-empty">◌<br>Fetching candidates…</div>';
    try {
      const res = await fetch('/api/tools/merge-candidates');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      candidates = await res.json();
      render();
    } catch (err) {
      headlineEl.textContent = 'Failed to load candidates.';
      listEl.innerHTML = '';
      console.error('[merge] load error', err);
    }
  }

  // ── Render ────────────────────────────────────────────────────────

  function render() {
    const n = candidates.length;
    headlineEl.textContent =
      n === 0 ? 'No pending candidates'
      : n === 1 ? '1 pending · pick a winner or dismiss'
      : `${n} pending · pick a winner or dismiss`;

    if (n === 0) {
      listEl.innerHTML = '<div class="dis-empty">◌<br>All caught up — no pending decisions.</div>';
      return;
    }

    listEl.innerHTML = candidates.map(renderCard).join('');

    // Wire button events
    listEl.querySelectorAll('.mc-card').forEach(card => {
      const id    = Number(card.dataset.id);
      const codeA = card.dataset.codeA;
      const codeB = card.dataset.codeB;

      card.querySelector('.mc-dismiss-btn').addEventListener('click', () => decide(id, 'DISMISS', null));
      card.querySelector('.mc-merge-a-btn').addEventListener('click', () => decide(id, 'MERGE', codeA));
      card.querySelector('.mc-merge-b-btn').addEventListener('click', () => decide(id, 'MERGE', codeB));
    });
  }

  function renderCard(c) {
    const badgeClass  = c.confidence === 'code-normalization' ? 'mc-badge--norm' : 'mc-badge--variant';
    const badgeLabel  = c.confidence === 'code-normalization' ? 'code normalization' : 'variant suffix';

    return `
    <div class="mc-card" data-id="${c.id}" data-code-a="${esc(c.titleCodeA)}" data-code-b="${esc(c.titleCodeB)}">
      <div class="mc-pair">
        <span class="mc-code">${esc(c.titleCodeA)}</span>
        <span class="mc-pair-sep">↔</span>
        <span class="mc-code">${esc(c.titleCodeB)}</span>
      </div>
      <span class="mc-badge ${badgeClass}">${esc(badgeLabel)}</span>
      <div class="mc-btns">
        <button type="button" class="mc-merge-a-btn mc-btn-merge mc-btn-merge-primary" title="Keep ${esc(c.titleCodeA)}, delete ${esc(c.titleCodeB)}">
          Merge → ${esc(c.titleCodeA)}
        </button>
        <button type="button" class="mc-merge-b-btn mc-btn-merge mc-btn-merge-primary" title="Keep ${esc(c.titleCodeB)}, delete ${esc(c.titleCodeA)}">
          Merge → ${esc(c.titleCodeB)}
        </button>
        <button type="button" class="mc-dismiss-btn mc-btn-dismiss">Dismiss</button>
      </div>
    </div>`;
  }

  // ── Decision ──────────────────────────────────────────────────────

  async function decide(id, decision, winnerCode) {
    try {
      const body = decision === 'MERGE' ? { decision, winnerCode } : { decision };
      const res = await fetch(`/api/tools/merge-candidates/${id}/decision`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(await res.text());
      // Remove from local list and re-render
      candidates = candidates.filter(c => c.id !== id);
      render();
    } catch (err) {
      console.error('[merge] failed to save decision', id, err);
      alert('Failed to save decision: ' + err.message);
    }
  }

  // ── Wire toolbar buttons ──────────────────────────────────────────

  detectBtn.addEventListener('click', () => runTask('duplicates.detect_merge_candidates', 'Detect merge candidates'));
  executeBtn.addEventListener('click', () => runTask('duplicates.execute_merge', 'Execute merges'));

  // ── Initial load ──────────────────────────────────────────────────

  syncButtonState();
  await reload();
}
