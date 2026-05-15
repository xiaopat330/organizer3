// notes.js — Post-It Notes integration for v2 titles grid cards.
//
// Exports:
//   injectNotesTokens()              — idempotently injects tokens.css <link> + scoped styles
//   decorateWithNotesIcon(card, t)   — adds sticky-note icon slot to a v2 title grid card;
//                                      call after renderTitleCard() returns.
//   scheduleBatchHydration(ids)      — coalesces batchNotes calls; updates icons in-place.
//   notesChipHtml(currentFilter)     — returns HTML for the tri-state filter chip.
//   wireNotesChip(chipEl, state, onCycle) — wires click → cycle → callback.
//
// Palette cards are excluded by construction: only callers inside the titles grid
// path use decorateWithNotesIcon; the palette popover does not.
//
// CSS namespace: tcv2- prefix (consistent with v2 card conventions).

import { notesIcon, openStickyModal, batchNotes } from '../../notes/index.js';

// ── CSS tokens + scoped styles ────────────────────────────────────────────

const STYLE_ID = 'tcv2-notes-styles';

/**
 * Injects notes/tokens.css and scoped card/chip styles.
 * Safe to call multiple times (idempotent).
 */
export function injectNotesTokens() {
  if (!document.getElementById('notes-tokens-css')) {
    const link = document.createElement('link');
    link.id   = 'notes-tokens-css';
    link.rel  = 'stylesheet';
    link.href = '/modules/notes/tokens.css';
    document.head.appendChild(link);
  }

  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = `
/* ── Post-It Notes: v2 title card icon slot ─────────────────────────── */

.tcv2-notes-slot {
  display: inline-flex;
  align-items: center;
  margin-left: 3px;
  cursor: pointer;
  opacity: 0.75;
  transition: opacity 0.12s ease, transform 0.1s ease;
  flex-shrink: 0;
  vertical-align: middle;
}

.tcv2-notes-slot:hover {
  opacity: 1;
  transform: scale(1.15);
}

/* ── Post-It Notes: v2 title-browse filter chip ──────────────────────── */

.tcv2-notes-chip {
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

.tcv2-notes-chip:hover {
  color: #bbb;
  border-color: #555;
  background: #131313;
}

.tcv2-notes-chip.tcv2-notes-chip--active {
  color: var(--postit-yellow, #FFF59D);
  border-color: var(--postit-yellow-edge, #FFEB3B);
  background: #1a1800;
}

.tcv2-notes-chip.tcv2-notes-chip--active:hover {
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
 * Adds the sticky-note icon to a v2 title card's `.tcv2-code` row.
 * Click → opens the sticky modal; on resolve, updates the icon in place.
 *
 * @param {HTMLElement} card  - element returned by renderTitleCard
 * @param {object}      t     - title data (needs t.code, t.titleEnglish, etc.)
 */
export function decorateWithNotesIcon(card, t) {
  const codeRow = card.querySelector('.tcv2-code');
  if (!codeRow) return;

  // Seed note state as null; batch hydration updates it asynchronously.
  _cardNotes.set(t.code, null);

  const slot = document.createElement('span');
  slot.className = 'tcv2-notes-slot';

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
 * Re-renders the SVG icon inside a slot.
 * Also called by batch hydration after the notes map arrives.
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
// Collect IDs from every synchronous makeCard call, flush as one batchNotes
// request on the next microtask — same pattern as v1 title-browse/notes.js.

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

  const ids = [..._pending];
  _pending.clear();

  let notesMap;
  try {
    notesMap = await batchNotes('title', ids);
  } catch (err) {
    console.error('[v2/titles/notes] batchNotes failed', err);
    return;
  }

  for (const id of ids) {
    const note = notesMap[id] ?? null;
    _cardNotes.set(id, note);
    const card = document.querySelector(`.tcv2-card[data-code="${CSS.escape(id)}"]`);
    if (!card) continue;
    const slot = card.querySelector('.tcv2-notes-slot');
    if (!slot) continue;
    _renderSlotIcon(slot, note, id);
  }
}

// ── Filter chip helpers ───────────────────────────────────────────────────

/**
 * Returns the HTML string for the tri-state Notes filter chip.
 *
 * @param {string|null} currentFilter  null | 'has_note' | 'no_note'
 * @returns {string}
 */
export function notesChipHtml(currentFilter) {
  const val   = currentFilter || '';
  const label = val === 'has_note' ? 'Notes: Has'
               : val === 'no_note'  ? 'Notes: None'
               :                      'Notes: Any';
  const activeClass = val ? ' tcv2-notes-chip--active' : '';
  return `<button type="button" class="tcv2-notes-chip${activeClass}" id="tcv2-notes-chip" data-notes-value="${val}" title="Filter by note">${label}</button>`;
}

/**
 * Wires the click handler for the notes filter chip.
 * Cycles: Any → Has → None → Any.
 *
 * @param {HTMLElement}            chipEl     — the chip button
 * @param {{ notesFilter: string|null }} state — mutable state object
 * @param {Function}               onCycle    — called after state update; receives new filter value
 */
export function wireNotesChip(chipEl, state, onCycle) {
  chipEl.addEventListener('click', () => {
    const cycle = [null, 'has_note', 'no_note'];
    const idx = cycle.indexOf(state.notesFilter);
    state.notesFilter = cycle[(idx + 1) % cycle.length];
    onCycle(state.notesFilter);
  });
}
