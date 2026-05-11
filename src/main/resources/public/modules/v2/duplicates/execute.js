/* ─────────────────────────────────────────────────────────────────────
   duplicates/execute.js — fire execute-trash task, SSE progress, reconcile.
   Mirrors legacy utilities-duplicate-triage.js execute + subscribeToRun.
   ───────────────────────────────────────────────────────────────────── */

import * as taskCenter from '../../task-center.js';

// loadAll is injected so execute can trigger a full reload after the run.
let _loadAll = null;
let _showBanner = null;

export function initExecute({ loadAll, showBanner }) {
  _loadAll    = loadAll;
  _showBanner = showBanner;
}

export async function runExecuteTask(actressKey) {
  if (taskCenter.isRunning()) return;
  const body = actressKey ? { actressKey } : {};
  let res;
  try {
    res = await fetch('/api/utilities/tasks/duplicates.execute_trash/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch (err) {
    alert('Failed to start execute task: ' + err.message);
    return;
  }
  const data = await res.json();
  if (!res.ok) {
    alert(data.error || 'Could not start task — another task may be running');
    return;
  }
  const runId = data.runId;
  const label = actressKey ? `Execute trash (${actressKey})` : 'Execute all trash';
  taskCenter.start({ taskId: 'duplicates.execute_trash', runId, label });
  _subscribeToRun(runId);
}

function _subscribeToRun(runId) {
  const src = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
  const failedPhases = [];

  src.onmessage = e => {
    let ev;
    try { ev = JSON.parse(e.data); } catch { return; }

    if (ev.type === 'PhaseStarted') {
      taskCenter.updateProgress({ phaseLabel: ev.label });
    } else if (ev.type === 'PhaseProgress' && ev.total > 0) {
      taskCenter.updateProgress({ overallPct: Math.round((ev.current / ev.total) * 100) });
    } else if (ev.type === 'PhaseEnded' && ev.status !== 'ok') {
      failedPhases.push(ev.summary || ev.phaseId);
    } else if (ev.type === 'TaskEnded') {
      taskCenter.finish({ status: ev.status, summary: ev.summary });
      src.close();
      _reconcileAfterExecute(ev.status, failedPhases);
    }
  };

  src.onerror = () => {
    src.close();
    taskCenter.finish({ status: 'failed', summary: 'Connection lost' });
    _reconcileAfterExecute('failed', []);
  };
}

async function _reconcileAfterExecute(status, failedPhases) {
  if (_loadAll) await _loadAll();
  if (status !== 'ok' && failedPhases.length > 0 && _showBanner) {
    _showBanner('Some actions failed during execute. Check the task log for details.');
  }
}
