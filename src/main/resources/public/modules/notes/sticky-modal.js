// sticky-modal.js — Yellow sticky-note editor modal (§5.2)
//
// openStickyModal({ entityType, entityId, entityName, initialNote })
//   → Promise<NoteState | null>
//
// null means: cleared, cancelled with no existing note, or saved with empty body.
// Resolves with the new NoteState when saved with content.
// Resolves with the unchanged initialNote (or null) when cancelled:
//   - Cancel/Esc with no initial note      → null
//   - Cancel/Esc with an existing note     → initialNote (caller state unchanged)
//
// The modal injects its own <style> tag on first use (idempotent).
// No external CSS file required — importers need only import this module.

import { putNote, deleteNote } from './api-client.js';
import { attachCharCounter } from './char-counter.js';

const MAX_CHARS = 280;
const STYLE_ID  = 'notes-sticky-modal-styles';

// ── Style injection (idempotent) ──────────────────────────────────────────

function ensureStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = `
/* ── Notes sticky-note modal (sticky-modal.js) ────────────────────────── */

.nsm-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 900;
}

.nsm-card {
  background: var(--postit-yellow, #FFF59D);
  color: var(--postit-ink, #1A1A1A);
  border: none;
  border-radius: 2px;
  width: 360px;
  max-width: calc(100vw - 32px);
  box-shadow: var(--postit-shadow-modal, 0 4px 16px rgba(0, 0, 0, 0.25));
  padding: 18px 20px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  /* No rotation on modal — spec §5.0: skip in card popovers/modals */
}

.nsm-title {
  font-size: 0.88rem;
  font-weight: 600;
  color: var(--postit-ink, #1A1A1A);
  margin: 0;
  padding: 0;
  border-bottom: 1px solid var(--postit-yellow-edge, #FFEB3B);
  padding-bottom: 8px;
}

.nsm-textarea {
  width: 100%;
  box-sizing: border-box;
  background: transparent;
  border: 1px solid var(--postit-yellow-edge, #FFEB3B);
  border-radius: 2px;
  color: var(--postit-ink, #1A1A1A);
  font-family: inherit;
  font-size: 0.9rem;
  line-height: 1.5;
  padding: 8px;
  resize: vertical;
  outline: none;
  min-height: 80px;
}

.nsm-textarea:focus {
  border-color: var(--postit-ink, #1A1A1A);
}

.nsm-textarea::placeholder {
  color: rgba(26, 26, 26, 0.45);
}

.nsm-counter {
  font-size: 0.78rem;
  color: var(--postit-counter-ok, var(--postit-ink, #1A1A1A));
  text-align: right;
  margin-top: -4px;
}

.nsm-counter.over-limit {
  color: var(--postit-counter-over, #D32F2F);
  font-weight: 600;
}

.nsm-footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

/* All buttons share dark text on yellow, hover underline, no accent colors */
.nsm-btn {
  background: none;
  border: none;
  color: var(--postit-ink, #1A1A1A);
  cursor: pointer;
  font-size: 0.85rem;
  font-family: inherit;
  padding: 4px 6px;
  border-radius: 2px;
  text-decoration: none;
}

.nsm-btn:hover {
  text-decoration: underline;
}

.nsm-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
  text-decoration: none;
}

/* Inline clear-confirm sits left of the spacer */
.nsm-clear-confirm {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.82rem;
  color: var(--postit-ink, #1A1A1A);
  margin-right: auto;
}

.nsm-clear-label {
  /* "Clear?" text */
}
`;
  document.head.appendChild(style);
}

// ── Modal state ───────────────────────────────────────────────────────────

let _activeBackdrop = null;

function closeActiveModal() {
  if (_activeBackdrop) {
    _activeBackdrop.remove();
    _activeBackdrop = null;
  }
}

// ── Public API ────────────────────────────────────────────────────────────

/**
 * Opens the sticky-note editor modal.
 *
 * @param {object} opts
 * @param {'actress'|'title'} opts.entityType
 * @param {string}            opts.entityId
 * @param {string}            opts.entityName    - displayed in the title row
 * @param {NoteState|null}    opts.initialNote   - pre-populate textarea; null = empty
 * @returns {Promise<NoteState|null>}
 *   Resolves with:
 *   - new NoteState after a successful save with content
 *   - null if cleared, cancelled with no initial note, or saved with empty body
 *   - initialNote if cancelled/Esc'd while an existing note was present
 */
export function openStickyModal({ entityType, entityId, entityName, initialNote = null }) {
  ensureStyles();
  closeActiveModal(); // only one modal at a time

  return new Promise((resolve) => {
    // ── Build DOM ─────────────────────────────────────────────────────────

    const backdrop = document.createElement('div');
    backdrop.className = 'nsm-backdrop';
    _activeBackdrop = backdrop;

    const card = document.createElement('div');
    card.className = 'nsm-card';
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');
    card.setAttribute('aria-labelledby', 'nsm-title');

    const titleEl = document.createElement('h2');
    titleEl.className = 'nsm-title';
    titleEl.id = 'nsm-title';
    titleEl.textContent = `Note for ${entityName}`;

    const textarea = document.createElement('textarea');
    textarea.className = 'nsm-textarea';
    textarea.rows = 4;
    textarea.maxLength = MAX_CHARS * 2; // soft cap; hard cap is server-side
    textarea.placeholder = 'Add a note…';
    textarea.value = initialNote ? initialNote.body : '';

    const counterEl = document.createElement('div');
    counterEl.className = 'nsm-counter';

    const footer = document.createElement('div');
    footer.className = 'nsm-footer';

    // Buttons (built after state vars are established)
    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'nsm-btn';
    cancelBtn.textContent = 'Cancel';

    const saveBtn = document.createElement('button');
    saveBtn.type = 'button';
    saveBtn.className = 'nsm-btn';
    saveBtn.textContent = 'Save';

    // ── Clear button (only shown when a note exists) ───────────────────

    let clearConfirmVisible = false;

    function renderFooter() {
      footer.innerHTML = '';
      if (initialNote && !clearConfirmVisible) {
        const clearBtn = document.createElement('button');
        clearBtn.type = 'button';
        clearBtn.className = 'nsm-btn';
        clearBtn.textContent = 'Clear';
        clearBtn.style.marginRight = 'auto'; // push Cancel/Save to right
        clearBtn.addEventListener('click', () => {
          clearConfirmVisible = true;
          renderFooter();
        });
        footer.appendChild(clearBtn);
      } else if (initialNote && clearConfirmVisible) {
        // Inline confirm: "Clear? [Yes] [No]"
        const confirm = document.createElement('div');
        confirm.className = 'nsm-clear-confirm';

        const label = document.createElement('span');
        label.className = 'nsm-clear-label';
        label.textContent = 'Clear?';

        const yesBtn = document.createElement('button');
        yesBtn.type = 'button';
        yesBtn.className = 'nsm-btn';
        yesBtn.textContent = 'Yes';
        yesBtn.addEventListener('click', async () => {
          try {
            await deleteNote(entityType, entityId);
          } catch (err) {
            console.error('notes: deleteNote failed', err);
          }
          cleanup();
          resolve(null);
        });

        const noBtn = document.createElement('button');
        noBtn.type = 'button';
        noBtn.className = 'nsm-btn';
        noBtn.textContent = 'No';
        noBtn.addEventListener('click', () => {
          clearConfirmVisible = false;
          renderFooter();
        });

        confirm.appendChild(label);
        confirm.appendChild(yesBtn);
        confirm.appendChild(noBtn);
        footer.appendChild(confirm);
      }

      footer.appendChild(cancelBtn);
      footer.appendChild(saveBtn);
    }

    // ── Char counter & Save disable ───────────────────────────────────────

    const detachCounter = attachCharCounter(textarea, counterEl, { max: MAX_CHARS });

    function updateSaveState() {
      const over = textarea.value.length > MAX_CHARS;
      saveBtn.disabled = over;
    }
    textarea.addEventListener('input', updateSaveState);

    // ── Actions ───────────────────────────────────────────────────────────

    function cleanup() {
      detachCounter();
      backdrop.removeEventListener('click', onBackdropClick);
      document.removeEventListener('keydown', onKeydown);
      closeActiveModal();
    }

    cancelBtn.addEventListener('click', () => {
      cleanup();
      // Cancel: return unchanged state — initialNote if present, else null
      resolve(initialNote);
    });

    saveBtn.addEventListener('click', async () => {
      const body = textarea.value;
      let result;
      try {
        result = await putNote(entityType, entityId, body);
      } catch (err) {
        console.error('notes: putNote failed', err);
        return; // leave modal open on error
      }
      cleanup();
      resolve(result); // null if server deleted (empty body), NoteState otherwise
    });

    // ── Keyboard shortcuts ────────────────────────────────────────────────

    function onKeydown(e) {
      if (e.key === 'Escape') {
        e.preventDefault();
        cleanup();
        resolve(initialNote);
      } else if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        if (!saveBtn.disabled) saveBtn.click();
      }
    }

    // Click outside card cancels (same as Esc)
    function onBackdropClick(e) {
      if (e.target === backdrop) {
        cleanup();
        resolve(initialNote);
      }
    }

    // ── Wire up & render ──────────────────────────────────────────────────

    document.addEventListener('keydown', onKeydown);
    backdrop.addEventListener('click', onBackdropClick);

    renderFooter();
    updateSaveState();

    card.appendChild(titleEl);
    card.appendChild(textarea);
    card.appendChild(counterEl);
    card.appendChild(footer);
    backdrop.appendChild(card);
    document.body.appendChild(backdrop);

    // Focus textarea after render
    textarea.focus();
    // Move cursor to end
    textarea.setSelectionRange(textarea.value.length, textarea.value.length);
  });
}
