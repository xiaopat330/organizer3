// title-editor-nodraft.js
// No-draft view: read-only title info + intrinsic-tag editor + "Enrich (draft)" button.
// Mounted when a queue title has no active draft_titles row.
// See spec/PROPOSAL_DRAFT_MODE.md §11.2 (no-draft state).

import { esc } from './utils.js';

// ── DOM refs (no-draft pane) ──────────────────────────────────────────────
const enrichBtn = document.getElementById('queue-enrich-btn');

/**
 * Show the no-draft pane for the given title.
 *
 * @param {object} detail    - UnsortedEditorService detail response
 * @param {object} state     - shared editor state (editorState from title-editor.js)
 * @param {Function} onEnrichSuccess - called with the new draft after populate succeeds
 * @param {Function} setStatus - (msg, cls) → void
 */
export function mountNoDraftView(detail, state, onEnrichSuccess, setStatus) {
  // Show/hide the Enrich button (not for duplicates — they can't be enriched meaningfully).
  const isDup = !!(detail && detail.duplicate);
  if (enrichBtn) {
    enrichBtn.style.display = isDup ? 'none' : '';
    enrichBtn.disabled = false;
  }

  // Wire enrich button for this title.
  if (enrichBtn) {
    // Remove previous listener by cloning.
    const fresh = enrichBtn.cloneNode(true);
    enrichBtn.replaceWith(fresh);
    const btn = document.getElementById('queue-enrich-btn');
    btn.addEventListener('click', async () => {
      if (!detail) return;
      const titleId = detail.detail?.id ?? detail.id;
      if (!titleId) return;
      btn.disabled = true;
      btn.textContent = 'Enriching…';
      setStatus('Starting enrichment…', '');
      try {
        const res = await fetch(`/api/drafts/${titleId}/populate`, { method: 'POST' });
        if (res.status === 201) {
          setStatus('Draft created — loading…', 'success');
          onEnrichSuccess(titleId);
        } else if (res.status === 409) {
          setStatus('Draft already exists — reload to see it.', '');
          onEnrichSuccess(titleId); // reload draft view
        } else if (res.status === 422) {
          const body = await res.json().catch(() => ({}));
          setStatus('No javdb match found for this title.', 'error');
          btn.disabled = false;
          btn.textContent = 'Enrich (draft)';
        } else {
          setStatus('Enrich failed: HTTP ' + res.status, 'error');
          btn.disabled = false;
          btn.textContent = 'Enrich (draft)';
        }
      } catch (err) {
        console.error('Enrich populate failed', err);
        setStatus('Enrich failed: ' + (err.message || err), 'error');
        btn.disabled = false;
        btn.textContent = 'Enrich (draft)';
      }
    });
  }
}
