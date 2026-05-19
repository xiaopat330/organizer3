// v2/workflow/orphan.js — inline orphan-delete confirm panel for orphan_enriched rows.
//
// Primary UX: two inline buttons (Mark as moved + Confirm delete). The delete button
// uses a two-click confirmation pattern rather than a modal — no JS confirm() dialog —
// to match the spec while keeping the interaction on-row.

import { handleResolve, handleOrphanDelete } from './actions.js';

/**
 * Renders the orphan triage panel into the given container element.
 * The container replaces the standard candidate-thumbs cell content.
 *
 * @param {HTMLElement} container  the wf-row-candidates td (or a div inside it)
 * @param {object}      row        the workflow row object
 * @param {Function}    reload     called after a successful action
 */
export function renderOrphanPanel(container, row, reload) {
  container.innerHTML = '';

  const note = document.createElement('p');
  note.className = 'wf-orphan-note';
  note.textContent = 'Enrichment record exists but folder is gone.';
  container.appendChild(note);

  const btnRow = document.createElement('div');
  btnRow.className = 'wf-orphan-btns';
  container.appendChild(btnRow);

  // "Mark as moved" — non-destructive resolve
  const movedBtn = document.createElement('button');
  movedBtn.type = 'button';
  movedBtn.className = 'wf-orphan-moved-btn';
  movedBtn.textContent = 'Mark as moved';
  movedBtn.addEventListener('click', async () => {
    movedBtn.disabled = true;
    deleteBtn.disabled = true;
    try {
      await handleResolve(row.queueId, 'marked_moved', reload);
    } catch (err) {
      console.error('[workflow] mark-moved failed', err);
      alert(`Action failed: ${err.message}`);
      movedBtn.disabled = false;
      deleteBtn.disabled = false;
    }
  });
  btnRow.appendChild(movedBtn);

  // "Confirm delete" — two-click destructive pattern (no modal).
  // First click: change text to "Click again to confirm".
  // Second click: execute. Blur resets the button back to its initial state.
  const deleteBtn = document.createElement('button');
  deleteBtn.type = 'button';
  deleteBtn.className = 'wf-orphan-delete-btn';
  deleteBtn.textContent = 'Confirm delete';

  let pendingConfirm = false;

  const resetDelete = () => {
    pendingConfirm = false;
    deleteBtn.textContent = 'Confirm delete';
    deleteBtn.classList.remove('wf-orphan-delete-pending');
  };

  deleteBtn.addEventListener('blur', resetDelete);

  deleteBtn.addEventListener('click', async () => {
    if (!pendingConfirm) {
      pendingConfirm = true;
      deleteBtn.textContent = 'Click again to confirm';
      deleteBtn.classList.add('wf-orphan-delete-pending');
      return;
    }
    // Second click — commit.
    deleteBtn.disabled = true;
    movedBtn.disabled = true;
    deleteBtn.textContent = 'Deleting…';
    try {
      await handleOrphanDelete(row.queueId, reload);
    } catch (err) {
      console.error('[workflow] confirm-orphan-delete failed', err);
      alert(`Delete failed: ${err.message}`);
      deleteBtn.disabled = false;
      movedBtn.disabled = false;
      resetDelete();
    }
  });

  btnRow.appendChild(deleteBtn);
}
