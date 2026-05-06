// utilities-pending-kanji.js
// Tools → Pending Kanji: aggregate view of every unresolved kanji stage name.
// API: showPendingKanjiView() / hidePendingKanjiView()

import { esc } from './utils.js';
import { mount as mountModal } from './near-miss-modal.js';

const view = document.getElementById('tools-pending-kanji-view');

let _listenerRegistered = false;

// ── Public API ────────────────────────────────────────────────────────────

export function showPendingKanjiView() {
  view.style.display = 'block';
  registerResolvedListener();
  loadAndRender();
}

export function hidePendingKanjiView() {
  view.style.display = 'none';
}

// ── Listener (idempotent — only registered once) ──────────────────────────

function registerResolvedListener() {
  if (_listenerRegistered) return;
  _listenerRegistered = true;
  window.addEventListener('near-miss-resolved', () => {
    if (view.style.display !== 'none') loadAndRender();
  });
}

// ── Data + render ─────────────────────────────────────────────────────────

async function loadAndRender() {
  view.innerHTML = '<div class="upk-loading">Loading…</div>';
  try {
    const res = await fetch('/api/curation/pending-kanji');
    if (!res.ok) {
      view.innerHTML = `<div class="upk-error">Failed to load (HTTP ${res.status}).</div>`;
      return;
    }
    const rows = await res.json();
    renderTable(rows);
  } catch (err) {
    view.innerHTML = '<div class="upk-error">Network error loading pending kanji.</div>';
    console.error('pending-kanji load failed', err);
  }
}

function renderTable(rows) {
  if (!rows || rows.length === 0) {
    view.innerHTML = '<div class="upk-empty">No pending kanji — all drafts resolved.</div>';
    return;
  }

  const table = document.createElement('table');
  table.className = 'upk-table';
  table.innerHTML = `
    <thead>
      <tr>
        <th>Kanji</th>
        <th>Translation</th>
        <th>Drafts</th>
        <th>Oldest seen</th>
        <th></th>
      </tr>
    </thead>`;

  const tbody = document.createElement('tbody');
  rows.forEach(row => tbody.appendChild(buildRow(row)));
  table.appendChild(tbody);

  view.innerHTML = '';
  view.appendChild(table);
}

async function translateNow(kanji, transTd, translateNowBtn) {
  translateNowBtn.disabled = true;
  translateNowBtn.textContent = 'Translating…';
  try {
    const res = await fetch('/api/translation/stage-name-translate-now', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ kanji }),
    });
    if (!res.ok) {
      translateNowBtn.textContent = 'Translate now';
      translateNowBtn.disabled = false;
      return;
    }
    const data = await res.json();
    if (data.status === 'ready' && data.romaji) {
      transTd.textContent = data.romaji;
      translateNowBtn.remove();
    } else {
      translateNowBtn.textContent = 'Translate now';
      translateNowBtn.disabled = false;
    }
  } catch {
    translateNowBtn.textContent = 'Translate now';
    translateNowBtn.disabled = false;
  }
}

function buildRow(row) {
  const tr = document.createElement('tr');
  tr.className = 'upk-row';

  const kanjiTd = document.createElement('td');
  kanjiTd.className = 'upk-kanji-cell';
  kanjiTd.textContent = row.kanji;

  const transTd = document.createElement('td');
  transTd.className = 'upk-trans-cell';
  const sug = row.suggestion || {};
  if (sug.status === 'ready' && sug.romaji) {
    transTd.textContent = sug.romaji;
  } else if (sug.status === 'queued') {
    transTd.innerHTML = '<span class="upk-badge-translating">Translating…</span>';
  } else {
    transTd.innerHTML = '<span class="upk-badge-missing">Not translated</span>';
  }

  // "Translate now" button for queued or missing rows (not ready)
  if (sug.status !== 'ready') {
    const translateNowBtn = document.createElement('button');
    translateNowBtn.type = 'button';
    translateNowBtn.className = 'upk-translate-now-btn';
    translateNowBtn.textContent = 'Translate now';
    translateNowBtn.addEventListener('click', () => translateNow(row.kanji, transTd, translateNowBtn));
    transTd.appendChild(translateNowBtn);
  }

  const countTd = document.createElement('td');
  countTd.className = 'upk-count-cell';
  countTd.textContent = row.count;

  const dateTd = document.createElement('td');
  dateTd.className = 'upk-date-cell';
  dateTd.textContent = row.oldestSeen ? fmtDate(row.oldestSeen) : '—';

  const actionTd = document.createElement('td');
  actionTd.className = 'upk-action-cell';
  const resolveBtn = document.createElement('button');
  resolveBtn.type = 'button';
  resolveBtn.className = 'upk-resolve-btn';
  resolveBtn.textContent = 'Resolve';
  resolveBtn.addEventListener('click', () => openModal(row.kanji));
  actionTd.appendChild(resolveBtn);

  tr.appendChild(kanjiTd);
  tr.appendChild(transTd);
  tr.appendChild(countTd);
  tr.appendChild(dateTd);
  tr.appendChild(actionTd);
  return tr;
}

function openModal(kanji) {
  let mount = document.getElementById('near-miss-modal-mount');
  if (!mount) {
    mount = document.createElement('div');
    mount.id = 'near-miss-modal-mount';
    document.body.appendChild(mount);
  }
  // primarySlug is null: Tools-page entry — backend auto-picks oldest sibling (spec §4.4 outcome B).
  mountModal(mount, { kanji });
}

function fmtDate(iso) {
  const d = new Date(iso);
  if (isNaN(d)) return iso;
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}
