# modules/notes — Shared Post-It Notes module

Single source of truth for sticky-note UI (v1 + v2).

## Design tokens

`<link rel="stylesheet" href="/modules/notes/tokens.css">` — provides
`--postit-yellow` `--postit-yellow-edge` `--postit-ink` `--postit-shadow`
`--postit-empty-outline` `--postit-rotation`

## Icon + modal (card usage)

```js
import { notesIcon, openStickyModal, batchNotes } from '/modules/notes/index.js';
// Icon: filled=true (yellow) when note exists, false (gray outline) otherwise
const icon = notesIcon({ filled: !!note, title: note?.body || 'Add note' });
icon.addEventListener('click', async e => {
  e.stopPropagation();
  const result = await openStickyModal({  // → Promise<NoteState | null>
    entityType: 'actress', entityId: actress.id, entityName: actress.name,
    initialNote: note,   // null = cleared/cancelled; NoteState = saved
  });
});
cardIconRowEl.appendChild(icon);
```

## Detail-page block (Phase 4)

Phase 4 imports `openStickyModal`, `getNote`, `putNote`, `deleteNote`,
`attachCharCounter` from here — renders on yellow with rotation+shadow (§5.3).
