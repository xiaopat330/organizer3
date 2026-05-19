// v2/workflow/index.js — Enrichment Workflow surface.
// Entry point: call mountWorkflow(rootEl) from the HTML page.

const POLL_INTERVAL_MS = 3000;

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

let _tableBody = null;
let _emptyEl   = null;
let _kpiEl     = null;
let _bulkBtn   = null;
let _pollTimer = null;
let _rows      = [];

export async function mountWorkflow(rootEl) {
  rootEl.innerHTML = `
    <div class="wf-page wb-page">
      <div class="wf-header">
        <span class="wf-header-title wb-section-title">Enrichment Workflow</span>
        <div class="wf-header-kpis" id="wf-kpis"></div>
        <button type="button" class="btn sm" id="wf-bulk-btn" disabled>Queue all ambiguous</button>
      </div>
      <div class="wf-table-wrap wb-table-wrap">
        <table class="wf-table wb-table">
          <thead>
            <tr>
              <th>Code</th>
              <th>Actresses</th>
              <th>State</th>
              <th>Cover</th>
              <th>Candidates</th>
              <th>Judge votes</th>
              <th></th>
            </tr>
          </thead>
          <tbody id="wf-tbody"></tbody>
        </table>
        <div class="wf-empty" id="wf-empty" style="display:none">Nothing in the workflow queue.</div>
      </div>
    </div>
  `;

  _tableBody = rootEl.querySelector('#wf-tbody');
  _emptyEl   = rootEl.querySelector('#wf-empty');
  _kpiEl     = rootEl.querySelector('#wf-kpis');
  _bulkBtn   = rootEl.querySelector('#wf-bulk-btn');

  _bulkBtn.addEventListener('click', handleBulkAssist);

  await reload();
  _pollTimer = setInterval(reload, POLL_INTERVAL_MS);
}

// ── Data loading ──────────────────────────────────────────────────────────────

async function reload() {
  try {
    const res = await fetch('/api/enrichment/workflow/rows?limit=200');
    if (!res.ok) throw new Error(await res.text());
    _rows = await res.json();

    render();
  } catch (err) {
    console.error('[workflow] load failed', err);
  }
}

// ── Rendering ─────────────────────────────────────────────────────────────────

function render() {
  renderKpis();
  renderTable();
}

function renderKpis() {
  if (!_kpiEl) return;
  const total     = _rows.length;
  const decision  = _rows.filter(r => r.state === 'decision').length;
  const suggested = _rows.filter(r => r.state === 'ai_suggested').length;
  const inflight  = _rows.filter(r => r.state === 'in_flight').length;

  const parts = [`<strong>${total}</strong> open`];
  if (decision  > 0) parts.push(`<strong>${decision}</strong> need decision`);
  if (suggested > 0) parts.push(`<strong>${suggested}</strong> AI suggested`);
  if (inflight  > 0) parts.push(`<strong>${inflight}</strong> in flight`);
  _kpiEl.innerHTML = parts.join(' · ');

  // Enable bulk button only when there are ambiguous rows that are not already queued or running.
  const hasAmbiguousAwaitingAi = _rows.some(
    r => r.reason === 'ambiguous' && !r.aiSuggestionAt
         && r.state !== 'ai_queued' && r.state !== 'judging');
  if (_bulkBtn) {
    _bulkBtn.disabled = !hasAmbiguousAwaitingAi;
  }
}

function renderTable() {
  if (!_tableBody || !_emptyEl) return;
  if (_rows.length === 0) {
    _tableBody.innerHTML = '';
    _emptyEl.style.display = '';
    return;
  }
  _emptyEl.style.display = 'none';
  _tableBody.innerHTML = '';
  for (const row of _rows) {
    _tableBody.appendChild(makeRow(row));
  }
}

function makeRow(row) {
  const tr = document.createElement('tr');
  tr.className = 'wf-row';
  tr.dataset.id = row.queueId;

  const codeHtml = `<span class="wf-code-pill">${esc(row.titleCode || '')}</span>`;

  const actressHtml = (row.actresses || []).length === 0
    ? '<span class="wf-actress-chip">—</span>'
    : (row.actresses || []).map(n => `<span class="wf-actress-chip">${esc(n)}</span>`).join('');

  const stateLabel  = humanizeState(row.state);
  const stateClass  = `wf-state wf-state-${esc(row.state || 'decision')}`;

  const titleCoverHtml  = buildTitleCoverHtml(row);
  const candidatesHtml  = buildCandidatesHtml(row);
  const judgeVotesHtml  = buildJudgeVotesHtml(row);
  const actionsHtml     = buildActionsHtml(row);

  tr.innerHTML = `
    <td class="wf-row-code">${codeHtml}</td>
    <td class="wf-row-actresses"><div class="wf-actress-chips">${actressHtml}</div></td>
    <td class="wf-row-state"><span class="${stateClass}">${esc(stateLabel)}</span></td>
    <td class="wf-row-cover">${titleCoverHtml}</td>
    <td class="wf-row-candidates">${candidatesHtml}</td>
    <td class="wf-row-judges">${judgeVotesHtml}</td>
    <td class="wf-row-actions">${actionsHtml}</td>
  `;

  // Wire hover previews: clicking any thumb opens the lightbox.
  tr.querySelectorAll('[data-fullsrc]').forEach(el => {
    el.addEventListener('click', () => openLightbox(el.dataset.fullsrc));
  });

  // Wire AI assist button.
  const aiBtn = tr.querySelector('.wf-ai-btn');
  if (aiBtn) {
    aiBtn.addEventListener('click', () => handleAiAssist(row.queueId, aiBtn));
  }

  // Wire Pick buttons (one per candidate thumb).
  tr.querySelectorAll('.wf-pick-btn').forEach(btn => {
    btn.addEventListener('click', e => {
      e.stopPropagation();
      handlePick(btn.dataset.rowId, btn.dataset.slug, btn);
    });
  });

  // Wire overflow menu button.
  const moreBtn = tr.querySelector('.wf-actions-more-btn');
  if (moreBtn) {
    moreBtn.addEventListener('click', e => {
      e.stopPropagation();
      toggleActionMenu(moreBtn, row);
    });
  }

  return tr;
}

// ── Cell builders ─────────────────────────────────────────────────────────────

function buildTitleCoverHtml(row) {
  if (!row.coverUrl) return '<div class="wf-cover-thumb wf-cover-empty"></div>';
  return `
    <div class="wf-cover-thumb wf-cover-preview" data-fullsrc="${esc(row.coverUrl)}"
         style="background-image:url('${esc(row.coverUrl)}')"></div>`;
}

function buildCandidatesHtml(row) {
  if (!row.detail) return '<span class="wf-candidate-count">—</span>';
  let detail = null;
  try { detail = JSON.parse(row.detail); } catch { return '<span class="wf-candidate-count">—</span>'; }
  const candidates = detail.candidates || [];
  if (candidates.length === 0) return '<span class="wf-candidate-count">—</span>';

  const visible = candidates.slice(0, 4);
  const thumbs = visible.map(c => buildCandidateThumb(c, row)).join('');

  const extra = candidates.length > 4
    ? `<span class="wf-candidate-count">+${candidates.length - 4}</span>` : '';

  return `<div class="wf-candidate-thumbs">${thumbs}${extra}</div>`;
}

// Returns 'agreed' | 'phi4' | 'gemma' | 'desaturated' | null
// null = no judge voted at all (no highlight, no desaturation).
function computeCardMod(c, row) {
  const outcome    = row.aiSuggestionConfidence || '';
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
    return `<div class="wf-candidate-wrap"><div class="wf-candidate-thumb wf-candidate-empty"></div></div>`;
  }

  const phi4Voted  = aiPhi4Slug  && c.slug === aiPhi4Slug;
  const gemmaVoted = aiGemmaSlug && c.slug === aiGemmaSlug;

  // Pick button modifier class based on which judges voted for this candidate.
  let pickMod = '';
  if (phi4Voted && gemmaVoted) pickMod = ' wf-pick-agreed';
  else if (phi4Voted)          pickMod = ' wf-pick-phi4';
  else if (gemmaVoted)         pickMod = ' wf-pick-gemma';

  const cardMod = computeCardMod(c, row);
  const wrapClass = cardMod ? `wf-candidate-wrap wf-card-${cardMod}` : 'wf-candidate-wrap';

  // The right-half crop: background-position right center + background-size 200% shows
  // only the front panel of a DVD cover (JAV covers have back/disc on the left half).
  return `
    <div class="${wrapClass}">
      <div class="wf-candidate-thumb" data-fullsrc="${esc(c.cover_url)}"
           style="background-image:url('${esc(c.cover_url)}');
           background-position:right center;
           background-size:200% 100%;"></div>
      <button type="button" class="wf-pick-btn${pickMod}" data-slug="${esc(c.slug)}" data-row-id="${esc(String(row.queueId))}">Pick</button>
    </div>`;
}

// Returns 'vote' | 'abstain' | 'unknown'
function derivePhi4Status(row) {
  const outcome = row.aiSuggestionConfidence || '';
  if (!row.aiSuggestionAt || outcome === 'error' || !outcome) return 'unknown';
  if (outcome === 'both_abstain' || outcome === 'gemma_only') return 'abstain';
  if (outcome === 'agreed' || outcome === 'phi4_only' || outcome === 'conflict') {
    return row.aiPhi4Slug ? 'vote' : 'unknown';
  }
  return 'unknown';
}

// Returns 'vote' | 'abstain' | 'unknown'
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
  if (status === 'vote') {
    return `<span class="wf-judge-status-vote wf-vote-${judgeKey}"></span>`;
  }
  if (status === 'abstain') {
    return `<span class="wf-judge-status-abstain">${ICON_X}</span>`;
  }
  return `<span class="wf-judge-status-unknown">${ICON_QUESTION}</span>`;
}

function buildJudgeVotesHtml(row) {
  if (row.state === 'judging')   return '<span class="wf-ai-pending">judging…</span>';
  if (row.state === 'ai_queued') return '<span class="wf-ai-pending">queued…</span>';

  const phi4Status  = derivePhi4Status(row);
  const gemmaStatus = deriveGemmaStatus(row);

  return `
    <div class="wf-judge-row">${ICON_PHI4}${renderStatus(phi4Status, 'phi4')}</div>
    <div class="wf-judge-row">${ICON_GEMMA}${renderStatus(gemmaStatus, 'gemma')}</div>
  `;
}

function availableActions(row) {
  switch (row.reason) {
    case 'ambiguous':    return ['mark_resolved', 'accept_gap', 'override_slug'];
    case 'cast_anomaly': return ['mark_resolved'];
    case 'no_match':     return ['mark_resolved', 'accept_gap', 'override_slug'];
    case 'fetch_failed': return ['mark_resolved', 'accept_gap', 'override_slug'];
    case 'slug_conflict':return ['mark_resolved'];
    default:             return [];
  }
}

function buildActionsHtml(row) {
  if (row.state === 'judging' || row.state === 'ai_queued') {
    return `<button type="button" class="wf-ai-btn" disabled>AI Assist</button>`;
  }

  const isAmbiguous = row.reason === 'ambiguous';
  const hasAi       = !!row.aiSuggestionAt;
  const actions     = availableActions(row);

  const aiPart = isAmbiguous
    ? (hasAi
        ? `<button type="button" class="wf-ai-btn wf-retry-btn" data-id="${row.queueId}">↻ Retry AI</button>`
        : `<button type="button" class="wf-ai-btn" data-id="${row.queueId}">AI Assist</button>`)
    : '';

  const morePart = actions.length > 0
    ? `<button type="button" class="wf-actions-more-btn" title="More actions" data-row-id="${row.queueId}">⋮</button>`
    : '';

  if (!aiPart && !morePart) return '<span style="color:var(--text-faint)">—</span>';
  return `<span class="wf-actions-wrap">${aiPart}${morePart}</span>`;
}

// ── Actions ───────────────────────────────────────────────────────────────────

async function handleAiAssist(queueId, btn) {
  btn.disabled = true;
  try {
    const res = await fetch(`/api/enrichment/workflow/${queueId}/ai-assist`, {
      method: 'POST',
    });
    if (!res.ok) {
      console.warn('[workflow] ai-assist failed', await res.text());
    }
    // Server now tracks state; next poll will reflect queued/judging/done.
    await reload();
  } catch (err) {
    console.error('[workflow] ai-assist error', err);
    await reload();
  }
}

async function handleBulkAssist() {
  if (!_bulkBtn) return;
  _bulkBtn.disabled = true;
  try {
    const res = await fetch('/api/enrichment/workflow/ai-assist-all', { method: 'POST' });
    const data = await res.json();
    console.info('[workflow] ai-assist-all queued', data.queued);
    // Server tracks state; next poll will show rows as ai_queued/judging.
    await reload();
  } catch (err) {
    console.error('[workflow] ai-assist-all error', err);
  }
}

async function handlePick(queueRowId, slug, btn) {
  btn.disabled = true;
  btn.textContent = 'Picking…';
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${encodeURIComponent(queueRowId)}/pick`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
    });
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.error || `HTTP ${res.status}`);
    }
    await reload();
  } catch (err) {
    console.error('[workflow] pick failed', err);
    btn.disabled = false;
    btn.textContent = 'Pick';
    alert(`Pick failed: ${err.message}`);
  }
}

// ── Overflow action menu ──────────────────────────────────────────────────────

let _openMenu = null;

function closeOpenMenu() {
  if (_openMenu) { _openMenu.remove(); _openMenu = null; }
}

document.addEventListener('click', closeOpenMenu);

function toggleActionMenu(btn, row) {
  closeOpenMenu();
  const actions = availableActions(row);
  if (actions.length === 0) return;

  const menu = document.createElement('div');
  menu.className = 'wf-action-menu';

  const labels = {
    mark_resolved: 'Mark resolved',
    accept_gap:    'Accept as gap',
    override_slug: 'Override slug…',
  };

  for (const id of actions) {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'wf-action-menu-item';
    item.textContent = labels[id];
    item.addEventListener('click', e => {
      e.stopPropagation();
      closeOpenMenu();
      if (id === 'mark_resolved') handleResolve(row.queueId, 'marked_resolved', btn);
      else if (id === 'accept_gap') handleResolve(row.queueId, 'accepted_gap', btn);
      else if (id === 'override_slug') openOverrideSlugInput(btn, row.queueId);
    });
    menu.appendChild(item);
  }

  // Position below the ⋮ button.
  btn.parentElement.style.position = 'relative';
  btn.parentElement.appendChild(menu);
  _openMenu = menu;
}

function openOverrideSlugInput(anchorEl, queueId) {
  // Remove any existing inline input for this row.
  const existingRow = anchorEl.closest('tr').querySelector('.wf-override-input-row');
  if (existingRow) { existingRow.remove(); return; }

  const wrap = document.createElement('div');
  wrap.className = 'wf-override-input-row';

  const input = document.createElement('input');
  input.type = 'text';
  input.placeholder = 'javdb slug…';
  input.className = 'wf-override-input';

  const applyBtn = document.createElement('button');
  applyBtn.type = 'button';
  applyBtn.textContent = 'Apply';
  applyBtn.className = 'btn sm';

  const cancelBtn = document.createElement('button');
  cancelBtn.type = 'button';
  cancelBtn.textContent = 'Cancel';
  cancelBtn.className = 'btn sm wf-override-cancel';

  wrap.appendChild(input);
  wrap.appendChild(applyBtn);
  wrap.appendChild(cancelBtn);

  // Insert after the actions cell (append to the row's last td).
  const td = anchorEl.closest('td');
  td.appendChild(wrap);
  input.focus();

  applyBtn.addEventListener('click', async () => {
    const slug = input.value.trim();
    if (!slug) { input.focus(); return; }
    applyBtn.disabled = true;
    applyBtn.textContent = '…';
    try {
      const res = await fetch(`/api/utilities/enrichment-review/queue/${encodeURIComponent(queueId)}/force-enrich`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ slug }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.error || `HTTP ${res.status}`);
      }
      await reload();
    } catch (err) {
      console.error('[workflow] override-slug failed', err);
      applyBtn.disabled = false;
      applyBtn.textContent = 'Apply';
      const errSpan = wrap.querySelector('.wf-override-err') || document.createElement('span');
      errSpan.className = 'wf-override-err';
      errSpan.textContent = err.message;
      wrap.appendChild(errSpan);
    }
  });

  cancelBtn.addEventListener('click', () => wrap.remove());
}

async function handleResolve(queueId, resolution, anchorEl) {
  try {
    const res = await fetch(`/api/utilities/enrichment-review/queue/${encodeURIComponent(queueId)}/resolve`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolution }),
    });
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.error || `HTTP ${res.status}`);
    }
    await reload();
  } catch (err) {
    console.error('[workflow] resolve failed', err);
    alert(`Action failed: ${err.message}`);
  }
}

// ── Lightbox ──────────────────────────────────────────────────────────────────

function openLightbox(src) {
  const overlay = document.createElement('div');
  Object.assign(overlay.style, {
    position: 'fixed', inset: '0', background: 'rgba(0,0,0,0.82)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    zIndex: 1000, cursor: 'zoom-out',
  });
  const img = document.createElement('img');
  img.src = src;
  img.style.maxWidth  = '90vw';
  img.style.maxHeight = '90vh';
  img.style.objectFit = 'contain';
  overlay.appendChild(img);
  overlay.addEventListener('click', () => overlay.remove());
  document.body.appendChild(overlay);
}

// ── Utilities ─────────────────────────────────────────────────────────────────

function esc(s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function humanizeState(state) {
  switch (state) {
    case 'in_flight':    return 'In Flight';
    case 'pending':      return 'Pending';
    case 'ai_suggested': return 'AI Suggested';
    case 'decision':     return 'Decision';
    case 'ai_queued':    return 'Queued for AI';
    case 'judging':      return 'Judging';
    default:             return state || 'Unknown';
  }
}
