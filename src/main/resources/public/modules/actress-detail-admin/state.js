// Per-page staging state for the Admin tab.
//
// One state instance lives in this module; it's reset whenever the active
// actress or page changes. Cards on the page that have uncommitted edits
// keep their pending stages here keyed by title code.
//
// Stage shape: { kind, payload, status, error? }
//   kind     — string identifier (e.g. 'flag-favorite', 'flag-bookmark',
//              'flag-reject', 'duplicate-decision', 'trash-video',
//              'trash-cover', 'normalize')
//   payload  — kind-specific data the commit step will use to fire the call
//   status   — 'pending' | 'committed' | 'failed'
//   error    — populated when status === 'failed'
//
// Phase 2b ships only the flag-* kinds; the rest land in later phases.
//
// The store is intentionally tiny — no observers / events. Callers that
// need to re-render after mutating state should do so explicitly.

let currentActressId = null;
let cardStates = new Map();   // code -> { stages: Stage[], titleData: object }

export function reset(actressId) {
  currentActressId = actressId;
  cardStates = new Map();
}

export function setCardData(code, titleData) {
  cardStates.set(code, { stages: [], titleData });
}

export function getCardData(code) {
  const s = cardStates.get(code);
  return s ? s.titleData : null;
}

export function getStages(code) {
  const s = cardStates.get(code);
  return s ? s.stages : [];
}

export function getPendingCount(code) {
  return getStages(code).filter(x => x.status === 'pending').length;
}

export function getTotalPendingCount() {
  let n = 0;
  for (const s of cardStates.values()) {
    n += s.stages.filter(x => x.status === 'pending').length;
  }
  return n;
}

export function hasStagedChanges() {
  return getTotalPendingCount() > 0;
}

export function clearStages(code) {
  const s = cardStates.get(code);
  if (s) s.stages = [];
}

// Clears every card's stages without resetting actressId / titleData.
// Used by the navigate-away guard when the user confirms Discard.
export function clearAllStages() {
  for (const s of cardStates.values()) s.stages = [];
}

// Stage lifecycle ────────────────────────────────────────────────────────

// Normalize key: treat null and undefined as null so comparisons are stable.
function normalizeKey(key) {
  return key ?? null;
}

// Match predicate: same kind and same key (null == null).
function stageMatches(stage, kind, key) {
  return stage.kind === kind && stage.key === normalizeKey(key);
}

// Add a stage. Uniqueness is by (kind, key):
//   - key = null (default): behaviour identical to Phase 2 — one stage per kind.
//   - key != null: stages of the same kind with different keys coexist.
// A matching pending stage is replaced (idempotent re-stage).
// Returns the resulting stage.
export function addStage(code, kind, payload, key = null) {
  key = normalizeKey(key);
  const s = cardStates.get(code);
  if (!s) return null;
  const existing = s.stages.findIndex(x => stageMatches(x, kind, key) && x.status === 'pending');
  const stage = { kind, key, payload, status: 'pending' };
  if (existing >= 0) s.stages[existing] = stage;
  else s.stages.push(stage);
  return stage;
}

// Remove the pending stage matching (kind, key) from this card.
// When key is null/undefined, removes the single null-key stage (Phase 2 flag toggles).
export function removePendingStage(code, kind, key = null) {
  key = normalizeKey(key);
  const s = cardStates.get(code);
  if (!s) return;
  s.stages = s.stages.filter(x => !(stageMatches(x, kind, key) && x.status === 'pending'));
}

// Returns the pending stage matching (kind, key), or null.
export function findPendingStage(code, kind, key = null) {
  key = normalizeKey(key);
  return getStages(code).find(x => stageMatches(x, kind, key) && x.status === 'pending') || null;
}

export function markStageCommitted(code, kind, key = null) {
  key = normalizeKey(key);
  const s = cardStates.get(code);
  if (!s) return;
  for (const stage of s.stages) {
    if (stageMatches(stage, kind, key) && stage.status === 'pending') stage.status = 'committed';
  }
}

export function markStageFailed(code, kind, error, key = null) {
  key = normalizeKey(key);
  const s = cardStates.get(code);
  if (!s) return;
  for (const stage of s.stages) {
    if (stageMatches(stage, kind, key) && stage.status === 'pending') {
      stage.status = 'failed';
      stage.error  = error;
    }
  }
}

export function actressId() {
  return currentActressId;
}
