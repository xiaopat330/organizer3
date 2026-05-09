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
import { displayPath, installPathClickToCopy } from '../path-utils.js';

const ICON_TRASH = '<svg class="admin-icon-trash" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>';
const ICON_FOLDER = '<svg class="admin-icon-folder" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>';
const ICON_VIDEO  = '<svg class="admin-icon-video" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>';

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

// ── Resolution helper ─────────────────────────────────────────────────────────

/**
 * Derive a resolution label from FolderVideo metadata.
 * Follows the spec: ≥3840w or ≥2160h → "4K"; ≥1080h → "1080p"; ≥720h → "720p"; else "${h}p".
 * Returns null if neither width nor height is available.
 */
function resolveLabel(v) {
  if (v.width == null && v.height == null) return null;
  if ((v.width != null && v.width >= 3840) || (v.height != null && v.height >= 2160)) return '4K';
  if (v.height != null && v.height >= 1080) return '1080p';
  if (v.height != null && v.height >= 720)  return '720p';
  if (v.height != null) return `${v.height}p`;
  return null;
}

/**
 * Build chip HTML for a FolderVideo row.
 * Chips: size, duration, resolution, HEVC.
 */
function videoRowChips(v) {
  let html = '';
  const sizeStr = humanizeBytes(v.sizeBytes);
  if (sizeStr !== '—') html += `<span class="admin-card-file-chip">${esc(sizeStr)}</span>`;
  const durStr  = humanizeDuration(v.durationSec);
  if (durStr !== '—')  html += `<span class="admin-card-file-chip">${esc(durStr)}</span>`;
  const res = resolveLabel(v);
  if (res)             html += `<span class="admin-card-file-chip admin-card-file-chip-res">${esc(res)}</span>`;
  const fnLower  = (v.filename || '').toLowerCase();
  const codec    = (v.videoCodec || '').toLowerCase();
  const isHevc   = codec.includes('hevc') || codec.includes('h265') || fnLower.includes('-h265');
  if (isHevc)          html += `<span class="admin-card-file-chip admin-card-file-chip-hevc">HEVC</span>`;
  return html;
}

// ── Render ───────────────────────────────────────────────────────────────────

export function renderFolderContents(code, folderContents) {
  if (!folderContents) {
    return '<div class="admin-card-folder-loading">Loading folder contents…</div>';
  }

  if (isFolderContentsError(folderContents)) {
    return `<div class="admin-card-folder-error">⚠ Could not read folder from disk — ${esc(folderContents[FOLDER_ERROR_KEY])}. Folder state is unknown.</div>`;
  }

  const { videos, covers } = folderContents;
  // Prefer the single-location nasPath (full //server/share/... form) over
  // folderContents.folderPath (volume-relative). Falls back to folderPath if
  // titleData isn't available for any reason.
  const titleData = state.getCardData(code);
  const loc = titleData && titleData.locationEntries && titleData.locationEntries[0];
  const rawPath = (loc && loc.nasPath) || folderContents.folderPath || '';
  const folderLabel = rawPath
    ? `<div class="admin-card-folder-path" data-raw-path="${esc(rawPath)}">${ICON_FOLDER}<span class="admin-card-folder-path-text">${esc(displayPath(rawPath))}</span></div>`
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
    const nameInner = isPending
      ? `<s class="admin-card-filename">${esc(v.filename)}</s>`
      : `<span class="admin-card-filename">${esc(v.filename)}</span>`;
    const nameHtml  = `<span class="admin-card-file-name-wrap">${ICON_VIDEO}${nameInner}</span>`;
    const chipsHtml = `<span class="admin-card-file-chips">${videoRowChips(v)}</span>`;
    const actionHtml = isPending
      ? `<button class="admin-card-file-undo-btn" data-file-action="undo-trash-video" data-filename="${esc(v.filename)}">Undo</button>`
      : `<button class="admin-card-file-trash-btn" data-file-action="trash-video" data-filename="${esc(v.filename)}">${ICON_TRASH} Trash</button>`;
    return `<div class="${rowClass}">${nameHtml}${chipsHtml}${actionHtml}</div>`;
  }).join('');

  return `
    <div class="admin-card-file-list">
      <div class="admin-card-file-list-label">Videos</div>
      ${rows || '<div class="admin-card-file-empty">No video files in this folder</div>'}
    </div>`;
}

// Cover icon SVG — shown when no thumbnail URL is available (no per-folder cover serving route).
const COVER_ICON_SVG = `<svg class="admin-card-cover-icon" viewBox="0 0 20 20" fill="none"
  xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
  <rect x="2" y="2" width="16" height="16" rx="2" stroke="currentColor" stroke-width="1.5"/>
  <circle cx="7.5" cy="7.5" r="1.5" stroke="currentColor" stroke-width="1.2"/>
  <path d="M2 13l4-4 3 3 3-3 6 5" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
</svg>`;

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
    const sizeStr   = humanizeBytes(c.sizeBytes);
    const metaHtml  = sizeStr !== '—'
      ? `<span class="admin-card-file-chips"><span class="admin-card-file-chip">${esc(sizeStr)}</span></span>`
      : '';

    let actionHtml;
    if (isPending) {
      actionHtml = `<button class="admin-card-file-undo-btn" data-file-action="undo-trash-cover" data-filename="${esc(c.filename)}">Undo</button>`;
    } else if (multiCover) {
      actionHtml =
        `<button class="admin-card-file-keep-btn" data-file-action="keep-cover" data-filename="${esc(c.filename)}">[keep]</button>` +
        `<button class="admin-card-file-trash-btn" data-file-action="trash-cover" data-filename="${esc(c.filename)}">${ICON_TRASH} Trash</button>`;
    } else {
      actionHtml = `<button class="admin-card-file-trash-btn" data-file-action="trash-cover" data-filename="${esc(c.filename)}">${ICON_TRASH} Trash</button>`;
    }
    // No per-folder cover serving route exists — use an icon placeholder instead of a thumbnail.
    return `<div class="${rowClass}">${COVER_ICON_SVG}${nameHtml}${metaHtml}${actionHtml}</div>`;
  }).join('');

  return `
    <div class="admin-card-file-list admin-card-file-list-covers">
      <div class="admin-card-file-list-label">Covers</div>
      ${warningHtml}
      ${rows || ''}
    </div>`;
}

// ── Lazy fetch ───────────────────────────────────────────────────────────────

// Sentinel shape stored in _folderContents on fetch failure.
// Checked with isFolderContentsError(); message extracted with folderContentsErrorMsg().
const FOLDER_ERROR_KEY = '__folderError';

export function isFolderContentsError(contents) {
  return contents != null && Object.prototype.hasOwnProperty.call(contents, FOLDER_ERROR_KEY);
}

export function folderContentsErrorMsg(contents) {
  return (contents && contents[FOLDER_ERROR_KEY]) || 'Unknown error';
}

export function ensureFolderContents(code, titleData) {
  if (Object.prototype.hasOwnProperty.call(titleData, '_folderContents')) return;
  titleData._folderContents = null;  // mark in-flight; prevents double-fetch
  fetch(`/api/titles/${encodeURIComponent(code)}/folder-contents`)
    .then(res => res.ok ? res.json() : Promise.reject(new Error(`HTTP ${res.status}`)))
    .then(contents => {
      titleData._folderContents = contents;
      renderCardInPlace(code);
    })
    .catch(err => {
      // Store a sticky error sentinel so failed state persists across re-renders.
      titleData._folderContents = { [FOLDER_ERROR_KEY]: err.message || 'Unknown error' };
      renderCardInPlace(code);
    });
}

// ── Event listeners ──────────────────────────────────────────────────────────

export function attachFolderListeners(code, card, titleData) {
  card.querySelectorAll('.admin-card-folder-path[data-raw-path]').forEach(el => {
    installPathClickToCopy(el, el.dataset.rawPath);
  });

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
