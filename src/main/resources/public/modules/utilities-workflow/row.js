// utilities-workflow/row.js — row builder and cell helpers for the v1 Workflow subtab.
// Forked from modules/v2/workflow/row.js; reskinned to .wf1-* classes.
//
// Exported: makeRow(row, reload) → <tr>

import { esc, humanizeState, openLightbox, openTitleByCode } from './utils.js';
import {
  handlePick, handleAiAssist, handleResolve, handleForceEnrich, handleRefreshCandidates,
} from './actions.js';
import { renderCastAnomalyPanel }       from './cast-anomaly.js';
import { renderOrphanPanel }            from './orphan.js';
import { renderRecodePanel }            from './recode.js';
import { renderSlugConflictPanel }      from './slug-conflict.js';
import { renderStageNameConflictPanel } from './stage-name-conflict.js';

// ── SVG icons ─────────────────────────────────────────────────────────────────

// phi4 — boxy old-school robot (antenna, square head, rectangular eyes)
const ICON_PHI4 = `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
  <line x1="12" y1="2" x2="12" y2="5"/>
  <circle cx="12" cy="1.8" r="0.8" fill="currentColor" stroke="none"/>
  <rect x="5" y="6" width="14" height="13" rx="1"/>
  <rect x="8" y="10" width="3" height="2"/>
  <rect x="13" y="10" width="3" height="2"/>
  <line x1="9" y1="15" x2="15" y2="15"/>
</svg>`;

// gemma3 — rounded modern robot (dome head, circular eyes)
const ICON_GEMMA = `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
  <path d="M5 11a7 7 0 0 1 14 0v5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2z"/>
  <circle cx="9" cy="13" r="1.4" fill="currentColor" stroke="none"/>
  <circle cx="15" cy="13" r="1.4" fill="currentColor" stroke="none"/>
  <line x1="12" y1="4" x2="12" y2="7"/>
</svg>`;

const ICON_X = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><line x1="6" y1="6" x2="18" y2="18"/><line x1="18" y1="6" x2="6" y2="18"/></svg>`;
const ICON_QUESTION = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"/><path d="M9.5 9a2.5 2.5 0 0 1 5 0c0 2-2.5 2-2.5 4"/><line x1="12" y1="17" x2="12" y2="17.5"/></svg>`;

// ── Action availability table ─────────────────────────────────────────────────

function availableActions(row) {
  switch (row.reason) {
    case 'ambiguous':                return ['mark_resolved', 'accept_gap', 'override_slug', 'refresh'];
    case 'cast_anomaly':             return ['mark_resolved'];
    case 'no_match':                 return ['mark_resolved', 'accept_gap', 'override_slug'];
    case 'fetch_failed':             return ['mark_resolved', 'accept_gap', 'override_slug'];
    case 'slug_conflict':            return ['mark_resolved'];
    case 'stage_name_conflict':      return ['mark_resolved'];
    case 'orphan_enriched':          return ['mark_resolved'];
    case 'recode_candidate':         return ['dismiss'];
    case 'actress_rename_candidate': return ['dismiss'];
    default:                         return [];
  }
}

// ── Row builder ───────────────────────────────────────────────────────────────

export function makeRow(row, reload) {
  const tr = document.createElement('tr');
  tr.className = 'wf1-row';
  tr.dataset.id = row.queueId;

  const isCastAnomaly       = row.reason === 'cast_anomaly';
  const isOrphan            = row.reason === 'orphan_enriched';
  const isRecode            = row.reason === 'recode_candidate';
  const isActressRename     = row.reason === 'actress_rename_candidate';
  const isSlugConflict      = row.reason === 'slug_conflict';
  const isStageNameConflict = row.reason === 'stage_name_conflict';

  const stateLabel = humanizeState(row.state, row.reason);
  const stateClass = `wf1-state wf1-state-${esc(row.state || 'other_intervention')}`;

  tr.innerHTML = `
    <td class="wf1-row-code"><span class="wf1-code-pill wf1-link" data-title-code="${esc(row.titleCode || '')}">${esc(row.titleCode || '')}</span></td>
    <td class="wf1-row-actresses"><div class="wf1-actress-chips">${buildActressChips(row)}</div></td>
    <td class="wf1-row-state"><span class="${stateClass}">${esc(stateLabel)}</span></td>
    <td class="wf1-row-cover">${buildTitleCoverHtml(row)}</td>
    <td class="wf1-row-candidates"></td>
    <td class="wf1-row-judges">${buildJudgeVotesHtml(row)}</td>
    <td class="wf1-row-actions">${buildActionsHtml(row)}</td>
  `;

  // Code pill → open title detail in-app (v1 has no /title/{code} route).
  const codeLink = tr.querySelector('.wf1-row-code .wf1-link[data-title-code]');
  if (codeLink && codeLink.dataset.titleCode) {
    codeLink.addEventListener('click', () => openTitleByCode(codeLink.dataset.titleCode));
  }

  // Populate the candidates cell — reason-specific inline panels or standard thumbs.
  const candidatesCell = tr.querySelector('.wf1-row-candidates');
  if (isCastAnomaly) {
    renderCastAnomalyPanel(candidatesCell, row, reload);
  } else if (isOrphan) {
    renderOrphanPanel(candidatesCell, row, reload);
  } else if (isRecode) {
    renderRecodePanel(candidatesCell, row, reload);
  } else if (isActressRename) {
    renderActressRenamePanel(candidatesCell, row, reload);
  } else if (isSlugConflict) {
    renderSlugConflictPanel(candidatesCell, row, reload);
  } else if (isStageNameConflict) {
    renderStageNameConflictPanel(candidatesCell, row, reload);
  } else {
    candidatesCell.innerHTML = buildCandidatesHtml(row);
    // Wire candidate pick buttons.
    candidatesCell.querySelectorAll('.wf1-pick-btn').forEach(btn => {
      btn.addEventListener('click', e => {
        e.stopPropagation();
        handlePick(btn.dataset.rowId, btn.dataset.slug, btn, reload);
      });
    });
    // Wire cover thumb lightbox in candidates.
    candidatesCell.querySelectorAll('[data-fullsrc]').forEach(el => {
      el.addEventListener('click', () => openLightbox(el.dataset.fullsrc));
    });
  }

  // Wire title cover lightbox.
  tr.querySelectorAll('.wf1-cover-preview[data-fullsrc]').forEach(el => {
    el.addEventListener('click', () => openLightbox(el.dataset.fullsrc));
  });

  // Wire AI assist / retry button.
  const aiBtn = tr.querySelector('.wf1-ai-btn');
  if (aiBtn && !aiBtn.disabled) {
    aiBtn.addEventListener('click', () => handleAiAssist(row.queueId, aiBtn, reload));
  }

  // Wire Refresh button (ambiguous rows).
  const refreshBtn = tr.querySelector('.wf1-refresh-btn');
  if (refreshBtn) {
    refreshBtn.addEventListener('click', async () => {
      refreshBtn.disabled = true;
      refreshBtn.textContent = 'Refreshing…';
      try {
        await handleRefreshCandidates(row.queueId, reload);
      } catch (err) {
        console.error('[workflow] refresh failed', err);
        alert(`Refresh failed: ${err.message}`);
        refreshBtn.disabled = false;
        refreshBtn.textContent = 'Refresh';
      }
    });
  }

  // Wire overflow menu button.
  const moreBtn = tr.querySelector('.wf1-actions-more-btn');
  if (moreBtn) {
    moreBtn.addEventListener('click', e => {
      e.stopPropagation();
      toggleActionMenu(moreBtn, row, reload);
    });
  }

  return tr;
}

// ── Cell builders ─────────────────────────────────────────────────────────────

function buildActressChips(row) {
  const names = row.actresses || [];
  if (names.length === 0) return '<span class="wf1-actress-chip">—</span>';
  return names.map(n => `<span class="wf1-actress-chip">${esc(n)}</span>`).join('');
}

function buildTitleCoverHtml(row) {
  if (!row.coverUrl) return '<div class="wf1-cover-thumb wf1-cover-empty"></div>';
  return `<div class="wf1-cover-thumb wf1-cover-preview" data-fullsrc="${esc(row.coverUrl)}"
       style="background-image:url('${esc(row.coverUrl)}')"></div>`;
}

function buildCandidatesHtml(row) {
  if (!row.detail) return '<span class="wf1-candidate-count">—</span>';
  let detail = null;
  try { detail = JSON.parse(row.detail); } catch { return '<span class="wf1-candidate-count">—</span>'; }
  const candidates = detail.candidates || [];
  if (candidates.length === 0) return '<span class="wf1-candidate-count">—</span>';

  const visible = candidates.slice(0, 4);
  const thumbs  = visible.map(c => buildCandidateThumb(c, row)).join('');
  const extra   = candidates.length > 4
    ? `<span class="wf1-candidate-count">+${candidates.length - 4}</span>` : '';
  return `<div class="wf1-candidate-thumbs">${thumbs}${extra}</div>`;
}

function computeCardMod(c, row) {
  const outcome     = row.aiSuggestionConfidence || '';
  const aiPhi4Slug  = row.aiPhi4Slug  || null;
  const aiGemmaSlug = row.aiGemmaSlug || null;
  const anyJudgeVoted = ['agreed', 'phi4_only', 'gemma_only', 'conflict'].includes(outcome)
                        && (aiPhi4Slug || aiGemmaSlug);
  if (!anyJudgeVoted) return null;
  const phi4Voted  = aiPhi4Slug  && c.slug === aiPhi4Slug;
  const gemmaVoted = aiGemmaSlug && c.slug === aiGemmaSlug;
  if (phi4Voted && gemmaVoted) return 'agreed';
  if (phi4Voted)               return 'phi4';
  if (gemmaVoted)              return 'gemma';
  return 'desaturated';
}

function buildCandidateThumb(c, row) {
  const aiPhi4Slug  = row.aiPhi4Slug  || null;
  const aiGemmaSlug = row.aiGemmaSlug || null;
  if (!c.cover_url) {
    return `<div class="wf1-candidate-wrap"><div class="wf1-candidate-thumb wf1-candidate-empty"></div></div>`;
  }
  const phi4Voted  = aiPhi4Slug  && c.slug === aiPhi4Slug;
  const gemmaVoted = aiGemmaSlug && c.slug === aiGemmaSlug;
  let pickMod = '';
  if (phi4Voted && gemmaVoted) pickMod = ' wf1-pick-agreed';
  else if (phi4Voted)          pickMod = ' wf1-pick-phi4';
  else if (gemmaVoted)         pickMod = ' wf1-pick-gemma';
  const cardMod   = computeCardMod(c, row);
  const wrapClass = cardMod ? `wf1-candidate-wrap wf1-card-${cardMod}` : 'wf1-candidate-wrap';
  return `
    <div class="${wrapClass}">
      <div class="wf1-candidate-thumb" data-fullsrc="${esc(c.cover_url)}"
           style="background-image:url('${esc(c.cover_url)}');
           background-position:right center;background-size:200% 100%;"></div>
      <button type="button" class="wf1-pick-btn${pickMod}" data-slug="${esc(c.slug)}" data-row-id="${esc(String(row.queueId))}">Pick</button>
    </div>`;
}

function derivePhi4Status(row) {
  const outcome = row.aiSuggestionConfidence || '';
  if (!row.aiSuggestionAt || outcome === 'error' || !outcome) return 'unknown';
  if (outcome === 'both_abstain' || outcome === 'gemma_only') return 'abstain';
  if (outcome === 'agreed' || outcome === 'phi4_only' || outcome === 'conflict') {
    return row.aiPhi4Slug ? 'vote' : 'unknown';
  }
  return 'unknown';
}

function deriveGemmaStatus(row) {
  const outcome = row.aiSuggestionConfidence || '';
  if (!row.aiSuggestionAt || outcome === 'error' || !outcome) return 'unknown';
  if (outcome === 'both_abstain' || outcome === 'phi4_only') return 'abstain';
  if (outcome === 'agreed' || outcome === 'gemma_only' || outcome === 'conflict') {
    return row.aiGemmaSlug ? 'vote' : 'unknown';
  }
  return 'unknown';
}

function renderStatus(status, judgeKey) {
  if (status === 'vote')    return `<span class="wf1-judge-status-vote wf1-vote-${judgeKey}"></span>`;
  if (status === 'abstain') return `<span class="wf1-judge-status-abstain">${ICON_X}</span>`;
  return `<span class="wf1-judge-status-unknown">${ICON_QUESTION}</span>`;
}

function buildJudgeVotesHtml(row) {
  if (row.state === 'judging')       return '<span class="wf1-ai-pending">judging…</span>';
  if (row.state === 'queued_for_ai') return '<span class="wf1-ai-pending">queued…</span>';
  const phi4Status  = derivePhi4Status(row);
  const gemmaStatus = deriveGemmaStatus(row);
  return `
    <div class="wf1-judge-row">${ICON_PHI4}${renderStatus(phi4Status, 'phi4')}</div>
    <div class="wf1-judge-row">${ICON_GEMMA}${renderStatus(gemmaStatus, 'gemma')}</div>
  `;
}

function buildActionsHtml(row) {
  if (row.state === 'judging' || row.state === 'queued_for_ai') {
    return `<button type="button" class="wf1-ai-btn" disabled>AI Assist</button>`;
  }

  const isAmbiguous = row.reason === 'ambiguous';
  const hasAi       = !!row.aiSuggestionAt;
  const actions     = availableActions(row);

  const aiPart = isAmbiguous
    ? (hasAi
        ? `<button type="button" class="wf1-ai-btn wf1-retry-btn" data-id="${row.queueId}">↻ Retry AI</button>`
        : `<button type="button" class="wf1-ai-btn" data-id="${row.queueId}">AI Assist</button>`)
    : '';

  // Refresh button for ambiguous rows — re-fetches javdb disambiguation candidates.
  const refreshPart = isAmbiguous
    ? `<button type="button" class="wf1-refresh-btn" title="Re-fetch javdb disambiguation">Refresh</button>`
    : '';

  const morePart = actions.length > 0
    ? `<button type="button" class="wf1-actions-more-btn" title="More actions" data-row-id="${row.queueId}">⋮</button>`
    : '';

  if (!aiPart && !refreshPart && !morePart) return '<span style="color:var(--text-faint)">—</span>';
  const stack = (refreshPart || aiPart) ? `<span class="wf1-actions-stack">${refreshPart}${aiPart}</span>` : '';
  return `<span class="wf1-actions-wrap">${stack}${morePart}</span>`;
}

// ── Actress rename panel (inline, simplest reason) ────────────────────────────

function renderActressRenamePanel(container, row, reload) {
  container.innerHTML = '';
  let detail = null;
  try { detail = row.detail ? JSON.parse(row.detail) : null; } catch {}
  const canonical = detail ? (detail.candidate_canonical_name || '') : '';
  const observed  = detail ? (detail.observed_folder_name     || '') : '';

  if (canonical) {
    const note = document.createElement('div');
    note.className = 'wf1-rename-note';
    note.innerHTML = `<b>${esc(canonical)}</b> vs folder <b>${esc(observed)}</b>`;
    container.appendChild(note);
  }

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'wf1-rename-dismiss-btn';
  btn.textContent = 'Dismiss';
  btn.addEventListener('click', async () => {
    btn.disabled = true;
    try {
      await handleResolve(row.queueId, 'dismissed', reload);
    } catch (err) {
      console.error('[workflow] actress-rename dismiss failed', err);
      alert(`Dismiss failed: ${err.message}`);
      btn.disabled = false;
    }
  });
  container.appendChild(btn);
}

// ── Overflow action menu ──────────────────────────────────────────────────────

let _openMenu = null;

export function closeOpenMenu() {
  if (_openMenu) { _openMenu.remove(); _openMenu = null; }
}

// Registered once (module imports are cached). Closes any open menu on outside click.
document.addEventListener('click', closeOpenMenu);

function toggleActionMenu(btn, row, reload) {
  closeOpenMenu();
  const actions = availableActions(row);
  if (actions.length === 0) return;

  const menu = document.createElement('div');
  menu.className = 'wf1-action-menu';

  const labels = {
    mark_resolved: 'Mark resolved',
    accept_gap:    'Accept as gap',
    override_slug: 'Override slug…',
    refresh:       'Refresh candidates',
    dismiss:       'Dismiss',
  };

  const resolutionMap = {
    mark_resolved: 'marked_resolved',
    accept_gap:    'accepted_gap',
    dismiss:       'dismissed',
  };

  for (const id of actions) {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'wf1-action-menu-item';
    item.textContent = labels[id] || id;
    item.addEventListener('click', async e => {
      e.stopPropagation();
      closeOpenMenu();
      if (id === 'override_slug') {
        openOverrideSlugInput(btn, row.queueId, reload);
      } else if (id === 'refresh') {
        try {
          await handleRefreshCandidates(row.queueId, reload);
        } catch (err) {
          alert(`Refresh failed: ${err.message}`);
        }
      } else if (resolutionMap[id]) {
        try {
          await handleResolve(row.queueId, resolutionMap[id], reload);
        } catch (err) {
          alert(`Action failed: ${err.message}`);
        }
      }
    });
    menu.appendChild(item);
  }

  btn.parentElement.appendChild(menu);
  _openMenu = menu;
}

function openOverrideSlugInput(anchorEl, queueId, reload) {
  const existingRow = anchorEl.closest('tr').querySelector('.wf1-override-input-row');
  if (existingRow) { existingRow.remove(); return; }

  const wrap = document.createElement('div');
  wrap.className = 'wf1-override-input-row';

  const input = document.createElement('input');
  input.type = 'text';
  input.placeholder = 'javdb slug…';
  input.className = 'wf1-override-input';

  const applyBtn = document.createElement('button');
  applyBtn.type = 'button';
  applyBtn.textContent = 'Apply';
  applyBtn.className = 'btn sm';

  const cancelBtn = document.createElement('button');
  cancelBtn.type = 'button';
  cancelBtn.textContent = 'Cancel';
  cancelBtn.className = 'btn sm ghost';

  wrap.appendChild(input);
  wrap.appendChild(applyBtn);
  wrap.appendChild(cancelBtn);

  const td = anchorEl.closest('td');
  td.appendChild(wrap);
  input.focus();

  applyBtn.addEventListener('click', async () => {
    const slug = input.value.trim();
    if (!slug) { input.focus(); return; }
    try {
      await handleForceEnrich(queueId, slug, applyBtn, reload);
    } catch (err) {
      const errSpan = wrap.querySelector('.wf1-override-err') || document.createElement('span');
      errSpan.className = 'wf1-override-err';
      errSpan.textContent = err.message;
      wrap.appendChild(errSpan);
    }
  });

  cancelBtn.addEventListener('click', () => wrap.remove());
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter') applyBtn.click();
    if (e.key === 'Escape') wrap.remove();
  });
}
