import { showView, updateBreadcrumb } from './grid.js';
import { pushNav } from './nav.js';
import { resetActressState } from './actress-browse.js';
import { createSearch } from './search.js';

const FILTER_STORAGE_KEY = 'portal-search-filters';
const ALL_CATEGORIES = ['actresses', 'titles', 'labels', 'studios'];

// ── Portal search filter toggles ──────────────────────────────────────────

function loadFilterState() {
  try {
    const saved = JSON.parse(localStorage.getItem(FILTER_STORAGE_KEY));
    if (saved && typeof saved === 'object') return saved;
  } catch { /* ignore */ }
  return Object.fromEntries(ALL_CATEGORIES.map(c => [c, true]));
}

function saveFilterState(state) {
  localStorage.setItem(FILTER_STORAGE_KEY, JSON.stringify(state));
}

// ── Portal search init ────────────────────────────────────────────────────

export function initPortalSearch() {
  const input      = document.getElementById('portal-search-input');
  const overlay    = document.getElementById('portal-search-overlay');
  const clearBtn   = document.getElementById('portal-search-clear');
  const filtersEl  = document.getElementById('portal-search-filters');
  if (!input || !overlay) return;

  // ── Clear button ────────────────────────────────────────────────────────
  if (clearBtn) {
    input.addEventListener('input', () => {
      clearBtn.style.display = input.value ? 'flex' : 'none';
    });
    clearBtn.addEventListener('click', () => {
      input.value = '';
      clearBtn.style.display = 'none';
      overlay.style.display = 'none';
      input.focus();
    });
  }

  // ── Restore saved toggle state ──────────────────────────────────────────
  const state = loadFilterState();

  if (filtersEl) {
    const toggleEntries = Array.from(
      filtersEl.querySelectorAll('.portal-filter-toggle')
    ).map(label => ({
      label,
      category: label.dataset.category,
      checkbox: label.querySelector('input[type=checkbox]'),
    }));

    // Disable the sole active toggle so it can't be turned off.
    function updateDisabledState() {
      const activeCount = toggleEntries.filter(e => e.checkbox?.checked).length;
      toggleEntries.forEach(({ checkbox, label }) => {
        const isLast = activeCount === 1 && checkbox?.checked;
        if (checkbox) checkbox.disabled = isLast;
        label.classList.toggle('portal-filter-toggle-locked', isLast);
      });
    }

    toggleEntries.forEach(({ label, category, checkbox }) => {
      if (checkbox && category in state) checkbox.checked = state[category];

      checkbox?.addEventListener('change', () => {
        state[category] = checkbox.checked;
        saveFilterState(state);
        updateDisabledState();
        // Re-trigger search so results update immediately
        input.dispatchEvent(new Event('input'));
      });
    });

    updateDisabledState();
  }

  const getEnabledCategories = () =>
    new Set(ALL_CATEGORIES.filter(c => state[c] !== false));

  // No keyboard shortcuts for now — keyboard nav and Cmd+K deferred to a later pass.
  createSearch(input, overlay, {
    keyboardNav: true,
    globalShortcut: false,
    autoNavigate: false,
    twoColumn: true,
    getEnabledCategories,
  });
}

// ── showTitlesView ────────────────────────────────────────────────────────
export function showTitlesView() {
  pushNav({ view: 'titles' }, 'home');
  showView('titles');
  document.getElementById('actresses-btn')?.classList.remove('active');
  document.getElementById('titles-browse-btn')?.classList.remove('active');
  document.getElementById('title-collections-btn')?.classList.remove('active');
  document.getElementById('av-btn')?.classList.remove('active');
  resetActressState();
  updateBreadcrumb([]);
}
