// utilities-workflow/orphan.js — inline orphan-delete confirm panel for orphan_enriched rows.
// Forked from modules/v2/workflow/orphan.js; reskinned to .wf1-* classes.
// Two-click confirm (no modal): Mark as moved + Confirm delete.

import { handleResolve, handleOrphanDelete } from './actions.js';

export function renderOrphanPanel(container, row, reload) {
  container.innerHTML = '';

  const note = document.createElement('p');
  note.className = 'wf1-orphan-note';
  note.textContent = 'Enrichment record exists but folder is gone.';
  container.appendChild(note);

  const btnRow = document.createElement('div');
  btnRow.className = 'wf1-orphan-btns';
  container.appendChild(btnRow);

  // "Mark as moved" — non-destructive resolve
  const movedBtn = document.createElement('button');
  movedBtn.type = 'button';
  movedBtn.className = 'wf1-orphan-moved-btn';
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
  const deleteBtn = document.createElement('button');
  deleteBtn.type = 'button';
  deleteBtn.className = 'wf1-orphan-delete-btn';
  deleteBtn.textContent = 'Confirm delete';

  let pendingConfirm = false;

  const resetDelete = () => {
    pendingConfirm = false;
    deleteBtn.textContent = 'Confirm delete';
    deleteBtn.classList.remove('wf1-orphan-delete-pending');
  };

  deleteBtn.addEventListener('blur', resetDelete);

  deleteBtn.addEventListener('click', async () => {
    if (!pendingConfirm) {
      pendingConfirm = true;
      deleteBtn.textContent = 'Click again to confirm';
      deleteBtn.classList.add('wf1-orphan-delete-pending');
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
