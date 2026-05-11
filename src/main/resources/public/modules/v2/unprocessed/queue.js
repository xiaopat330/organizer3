/* ─────────────────────────────────────────────────────────────────────
   unprocessed/queue.js — sidebar queue list renderer.

   Exported: mountQueue(rootEl, { onSelect, onCountsChange })

   Responsibilities:
     - Fetch GET /api/unsorted/titles  → queueRows
     - Fetch GET /api/drafts           → draftedTitleIds (Set)
     - Render rows with 3-state status marker (●/◐/○), code, folderName,
       DRAFT pill when applicable, actress summary
     - Selected row accent left-border + bg highlight
     - "Show complete" toggle (default: hide complete rows)
     - Empty state using shared .dis-empty pattern
     - Refresh on page visibilitychange (when tab returns to focus)
     - Reports pending count + bulk-enrich candidate count via onCountsChange
   ───────────────────────────────────────────────────────────────────── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

/**
 * Compute the 3-state status marker for a queue row.
 * Mirrors legacy title-editor.js statusMarker():
 *   complete → ● (green)
 *   partial  → ◐ (accent)  — has actress(es) or cover but not complete
 *   empty    → ○ (faint)
 */
function statusMarker(row) {
  if (row.complete)                              return { cls: 'un-status-complete', glyph: '●', title: 'Complete' };
  if (row.actressCount > 0 || row.hasCover)      return { cls: 'un-status-partial',  glyph: '◐', title: 'Partial' };
  return                                                { cls: 'un-status-empty',    glyph: '○', title: 'Empty' };
}

/**
 * Mount the queue into rootEl.
 *
 * @param {HTMLElement} rootEl       — container to render into
 * @param {object}      callbacks
 * @param {Function}    callbacks.onSelect        — called with titleId when a row is clicked
 * @param {Function}    callbacks.onCountsChange  — called with { pending, bulkCandidates, queueRows, showComplete }
 *                                                  where pending = !complete, bulkCandidates = visible && !complete
 * @returns {{ reload, setSelectedId }}
 */
export function mountQueue(rootEl, { onSelect, onCountsChange }) {
  // ── State ────────────────────────────────────────────────────────────
  let queueRows      = [];          // [{titleId, code, folderName, actressCount, hasCover, complete}]
  let draftedIds     = new Set();   // Set<titleId> from /api/drafts
  let showComplete   = false;
  let selectedId     = null;

  // ── Inner DOM ────────────────────────────────────────────────────────
  rootEl.innerHTML = `
    <div class="un-queue-header">
      <div class="un-queue-toggle">
        <label class="un-toggle-label">
          <input type="checkbox" id="un-show-complete" class="un-toggle-cb">
          Show complete
        </label>
      </div>
      <div class="un-queue-meta" id="un-queue-meta"></div>
    </div>
    <ul class="un-queue-list" id="un-queue-list"></ul>
  `;

  const toggleCb  = rootEl.querySelector('#un-show-complete');
  const listEl    = rootEl.querySelector('#un-queue-list');
  const metaEl    = rootEl.querySelector('#un-queue-meta');

  toggleCb.addEventListener('change', () => {
    showComplete = toggleCb.checked;
    render();
  });

  // ── Derived ──────────────────────────────────────────────────────────
  function visibleRows() {
    return showComplete ? queueRows : queueRows.filter(r => !r.complete);
  }

  // ── Render ───────────────────────────────────────────────────────────
  function render() {
    const visible  = visibleRows();
    const pending  = queueRows.filter(r => !r.complete).length;
    const complete = queueRows.filter(r => r.complete).length;
    const bulkCandidates = visible.filter(r => !r.complete).length;

    // KPI meta line
    metaEl.textContent = `${queueRows.length} eligible · ${complete} complete`;

    // Report counts up (include queueRows + showComplete so index can compute nextIdAfter)
    onCountsChange?.({ pending, bulkCandidates, queueRows, showComplete });

    // Render rows
    listEl.innerHTML = '';

    if (visible.length === 0) {
      const empty = document.createElement('li');
      empty.className = 'un-queue-empty';
      if (queueRows.length === 0) {
        empty.innerHTML = `
          <div class="dis-empty">
            <span class="un-empty-glyph">◌</span>
            No unprocessed titles found.
          </div>
        `;
      } else {
        // Has items but all are complete and toggle is off
        empty.innerHTML = `
          <div class="dis-empty">
            <span class="un-empty-glyph">◌</span>
            All titles complete. Toggle "Show complete" to review them.
          </div>
        `;
      }
      listEl.appendChild(empty);
      return;
    }

    for (const row of visible) {
      const marker   = statusMarker(row);
      const isDraft  = draftedIds.has(row.titleId);
      const draftPill = isDraft
        ? `<span class="un-draft-pill" title="Has active draft">DRAFT</span>`
        : '';
      const actressSummary = row.actressCount > 0
        ? `<span class="un-actress-count">${row.actressCount} actress${row.actressCount !== 1 ? 'es' : ''}</span>`
        : '';
      const completeClass = row.complete ? ' un-row-complete' : '';
      const selectedClass = row.titleId === selectedId ? ' un-row-selected' : '';

      const li = document.createElement('li');
      li.className = `un-queue-row${completeClass}${selectedClass}`;
      li.dataset.titleId = row.titleId;
      li.innerHTML = `
        <span class="un-status-marker ${esc(marker.cls)}" title="${esc(marker.title)}">${marker.glyph}</span>
        <span class="un-row-body">
          <span class="un-row-top">
            <span class="un-code">${esc(row.code)}</span>${draftPill}${actressSummary}
          </span>
          <span class="un-folder-name" title="${esc(row.folderName)}">${esc(row.folderName)}</span>
        </span>
      `;

      li.addEventListener('click', () => {
        selectedId = row.titleId;
        // Update selected visual without full re-render
        listEl.querySelectorAll('.un-queue-row').forEach(el => el.classList.remove('un-row-selected'));
        li.classList.add('un-row-selected');
        onSelect?.(row.titleId);
      });

      listEl.appendChild(li);
    }

    // Scroll selected into view
    listEl.querySelector('.un-row-selected')?.scrollIntoView({ block: 'nearest' });
  }

  // ── Data loading ─────────────────────────────────────────────────────
  async function load() {
    metaEl.textContent = 'Loading…';
    listEl.innerHTML = '<li class="un-queue-loading">Loading…</li>';

    try {
      const [titlesRes, draftsRes] = await Promise.all([
        fetch('/api/unsorted/titles'),
        fetch('/api/drafts'),
      ]);

      if (!titlesRes.ok) throw new Error(`GET /api/unsorted/titles → HTTP ${titlesRes.status}`);
      if (!draftsRes.ok) throw new Error(`GET /api/drafts → HTTP ${draftsRes.status}`);

      queueRows  = await titlesRes.json();
      const drafts = await draftsRes.json();
      draftedIds = new Set(drafts.map(d => d.titleId));

      render();
    } catch (err) {
      console.error('[unprocessed/queue] load error', err);
      metaEl.textContent = 'Failed to load.';
      listEl.innerHTML = `<li class="un-queue-error">Error loading queue: ${esc(String(err.message))}</li>`;
    }
  }

  // ── Visibility-based refresh ──────────────────────────────────────────
  // Refresh when the tab regains visibility (user switched away and back).
  // No polling interval — load on mount + visibilitychange.
  function onVisibilityChange() {
    if (!document.hidden) {
      load();
    }
  }
  document.addEventListener('visibilitychange', onVisibilityChange);

  // ── Public API ────────────────────────────────────────────────────────
  function reload() {
    return load();
  }

  function setSelectedId(id) {
    selectedId = id;
    listEl.querySelectorAll('.un-queue-row').forEach(el => {
      el.classList.toggle('un-row-selected', el.dataset.titleId == id);
    });
    listEl.querySelector('.un-row-selected')?.scrollIntoView({ block: 'nearest' });
  }

  function destroy() {
    document.removeEventListener('visibilitychange', onVisibilityChange);
  }

  // Initial load
  load();

  return { reload, setSelectedId, destroy };
}
