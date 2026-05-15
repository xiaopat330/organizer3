// actress-detail-notes.js — Sticky-note panel for the v2 actress detail left column.
//
// Usage (called once after loadAndRender):
//   mountActressNotePanel(actressId);
//
// Appends (or reuses) #ad-note-panel as the LAST CHILD of aside.ad-rail
// (the left column holding the cover photo and sidebar sections).
//
// Visual treatment per §5.0 / §5.3 of PROPOSAL_POST_IT_NOTES.md:
//   - Yellow surface (--postit-yellow), ink text (--postit-ink)
//   - Soft drop shadow (--postit-shadow)
//   - Slight rotation (--postit-rotation, ≈ -1deg) — straightens on edit
//   - Inline edit (NOT the modal — that is for card flows)
//   - Char counter via attachCharCounter from shared notes module
//   - Edited-at line (present state only), relative + absolute tooltip
//
// CSS classes reuse the actress-detail/notes-panel.css rules (loaded in the
// v2 host page alongside /modules/notes/tokens.css).

import { getNote, putNote, deleteNote, attachCharCounter } from '../notes/index.js';

const PANEL_ID  = 'ad-note-panel';
const MAX_CHARS = 280;

// ── Relative-time helper (epoch millis → "edited X ago") ─────────────────
//
// Produces the "edited …" phrasing expected by §5.3.
// The note's updated_at is epoch millis (not an ISO string).
//
function editedAgo(epochMillis) {
  const seconds = Math.floor((Date.now() - epochMillis) / 1000);
  if (seconds < 60)  return 'edited just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60)  return minutes === 1 ? 'edited 1 minute ago' : `edited ${minutes} minutes ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24)    return hours === 1 ? 'edited 1 hour ago' : `edited ${hours} hours ago`;
  const days = Math.floor(hours / 24);
  if (days < 14)     return days === 1 ? 'edited 1 day ago' : `edited ${days} days ago`;
  const weeks = Math.floor(days / 7);
  if (weeks < 9)     return weeks === 1 ? 'edited 1 week ago' : `edited ${weeks} weeks ago`;
  const months = Math.floor(days / 30);
  if (months < 12)   return months === 1 ? 'edited 1 month ago' : `edited ${months} months ago`;
  const years = Math.floor(months / 12);
  return years === 1 ? 'edited 1 year ago' : `edited ${years} years ago`;
}

// Absolute timestamp for the tooltip (locale-consistent with fmtDate in actress-detail.js)
function absoluteTimestamp(epochMillis) {
  return new Date(epochMillis).toLocaleString('en-US', {
    month: 'short', day: 'numeric', year: 'numeric',
    hour: 'numeric', minute: '2-digit',
  });
}

// ── Panel container management ────────────────────────────────────────────
//
// getOrCreatePanel() finds or inserts the stable #ad-note-panel div as the
// LAST CHILD of aside.ad-rail (the left column). The container persists
// across actress navigations; mountActressNotePanel() re-renders contents.
//
function getOrCreatePanel() {
  let panel = document.getElementById(PANEL_ID);
  if (!panel) {
    panel = document.createElement('div');
    panel.id = PANEL_ID;
    const rail = document.querySelector('aside.ad-rail');
    if (rail) rail.appendChild(panel);
  }
  return panel;
}

// ── Public mount entry point ──────────────────────────────────────────────
//
// Called from actress-detail.js after loadAndRender() completes.
// Fetches the note for actressId and renders the appropriate state.
//
export async function mountActressNotePanel(actressId) {
  const panel = getOrCreatePanel();
  // Show a minimal placeholder immediately while the fetch is in flight.
  panel.className = 'actress-note-panel actress-note-panel--loading';
  panel.innerHTML = '';

  let noteState = null;
  try {
    noteState = await getNote('actress', String(actressId));
  } catch (err) {
    console.error('[actress-detail-notes] getNote failed:', err);
    panel.className = 'actress-note-panel actress-note-panel--error';
    panel.innerHTML = '<span class="actress-note-error">Note unavailable</span>';
    return;
  }

  if (noteState) {
    renderPresent(panel, actressId, noteState);
  } else {
    renderEmpty(panel, actressId);
  }
}

// ── Empty state ───────────────────────────────────────────────────────────
//
// Dashed outline placeholder. Clicking enters edit mode.
//
function renderEmpty(panel, actressId) {
  panel.className = 'actress-note-panel actress-note-panel--empty';
  panel.innerHTML = `
    <div class="actress-note-empty-label" role="button" tabindex="0" aria-label="Add a note">
      No note yet — click to add
    </div>
  `;

  const label = panel.querySelector('.actress-note-empty-label');
  const openEdit = () => renderEdit(panel, actressId, null);
  label.addEventListener('click', openEdit);
  label.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openEdit(); }
  });
}

// ── Present state ─────────────────────────────────────────────────────────
//
// Body rendered as preformatted text, small "Edit" affordance in corner,
// edited-at line bottom-right.
//
function renderPresent(panel, actressId, noteState) {
  panel.className = 'actress-note-panel actress-note-panel--present';

  const relTime = editedAgo(noteState.updatedAt);
  const absTime = absoluteTimestamp(noteState.updatedAt);

  const bodyHtml = escHtml(noteState.body);

  panel.innerHTML = `
    <div class="actress-note-present">
      <button class="actress-note-edit-btn" aria-label="Edit note">Edit</button>
      <pre class="actress-note-body">${bodyHtml}</pre>
      <div class="actress-note-edited-at" title="${escAttr(absTime)}">${escHtml(relTime)}</div>
    </div>
  `;

  panel.querySelector('.actress-note-edit-btn').addEventListener('click', () => {
    renderEdit(panel, actressId, noteState);
  });
}

// ── Edit state ────────────────────────────────────────────────────────────
//
// Textarea + char counter + Save / Cancel (+ Clear when a note exists).
// Rotation straightens to 0deg per v1 precedent for typing usability.
// Save → putNote → re-render present.
// Clear → deleteNote → re-render empty.
// Cancel → revert to whichever state was showing.
//
function renderEdit(panel, actressId, existingNote) {
  const prevClass   = panel.className;
  const prevHtml    = panel.innerHTML;
  panel.className   = 'actress-note-panel actress-note-panel--editing';

  const initialValue = existingNote ? existingNote.body : '';
  const hasNote = !!existingNote;

  panel.innerHTML = `
    <div class="actress-note-editor">
      <textarea class="actress-note-textarea" rows="4" maxlength="560"
                placeholder="Add a note…">${escHtml(initialValue)}</textarea>
      <div class="actress-note-counter" aria-live="polite"></div>
      <div class="actress-note-actions">
        ${hasNote ? '<button class="actress-note-clear-btn" type="button">Clear</button>' : ''}
        <button class="actress-note-cancel-btn" type="button">Cancel</button>
        <button class="actress-note-save-btn" type="button">Save</button>
      </div>
    </div>
  `;

  const textarea  = panel.querySelector('.actress-note-textarea');
  const counterEl = panel.querySelector('.actress-note-counter');
  const saveBtn   = panel.querySelector('.actress-note-save-btn');
  const cancelBtn = panel.querySelector('.actress-note-cancel-btn');
  const clearBtn  = panel.querySelector('.actress-note-clear-btn');

  // Attach live char counter; re-evaluate Save disabled state on each input.
  const detachCounter = attachCharCounter(textarea, counterEl, { max: MAX_CHARS });

  function updateSaveEnabled() {
    saveBtn.disabled = textarea.value.length > MAX_CHARS;
  }
  textarea.addEventListener('input', updateSaveEnabled);
  updateSaveEnabled();

  // Focus and place cursor at end.
  textarea.focus();
  textarea.setSelectionRange(textarea.value.length, textarea.value.length);

  // Cancel: revert to previous view and re-attach appropriate listener.
  cancelBtn.addEventListener('click', () => {
    detachCounter();
    panel.className = prevClass;
    panel.innerHTML = prevHtml;
    if (existingNote) {
      const editBtn = panel.querySelector('.actress-note-edit-btn');
      if (editBtn) editBtn.addEventListener('click', () => renderEdit(panel, actressId, existingNote));
    } else {
      const label = panel.querySelector('.actress-note-empty-label');
      if (label) {
        const openEdit = () => renderEdit(panel, actressId, null);
        label.addEventListener('click', openEdit);
        label.addEventListener('keydown', e => {
          if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openEdit(); }
        });
      }
    }
  });

  // Save: PUT note → re-render present or empty.
  saveBtn.addEventListener('click', async () => {
    if (saveBtn.disabled) return;
    detachCounter();
    saveBtn.disabled = true;
    saveBtn.textContent = 'Saving…';
    try {
      const body   = textarea.value;
      const result = await putNote('actress', String(actressId), body);
      if (result) {
        renderPresent(panel, actressId, result);
      } else {
        // PUT with empty body → server deleted the row (204 → null).
        renderEmpty(panel, actressId);
      }
    } catch (err) {
      console.error('[actress-detail-notes] putNote failed:', err);
      saveBtn.disabled = false;
      saveBtn.textContent = 'Save';
    }
  });

  // Clear (only shown in edit mode when a note exists).
  if (clearBtn) {
    clearBtn.addEventListener('click', async () => {
      detachCounter();
      clearBtn.disabled = true;
      clearBtn.textContent = 'Clearing…';
      try {
        await deleteNote('actress', String(actressId));
        renderEmpty(panel, actressId);
      } catch (err) {
        console.error('[actress-detail-notes] deleteNote failed:', err);
        clearBtn.disabled = false;
        clearBtn.textContent = 'Clear';
      }
    });
  }

  // Cmd/Ctrl+Enter saves.
  textarea.addEventListener('keydown', e => {
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
      e.preventDefault();
      if (!saveBtn.disabled) saveBtn.click();
    }
  });
}

// ── HTML escaping helpers ─────────────────────────────────────────────────
function escHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function escAttr(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;');
}
