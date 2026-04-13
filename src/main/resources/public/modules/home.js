import { showView, updateBreadcrumb } from './grid.js';
import { pushNav } from './nav.js';
import { resetActressState } from './actress-browse.js';
import { createSearch } from './search.js';

// ── Portal search init ────────────────────────────────────────────────────

export function initPortalSearch() {
  const input   = document.getElementById('portal-search-input');
  const overlay = document.getElementById('portal-search-overlay');
  if (!input || !overlay) return;
  // No keyboard shortcuts for now — keyboard nav and Cmd+K deferred to a later pass.
  createSearch(input, overlay, { keyboardNav: true, globalShortcut: false, autoNavigate: false, twoColumn: true });
}

// ── showTitlesView ────────────────────────────────────────────────────────
export function showTitlesView() {
  pushNav({ view: 'titles' }, 'home');
  showView('titles');
  document.getElementById('actresses-btn')?.classList.remove('active');
  document.getElementById('titles-browse-btn')?.classList.remove('active');
  document.getElementById('title-collections-btn')?.classList.remove('active');
  resetActressState();
  updateBreadcrumb([]);
}
