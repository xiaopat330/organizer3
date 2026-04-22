// Aliases subview inside Actress Data. Two-pane inline editor:
//   Left pane:  search input + persistent result list (sticky selection)
//   Right pane: selected actress card + inline alias editor (add / save),
//               with merge-conflict flow rendered inline below the editor.
//
// No modal — matches the file-explorer interaction pattern the rest of
// Utilities uses. All endpoints and validation logic are unchanged from
// the previous modal-based implementation.

import { esc } from './utils.js';

const SELECTION_KEY = 'utilities.actress-data.aliases.selection';

const viewEl    = () => document.getElementById('ad-subview-aliases');
const searchEl  = () => document.getElementById('al-search-input');
const resultsEl = () => document.getElementById('al-results');
const emptyEl   = () => document.getElementById('al-empty');
const detailEl  = () => document.getElementById('al-detail');

const MIN_CHARS   = 2;
const DEBOUNCE_MS = 300;

let debounceTimer = null;
let lastResults   = [];         // last search hit list, used to highlight selection
let selectedId    = null;
let currentQuery  = '';

// ── Public lifecycle (called from utilities-actress-data.js) ──────────────

export function showAliasEditor() {
  viewEl().style.display = 'flex';
  searchEl().value = '';
  lastResults = [];
  renderResults([], '');
  // Restore last-edited actress if present, so quick return trips skip the search.
  const sticky = localStorage.getItem(SELECTION_KEY);
  if (sticky) {
    const id = Number(sticky);
    if (!Number.isNaN(id)) openActress(id);
  } else {
    showEmpty();
  }
  setTimeout(() => searchEl().focus(), 50);
}

export function hideAliasEditorView() {
  viewEl().style.display = 'none';
}

// ── Search ────────────────────────────────────────────────────────────────

searchEl().addEventListener('input', () => {
  clearTimeout(debounceTimer);
  const q = searchEl().value.trim();
  currentQuery = q;
  if (q.length < MIN_CHARS) {
    lastResults = [];
    renderResults([], q);
    return;
  }
  debounceTimer = setTimeout(() => runSearch(q), DEBOUNCE_MS);
});

searchEl().addEventListener('keydown', (e) => {
  if (e.key === 'Escape') searchEl().value = '';
});

async function runSearch(q) {
  try {
    const res = await fetch(`/api/search?q=${encodeURIComponent(q)}&matchMode=contains&includeAv=false&includeSparse=true`);
    if (!res.ok) return;
    const data = await res.json();
    lastResults = data.actresses || [];
    renderResults(lastResults, q);
  } catch { /* ignore transient errors */ }
}

function renderResults(actresses, query) {
  const el = resultsEl();
  if (!query || query.length < MIN_CHARS) {
    el.innerHTML = '';
    return;
  }
  if (!actresses.length) {
    el.innerHTML = '<div class="al-empty-results">No actresses found</div>';
    return;
  }
  el.innerHTML = '';
  for (const a of actresses) {
    const row = document.createElement('div');
    row.className = 'al-result';
    if (a.id === selectedId) row.classList.add('selected');

    const cover = a.coverUrl
        ? `<div class="al-result-cover"><img class="al-result-cover-img" src="${esc(a.coverUrl)}" alt="" loading="lazy"></div>`
        : '<div class="al-result-cover empty"></div>';

    const alias = a.matchedAlias
        ? `<div class="al-result-alias">a.k.a. ${highlight(a.matchedAlias, query)}</div>` : '';

    row.innerHTML = cover
        + '<div class="al-result-body">'
        +   `<div class="al-result-name">${highlight(a.canonicalName, query)}</div>`
        +   alias
        +   `<div class="al-result-count">${a.titleCount} titles</div>`
        + '</div>';

    row.addEventListener('click', () => openActress(a.id));
    el.appendChild(row);
  }
}

function highlight(text, query) {
  if (!text) return '';
  if (!query) return esc(text);
  const lower = text.toLowerCase();
  const idx   = lower.indexOf(query.toLowerCase());
  if (idx < 0) return esc(text);
  return esc(text.slice(0, idx))
       + '<em><strong class="search-match">' + esc(text.slice(idx, idx + query.length)) + '</strong></em>'
       + esc(text.slice(idx + query.length));
}

// ── Detail pane: actress card + inline alias editor ──────────────────────

async function openActress(actressId) {
  selectedId = actressId;
  localStorage.setItem(SELECTION_KEY, String(actressId));

  // Reflect selection in result list immediately.
  resultsEl().querySelectorAll('.al-result').forEach(row => row.classList.remove('selected'));

  showEmpty(false);
  detailEl().style.display = '';
  detailEl().innerHTML = '<div class="al-detail-loading">Loading…</div>';

  try {
    const res = await fetch(`/api/actresses/${actressId}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const a = await res.json();
    renderDetail(a);
    // Re-render result list so the selected row updates its class
    renderResults(lastResults, currentQuery);
  } catch (err) {
    detailEl().innerHTML = `<div class="al-detail-error">Failed to load actress: ${esc(err.message)}</div>`;
  }
}

function renderDetail(a) {
  const cover = (a.coverUrls && a.coverUrls.length)
      ? `<div class="al-card-cover"><img class="al-card-cover-img" src="${esc(a.coverUrls[0])}" alt=""></div>`
      : '<div class="al-card-cover empty"></div>';

  const nameParts = splitCanonical(a.canonicalName);
  const nameHtml = nameParts.first
      ? `<span class="al-card-first">${esc(nameParts.first)}</span><span class="al-card-last">${esc(nameParts.last)}</span>`
      : `<span class="al-card-last">${esc(nameParts.last)}</span>`;

  const grade = a.grade ? `<span class="al-card-grade">${esc(a.grade)}</span>` : '';

  detailEl().innerHTML = `
    <div class="al-card">
      ${cover}
      <div class="al-card-body">
        <div class="al-card-name">${nameHtml}</div>
        <div class="al-card-meta">
          <span class="al-card-tier">${esc(a.tier || '')}</span>
          ${grade}
          ${a.titleCount != null ? `<span class="al-card-count">${a.titleCount} title${a.titleCount !== 1 ? 's' : ''}</span>` : ''}
        </div>
      </div>
    </div>
    <div class="al-editor">
      <div class="al-editor-heading">Aliases</div>
      <table class="al-table">
        <thead><tr>
          <th class="al-col-first">First Name</th>
          <th class="al-col-last">Name</th>
          <th class="al-col-remove"></th>
        </tr></thead>
        <tbody id="al-table-body"></tbody>
      </table>
      <div class="al-editor-actions">
        <button type="button" id="al-add-btn" class="al-add-btn">+ Add alias</button>
        <div class="al-editor-actions-right">
          <button type="button" id="al-cancel-btn" class="al-cancel-btn">Cancel</button>
          <button type="button" id="al-save-btn" class="al-save-btn">Save</button>
        </div>
      </div>
      <div id="al-error" class="al-error" style="display:none"></div>
    </div>
  `;

  const tbody = document.getElementById('al-table-body');
  const aliases = a.aliases || [];
  for (const alias of aliases) appendRow(tbody, alias.name);

  document.getElementById('al-add-btn').addEventListener('click', () => {
    clearError();
    appendRow(tbody, '');
    const last = tbody.querySelector('tr:last-child .al-input-last');
    if (last) last.focus();
  });
  document.getElementById('al-save-btn').addEventListener('click', () =>
      saveAliases(a.id, a.canonicalName));
  document.getElementById('al-cancel-btn').addEventListener('click', () => {
    // Discard unsaved edits by re-fetching the actress. Simpler than tracking
    // original state locally and handles re-ordering / removes in one step.
    openActress(a.id);
  });
}

function splitCanonical(name) {
  if (!name) return { first: '', last: '' };
  const i = name.indexOf(' ');
  return i >= 0 ? { first: name.slice(0, i), last: name.slice(i + 1) } : { first: '', last: name };
}

function appendRow(tbody, aliasName) {
  const { first, last } = splitCanonical(aliasName || '');
  const tr = document.createElement('tr');
  tr.innerHTML =
      `<td><input class="al-input al-input-first" type="text" value="${esc(first)}" placeholder="(optional)"></td>`
    + `<td><input class="al-input al-input-last"  type="text" value="${esc(last)}"  placeholder="Required"></td>`
    + `<td><button class="al-row-remove" title="Remove" tabindex="-1">✕</button></td>`;
  tr.querySelector('.al-row-remove').addEventListener('click', () => { tr.remove(); clearError(); });
  tbody.appendChild(tr);
}

// ── Save / merge flow ────────────────────────────────────────────────────

async function saveAliases(actressId, actressName) {
  clearError();
  const tbody = document.getElementById('al-table-body');
  const rows = Array.from(tbody.querySelectorAll('tr'));
  const aliases = rows.map(tr => {
    const first = tr.querySelector('.al-input-first')?.value.trim() || '';
    const last  = tr.querySelector('.al-input-last')?.value.trim()  || '';
    return first ? `${first} ${last}` : last;
  }).filter(Boolean);

  // Client-side duplicate check
  const seen = new Set();
  const dupes = [];
  for (const a of aliases) {
    const key = a.toLowerCase();
    if (seen.has(key)) dupes.push(a); else seen.add(key);
  }
  if (dupes.length) {
    showError(`Duplicate alias${dupes.length > 1 ? 'es' : ''}: ${dupes.join(', ')}`);
    return;
  }

  const saveBtn = document.getElementById('al-save-btn');
  saveBtn.disabled = true;
  saveBtn.textContent = 'Saving…';
  try {
    const res = await fetch(`/api/actresses/${actressId}/aliases`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ aliases }),
    });
    if (res.ok) {
      showSavedToast(actressName);
      // Refresh detail in place so aliases persist visually.
      openActress(actressId);
    } else {
      const data = await res.json().catch(() => ({}));
      showError(data.error || `Error ${res.status}`,
          data.conflictActressId, data.conflictActressName, data.conflictKind, actressId, actressName);
    }
  } catch (err) {
    showError('Network error — aliases not saved.');
    console.error(err);
  } finally {
    if (saveBtn && document.body.contains(saveBtn)) {
      saveBtn.disabled = false;
      saveBtn.textContent = 'Save';
    }
  }
}

function showError(msg, conflictActressId, conflictActressName, conflictKind,
                   currentId, currentName) {
  const errorEl = document.getElementById('al-error');
  if (!errorEl) return;
  errorEl.innerHTML = '';
  errorEl.appendChild(document.createTextNode(msg));

  if (conflictActressId != null && conflictActressName) {
    errorEl.appendChild(document.createTextNode(' — '));
    const openLink = document.createElement('a');
    openLink.href = '#';
    openLink.className = 'al-error-link';
    openLink.textContent = `Open ${conflictActressName}`;
    openLink.addEventListener('click', (e) => {
      e.preventDefault();
      openActress(conflictActressId);
    });
    errorEl.appendChild(openLink);

    if (conflictKind === 'canonical' && currentId != null) {
      errorEl.appendChild(document.createTextNode(' · '));
      const mergeLink = document.createElement('a');
      mergeLink.href = '#';
      mergeLink.className = 'al-error-link';
      mergeLink.textContent = `Merge ${conflictActressName} into ${currentName}`;
      mergeLink.addEventListener('click', (e) => {
        e.preventDefault();
        startMergeFlow(currentId, currentName, conflictActressId, conflictActressName);
      });
      errorEl.appendChild(mergeLink);
    }
  }
  errorEl.style.display = 'block';
}

function clearError() {
  const errorEl = document.getElementById('al-error');
  if (!errorEl) return;
  errorEl.style.display = 'none';
  errorEl.innerHTML = '';
}

async function startMergeFlow(intoId, intoName, fromId, fromName) {
  // 1. Dry-run plan
  let plan;
  try {
    const res = await fetch(`/api/actresses/${intoId}/merge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fromId, dryRun: true }),
    });
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      showError(data.error || `Merge preview failed (${res.status})`);
      return;
    }
    const body = await res.json();
    plan = body.plan || body;
  } catch (err) {
    showError('Network error — merge preview failed.');
    return;
  }

  // 2. Confirm with plan summary
  const summary = plan.summary || `Fold "${fromName}" into "${intoName}"`;
  if (!window.confirm(
        `${summary}\n\n`
      + `This will:\n`
      + `  • reassign all of ${fromName}'s titles to ${intoName}\n`
      + `  • add "${fromName}" as an alias of ${intoName}\n`
      + `  • delete the ${fromName} record\n\n`
      + `This cannot be undone. Proceed?`)) return;

  // 3. Commit
  try {
    const res = await fetch(`/api/actresses/${intoId}/merge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fromId, dryRun: false }),
    });
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      showError(data.error || `Merge failed (${res.status})`);
      return;
    }
  } catch (err) {
    showError('Network error — merge failed.');
    return;
  }

  showSavedToast(`Merged ${fromName} into ${intoName}`);
  openActress(intoId);
}

function showEmpty(force) {
  if (force === false) {
    emptyEl().style.display = 'none';
    return;
  }
  emptyEl().style.display = '';
  detailEl().style.display = 'none';
}

function showSavedToast(name) {
  const t = document.createElement('div');
  t.className = 'al-toast';
  t.textContent = typeof name === 'string' && name.startsWith('Merged') ? name : `Aliases saved for ${name}`;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 3000);
}
