// Utilities → Triage / No-Match Enrichments
// Lists titles whose javdb enrichment failed with 'no_match_in_filmography' and
// provides three resolution paths per title:
//   1. Try a candidate actress (whose cached filmography has the code)
//   2. Enter a javdb slug manually
//   3. Mark resolved (no javdb data exists)
// See spec/PROPOSAL_JAVDB_SLUG_VERIFICATION.md — Step 8 addendum.

import { esc } from './utils.js';

// ── DOM accessors (lazy — avoids FOUC before element exists) ──────────────────
const viewEl    = () => document.getElementById('tools-no-match-triage-view');
const headerEl  = () => document.getElementById('nmt-header');
const listEl    = () => document.getElementById('nmt-list');
const filterEl  = () => document.getElementById('nmt-filter-orphan');

// ── Module state factory ──────────────────────────────────────────────────────
function makeState() {
  return {
    rows:        [],   // all fetched rows
    loading:     false,
    orphanOnly:  false,
  };
}
let S = makeState();

// ── Public API ────────────────────────────────────────────────────────────────

export async function showNoMatchTriageView() {
  viewEl().style.display = 'block';
  S = makeState();
  // Sync checkbox DOM state with the reset module state.
  const filter = filterEl();
  if (filter) filter.checked = false;
  await loadAll();
}

export function hideNoMatchTriageView() {
  viewEl().style.display = 'none';
}

// ── Data loading ──────────────────────────────────────────────────────────────

async function loadAll() {
  if (S.loading) return;
  S.loading = true;
  setStatus('Loading…');
  try {
    const params = new URLSearchParams();
    if (S.orphanOnly) params.set('orphan', '1');
    const res  = await fetch('/api/triage/no-match?' + params.toString());
    if (!res.ok) throw new Error('HTTP ' + res.status);
    S.rows = await res.json();
    renderHeader();
    renderList();
  } catch (err) {
    console.error('no-match-triage: loadAll failed', err);
    setStatus('Failed to load. ' + err.message);
  } finally {
    S.loading = false;
  }
}

// ── Render ────────────────────────────────────────────────────────────────────

function renderHeader() {
  const total   = S.rows.length;
  const orphans = S.rows.filter(r => r.orphan).length;
  const linked  = total - orphans;
  const el = headerEl();
  if (!el) return;
  el.innerHTML =
    `<span class="nmt-count">${total} no-match title${total !== 1 ? 's' : ''}</span>` +
    (total > 0
      ? `<span class="nmt-breakdown">${linked} linked &middot; ${orphans} orphan</span>`
      : '');
}

function renderList() {
  const list = listEl();
  if (!list) return;
  list.innerHTML = '';

  if (S.rows.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'nmt-empty';
    empty.textContent = S.orphanOnly ? 'No orphan rows.' : 'No no-match rows. All clear!';
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

// ── Row card ──────────────────────────────────────────────────────────────────

function buildRowCard(row) {
  const card = document.createElement('div');
  card.className = 'nmt-card' + (row.orphan ? ' nmt-card-orphan' : '');
  card.dataset.titleId = row.titleId;

  // ── Title header ──
  const titleBar = document.createElement('div');
  titleBar.className = 'nmt-title-bar';

  const codeEl = document.createElement('span');
  codeEl.className = 'nmt-code';
  codeEl.textContent = row.code;
  titleBar.appendChild(codeEl);

  // Javdb search link
  const javdbLink = document.createElement('a');
  javdbLink.href = 'https://javdb.com/search?q=' + encodeURIComponent(row.baseCode || row.code);
  javdbLink.target = '_blank';
  javdbLink.rel = 'noopener noreferrer';
  javdbLink.className = 'nmt-javdb-link';
  javdbLink.textContent = 'Search javdb ↗';
  titleBar.appendChild(javdbLink);

  card.appendChild(titleBar);

  // ── Current actress link ──
  const actressEl = document.createElement('div');
  actressEl.className = 'nmt-actress';
  if (row.orphan) {
    actressEl.innerHTML = '<span class="nmt-orphan-label">No actress link</span>';
  } else {
    actressEl.textContent = 'Filed under: ' + row.linkedActressNames.join(', ');
  }
  card.appendChild(actressEl);

  // ── Folder path + open link ──
  if (row.folderPath) {
    const pathEl = document.createElement('div');
    pathEl.className = 'nmt-path';
    pathEl.innerHTML = '<span class="nmt-path-text">' + esc(row.folderPath) + '</span>';
    card.appendChild(pathEl);
  }

  // ── Candidate actress pills ──
  if (row.candidates && row.candidates.length > 0) {
    const candSection = document.createElement('div');
    candSection.className = 'nmt-candidates';

    const candLabel = document.createElement('div');
    candLabel.className = 'nmt-candidates-label';
    candLabel.textContent = 'Found in filmography:';
    candSection.appendChild(candLabel);

    const pills = document.createElement('div');
    pills.className = 'nmt-pills';

    for (const c of row.candidates) {
      const pill = document.createElement('button');
      pill.className = 'nmt-pill' + (c.stale ? ' nmt-pill-stale' : '');
      pill.type = 'button';
      pill.title = c.stale
        ? c.stageName + ' (filmography cache may be stale — >30d)'
        : c.stageName;
      pill.innerHTML = esc(c.stageName) + (c.stale ? ' <span class="nmt-stale-badge">stale</span>' : '');
      pill.addEventListener('click', () => doReassign(row.titleId, c.actressId, pill, card));
      pills.appendChild(pill);
    }

    candSection.appendChild(pills);
    card.appendChild(candSection);
  } else if (row.orphan) {
    const noCand = document.createElement('div');
    noCand.className = 'nmt-no-candidates';
    noCand.textContent = 'No actresses found in local filmography cache — search javdb manually.';
    card.appendChild(noCand);
  }

  // ── Manual slug entry ──
  const manualSection = document.createElement('div');
  manualSection.className = 'nmt-manual';

  const slugInput = document.createElement('input');
  slugInput.type = 'text';
  slugInput.className = 'nmt-slug-input';
  slugInput.placeholder = 'Enter javdb slug (e.g. AbCd12)';
  slugInput.maxLength = 32;
  manualSection.appendChild(slugInput);

  const slugBtn = document.createElement('button');
  slugBtn.type = 'button';
  slugBtn.className = 'nmt-action-btn nmt-manual-btn';
  slugBtn.textContent = 'Apply slug';
  slugBtn.addEventListener('click', () => doManualSlug(row.titleId, slugInput, slugBtn, card));
  manualSection.appendChild(slugBtn);

  card.appendChild(manualSection);

  // ── Mark resolved ──
  const resolveBtn = document.createElement('button');
  resolveBtn.type = 'button';
  resolveBtn.className = 'nmt-action-btn nmt-resolve-btn';
  resolveBtn.textContent = 'Mark resolved (no javdb data)';
  resolveBtn.addEventListener('click', () => doMarkResolved(row.titleId, resolveBtn, card));
  card.appendChild(resolveBtn);

  // ── Attempts / age ──
  const meta = document.createElement('div');
  meta.className = 'nmt-meta';
  meta.textContent = `${row.attempts} attempt${row.attempts !== 1 ? 's' : ''} · last: ${formatDate(row.updatedAt)}`;
  card.appendChild(meta);

  return card;
}

// ── Action handlers ───────────────────────────────────────────────────────────

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

// ── Card state helpers ────────────────────────────────────────────────────────

function setCardBusy(card, busy) {
  card.querySelectorAll('button, input').forEach(el => { el.disabled = busy; });
  card.classList.toggle('nmt-card-busy', busy);
}

function markCardDone(card, message) {
  card.classList.add('nmt-card-done');
  const msg = document.createElement('div');
  msg.className = 'nmt-done-msg';
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
  card.querySelectorAll('.nmt-error').forEach(e => e.remove());
  const err = document.createElement('div');
  err.className = 'nmt-error';
  err.textContent = '⚠ ' + message;
  card.appendChild(err);
}

// ── Utilities ─────────────────────────────────────────────────────────────────

function formatDate(isoStr) {
  if (!isoStr) return '—';
  try {
    return new Date(isoStr).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
  } catch (_) {
    return isoStr.substring(0, 10);
  }
}

// ── Filter wiring (called once by action.js) ──────────────────────────────────

export function wireNoMatchTriageEvents() {
  const filter = filterEl();
  if (!filter) return;
  filter.addEventListener('change', async () => {
    S.orphanOnly = filter.checked;
    await loadAll();
  });
}
