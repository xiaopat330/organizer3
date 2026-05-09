// Actress Detail → Admin tab — entry point.
//
// Owns the lifecycle for the Admin tab content: data fetch, render,
// pagination state. The state machine for staged edits lives in
// `./state.js`; the per-card render in `./card.js`; commit/cancel
// orchestration in `./commit.js`.
//
// Public surface:
//   mountAdmin(actressId)       — call when the tab becomes visible
//   unmountAdmin()              — call when the tab is hidden
//   hasStagedChanges()          — for navigate-away interception (2e)
//   loadAdminPage(page)         — internal-ish; exported so the
//                                 pagination control (2c) can call it

import { setStatus } from '../utils.js';
import { renderCard, attachCardListeners } from './card.js';
import * as state from './state.js';

let currentActressId = null;
let currentPage = 1;
let totalPages = 0;
let pageSize = 5;

export async function mountAdmin(actressId) {
  if (actressId == null) return;
  if (actressId !== currentActressId) {
    state.reset(actressId);
    currentActressId = actressId;
    currentPage = 1;
  }
  await loadAdminPage(currentPage);
}

export function unmountAdmin() {
  // No-op for now. State stays alive so the user can switch to Catalog and
  // back without losing their place. State is reset on actress change.
}

export function hasStagedChanges() {
  return state.hasStagedChanges();
}

export async function loadAdminPage(page) {
  const view = document.getElementById('actress-detail-admin-view');
  if (!view) return;
  if (currentActressId == null) return;

  view.innerHTML = '<div class="admin-loading">Loading…</div>';

  try {
    const url = `/api/actresses/${currentActressId}/admin-titles?page=${page}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();

    currentPage = data.page;
    totalPages  = data.totalPages;
    pageSize    = data.pageSize;

    renderPage(data);
  } catch (err) {
    view.innerHTML = `<div class="admin-error">Failed to load admin titles: ${err.message}</div>`;
    setStatus('error');
  }
}

function renderPage(data) {
  const view = document.getElementById('actress-detail-admin-view');
  if (!view) return;

  if (!data.titles || data.titles.length === 0) {
    view.innerHTML = '<div class="admin-empty">No titles for this actress.</div>';
    return;
  }

  const cards = data.titles.map(t => {
    state.setCardData(t.code, t);
    return renderCard(t);
  }).join('');

  // Pagination control — placeholder until 2d. Includes total-pages info
  // so the user sees something useful even at this phase.
  const pagination = data.totalPages > 1
    ? `<div class="admin-pagination-stub">Page ${data.page} of ${data.totalPages}
         (pagination control — Phase 2d)</div>`
    : '';

  view.innerHTML = `
    <div class="admin-card-list">${cards}</div>
    ${pagination}
  `;

  // Attach event listeners for each card after setting innerHTML.
  data.titles.forEach(t => attachCardListeners(t.code));
}
