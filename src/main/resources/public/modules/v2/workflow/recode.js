// v2/workflow/recode.js — inline recode dry-run preview for recode_candidate rows.
//
// Two-step flow (matching the legacy enrichment surface):
//   1. User clicks "Recode" — triggers dry-run to /api/utilities/title/{orphanId}/recode
//      with dryRun:true. Renders a list of planned folder renames inline.
//   2. User clicks "Commit" — sends dryRun:false, then resolves the queue row.
//
// orphanId and newCode come from row.detail (JSON string with orphan_id + new_folder_code).

import { esc } from './utils.js';
import { recodePreview, recodeCommit, handleResolve } from './actions.js';

/**
 * Renders the recode triage panel into the given container element.
 * The container replaces the standard candidate-thumbs cell content.
 *
 * @param {HTMLElement} container  the wf-row-candidates td (or a div inside it)
 * @param {object}      row        the workflow row object (needs row.queueId, row.detail)
 * @param {Function}    reload     called after a successful action
 */
export function renderRecodePanel(container, row, reload) {
  container.innerHTML = '';

  let detail = null;
  try { detail = row.detail ? JSON.parse(row.detail) : null; } catch {}

  const orphanId  = detail ? detail.orphan_id        : null;
  const newCode   = detail ? detail.new_folder_code  : null;
  const orphanCode = detail ? (detail.orphan_code || '') : '';
  const matchType  = detail ? (detail.match_type  || '') : '';

  if (!orphanId || !newCode) {
    container.innerHTML = `<span class="wf-recode-note">Missing recode detail data.</span>`;
    return;
  }

  // Summary line.
  const summary = document.createElement('div');
  summary.className = 'wf-recode-summary';
  summary.innerHTML = `<b>${esc(orphanCode)}</b> → <b>${esc(newCode)}</b>`;
  if (matchType) {
    const badge = document.createElement('span');
    badge.className = 'wf-recode-match-type';
    badge.textContent = matchType;
    summary.appendChild(badge);
  }
  container.appendChild(summary);

  // Button row: Recode (opens dry-run preview) + Dismiss.
  const btnRow = document.createElement('div');
  btnRow.className = 'wf-recode-btn-row';
  container.appendChild(btnRow);

  const recodeBtn = document.createElement('button');
  recodeBtn.type = 'button';
  recodeBtn.className = 'wf-recode-btn';
  recodeBtn.textContent = `Recode to ${newCode}`;
  btnRow.appendChild(recodeBtn);

  const dismissBtn = document.createElement('button');
  dismissBtn.type = 'button';
  dismissBtn.className = 'wf-recode-dismiss-btn';
  dismissBtn.textContent = 'Dismiss';
  dismissBtn.addEventListener('click', async () => {
    dismissBtn.disabled = true;
    recodeBtn.disabled = true;
    try {
      await handleResolve(row.queueId, 'dismissed', reload);
    } catch (err) {
      console.error('[workflow] recode dismiss failed', err);
      alert(`Dismiss failed: ${err.message}`);
      dismissBtn.disabled = false;
      recodeBtn.disabled = false;
    }
  });
  btnRow.appendChild(dismissBtn);

  // Preview area (initially empty; populated on recode-btn click).
  const previewArea = document.createElement('div');
  previewArea.className = 'wf-recode-preview';
  previewArea.style.display = 'none';
  container.appendChild(previewArea);

  recodeBtn.addEventListener('click', () =>
    startPreview(recodeBtn, dismissBtn, previewArea, orphanId, newCode, row.queueId, reload)
  );
}

async function startPreview(recodeBtn, dismissBtn, previewArea, orphanId, newCode, queueId, reload) {
  // Toggle: if preview is already showing, close it.
  if (previewArea.style.display !== 'none') {
    previewArea.style.display = 'none';
    previewArea.innerHTML = '';
    recodeBtn.textContent = `Recode to ${newCode}`;
    return;
  }

  recodeBtn.disabled = true;
  dismissBtn.disabled = true;
  recodeBtn.textContent = 'Loading preview…';
  previewArea.innerHTML = '';
  previewArea.style.display = '';

  try {
    const data = await recodePreview(orphanId, newCode);
    renderPreview(previewArea, data, orphanId, newCode, queueId, recodeBtn, dismissBtn, reload);
    recodeBtn.disabled = false;
    dismissBtn.disabled = false;
    recodeBtn.textContent = `Recode to ${newCode}`;
  } catch (err) {
    console.error('[workflow] recode preview failed', err);
    previewArea.innerHTML = `<span class="wf-recode-error">Preview failed: ${esc(err.message)}</span>`;
    recodeBtn.disabled = false;
    dismissBtn.disabled = false;
    recodeBtn.textContent = `Recode to ${newCode}`;
  }
}

function renderPreview(previewArea, dryRunData, orphanId, newCode, queueId, recodeBtn, dismissBtn, reload) {
  previewArea.innerHTML = '';

  const locations = dryRunData.locations || [];

  const pathsEl = document.createElement('div');
  pathsEl.className = 'wf-recode-paths';
  if (locations.length === 0) {
    pathsEl.innerHTML = '<span class="wf-recode-no-paths">No location paths to rename.</span>';
  } else {
    locations.forEach(l => {
      const oldBase = l.oldPath.split(/[\\/]/).pop();
      const newBase = l.newPath.split(/[\\/]/).pop();
      const pathRow = document.createElement('div');
      pathRow.className = 'wf-recode-path-row';
      pathRow.innerHTML = `<span class="wf-recode-old" title="${esc(l.oldPath)}">${esc(oldBase)}</span>
        <span class="wf-recode-arrow">→</span>
        <span class="wf-recode-new" title="${esc(l.newPath)}">${esc(newBase)}</span>`;
      pathsEl.appendChild(pathRow);
    });
  }
  previewArea.appendChild(pathsEl);

  const commitBtnRow = document.createElement('div');
  commitBtnRow.className = 'wf-recode-commit-row';

  const commitBtn = document.createElement('button');
  commitBtn.type = 'button';
  commitBtn.className = 'wf-recode-commit-btn';
  commitBtn.textContent = 'Commit';

  const cancelBtn = document.createElement('button');
  cancelBtn.type = 'button';
  cancelBtn.className = 'wf-recode-cancel-btn';
  cancelBtn.textContent = 'Cancel';
  cancelBtn.addEventListener('click', () => {
    previewArea.style.display = 'none';
    previewArea.innerHTML = '';
  });

  const errorEl = document.createElement('span');
  errorEl.className = 'wf-recode-error';
  errorEl.style.display = 'none';

  commitBtnRow.appendChild(commitBtn);
  commitBtnRow.appendChild(cancelBtn);
  commitBtnRow.appendChild(errorEl);
  previewArea.appendChild(commitBtnRow);

  commitBtn.addEventListener('click', async () => {
    commitBtn.disabled = true;
    cancelBtn.disabled = true;
    recodeBtn.disabled = true;
    dismissBtn.disabled = true;
    commitBtn.textContent = 'Committing…';
    errorEl.style.display = 'none';
    try {
      await recodeCommit(orphanId, newCode, queueId);
      await reload();
    } catch (err) {
      console.error('[workflow] recode commit failed', err);
      errorEl.textContent = 'Commit failed: ' + err.message;
      errorEl.style.display = '';
      commitBtn.disabled = false;
      cancelBtn.disabled = false;
      recodeBtn.disabled = false;
      dismissBtn.disabled = false;
      commitBtn.textContent = 'Commit';
    }
  });
}
