// Card Commit / Cancel orchestration — Phase 3: adds duplicate-decision kind.
//
// Commit all pending stages on a card, in the order specified by §4.5
// of PROPOSAL_ACTRESS_TITLE_ADMIN.md:
//   1. flag toggles (favorite, bookmark, reject)
//   2. duplicate decisions
//   3. folder-content actions (trash, then rename/move) — Phase 4/5
//
// Stops at the first failure; later stages stay 'pending'. Returns
// { committed, failed, remaining } counts so callers can re-render.

import * as state from './state.js';

// Execution order by kind: lower value fires first.
const ORDER = {
  'flag-favorite': 0,
  'flag-bookmark': 0,
  'flag-reject':   0,
  'duplicate-decision': 1,
  'trash-video':   2,
  'trash-cover':   2,
};

export async function commitCard(code) {
  // Sort by execution order; preserve insertion order within the same bucket.
  const stages = state.getStages(code)
    .filter(s => s.status === 'pending')
    .sort((a, b) => (ORDER[a.kind] ?? 99) - (ORDER[b.kind] ?? 99));

  let committed = 0, failed = 0;

  for (const stage of stages) {
    try {
      await fireStage(code, stage);
      state.markStageCommitted(code, stage.kind, stage.key);
      committed++;
    } catch (err) {
      state.markStageFailed(code, stage.kind, err.message || String(err), stage.key);
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
    case 'duplicate-decision': {
      const { volumeId, nasPath, decision } = stage.payload;
      if (decision === null) {
        // User staged "clear my prior decision" — fire a DELETE.
        const url = `/api/tools/duplicates/decisions/${encodeURIComponent(code)}/${encodeURIComponent(volumeId)}?nasPath=${encodeURIComponent(nasPath)}`;
        const res = await fetch(url, { method: 'DELETE' });
        if (!res.ok) {
          const body = await res.json().catch(() => ({}));
          throw new Error(body.error || `HTTP ${res.status}`);
        }
      } else {
        const res = await fetch('/api/tools/duplicates/decisions', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ titleCode: code, volumeId, nasPath, decision }),
        });
        if (!res.ok) {
          const body = await res.json().catch(() => ({}));
          throw new Error(body.error || `HTTP ${res.status}`);
        }
      }
      // Invalidate cached decisions so the next render re-fetches from server.
      const td = state.getCardData(code);
      if (td) td._dupDecisions = null;
      return;
    }
    case 'trash-video': {
      const { filename } = stage.payload;
      const res = await fetch(
        `/api/titles/${encodeURIComponent(code)}/videos/${encodeURIComponent(filename)}/trash`,
        { method: 'POST' },
      );
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.error || `HTTP ${res.status}`);
      }
      // Invalidate folder-contents cache so the next render re-fetches.
      const tdV = state.getCardData(code);
      if (tdV) delete tdV._folderContents;
      return;
    }
    case 'trash-cover': {
      const { filename } = stage.payload;
      const res = await fetch(
        `/api/titles/${encodeURIComponent(code)}/covers/${encodeURIComponent(filename)}/trash`,
        { method: 'POST' },
      );
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.error || `HTTP ${res.status}`);
      }
      // Invalidate folder-contents cache so the next render re-fetches.
      const tdC = state.getCardData(code);
      if (tdC) delete tdC._folderContents;
      return;
    }
    default:
      throw new Error(`commit kind not implemented in this phase: ${stage.kind}`);
  }
}
