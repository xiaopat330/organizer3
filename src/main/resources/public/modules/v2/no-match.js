/* ─────────────────────────────────────────────────────────────────────
   no-match.js — No-Match Triage workbench (v2).

   Ported from: modules/utilities-no-match-triage.js (legacy)
   Entry point: mountNoMatch(rootEl)

   What's ported:
     - Header with total / linked / orphan breakdown counts
     - Orphan-only filter checkbox (synced on each mount)
     - Per-title cards: code, javdb search link (baseCode || code),
       filed-under actress names or "No actress link" label for orphans,
       folder path display
     - Candidate actress pills with stale indicator and tooltip
     - "No actresses found" note for orphan + no-candidates case
     - Manual javdb-slug input (maxLength=32, focus-on-empty-submit)
     - "Mark resolved" action
     - Busy state (disables all buttons/inputs within card)
     - Done state (1 200ms hold → 400ms fade → remove + recount header)
     - Per-card error display (clears prior, re-shows)
     - attempt count + formatted last-updated date in card footer

   Deferred: none.
   ───────────────────────────────────────────────────────────────────── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

// ── Entry point ───────────────────────────────────────────────────────────────

export async function mountNoMatch(rootEl) {
  rootEl.innerHTML = buildPageHTML();

  // Local DOM refs (all scoped to rootEl)
  const headerEl  = () => rootEl.querySelector('#nm-header');
  const listEl    = () => rootEl.querySelector('#nm-list');
  const filterEl  = () => rootEl.querySelector('#nm-filter-orphan');

  // ── State (closure — no module-level singleton) ───────────────────────────
  const S = {
    rows:       [],
    loading:    false,
    orphanOnly: false,
  };

  // ── Data loading ──────────────────────────────────────────────────────────

  async function loadAll() {
    if (S.loading) return;
    S.loading = true;
    setStatus('Loading…');
    try {
      const params = new URLSearchParams();
      if (S.orphanOnly) params.set('orphan', '1');
      const res = await fetch('/api/triage/no-match?' + params.toString());
      if (!res.ok) throw new Error('HTTP ' + res.status);
      S.rows = await res.json();
      renderHeader();
      renderList();
    } catch (err) {
      console.error('no-match: loadAll failed', err);
      setStatus('Failed to load. ' + err.message);
    } finally {
      S.loading = false;
    }
  }

  // ── Render ────────────────────────────────────────────────────────────────

  function renderHeader() {
    const total   = S.rows.length;
    const orphans = S.rows.filter(r => r.orphan).length;
    const linked  = total - orphans;
    const el = headerEl();
    if (!el) return;
    el.innerHTML =
      `<span class="nm-count">${total} no-match title${total !== 1 ? 's' : ''}</span>` +
      (total > 0
        ? `<span class="nm-breakdown">${linked} linked &middot; ${orphans} orphan</span>`
        : '');
  }

  function renderList() {
    const list = listEl();
    if (!list) return;
    list.innerHTML = '';

    if (S.rows.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'dis-empty';
      empty.innerHTML = S.orphanOnly
        ? '◌<br>No orphan rows.'
        : '◌<br>No no-match rows. All clear!';
      list.appendChild(empty);
      return;
    }

    for (const row of S.rows) {
      list.appendChild(buildRowCard(row));
    }
  }

  function setStatus(msg) {
    const el = headerEl();
    if (el) el.textContent = msg;
  }

  // ── Row card ──────────────────────────────────────────────────────────────

  function buildRowCard(row) {
    const card = document.createElement('div');
    card.className = 'nm-card' + (row.orphan ? ' nm-card-orphan' : '');
    card.dataset.titleId = row.titleId;

    // ── Title header ──
    const titleBar = document.createElement('div');
    titleBar.className = 'nm-title-bar';

    const codeEl = document.createElement('span');
    codeEl.className = 'nm-code';
    codeEl.textContent = row.code;
    titleBar.appendChild(codeEl);

    // Javdb search link
    const javdbLink = document.createElement('a');
    javdbLink.href = 'https://javdb.com/search?q=' + encodeURIComponent(row.baseCode || row.code);
    javdbLink.target = '_blank';
    javdbLink.rel = 'noopener noreferrer';
    javdbLink.className = 'nm-javdb-link';
    javdbLink.textContent = 'Search javdb ↗';
    titleBar.appendChild(javdbLink);

    card.appendChild(titleBar);

    // ── Current actress link ──
    const actressEl = document.createElement('div');
    actressEl.className = 'nm-actress';
    if (row.orphan) {
      actressEl.innerHTML = '<span class="nm-orphan-label">No actress link</span>';
    } else {
      actressEl.textContent = 'Filed under: ' + row.linkedActressNames.join(', ');
    }
    card.appendChild(actressEl);

    // ── Folder path ──
    if (row.folderPath) {
      const pathEl = document.createElement('div');
      pathEl.className = 'nm-path';
      pathEl.innerHTML = '<span class="nm-path-text">' + esc(row.folderPath) + '</span>';
      card.appendChild(pathEl);
    }

    // ── Candidate actress pills ──
    if (row.candidates && row.candidates.length > 0) {
      const candSection = document.createElement('div');
      candSection.className = 'nm-candidates';

      const candLabel = document.createElement('div');
      candLabel.className = 'nm-candidates-label';
      candLabel.textContent = 'Found in filmography:';
      candSection.appendChild(candLabel);

      const pills = document.createElement('div');
      pills.className = 'nm-pills';

      for (const c of row.candidates) {
        const pill = document.createElement('button');
        pill.className = 'nm-pill' + (c.stale ? ' nm-pill-stale' : '');
        pill.type = 'button';
        pill.title = c.stale
          ? c.stageName + ' (filmography cache may be stale — >30d)'
          : c.stageName;
        pill.innerHTML = esc(c.stageName) + (c.stale ? ' <span class="nm-stale-badge">stale</span>' : '');
        pill.addEventListener('click', () => doReassign(row.titleId, c.actressId, pill, card));
        pills.appendChild(pill);
      }

      candSection.appendChild(pills);
      card.appendChild(candSection);
    } else if (row.orphan) {
      const noCand = document.createElement('div');
      noCand.className = 'nm-no-candidates';
      noCand.textContent = 'No actresses found in local filmography cache — search javdb manually.';
      card.appendChild(noCand);
    }

    // ── Manual slug entry ──
    const manualSection = document.createElement('div');
    manualSection.className = 'nm-manual';

    const slugInput = document.createElement('input');
    slugInput.type = 'text';
    slugInput.className = 'nm-slug-input';
    slugInput.placeholder = 'Enter javdb slug (e.g. AbCd12)';
    slugInput.maxLength = 32;
    manualSection.appendChild(slugInput);

    const slugBtn = document.createElement('button');
    slugBtn.type = 'button';
    slugBtn.className = 'nm-action-btn nm-manual-btn nm-apply-slug-btn';
    slugBtn.textContent = 'Apply slug';
    slugBtn.addEventListener('click', () => doManualSlug(row.titleId, slugInput, slugBtn, card));
    manualSection.appendChild(slugBtn);

    card.appendChild(manualSection);

    // ── Mark resolved ──
    const resolveBtn = document.createElement('button');
    resolveBtn.type = 'button';
    resolveBtn.className = 'nm-action-btn nm-resolve-btn';
    resolveBtn.textContent = 'Mark resolved (no javdb data)';
    resolveBtn.addEventListener('click', () => doMarkResolved(row.titleId, resolveBtn, card));
    card.appendChild(resolveBtn);

    // ── Attempts / age ──
    const meta = document.createElement('div');
    meta.className = 'nm-meta';
    meta.textContent = `${row.attempts} attempt${row.attempts !== 1 ? 's' : ''} · last: ${formatDate(row.updatedAt)}`;
    card.appendChild(meta);

    return card;
  }

  // ── Action handlers ───────────────────────────────────────────────────────

  async function doReassign(titleId, actressId, pill, card) {
    setCardBusy(card, true);
    try {
      const res = await fetch(`/api/triage/no-match/${titleId}/reassign`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ actressId }),
      });
      if (res.status === 204) {
        markCardDone(card, 'Re-queued with new actress.');
      } else {
        const body = await res.json().catch(() => ({}));
        showCardError(card, body.error || 'Reassign failed (' + res.status + ')');
      }
    } catch (err) {
      showCardError(card, 'Network error: ' + err.message);
    } finally {
      setCardBusy(card, false);
    }
  }

  async function doManualSlug(titleId, input, btn, card) {
    const slug = input.value.trim();
    if (!slug) {
      input.focus();
      return;
    }
    setCardBusy(card, true);
    try {
      const res = await fetch(`/api/triage/no-match/${titleId}/manual`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ javdbSlug: slug }),
      });
      if (res.status === 204) {
        markCardDone(card, 'Enriched with slug ' + slug + '.');
      } else {
        const body = await res.json().catch(() => ({}));
        showCardError(card, body.error || 'Manual entry failed (' + res.status + ')');
      }
    } catch (err) {
      showCardError(card, 'Network error: ' + err.message);
    } finally {
      setCardBusy(card, false);
    }
  }

  async function doMarkResolved(titleId, btn, card) {
    setCardBusy(card, true);
    try {
      const res = await fetch(`/api/triage/no-match/${titleId}/resolve`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      });
      if (res.status === 204) {
        markCardDone(card, 'Marked as resolved — no javdb data.');
      } else {
        const body = await res.json().catch(() => ({}));
        showCardError(card, body.error || 'Resolve failed (' + res.status + ')');
      }
    } catch (err) {
      showCardError(card, 'Network error: ' + err.message);
    } finally {
      setCardBusy(card, false);
    }
  }

  // ── Card state helpers ────────────────────────────────────────────────────

  function setCardBusy(card, busy) {
    card.querySelectorAll('button, input').forEach(el => { el.disabled = busy; });
    card.classList.toggle('nm-card-busy', busy);
  }

  function markCardDone(card, message) {
    card.classList.add('nm-card-done');
    const msg = document.createElement('div');
    msg.className = 'nm-done-msg';
    msg.textContent = '✓ ' + message;
    card.appendChild(msg);
    // Fade out and remove from live list after a short delay.
    setTimeout(() => {
      card.style.transition = 'opacity 0.4s';
      card.style.opacity = '0';
      setTimeout(() => {
        card.remove();
        S.rows = S.rows.filter(r => r.titleId != card.dataset.titleId);
        renderHeader();
      }, 400);
    }, 1200);
  }

  function showCardError(card, message) {
    // Remove any previous error
    card.querySelectorAll('.nm-error').forEach(e => e.remove());
    const err = document.createElement('div');
    err.className = 'nm-error';
    err.textContent = '⚠ ' + message;
    card.appendChild(err);
  }

  // ── Orphan filter wiring ──────────────────────────────────────────────────

  const filter = filterEl();
  if (filter) {
    filter.checked = false; // sync DOM with initial state
    filter.addEventListener('change', async () => {
      S.orphanOnly = filter.checked;
      await loadAll();
    });
  }

  // ── Initial load ──────────────────────────────────────────────────────────

  await loadAll();
}

// ── Utilities ─────────────────────────────────────────────────────────────────

function formatDate(isoStr) {
  if (!isoStr) return '—';
  try {
    return new Date(isoStr).toLocaleDateString(undefined, {
      month: 'short', day: 'numeric', year: 'numeric',
    });
  } catch (_) {
    return isoStr.substring(0, 10);
  }
}

// ── Page scaffold HTML ────────────────────────────────────────────────────────

function buildPageHTML() {
  return `
    <div class="wb-page nm-wb">
      <div class="nm-wb-head">
        <h1 class="nm-wb-title">No-Match Triage</h1>
        <div class="nm-wb-subtitle">
          Titles whose JavDB enrichment returned no match in filmography.
          Resolve each via a candidate actress, a manual slug, or mark as unresolvable.
        </div>
      </div>

      <div class="nm-toolbar">
        <div id="nm-header" class="nm-header dis-kpi-strip">Loading…</div>
        <label class="nm-filter-label">
          <input type="checkbox" id="nm-filter-orphan" class="nm-filter-check">
          Orphan only
        </label>
      </div>

      <div id="nm-list" class="nm-list"></div>
    </div>
  `;
}
