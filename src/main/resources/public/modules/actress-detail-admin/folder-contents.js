// §4.4 Folder contents section — two-list view (videos + covers).
//
// Renders inside the Edit Card when title.locationEntries.length === 1.
// Fetched lazily and cached on titleData._folderContents (survives re-renders
// because titleData is the stable state object, not the DOM node).
//
// Trash actions are staged, not fired immediately:
//   addStage(code, 'trash-video', { filename }, filename)
//   addStage(code, 'trash-cover', { filename }, filename)
// Undo removes the pending stage.

import { esc } from '../utils.js';
import * as state from './state.js';
import { renderCardInPlace } from './card.js';

// ── Humanize helpers ─────────────────────────────────────────────────────────

function humanizeBytes(bytes) {
  if (bytes == null) return '—';
  if (bytes >= 1_073_741_824) return (bytes / 1_073_741_824).toFixed(1) + ' GB';
  if (bytes >= 1_048_576)     return (bytes / 1_048_576).toFixed(1) + ' MB';
  if (bytes >= 1_024)         return (bytes / 1_024).toFixed(0) + ' KB';
  return bytes + ' B';
}

function humanizeDuration(sec) {
  if (sec == null) return '—';
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

// ── Render ───────────────────────────────────────────────────────────────────

export function renderFolderContents(code, folderContents) {
  if (!folderContents) {
    return '<div class="admin-card-folder-loading">Loading folder contents…</div>';
  }

  const { folderPath, videos, covers } = folderContents;
  const folderLabel = folderPath
    ? `<div class="admin-card-folder-path" title="${esc(folderPath)}">${esc(folderPath)}</div>`
    : '';

  const multiCover = covers.length > 1;

  const videosHtml = renderVideoList(code, videos);
  const coversHtml = renderCoverList(code, covers, multiCover);

  return `
    <div class="admin-card-folder-section">
      <div class="admin-card-folder-section-title">Folder contents</div>
      ${folderLabel}
      ${videosHtml}
      ${coversHtml}
    </div>`;
}

function renderVideoList(code, videos) {
  const rows = videos.map(v => {
    const isPending = !!state.findPendingStage(code, 'trash-video', v.filename);
    const rowClass  = isPending ? 'admin-card-file-row admin-card-file-row-pending' : 'admin-card-file-row';
    const nameHtml  = isPending
      ? `<s class="admin-card-filename">${esc(v.filename)}</s>`
      : `<span class="admin-card-filename">${esc(v.filename)}</span>`;
    const metaHtml  = `<span class="admin-card-file-meta">${humanizeBytes(v.sizeBytes)}</span>` +
                      `<span class="admin-card-file-meta">${humanizeDuration(v.durationSec)}</span>`;
    const actionHtml = isPending
      ? `<button class="admin-card-file-undo-btn" data-file-action="undo-trash-video" data-filename="${esc(v.filename)}">Undo</button>`
      : `<button class="admin-card-file-trash-btn" data-file-action="trash-video" data-filename="${esc(v.filename)}">[trash]</button>`;
    return `<div class="${rowClass}">${nameHtml}${metaHtml}${actionHtml}</div>`;
  }).join('');

  return `
    <div class="admin-card-file-list">
      <div class="admin-card-file-list-label">videos</div>
      ${rows || '<div class="admin-card-file-empty">No video files</div>'}
    </div>`;
}

function renderCoverList(code, covers, multiCover) {
  const warningHtml = multiCover
    ? '<div class="admin-card-cover-warning">⚠ Multiple covers detected — keep one, trash the rest.</div>'
    : '';

  const rows = covers.map(c => {
    const isPending = !!state.findPendingStage(code, 'trash-cover', c.filename);
    const rowClass  = isPending ? 'admin-card-file-row admin-card-file-row-pending' : 'admin-card-file-row';
    const nameHtml  = isPending
      ? `<s class="admin-card-filename">${esc(c.filename)}</s>`
      : `<span class="admin-card-filename">${esc(c.filename)}</span>`;
    const metaHtml  = `<span class="admin-card-file-meta">${humanizeBytes(c.sizeBytes)}</span>`;

    let actionHtml;
    if (isPending) {
      actionHtml = `<button class="admin-card-file-undo-btn" data-file-action="undo-trash-cover" data-filename="${esc(c.filename)}">Undo</button>`;
    } else if (multiCover) {
      actionHtml =
        `<button class="admin-card-file-keep-btn" data-file-action="keep-cover" data-filename="${esc(c.filename)}">[keep]</button>` +
        `<button class="admin-card-file-trash-btn" data-file-action="trash-cover" data-filename="${esc(c.filename)}">[trash]</button>`;
    } else {
      actionHtml = `<button class="admin-card-file-trash-btn" data-file-action="trash-cover" data-filename="${esc(c.filename)}">[trash]</button>`;
    }
    return `<div class="${rowClass}">${nameHtml}${metaHtml}${actionHtml}</div>`;
  }).join('');

  return `
    <div class="admin-card-file-list">
      <div class="admin-card-file-list-label">covers</div>
      ${warningHtml}
      ${rows || ''}
    </div>`;
}

// ── Lazy fetch ───────────────────────────────────────────────────────────────

export function ensureFolderContents(code, titleData) {
  if (Object.prototype.hasOwnProperty.call(titleData, '_folderContents')) return;
  titleData._folderContents = null;  // mark in-flight; prevents double-fetch
  fetch(`/api/titles/${encodeURIComponent(code)}/folder-contents`)
    .then(res => res.ok ? res.json() : Promise.reject(new Error(`HTTP ${res.status}`)))
    .then(contents => {
      titleData._folderContents = contents;
      renderCardInPlace(code);
    })
    .catch(() => {
      // Leave _folderContents as null (loading indicator stays).
      // A re-render triggered by another action will retry.
      delete titleData._folderContents;
    });
}

// ── Event listeners ──────────────────────────────────────────────────────────

export function attachFolderListeners(code, card, titleData) {
  card.querySelectorAll('[data-file-action]').forEach(btn => {
    btn.addEventListener('click', () => {
      const action   = btn.dataset.fileAction;
      const filename = btn.dataset.filename;
      const covers   = titleData._folderContents ? titleData._folderContents.covers : [];

      switch (action) {
        case 'trash-video':
          state.addStage(code, 'trash-video', { filename }, filename);
          break;
        case 'undo-trash-video':
          state.removePendingStage(code, 'trash-video', filename);
          break;
        case 'trash-cover':
          state.addStage(code, 'trash-cover', { filename }, filename);
          break;
        case 'undo-trash-cover':
          state.removePendingStage(code, 'trash-cover', filename);
          break;
        case 'keep-cover':
          // Stage trash for every cover *other* than this one.
          for (const c of covers) {
            if (c.filename !== filename) {
              state.addStage(code, 'trash-cover', { filename: c.filename }, c.filename);
            }
          }
          break;
      }

      renderCardInPlace(code);
    });
  });
}
