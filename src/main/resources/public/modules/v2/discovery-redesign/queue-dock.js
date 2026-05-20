/* ─────────────────────────────────────────────────────────────────────
   discovery-redesign/queue-dock.js — bottom queue dock.

   Collapsed (32 px): summary ticker + expand button.
   Expanded (~280 px): full queue table with per-item actions (⇑ ↑ ↓ ⇓ ⏸/▶ ↺).

   Does not poll itself — receives items via updateItems(items[]).
   Notifies the poller via onExpandChange so items polling can start/stop.
   ───────────────────────────────────────────────────────────────────── */

import { esc } from '../../utils.js';
import { showCoverLightbox } from './pivots/shared.js';

// ── Failure meta (same as legacy queue.js) ────────────────────────────────

const QUEUE_FAIL_META = {
  ambiguous:               { label: 'ambiguous',           icon: '⚠', cls: 'dr-qi-failed-resolvable' },
  cast_anomaly:            { label: 'cast anomaly',        icon: '⚠', cls: 'dr-qi-failed-resolvable' },
  sentinel_actress:        { label: 'needs actress',       icon: '⚠', cls: 'dr-qi-failed-resolvable' },
  not_found:               { label: 'not on javdb',        icon: '⊘', cls: 'dr-qi-failed-deadend'    },
  no_match_in_filmography: { label: 'not in filmography',  icon: '⊘', cls: 'dr-qi-failed-deadend'    },
  title_not_in_db:         { label: 'orphaned job',        icon: '⊘', cls: 'dr-qi-failed-deadend'    },
  unknown_job_type:        { label: 'internal error',      icon: '⊘', cls: 'dr-qi-failed-deadend'    },
  fetch_failed:            { label: 'fetch failed',        icon: '↻', cls: 'dr-qi-failed'            },
  no_slug:                 { label: 'no slug',             icon: '↻', cls: 'dr-qi-failed'            },
  slug_conflict:           { label: 'slug conflict',       icon: '⚠', cls: 'dr-qi-failed-resolvable' },
};

function queueFailLabel(lastError) {
  const m = QUEUE_FAIL_META[lastError];
  return m ? `${m.icon} ${m.label}` : (lastError ? lastError.replace(/_/g, ' ') : 'failed');
}

function queueFailClass(lastError) {
  return QUEUE_FAIL_META[lastError]?.cls ?? 'dr-qi-failed';
}

function formatQueueAge(updatedAt) {
  if (!updatedAt) return '—';
  const ms  = Date.now() - new Date(updatedAt).getTime();
  const sec = Math.floor(ms / 1000);
  if (sec < 60)  return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60)  return `${min}m ago`;
  return `${Math.floor(min / 60)}h ago`;
}

const AVG_JOB_MS  = 3_500;
const ETA_WINDOW  = 8;

function formatEta(pausedUntil, queuePosition) {
  if (!queuePosition || queuePosition > ETA_WINDOW) return '—';
  const pauseMs = pausedUntil
    ? Math.max(0, new Date(pausedUntil).getTime() - Date.now())
    : 0;
  const eta    = new Date(Date.now() + pauseMs + (queuePosition - 1) * AVG_JOB_MS);
  const diffMs = eta.getTime() - Date.now();
  if (diffMs < 15_000) return '<span class="dr-qi-eta-soon">soon</span>';
  if (diffMs < 90_000) return `<span class="dr-qi-eta">~${Math.round(diffMs / 1000)}s</span>`;
  return `<span class="dr-qi-eta">${eta.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>`;
}

function renderItemActions(item) {
  const id = item.id;
  if (item.status === 'failed') {
    return `<span class="dr-qi-actions">` +
      `<button class="dr-qi-action-btn" data-item-id="${id}" data-action="requeue" title="Re-queue">↺</button>` +
      `</span>`;
  }
  if (item.status !== 'pending' && item.status !== 'paused') return '';
  const pauseBtn = item.status === 'paused'
    ? `<button class="dr-qi-action-btn" data-item-id="${id}" data-action="resume" title="Resume">▶</button>`
    : `<button class="dr-qi-action-btn" data-item-id="${id}" data-action="pause"  title="Pause">⏸</button>`;
  return `<span class="dr-qi-actions">` +
    `<button class="dr-qi-action-btn" data-item-id="${id}" data-action="top"     title="Move to top">⇑</button>` +
    `<button class="dr-qi-action-btn" data-item-id="${id}" data-action="promote" title="Move up">↑</button>` +
    `<button class="dr-qi-action-btn" data-item-id="${id}" data-action="demote"  title="Move down">↓</button>` +
    `<button class="dr-qi-action-btn" data-item-id="${id}" data-action="bottom"  title="Move to bottom">⇓</button>` +
    pauseBtn +
    `</span>`;
}

function renderItemRow(item, pausedUntil) {
  const isProfile  = item.jobType === 'fetch_actress_profile';
  const typeLabel  = isProfile ? 'profile' : 'title';
  const titleCode  = item.titleCode || '—';
  const age        = formatQueueAge(item.updatedAt);
  const statusClass = item.status === 'in_flight' ? 'dr-qi-inflight'
                    : item.status === 'failed'    ? queueFailClass(item.lastError)
                    : item.status === 'paused'    ? 'dr-qi-paused' : 'dr-qi-pending';
  const statusLabel = item.status === 'in_flight' ? 'in flight'
                    : item.status === 'failed'    ? queueFailLabel(item.lastError)
                    : item.status === 'paused'    ? '⏸ paused' : item.status;
  const etaCell   = item.status === 'pending' ? formatEta(pausedUntil, item.queuePosition) : '—';
  const actions   = renderItemActions(item);
  const codeCell  = (!isProfile && item.coverUrl)
    ? `<button class="dr-qi-cover-link" data-cover-url="${esc(item.coverUrl)}" data-code="${esc(titleCode)}">${esc(titleCode)}</button>`
    : esc(titleCode);
  const canReview  = item.status === 'failed' && item.reviewQueueId != null;
  const canAddSlug = item.status === 'failed' && item.lastError === 'no_slug';
  const statusCell = canReview
    ? `<button class="dr-qi-status ${statusClass} dr-qi-review-link" data-review-id="${item.reviewQueueId}" title="Open in Review Queue">${statusLabel}</button>`
    : canAddSlug
    ? `<button class="dr-qi-status ${statusClass} dr-qi-slug-link" data-actress-id="${item.actressId}" title="Assign slug">${statusLabel}</button>`
    : `<span class="dr-qi-status ${statusClass}" title="${esc(item.lastError || '')}">${statusLabel}</span>`;
  const prio = item.priority || 'NORMAL';
  const prioCell = prio !== 'NORMAL'
    ? `<span class="dr-qi-prio dr-qi-prio-${prio.toLowerCase()}">${prio}</span>`
    : `<span class="dr-qi-prio"></span>`;

  return `<tr>
    <td><button class="dr-qi-actress-link" data-actress-id="${item.actressId}">${esc(item.actressName)}</button></td>
    <td class="dr-qi-code">${codeCell}</td>
    <td>${typeLabel}</td>
    <td>${prioCell}</td>
    <td>${statusCell}</td>
    <td>${item.attempts}</td>
    <td class="dr-qi-eta-cell">${etaCell}</td>
    <td class="dr-qi-age">${age}</td>
    <td class="dr-qi-actions-cell">${actions}</td>
  </tr>`;
}

// ── Mount ─────────────────────────────────────────────────────────────────

/**
 * Mount the queue dock into containerEl.
 *
 * @param {HTMLElement} containerEl
 * @param {{
 *   expanded: boolean,
 *   onExpandChange: (expanded: boolean) => void,
 *   onNavigateToActress?: (actressId: number) => void,
 * }} opts
 * @returns {{
 *   setExpanded(v: boolean): void,
 *   updateItems(items: object[], queueStatus?: object): void,
 *   updateSummary(status: object): void,
 *   destroy(): void,
 * }}
 */
export function mountQueueDock(containerEl, { expanded, onExpandChange, onNavigateToActress }) {
  containerEl.innerHTML = `
    <div class="dr-queue-dock-ticker">
      <button class="dr-queue-dock-expand-btn" id="dr-queue-dock-toggle" type="button"
              aria-expanded="${expanded}" aria-controls="dr-queue-dock-body">
        <span id="dr-queue-dock-chevron">${expanded ? '▾' : '▸'}</span>
        Queue
      </button>
      <span class="dr-queue-dock-ticker-text" id="dr-queue-dock-ticker-text">
        — idle —
      </span>
    </div>
    <div class="dr-queue-dock-body" id="dr-queue-dock-body">
      <div class="dr-queue-empty-state" id="dr-queue-empty">Queue is empty.</div>
      <div class="dr-queue-table-wrap" id="dr-queue-table-wrap" style="display:none">
        <table class="dr-queue-table">
          <thead><tr>
            <th>Actress</th>
            <th>Code</th>
            <th>Type</th>
            <th>Prio</th>
            <th>Status</th>
            <th>Tries</th>
            <th>ETA</th>
            <th>Age</th>
            <th></th>
          </tr></thead>
          <tbody id="dr-queue-table-body"></tbody>
        </table>
      </div>
    </div>
  `;

  containerEl.dataset.expanded = String(expanded);

  const toggleBtn   = containerEl.querySelector('#dr-queue-dock-toggle');
  const chevronEl   = containerEl.querySelector('#dr-queue-dock-chevron');
  const tickerEl    = containerEl.querySelector('#dr-queue-dock-ticker-text');
  const emptyEl     = containerEl.querySelector('#dr-queue-empty');
  const tableWrap   = containerEl.querySelector('#dr-queue-table-wrap');
  const tableBody   = containerEl.querySelector('#dr-queue-table-body');

  let _lastStatus   = null;

  // ── Expand / collapse ─────────────────────────────────────────────

  function setExpanded(v) {
    containerEl.dataset.expanded = String(v);
    toggleBtn.setAttribute('aria-expanded', String(v));
    chevronEl.textContent = v ? '▾' : '▸';
  }

  function handleToggle() {
    const next = containerEl.dataset.expanded !== 'true';
    setExpanded(next);
    onExpandChange(next);
  }

  toggleBtn.addEventListener('click', handleToggle);

  // ── Summary ticker update ─────────────────────────────────────────

  /**
   * Update the ticker text from a QueueStatus object.
   * @param {object} status
   */
  function updateSummary(status) {
    _lastStatus = status;
    if (!status) return;
    const parts = [];
    if (status.inFlight > 0) parts.push(`${status.inFlight} in-flight`);
    if (status.pending  > 0) parts.push(`${status.pending} pending`);
    if (status.failed   > 0) parts.push(`${status.failed} failed`);
    if (status.paused)       parts.push('paused');
    tickerEl.textContent = parts.length > 0 ? parts.join(' · ') : '— idle —';
  }

  // ── Items table render ────────────────────────────────────────────

  /**
   * Render the queue items table.
   * @param {object[]} items
   * @param {object}   [queueStatus] — if provided, used for ETA calculation
   */
  function updateItems(items, queueStatus) {
    if (queueStatus) _lastStatus = queueStatus;
    const pausedUntil = _lastStatus?.rateLimitPausedUntil ?? null;

    if (!items || items.length === 0) {
      emptyEl.style.display   = '';
      tableWrap.style.display = 'none';
      return;
    }
    emptyEl.style.display   = 'none';
    tableWrap.style.display = '';
    tableBody.innerHTML = items.map(item => renderItemRow(item, pausedUntil)).join('');
  }

  // ── Event delegation on queue table ──────────────────────────────

  tableBody.addEventListener('click', async e => {
    // Cover lightbox.
    const coverBtn = e.target.closest('.dr-qi-cover-link[data-cover-url]');
    if (coverBtn) {
      showCoverLightbox(coverBtn.dataset.coverUrl, coverBtn.dataset.code || '');
      return;
    }

    // Navigate to actress (in the Actresses pivot).
    const actressBtn = e.target.closest('.dr-qi-actress-link[data-actress-id]');
    if (actressBtn) {
      onNavigateToActress?.(parseInt(actressBtn.dataset.actressId, 10));
      return;
    }

    // Review queue link.
    const reviewBtn = e.target.closest('.dr-qi-review-link[data-review-id]');
    if (reviewBtn) {
      document.dispatchEvent(new CustomEvent('navigate-to-review-item', {
        detail: { reviewQueueId: parseInt(reviewBtn.dataset.reviewId, 10) }
      }));
      return;
    }

    // Per-item actions.
    const actionBtn = e.target.closest('.dr-qi-action-btn[data-action]');
    if (actionBtn) {
      await handleItemAction(actionBtn.dataset.itemId, actionBtn.dataset.action);
    }
  });

  async function handleItemAction(itemId, action) {
    if (action === 'pause' || action === 'resume' || action === 'requeue') {
      await fetch(`/api/javdb/discovery/queue/items/${itemId}/${action}`, { method: 'POST' });
    } else {
      await fetch(`/api/javdb/discovery/queue/items/${itemId}/move`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action }),
      });
    }
    // Poller will pick this up within 5 s; no force-refresh needed here.
  }

  // ── Public API ────────────────────────────────────────────────────

  return {
    setExpanded,
    updateItems,
    updateSummary,
    destroy() {
      toggleBtn.removeEventListener('click', handleToggle);
    },
  };
}
