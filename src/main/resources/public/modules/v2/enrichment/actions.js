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
