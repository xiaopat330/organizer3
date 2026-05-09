// Card Commit / Cancel orchestration — Phase 2b stub.
//
// Phase 2c wires real Commit / Cancel buttons; Phase 2e wires the
// navigate-away confirm modal. For now we expose the function shapes so
// other modules can import a stable surface.

import * as state from './state.js';

// Commit all pending stages on a card, in the order specified by §4.5
// of PROPOSAL_ACTRESS_TITLE_ADMIN.md:
//   1. flag toggles (favorite, bookmark, reject)
//   2. duplicate decisions
//   3. folder-content actions (trash, then rename/move)
//
// Stops at the first failure; later stages stay 'pending'. Returns
// { committed, failed, remaining } counts so callers can re-render.
//
// Phase 2b ships flag-* commit only — other kinds become real in 2d/3/4/5.
export async function commitCard(code) {
  const stages = state.getStages(code).filter(s => s.status === 'pending');
  let committed = 0, failed = 0;

  for (const stage of stages) {
    try {
      await fireStage(code, stage);
      state.markStageCommitted(code, stage.kind);
      committed++;
    } catch (err) {
      state.markStageFailed(code, stage.kind, err.message || String(err));
      failed++;
      break;  // halt; remaining stages stay pending
    }
  }

  const remaining = state.getPendingCount(code);
  return { committed, failed, remaining };
}

export function cancelCard(code) {
  state.clearStages(code);
}

// Fire a single stage. Throws on non-2xx so commitCard can mark failed.
async function fireStage(code, stage) {
  switch (stage.kind) {
    case 'flag-favorite':
    case 'flag-bookmark':
    case 'flag-reject': {
      const path = stage.kind.replace('flag-', '');
      const res = await fetch(`/api/titles/${encodeURIComponent(code)}/${path}`, { method: 'POST' });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.error || `HTTP ${res.status}`);
      }
      const data = await res.json();
      // Update titleData so re-render reflects new server state without a fetch.
      const td = state.getCardData(code);
      if (td) {
        td.favorite = data.favorite;
        td.bookmark = data.bookmark;
        td.rejected = data.rejected;
      }
      return data;
    }
    default:
      throw new Error(`commit kind not implemented in this phase: ${stage.kind}`);
  }
}
