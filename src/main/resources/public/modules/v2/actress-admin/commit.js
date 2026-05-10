// v2 Admin tab — Card Commit / Cancel orchestration.
//
// Mirrors legacy actress-detail-admin/commit.js verbatim.
// Execution order: flag toggles → duplicate decisions → trash → normalize-folder.
// Stops at first failure; remaining stages stay 'pending'.

import * as state from './state.js';

const ORDER = {
  'flag-favorite':    0,
  'flag-bookmark':    0,
  'flag-reject':      0,
  'duplicate-decision': 1,
  'trash-video':      2,
  'trash-cover':      2,
  'normalize-folder': 3,
};

export async function commitCard(code) {
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
      const tdC = state.getCardData(code);
      if (tdC) delete tdC._folderContents;
      return;
    }
    case 'normalize-folder': {
      const { moves } = stage.payload;
      if (!moves || moves.length === 0) return;
      const res = await fetch(`/api/titles/${encodeURIComponent(code)}/apply-moves`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ moves }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.error || `HTTP ${res.status}`);
      }
      const tdN = state.getCardData(code);
      if (tdN) delete tdN._folderContents;
      return;
    }
    default:
      throw new Error(`commit kind not implemented: ${stage.kind}`);
  }
}
