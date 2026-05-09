// §4.4.1 Normalize folder modal — Phase 5.
//
// openNormalizeModal(code, folderContents)
//   Fetches the normalization plan from the server (GET /api/titles/{code}/normalization-plan),
//   renders the unified Renames + Moves preview modal, and on confirm stages a
//   'normalize-folder' action on the card.
//
// Modal sections:
//   RENAMES — files where the filename changes (parent dir may also change)
//   MOVES   — files where only the parent directory changes (filename is already canonical)
//
// Files with pending trash stages are excluded from the plan request via
// ?excludeRelPaths=... so the modal preview matches what Commit will actually do.
//
// Confirming stages:
//   addStage(code, 'normalize-folder', { moves: [{from, to}] })
// This is a keyless stage (key=null) — only one normalize stage per card.
//
// The modal is built as a <dialog> element appended to <body> and removed on close.

import { esc } from '../utils.js';
import * as state from './state.js';
import { renderCardInPlace } from './card.js';

// ── Public entry point ────────────────────────────────────────────────────────

/**
 * Open the Normalize folder modal for the given title code.
 *
 * @param {string} code  Title code (e.g. "ABC-123")
 * @param {object} folderContents  The cached _folderContents object (or null)
 */
export async function openNormalizeModal(code, folderContents) {
  // Build excludeRelPaths from staged trash actions so the server plan
  // reflects what the folder will look like after those trashes commit.
  const excludeRelPaths = buildExcludeSet(code);
  const planUrl = buildPlanUrl(code, excludeRelPaths);

  // Show a loading modal while we fetch.
  const dialog = showLoadingModal(code);

  let plan;
  try {
    const res = await fetch(planUrl);
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error(body.error || `HTTP ${res.status}`);
    }
    plan = await res.json();
  } catch (err) {
    renderErrorModal(dialog, code, err.message || String(err));
    return;
  }

  renderPlanModal(dialog, code, plan);
}

// ── Exclude set ────────────────────────────────────────────────────────────────

/**
 * Collect rel-paths of files with pending trash stages so the server plan
 * treats them as already gone.
 */
function buildExcludeSet(code) {
  const stages = state.getStages(code) || [];
  return stages
    .filter(s => s.status === 'pending' && (s.kind === 'trash-video' || s.kind === 'trash-cover'))
    .map(s => s.payload && s.payload.filename ? s.payload.filename : null)
    .filter(Boolean);
}

function buildPlanUrl(code, excludeRelPaths) {
  let url = `/api/titles/${encodeURIComponent(code)}/normalize-proposal`;
  if (excludeRelPaths.length > 0) {
    url += `?excludeRelPaths=${encodeURIComponent(excludeRelPaths.join(','))}`;
  }
  return url;
}

// ── Modal scaffolding ─────────────────────────────────────────────────────────

function createDialog() {
  const dialog = document.createElement('dialog');
  dialog.className = 'normalize-modal';
  document.body.appendChild(dialog);

  // Close on backdrop click.
  dialog.addEventListener('click', (e) => {
    if (e.target === dialog) closeModal(dialog);
  });

  dialog.showModal();
  return dialog;
}

function closeModal(dialog) {
  dialog.close();
  dialog.remove();
}

function showLoadingModal(code) {
  const dialog = createDialog();
  dialog.innerHTML = `
    <div class="normalize-modal-inner">
      <div class="normalize-modal-title">Normalize folder: <code>${esc(code)}</code></div>
      <div class="normalize-modal-loading">Loading plan…</div>
    </div>`;
  return dialog;
}

// ── Error state ───────────────────────────────────────────────────────────────

function renderErrorModal(dialog, code, errorMsg) {
  dialog.innerHTML = `
    <div class="normalize-modal-inner">
      <div class="normalize-modal-title">Normalize folder: <code>${esc(code)}</code></div>
      <div class="normalize-modal-error">⚠ Could not load plan — ${esc(errorMsg)}</div>
      <div class="normalize-modal-footer">
        <button type="button" class="normalize-modal-cancel-btn">Close</button>
      </div>
    </div>`;
  dialog.querySelector('.normalize-modal-cancel-btn').addEventListener('click', () => closeModal(dialog));
}

// ── Plan rendering ─────────────────────────────────────────────────────────────

/**
 * Render the full plan modal.
 * Entries are split into:
 *   - alreadyCanonical=true → "already canonical" display (no checkbox needed, shown dimmed)
 *   - conflict=true          → input row for user to supply a name
 *   - otherwise              → checked checkbox with editable target
 *
 * For simplicity this implementation renders all non-canonical entries as
 * checked rows. The user can uncheck any row to skip that move.
 *
 * Classification for display sections:
 *   RENAMES — entries where the filename changes (basename differs between from and to)
 *   MOVES   — entries where only the parent directory changes (same basename)
 *   BOTH    — entries that change both basename and parent; shown in RENAMES section
 *
 * We put entries that change ONLY the directory in the MOVES section.
 * Everything else (name change or both) goes in the RENAMES section.
 */
function renderPlanModal(dialog, code, plan) {
  const entries = plan.entries || [];
  const alreadyNormalized = plan.alreadyNormalized;

  // Partition entries.
  const renames = [];  // change filename (may also change dir)
  const moves   = [];  // change only directory (filename stays the same)
  const canonical = []; // already correct — display only

  for (const entry of entries) {
    if (entry.alreadyCanonical && !entry.conflict) {
      canonical.push(entry);
      continue;
    }
    if (!entry.to) {
      // Conflict — goes in renames section with an input.
      renames.push(entry);
      continue;
    }
    const fromBase = baseName(entry.from);
    const toBase   = baseName(entry.to);
    if (fromBase.toLowerCase() === toBase.toLowerCase()) {
      // Only directory changes.
      moves.push(entry);
    } else {
      renames.push(entry);
    }
  }

  const hasSomethingToDo = renames.length > 0 || moves.length > 0;

  // Build HTML.
  const alreadyMsg = alreadyNormalized
    ? '<div class="normalize-modal-already-canonical">✓ Folder is already canonical. No changes needed.</div>'
    : '';

  const renamesSection = renames.length > 0
    ? `<div class="normalize-modal-section">
        <div class="normalize-modal-section-label">RENAMES</div>
        <div class="normalize-modal-section-desc">Filename changes (may also move to canonical subfolder)</div>
        ${renames.map((e, i) => renderRenameRow(e, i)).join('')}
       </div>`
    : '';

  const movesSection = moves.length > 0
    ? `<div class="normalize-modal-section">
        <div class="normalize-modal-section-label">MOVES</div>
        <div class="normalize-modal-section-desc">Files moving to canonical subfolder (filename unchanged)</div>
        ${moves.map((e, i) => renderMoveRow(e, i + renames.length)).join('')}
       </div>`
    : '';

  const canonicalSection = canonical.length > 0
    ? `<div class="normalize-modal-section normalize-modal-section-canonical">
        <div class="normalize-modal-section-label">ALREADY CANONICAL</div>
        ${canonical.map(e => `<div class="normalize-modal-canonical-row">
          <span class="normalize-modal-from">${esc(e.from)}</span>
          <span class="normalize-modal-canonical-mark">(no change needed)</span>
        </div>`).join('')}
       </div>`
    : '';

  const stageLabel = hasSomethingToDo
    ? `Stage changes`
    : `Nothing to stage`;

  dialog.innerHTML = `
    <form method="dialog" class="normalize-modal-inner" novalidate>
      <div class="normalize-modal-title">Normalize folder: <code>${esc(code)}</code></div>
      <div class="normalize-modal-path">${esc(plan.folderPath || '')}</div>
      ${alreadyMsg}
      ${renamesSection}
      ${movesSection}
      ${canonicalSection}
      <div class="normalize-modal-footer">
        <button type="button" class="normalize-modal-cancel-btn">Cancel</button>
        <button type="button" class="normalize-modal-stage-btn" ${hasSomethingToDo ? '' : 'disabled'}>
          ${esc(stageLabel)}
        </button>
      </div>
    </form>`;

  // Wire cancel.
  dialog.querySelector('.normalize-modal-cancel-btn').addEventListener('click', () => closeModal(dialog));

  // Wire stage.
  const stageBtn = dialog.querySelector('.normalize-modal-stage-btn');
  if (hasSomethingToDo) {
    stageBtn.addEventListener('click', () => {
      const confirmedMoves = collectMoves(dialog);
      if (confirmedMoves === null) return; // validation failed — errors shown inline
      stageMoves(code, confirmedMoves);
      closeModal(dialog);
      renderCardInPlace(code);
    });
  }
}

// ── Row renderers ──────────────────────────────────────────────────────────────

function renderRenameRow(entry, idx) {
  const checkId = `nm-chk-${idx}`;
  const inputId = `nm-inp-${idx}`;

  if (entry.conflict) {
    // Conflict: user must supply a name — editable input, no pre-fill for canonical part.
    return `
      <div class="normalize-modal-row normalize-modal-row-conflict" data-idx="${idx}" data-from="${esc(entry.from)}" data-kind="${esc(entry.kind)}">
        <label class="normalize-modal-check-label">
          <input type="checkbox" class="nm-entry-check" id="${esc(checkId)}" data-idx="${idx}" checked>
          <span class="normalize-modal-from normalize-modal-conflict-label" title="${esc(entry.from)}">
            ${esc(entry.from)}
            <span class="normalize-modal-conflict-badge">⚠ conflict — enter target name</span>
          </span>
        </label>
        <span class="normalize-modal-arrow">→</span>
        <input type="text" class="nm-entry-input" id="${esc(inputId)}" data-idx="${idx}"
               placeholder="e.g. ${esc(entry.kind === 'cover' ? 'ABC-123.jpg' : 'ABC-123_disc1.mp4')}"
               value="" autocomplete="off">
      </div>`;
  }

  // Normal rename: pre-fill with the proposed canonical name (just the basename for editing).
  const proposedBase = baseName(entry.to || '');
  return `
    <div class="normalize-modal-row" data-idx="${idx}" data-from="${esc(entry.from)}" data-to="${esc(entry.to || '')}" data-kind="${esc(entry.kind)}">
      <label class="normalize-modal-check-label">
        <input type="checkbox" class="nm-entry-check" id="${esc(checkId)}" data-idx="${idx}" checked>
        <span class="normalize-modal-from" title="${esc(entry.from)}">${esc(entry.from)}</span>
      </label>
      <span class="normalize-modal-arrow">→</span>
      <input type="text" class="nm-entry-input" id="${esc(inputId)}" data-idx="${idx}"
             value="${esc(proposedBase)}" autocomplete="off">
    </div>`;
}

function renderMoveRow(entry, idx) {
  const checkId = `nm-chk-${idx}`;
  return `
    <div class="normalize-modal-row" data-idx="${idx}" data-from="${esc(entry.from)}" data-to="${esc(entry.to || '')}" data-kind="${esc(entry.kind)}">
      <label class="normalize-modal-check-label">
        <input type="checkbox" class="nm-entry-check" id="${esc(checkId)}" data-idx="${idx}" checked>
        <span class="normalize-modal-from" title="${esc(entry.from)}">${esc(entry.from)}</span>
      </label>
      <span class="normalize-modal-arrow">→</span>
      <span class="normalize-modal-to">${esc(entry.to || '')}</span>
    </div>`;
}

// ── Move collection & validation ───────────────────────────────────────────────

/**
 * Collect the confirmed moves from the rendered modal DOM.
 * Returns an array of {from, to} pairs, or null on validation failure.
 *
 * For rename rows (have an input): reconstruct the full target rel-path from
 * the parent dir of the plan's proposed `to` combined with the user's edited basename.
 * For move-only rows (no input): use the plan's `to` directly from data-to.
 *
 * Validation:
 *   - All checked entries must have a non-empty target.
 *   - No duplicate target paths within the checked set.
 *
 * @param {HTMLDialogElement} dialog  The modal element
 * @returns {Array<{from:string,to:string}>|null}
 */
function collectMoves(dialog) {
  const collected = [];
  let hasError = false;

  // Clear previous inline errors.
  dialog.querySelectorAll('.nm-inline-error').forEach(el => el.remove());

  for (const row of dialog.querySelectorAll('.normalize-modal-row')) {
    const idx     = parseInt(row.dataset.idx, 10);
    const from    = row.dataset.from;
    const baseDir = row.dataset.to ? dirName(row.dataset.to) : null;
    const chk     = row.querySelector('.nm-entry-check');
    if (!chk || !chk.checked) continue;  // user unchecked this row → skip

    const inp = row.querySelector('.nm-entry-input');
    if (inp) {
      // Rename or conflict row — user may have edited the basename.
      const userBasename = inp.value.trim();
      if (!userBasename) {
        showInlineError(row, 'Target name is required');
        hasError = true;
        continue;
      }
      // Reconstruct full rel-path: use the parent dir from the plan's to, replace basename.
      const proposedTo = row.dataset.to;
      let to;
      if (proposedTo) {
        to = dirName(proposedTo) + '/' + userBasename;
        // Strip leading slash from dir join (dir may be empty for base-level files).
        to = to.replace(/^\//, '');
      } else {
        // Conflict row — no proposed to; use the user-entered name at base for covers
        // or in video/ for videos.
        const kind = row.dataset.kind;
        to = kind === 'cover' ? userBasename : 'video/' + userBasename;
      }
      collected.push({ from, to });
    } else {
      // Move-only row: target is fixed (taken from data-to).
      const to = row.dataset.to;
      if (!to) continue; // no target (shouldn't happen)
      collected.push({ from, to });
    }
  }

  if (hasError) return null;

  // Duplicate target check.
  const seen = new Set();
  for (const m of collected) {
    const key = m.to.toLowerCase();
    if (seen.has(key)) {
      // Find the row and show error.
      const row = dialog.querySelector(`.normalize-modal-row[data-from="${CSS.escape(m.from)}"]`);
      if (row) showInlineError(row, 'Duplicate target path — each file must go to a unique location');
      hasError = true;
    }
    seen.add(key);
  }

  if (hasError) return null;
  return collected;
}

function showInlineError(row, msg) {
  const err = document.createElement('div');
  err.className = 'nm-inline-error';
  err.textContent = msg;
  row.appendChild(err);
}

// ── Stage the confirmed moves ─────────────────────────────────────────────────

function stageMoves(code, moves) {
  // Keyless stage — replaces any existing normalize-folder stage.
  state.removePendingStage(code, 'normalize-folder', null);
  state.addStage(code, 'normalize-folder', { moves });
}

// ── Path helpers ───────────────────────────────────────────────────────────────

function baseName(relPath) {
  if (!relPath) return '';
  const idx = relPath.lastIndexOf('/');
  return idx >= 0 ? relPath.substring(idx + 1) : relPath;
}

function dirName(relPath) {
  if (!relPath) return '';
  const idx = relPath.lastIndexOf('/');
  return idx >= 0 ? relPath.substring(0, idx) : '';
}
