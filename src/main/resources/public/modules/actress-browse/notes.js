// actress-browse/notes.js — Post-It Notes integration for the v1 actress-browse grid.
//
// Exports:
//   makeActressCardWithNotes(a)  — wraps makeActressCard; appends notes icon; registers id
//   resetNotesState()            — clears pending-ids list (call on grid reset)
//
// Wiring model:
//   Each wrapped card appends a notes-icon wrapper element to .actress-card-name.
//   The icon wrapper is registered in a pending Map<actressId, iconWrapperEl>.
//   A debounced microtask-flush fires after all cards in a loadMore() batch are created,
//   calls batchNotes once, then updates each icon via DOM lookup.
//
// This module does NOT modify cards.js, grid.js, or any other legacy file.

import { makeActressCard } from '../cards.js';
import { notesIcon, openStickyModal, batchNotes } from '../notes/index.js';

// ── Scoped styles (idempotent injection) ──────────────────────────────────

const STYLE_ID = 'actress-browse-notes-styles';

function ensureStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = `
/* ── Post-It Notes: actress card icon ──────────────────────────────────── */

.actress-notes-icon-wrap {
  display: inline-flex;
  align-items: center;
  margin-left: 3px;
  flex-shrink: 0;
}

.actress-notes-icon {
  cursor: pointer;
  opacity: 0.75;
  transition: opacity 0.12s ease, transform 0.1s ease;
  display: block;
}

.actress-notes-icon:hover {
  opacity: 1;
  transform: scale(1.15);
}

/* ── Post-It Notes: actress-browse filter chip ──────────────────────────── */

.actress-notes-filter-row {
  /* Sits below the special-row in actress-landing; same flex row layout. */
}

.actress-notes-filter-chip {
  display: inline-flex;
  align-items: center;
  background: transparent;
  border: 1px solid #333;
  border-radius: 4px;
  padding: 4px 10px;
  font-size: 0.75rem;
  font-weight: 600;
  letter-spacing: 0.03em;
  color: #888;
  cursor: pointer;
  font-family: inherit;
  transition: background 0.12s ease, border-color 0.12s ease, color 0.12s ease;
  white-space: nowrap;
}

.actress-notes-filter-chip:hover {
  color: #bbb;
  border-color: #555;
  background: #131313;
}

.actress-notes-filter-chip.actress-notes-chip-active {
  color: #FFF59D;
  border-color: #FFEB3B;
  background: #1a1800;
}

.actress-notes-filter-chip.actress-notes-chip-active:hover {
  color: #fff7b0;
  border-color: #ffe040;
  background: #201c00;
}
`;
  document.head.appendChild(style);
}

ensureStyles();

// ── Pending-hydration registry ────────────────────────────────────────────
// Map<actressId:string, iconWrapperEl:HTMLElement>
// Populated as cards are created; flushed on next microtask.
let _pending = new Map();
let _flushScheduled = false;

function _scheduleFlush() {
  if (_flushScheduled) return;
  _flushScheduled = true;
  // Use queueMicrotask so the flush fires after all synchronous card
  // appends in a single loadMore() call finish, in one batch request.
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
    console.error('actress-browse/notes: batchNotes failed', err);
    return;
  }

  for (const [id, wrapperEl] of snapshot) {
    // wrapperEl may have been removed from DOM if the grid was reset mid-flight.
    if (!wrapperEl.isConnected) continue;
    const note = notesMap[id] || null;
    _updateIconWrapper(wrapperEl, note, id);
  }
}

// ── Icon wrapper helpers ──────────────────────────────────────────────────

function _makeIconWrapper(actressId, note) {
  const wrapper = document.createElement('span');
  wrapper.className = 'actress-notes-icon-wrap';
  wrapper.dataset.actressId = actressId;
  _populateIconWrapper(wrapper, actressId, note);
  return wrapper;
}

function _populateIconWrapper(wrapper, actressId, note) {
  wrapper.innerHTML = '';
  const icon = notesIcon({
    filled: !!note,
    title: note ? note.body : 'Add note',
  });
  icon.classList.add('actress-notes-icon');
  icon.addEventListener('click', async (e) => {
    e.preventDefault();
    e.stopPropagation();
    // Resolve actor name from closest card for the modal title
    const card = wrapper.closest('.actress-card');
    const entityName = card
      ? (card.querySelector('.actress-first-name')?.textContent || actressId)
      : actressId;
    const result = await openStickyModal({
      entityType: 'actress',
      entityId: actressId,
      entityName,
      initialNote: note,
    });
    // result: null (cleared/cancelled-no-note) or NoteState (saved)
    // If cancelled with existing note, result === note (same ref or same shape)
    // Either way, update the in-memory note and swap the icon.
    note = result;
    _populateIconWrapper(wrapper, actressId, note);
  });
  wrapper.appendChild(icon);
}

function _updateIconWrapper(wrapperEl, note, actressId) {
  _populateIconWrapper(wrapperEl, actressId, note);
}

// ── Public: state reset (call on grid reset / mode change) ────────────────

export function resetNotesState() {
  _pending.clear();
  _flushScheduled = false;
}

// ── Public: wrapped card factory ──────────────────────────────────────────

export function makeActressCardWithNotes(a) {
  const card = makeActressCard(a);

  // Notes icon: append to .actress-card-name (the icon row with fav/bm buttons).
  const nameEl = card.querySelector('.actress-card-name');
  if (nameEl) {
    // Stub: start with outline (no note); batch hydration fills it in.
    const wrapper = _makeIconWrapper(a.id, null);
    nameEl.appendChild(wrapper);

    // Register for the pending batch.
    _pending.set(a.id, wrapper);
    _scheduleFlush();
  }

  return card;
}
