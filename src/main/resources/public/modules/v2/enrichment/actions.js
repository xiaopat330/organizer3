// v2/enrichment/actions.js — API-backed action handlers.
//
// Each exported function corresponds to one server operation:
//   resolveRow         — POST /resolve with a resolution string
//   doPickCandidate    — POST /pick with a javdb slug
//   doRefreshCandidates— POST /refresh for the ambiguous picker
//   doForceEnrich      — POST /force-enrich with a manually entered slug
//   confirmOrphanDelete— POST /confirm-orphan-delete
//   doAddAlias         — POST /api/triage/cast-anomaly/:id/add-alias

// ── Resolve ──────────────────────────────────────────────────────────────────

/**
 * @param {number}        id          queue row ID
 * @param {string}        resolution  e.g. 'accepted_gap', 'marked_resolved', 'marked_moved', 'dismissed'
 * @param {HTMLElement}   tr          the <tr> to disable during the call
 * @param {Function}      reload      called on success
 */
export async function resolveRow(id, resolution, tr, reload) {
  tr.querySelectorAll('.er-action-btn').forEach(b => { b.disabled = true; });
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${id}/resolve`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolution }),
    });
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    if (data.ok) {
      await reload();
    } else {
      alert(data.message);
      tr.querySelectorAll('.er-action-btn').forEach(b => { b.disabled = false; });
    }
  } catch (err) {
    console.error('EnrichmentReview: resolve failed', err);
    alert('Failed to resolve row: ' + err.message);
    tr.querySelectorAll('.er-action-btn').forEach(b => { b.disabled = false; });
  }
}

// ── Pick candidate ────────────────────────────────────────────────────────────

/**
 * @param {number}      queueRowId
 * @param {string}      slug        javdb slug chosen
 * @param {HTMLElement} pickBtn     button to show loading state
 * @param {Function}    reload
 */
export async function doPickCandidate(queueRowId, slug, pickBtn, reload) {
  pickBtn.disabled = true;
  pickBtn.textContent = 'Picking…';
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${queueRowId}/pick`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
    });
    const data = await res.json();
    if (!res.ok || !data.ok) {
      alert('Pick failed: ' + (data.error || data.message || res.statusText));
      pickBtn.disabled = false;
      pickBtn.textContent = 'Pick this';
    } else {
      await reload();
    }
  } catch (err) {
    console.error('EnrichmentReview: pick failed', err);
    alert('Pick failed: ' + err.message);
    pickBtn.disabled = false;
    pickBtn.textContent = 'Pick this';
  }
}

// ── Refresh candidates ────────────────────────────────────────────────────────

/**
 * @param {object}      row         the queue row object (mutated on success)
 * @param {HTMLElement} panel       the picker panel element to re-render into
 * @param {Function}    renderPanel callback(panel, row, freshDetail) to re-render
 */
export async function doRefreshCandidates(row, panel, renderPanel) {
  const btn = panel.querySelector('.er-picker-refresh-btn, .er-picker-load-btn');
  if (btn) { btn.disabled = true; btn.textContent = 'Loading…'; }
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${row.id}/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    });
    const data = await res.json();
    if (!res.ok || !data.ok) {
      alert('Refresh failed: ' + (data.error || data.message || res.statusText));
      if (btn) { btn.disabled = false; btn.textContent = 'Refresh candidates'; }
      return;
    }
    row.detail = data.detailJson;
    let freshDetail = null;
    try { freshDetail = data.detailJson ? JSON.parse(data.detailJson) : null; } catch {}
    renderPanel(panel, row, freshDetail);
  } catch (err) {
    console.error('EnrichmentReview: refresh failed', err);
    alert('Refresh failed: ' + err.message);
    if (btn) { btn.disabled = false; btn.textContent = 'Refresh candidates'; }
  }
}

// ── Force enrich (override slug form) ────────────────────────────────────────

/**
 * @param {number}      queueRowId
 * @param {string}      slug        manually entered slug
 * @param {HTMLElement} tr          row element (buttons disabled during call)
 * @param {Function}    reload
 */
export async function doForceEnrich(queueRowId, slug, tr, reload) {
  tr.querySelectorAll('.er-action-btn, .er-slug-submit, .er-slug-cancel').forEach(b => { b.disabled = true; });
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${queueRowId}/force-enrich`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
    });
    const data = await res.json();
    if (!res.ok || !data.ok) {
      alert('Force enrich failed: ' + (data.error || data.message || res.statusText));
      tr.querySelectorAll('.er-action-btn, .er-slug-submit, .er-slug-cancel').forEach(b => { b.disabled = false; });
    } else {
      await reload();
    }
  } catch (err) {
    console.error('EnrichmentReview: force enrich failed', err);
    alert('Force enrich failed: ' + err.message);
    tr.querySelectorAll('.er-action-btn, .er-slug-submit, .er-slug-cancel').forEach(b => { b.disabled = false; });
  }
}

// ── Orphan confirm delete ─────────────────────────────────────────────────────

export async function confirmOrphanDelete(queueRowId, tr, reload) {
  if (!confirm('Permanently delete this enriched title? This cannot be undone.')) return;
  tr.querySelectorAll('.er-action-btn').forEach(b => { b.disabled = true; });
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${queueRowId}/confirm-orphan-delete`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    });
    const data = await res.json();
    if (!res.ok || !data.ok) {
      alert('Delete failed: ' + (data.error || data.message || res.statusText));
      tr.querySelectorAll('.er-action-btn').forEach(b => { b.disabled = false; });
    } else {
      await reload();
    }
  } catch (err) {
    console.error('EnrichmentReview: confirm-orphan-delete failed', err);
    alert('Delete failed: ' + err.message);
    tr.querySelectorAll('.er-action-btn').forEach(b => { b.disabled = false; });
  }
}

// ── Recode title (recode_candidate rows) ─────────────────────────────────────

/**
 * Dry-run the recode, show an inline preview row, then offer Commit / Cancel.
 *
 * @param {object}      row         queue row (needs row.id, detail.orphan_id, detail.new_folder_code)
 * @param {HTMLElement} tr          the <tr> for this queue row
 * @param {HTMLElement} tableBody   the <tbody> (to close any other open preview rows)
 * @param {Function}    reload
 */
export async function startRecodeFlow(row, tr, tableBody, reload) {
  // Toggle: close if already open.
  const existing = tr.nextElementSibling;
  if (existing && existing.classList.contains('er-recode-preview-row')) {
    existing.remove();
    return;
  }
  tableBody.querySelectorAll('.er-recode-preview-row').forEach(r => r.remove());

  let detail = null;
  try { detail = row.detail ? JSON.parse(row.detail) : null; } catch {}
  const orphanId  = detail ? detail.orphan_id        : null;
  const newCode   = detail ? detail.new_folder_code  : null;

  if (!orphanId || !newCode) {
    alert('Recode: missing orphan_id or new_folder_code in queue row detail.');
    return;
  }

  // Insert a loading row immediately for responsiveness.
  const previewTr = document.createElement('tr');
  previewTr.className = 'er-recode-preview-row';
  const td = document.createElement('td');
  td.colSpan = 6;
  td.innerHTML = '<div class="er-recode-preview-panel"><span class="er-recode-loading">Loading preview…</span></div>';
  previewTr.appendChild(td);
  tr.insertAdjacentElement('afterend', previewTr);

  const panel = td.querySelector('.er-recode-preview-panel');

  try {
    const res = await fetch(`/api/utilities/title/${orphanId}/recode`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ newCode, dryRun: true, renameDisk: true }),
    });
    const data = await res.json();
    if (!res.ok) {
      panel.innerHTML = `<span class="er-recode-error">Preview failed: ${data.error || res.statusText}</span>
        <button type="button" class="er-recode-cancel-btn er-action-btn">Cancel</button>`;
      panel.querySelector('.er-recode-cancel-btn').addEventListener('click', () => previewTr.remove());
      return;
    }
    renderRecodePreview(panel, data, orphanId, newCode, row.id, tr, previewTr, reload);
  } catch (err) {
    console.error('EnrichmentReview: recode preview failed', err);
    panel.innerHTML = `<span class="er-recode-error">Preview failed: ${err.message}</span>
      <button type="button" class="er-recode-cancel-btn er-action-btn">Cancel</button>`;
    panel.querySelector('.er-recode-cancel-btn').addEventListener('click', () => previewTr.remove());
  }
}

function renderRecodePreview(panel, dryRunResult, orphanId, newCode, queueRowId, tr, previewTr, reload) {
  const locations = dryRunResult.locations || [];
  const pathLines = locations.map(l => {
    const oldBase = l.oldPath.split(/[\\/]/).pop();
    const newBase = l.newPath.split(/[\\/]/).pop();
    return `<div class="er-recode-path-row">
      <span class="er-recode-old" title="${l.oldPath}">${oldBase}</span>
      <span class="er-recode-arrow">→</span>
      <span class="er-recode-new" title="${l.newPath}">${newBase}</span>
    </div>`;
  }).join('');

  panel.innerHTML = `
    <div class="er-recode-preview-summary">
      Will recode <b>${dryRunResult.oldCode}</b> → <b>${dryRunResult.newCode}</b>,
      updating ${locations.length} location path${locations.length !== 1 ? 's' : ''}.
    </div>
    <div class="er-recode-paths">${pathLines || '<span class="er-recode-no-paths">No location paths to rename.</span>'}</div>
    <div class="er-recode-preview-actions">
      <button type="button" class="er-recode-commit-btn er-action-btn">Commit</button>
      <button type="button" class="er-recode-cancel-btn er-action-btn">Cancel</button>
    </div>
    <div class="er-recode-commit-error" style="display:none"></div>
  `;

  panel.querySelector('.er-recode-cancel-btn').addEventListener('click', () => previewTr.remove());
  panel.querySelector('.er-recode-commit-btn').addEventListener('click', () =>
    commitRecode(panel, orphanId, newCode, queueRowId, tr, previewTr, reload));
}

async function commitRecode(panel, orphanId, newCode, queueRowId, tr, previewTr, reload) {
  const commitBtn = panel.querySelector('.er-recode-commit-btn');
  const cancelBtn = panel.querySelector('.er-recode-cancel-btn');
  const errorEl   = panel.querySelector('.er-recode-commit-error');
  commitBtn.disabled = true;
  cancelBtn.disabled = true;
  commitBtn.textContent = 'Committing…';
  errorEl.style.display = 'none';

  try {
    const recodeRes = await fetch(`/api/utilities/title/${orphanId}/recode`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ newCode, dryRun: false, renameDisk: true }),
    });
    const recodeData = await recodeRes.json();
    if (!recodeRes.ok || recodeData.partialFailure) {
      const msg = recodeData.errorMessage || recodeData.error || recodeRes.statusText;
      errorEl.textContent = 'Recode failed: ' + msg;
      errorEl.style.display = '';
      commitBtn.disabled = false;
      cancelBtn.disabled = false;
      commitBtn.textContent = 'Commit';
      return;
    }

    // Recode succeeded — now mark the queue row resolved.
    const resolveRes = await fetch(`/api/utilities/enrichment-review/queue/${queueRowId}/resolve`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolution: 'marked_resolved' }),
    });
    if (!resolveRes.ok) {
      // Recode done but queue row is stale — log and reload anyway.
      console.warn('EnrichmentReview: recode succeeded but queue resolve failed', await resolveRes.text());
    }
    await reload();
  } catch (err) {
    console.error('EnrichmentReview: recode commit failed', err);
    errorEl.textContent = 'Commit failed: ' + err.message;
    errorEl.style.display = '';
    commitBtn.disabled = false;
    cancelBtn.disabled = false;
    commitBtn.textContent = 'Commit';
  }
}

// ── Add alias (cast-anomaly) ──────────────────────────────────────────────────

/**
 * @param {number}      queueRowId
 * @param {number}      actressId
 * @param {string}      aliasName   javdb cast name to add as alias
 * @param {HTMLElement} btn
 * @param {HTMLElement} errorEl     element to show error text in
 * @param {Function}    reload
 */
export async function doAddAlias(queueRowId, actressId, aliasName, btn, errorEl, reload) {
  btn.disabled = true;
  btn.textContent = 'Adding…';
  errorEl.style.display = 'none';
  try {
    const res = await fetch(`/api/triage/cast-anomaly/${queueRowId}/add-alias`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ actressId, aliasName }),
    });
    const data = await res.json();
    if (!res.ok) {
      errorEl.textContent = 'Error: ' + (data.error || res.statusText);
      errorEl.style.display = '';
      btn.disabled = false;
      btn.textContent = `Add "${aliasName}" as alias for actress`;
      return;
    }
    const rowsMsg = data.rows_recovered > 0
      ? `${data.rows_recovered} row(s) discharged`
      : '0 rows discharged (alias already known or no pending rows)';
    btn.textContent = `✓ alias added — ${rowsMsg}`;
    setTimeout(() => reload(), 1200);
  } catch (err) {
    console.error('EnrichmentReview: add-alias failed', err);
    errorEl.textContent = 'Error: ' + err.message;
    errorEl.style.display = '';
    btn.disabled = false;
    btn.textContent = `Add "${aliasName}" as alias`;
  }
}
