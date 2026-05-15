// actresses/notes.js — Post-It Notes integration for the v2 actress-browse grid.
//
// Exports:
//   renderActressCardWithNotes(a)  — wraps renderActressCard; appends notes icon;
//                                    registers id for batch hydration
//   resetNotesState()              — clears pending-ids map (call on grid reset)
//   buildNotesFilterRow(containerEl, anchorEl, state, onFilterChange)
//                                  — injects the Notes filter chip row before anchorEl
//                                    inside containerEl; returns { show, hide, sync,
//                                    resetFilter } control object
//
// Design notes:
//   Icon placement: bottom-right corner of .acv2-portrait (position:absolute).
//   This avoids the .acv2-name text-overflow clip and keeps clear of the
//   tier badge (top-left corner). The click handler prevents card navigation.
//
//   Filter chip: injected as a <div class="act-notes-filter-row"> and shown
//   only when a grid mode is active (dashboard/studio hide it).
//
//   Tokens: /modules/notes/tokens.css is lazily injected once, idempotent.
//
//   Hydration: queueMicrotask flush after each loadMore() batch — same pattern
//   as the v1 actress-browse/notes.js implementation.

import { renderActressCard } from '../cards/actress-card.js';
import { notesIcon, openStickyModal, batchNotes } from '../../notes/index.js';

// ── Design tokens (idempotent <link> injection) ───────────────────────────

(function ensureTokens() {
  const LINK_ID = 'notes-tokens-css';
  if (document.getElementById(LINK_ID)) return;
  const link = document.createElement('link');
  link.id   = LINK_ID;
  link.rel  = 'stylesheet';
  link.href = '/modules/notes/tokens.css';
  document.head.appendChild(link);
})();

// ── Scoped styles (idempotent injection) ──────────────────────────────────

const STYLE_ID = 'v2-actress-browse-notes-styles';

(function ensureStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = `
/* ── Post-It Notes: v2 actress card icon ───────────────────────────────── */

.acv2-notes-wrap {
  position: absolute;
  bottom: 6px;
  right: 6px;
  z-index: 3;
  line-height: 0;
}

.acv2-notes-icon {
  cursor: pointer;
  opacity: 0.80;
  transition: opacity 0.12s ease, transform 0.1s ease;
  display: block;
  /* Slight drop shadow so the icon reads against busy portraits */
  filter: drop-shadow(0 1px 2px rgba(0,0,0,0.45));
}

.acv2-notes-icon:hover {
  opacity: 1;
  transform: scale(1.18);
}

/* ── Post-It Notes: v2 actress-browse filter chip row ──────────────────── */

.act-notes-filter-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0 4px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 2px;
}

.act-notes-chip {
  padding: 4px 12px;
  font-size: 11.5px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--bg-panel);
  color: var(--text-dim);
  cursor: pointer;
  font-family: inherit;
  transition: color var(--dur-fast, 0.1s) var(--ease-out, ease),
              background var(--dur-fast, 0.1s) var(--ease-out, ease),
              border-color var(--dur-fast, 0.1s) var(--ease-out, ease);
  white-space: nowrap;
}

.act-notes-chip:hover {
  color: var(--text);
  border-color: var(--border-strong);
  background: var(--bg-hover);
}

.act-notes-chip.on {
  background: var(--bg-active);
  color: var(--text);
  border-color: var(--border-strong);
  font-weight: 600;
}
`;
  document.head.appendChild(style);
})();

// ── Pending-hydration registry ────────────────────────────────────────────
// Map<actressId:string, iconWrapperEl:HTMLElement>
// Populated as cards are created; flushed on next microtask.

let _pending = new Map();
let _flushScheduled = false;

function _scheduleFlush() {
  if (_flushScheduled) return;
  _flushScheduled = true;
  queueMicrotask(_flush);
}

async function _flush() {
  _flushScheduled = false;
  if (_pending.size === 0) return;

  // Snapshot and clear — the grid may add more cards while we await.
  const snapshot = new Map(_pending);
  _pending.clear();

  const ids = [...snapshot.keys()];
  let notesMap;
  try {
    notesMap = await batchNotes('actress', ids);
  } catch (err) {
    console.error('[v2/actresses/notes] batchNotes failed', err);
    return;
  }

  for (const [id, wrapperEl] of snapshot) {
    if (!wrapperEl.isConnected) continue;
    const note = notesMap[id] || null;
    _populateIconWrapper(wrapperEl, id, note);
  }
}

// ── Icon wrapper helpers ──────────────────────────────────────────────────

function _makeIconWrapper(actressId) {
  const wrapper = document.createElement('span');
  wrapper.className = 'acv2-notes-wrap';
  wrapper.dataset.actressId = actressId;
  // Start as outline (no note); batch hydration fills in.
  _populateIconWrapper(wrapper, actressId, null);
  return wrapper;
}

function _populateIconWrapper(wrapper, actressId, note) {
  wrapper.innerHTML = '';

  const icon = notesIcon({
    filled: !!note,
    title: note ? note.body : 'Add note',
  });
  icon.classList.add('acv2-notes-icon');

  icon.addEventListener('click', async (e) => {
    // Prevent card navigation (<a href>).
    e.preventDefault();
    e.stopPropagation();

    // Resolve display name from the closest card element.
    const card = wrapper.closest('.acv2-card');
    const nameEl = card ? card.querySelector('.acv2-name') : null;
    const entityName = nameEl ? nameEl.textContent.trim() : actressId;

    const result = await openStickyModal({
      entityType:  'actress',
      entityId:    actressId,
      entityName,
      initialNote: note,
    });
    // result: null (cleared / cancelled with no pre-existing note) or NoteState.
    note = result;
    _populateIconWrapper(wrapper, actressId, note);
  });

  wrapper.appendChild(icon);
}

// ── Public: state reset (call on grid reset / mode change) ───────────────

export function resetNotesState() {
  _pending.clear();
  _flushScheduled = false;
}

// ── Public: wrapped card factory ─────────────────────────────────────────

export function renderActressCardWithNotes(a) {
  const card = renderActressCard(a);

  // Notes icon: bottom-right corner of .acv2-portrait.
  // .acv2-portrait is position:relative with overflow:hidden — the icon wrapper
  // sits inside it as an absolutely-positioned overlay (z-index:3, above scrim).
  const portraitEl = card.querySelector('.acv2-portrait');
  if (portraitEl) {
    const wrapper = _makeIconWrapper(a.id);
    portraitEl.appendChild(wrapper);

    // Register for the pending batch.
    _pending.set(a.id, wrapper);
    _scheduleFlush();
  }

  return card;
}

// ── Public: Notes filter chip row factory ────────────────────────────────
//
// Injects a <div class="act-notes-filter-row"> before `anchorEl` (which must
// be a child of `containerEl`).  Returns a control object { show, hide, sync }.
//
// Parameters:
//   containerEl   — the element that contains anchorEl (and will host the row)
//   anchorEl      — the row is inserted before this element
//   state         — shared state object; must have `notesFilter` property
//   onFilterChange — called after state.notesFilter is updated; callers reset + reload

const NOTES_FILTER_VALUES = [null, 'has_note', 'no_note'];
const NOTES_FILTER_LABELS = {
  null:     'Notes: Any',
  has_note: 'Notes: Has note',
  no_note:  'Notes: No note',
};

export function buildNotesFilterRow(containerEl, anchorEl, state, onFilterChange) {
  const row = document.createElement('div');
  row.className = 'act-notes-filter-row';
  row.style.display = 'none';

  const chip = document.createElement('button');
  chip.type = 'button';
  chip.className = 'act-notes-chip';
  chip.textContent = NOTES_FILTER_LABELS[null];
  row.appendChild(chip);

  containerEl.insertBefore(row, anchorEl);

  function sync() {
    const key = state.notesFilter || null;
    chip.textContent = NOTES_FILTER_LABELS[key];
    chip.classList.toggle('on', key !== null);
  }

  chip.addEventListener('click', () => {
    const currentIdx = NOTES_FILTER_VALUES.indexOf(state.notesFilter);
    const nextIdx = (currentIdx + 1) % NOTES_FILTER_VALUES.length;
    state.notesFilter = NOTES_FILTER_VALUES[nextIdx];
    sync();
    onFilterChange();
  });

  return {
    show() { row.style.display = ''; },
    hide() { row.style.display = 'none'; },
    sync,
    resetFilter() {
      state.notesFilter = null;
      sync();
    },
  };
}
