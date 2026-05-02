// title-editor-nodraft.js
// No-draft view: read-only title info + intrinsic-tag editor + "Enrich (draft)" button.
// Mounted when a queue title has no active draft_titles row.
// See spec/PROPOSAL_DRAFT_MODE.md §11.2 (no-draft state).

import { esc } from './utils.js';

/**
 * Show the no-draft pane for the given title.
 *
 * @param {object} detail    - UnsortedEditorService detail response
 * @param {object} state     - shared editor state (editorState from title-editor.js)
 * @param {Function} onEnrichSuccess - called with the new draft after populate succeeds
 * @param {Function} setStatus - (msg, cls) → void
 */
export function mountNoDraftView(detail, state, onEnrichSuccess, setStatus) {
  // Always query fresh — the node may have been replaced by a previous mount.
  let enrichBtn = document.getElementById('queue-enrich-btn');

  const isDup = !!(detail && detail.duplicate);
  if (enrichBtn) {
    enrichBtn.style.display = isDup ? 'none' : '';
    enrichBtn.disabled = false;
    enrichBtn.textContent = 'Enrich (draft)';
    enrichBtn.classList.remove('enriching');
  }

  if (enrichBtn && !isDup) {
    // Remove previous listener by cloning, then re-query the replacement.
    const fresh = enrichBtn.cloneNode(true);
    enrichBtn.replaceWith(fresh);
    enrichBtn = document.getElementById('queue-enrich-btn');

    enrichBtn.addEventListener('click', async () => {
      if (!detail) return;
      const titleId = detail.detail?.titleId ?? detail.titleId;
      if (!titleId) return;

      enrichBtn.disabled = true;
      enrichBtn.textContent = 'Enriching…';
      enrichBtn.classList.add('enriching');
      setStatus('Enriching — contacting javdb…', '');

      // Elapsed-time counter so the user knows something is happening.
      const start = Date.now();
      const timer = setInterval(() => {
        const s = Math.floor((Date.now() - start) / 1000);
        setStatus(`Enriching… ${s}s`, '');
      }, 1000);

      const resetBtn = () => {
        clearInterval(timer);
        enrichBtn.classList.remove('enriching');
        enrichBtn.disabled = false;
        enrichBtn.textContent = 'Enrich (draft)';
      };

      try {
        const res = await fetch(`/api/drafts/${titleId}/populate`, { method: 'POST' });
        clearInterval(timer);
        enrichBtn.classList.remove('enriching');
        if (res.status === 201) {
          setStatus('Draft created — loading…', 'success');
          onEnrichSuccess(titleId);
        } else if (res.status === 409) {
          setStatus('Draft already exists — reload to see it.', '');
          onEnrichSuccess(titleId); // reload draft view
        } else if (res.status === 422) {
          setStatus('No javdb match found for this title.', 'error');
          resetBtn();
        } else {
          setStatus('Enrich failed: HTTP ' + res.status, 'error');
          resetBtn();
        }
      } catch (err) {
        console.error('Enrich populate failed', err);
        setStatus('Enrich failed: ' + (err.message || err), 'error');
        resetBtn();
      }
    });
  }
}
