// Navigate-away guard for the Admin tab.
//
// Per §4.5 / C2b: when the user attempts to leave the current admin page
// while at least one card has staged edits, surface a confirm modal:
//
//   "You have N staged changes on this page.
//    Discard?"          [Stay] [Discard]
//
// On Discard → stages cleared, navigation proceeds (resolves true).
// On Stay   → navigation aborted (resolves false).
// On no staged edits → resolves true immediately, no modal.
//
// Public surface:
//   confirmDiscardIfStaged(): Promise<boolean>
//   installBeforeUnload()    — call on mountAdmin
//   uninstallBeforeUnload()  — call on unmountAdmin

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
      if (e.key === 'Escape')         { e.preventDefault(); close(false); }
      else if (e.key === 'Enter')     { e.preventDefault(); close(false); } // default action: Stay (safer)
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

// Backstop for browser-level navigation (tab close / reload / back-forward).
// We can't show our custom modal here — browsers gate beforeunload to a
// generic warning string. The most we get is "Leave site? Changes you made
// may not be saved."  Better than silent loss.
export function installBeforeUnload() {
  if (beforeUnloadHandler) return;
  beforeUnloadHandler = (e) => {
    if (state.hasStagedChanges()) {
      e.preventDefault();
      e.returnValue = '';  // Chrome / Firefox require this to trigger the prompt.
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
