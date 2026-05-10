// v2 Admin tab — Navigate-away guard.
//
// Mirrors legacy actress-detail-admin/nav-guard.js verbatim.
// When the user attempts to leave while staged changes exist, surfaces a
// confirm modal. On Discard → clears stages, resolves true. On Stay →
// resolves false. No staged edits → resolves true immediately.

import * as state from './state.js';

let beforeUnloadHandler = null;

export function confirmDiscardIfStaged() {
  return new Promise(resolve => {
    if (!state.hasStagedChanges()) {
      resolve(true);
      return;
    }

    const count = state.getTotalPendingCount();
    const backdrop = document.createElement('div');
    backdrop.className = 'admin-discard-backdrop';
    backdrop.innerHTML = `
      <div class="admin-discard-modal" role="dialog" aria-modal="true" aria-labelledby="admin-discard-title">
        <div class="admin-discard-header">
          <span id="admin-discard-title">Discard staged changes?</span>
        </div>
        <div class="admin-discard-body">
          You have <strong>${count}</strong> staged change${count === 1 ? '' : 's'} on this page.
          Leaving now will discard them.
        </div>
        <div class="admin-discard-footer">
          <button type="button" class="admin-discard-stay">Stay</button>
          <button type="button" class="admin-discard-confirm">Discard</button>
        </div>
      </div>
    `;
    document.body.appendChild(backdrop);

    const close = (result) => {
      document.removeEventListener('keydown', onKey);
      backdrop.remove();
      if (result) state.clearAllStages();
      resolve(result);
    };

    function onKey(e) {
      if (e.key === 'Escape')     { e.preventDefault(); close(false); }
      else if (e.key === 'Enter') { e.preventDefault(); close(false); } // default: Stay (safer)
    }
    document.addEventListener('keydown', onKey);

    backdrop.addEventListener('click', (e) => {
      if (e.target === backdrop) close(false);  // click on backdrop = Stay
    });
    backdrop.querySelector('.admin-discard-stay').addEventListener('click', () => close(false));
    backdrop.querySelector('.admin-discard-confirm').addEventListener('click', () => close(true));

    // Default focus to Stay so a stray Enter / Space doesn't blow away work.
    backdrop.querySelector('.admin-discard-stay').focus();
  });
}

export function installBeforeUnload() {
  if (beforeUnloadHandler) return;
  beforeUnloadHandler = (e) => {
    if (state.hasStagedChanges()) {
      e.preventDefault();
      e.returnValue = '';
      return '';
    }
  };
  window.addEventListener('beforeunload', beforeUnloadHandler);
}

export function uninstallBeforeUnload() {
  if (!beforeUnloadHandler) return;
  window.removeEventListener('beforeunload', beforeUnloadHandler);
  beforeUnloadHandler = null;
}
