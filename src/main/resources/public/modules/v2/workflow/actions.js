// v2/workflow/actions.js — API endpoint wrappers for the Workflow surface.
//
// All functions return a Promise and call reload() on success. Callers disable
// relevant buttons during the request and re-enable them on failure.

// ── Resolve (generic) ─────────────────────────────────────────────────────────

export async function handleResolve(queueId, resolution, reload) {
  const res = await fetch(
    `/api/utilities/enrichment-review/queue/${encodeURIComponent(queueId)}/resolve`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolution }),
    }
  );
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.error || `HTTP ${res.status}`);
  }
  await reload();
}

// ── Pick candidate ────────────────────────────────────────────────────────────

export async function handlePick(queueRowId, slug, btn, reload) {
  btn.disabled = true;
  btn.textContent = 'Picking…';
  try {
    const res = await fetch(
      `/api/utilities/enrichment-review/queue/${encodeURIComponent(queueRowId)}/pick`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ slug }),
      }
    );
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.error || `HTTP ${res.status}`);
    }
    await reload();
  } catch (err) {
    console.error('[workflow] pick failed', err);
    btn.disabled = false;
    btn.textContent = 'Pick';
    alert(`Pick failed: ${err.message}`);
  }
}

// ── AI assist ─────────────────────────────────────────────────────────────────

export async function handleAiAssist(queueId, btn, reload) {
  btn.disabled = true;
  try {
    const res = await fetch(`/api/enrichment/workflow/${queueId}/ai-assist`, {
      method: 'POST',
    });
    if (!res.ok) {
      console.warn('[workflow] ai-assist failed', await res.text());
    }
    await reload();
  } catch (err) {
    console.error('[workflow] ai-assist error', err);
    await reload();
  }
}

// ── Bulk AI assist ────────────────────────────────────────────────────────────

export async function handleBulkAssist(bulkBtn, reload) {
  if (!bulkBtn) return;
  bulkBtn.disabled = true;
  try {
    const res = await fetch('/api/enrichment/workflow/ai-assist-all', { method: 'POST' });
    const data = await res.json();
    console.info('[workflow] ai-assist-all queued', data.queued);
    await reload();
  } catch (err) {
    console.error('[workflow] ai-assist-all error', err);
  }
}

// ── Override slug (force-enrich) ──────────────────────────────────────────────

export async function handleForceEnrich(queueId, slug, applyBtn, reload) {
  applyBtn.disabled = true;
  applyBtn.textContent = '…';
  try {
    const res = await fetch(
      `/api/utilities/enrichment-review/queue/${encodeURIComponent(queueId)}/force-enrich`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ slug }),
      }
    );
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.error || `HTTP ${res.status}`);
    }
    await reload();
  } catch (err) {
    console.error('[workflow] override-slug failed', err);
    applyBtn.disabled = false;
    applyBtn.textContent = 'Apply';
    throw err;
  }
}

// ── Refresh candidates ────────────────────────────────────────────────────────

export async function handleRefreshCandidates(queueId, reload) {
  const res = await fetch(
    `/api/utilities/enrichment-review/queue/${encodeURIComponent(queueId)}/refresh`,
    { method: 'POST', headers: { 'Content-Type': 'application/json' } }
  );
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.error || `HTTP ${res.status}`);
  }
  await reload();
}

// ── Orphan confirm delete ─────────────────────────────────────────────────────

export async function handleOrphanDelete(queueId, reload) {
  const res = await fetch(
    `/api/utilities/enrichment-review/queue/${encodeURIComponent(queueId)}/confirm-orphan-delete`,
    { method: 'POST', headers: { 'Content-Type': 'application/json' } }
  );
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.error || `HTTP ${res.status}`);
  }
  await reload();
}

// ── Add alias (cast-anomaly) ──────────────────────────────────────────────────

export async function handleAddAlias(queueRowId, actressId, aliasName, btn, errorEl, reload) {
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
      btn.textContent = `Add "${aliasName}" as alias`;
      return;
    }
    const rowsMsg = data.rows_recovered > 0
      ? `${data.rows_recovered} row(s) discharged`
      : '0 rows discharged (alias already known or no pending rows)';
    btn.textContent = `Added — ${rowsMsg}`;
    // Short delay so the user can see the confirmation before the row disappears.
    setTimeout(() => reload(), 1200);
  } catch (err) {
    console.error('[workflow] add-alias failed', err);
    errorEl.textContent = 'Error: ' + err.message;
    errorEl.style.display = '';
    btn.disabled = false;
    btn.textContent = `Add "${aliasName}" as alias`;
  }
}

// ── Recode title (two-step dry-run → commit) ──────────────────────────────────

/**
 * Step 1: dry-run call. Returns parsed response data.
 * Throws on HTTP error.
 */
export async function recodePreview(orphanId, newCode) {
  const res = await fetch(`/api/utilities/title/${encodeURIComponent(orphanId)}/recode`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newCode, dryRun: true, renameDisk: true }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || res.statusText);
  return data;
}

/**
 * Step 2: commit recode, then mark the queue row resolved.
 * Returns the recode response data on success; throws on failure.
 */
export async function recodeCommit(orphanId, newCode, queueRowId) {
  const recodeRes = await fetch(`/api/utilities/title/${encodeURIComponent(orphanId)}/recode`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newCode, dryRun: false, renameDisk: true }),
  });
  const recodeData = await recodeRes.json();
  if (!recodeRes.ok || recodeData.partialFailure) {
    throw new Error(recodeData.errorMessage || recodeData.error || recodeRes.statusText);
  }

  // Recode succeeded — mark the queue row resolved. If this fails we still succeeded on the
  // primary operation; log and allow the caller to reload so the stale row disappears.
  const resolveRes = await fetch(
    `/api/utilities/enrichment-review/queue/${encodeURIComponent(queueRowId)}/resolve`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolution: 'marked_resolved' }),
    }
  );
  if (!resolveRes.ok) {
    console.warn('[workflow] recode succeeded but queue resolve failed', await resolveRes.text());
  }
  return recodeData;
}
