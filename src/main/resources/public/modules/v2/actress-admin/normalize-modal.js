// v2 Admin tab — §4.4.1 Normalize folder modal.
//
// Mirrors legacy actress-detail-admin/normalize-modal.js verbatim.
// Fetches normalization plan from GET /api/titles/{code}/normalize-proposal,
// renders renames + moves preview in a <dialog>, and on confirm stages a
// 'normalize-folder' action on the card.

import { esc } from '../../utils.js';
import * as state from './state.js';
import { renderCardInPlace } from './card.js';

// ── Public entry point ────────────────────────────────────────────────────────

export async function openNormalizeModal(code, folderContents) {
  const excludeRelPaths = buildExcludeSet(code);
  const planUrl = buildPlanUrl(code, excludeRelPaths);

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

function renderPlanModal(dialog, code, plan) {
  const entries = plan.entries || [];
  const alreadyNormalized = plan.alreadyNormalized;

  const renames = [];
  const moves   = [];
  const canonical = [];

  for (const entry of entries) {
    if (entry.alreadyCanonical && !entry.conflict) {
      canonical.push(entry);
      continue;
    }
    if (!entry.to) {
      renames.push(entry);
      continue;
    }
    const fromBase = baseName(entry.from);
    const toBase   = baseName(entry.to);
    if (fromBase.toLowerCase() === toBase.toLowerCase()) {
      moves.push(entry);
    } else {
      renames.push(entry);
    }
  }

  const hasSomethingToDo = renames.length > 0 || moves.length > 0;

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

  const stageLabel = hasSomethingToDo ? `Stage changes` : `Nothing to stage`;

  dialog.innerHTML = `
    <form method="dialog" class="normalize-modal-inner" novalidate>
      <div class="normalize-modal-title">Normalize folder: <code>${esc(code)}</code></div>
      <div class="normalize-modal-path">${esc(plan.folderPath || '')}</div>
      <div class="normalize-modal-body">
        ${alreadyMsg}
        ${renamesSection}
        ${movesSection}
        ${canonicalSection}
      </div>
      <div class="normalize-modal-footer">
        <button type="button" class="normalize-modal-cancel-btn">Cancel</button>
        <button type="button" class="normalize-modal-stage-btn" ${hasSomethingToDo ? '' : 'disabled'}>
          ${esc(stageLabel)}
        </button>
      </div>
    </form>`;

  dialog.querySelector('.normalize-modal-cancel-btn').addEventListener('click', () => closeModal(dialog));

  const stageBtn = dialog.querySelector('.normalize-modal-stage-btn');
  if (hasSomethingToDo) {
    stageBtn.addEventListener('click', () => {
      const confirmedMoves = collectMoves(dialog);
      if (confirmedMoves === null) return;
      stageMoves(code, confirmedMoves);
      closeModal(dialog);
      renderCardInPlace(code);
    });
  }

  function refreshConflictInputFlags() {
    for (const row of dialog.querySelectorAll('.normalize-modal-row-conflict')) {
      const chk = row.querySelector('.nm-entry-check');
      const inp = row.querySelector('.nm-entry-input-conflict');
      if (!inp) continue;
      const needsValue = chk && chk.checked;
      const isEmpty    = inp.value.trim() === '';
      inp.classList.toggle('nm-entry-input-needs-attention', needsValue && isEmpty);
    }
  }

  dialog.querySelectorAll('.nm-entry-input-conflict').forEach(inp => {
    inp.addEventListener('input', refreshConflictInputFlags);
  });
  dialog.querySelectorAll('.nm-entry-check').forEach(chk => {
    chk.addEventListener('change', refreshConflictInputFlags);
  });
  refreshConflictInputFlags();
}

// ── Row renderers ──────────────────────────────────────────────────────────────

function renderRenameRow(entry, idx) {
  const checkId = `nm-chk-${idx}`;
  const inputId = `nm-inp-${idx}`;

  if (entry.conflict) {
    return `
      <div class="normalize-modal-row normalize-modal-row-conflict" data-idx="${idx}" data-from="${esc(entry.from)}" data-kind="${esc(entry.kind)}">
        <label class="normalize-modal-check-label">
          <input type="checkbox" class="nm-entry-check" id="${esc(checkId)}" data-idx="${idx}" checked>
          <span class="normalize-modal-from" title="${esc(entry.from)}">${esc(entry.from)}</span>
        </label>
        <div class="normalize-modal-conflict-badge-row">
          <span class="normalize-modal-conflict-badge">⚠ conflict — enter target name</span>
        </div>
        <div class="normalize-modal-conflict-input-row">
          <span class="normalize-modal-arrow">→</span>
          <input type="text" class="nm-entry-input nm-entry-input-conflict" id="${esc(inputId)}" data-idx="${idx}"
                 placeholder="e.g. ${esc(entry.kind === 'cover' ? 'ABC-123.jpg' : 'ABC-123_disc1.mp4')}"
                 value="" autocomplete="off">
        </div>
      </div>`;
  }

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

function collectMoves(dialog) {
  const collected = [];
  let hasError = false;

  dialog.querySelectorAll('.nm-inline-error').forEach(el => el.remove());

  for (const row of dialog.querySelectorAll('.normalize-modal-row')) {
    const from    = row.dataset.from;
    const chk     = row.querySelector('.nm-entry-check');
    if (!chk || !chk.checked) continue;

    const inp = row.querySelector('.nm-entry-input');
    if (inp) {
      const userBasename = inp.value.trim();
      if (!userBasename) {
        showInlineError(row, 'Target name is required');
        hasError = true;
        continue;
      }
      const proposedTo = row.dataset.to;
      let to;
      if (proposedTo) {
        to = dirName(proposedTo) + '/' + userBasename;
        to = to.replace(/^\//, '');
      } else {
        const kind = row.dataset.kind;
        to = kind === 'cover' ? userBasename : 'video/' + userBasename;
      }
      collected.push({ from, to });
    } else {
      const to = row.dataset.to;
      if (!to) continue;
      collected.push({ from, to });
    }
  }

  if (hasError) return null;

  const seen = new Set();
  for (const m of collected) {
    const key = m.to.toLowerCase();
    if (seen.has(key)) {
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
