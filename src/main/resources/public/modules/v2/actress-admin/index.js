// v2 Admin tab — entry point.
//
// Owns the lifecycle for the Admin tab content: data fetch, render,
// pagination state. Wired into v2/actress-detail.js via:
//   mountAdminTab(panelEl, actressId)  — call when the tab becomes visible
//   unmountAdminTab()                  — call when the tab is hidden
//
// The panelEl is given id="actress-detail-admin-view" so that inner DOM
// queries (document.getElementById) from sub-modules continue to work
// exactly as in the legacy surface.
//
// Public surface:
//   mountAdminTab(panelEl, actressId)
//   unmountAdminTab()
//   hasStagedChanges()            — for navigate-away interception
//   loadAdminPage(page)           — also called by pagination control
//   confirmDiscardIfStaged()      — re-exported from nav-guard for actress-detail.js

import { renderCard, attachCardListeners } from './card.js';
import { renderPagination, attachPaginationListeners } from './pagination.js';
import {
  confirmDiscardIfStaged,
  installBeforeUnload,
  uninstallBeforeUnload,
} from './nav-guard.js';
import * as state from './state.js';

// Re-export so actress-detail.js can call the guard on actress switch / tab leave.
export { confirmDiscardIfStaged } from './nav-guard.js';

const ADMIN_VIEW_ID = 'actress-detail-admin-view';

let currentActressId = null;
let currentPage = 1;
let totalPages  = 0;
let pageSize    = 5;

export async function mountAdminTab(panelEl, actressId) {
  if (actressId == null || !panelEl) return;

  // Give the panel element the ID that sub-modules use for DOM queries.
  panelEl.id = ADMIN_VIEW_ID;

  if (actressId !== currentActressId) {
    state.reset(actressId);
    currentActressId = actressId;
    currentPage = 1;
  }
  installBeforeUnload();
  await loadAdminPage(currentPage);
}

export function unmountAdminTab() {
  // State stays alive so the user can switch to Catalog and back without
  // losing their place. State is reset on actress change. The beforeunload
  // backstop is removed when leaving the tab — re-installed on next mount.
  uninstallBeforeUnload();
}

export function hasStagedChanges() {
  return state.hasStagedChanges();
}

export async function loadAdminPage(page) {
  const view = document.getElementById(ADMIN_VIEW_ID);
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
    const view2 = document.getElementById(ADMIN_VIEW_ID);
    if (view2) view2.innerHTML = `<div class="admin-error">Failed to load admin titles: ${err.message}</div>`;
  }
}

function renderPage(data) {
  const view = document.getElementById(ADMIN_VIEW_ID);
  if (!view) return;

  if (!data.titles || data.titles.length === 0) {
    view.innerHTML = '<div class="admin-empty">No titles for this actress.</div>';
    return;
  }

  const cards = data.titles.map(t => {
    state.setCardData(t.code, t);
    return renderCard(t);
  }).join('');

  view.innerHTML = `
    <div class="admin-card-list">${cards}</div>
    ${renderPagination(data.page, data.totalPages, data.pageSize)}
  `;

  data.titles.forEach(t => attachCardListeners(t.code));
  attachPaginationListeners(data.page, data.totalPages, data.pageSize, navigateToPage);
}

// Navigate-away guard for in-tab pagination. Tab/actress switches are
// guarded at their entry points in actress-detail.js.
async function navigateToPage(targetPage) {
  if (targetPage === currentPage) return;
  const ok = await confirmDiscardIfStaged();
  if (!ok) return;
  loadAdminPage(targetPage);
}
