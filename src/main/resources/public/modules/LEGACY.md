# Legacy UI modules

Files at this directory level (NOT inside `v2/` or `chrome/`) belong to the **legacy UI surface** that the v2 modernization is replacing. They must keep working until v2 reaches functional parity and is production-ready.

**Do not modify legacy files** unless:
- The user has explicitly asked for a legacy-side change.
- A change elsewhere has caused a regression in the legacy UI that needs fixing.

Eventually these files will be moved into `modules/legacy/` and ultimately removed when v2 ships. The current "files-at-the-top-level-are-legacy" convention is a stepping stone toward that physical isolation.

## Directory boundaries

| Path | Status | May be modified? |
|------|--------|------------------|
| `modules/v2/` | New v2 surface; active development | Yes |
| `modules/chrome/` | Page chrome shared by v1 + v2 (e.g., `status-bar.js`) | Yes, with care — affects both UIs |
| `modules/*.js` (top level) | Mostly legacy; a few shared utilities (`utils.js`, `task-center.js`, `cards.js`) that v2 also imports | **No, by default** |

## What "do not modify" means

Edits, refactors, renames, deletions — all off-limits without explicit user approval. Reading is fine. v2 modules importing legacy utilities is fine (and already happens — e.g., `v2/backup.js` imports `task-center.js`).

## When v2 reaches parity

The expected sequence:
1. Move all top-level `modules/*.js` files into `modules/legacy/` (preserving the few shared utilities by extracting them to `modules/shared/`).
2. Update legacy HTML script tags to point at the new paths.
3. Smoke-test the legacy UI end-to-end.
4. Eventually delete `modules/legacy/` once v2 is production-ready and the legacy URLs are retired.

This README exists so the convention survives across sessions, AI assistants, and contributors who weren't around for the v2 modernization sweep.
