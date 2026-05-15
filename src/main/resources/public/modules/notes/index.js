// index.js — Public surface for the notes shared module.
//
// Import from here in both v1 and v2 surfaces:
//   import { openStickyModal, notesIcon, getNote, putNote, deleteNote, batchNotes, attachCharCounter }
//     from '/modules/notes/index.js';
//
// Design tokens (CSS custom properties) live in tokens.css:
//   <link rel="stylesheet" href="/modules/notes/tokens.css">

export { openStickyModal } from './sticky-modal.js';
export { notesIcon       } from './icon.js';
export { getNote, putNote, deleteNote, batchNotes } from './api-client.js';
export { attachCharCounter } from './char-counter.js';
