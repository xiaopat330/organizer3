// Queue subtab: items table + per-item actions + ETA computation.
// Shared queue STATUS (queue header banner, badge) lives in index.js since
// it's read by all top-level subtabs. This module owns only the queue *table*.

import { esc } from '../utils.js';
import { showJdCoverModal } from './shared.js';

// Average time between job completions (sleep + network). Conservative estimate used for ETAs.
const AVG_JOB_MS = 3_500;
const ETA_WINDOW = 8;

// Failure reason metadata — label, icon prefix, and CSS class for the queue status cell.
// Buckets: resolvable (amber ⚠), dead-end (slate ⊘), transient/fixable (red ↻).
export const QUEUE_FAIL_META = {
  ambiguous:               { label: 'ambiguous',           icon: '⚠', cls: 'jd-qi-failed-resolvable' },
  cast_anomaly:            { label: 'cast anomaly',        icon: '⚠', cls: 'jd-qi-failed-resolvable' },
  sentinel_actress:        { label: 'needs actress',       icon: '⚠', cls: 'jd-qi-failed-resolvable' },
  not_found:               { label: 'not on javdb',        icon: '⊘', cls: 'jd-qi-failed-deadend'    },
  no_match_in_filmography: { label: 'not in filmography',  icon: '⊘', cls: 'jd-qi-failed-deadend'    },
  title_not_in_db:         { label: 'orphaned job',        icon: '⊘', cls: 'jd-qi-failed-deadend'    },
  unknown_job_type:        { label: 'internal error',      icon: '⊘', cls: 'jd-qi-failed-deadend'    },
  fetch_failed:            { label: 'fetch failed',        icon: '↻', cls: 'jd-qi-failed'            },
  no_slug:                 { label: 'no slug',             icon: '↻', cls: 'jd-qi-failed'            },
};

export function queueFailLabel(lastError) {
  const m = QUEUE_FAIL_META[lastError];
  if (m) return `${m.icon} ${m.label}`;
  return lastError ? lastError.replace(/_/g, ' ') : 'failed';
}

export function queueFailClass(lastError) {
  return QUEUE_FAIL_META[lastError]?.cls ?? 'jd-qi-failed';
}

function formatQueueAge(updatedAt) {
  if (!updatedAt) return '—';
  const ms = Date.now() - new Date(updatedAt).getTime();
  const sec = Math.floor(ms / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  return `${Math.floor(min / 60)}h ago`;
}

function computeEta(state, queuePosition) {
  if (!queuePosition) return null;
  const pauseRemainingMs = state.rateLimitPausedUntil
    ? Math.max(0, new Date(state.rateLimitPausedUntil).getTime() - Date.now())
    : 0;
  return new Date(Date.now() + pauseRemainingMs + (queuePosition - 1) * AVG_JOB_MS);
}

function formatEta(state, queuePosition) {
  if (!queuePosition || queuePosition > ETA_WINDOW) return '—';
  const eta = computeEta(state, queuePosition);
  const diffMs = eta.getTime() - Date.now();
  if (diffMs < 15_000) return '<span class="jd-qi-eta-soon">soon</span>';
  if (diffMs < 90_000) return `<span class="jd-qi-eta">~${Math.round(diffMs / 1000)}s</span>`;
  return `<span class="jd-qi-eta">${eta.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>`;
}

function renderQueueItemActions(item) {
  const id = item.id;
  if (item.status === 'failed') {
    return `<span class="jd-qi-actions">` +
      `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="requeue" title="Re-queue">↺</button>` +
      `</span>`;
  }
  if (item.status !== 'pending' && item.status !== 'paused') return '';
  const pauseBtn = item.status === 'paused'
    ? `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="resume" title="Resume">▶</button>`
    : `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="pause" title="Pause">⏸</button>`;
  return `<span class="jd-qi-actions">` +
    `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="top"     title="Move to top">⇑</button>` +
    `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="promote" title="Move up">↑</button>` +
    `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="demote"  title="Move down">↓</button>` +
    `<button class="jd-qi-action-btn" data-item-id="${id}" data-action="bottom"  title="Move to bottom">⇓</button>` +
    pauseBtn +
    `</span>`;
}

/**
 * Initializes the queue subtab. Returns an API used by index.js for polling
 * and for navigation hooks (clicking an actress link in the queue jumps to enrich).
 */
export function initQueue(state, hooks) {
  const queueEmpty     = document.getElementById('jd-queue-empty');
  const queueTableWrap = document.getElementById('jd-queue-table-wrap');
  const queueTableBody = document.getElementById('jd-queue-table-body');

  let queueItemsPollTimer = null;

  function renderQueueItems(items) {
    if (items.length === 0) {
      queueEmpty.style.display = '';
      queueTableWrap.style.display = 'none';
      return;
    }
    queueEmpty.style.display = 'none';
    queueTableWrap.style.display = '';

    queueTableBody.innerHTML = items.map(item => {
      const isProfile = item.jobType === 'fetch_actress_profile';
      const typeLabel = isProfile ? 'profile' : 'title';
      const titleCell = item.titleCode || '—';
      const age = formatQueueAge(item.updatedAt);
      const statusClass = item.status === 'in_flight' ? 'jd-qi-inflight'
                        : item.status === 'failed'    ? queueFailClass(item.lastError)
                        : item.status === 'paused'    ? 'jd-qi-paused' : 'jd-qi-pending';
      const statusLabel = item.status === 'in_flight' ? 'in flight'
                        : item.status === 'failed'    ? queueFailLabel(item.lastError)
                        : item.status === 'paused'    ? '⏸ paused' : item.status;
      const etaCell = item.status === 'pending' ? formatEta(state, item.queuePosition) : '—';
      const actions = renderQueueItemActions(item);
      const codeCell = (!isProfile && item.coverUrl)
        ? `<button class="jd-qi-cover-link" data-cover-url="${esc(item.coverUrl)}" data-code="${esc(titleCell)}">${esc(titleCell)}</button>`
        : esc(titleCell);
      const canReview  = item.status === 'failed' && item.reviewQueueId != null;
      const canAddSlug = item.status === 'failed' && item.lastError === 'no_slug';
      const statusCell = canReview
        ? `<button class="jd-qi-status ${statusClass} jd-qi-review-link" data-review-id="${item.reviewQueueId}" title="Click to review in Review Queue">${statusLabel}</button>`
        : canAddSlug
        ? `<button class="jd-qi-status ${statusClass} jd-qi-slug-link" data-actress-id="${item.actressId}" title="Click to assign slug in Discovery">${statusLabel}</button>`
        : `<span class="jd-qi-status ${statusClass}" title="${esc(item.lastError || '')}">${statusLabel}</span>`;
      const prio = item.priority || 'NORMAL';
      const prioCell = prio !== 'NORMAL'
        ? `<span class="jd-qi-prio prio-${prio.toLowerCase()}">${prio}</span>`
        : `<span class="jd-qi-prio prio-normal"></span>`;
      return `<tr>
        <td><button class="jd-qi-actress-link" data-actress-id="${item.actressId}">${esc(item.actressName)}</button></td>
        <td class="jd-qi-code">${codeCell}</td>
        <td>${typeLabel}</td>
        <td>${prioCell}</td>
        <td>${statusCell}</td>
        <td>${item.attempts}</td>
        <td class="jd-qi-eta-cell">${etaCell}</td>
        <td class="jd-qi-age">${age}</td>
        <td class="jd-qi-actions-cell">${actions}</td>
      </tr>`;
    }).join('');
  }

  async function loadQueueItems() {
    try {
      const res = await fetch('/api/javdb/discovery/queue/items');
      const items = await res.json();
      renderQueueItems(items);
    } catch { /* ignore */ }
  }

  function startQueueItemsPoll() {
    stopQueueItemsPoll();
    queueItemsPollTimer = setInterval(loadQueueItems, 5_000);
  }

  function stopQueueItemsPoll() {
    if (queueItemsPollTimer !== null) {
      clearInterval(queueItemsPollTimer);
      queueItemsPollTimer = null;
    }
  }

  async function handleQueueItemAction(itemId, action) {
    if (action === 'pause' || action === 'resume' || action === 'requeue') {
      await fetch(`/api/javdb/discovery/queue/items/${itemId}/${action}`, { method: 'POST' });
    } else {
      await fetch(`/api/javdb/discovery/queue/items/${itemId}/move`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action }),
      });
    }
    await loadQueueItems();
  }

  // Event delegation on the queue table body.
  queueTableBody.addEventListener('click', async e => {
    const coverBtn = e.target.closest('.jd-qi-cover-link[data-cover-url]');
    if (coverBtn) {
      showJdCoverModal(coverBtn.dataset.coverUrl, coverBtn.dataset.code || '');
      return;
    }

    const reviewBtn = e.target.closest('.jd-qi-review-link[data-review-id]');
    if (reviewBtn) {
      document.dispatchEvent(new CustomEvent('navigate-to-review-item', {
        detail: { reviewQueueId: parseInt(reviewBtn.dataset.reviewId, 10) }
      }));
      return;
    }

    const slugBtn = e.target.closest('.jd-qi-slug-link[data-actress-id]');
    if (slugBtn) {
      document.dispatchEvent(new CustomEvent('navigate-to-discovery-actress-profile', {
        detail: { actressId: parseInt(slugBtn.dataset.actressId, 10) }
      }));
      return;
    }

    const actressBtn = e.target.closest('.jd-qi-actress-link');
    if (actressBtn) {
      const actressId = parseInt(actressBtn.dataset.actressId, 10);
      await hooks.navigateToActress(actressId);
      return;
    }

    const actionBtn = e.target.closest('.jd-qi-action-btn');
    if (actionBtn) {
      const itemId = actionBtn.dataset.itemId;
      const action = actionBtn.dataset.action;
      await handleQueueItemAction(itemId, action);
    }
  });

  return {
    loadQueueItems,
    startQueueItemsPoll,
    stopQueueItemsPoll,
  };
}
