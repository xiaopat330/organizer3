// volumes/organize.js — Organize pipeline: 6 steps (prep-fresh, normalize,
// restructure, fix-timestamps, sort-title, classify-actress) + "all" composite.
// State machine: idle → planning → plan-ready → executing → done.
// Mirrors utilities-volumes.js organize logic verbatim; endpoints unchanged.

import * as taskCenter from '../../task-center.js';
import { esc } from './cards.js';

export const ORGANIZE_ACTIONS = [
  {
    id: 'prep', label: 'Prep fresh',
    icon: '<path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/>',
    previewId: 'prep.preview', executeId: 'prep',
    structureTypes: ['queue'],
    desc: 'Scans the queue partition for raw video files and organizes each one into a title folder skeleton.',
    steps: [
      'Parses each filename to extract the product code (e.g. PRED-848-h265.mkv → PRED-848)',
      'Creates a title folder (PRED-848) with a subfolder matching the encoding (video/, h265/, 4K/)',
      'Moves the video into that subfolder',
      'Files that cannot be parsed are skipped and listed',
    ],
  },
  {
    id: 'normalize', label: 'Normalize',
    icon: '<polyline points="4 7 4 4 20 4 20 7"/><line x1="9" y1="20" x2="15" y2="20"/><line x1="12" y1="4" x2="12" y2="20"/>',
    previewId: 'organize.normalize.preview', executeId: 'organize.normalize',
    structureTypes: ['queue', 'conventional', 'exhibition', 'collections', 'sort_pool'],
    desc: 'Renames the cover image and video file in each title folder to the standard CODE.ext filename.',
    steps: [
      'Renames the cover image to CODE.jpg (e.g. mide123pl.jpg → MIDE-123.jpg)',
      'Renames the single video file to CODE.mkv, preserving quality suffixes like -h265 or _4K',
      'Strips site watermark prefixes from filenames before renaming (e.g. hhd800.com@)',
      'Skips titles with multiple covers or multiple video files — those need manual attention first',
    ],
  },
  {
    id: 'restructure', label: 'Restructure',
    icon: '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>',
    previewId: 'organize.restructure.preview', executeId: 'organize.restructure',
    structureTypes: ['queue', 'conventional', 'exhibition', 'collections', 'sort_pool'],
    desc: 'Moves video files from the title folder root into a proper named subfolder.',
    steps: [
      'Finds video files sitting directly in the title folder alongside the cover image',
      'Determines the correct subfolder name from quality markers in the filename (video/, h265/, 4K/)',
      'Moves the video into that subfolder',
    ],
  },
  {
    id: 'timestamps', label: 'Fix timestamps',
    icon: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    previewId: 'organize.timestamps.preview', executeId: 'organize.timestamps',
    structureTypes: ['conventional', 'exhibition', 'collections'],
    desc: "Corrects each title folder's creation and modification date to match the earliest file it contains.",
    steps: [
      'Walks every title in the curated area (stars/, actress folders, collections)',
      'Reads the creation and modification timestamps of all child files',
      'Sets the folder timestamp to the earliest value found across all children',
      'Skips folders that already have the correct timestamp',
    ],
  },
  {
    id: 'sort', label: 'Sort',
    icon: '<polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/>',
    previewId: 'organize.sort.preview', executeId: 'organize.sort',
    structureTypes: ['conventional'],
    desc: 'Files each title into the permanent library under stars/{actress}/, organized by primary actress.',
    steps: [
      'Looks up the primary actress for each title in the database',
      'Moves the title folder to its permanent home at stars/{actress-name}/',
      "Sets the folder's timestamp to the earliest date found among its contents",
      'Updates the database to reflect the new location',
      'Titles with no known actress are routed to attention/ for manual review',
    ],
  },
  {
    id: 'classify', label: 'Classify',
    icon: '<path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><line x1="7" y1="7" x2="7.01" y2="7"/>',
    previewId: 'organize.classify.preview', executeId: 'organize.classify',
    structureTypes: ['conventional'],
    desc: 'Updates actress tier ratings (SSS / SS / S / A / B) based on their current title count and portfolio.',
    steps: [
      'Identifies actresses whose titles were touched in recent organize runs',
      'Counts titles and evaluates portfolio scores for each',
      'Promotes or adjusts each actress\'s tier in the database',
    ],
  },
  {
    id: 'all', label: 'Organize all',
    icon: '<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>',
    previewId: 'organize.preview', executeId: 'organize.queue',
    structureTypes: ['conventional'],
    desc: 'Runs the full pipeline — Normalize, Restructure, Sort, and Classify — on every queued title in sequence.',
    steps: [
      'Normalize — renames covers and videos to CODE.ext',
      'Restructure — moves loose videos from the folder root into named subfolders',
      'Sort — files each title into stars/{actress}/ and corrects folder timestamps',
      'Classify — updates actress tier ratings for all affected actresses',
    ],
  },
];

function actionSVG(paths) {
  return `<svg class="vol-org-action-icon" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`;
}

// ── Plan/execute flow state ───────────────────────────────────────────────────
// { volumeId, action, state, planResult, execResult, progress, error, eventSource }
let organizeFlow = null;

export function getOrganizeFlow() { return organizeFlow; }
export function resetOrganizeFlow() { organizeFlow = null; }

// ── Main render entry ─────────────────────────────────────────────────────────

export function renderOrgSection(v, updateSection) {
  const volumeId = v.id;
  const isBlocked = taskCenter.isRunning();

  if (!organizeFlow) {
    const structureType = v.structureType || '';
    const visibleActions = ORGANIZE_ACTIONS.filter(a =>
      !a.structureTypes || a.structureTypes.includes(structureType));

    if (visibleActions.length === 0) {
      return {
        html: `<div class="vol-org-empty">No organize operations are available for <b>${esc(structureType || 'this type of')}</b> volume.</div>`,
        wire: () => {},
      };
    }

    const disabledAttr = isBlocked ? ' disabled' : '';
    const btns = visibleActions.map(a =>
      `<button type="button" class="vol-org-action-btn${a.id === 'all' ? ' vol-org-action-all' : ''}" data-action="${esc(a.id)}"${disabledAttr}>${actionSVG(a.icon)} ${esc(a.label)}</button>`
    ).join('');

    const queueLine = v.queueCount > 0
      ? `<div class="vol-org-queue-count">${v.queueCount} title${v.queueCount === 1 ? '' : 's'} in queue</div>`
      : '';
    const blockedNote = isBlocked
      ? '<div class="vol-org-blocked">Another utility task is running. Wait for it to finish.</div>'
      : '';

    return {
      html: `${queueLine}<div class="vol-org-actions">${btns}</div>${blockedNote}`,
      wire(el) {
        el.querySelectorAll('.vol-org-action-btn').forEach(btn => {
          btn.addEventListener('click', () => {
            const actionId = btn.getAttribute('data-action');
            const action = ORGANIZE_ACTIONS.find(a => a.id === actionId);
            if (action) beginOrgPreview(volumeId, action, updateSection);
          });
        });
      },
    };
  }

  const { action, state, planResult, execResult, progress, error } = organizeFlow;
  const descHTML = renderActionDescriptionHTML(action);

  if (state === 'planning') {
    return {
      html: `${descHTML}
        <div class="vol-org-flow-head">Planning…</div>
        <div class="vol-org-spinner"></div>`,
      wire: () => {},
    };
  }

  if (state === 'plan-ready') {
    let bodyHTML;
    if (action.id === 'prep') {
      bodyHTML = renderPrepPlanReadyHTML(planResult);
    } else if (action.id === 'timestamps') {
      bodyHTML = renderTimestampsPlanReadyHTML(planResult);
    } else {
      const allRows = (planResult?.titles || []).map(renderPlanRows).filter(r => r);
      const titleCount = allRows.length;
      bodyHTML = `
        <div class="vol-org-flow-head">Plan — ${titleCount} title${titleCount === 1 ? '' : 's'} with changes</div>
        ${planTableHTML(allRows.join(''))}
        <div class="vol-org-flow-actions">
          <button type="button" class="btn primary vol-org-execute-btn">Execute</button>
          <button type="button" class="btn vol-org-cancel-btn">Cancel</button>
        </div>`;
    }
    return {
      html: `${descHTML}${bodyHTML}`,
      wire(el) {
        el.querySelector('.vol-org-execute-btn')?.addEventListener('click', () => beginOrgExecute(volumeId, updateSection));
        el.querySelector('.vol-org-cancel-btn')?.addEventListener('click', () => {
          if (organizeFlow?.eventSource) organizeFlow.eventSource.close();
          organizeFlow = null;
          updateSection(volumeId);
        });
      },
    };
  }

  if (state === 'executing') {
    const cur = progress?.current ?? 0;
    const tot = progress?.total ?? 0;
    const pct = tot > 0 ? Math.floor(100 * cur / tot) : 0;
    const bar = tot > 0
      ? `<div class="vol-phase-bar"><div class="vol-phase-bar-fill" style="width:${pct}%"></div></div>`
      : `<div class="vol-phase-bar"><div class="vol-phase-bar-indet"></div></div>`;
    const progressText = tot > 0 ? `${cur} / ${tot}` : 'Working…';
    return {
      html: `${descHTML}
        <div class="vol-org-flow-head">Running…</div>
        <div class="vol-org-progress">${esc(progressText)}</div>
        ${bar}`,
      wire: () => {},
    };
  }

  if (state === 'done') {
    const result = execResult || planResult;
    const summaryHTML = action.id === 'prep'
      ? (result?.summary ? renderPrepSummaryHTML(result.summary) : '')
      : action.id === 'timestamps'
      ? (result?.summary ? renderTimestampsSummaryHTML(result.summary) : '')
      : (result?.summary ? renderOrgSummaryHTML(result.summary) : '');
    const errorHTML = error ? `<div class="vol-org-error">${esc(error)}</div>` : '';
    const statusLabel = error ? 'Failed' : 'Done';
    return {
      html: `${descHTML}
        <div class="vol-org-flow-head">${statusLabel}</div>
        ${errorHTML}
        ${summaryHTML}
        <div class="vol-org-flow-actions">
          <button type="button" class="btn primary vol-org-runagain-btn">Run again</button>
          <button type="button" class="btn vol-org-back-btn">Back</button>
        </div>`,
      wire(el) {
        el.querySelector('.vol-org-runagain-btn')?.addEventListener('click', () => {
          const act = organizeFlow?.action;
          if (!act) return;
          organizeFlow = null;
          beginOrgPreview(volumeId, act, updateSection);
        });
        el.querySelector('.vol-org-back-btn')?.addEventListener('click', () => {
          if (organizeFlow?.eventSource) organizeFlow.eventSource.close();
          organizeFlow = null;
          updateSection(volumeId);
        });
      },
    };
  }

  return { html: '', wire: () => {} };
}

// ── Flow start/execute ────────────────────────────────────────────────────────

async function beginOrgPreview(volumeId, action, updateSection) {
  if (taskCenter.isRunning()) {
    alert('Another utility task is already running.');
    return;
  }
  organizeFlow = { volumeId, action, state: 'planning', planResult: null, execResult: null, progress: null, error: null, eventSource: null };
  updateSection(volumeId);

  try {
    const res = await fetch(`/api/utilities/tasks/${encodeURIComponent(action.previewId)}/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      organizeFlow = null;
      updateSection(volumeId);
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    taskCenter.start({ taskId: action.previewId, runId, label: `Planning: ${action.label}` });

    const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
    organizeFlow.eventSource = es;

    es.addEventListener('phase.ended', e => {
      const ev = JSON.parse(e.data);
      if ((ev.phaseId === 'organize' || ev.phaseId === 'prep' || ev.phaseId === 'timestamps') && ev.summary) {
        try { organizeFlow.planResult = JSON.parse(ev.summary); } catch {}
      }
    });
    es.addEventListener('task.ended', e => {
      const ev = JSON.parse(e.data);
      es.close();
      organizeFlow.eventSource = null;
      taskCenter.finish({ status: ev.status, summary: '' });
      if (ev.status === 'ok' && organizeFlow.planResult) {
        organizeFlow.state = 'plan-ready';
      } else {
        organizeFlow.error = ev.status === 'ok' ? 'No plan data received' : (ev.summary || 'Preview failed');
        organizeFlow.state = 'done';
      }
      updateSection(volumeId);
    });
    es.onerror = () => {};
  } catch (err) {
    organizeFlow.error = err.message;
    organizeFlow.state = 'done';
    updateSection(volumeId);
  }
}

async function beginOrgExecute(volumeId, updateSection) {
  if (!organizeFlow) return;
  const action = organizeFlow.action;
  organizeFlow.state = 'executing';
  organizeFlow.progress = { current: 0, total: 0 };
  updateSection(volumeId);

  try {
    const res = await fetch(`/api/utilities/tasks/${encodeURIComponent(action.executeId)}/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ volumeId }),
    });
    if (res.status === 409) {
      const body = await res.json().catch(() => ({}));
      organizeFlow.state = 'plan-ready';
      updateSection(volumeId);
      alert(body.error || 'Another utility task is already running.');
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const { runId } = await res.json();
    taskCenter.start({ taskId: action.executeId, runId, label: `${action.label}…` });

    const es = new EventSource(`/api/utilities/runs/${encodeURIComponent(runId)}/events`);
    organizeFlow.eventSource = es;

    es.addEventListener('phase.progress', e => {
      const ev = JSON.parse(e.data);
      if (ev.phaseId === 'organize' || ev.phaseId === 'prep' || ev.phaseId === 'timestamps') {
        organizeFlow.progress = { current: ev.current, total: ev.total };
        if (ev.total > 0) taskCenter.updateProgress({ overallPct: Math.floor(100 * ev.current / ev.total) });
        updateSection(volumeId);
      }
    });
    es.addEventListener('phase.ended', e => {
      const ev = JSON.parse(e.data);
      if ((ev.phaseId === 'organize' || ev.phaseId === 'prep' || ev.phaseId === 'timestamps') && ev.summary) {
        try { organizeFlow.execResult = JSON.parse(ev.summary); } catch {}
      }
    });
    es.addEventListener('task.ended', e => {
      const ev = JSON.parse(e.data);
      es.close();
      organizeFlow.eventSource = null;
      taskCenter.finish({ status: ev.status, summary: '' });
      if (ev.status !== 'ok') organizeFlow.error = ev.summary || 'Execute failed';
      organizeFlow.state = 'done';
      updateSection(volumeId);
    });
    es.onerror = () => {};
  } catch (err) {
    organizeFlow.error = err.message;
    organizeFlow.state = 'done';
    updateSection(volumeId);
  }
}

// ── HTML renderers ────────────────────────────────────────────────────────────

function renderActionDescriptionHTML(action) {
  if (!action.desc) return '';
  const stepsHTML = (action.steps || []).map(s => `<li>${esc(s)}</li>`).join('');
  return `<div class="vol-org-action-desc">
    <div class="vol-org-action-desc-summary">${esc(action.desc)}</div>
    ${stepsHTML ? `<ol class="vol-org-action-desc-steps">${stepsHTML}</ol>` : ''}
  </div>`;
}

function planTableHTML(tbody) {
  return `<div class="vol-org-plan-wrap">
    <table class="vol-org-plan-table">
      <colgroup>
        <col style="width:22%">
        <col style="width:39%">
        <col style="width:39%">
      </colgroup>
      <thead><tr>
        <th class="vol-org-plan-th">Title</th>
        <th class="vol-org-plan-th">Before</th>
        <th class="vol-org-plan-th">After</th>
      </tr></thead>
      <tbody>${tbody}</tbody>
    </table>
  </div>`;
}

function renderPlanRows(t) {
  const code = t.titleCode || t.path?.split('/').pop() || '?';

  if (t.error) {
    return `<tr class="vol-org-plan-tr vol-org-plan-tr-error">
      <td class="vol-org-plan-td-title">${esc(code)}</td>
      <td class="vol-org-plan-td-err" colspan="2">${esc(t.error)}</td>
    </tr>`;
  }

  const ops = [];

  if (t.normalize?.planned?.length > 0) {
    for (const a of t.normalize.planned) {
      ops.push({ before: a.from?.split('/').pop() || a.from || '', after: a.to?.split('/').pop() || a.to || '' });
    }
  }

  if (t.restructure?.planned?.length > 0) {
    for (const a of t.restructure.planned) {
      const segs = (a.to || '').split('/').filter(Boolean);
      ops.push({ before: a.from?.split('/').pop() || a.from || '', after: segs.length >= 2 ? segs.slice(-2).join('/') : (a.to || '') });
    }
  }

  if (t.sort) {
    const s = t.sort;
    const fromSegs = (t.path || '').split('/').filter(Boolean);
    const before = fromSegs.length >= 2 ? fromSegs.slice(-2).join('/') : (t.path || '');
    if (s.outcome === 'WOULD_SORT') {
      const toSegs = (s.to || '').split('/').filter(Boolean);
      const after = toSegs.length >= 3 ? toSegs.slice(-3).join('/') : (s.to || '');
      ops.push({ before, after });
    } else if (s.outcome === 'WOULD_ROUTE_TO_ATTENTION') {
      ops.push({ before, after: `attention/ — ${s.reason || ''}` });
    }
  }

  if (ops.length === 0) return '';

  return ops.map((op, i) => {
    const titleCell = i === 0
      ? `<td class="vol-org-plan-td-title">${esc(code)}</td>`
      : `<td class="vol-org-plan-td-title vol-org-plan-td-cont"></td>`;
    return `<tr class="vol-org-plan-tr">
      ${titleCell}
      <td class="vol-org-plan-td-before">${esc(op.before)}</td>
      <td class="vol-org-plan-td-after">${esc(op.after)}</td>
    </tr>`;
  }).join('');
}

function renderPrepPlanReadyHTML(planResult) {
  const allPlanned = (planResult?.partitions || []).flatMap(p => p.planned || []);
  const allSkipped = (planResult?.partitions || []).flatMap(p => p.skipped || []);
  const planTrs = allPlanned.map(p => {
    const before = (p.sourcePath || '').split('/').pop();
    const toSegs = (p.targetVideoPath || '').split('/').filter(Boolean);
    const after  = toSegs.length >= 3 ? toSegs.slice(-3).join('/') : (p.targetVideoPath || '');
    return `<tr class="vol-org-plan-tr">
      <td class="vol-org-plan-td-title">${esc(p.code || '?')}</td>
      <td class="vol-org-plan-td-before">${esc(before)}</td>
      <td class="vol-org-plan-td-after">${esc(after)}</td>
    </tr>`;
  }).join('');
  const skipTrs = allSkipped.map(s =>
    `<tr class="vol-org-plan-tr vol-org-plan-tr-skipped">
      <td class="vol-org-plan-td-title">—</td>
      <td class="vol-org-plan-td-before">${esc(s.filename || '')}</td>
      <td class="vol-org-plan-td-after vol-org-plan-td-skip">${esc(s.reason || '')}</td>
    </tr>`
  ).join('');
  const count = allPlanned.length;
  const skipNote = allSkipped.length > 0 ? ` (${allSkipped.length} skipped)` : '';
  return `<div class="vol-org-flow-head">Plan — ${count} file${count === 1 ? '' : 's'} to move${skipNote}</div>
    ${planTableHTML(planTrs + skipTrs)}
    <div class="vol-org-flow-actions">
      <button type="button" class="btn primary vol-org-execute-btn">Execute</button>
      <button type="button" class="btn vol-org-cancel-btn">Cancel</button>
    </div>`;
}

function renderTimestampsPlanReadyHTML(planResult) {
  const toFix = (planResult?.titles || []).filter(t => t.needsChange);
  const tbody = toFix.map(t =>
    `<tr class="vol-org-plan-tr">
      <td class="vol-org-plan-td-title">${esc(t.titleCode || '?')}</td>
      <td class="vol-org-plan-td-before">${esc(fmtDate(t.currentTimestamp))}</td>
      <td class="vol-org-plan-td-after">${esc(fmtDate(t.targetTimestamp))}</td>
    </tr>`
  ).join('');
  const count = toFix.length;
  const total = planResult?.summary?.checked ?? 0;
  const alreadyOk = total - count;
  const skipNote = alreadyOk > 0 ? ` (${alreadyOk} already correct)` : '';
  return `<div class="vol-org-flow-head">Plan — ${count} folder${count === 1 ? '' : 's'} to correct${skipNote}</div>
    ${planTableHTML(tbody)}
    <div class="vol-org-flow-actions">
      <button type="button" class="btn primary vol-org-execute-btn">Execute</button>
      <button type="button" class="btn vol-org-cancel-btn">Cancel</button>
    </div>`;
}

function fmtDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString('en-US', {
      year: 'numeric', month: 'short', day: 'numeric',
      hour: 'numeric', minute: '2-digit', hour12: true,
    });
  } catch {
    return iso.slice(0, 16).replace('T', ' ');
  }
}

function renderOrgSummaryHTML(s) {
  const rows = [];
  if (s.normalizeSuccesses   > 0) rows.push(['Renamed',             s.normalizeSuccesses]);
  if (s.restructureSuccesses > 0) rows.push(['Restructured',        s.restructureSuccesses]);
  if (s.sortedToStars        > 0) rows.push(['Filed to stars',      s.sortedToStars]);
  if (s.sortedToAttention    > 0) rows.push(['Routed to attention', s.sortedToAttention]);
  if (s.sortsSkipped         > 0) rows.push(['Already in place',    s.sortsSkipped]);
  if (s.actressesPromoted    > 0) rows.push(['Actresses promoted',  s.actressesPromoted]);
  if (s.titlesWithErrors     > 0) rows.push(['Errors',              s.titlesWithErrors]);
  if (rows.length === 0)          rows.push(['Processed',           s.titlesProcessed]);
  return summaryHTML(rows);
}

function renderPrepSummaryHTML(s) {
  const rows = [];
  if (s.moved   > 0) rows.push(['Moved',   s.moved]);
  if (s.skipped > 0) rows.push(['Skipped', s.skipped]);
  if (s.failed  > 0) rows.push(['Failed',  s.failed]);
  if (rows.length === 0) rows.push(['Total videos', s.totalVideos || 0]);
  return summaryHTML(rows);
}

function renderTimestampsSummaryHTML(s) {
  const rows = [];
  if (s.changed > 0) rows.push(['Corrected',      s.changed]);
  if (s.skipped > 0) rows.push(['Already correct', s.skipped]);
  if (s.failed  > 0) rows.push(['Failed',          s.failed]);
  if (rows.length === 0) rows.push(['Checked', s.checked || 0]);
  return summaryHTML(rows);
}

function summaryHTML(rows) {
  return `<div class="vol-org-summary">
    ${rows.map(([k, n]) =>
      `<div class="vol-org-summary-row">
        <span class="vol-org-summary-key">${esc(k)}</span>
        <span class="vol-org-summary-val">${n}</span>
      </div>`
    ).join('')}
  </div>`;
}
