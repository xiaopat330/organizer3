// notes.js — Post-It Notes integration for v1 title-browse grid cards.
//
// Exports:
//   injectNotesTokens()            — idempotently injects tokens.css <link>
//   decorateWithNotesIcon(card, t) — adds the sticky-note icon slot to a title grid card;
//                                    call from the allTitlesGrid makeCard callback only.
//   scheduleBatchHydration(ids)    — coalesces batchNotes calls; updates icons in-place.
//
// Strictly inside title-browse/. Does NOT modify cards.js or any other legacy module.
// Palette surfaces are excluded by construction: only the allTitlesGrid callback uses
// decorateWithNotesIcon, so dashboard tiles and compact cards are untouched.

import { notesIcon, openStickyModal, batchNotes } from '../notes/index.js';

// ── CSS tokens + scoped styles ────────────────────────────────────────────

const STYLE_ID = 'title-browse-notes-styles';

/**
 * Injects the notes/tokens.css <link> and scoped card/filter-chip styles.
 * Safe to call multiple times (idempotent).
 */
export function injectNotesTokens() {
  // tokens.css
  if (!document.getElementById('notes-tokens-css')) {
    const link = document.createElement('link');
    link.id   = 'notes-tokens-css';
    link.rel  = 'stylesheet';
    link.href = '/modules/notes/tokens.css';
    document.head.appendChild(link);
  }

  // Scoped styles
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = `
/* ── Post-It Notes: title-browse card icon slot ─────────────────────────── */

.card-notes-slot {
  display: inline-flex;
  align-items: center;
  margin-left: 3px;
  cursor: pointer;
  opacity: 0.75;
  transition: opacity 0.12s ease, transform 0.1s ease;
  flex-shrink: 0;
  vertical-align: middle;
}

.card-notes-slot:hover {
  opacity: 1;
  transform: scale(1.15);
}

/* ── Post-It Notes: title-browse filter chip ─────────────────────────────── */

.title-notes-filter-chip {
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

.title-notes-filter-chip:hover {
  color: #bbb;
  border-color: #555;
  background: #131313;
}

.title-notes-filter-chip.title-notes-chip-active {
  color: var(--postit-yellow, #FFF59D);
  border-color: var(--postit-yellow-edge, #FFEB3B);
  background: #1a1800;
}

.title-notes-filter-chip.title-notes-chip-active:hover {
  color: #fff7b0;
  border-color: #ffe040;
  background: #201c00;
}
`;
  document.head.appendChild(style);
}

// ── Per-card note state ───────────────────────────────────────────────────
// Keyed by t.code so batch hydration can find and update cards already in DOM.
const _cardNotes = new Map(); // code → NoteState | null

// ── Icon decoration ───────────────────────────────────────────────────────

/**
 * Adds the sticky-note icon to a title grid card's `.title-code` row.
 * Click → opens the sticky modal; on resolve, updates the icon in place.
 *
 * @param {HTMLElement} card  - element returned by makeTitleCard
 * @param {object}      t     - title data (needs t.code, t.titleEnglish, etc.)
 */
export function decorateWithNotesIcon(card, t) {
  const codeRow = card.querySelector('.title-code');
  if (!codeRow) return;

  // Seed note state as null; batch hydration updates it asynchronously.
  _cardNotes.set(t.code, null);

  const slot = document.createElement('span');
  slot.className = 'card-notes-slot';

  _renderSlotIcon(slot, null, t.code);

  slot.addEventListener('click', async (e) => {
    e.preventDefault();
    e.stopPropagation();
    const currentNote = _cardNotes.get(t.code) ?? null;
    const entityName  = t.titleEnglish || t.titleOriginalEn || t.titleOriginal || t.code;
    const result = await openStickyModal({
      entityType:  'title',
      entityId:    t.code,
      entityName,
      initialNote: currentNote,
    });
    // result: new NoteState (saved with content) | null (cleared/empty) | initialNote (cancelled)
    // Only update if state actually changed
    if (result !== currentNote) {
      _cardNotes.set(t.code, result);
      _renderSlotIcon(slot, result, t.code);
    }
  });

  codeRow.appendChild(slot);
}

/**
 * Re-renders the SVG icon inside a slot. Kept as a standalone function so
 * batch hydration can also call it.
 */
function _renderSlotIcon(slot, note, _code) {
  slot.innerHTML = '';
  const filled = note !== null;
  const tooltipText = filled ? note.body : 'Add note';
  const svg = notesIcon({ filled, title: tooltipText });
  slot.appendChild(svg);
  slot.title = filled ? note.body : '';
}

// ── Batch hydration ───────────────────────────────────────────────────────
// We collect IDs from every synchronous makeCard call, then flush as one
// batchNotes request on the next microtask — same pattern as actress-browse.

let _pending = new Set();
let _flushScheduled = false;

/**
 * Queues title codes for batch note hydration. Multiple calls within the
 * same synchronous turn are coalesced into a single API request.
 *
 * @param {string[]} ids
 */
export function scheduleBatchHydration(ids) {
  if (!ids || ids.length === 0) return;
  for (const id of ids) _pending.add(id);
  if (!_flushScheduled) {
    _flushScheduled = true;
    queueMicrotask(_flush);
  }
}

async function _flush() {
  _flushScheduled = false;
  if (_pending.size === 0) return;

  // Snapshot and clear so cards added while we await go into the next batch.
  const ids = [..._pending];
  _pending.clear();

  let notesMap;
  try {
    notesMap = await batchNotes('title', ids);
  } catch (err) {
    console.error('title-browse/notes: batchNotes failed', err);
    return;
  }

  for (const id of ids) {
    const note = notesMap[id] ?? null;
    _cardNotes.set(id, note);
    const card = document.querySelector(`.card[data-code="${CSS.escape(id)}"]`);
    if (!card) continue;
    const slot = card.querySelector('.card-notes-slot');
    if (!slot) continue;
    _renderSlotIcon(slot, note, id);
  }
}
