import { esc } from './utils.js';
import { showView, updateBreadcrumb } from './grid.js';
import { pushNav } from './nav.js';

// ── DOM refs ──────────────────────────────────────────────────────────────
const aliasesBtn       = document.getElementById('tools-aliases-btn');
const aliasesView      = document.getElementById('tools-aliases-view');
const searchInput      = document.getElementById('alias-search-input');
const searchOverlay    = document.getElementById('alias-search-overlay');
const modalOverlay     = document.getElementById('alias-modal-overlay');
const modalTitle       = document.getElementById('alias-modal-title');
const modalLeft        = document.getElementById('alias-modal-left');
const tableBody        = document.getElementById('alias-table-body');
const addBtn           = document.getElementById('alias-add-btn');
const saveBtn          = document.getElementById('alias-modal-save');
const cancelBtn        = document.getElementById('alias-modal-cancel');

const MIN_CHARS    = 2;
const DEBOUNCE_MS  = 300;

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

// ── Alias editor view ──────────────────────────────────────────────────────

export function showAliasEditor() {
  aliasesView.style.display = '';
  searchInput.value = '';
  searchOverlay.style.display = 'none';
  setTimeout(() => searchInput.focus(), 50);
}

export function hideAliasEditorView() {
  aliasesView.style.display = 'none';
}

// ── Search ─────────────────────────────────────────────────────────────────

let debounceTimer = null;

searchInput.addEventListener('input', () => {
  clearTimeout(debounceTimer);
  const q = searchInput.value.trim();
  if (q.length < MIN_CHARS) { hideSearchOverlay(); return; }
  debounceTimer = setTimeout(() => runAliasSearch(q), DEBOUNCE_MS);
});

searchInput.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { hideSearchOverlay(); searchInput.blur(); }
});

document.addEventListener('click', (e) => {
  if (!searchOverlay.contains(e.target) && e.target !== searchInput) hideSearchOverlay();
});

function showSearchOverlay() { searchOverlay.style.display = 'block'; }
function hideSearchOverlay()  { searchOverlay.style.display = 'none'; }

async function runAliasSearch(q) {
  try {
    const res = await fetch(`/api/search?q=${encodeURIComponent(q)}&matchMode=contains&includeAv=false`);
    if (!res.ok) return;
    const data = await res.json();
    renderSearchResults(data.actresses || [], q);
  } catch { /* ignore */ }
}

function renderSearchResults(actresses, query) {
  if (!actresses.length) {
    searchOverlay.innerHTML = '<div class="alias-search-empty">No actresses found</div>';
    showSearchOverlay();
    return;
  }

  searchOverlay.innerHTML = '';
  for (const a of actresses) {
    const row = document.createElement('div');
    row.className = 'alias-search-row';

    const aliasHtml = a.matchedAlias
      ? `<div class="alias-search-alias">a.k.a. ${highlight(a.matchedAlias, query)}</div>` : '';

    const coverHtml = a.coverUrl
      ? `<div class="alias-search-cover-wrap"><img class="alias-search-cover-img" src="${esc(a.coverUrl)}" alt="" loading="lazy"></div>`
      : '<div class="alias-search-cover-wrap"></div>';

    row.innerHTML =
        `<div class="alias-search-text">`
      + `<span class="alias-search-name">${highlight(a.canonicalName, query)}</span>`
      + aliasHtml
      + `<span class="alias-search-count">${a.titleCount} titles</span>`
      + `</div>`
      + coverHtml;

    row.addEventListener('click', () => {
      hideSearchOverlay();
      searchInput.value = '';
      openAliasModal(a.id);
    });
    searchOverlay.appendChild(row);
  }
  showSearchOverlay();
}

// ── Modal ──────────────────────────────────────────────────────────────────

let currentActressId = null;

async function openAliasModal(actressId) {
  currentActressId = actressId;
  modalLeft.innerHTML = '<div style="color:#444;font-size:0.8rem;padding:20px">Loading…</div>';
  tableBody.innerHTML = '';
  modalOverlay.style.display = 'flex';

  try {
    const res = await fetch(`/api/actresses/${actressId}`);
    if (!res.ok) throw new Error('Not found');
    const a = await res.json();
    renderModal(a);
  } catch (err) {
    modalLeft.innerHTML = '<div style="color:#ef4444;padding:20px;font-size:0.8rem">Failed to load actress.</div>';
  }
}

function renderModal(a) {
  modalTitle.textContent = a.canonicalName;

  // ── Left panel: actress card ───────────────────────────────────────────
  const coverInner = (a.coverUrls && a.coverUrls.length)
    ? `<div class="alias-card-cover-wrap"><img class="alias-card-cover-img" src="${esc(a.coverUrls[0])}" alt=""></div>`
    : '<div class="alias-card-cover-empty"></div>';
  const cover = `<div style="margin-bottom:16px">${coverInner}</div>`;

  const nameParts  = splitCanonical(a.canonicalName);
  const nameHtml   = nameParts.first
    ? `<span class="alias-card-first">${esc(nameParts.first)}</span><span class="alias-card-last">${esc(nameParts.last)}</span>`
    : `<span class="alias-card-last">${esc(nameParts.last)}</span>`;

  const grade = a.grade ? `<span class="alias-card-grade">${esc(a.grade)}</span>` : '';

  modalLeft.innerHTML = cover
    + `<div class="alias-card-name">${nameHtml}</div>`
    + `<div class="alias-card-meta">`
    + `<span class="alias-card-tier">${esc(a.tier || '')}</span>`
    + grade
    + `</div>`
    + (a.titleCount != null ? `<div class="alias-card-count">${a.titleCount} title${a.titleCount !== 1 ? 's' : ''}</div>` : '');

  // ── Right panel: alias table ───────────────────────────────────────────
  tableBody.innerHTML = '';
  const aliases = a.aliases || [];
  for (const alias of aliases) {
    appendAliasRow(alias.name);
  }
}

function splitCanonical(name) {
  if (!name) return { first: '', last: '' };
  const i = name.indexOf(' ');
  return i >= 0 ? { first: name.slice(0, i), last: name.slice(i + 1) } : { first: '', last: name };
}

function appendAliasRow(aliasName) {
  const { first, last } = splitCanonical(aliasName || '');
  const tr = document.createElement('tr');
  tr.innerHTML =
    `<td><input class="alias-input alias-input-first" type="text" value="${esc(first)}" placeholder="(optional)"></td>`
  + `<td><input class="alias-input alias-input-last"  type="text" value="${esc(last)}"  placeholder="Required"></td>`
  + `<td><button class="alias-row-remove" title="Remove" tabindex="-1">✕</button></td>`;

  tr.querySelector('.alias-row-remove').addEventListener('click', () => tr.remove());
  tableBody.appendChild(tr);
}

addBtn.addEventListener('click', () => {
  appendAliasRow('');
  const inputs = tableBody.querySelectorAll('tr:last-child .alias-input-last');
  if (inputs.length) inputs[0].focus();
});

// ── Save / Cancel ──────────────────────────────────────────────────────────

saveBtn.addEventListener('click', async () => {
  if (!currentActressId) return;

  const rows = Array.from(tableBody.querySelectorAll('tr'));
  const aliases = rows.map(tr => {
    const first = tr.querySelector('.alias-input-first')?.value.trim() || '';
    const last  = tr.querySelector('.alias-input-last')?.value.trim()  || '';
    return first ? `${first} ${last}` : last;
  }).filter(Boolean);

  saveBtn.disabled = true;
  saveBtn.textContent = 'Saving…';

  try {
    const res = await fetch(`/api/actresses/${currentActressId}/aliases`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ aliases }),
    });

    if (res.ok) {
      closeModal();
    } else {
      const data = await res.json().catch(() => ({}));
      const msg = data.error || `Error ${res.status}`;
      alert(`Could not save aliases:\n${msg}`);
    }
  } catch (err) {
    alert('Network error — aliases not saved.');
    console.error(err);
  } finally {
    saveBtn.disabled = false;
    saveBtn.textContent = 'Save';
  }
});

cancelBtn.addEventListener('click', closeModal);

modalOverlay.addEventListener('click', (e) => {
  if (e.target === modalOverlay) closeModal();
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && modalOverlay.style.display !== 'none') closeModal();
});

function closeModal() {
  modalOverlay.style.display = 'none';
  tableBody.innerHTML = '';
  modalLeft.innerHTML = '';
  currentActressId = null;
}
