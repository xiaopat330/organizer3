// utilities-workflow/utils.js — shared utilities for the v1 Workflow subtab.
// Forked from modules/v2/workflow/utils.js, reskinned to v1 (.wf1-* classes).

export function esc(s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

export function humanizeState(state, reason) {
  switch (state) {
    case 'queued':              return 'queued';
    case 'fetching':            return 'fetching';
    case 'ambiguous':           return 'ambiguous';
    case 'queued_for_ai':       return 'queued for AI';
    case 'judging':             return 'judging…';
    case 'split_decision':      return 'split decision';
    case 'partial_vote':        return 'single voter';
    case 'no_verdict':          return 'no verdict';
    case 'other_intervention':  return reason ? reason.replace(/_/g, ' ') : 'needs intervention';
    default:                    return state || 'unknown';
  }
}

// In-app navigation helpers (v1 has no /v2-title-detail.html / /actress/{id} routes).
// Title: fetch by code, then open the shared title-detail overlay.
// Actress: open the shared actress-detail overlay by id.
export async function openTitleByCode(code) {
  if (!code) return;
  try {
    const res = await fetch(`/api/titles/by-code/${encodeURIComponent(code)}`);
    if (!res.ok) return;
    const titleData = await res.json();
    const { openTitleDetail } = await import('../title-detail.js');
    await openTitleDetail(titleData);
  } catch (err) {
    console.error('[workflow] open title failed', code, err);
  }
}

export async function openActressById(id) {
  if (id == null) return;
  try {
    const { openActressDetail } = await import('../actress-detail.js');
    await openActressDetail(Number(id));
  } catch (err) {
    console.error('[workflow] open actress failed', id, err);
  }
}

// Cover/candidate lightbox — simple full-screen zoom overlay.
export function openLightbox(src) {
  if (!src) return;
  const overlay = document.createElement('div');
  overlay.className = 'wf1-lightbox';
  const img = document.createElement('img');
  img.src = src;
  img.className = 'wf1-lightbox-img';
  overlay.appendChild(img);
  overlay.addEventListener('click', () => overlay.remove());
  document.addEventListener('keydown', function onKey(e) {
    if (e.key === 'Escape') { overlay.remove(); document.removeEventListener('keydown', onKey); }
  });
  document.body.appendChild(overlay);
}
